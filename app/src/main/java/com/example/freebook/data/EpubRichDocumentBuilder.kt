package com.example.freebook.data

import android.content.Context
import android.webkit.WebResourceResponse
import com.example.freebook.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Compiled rich EPUB payload ready to be embedded in the Readium WebView while preserving
 * publisher XHTML/CSS styling, cover/front-matter pages, embedded images/SVG/fonts, clickable
 * internal hyperlinks, and exact canonical character-offset annotations for TTS, Audiobook sync,
 * search, highlights, and bookmarks.
 */
data class RichEpubDocument(
    val publisherCss: String,
    val sectionsHtml: String,
    val spineCount: Int,
    val hasCoverFirstPage: Boolean
)

/**
 * Random-access ZIP resource server feeding embedded EPUB images, SVGs, fonts, and stylesheets
 * directly to the WebView via `WebViewClient.shouldInterceptRequest` without base64 bloat.
 */
class EpubArchiveResourceServer private constructor(
    private val zipFile: ZipFile?,
    private val entriesByNormPath: Map<String, ZipEntry>,
    private val entriesByLowerPath: Map<String, ZipEntry>,
    private val fallbackCoverFile: File?
) : Closeable {

    private val lock = Any()
    @Volatile
    private var isClosed = false

    fun interceptRequest(requestUrl: String, bookBaseUrl: String): WebResourceResponse? {
        if (isClosed) return null
        if (!requestUrl.startsWith(bookBaseUrl)) return null
        val relRaw = requestUrl.removePrefix(bookBaseUrl)
            .substringBefore('#')
            .substringBefore('?')
            .trimStart('/')
        if (relRaw.isEmpty()) return null

        if (relRaw == SYNTHETIC_COVER_RESOURCE_PATH) {
            val cover = fallbackCoverFile
            if (cover != null && cover.exists()) {
                val bytes = runCatching { cover.readBytes() }.getOrNull() ?: return null
                return WebResourceResponse(
                    guessMimeType(cover.name),
                    null,
                    ByteArrayInputStream(bytes)
                )
            }
            return null
        }

        val normPath = EpubTocOffsetResolver.normalizeHrefPath(relRaw)
        if (normPath.isEmpty()) return null

        val bytes = readEntryBytes(normPath) ?: return null
        val mime = guessMimeType(normPath)
        val encoding = if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")) {
            "UTF-8"
        } else {
            null
        }
        return WebResourceResponse(mime, encoding, ByteArrayInputStream(bytes))
    }

    internal fun readEntryBytes(normPath: String): ByteArray? {
        val zf = zipFile ?: return null
        val entry = entriesByNormPath[normPath]
            ?: entriesByLowerPath[normPath.lowercase(Locale.ROOT)]
            ?: run {
                val lower = normPath.lowercase(Locale.ROOT)
                entriesByLowerPath.entries.firstOrNull { (k, _) ->
                    k.endsWith("/$lower")
                }?.value
            }
            ?: return null

        synchronized(lock) {
            if (isClosed) return null
            return runCatching {
                zf.getInputStream(entry).use { it.readBytes() }
            }.getOrNull()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            runCatching { zipFile?.close() }
        }
    }

    companion object {
        const val SYNTHETIC_COVER_RESOURCE_PATH = "__r2_synthetic_cover__"

        fun open(epubPath: String, fallbackCoverPath: String?): EpubArchiveResourceServer? {
            if (epubPath.isBlank()) return null
            val file = File(epubPath)
            if (!file.exists()) return null
            val zf = runCatching { ZipFile(file) }.getOrNull() ?: return null
            val byNorm = HashMap<String, ZipEntry>()
            val byLower = HashMap<String, ZipEntry>()
            val enumEntries = zf.entries()
            while (enumEntries.hasMoreElements()) {
                val entry = enumEntries.nextElement()
                if (entry.isDirectory) continue
                val norm = EpubTocOffsetResolver.normalizeHrefPath(entry.name)
                if (norm.isNotEmpty()) {
                    byNorm[norm] = entry
                    byLower[norm.lowercase(Locale.ROOT)] = entry
                }
            }
            val coverFile = fallbackCoverPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            return EpubArchiveResourceServer(
                zipFile = zf,
                entriesByNormPath = byNorm,
                entriesByLowerPath = byLower,
                fallbackCoverFile = coverFile
            )
        }

        fun guessMimeType(path: String): String {
            val lower = path.lowercase(Locale.ROOT)
            return when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".svg") || lower.endsWith(".svgz") -> "image/svg+xml"
                lower.endsWith(".bmp") -> "image/bmp"
                lower.endsWith(".css") -> "text/css"
                lower.endsWith(".woff2") -> "font/woff2"
                lower.endsWith(".woff") -> "font/woff"
                lower.endsWith(".ttf") -> "font/ttf"
                lower.endsWith(".otf") -> "font/otf"
                lower.endsWith(".xhtml") || lower.endsWith(".xht") -> "application/xhtml+xml"
                lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
                else -> "application/octet-stream"
            }
        }
    }
}

/**
 * Compiles an EPUB's real spine XHTML documents and publisher CSS into a single paginated,
 * theme-safe rich HTML document while annotating leaf text blocks with exact canonical offsets
 * into `canonicalBookText`.
 */
object EpubRichDocumentBuilder {

    internal data class RawSpineItem(
        val primaryPath: String,
        val hrefKeys: List<String>,
        val rawHtml: String
    )

    @OptIn(ExperimentalReadiumApi::class)
    suspend fun loadRichEpubDocument(
        context: Context,
        book: Book,
        canonicalBookText: String,
        resourceServer: EpubArchiveResourceServer?
    ): RichEpubDocument? = withContext(Dispatchers.IO) {
        if (book.bookFilePath.isBlank()) return@withContext null
        val bookFile = File(book.bookFilePath)
        if (!bookFile.exists()) return@withContext null

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

        val asset = assetRetriever.retrieve(bookFile.toUrl()).getOrNull() ?: return@withContext null
        val publication = publicationOpener.open(asset, allowUserInteraction = false).getOrNull()
        if (publication == null) {
            runCatching { asset.close() }
            return@withContext null
        }

        try {
            if (publication.readingOrder.isEmpty()) return@withContext null

            val spineItems = ArrayList<RawSpineItem>(publication.readingOrder.size)
            for (spineLink in publication.readingOrder) {
                val resource = publication.get(spineLink) ?: continue
                val bytes = resource.use { res -> res.read().getOrNull() } ?: continue
                if (bytes.isEmpty()) continue

                val rawHtml = bytes.toString(Charsets.UTF_8)
                val hrefKeys = ArrayList<String>(2)
                val locHref = publication.locatorFromLink(spineLink)?.href?.toString()
                val rawHref = spineLink.href.toString()
                if (!locHref.isNullOrBlank()) hrefKeys.add(locHref)
                if (rawHref.isNotBlank()) hrefKeys.add(rawHref)
                val primaryPath = EpubTocOffsetResolver.normalizeHrefPath(locHref ?: rawHref)
                spineItems.add(
                    RawSpineItem(
                        primaryPath = primaryPath,
                        hrefKeys = hrefKeys,
                        rawHtml = rawHtml
                    )
                )
            }
            if (spineItems.isEmpty()) return@withContext null

            val coverImageHref = publication.resources
                .firstOrNull { link -> link.rels.any { it.equals("cover", ignoreCase = true) } }
                ?.href?.toString()
                ?.let { EpubTocOffsetResolver.normalizeHrefPath(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: book.coverImagePath?.takeIf { it.isNotBlank() && File(it).exists() }
                    ?.let { EpubArchiveResourceServer.SYNTHETIC_COVER_RESOURCE_PATH }

            val fallbackCssByLowerPath = HashMap<String, String>()
            if (resourceServer == null) {
                for (resLink in publication.resources) {
                    val normPath = EpubTocOffsetResolver.normalizeHrefPath(resLink.href.toString())
                    if (normPath.endsWith(".css", ignoreCase = true)) {
                        val cssText = publication.get(resLink)?.use { res ->
                            res.read().getOrNull()?.toString(Charsets.UTF_8)
                        }
                        if (!cssText.isNullOrBlank()) {
                            fallbackCssByLowerPath[normPath.lowercase(Locale.ROOT)] = cssText
                        }
                    }
                }
            }

            buildFromSpineItems(
                spineItems = spineItems,
                canonicalBookText = canonicalBookText,
                coverFallbackSrc = coverImageHref,
                readResourceText = { normPath ->
                    resourceServer?.readEntryBytes(normPath)?.toString(Charsets.UTF_8)
                        ?: fallbackCssByLowerPath[normPath.lowercase(Locale.ROOT)]
                }
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { publication.close() }
        }
    }

    /**
     * Pure-Kotlin builder taking already-read spine XHTML items and a resource text reader,
     * making the entire rich XHTML/CSS compiler + canonical offset annotator directly unit-testable.
     */
    internal fun buildFromSpineItems(
        spineItems: List<RawSpineItem>,
        canonicalBookText: String,
        coverFallbackSrc: String? = null,
        readResourceText: (normalizedPath: String) -> String?
    ): RichEpubDocument {
        val tocIndex = EpubTocOffsetResolver.buildBookIndex(
            canonicalBookText = canonicalBookText,
            spineDocuments = spineItems.map {
                EpubTocOffsetResolver.SpineDocument(
                    hrefKeys = it.hrefKeys,
                    rawHtml = it.rawHtml
                )
            }
        )

        val cssScopeByPath = LinkedHashMap<String, String>()
        val combinedCss = StringBuilder(4096)
        val sectionsOut = StringBuilder(canonicalBookText.length * 2 + 8192)
        var emittedSections = 0
        var hasCoverFirstPage = false

        // Prepend a clean cover page if the first spine item does not already render a cover image
        val firstLooksLikeCover = spineItems.firstOrNull()?.let { isLikelyCoverSpineItem(it) } == true
        if (!firstLooksLikeCover && !coverFallbackSrc.isNullOrBlank()) {
            hasCoverFirstPage = true
            emittedSections++
            sectionsOut.append("<section class=\"r2-spine-doc r2-cover-page\" data-spine-index=\"-1\" data-r2-href=\"__cover__.xhtml\" data-r2-canon-start=\"0\">")
                .append("<img class=\"r2-cover-img\" src=\"")
                .append(escapeAttr(coverFallbackSrc))
                .append("\" alt=\"Book Cover\"/>")
                .append("</section>")
        }

        for (i in spineItems.indices) {
            val item = spineItems[i]
            val slice = tocIndex.slicesInOrder[i]
            val spineDir = item.primaryPath.substringBeforeLast('/', "")
            val scopeClasses = ArrayList<String>(4)

            // 1. Collect & scope external stylesheets linked in <head>
            val linkedHrefs = extractStylesheetLinks(item.rawHtml)
            for (relHref in linkedHrefs) {
                val resolvedCssPath = resolveRelativePath(spineDir, relHref)
                if (resolvedCssPath.isEmpty()) continue
                val existingScope = cssScopeByPath[resolvedCssPath]
                if (existingScope != null) {
                    scopeClasses.add(existingScope)
                } else {
                    val rawCss = readResourceText(resolvedCssPath)
                    if (!rawCss.isNullOrBlank()) {
                        val scopeClass = "r2-css-${cssScopeByPath.size}"
                        cssScopeByPath[resolvedCssPath] = scopeClass
                        val cssDir = resolvedCssPath.substringBeforeLast('/', "")
                        val sanitizedCss = sanitizeAndScopePublisherCss(
                            rawCss = rawCss,
                            scopeSelector = ".$scopeClass",
                            cssBaseDir = cssDir
                        )
                        if (sanitizedCss.isNotBlank()) {
                            combinedCss.append(sanitizedCss).append('\n')
                        }
                        scopeClasses.add(scopeClass)
                    }
                }
            }

            // 2. Collect & scope inline <style> blocks
            val inlineStyles = extractInlineStyleBlocks(item.rawHtml)
            if (inlineStyles.isNotEmpty()) {
                val inlineScope = "r2-inline-$i"
                val mergedInline = inlineStyles.joinToString("\n")
                val sanitizedInline = sanitizeAndScopePublisherCss(
                    rawCss = mergedInline,
                    scopeSelector = ".$inlineScope",
                    cssBaseDir = spineDir
                )
                if (sanitizedInline.isNotBlank()) {
                    combinedCss.append(sanitizedInline).append('\n')
                    scopeClasses.add(inlineScope)
                }
            }

            // 3. Extract & sanitize <body> HTML
            val extractedBody = extractBodyElement(item.rawHtml)
            val sanitizedBody = sanitizeSpineBodyHtml(
                bodyInnerHtml = extractedBody.innerHtml,
                spineBaseDir = spineDir,
                currentSpinePath = item.primaryPath
            )

            // Skip genuinely empty spine items (no text and no images/svg/hr) to avoid blank pages
            val hasVisualContent = sanitizedBody.contains("<img", ignoreCase = true) ||
                sanitizedBody.contains("<svg", ignoreCase = true) ||
                sanitizedBody.contains("<hr", ignoreCase = true) ||
                EpubTocOffsetResolver.extractPlainTextFromHtml(sanitizedBody).isNotBlank()
            if (!hasVisualContent) continue

            // 4. Annotate leaf text blocks with exact canonical character offsets
            val annotatedBody = if (slice.matched && slice.endNorm > slice.startNorm) {
                annotateLeafBlocksWithCanonicalOffsets(
                    sanitizedHtml = sanitizedBody,
                    searchIndex = tocIndex.searchIndex,
                    sliceStartNorm = slice.startNorm,
                    sliceEndNorm = slice.endNorm
                )
            } else {
                sanitizedBody
            }

            val isCoverSection = emittedSections == 0 && isLikelyCoverSpineItem(item)
            if (isCoverSection) {
                hasCoverFirstPage = true
            }

            val sectionClasses = buildList {
                add("r2-spine-doc")
                if (isCoverSection) add("r2-cover-page")
                addAll(scopeClasses)
            }.joinToString(" ")
            val bodyWrapperClasses = buildList {
                add("r2-body")
                if (extractedBody.bodyClass.isNotBlank()) add(extractedBody.bodyClass)
            }.joinToString(" ")

            val allNormHrefs = item.hrefKeys
                .map { EpubTocOffsetResolver.normalizeHrefPath(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString("|")

            sectionsOut.append("<section class=\"")
                .append(escapeAttr(sectionClasses))
                .append("\" data-spine-index=\"")
                .append(i)
                .append("\" data-r2-href=\"")
                .append(escapeAttr(item.primaryPath))
                .append("\" data-r2-hrefs=\"")
                .append(escapeAttr(allNormHrefs))
                .append("\" data-r2-canon-start=\"")
                .append(slice.startOrig)
                .append("\" data-r2-canon-end=\"")
                .append(slice.endOrig)
                .append("\"><div class=\"")
                .append(escapeAttr(bodyWrapperClasses))
                .append("\"")
            if (extractedBody.bodyId.isNotBlank()) {
                sectionsOut.append(" id=\"")
                    .append(escapeAttr(extractedBody.bodyId))
                    .append("\" data-r2-id=\"")
                    .append(escapeAttr(extractedBody.bodyId))
                    .append("\"")
            }
            sectionsOut.append(">")
                .append(annotatedBody)
                .append("</div></section>")

            emittedSections++
        }

        return RichEpubDocument(
            publisherCss = combinedCss.toString(),
            sectionsHtml = sectionsOut.toString(),
            spineCount = emittedSections,
            hasCoverFirstPage = hasCoverFirstPage
        )
    }

    private fun isLikelyCoverSpineItem(item: RawSpineItem): Boolean {
        val hasImage = item.rawHtml.contains("<img", ignoreCase = true) ||
            item.rawHtml.contains("<svg", ignoreCase = true)
        if (!hasImage) return false
        val lowerPath = item.primaryPath.lowercase(Locale.ROOT)
        val plainLen = EpubTocOffsetResolver.extractPlainTextFromHtml(item.rawHtml).trim().length
        return lowerPath.contains("cover") || plainLen <= 35
    }

    internal data class ExtractedBody(
        val innerHtml: String,
        val bodyClass: String,
        val bodyId: String
    )

    internal fun extractBodyElement(rawHtml: String): ExtractedBody {
        val bodyOpenMatch = BODY_OPEN_REGEX.find(rawHtml)
        if (bodyOpenMatch != null) {
            val attrs = bodyOpenMatch.groupValues[1]
            val bodyClass = extractAttrValue(attrs, "class")
            val bodyId = extractAttrValue(attrs, "id")
            val afterOpen = bodyOpenMatch.range.last + 1
            val closeIdx = rawHtml.lastIndexOf("</body>", ignoreCase = true)
            val inner = if (closeIdx >= afterOpen) {
                rawHtml.substring(afterOpen, closeIdx)
            } else {
                rawHtml.substring(afterOpen)
            }
            return ExtractedBody(inner, bodyClass, bodyId)
        }
        val stripped = rawHtml.replace(HEAD_BLOCK_REGEX, "")
        return ExtractedBody(stripped, "", "")
    }

    internal fun extractStylesheetLinks(rawHtml: String): List<String> {
        val out = ArrayList<String>(4)
        for (m in LINK_TAG_REGEX.findAll(rawHtml)) {
            val attrs = m.groupValues[1]
            val rel = extractAttrValue(attrs, "rel").lowercase(Locale.ROOT)
            val type = extractAttrValue(attrs, "type").lowercase(Locale.ROOT)
            val href = extractAttrValue(attrs, "href").trim()
            if (href.isNotEmpty() && (rel.contains("stylesheet") || type == "text/css" || href.endsWith(".css", true))) {
                out.add(href)
            }
        }
        return out
    }

    internal fun extractInlineStyleBlocks(rawHtml: String): List<String> {
        val out = ArrayList<String>(2)
        for (m in STYLE_BLOCK_REGEX.findAll(rawHtml)) {
            val css = m.groupValues[1].trim()
            if (css.isNotEmpty()) out.add(css)
        }
        return out
    }

    /**
     * Sanitizes a spine XHTML body for safe HTML5 WebView embedding:
     * - Strips `<script>`, `<style>`, `<link>`, `<meta>`, comments, and `on*` event handlers
     * - Expands self-closing non-void XHTML tags (`<a id="x"/>` -> `<a id="x"></a>`, `<div/>` -> `<div></div>`)
     *   outside `<svg>` so HTML5 parsing never swallows subsequent content
     * - Rewrites relative `src`, `xlink:href`, and `href` attributes
     * - Marks internal EPUB `<a href="...">` links with `data-r2-link="internal"` + normalized target path & fragment
     * - Sanitizes inline `style="..."` for theme legibility
     * - Collapses pretty-printed source whitespace inside text runs (outside `<pre>`/`<code>`)
     */
    internal fun sanitizeSpineBodyHtml(
        bodyInnerHtml: String,
        spineBaseDir: String,
        currentSpinePath: String
    ): String {
        val len = bodyInnerHtml.length
        val out = StringBuilder(len + 256)
        var i = 0
        var svgDepth = 0
        var preDepth = 0

        while (i < len) {
            val lt = bodyInnerHtml.indexOf('<', i)
            if (lt < 0) {
                appendNormalizedTextRun(out, bodyInnerHtml, i, len, preserveWhitespace = preDepth > 0)
                break
            }
            if (lt > i) {
                appendNormalizedTextRun(out, bodyInnerHtml, i, lt, preserveWhitespace = preDepth > 0)
            }

            // Strip HTML comments <!-- ... -->
            if (lt + 3 < len && bodyInnerHtml[lt + 1] == '!' && bodyInnerHtml[lt + 2] == '-' && bodyInnerHtml[lt + 3] == '-') {
                val endComment = bodyInnerHtml.indexOf("-->", lt + 4)
                i = if (endComment >= 0) endComment + 3 else len
                continue
            }
            // Strip XML processing instructions / declarations
            if (lt + 1 < len && (bodyInnerHtml[lt + 1] == '?' || bodyInnerHtml[lt + 1] == '!')) {
                val gt = findClosingBracket(bodyInnerHtml, lt + 2)
                i = if (gt >= 0) gt + 1 else len
                continue
            }

            val gt = findClosingBracket(bodyInnerHtml, lt + 1)
            if (gt < 0) break

            val rawTagContent = bodyInnerHtml.substring(lt + 1, gt).trim()
            if (rawTagContent.isEmpty()) {
                i = gt + 1
                continue
            }

            val isClosing = rawTagContent[0] == '/'
            val isSelfClosing = !isClosing && rawTagContent.endsWith("/")
            val contentWithoutSlashes = rawTagContent
                .removePrefix("/")
                .removeSuffix("/")
                .trim()

            val tagNameEnd = contentWithoutSlashes.indexOfFirst { it.isWhitespace() }
                .let { if (it < 0) contentWithoutSlashes.length else it }
            val fullTagName = contentWithoutSlashes.substring(0, tagNameEnd)
            val tagName = fullTagName.lowercase(Locale.ROOT).substringAfter(':')
            val rawAttrs = if (tagNameEnd < contentWithoutSlashes.length) {
                contentWithoutSlashes.substring(tagNameEnd).trim()
            } else {
                ""
            }

            // Skip forbidden subtrees completely
            if (!isClosing && tagName in strippedSubtreeTags) {
                if (isSelfClosing) {
                    i = gt + 1
                } else {
                    val closeEnd = findClosingTagEnd(bodyInnerHtml, tagName, gt + 1)
                    i = if (closeEnd >= 0) closeEnd else len
                }
                continue
            }
            if (tagName in strippedSingleTags) {
                i = gt + 1
                continue
            }

            if (tagName == "svg") {
                if (isClosing) {
                    svgDepth = (svgDepth - 1).coerceAtLeast(0)
                } else if (!isSelfClosing) {
                    svgDepth++
                }
            } else if (tagName == "pre" || tagName == "code") {
                if (isClosing) {
                    preDepth = (preDepth - 1).coerceAtLeast(0)
                } else if (!isSelfClosing) {
                    preDepth++
                }
            }

            if (isClosing) {
                out.append("</").append(fullTagName).append('>')
                i = gt + 1
                continue
            }

            // Rewrite attributes cleanly
            val rewrittenAttrs = rewriteTagAttributes(
                tagName = tagName,
                rawAttrs = rawAttrs,
                spineBaseDir = spineBaseDir,
                currentSpinePath = currentSpinePath,
                inSvg = svgDepth > 0 || tagName == "svg"
            )

            val shouldExpandSelfClosing = isSelfClosing && svgDepth == 0 && tagName !in htmlVoidTags
            out.append('<').append(fullTagName)
            if (rewrittenAttrs.isNotEmpty()) {
                out.append(' ').append(rewrittenAttrs)
            }
            if (shouldExpandSelfClosing) {
                out.append("></").append(fullTagName).append('>')
            } else if (isSelfClosing) {
                out.append("/>")
            } else {
                out.append('>')
            }

            i = gt + 1
        }

        return out.toString()
    }

    private fun rewriteTagAttributes(
        tagName: String,
        rawAttrs: String,
        spineBaseDir: String,
        currentSpinePath: String,
        inSvg: Boolean
    ): String {
        if (rawAttrs.isEmpty()) return ""
        val parsedAttrs = parseTagAttributes(rawAttrs)
        if (parsedAttrs.isEmpty()) return ""

        val out = StringBuilder(rawAttrs.length + 48)
        var emittedDataR2Id = false
        var emittedSvgHref = false
        var internalTargetHref: String? = null
        var internalTargetFrag: String? = null
        var isExternalLink = false

        fun appendAttr(name: String, value: String) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(name).append("=\"").append(escapeAttr(value)).append('"')
        }

        for ((rawName, rawValue) in parsedAttrs) {
            val lowerName = rawName.lowercase(Locale.ROOT)
            // Security: drop inline event handlers
            if (lowerName.startsWith("on")) continue
            // Drop legacy presentational HTML color attributes that break Dark/AMOLED readability
            if (lowerName == "bgcolor" || lowerName == "color" || lowerName == "text" || lowerName == "link" || lowerName == "vlink") {
                continue
            }

            when {
                lowerName == "xml:id" -> {
                    val trimmed = rawValue.trim()
                    if (trimmed.isNotEmpty() && !emittedDataR2Id) {
                        appendAttr("data-r2-id", trimmed)
                        emittedDataR2Id = true
                    }
                }
                lowerName == "id" -> {
                    val trimmed = rawValue.trim()
                    appendAttr("id", trimmed)
                    if (trimmed.isNotEmpty() && !emittedDataR2Id) {
                        appendAttr("data-r2-id", trimmed)
                        emittedDataR2Id = true
                    }
                }
                lowerName == "name" -> {
                    val trimmed = rawValue.trim()
                    appendAttr("name", trimmed)
                    if (trimmed.isNotEmpty() && !emittedDataR2Id) {
                        appendAttr("data-r2-id", trimmed)
                        emittedDataR2Id = true
                    }
                }
                lowerName == "style" -> {
                    val sanitized = sanitizeInlineStyle(rawValue, spineBaseDir)
                    if (sanitized.isNotEmpty()) {
                        appendAttr("style", sanitized)
                    }
                }
                lowerName == "src" || lowerName == "poster" -> {
                    val trimmed = rawValue.trim()
                    if (isExternalOrDataUrl(trimmed)) {
                        appendAttr(rawName, trimmed)
                    } else {
                        val resolved = resolveRelativePath(spineBaseDir, trimmed)
                        appendAttr(rawName, resolved)
                    }
                }
                inSvg && (lowerName == "xlink:href" || lowerName == "href") && tagName == "image" -> {
                    if (!emittedSvgHref) {
                        emittedSvgHref = true
                        val trimmed = rawValue.trim()
                        val resolved = if (isExternalOrDataUrl(trimmed) || trimmed.startsWith("#")) {
                            trimmed
                        } else {
                            resolveRelativePath(spineBaseDir, trimmed)
                        }
                        // Emit both href and xlink:href for maximum WebView SVG compatibility
                        appendAttr("href", resolved)
                        appendAttr("xlink:href", resolved)
                    }
                }
                tagName == "a" && lowerName == "href" -> {
                    val trimmed = rawValue.trim()
                    val lowerVal = trimmed.lowercase(Locale.ROOT)
                    when {
                        lowerVal.startsWith("javascript:") -> {
                            // Drop unsafe javascript: links
                        }
                        lowerVal.startsWith("http://") ||
                            lowerVal.startsWith("https://") ||
                            lowerVal.startsWith("mailto:") ||
                            lowerVal.startsWith("tel:") -> {
                            isExternalLink = true
                            appendAttr("href", trimmed)
                        }
                        trimmed.isNotEmpty() -> {
                            val fragRaw = trimmed.substringAfter('#', "")
                            val filePart = trimmed.substringBefore('#').trim()
                            val targetPath = if (filePart.isEmpty()) {
                                currentSpinePath
                            } else {
                                resolveRelativePath(spineBaseDir, filePart)
                            }
                            internalTargetHref = targetPath
                            internalTargetFrag = fragRaw.takeIf { it.isNotBlank() }?.let {
                                EpubTocOffsetResolver.percentDecode(it)
                            }
                            val displayHref = if (!internalTargetFrag.isNullOrBlank()) {
                                "#${internalTargetFrag}"
                            } else {
                                "#"
                            }
                            appendAttr("href", displayHref)
                        }
                    }
                }
                else -> {
                    appendAttr(rawName, rawValue)
                }
            }
        }

        if (internalTargetHref != null) {
            appendAttr("data-r2-link", "internal")
            appendAttr("data-r2-target-href", internalTargetHref)
            if (!internalTargetFrag.isNullOrBlank()) {
                appendAttr("data-r2-target-frag", internalTargetFrag)
            }
        } else if (isExternalLink) {
            appendAttr("data-r2-link", "external")
        }

        return out.toString()
    }

    /**
     * Annotates leaf block elements (`<p>`, `<h1>`..`<h6>`, `<li>`, `<div>`, etc. that do not
     * contain nested block elements) with `data-r2-block="1" data-r2-start="..." data-r2-end="..."`
     * mapped onto exact character offsets inside `canonicalBookText`.
     */
    internal fun annotateLeafBlocksWithCanonicalOffsets(
        sanitizedHtml: String,
        searchIndex: EpubTocOffsetResolver.CanonicalSearchIndex,
        sliceStartNorm: Int,
        sliceEndNorm: Int
    ): String {
        val len = sanitizedHtml.length
        if (len == 0 || sliceEndNorm <= sliceStartNorm) return sanitizedHtml

        val out = StringBuilder(len + 512)
        var cursorNorm = sliceStartNorm
        var i = 0
        var svgDepth = 0

        while (i < len) {
            val lt = sanitizedHtml.indexOf('<', i)
            if (lt < 0) {
                out.append(sanitizedHtml, i, len)
                break
            }
            if (lt > i) {
                out.append(sanitizedHtml, i, lt)
            }

            val gt = findClosingBracket(sanitizedHtml, lt + 1)
            if (gt < 0) {
                out.append(sanitizedHtml, lt, len)
                break
            }

            val rawTag = sanitizedHtml.substring(lt + 1, gt).trim()
            if (rawTag.isEmpty() || rawTag[0] == '/' || rawTag[0] == '!' || rawTag[0] == '?') {
                if (rawTag.startsWith("/svg", ignoreCase = true)) {
                    svgDepth = (svgDepth - 1).coerceAtLeast(0)
                }
                out.append(sanitizedHtml, lt, gt + 1)
                i = gt + 1
                continue
            }

            val isSelfClosing = rawTag.endsWith("/")
            val clean = rawTag.removeSuffix("/").trim()
            val nameEnd = clean.indexOfFirst { it.isWhitespace() }.let { if (it < 0) clean.length else it }
            val tagName = clean.substring(0, nameEnd).lowercase(Locale.ROOT).substringAfter(':')

            if (tagName == "svg") {
                if (!isSelfClosing) svgDepth++
                out.append(sanitizedHtml, lt, gt + 1)
                i = gt + 1
                continue
            }

            if (svgDepth == 0 && !isSelfClosing && tagName in leafBlockCandidateTags) {
                val leafEnd = findLeafBlockCloseIfNoNestedBlocks(sanitizedHtml, tagName, gt + 1)
                if (leafEnd >= 0) {
                    val innerHtml = sanitizedHtml.substring(gt + 1, leafEnd)
                    val plain = EpubTocOffsetResolver.extractPlainTextFromHtml(innerHtml)
                    val norm = EpubTocOffsetResolver.normalizeForIndex(plain)

                    if (norm.isNotEmpty()) {
                        val matched = matchBlockNorm(
                            searchIndex = searchIndex,
                            blockNorm = norm,
                            cursorNorm = cursorNorm,
                            sliceEndNorm = sliceEndNorm
                        )
                        if (matched >= 0) {
                            val endNormExclusive = (matched + norm.length).coerceAtMost(sliceEndNorm)
                            val origStart = searchIndex.normToOrig(matched)
                            val origEnd = (searchIndex.normToOrig((endNormExclusive - 1).coerceAtLeast(matched)) + 1)
                                .coerceAtMost(searchIndex.canonicalText.length)
                                .coerceAtLeast(origStart + 1)
                            cursorNorm = endNormExclusive

                            // Inject data-r2-block / data-r2-start / data-r2-end right into the opening tag
                            out.append(sanitizedHtml, lt, gt)
                                .append(" data-r2-block=\"1\" data-r2-start=\"")
                                .append(origStart)
                                .append("\" data-r2-end=\"")
                                .append(origEnd)
                                .append("\">")
                            val closeTagEnd = findClosingBracket(sanitizedHtml, leafEnd + 1)
                            val copyEnd = if (closeTagEnd >= 0) closeTagEnd + 1 else leafEnd
                            out.append(sanitizedHtml, gt + 1, copyEnd)
                            i = copyEnd
                            continue
                        }
                    }
                }
            }

            out.append(sanitizedHtml, lt, gt + 1)
            i = gt + 1
        }

        return out.toString()
    }

    private fun matchBlockNorm(
        searchIndex: EpubTocOffsetResolver.CanonicalSearchIndex,
        blockNorm: String,
        cursorNorm: Int,
        sliceEndNorm: Int
    ): Int {
        if (blockNorm.isEmpty() || cursorNorm >= sliceEndNorm) return -1
        val probe = if (blockNorm.length > 64) {
            val cut = blockNorm.lastIndexOf(' ', startIndex = 64)
            if (cut >= 24) blockNorm.substring(0, cut) else blockNorm.substring(0, 64)
        } else {
            blockNorm
        }

        // Fast local window right at cursorNorm (hits 99%+ of blocks immediately)
        val localEnd = (cursorNorm + maxOf(256, probe.length * 3)).coerceAtMost(sliceEndNorm)
        val localHit = searchIndex.findWordAligned(probe, startNorm = cursorNorm, endNorm = localEnd)
        if (localHit >= 0) return localHit

        // Wider slice fallback
        val sliceHit = searchIndex.findWordAligned(probe, startNorm = cursorNorm, endNorm = sliceEndNorm)
        if (sliceHit >= 0) return sliceHit

        // Shorter prefix fallback if trailing word had unusual entity
        if (probe.length > 24) {
            val shortCut = probe.lastIndexOf(' ', startIndex = 24)
            val shortProbe = if (shortCut >= 8) probe.substring(0, shortCut) else probe.substring(0, 24)
            val shortHit = searchIndex.findWordAligned(shortProbe, startNorm = cursorNorm, endNorm = localEnd)
            if (shortHit >= 0) return shortHit
        }
        return -1
    }

    /**
     * Scans forward from immediately after an opening block tag `<tagName ...>` up to its matching
     * closing `</tagName>`. Returns the index of `</tagName>` ONLY if no nested block elements
     * appear inside; returns `-1` if nested block tags exist (so outer wrapper divs are skipped and
     * their inner leaf `<p>`/`<h2>` children are annotated instead).
     */
    private fun findLeafBlockCloseIfNoNestedBlocks(
        html: String,
        openTagName: String,
        startPos: Int
    ): Int {
        val len = html.length
        var i = startPos
        while (i < len) {
            val lt = html.indexOf('<', i)
            if (lt < 0) return -1
            val gt = findClosingBracket(html, lt + 1)
            if (gt < 0) return -1
            val raw = html.substring(lt + 1, gt).trim()
            if (raw.isEmpty()) {
                i = gt + 1
                continue
            }
            if (raw[0] == '/') {
                val closeName = raw.substring(1).trim().lowercase(Locale.ROOT).substringAfter(':')
                if (closeName == openTagName) {
                    return lt
                }
                i = gt + 1
                continue
            }
            val isSelfClosing = raw.endsWith("/")
            val clean = raw.removeSuffix("/").trim()
            val nameEnd = clean.indexOfFirst { it.isWhitespace() }.let { if (it < 0) clean.length else it }
            val childTag = clean.substring(0, nameEnd).lowercase(Locale.ROOT).substringAfter(':')
            if (!isSelfClosing && childTag in leafBlockCandidateTags) {
                return -1
            }
            i = gt + 1
        }
        return -1
    }

    /**
     * Parses, sanitizes, and scopes publisher CSS to [scopeSelector] so:
     * - Publisher visual hierarchy (centered headings, italic book lists, margins, indents, borders,
     *   small-caps, font-size scaling) is preserved cleanly
     * - Hardcoded text/background colors are stripped so Light / Sepia / Dark / AMOLED themes remain crisp
     * - `page-break-before: always` maps to CSS multi-column page breaks in paged mode
     * - `font-family`, `line-height`, and non-centered `text-align` activate under `body.publisher-styles-on`
     *   while centered/right-aligned headings & typography remain intact even when Publisher Styles is OFF
     */
    internal fun sanitizeAndScopePublisherCss(
        rawCss: String,
        scopeSelector: String,
        cssBaseDir: String
    ): String {
        val noComments = CSS_COMMENT_REGEX.replace(rawCss, " ")
        val out = StringBuilder(noComments.length)
        processCssBlock(
            css = noComments,
            start = 0,
            end = noComments.length,
            scopeSelector = scopeSelector,
            cssBaseDir = cssBaseDir,
            out = out
        )
        return out.toString()
    }

    private fun processCssBlock(
        css: String,
        start: Int,
        end: Int,
        scopeSelector: String,
        cssBaseDir: String,
        out: StringBuilder
    ) {
        var i = start
        while (i < end) {
            while (i < end && css[i].isWhitespace()) i++
            if (i >= end) break

            if (css[i] == '@') {
                val atStart = i
                // Find either '{' or ';' respecting quotes
                var p = i
                var quote = '\u0000'
                while (p < end) {
                    val c = css[p]
                    if (quote != '\u0000') {
                        if (c == '\\' && p + 1 < end) {
                            p += 2
                            continue
                        }
                        if (c == quote) quote = '\u0000'
                    } else {
                        if (c == '"' || c == '\'') quote = c
                        else if (c == '{' || c == ';') break
                    }
                    p++
                }
                if (p >= end) break
                val prelude = css.substring(atStart, p).trim()
                val lowerPrelude = prelude.lowercase(Locale.ROOT)
                if (css[p] == ';') {
                    // Drop @charset, @import, @namespace
                    i = p + 1
                    continue
                }
                val blockClose = findMatchingBrace(css, p, end)
                if (blockClose < 0) break

                when {
                    lowerPrelude.startsWith("@font-face") -> {
                        val body = css.substring(p + 1, blockClose)
                        val rewritten = rewriteCssUrls(body, cssBaseDir)
                        out.append("@font-face{").append(rewritten).append("}\n")
                    }
                    lowerPrelude.startsWith("@media") -> {
                        if (!lowerPrelude.contains("print") || lowerPrelude.contains("screen") || lowerPrelude.contains("all")) {
                            out.append(prelude).append("{\n")
                            processCssBlock(css, p + 1, blockClose, scopeSelector, cssBaseDir, out)
                            out.append("}\n")
                        }
                    }
                    lowerPrelude.startsWith("@supports") -> {
                        out.append(prelude).append("{\n")
                        processCssBlock(css, p + 1, blockClose, scopeSelector, cssBaseDir, out)
                        out.append("}\n")
                    }
                    else -> {
                        // Drop @page, @keyframes, etc.
                    }
                }
                i = blockClose + 1
                continue
            }

            // Normal selector { declarations } rule
            val braceOpen = findNextUnquotedChar(css, i, end, '{')
            if (braceOpen < 0) break
            val braceClose = findMatchingBrace(css, braceOpen, end)
            if (braceClose < 0) break

            val rawSelectorList = css.substring(i, braceOpen).trim()
            val rawDecls = css.substring(braceOpen + 1, braceClose).trim()
            i = braceClose + 1

            if (rawSelectorList.isEmpty() || rawDecls.isEmpty()) continue

            val splitSelectors = rawSelectorList.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val isRootOnlyRule = splitSelectors.all { sel ->
                val clean = sel.lowercase(Locale.ROOT)
                clean == "html" || clean == "body" || clean == "html body" || clean == ":root" || clean == "*"
            }

            val scopedSelectors = splitSelectors.mapNotNull { scopeSingleSelector(it, scopeSelector) }
            if (scopedSelectors.isEmpty()) continue

            val (alwaysDecls, publisherOnlyDecls) = sanitizeCssDeclarations(
                rawDecls = rawDecls,
                cssBaseDir = cssBaseDir,
                isRootRule = isRootOnlyRule
            )

            if (alwaysDecls.isNotEmpty()) {
                out.append(scopedSelectors.joinToString(", "))
                    .append('{')
                    .append(alwaysDecls)
                    .append("}\n")
            }
            if (publisherOnlyDecls.isNotEmpty()) {
                val pubSelectors = scopedSelectors.joinToString(", ") { "body.publisher-styles-on $it" }
                out.append(pubSelectors)
                    .append('{')
                    .append(publisherOnlyDecls)
                    .append("}\n")
            }
        }
    }

    private fun scopeSingleSelector(selector: String, scopeSelector: String): String? {
        val trimmed = selector.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower == ":root" || lower == "@page") return null
        if (lower == "html") {
            return scopeSelector
        }
        if (lower == "body" || lower == "html body") {
            return "$scopeSelector > .r2-body"
        }
        // Rewrite leading html/body tokens onto the spine section's inner .r2-body wrapper
        val replacedBody = LEADING_HTML_BODY_REGEX.replace(trimmed) { match ->
            val suffix = match.groupValues[1]
            "$scopeSelector > .r2-body$suffix"
        }
        if (replacedBody != trimmed) {
            return replacedBody
        }
        return "$scopeSelector $trimmed"
    }

    private fun sanitizeCssDeclarations(
        rawDecls: String,
        cssBaseDir: String,
        isRootRule: Boolean
    ): Pair<String, String> {
        val always = StringBuilder()
        val publisherOnly = StringBuilder()

        for (decl in splitCssDeclarations(rawDecls)) {
            val colon = decl.indexOf(':')
            if (colon <= 0) continue
            val prop = decl.substring(0, colon).trim().lowercase(Locale.ROOT)
            val rawVal = decl.substring(colon + 1).trim()
            if (rawVal.isEmpty()) continue
            val valWithoutImportant = rawVal.replace(IMPORTANT_REGEX, "").trim()
            val lowerVal = valWithoutImportant.lowercase(Locale.ROOT)

            when (prop) {
                // Theme colors are controlled by the reader theme for contrast on Dark/AMOLED/Sepia
                "color", "background-color", "-webkit-text-fill-color" -> continue
                "background" -> {
                    if (lowerVal.contains("url(")) {
                        always.append("background-image:").append(rewriteCssUrls(valWithoutImportant, cssBaseDir)).append(';')
                    }
                    continue
                }
                // Protect pagination viewport & multi-column engine
                "column-count", "column-width", "columns", "column-gap", "column-fill",
                "overflow", "overflow-x", "overflow-y", "z-index" -> continue
                "position" -> {
                    if (lowerVal == "fixed" || lowerVal == "sticky" || lowerVal == "absolute") continue
                    always.append("position:").append(valWithoutImportant).append(';')
                }
                "height", "min-height", "max-height", "width", "min-width", "max-width" -> {
                    if (isRootRule) continue
                    // Prevent fixed full-viewport heights or fixed giant pixel widths from breaking columns
                    if (lowerVal.contains("vh") || lowerVal.contains("vw")) continue
                    always.append(prop).append(':').append(valWithoutImportant).append(';')
                }
                "font-size" -> {
                    if (isRootRule) continue
                    val adapted = adaptCssFontSize(valWithoutImportant)
                    if (adapted != null) {
                        always.append("font-size:").append(adapted).append(';')
                    }
                }
                "font-family" -> {
                    if (isRootRule) {
                        publisherOnly.append("font-family:").append(valWithoutImportant).append(';')
                    } else if (lowerVal.contains("monospace") || lowerVal.contains("courier")) {
                        always.append("font-family:").append(valWithoutImportant).append(';')
                    } else {
                        publisherOnly.append("font-family:").append(valWithoutImportant).append(';')
                    }
                }
                "line-height" -> {
                    publisherOnly.append("line-height:").append(valWithoutImportant).append(';')
                }
                "text-align" -> {
                    // Always preserve centered & right-aligned titles/lists/poems like Apple Books!
                    if (lowerVal == "center" || lowerVal == "right" || lowerVal == "end") {
                        always.append("text-align:").append(valWithoutImportant).append(';')
                    } else {
                        publisherOnly.append("text-align:").append(valWithoutImportant).append(';')
                    }
                }
                "page-break-before", "break-before" -> {
                    if (lowerVal == "always" || lowerVal == "page" || lowerVal == "right" || lowerVal == "left" || lowerVal == "column") {
                        always.append("break-before:column;-webkit-column-break-before:always;page-break-before:always;")
                    } else if (lowerVal == "avoid") {
                        always.append("break-before:avoid;")
                    }
                }
                "page-break-after", "break-after" -> {
                    if (lowerVal == "avoid") {
                        always.append("break-after:avoid;-webkit-column-break-after:avoid;page-break-after:avoid;")
                    }
                }
                "page-break-inside", "break-inside" -> {
                    if (lowerVal == "avoid") {
                        always.append("break-inside:avoid;-webkit-column-break-inside:avoid;page-break-inside:avoid;")
                    }
                }
                "margin", "margin-left", "margin-right", "padding", "padding-left", "padding-right" -> {
                    if (isRootRule) continue
                    always.append(prop).append(':').append(valWithoutImportant).append(';')
                }
                "border-color", "border-top-color", "border-bottom-color", "border-left-color", "border-right-color" -> {
                    always.append(prop).append(":currentColor;")
                }
                else -> {
                    val rewritten = rewriteCssUrls(valWithoutImportant, cssBaseDir)
                    always.append(prop).append(':').append(rewritten).append(';')
                }
            }
        }

        return always.toString() to publisherOnly.toString()
    }

    private fun sanitizeInlineStyle(rawStyle: String, baseDir: String): String {
        val (always, _) = sanitizeCssDeclarations(rawStyle, baseDir, isRootRule = false)
        return always
    }

    internal fun adaptCssFontSize(rawValue: String): String? {
        val v = rawValue.trim().lowercase(Locale.ROOT)
        if (v.isEmpty() || v == "inherit" || v == "initial") return v
        if (v.endsWith("em") || v.endsWith("rem") || v.endsWith("%")) {
            return rawValue.trim()
        }
        if (v in relativeFontSizeKeywords) {
            return v
        }
        val ptMatch = PT_SIZE_REGEX.matchEntire(v)
        if (ptMatch != null) {
            val pt = ptMatch.groupValues[1].toDoubleOrNull() ?: return null
            val em = (pt / 12.0).coerceIn(0.65, 2.8)
            return String.format(Locale.US, "%.3fem", em)
        }
        val pxMatch = PX_SIZE_REGEX.matchEntire(v)
        if (pxMatch != null) {
            val px = pxMatch.groupValues[1].toDoubleOrNull() ?: return null
            val em = (px / 16.0).coerceIn(0.65, 2.8)
            return String.format(Locale.US, "%.3fem", em)
        }
        return null
    }

    private fun rewriteCssUrls(cssFragment: String, cssBaseDir: String): String {
        if (!cssFragment.contains("url(", ignoreCase = true)) return cssFragment
        return CSS_URL_REGEX.replace(cssFragment) { match ->
            val rawUrl = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'").trim()
            if (rawUrl.isEmpty() || isExternalOrDataUrl(rawUrl) || rawUrl.startsWith("#")) {
                match.value
            } else {
                val resolved = resolveRelativePath(cssBaseDir, rawUrl)
                "url(\"$resolved\")"
            }
        }
    }

    internal fun resolveRelativePath(baseDir: String, relativeHref: String): String {
        val clean = relativeHref.substringBefore('#').substringBefore('?').trim()
        if (clean.isEmpty()) return ""
        if (clean.startsWith("/")) {
            return EpubTocOffsetResolver.normalizeHrefPath(clean)
        }
        val combined = if (baseDir.isBlank()) clean else "$baseDir/$clean"
        return EpubTocOffsetResolver.normalizeHrefPath(combined)
    }

    private fun isExternalOrDataUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("data:") ||
            lower.startsWith("blob:")
    }

    private fun appendNormalizedTextRun(
        out: StringBuilder,
        src: String,
        start: Int,
        end: Int,
        preserveWhitespace: Boolean
    ) {
        if (preserveWhitespace) {
            out.append(src, start, end)
            return
        }
        var lastWasWs = false
        for (i in start until end) {
            val c = src[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (!lastWasWs) {
                    out.append(' ')
                    lastWasWs = true
                }
            } else {
                out.append(c)
                lastWasWs = false
            }
        }
    }

    private fun parseTagAttributes(rawAttrs: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(4)
        val len = rawAttrs.length
        var i = 0
        while (i < len) {
            while (i < len && rawAttrs[i].isWhitespace()) i++
            if (i >= len || rawAttrs[i] == '/') break
            val nameStart = i
            while (i < len && !rawAttrs[i].isWhitespace() && rawAttrs[i] != '=' && rawAttrs[i] != '/') i++
            val name = rawAttrs.substring(nameStart, i)
            while (i < len && rawAttrs[i].isWhitespace()) i++
            if (i >= len || rawAttrs[i] != '=') {
                if (name.isNotEmpty()) out.add(name to "")
                continue
            }
            i++ // skip '='
            while (i < len && rawAttrs[i].isWhitespace()) i++
            if (i >= len) break
            val value: String
            val q = rawAttrs[i]
            if (q == '"' || q == '\'') {
                i++
                val vStart = i
                while (i < len && rawAttrs[i] != q) i++
                value = rawAttrs.substring(vStart, i)
                if (i < len) i++
            } else {
                val vStart = i
                while (i < len && !rawAttrs[i].isWhitespace() && rawAttrs[i] != '/') i++
                value = rawAttrs.substring(vStart, i)
            }
            if (name.isNotEmpty()) {
                out.add(name to value)
            }
        }
        return out
    }

    private fun extractAttrValue(rawAttrs: String, attrName: String): String {
        return parseTagAttributes(rawAttrs)
            .firstOrNull { it.first.equals(attrName, ignoreCase = true) }
            ?.second
            .orEmpty()
    }

    private fun splitCssDeclarations(rawDecls: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var quote = '\u0000'
        var parenDepth = 0
        for (i in rawDecls.indices) {
            val c = rawDecls[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    ';' -> if (parenDepth == 0) {
                        val seg = rawDecls.substring(start, i).trim()
                        if (seg.isNotEmpty()) out.add(seg)
                        start = i + 1
                    }
                }
            }
        }
        if (start < rawDecls.length) {
            val tail = rawDecls.substring(start).trim()
            if (tail.isNotEmpty()) out.add(tail)
        }
        return out
    }

    private fun findClosingBracket(html: String, start: Int): Int {
        var i = start
        val len = html.length
        var quote = '\u0000'
        while (i < len) {
            val c = html[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '>' -> return i
                }
            }
            i++
        }
        return -1
    }

    private fun findClosingTagEnd(html: String, tagName: String, start: Int): Int {
        val len = html.length
        var i = start
        while (i < len) {
            val lt = html.indexOf("</", i)
            if (lt < 0) return -1
            var p = lt + 2
            while (p < len && html[p].isWhitespace()) p++
            val nameStart = p
            while (p < len && (html[p].isLetterOrDigit() || html[p] == ':' || html[p] == '-' || html[p] == '_')) p++
            val found = html.substring(nameStart, p).lowercase(Locale.ROOT).substringAfter(':')
            val gt = html.indexOf('>', p)
            if (gt < 0) return -1
            if (found == tagName) return gt + 1
            i = gt + 1
        }
        return -1
    }

    private fun findNextUnquotedChar(str: String, start: Int, end: Int, target: Char): Int {
        var i = start
        var quote = '\u0000'
        while (i < end) {
            val c = str[i]
            if (quote != '\u0000') {
                if (c == '\\' && i + 1 < end) {
                    i += 2
                    continue
                }
                if (c == quote) quote = '\u0000'
            } else {
                if (c == '"' || c == '\'') quote = c
                else if (c == target) return i
            }
            i++
        }
        return -1
    }

    private fun findMatchingBrace(str: String, openBraceIdx: Int, end: Int): Int {
        var depth = 0
        var i = openBraceIdx
        var quote = '\u0000'
        while (i < end) {
            val c = str[i]
            if (quote != '\u0000') {
                if (c == '\\' && i + 1 < end) {
                    i += 2
                    continue
                }
                if (c == quote) quote = '\u0000'
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun escapeAttr(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) {
            when (c) {
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val BODY_OPEN_REGEX = Regex("(?is)<body\\b([^>]*)>")
    private val HEAD_BLOCK_REGEX = Regex("(?is)<head\\b[^>]*>.*?</head>")
    private val LINK_TAG_REGEX = Regex("(?is)<link\\b([^>]*)>")
    private val STYLE_BLOCK_REGEX = Regex("(?is)<style\\b[^>]*>(.*?)</style>")
    private val CSS_COMMENT_REGEX = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val CSS_URL_REGEX = Regex("(?i)url\\(([^)]+)\\)")
    private val IMPORTANT_REGEX = Regex("(?i)!important")
    private val PT_SIZE_REGEX = Regex("^([0-9]+(?:\\.[0-9]+)?)pt$")
    private val PX_SIZE_REGEX = Regex("^([0-9]+(?:\\.[0-9]+)?)px$")
    private val LEADING_HTML_BODY_REGEX = Regex("(?i)^(?:html\\s+)?body(?=\\s|$|[.#:\\[>+~])(.*)")

    private val strippedSubtreeTags = setOf("script", "style", "noscript", "iframe", "object", "embed", "head", "title")
    private val strippedSingleTags = setOf("link", "meta", "base")
    private val htmlVoidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )
    private val leafBlockCandidateTags = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "blockquote", "td", "th", "dt", "dd", "figcaption", "pre", "div"
    )
    private val relativeFontSizeKeywords = setOf(
        "xx-small", "x-small", "small", "medium", "large", "x-large", "xx-large",
        "smaller", "larger"
    )
}
