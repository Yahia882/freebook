package com.example.freebook.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import com.example.freebook.model.Book
import com.example.freebook.model.CoverStyle
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

object EpubParser {

    fun parseEpubFromUri(context: Context, uri: Uri): Book? {
        val fileName = getFileName(context, uri) ?: "Imported Book.epub"
        val fallbackTitle = fileName
            .removeSuffix(".epub")
            .removeSuffix(".EPUB")
            .removeSuffix(".txt")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "Imported Book" }

        return try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null

            // Check if it's a ZIP/EPUB archive (starts with 'PK')
            if (rawBytes.size >= 4 && rawBytes[0] == 'P'.code.toByte() && rawBytes[1] == 'K'.code.toByte()) {
                parseZipEpub(context, rawBytes, fallbackTitle)
            } else {
                // Plain text fallback
                val text = rawBytes.toString(Charsets.UTF_8)
                val pages = paginateText(text).ifEmpty {
                    listOf("Imported book: $fallbackTitle\n\nNo readable text blocks were found in this file.")
                }
                Book(
                    title = fallbackTitle,
                    author = "Imported Edition",
                    coverStyle = CoverStyle.DEFAULT_EDITORIAL,
                    pages = pages,
                    displayTotalPages = pages.size
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val fallbackPages = listOf(
                "$fallbackTitle\n\nThis EPUB has been imported into your BookFree library. Swipe left or right to read and tap anywhere on the page to open the Active Reader controls, highlight passages, or take page-synced notes."
            )
            Book(
                title = fallbackTitle,
                author = "Imported Edition",
                coverStyle = CoverStyle.DEFAULT_EDITORIAL,
                pages = fallbackPages,
                displayTotalPages = fallbackPages.size
            )
        }
    }

    private fun parseZipEpub(context: Context, zipBytes: ByteArray, fallbackTitle: String): Book {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                    entries[normalizedName] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 1. Find OPF path via META-INF/container.xml or scan for .opf
        val opfPath = findOpfPath(entries) ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        val opfDir = opfPath?.substringBeforeLast('/', "") ?: ""

        var title: String? = null
        var author: String? = null
        val manifestHrefById = LinkedHashMap<String, String>()
        val manifestMediaById = LinkedHashMap<String, String>()
        val spineIds = mutableListOf<String>()
        var coverId: String? = null

        if (opfPath != null && entries.containsKey(opfPath)) {
            val opfBytes = entries[opfPath]!!
            try {
                val parser = XmlPullParserFactory.newInstance().apply {
                    isNamespaceAware = false
                }.newPullParser()
                parser.setInput(ByteArrayInputStream(opfBytes), "UTF-8")

                var eventType = parser.eventType
                var currentTag = ""
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name.substringAfter(':')
                            when (currentTag.lowercase()) {
                                "item" -> {
                                    val id = parser.getAttributeValue(null, "id")
                                    val href = parser.getAttributeValue(null, "href")
                                    val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                                    val properties = parser.getAttributeValue(null, "properties") ?: ""
                                    if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                        manifestHrefById[id] = href
                                        manifestMediaById[id] = mediaType
                                        if (properties.contains("cover-image", ignoreCase = true)) {
                                            coverId = id
                                        }
                                    }
                                }
                                "itemref" -> {
                                    val idref = parser.getAttributeValue(null, "idref")
                                    if (!idref.isNullOrBlank()) {
                                        spineIds.add(idref)
                                    }
                                }
                                "meta" -> {
                                    val metaName = parser.getAttributeValue(null, "name")
                                    val metaContent = parser.getAttributeValue(null, "content")
                                    if (metaName.equals("cover", ignoreCase = true) && !metaContent.isNullOrBlank()) {
                                        coverId = metaContent
                                    }
                                }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val textVal = parser.text?.trim().orEmpty()
                            if (textVal.isNotEmpty()) {
                                when (currentTag.lowercase()) {
                                    "title" -> if (title.isNullOrBlank()) title = textVal
                                    "creator", "author" -> if (author.isNullOrBlank()) author = textVal
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            currentTag = ""
                        }
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Try to extract cover image if present
        val savedCoverPath = extractCoverImage(
            context = context,
            entries = entries,
            opfDir = opfDir,
            coverId = coverId,
            manifestHrefById = manifestHrefById,
            manifestMediaById = manifestMediaById
        )

        // 3. Collect ordered HTML/XHTML files from spine (or fallback to all html/xhtml entries)
        val chapterPaths = mutableListOf<String>()
        for (idref in spineIds) {
            val href = manifestHrefById[idref] ?: continue
            val resolved = resolveRelativePath(opfDir, href)
            if (entries.containsKey(resolved)) {
                chapterPaths.add(resolved)
            } else {
                val decoded = Uri.decode(resolved)
                if (entries.containsKey(decoded)) {
                    chapterPaths.add(decoded)
                }
            }
        }

        if (chapterPaths.isEmpty()) {
            entries.keys
                .filter {
                    it.endsWith(".xhtml", ignoreCase = true) ||
                        it.endsWith(".html", ignoreCase = true) ||
                        it.endsWith(".htm", ignoreCase = true)
                }
                .sorted()
                .forEach { chapterPaths.add(it) }
        }

        val allPages = mutableListOf<String>()
        for (path in chapterPaths) {
            val bytes = entries[path] ?: continue
            val rawHtml = bytes.toString(Charsets.UTF_8)
            val cleanedText = htmlToCleanText(rawHtml)
            if (cleanedText.length > 30) {
                allPages.addAll(paginateText(cleanedText))
            }
        }

        val finalTitle = title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val finalAuthor = author?.takeIf { it.isNotBlank() } ?: "Classical Press"
        if (allPages.isEmpty()) {
            allPages.add(
                "$finalTitle\nby $finalAuthor\n\nImported from EPUB. Begin reading, highlighting key thoughts, and adding page-synced notes."
            )
        }

        return Book(
            title = finalTitle,
            author = finalAuthor,
            coverStyle = CoverStyle.DEFAULT_EDITORIAL,
            coverImagePath = savedCoverPath,
            pages = allPages,
            displayTotalPages = allPages.size
        )
    }

    private fun findOpfPath(entries: Map<String, ByteArray>): String? {
        val containerBytes = entries["META-INF/container.xml"]
            ?: entries.entries.firstOrNull { it.key.equals("META-INF/container.xml", ignoreCase = true) }?.value
            ?: return null

        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(ByteArrayInputStream(containerBytes), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.endsWith("rootfile", ignoreCase = true)) {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (!fullPath.isNullOrBlank()) {
                        return fullPath.trimStart('/')
                    }
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractCoverImage(
        context: Context,
        entries: Map<String, ByteArray>,
        opfDir: String,
        coverId: String?,
        manifestHrefById: Map<String, String>,
        manifestMediaById: Map<String, String>
    ): String? {
        var candidatePath: String? = null

        if (!coverId.isNullOrBlank()) {
            val href = manifestHrefById[coverId]
            if (!href.isNullOrBlank()) {
                candidatePath = resolveRelativePath(opfDir, href)
            } else if (entries.containsKey(resolveRelativePath(opfDir, coverId))) {
                candidatePath = resolveRelativePath(opfDir, coverId)
            }
        }

        if (candidatePath == null) {
            // Look for manifest item with image media type and "cover" in id or href
            val coverEntry = manifestHrefById.entries.firstOrNull { (id, href) ->
                val media = manifestMediaById[id].orEmpty()
                val isImage = media.startsWith("image/", ignoreCase = true) ||
                    href.endsWith(".jpg", true) ||
                    href.endsWith(".jpeg", true) ||
                    href.endsWith(".png", true) ||
                    href.endsWith(".webp", true)
                isImage && (id.contains("cover", ignoreCase = true) || href.contains("cover", ignoreCase = true))
            }
            if (coverEntry != null) {
                candidatePath = resolveRelativePath(opfDir, coverEntry.value)
            }
        }

        if (candidatePath == null) {
            candidatePath = entries.keys.firstOrNull { key ->
                val lower = key.lowercase()
                lower.contains("cover") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))
            }
        }

        val imageBytes = candidatePath?.let { entries[it] ?: entries[Uri.decode(it)] } ?: return null
        if (imageBytes.isEmpty()) return null

        return try {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val outFile = File(coversDir, "cover_${UUID.randomUUID()}.img")
            FileOutputStream(outFile).use { it.write(imageBytes) }
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun copyCustomCoverImage(context: Context, uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.isEmpty()) return null
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val outFile = File(coversDir, "custom_cover_${UUID.randomUUID()}.img")
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveRelativePath(baseDir: String, relativeHref: String): String {
        val cleanHref = relativeHref.substringBefore('#').trimStart('/')
        if (baseDir.isBlank()) return cleanHref
        val combined = "$baseDir/$cleanHref"
        val segments = mutableListOf<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(part)
            }
        }
        return segments.joinToString("/")
    }

    private fun htmlToCleanText(html: String): String {
        val withoutHeadAndStyles = html
            .replace(Regex("(?is)<head.*?>.*?</head>"), "")
            .replace(Regex("(?is)<style.*?>.*?</style>"), "")
            .replace(Regex("(?is)<script.*?>.*?</script>"), "")
        val spanned = HtmlCompat.fromHtml(withoutHeadAndStyles, HtmlCompat.FROM_HTML_MODE_LEGACY)
        return spanned.toString()
            .replace("\u00A0", " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun paginateText(text: String, maxCharsPerPage: Int = 920): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()

        val paragraphs = cleaned.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val pages = mutableListOf<String>()
        val currentBuilder = StringBuilder()

        for (para in paragraphs) {
            if (currentBuilder.isNotEmpty() && currentBuilder.length + para.length + 2 > maxCharsPerPage) {
                pages.add(currentBuilder.toString().trim())
                currentBuilder.clear()
            }

            if (para.length > maxCharsPerPage) {
                // Split long paragraph by sentences
                val sentences = para.split(Regex("(?<=[.!?])\\s+"))
                for (sentence in sentences) {
                    if (currentBuilder.isNotEmpty() && currentBuilder.length + sentence.length + 1 > maxCharsPerPage) {
                        pages.add(currentBuilder.toString().trim())
                        currentBuilder.clear()
                    }
                    if (currentBuilder.isNotEmpty()) currentBuilder.append(" ")
                    currentBuilder.append(sentence)
                }
            } else {
                if (currentBuilder.isNotEmpty()) currentBuilder.append("\n\n")
                currentBuilder.append(para)
            }
        }

        if (currentBuilder.isNotEmpty()) {
            pages.add(currentBuilder.toString().trim())
        }

        return pages
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
}
