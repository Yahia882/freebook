package com.example.freebook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import com.example.freebook.data.storage.BookFileStorage
import com.example.freebook.data.storage.ContentIdentityHasher
import com.example.freebook.model.StagedBookImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

object EpubParser {

    suspend fun stageBookFromUri(
        context: Context,
        uri: Uri,
        fileStorage: BookFileStorage
    ): StagedBookImport? = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri) ?: "Imported Book.epub"
        val extension = fileName.substringAfterLast('.', "epub").lowercase()
        val fallbackTitle = fileName
            .removeSuffix(".epub")
            .removeSuffix(".EPUB")
            .removeSuffix(".pdf")
            .removeSuffix(".PDF")
            .removeSuffix(".txt")
            .removeSuffix(".TXT")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "Imported Book" }

        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        if (rawBytes.isEmpty()) return@withContext null

        val savedRawPath = fileStorage.saveRawBookFile(rawBytes, extension)

        try {
            val isZip = rawBytes.size >= 4 &&
                rawBytes[0] == 'P'.code.toByte() &&
                rawBytes[1] == 'K'.code.toByte()
            val isPdf = rawBytes.size >= 4 &&
                rawBytes[0] == '%'.code.toByte() &&
                rawBytes[1] == 'P'.code.toByte() &&
                rawBytes[2] == 'D'.code.toByte() &&
                rawBytes[3] == 'F'.code.toByte()

            val parsed: ParsedBookPayload = when {
                isZip -> parseZipEpubPayload(rawBytes, fallbackTitle, fileStorage)
                isPdf -> parsePdfPayload(savedRawPath, rawBytes, fallbackTitle, fileStorage)
                else -> {
                    val text = rawBytes.toString(Charsets.UTF_8).trim().ifBlank {
                        "$fallbackTitle\n\nImported plain-text edition."
                    }
                    ParsedBookPayload(
                        title = fallbackTitle,
                        author = "Imported Edition",
                        fullText = text,
                        coverImagePath = null
                    )
                }
            }

            val normalizedContent = ContentIdentityHasher.normalizeForHash(parsed.fullText)
                .ifBlank { ContentIdentityHasher.normalizeForHash("${parsed.title} ${parsed.author}") }
            val contentHash = ContentIdentityHasher.sha256Hex(normalizedContent)
            val textFilePath = fileStorage.saveExtractedTextFile(parsed.fullText)
            val estimatedPages = (parsed.fullText.length / 750).coerceAtLeast(1)

            StagedBookImport(
                title = parsed.title,
                author = parsed.author,
                normalizedTitle = ContentIdentityHasher.normalizeMetadataField(parsed.title),
                normalizedAuthor = ContentIdentityHasher.normalizeMetadataField(parsed.author),
                contentHash = contentHash,
                rawFilePath = savedRawPath,
                textContentFilePath = textFilePath,
                coverImagePath = parsed.coverImagePath,
                estimatedTotalPages = estimatedPages,
                normalizedCharLength = normalizedContent.length
            )
        } catch (e: Exception) {
            fileStorage.deleteBookFiles(savedRawPath, null, null)
            null
        }
    }

    suspend fun copyCustomCoverImage(
        context: Context,
        uri: Uri,
        fileStorage: BookFileStorage
    ): String? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        fileStorage.saveCoverImageBytes(bytes)
    }

    private data class ParsedBookPayload(
        val title: String,
        val author: String,
        val fullText: String,
        val coverImagePath: String?
    )

    private suspend fun parseZipEpubPayload(
        zipBytes: ByteArray,
        fallbackTitle: String,
        fileStorage: BookFileStorage
    ): ParsedBookPayload {
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

        val opfPath = findOpfPath(entries)
            ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
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
            } catch (_: Exception) {
            }
        }

        val savedCoverPath = extractCoverImage(
            entries = entries,
            opfDir = opfDir,
            coverId = coverId,
            manifestHrefById = manifestHrefById,
            manifestMediaById = manifestMediaById,
            fileStorage = fileStorage
        )

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

        val chapterTexts = mutableListOf<String>()
        for (path in chapterPaths) {
            val bytes = entries[path] ?: continue
            val rawHtml = bytes.toString(Charsets.UTF_8)
            val cleanedText = htmlToCleanText(rawHtml)
            if (cleanedText.length > 20) {
                chapterTexts.add(cleanedText)
            }
        }

        val finalTitle = title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val finalAuthor = author?.takeIf { it.isNotBlank() } ?: "Unknown Author"
        val combinedText = chapterTexts.joinToString("\n\n").ifBlank {
            "$finalTitle\nby $finalAuthor\n\nImported from EPUB."
        }

        return ParsedBookPayload(
            title = finalTitle,
            author = finalAuthor,
            fullText = combinedText,
            coverImagePath = savedCoverPath
        )
    }

    private suspend fun parsePdfPayload(
        savedRawPath: String,
        rawBytes: ByteArray,
        fallbackTitle: String,
        fileStorage: BookFileStorage
    ): ParsedBookPayload {
        var coverPath: String? = null
        var pageCount = 1
        try {
            val file = File(savedRawPath)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount.coerceAtLeast(1)
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { firstPage ->
                            val bmp = Bitmap.createBitmap(
                                firstPage.width.coerceIn(200, 800),
                                firstPage.height.coerceIn(280, 1120),
                                Bitmap.Config.ARGB_8888
                            )
                            Canvas(bmp).drawColor(Color.WHITE)
                            firstPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val out = ByteArrayOutputStream()
                            @Suppress("DEPRECATION")
                            bmp.compress(Bitmap.CompressFormat.WEBP, 85, out)
                            bmp.recycle()
                            coverPath = fileStorage.saveCoverImageBytes(out.toByteArray())
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Extract printable text literals from PDF stream or provide structured page text
        val extractedLiterals = extractPrintableTextFromPdfBytes(rawBytes)
        val fullText = extractedLiterals.ifBlank {
            "$fallbackTitle\n\nImported PDF Document ($pageCount pages)."
        }
        return ParsedBookPayload(
            title = fallbackTitle,
            author = "PDF Document",
            fullText = fullText,
            coverImagePath = coverPath
        )
    }

    private fun extractPrintableTextFromPdfBytes(bytes: ByteArray): String {
        val rawLatin = String(bytes, Charsets.ISO_8859_1)
        val parenMatches = Regex("\\(([^()\\\\]{4,200})\\)")
            .findAll(rawLatin)
            .map { it.groupValues[1].trim() }
            .filter { token ->
                token.count { ch -> ch.isLetterOrDigit() || ch == ' ' } > token.length * 0.85
            }
            .toList()
        return parenMatches.joinToString(" ").trim()
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
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractCoverImage(
        entries: Map<String, ByteArray>,
        opfDir: String,
        coverId: String?,
        manifestHrefById: Map<String, String>,
        manifestMediaById: Map<String, String>,
        fileStorage: BookFileStorage
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
                lower.contains("cover") && (
                    lower.endsWith(".jpg") ||
                        lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") ||
                        lower.endsWith(".webp")
                    )
            }
        }

        val imageBytes = candidatePath?.let { entries[it] ?: entries[Uri.decode(it)] } ?: return null
        return fileStorage.saveCoverImageBytes(imageBytes)
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
