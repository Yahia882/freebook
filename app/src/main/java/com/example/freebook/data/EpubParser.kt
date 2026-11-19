package com.example.freebook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import com.example.freebook.data.storage.BookFileStorage
import com.example.freebook.data.storage.ContentIdentityHasher
import com.example.freebook.model.Book
import com.example.freebook.model.BookReadingDirection
import com.example.freebook.model.PublicationLayoutType
import com.example.freebook.model.StagedBookImport
import com.example.freebook.ui.reader.ReadiumTocEntry
import com.example.freebook.ui.reader.ReadiumWebViewNavigator
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

object EpubParser {

    const val UNSUPPORTED_FORMAT_MESSAGE = "Unsupported format. Please import an EPUB or PDF file."
    private val supportedExtensions = setOf("epub", "pdf")
    private val rtlLanguagePrefixes = setOf("ar", "he", "iw", "fa", "ur", "yi", "ps", "sd", "ug")

    /**
     * Resolves the file extension for [uri] and returns `"epub"` or `"pdf"` if strictly supported,
     * or `null` if the file has an unsupported extension or no extension at all.
     */
    suspend fun resolveSupportedExtension(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val fileName = getFileName(context, uri)?.trim()?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            extractSupportedExtension(fileName)
        }

    private fun extractSupportedExtension(fileName: String): String? {
        val cleanName = fileName.trim()
        val dotIndex = cleanName.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == cleanName.lastIndex) return null
        val ext = cleanName.substring(dotIndex + 1).trim().lowercase(Locale.ROOT)
        return ext.takeIf { it in supportedExtensions }
    }

    suspend fun stageBookFromUri(
        context: Context,
        uri: Uri,
        fileStorage: BookFileStorage
    ): StagedBookImport? = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri)?.trim()?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        val extension = extractSupportedExtension(fileName)
            ?: return@withContext null
        val fallbackTitle = fileName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "Imported Book" }

        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        if (rawBytes.isEmpty()) return@withContext null

        val savedRawPath = fileStorage.saveRawBookFile(rawBytes, extension)

        try {
            val parsed: ParsedBookPayload = if (extension == "pdf") {
                parsePdfWithPdfium(
                    context = context.applicationContext,
                    savedRawPath = savedRawPath,
                    fallbackTitle = fallbackTitle,
                    fileStorage = fileStorage
                )
            } else {
                parseWithReadiumOrFallback(
                    context = context.applicationContext,
                    savedRawPath = savedRawPath,
                    rawBytes = rawBytes,
                    extension = extension,
                    fallbackTitle = fallbackTitle,
                    fileStorage = fileStorage
                )
            }

            val normalizedContent = ContentIdentityHasher.normalizeForHash(parsed.fullText)
                .ifBlank { ContentIdentityHasher.normalizeForHash("${parsed.title} ${parsed.author}") }
            val contentHash = if (parsed.isBinaryFixedDocument) {
                ContentIdentityHasher.sha256BytesHex(rawBytes)
            } else {
                ContentIdentityHasher.sha256Hex(normalizedContent)
            }
            val textFilePath = fileStorage.saveExtractedTextFile(parsed.fullText)
            val estimatedPages = (parsed.explicitPageCount ?: (parsed.fullText.length / 900)).coerceAtLeast(1)

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
                normalizedCharLength = normalizedContent.length,
                layoutType = parsed.layoutType,
                readingDirection = parsed.readingDirection,
                language = parsed.language
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

    private data class SpineChapterSlice(
        val startOffset: Int,
        val endOffset: Int,
        val rawHtml: String
    )

    private fun normalizeCanonicalSegment(raw: String): String =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /**
     * Opens the book's Readium [Publication] on [Dispatchers.IO], retrieves `publication.tableOfContents`,
     * and resolves each chapter [org.readium.r2.shared.publication.Link] to a Readium
     * [org.readium.r2.shared.publication.Locator] mapped to its canonical character offset.
     */
    @OptIn(ExperimentalReadiumApi::class)
    suspend fun loadReflowableTableOfContents(
        context: Context,
        book: Book,
        canonicalBookText: String
    ): List<ReadiumTocEntry> = withContext(Dispatchers.IO) {
        if (book.bookFilePath.isBlank()) return@withContext emptyList()
        val bookFile = File(book.bookFilePath)
        if (!bookFile.exists()) return@withContext emptyList()

        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(
            contentResolver = context.contentResolver,
            httpClient = httpClient
        )
        val publicationOpener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context = context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null
            )
        )

        val asset = assetRetriever.retrieve(bookFile.toUrl()).getOrNull() ?: return@withContext emptyList()
        val publication = publicationOpener.open(asset, allowUserInteraction = false).getOrNull()
        if (publication == null) {
            runCatching { asset.close() }
            return@withContext emptyList()
        }

        try {
            val rawTocLinks = publication.tableOfContents
            if (rawTocLinks.isEmpty()) return@withContext emptyList()

            val slicesByHref = LinkedHashMap<String, SpineChapterSlice>()
            var rollingOffset = 0
            val totalLen = canonicalBookText.length

            for (spineLink in publication.readingOrder) {
                val resource = publication.get(spineLink) ?: continue
                val rawBytes = resource.use { res -> res.read().getOrNull() } ?: continue
                if (rawBytes.isEmpty()) continue

                val rawHtml = rawBytes.toString(Charsets.UTF_8)
                val cleaned = htmlToCleanText(rawHtml)
                val normalized = normalizeCanonicalSegment(cleaned)

                val sliceStart: Int
                val sliceEnd: Int
                if (normalized.length > 15 && totalLen > 0) {
                    val probe = normalized.take(80)
                    val matchedStart = canonicalBookText.indexOf(probe, startIndex = rollingOffset)
                        .takeIf { it >= 0 }
                        ?: rollingOffset.coerceIn(0, (totalLen - 1).coerceAtLeast(0))
                    sliceStart = matchedStart
                    sliceEnd = (sliceStart + normalized.length).coerceAtMost(totalLen)
                    rollingOffset = (sliceEnd + 2).coerceAtMost(totalLen)
                } else {
                    sliceStart = rollingOffset.coerceIn(0, (totalLen - 1).coerceAtLeast(0))
                    sliceEnd = sliceStart
                }

                val slice = SpineChapterSlice(
                    startOffset = sliceStart,
                    endOffset = sliceEnd,
                    rawHtml = rawHtml
                )

                val locatorHrefKey = publication.locatorFromLink(spineLink)?.href?.toString()
                if (!locatorHrefKey.isNullOrBlank()) {
                    slicesByHref[locatorHrefKey] = slice
                    slicesByHref[Uri.decode(locatorHrefKey)] = slice
                }
                val cleanHrefKey = spineLink.href.toString().substringBefore('#').trimStart('/')
                if (cleanHrefKey.isNotEmpty()) {
                    slicesByHref[cleanHrefKey] = slice
                    slicesByHref[Uri.decode(cleanHrefKey)] = slice
                }
            }

            ReadiumWebViewNavigator.flattenTableOfContentsLinks(
                publication = publication,
                links = rawTocLinks,
                totalChars = totalLen,
                resolveCharOffset = { tocLink, baseLocator ->
                    val locatorHref = baseLocator?.href?.toString().orEmpty()
                    val cleanLinkHref = tocLink.href.toString().substringBefore('#').trimStart('/')
                    val slice = slicesByHref[locatorHref]
                        ?: slicesByHref[Uri.decode(locatorHref)]
                        ?: slicesByHref[cleanLinkHref]
                        ?: slicesByHref[Uri.decode(cleanLinkHref)]
                        ?: slicesByHref.entries.firstOrNull { (k, _) ->
                            cleanLinkHref.isNotEmpty() && (k.endsWith("/$cleanLinkHref") || cleanLinkHref.endsWith("/$k"))
                        }?.value

                    var resolved = slice?.startOffset ?: 0
                    val fragmentId = baseLocator?.locations?.fragments?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: tocLink.href.toString().substringAfter('#', "").takeIf { it.isNotBlank() }

                    if (fragmentId != null && slice != null && totalLen > 0) {
                        val decodedFrag = Uri.decode(fragmentId)
                        val fragPattern = Regex(
                            """(?:id|name)\s*=\s*["']${Regex.escape(decodedFrag)}["']""",
                            RegexOption.IGNORE_CASE
                        )
                        val match = fragPattern.find(slice.rawHtml)
                        var refined = false
                        if (match != null) {
                            val snippet = normalizeCanonicalSegment(
                                htmlToCleanText(slice.rawHtml.substring(match.range.first))
                            ).lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
                            if (!snippet.isNullOrBlank()) {
                                val foundIdx = canonicalBookText.indexOf(snippet, startIndex = slice.startOffset)
                                if (foundIdx in slice.startOffset..slice.endOffset) {
                                    resolved = foundIdx
                                    refined = true
                                }
                            }
                        }
                        if (!refined && !tocLink.title.isNullOrBlank()) {
                            val titleProbe = tocLink.title!!.trim()
                            val foundByTitle = canonicalBookText.indexOf(
                                titleProbe,
                                startIndex = slice.startOffset,
                                ignoreCase = true
                            )
                            if (foundByTitle in slice.startOffset..slice.endOffset) {
                                resolved = foundByTitle
                            }
                        }
                    }

                    resolved.coerceIn(0, (totalLen - 1).coerceAtLeast(0))
                }
            )
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { publication.close() }
        }
    }

    private data class ParsedBookPayload(
        val title: String,
        val author: String,
        val fullText: String,
        val coverImagePath: String?,
        val layoutType: PublicationLayoutType = PublicationLayoutType.REFLOWABLE,
        val readingDirection: BookReadingDirection = BookReadingDirection.LTR,
        val language: String = "en",
        val explicitPageCount: Int? = null,
        val isBinaryFixedDocument: Boolean = false
    )

    /**
     * Parses a PDF file directly via `io.legere:pdfiumandroid` without involving Readium.
     * Extracts page count and document metadata, and renders page 0 to a Bitmap for the cover.
     */
    private suspend fun parsePdfWithPdfium(
        context: Context,
        savedRawPath: String,
        fallbackTitle: String,
        fileStorage: BookFileStorage
    ): ParsedBookPayload {
        val pdfFile = File(savedRawPath)
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfiumCore = PdfiumCore(context)

        return pfd.use { descriptor ->
            pdfiumCore.newDocument(descriptor).use { document ->
                val pageCount = document.getPageCount().coerceAtLeast(1)
                val meta = runCatching { document.getDocumentMeta() }.getOrNull()
                val resolvedTitle = meta?.title?.trim()?.takeIf { it.isNotBlank() } ?: fallbackTitle
                val resolvedAuthor = meta?.author?.trim()?.takeIf { it.isNotBlank() } ?: "PDF Document"

                var coverPath: String? = null
                runCatching {
                    document.openPage(0)?.use { page ->
                        val rawW = page.getPageWidthPoint().coerceAtLeast(1)
                        val rawH = page.getPageHeightPoint().coerceAtLeast(1)
                        val maxDim = 900f
                        val scale = minOf(maxDim / rawW.toFloat(), maxDim / rawH.toFloat(), 2.5f)
                        val targetW = (rawW * scale).toInt().coerceAtLeast(1)
                        val targetH = (rawH * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.renderPageBitmap(
                                bitmap = bitmap,
                                startX = 0,
                                startY = 0,
                                drawSizeX = targetW,
                                drawSizeY = targetH,
                                renderAnnot = true
                            )
                            val out = ByteArrayOutputStream()
                            @Suppress("DEPRECATION")
                            bitmap.compress(Bitmap.CompressFormat.WEBP, 86, out)
                            coverPath = fileStorage.saveCoverImageBytes(out.toByteArray())
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }

                // Sample text from the first few pages (+ metadata) to auto-detect RTL PDFs (e.g. Arabic/Hebrew)
                val rtlSampleBuilder = StringBuilder()
                rtlSampleBuilder.append(resolvedTitle).append(' ').append(resolvedAuthor).append(' ')
                val samplePageLimit = minOf(5, pageCount)
                for (i in 0 until samplePageLimit) {
                    if (rtlSampleBuilder.length >= 1200) break
                    runCatching {
                        document.openPage(i)?.use { page ->
                            page.openTextPage().use { textPage ->
                                val charCount = textPage.textPageCountChars().coerceAtMost(400)
                                if (charCount > 0) {
                                    val snippet = textPage.textPageGetText(0, charCount)
                                    if (!snippet.isNullOrBlank()) {
                                        rtlSampleBuilder.append(snippet).append(' ')
                                    }
                                }
                            }
                        }
                    }
                }
                val isRtlPdf = containsMostlyRtlCharacters(rtlSampleBuilder.toString())

                val placeholderMetadataText = "$resolvedTitle\nby $resolvedAuthor"
                ParsedBookPayload(
                    title = resolvedTitle,
                    author = resolvedAuthor,
                    fullText = placeholderMetadataText,
                    coverImagePath = coverPath,
                    layoutType = PublicationLayoutType.FIXED,
                    readingDirection = if (isRtlPdf) BookReadingDirection.RTL else BookReadingDirection.LTR,
                    language = if (isRtlPdf) "ar" else "en",
                    explicitPageCount = pageCount,
                    isBinaryFixedDocument = true
                )
            }
        }
    }

    /**
     * Primary parser for EPUB files using Readium Kotlin Toolkit (`AssetRetriever` +
     * `DefaultPublicationParser` + `PublicationOpener`).
     * PDFs are handled separately by [parsePdfWithPdfium].
     */
    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun parseWithReadiumOrFallback(
        context: Context,
        savedRawPath: String,
        rawBytes: ByteArray,
        extension: String,
        fallbackTitle: String,
        fileStorage: BookFileStorage
    ): ParsedBookPayload {
        val bookFile = File(savedRawPath)
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(
            contentResolver = context.contentResolver,
            httpClient = httpClient
        )
        val publicationOpener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context = context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null
            )
        )

        val asset = assetRetriever.retrieve(bookFile.toUrl()).getOrNull()
        if (asset != null) {
            val publication = publicationOpener.open(asset, allowUserInteraction = false).getOrNull()
            if (publication != null) {
                try {
                    val metadata = publication.metadata

                    val resolvedTitle = metadata.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
                    val resolvedAuthor = metadata.authors
                        .mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
                        .joinToString(", ")
                        .ifBlank { "Unknown Author" }

                    val primaryLanguage = metadata.languages.firstOrNull()?.lowercase()?.trim().orEmpty()
                        .ifBlank { "en" }
                    val langPrefix = primaryLanguage.substringBefore('-').substringBefore('_')

                    val resolvedDirection = when (metadata.readingProgression) {
                        ReadingProgression.RTL -> BookReadingDirection.RTL
                        ReadingProgression.LTR -> {
                            if (langPrefix in rtlLanguagePrefixes) BookReadingDirection.RTL
                            else BookReadingDirection.LTR
                        }
                        else -> {
                            if (langPrefix in rtlLanguagePrefixes) BookReadingDirection.RTL
                            else BookReadingDirection.LTR
                        }
                    }

                    val presentationMap = metadata.otherMetadata["presentation"] as? Map<*, *>
                    val layoutValue = presentationMap?.get("layout")?.toString()?.lowercase().orEmpty()
                    val resolvedLayout = if (
                        layoutValue == "fixed" ||
                        layoutValue == "pre-paginated"
                    ) {
                        PublicationLayoutType.FIXED
                    } else {
                        PublicationLayoutType.REFLOWABLE
                    }

                    // Extract cover bitmap from Readium's CoverService
                    var coverPath: String? = null
                    val coverBitmap = runCatching { publication.cover() }.getOrNull()
                    if (coverBitmap != null) {
                        val out = ByteArrayOutputStream()
                        @Suppress("DEPRECATION")
                        coverBitmap.compress(Bitmap.CompressFormat.WEBP, 86, out)
                        coverPath = fileStorage.saveCoverImageBytes(out.toByteArray())
                    } else {
                        // Fallback to manual zip extraction if Readium's cover() misses EPUB metadata
                        coverPath = runCatching {
                            parseZipEpubFallback(rawBytes, fallbackTitle, fileStorage).coverImagePath
                        }.getOrNull()
                    }

                    // Extract spine text in readingOrder via Readium Resource API for reflowable EPUBs
                    val chapterTexts = mutableListOf<String>()
                    for (link in publication.readingOrder) {
                        val resource = publication.get(link) ?: continue
                        val rawBytesResult = resource.use { res -> res.read().getOrNull() }
                        if (rawBytesResult != null && rawBytesResult.isNotEmpty()) {
                            val html = rawBytesResult.toString(Charsets.UTF_8)
                            val cleaned = htmlToCleanText(html)
                            if (cleaned.length > 15) {
                                chapterTexts.add(cleaned)
                            }
                        }
                    }

                    val combinedText = chapterTexts.joinToString("\n\n")
                    if (combinedText.isNotBlank()) {
                        return ParsedBookPayload(
                            title = resolvedTitle,
                            author = resolvedAuthor,
                            fullText = combinedText,
                            coverImagePath = coverPath,
                            layoutType = resolvedLayout,
                            readingDirection = resolvedDirection,
                            language = primaryLanguage
                        )
                    }
                } finally {
                    runCatching { publication.close() }
                }
            } else {
                runCatching { asset.close() }
            }
        }

        // Fallback strictly for non-standard ZIP/EPUB archives only
        val isZip = extension == "epub" &&
            rawBytes.size >= 4 &&
            rawBytes[0] == 'P'.code.toByte() &&
            rawBytes[1] == 'K'.code.toByte()

        if (isZip) {
            return parseZipEpubFallback(rawBytes, fallbackTitle, fileStorage)
        }
        throw IllegalArgumentException(UNSUPPORTED_FORMAT_MESSAGE)
    }

    private fun containsMostlyRtlCharacters(text: String): Boolean {
        val sample = text.take(1200)
        var rtlCount = 0
        var ltrCount = 0
        for (ch in sample) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtlCount++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltrCount++
            }
        }
        return rtlCount > ltrCount && rtlCount > 10
    }

    private suspend fun parseZipEpubFallback(
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
        var language: String? = null
        var pageProgressionDir: String? = null
        var renditionLayout: String? = null
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
                var currentMetaProperty = ""
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name.substringAfter(':')
                            when (currentTag.lowercase()) {
                                "spine" -> {
                                    val ppd = parser.getAttributeValue(null, "page-progression-direction")
                                    if (!ppd.isNullOrBlank()) {
                                        pageProgressionDir = ppd.lowercase().trim()
                                    }
                                }
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
                                    currentMetaProperty = parser.getAttributeValue(null, "property").orEmpty()
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
                                    "language" -> if (language.isNullOrBlank()) language = textVal
                                    "meta" -> {
                                        if (currentMetaProperty.endsWith("layout", ignoreCase = true)) {
                                            renditionLayout = textVal.lowercase()
                                        }
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            currentTag = ""
                            currentMetaProperty = ""
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

        val normLang = language?.lowercase()?.trim().orEmpty().ifBlank {
            if (containsMostlyRtlCharacters(combinedText)) "ar" else "en"
        }
        val langPrefix = normLang.substringBefore('-').substringBefore('_')
        val isRtl = pageProgressionDir == "rtl" || langPrefix in rtlLanguagePrefixes
        val layoutType = if (renditionLayout == "pre-paginated") {
            PublicationLayoutType.FIXED
        } else {
            PublicationLayoutType.REFLOWABLE
        }

        return ParsedBookPayload(
            title = finalTitle,
            author = finalAuthor,
            fullText = combinedText,
            coverImagePath = savedCoverPath,
            layoutType = layoutType,
            readingDirection = if (isRtl) BookReadingDirection.RTL else BookReadingDirection.LTR,
            language = normLang
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
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && !cursor.isNull(idx)) {
                            name = cursor.getString(idx)
                        }
                    }
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        }
        if (name.isNullOrBlank()) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
}
