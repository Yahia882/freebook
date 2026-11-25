package com.example.freebook.audio.sync

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.example.freebook.data.local.room.AudiobookDao
import com.example.freebook.data.local.room.PdfPageMetricEntity
import com.example.freebook.model.Book
import com.example.freebook.model.PdfPageMetric
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class ExtractedPdfPageText(
    val pageIndex: Int,
    val fullText: String,
    val bodyStartChar: Int,
    val bodyEndChar: Int,
    val isScanned: Boolean
) {
    val bodyText: String
        get() = if (bodyEndChar > bodyStartChar && bodyEndChar <= fullText.length) {
            fullText.substring(bodyStartChar, bodyEndChar)
        } else {
            fullText
        }
}

/**
 * Thread-safe PDF text & metrics indexer backed by `io.legere:pdfiumandroid`.
 *
 * - Serializes all native PDFium calls on a single-threaded IO dispatcher so native C++ calls
 *   never race.
 * - Caches lightweight per-page counts/weights (`PdfPageMetricEntity`) in Room and keeps
 *   per-page extracted text in a bounded memory LRU cache (never storing bloated RectFs in SQLite).
 * - Performs smart repeating header/footer detection in raw PDF point coordinates (top/bottom 8% bands)
 *   so running titles and page numbers don't skew TTS reading or proportional audiobook sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfTextIndexer(
    private val appContext: Context,
    private val audiobookDao: AudiobookDao
) {
    private val pdfIoDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Keyed by "bookId:pageIndex"
    private val pageTextCache = LruCache<String, ExtractedPdfPageText>(48)

    private val pageNumberRegex = Regex(
        pattern = """^[\s\-–—•|]*(?:page|p\.?|ص\.?|صفحة)?\s*[\d٠-٩]+(?:\s*/\s*[\d٠-٩]+)?[\s\-–—•|]*$""",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Ensures lightweight [PdfPageMetric] rows exist in Room for [book].
     * Returns immediately if already cached.
     */
    suspend fun ensureBookMetricsIndexed(book: Book): List<PdfPageMetric> = withContext(pdfIoDispatcher) {
        val existing = audiobookDao.getPdfPageMetricsOnce(book.id)
        if (existing.isNotEmpty() && existing.size >= book.totalPages.coerceAtLeast(1)) {
            return@withContext existing.map { it.toDomainModel() }
        }

        val pdfFile = File(book.bookFilePath)
        if (!pdfFile.exists()) return@withContext emptyList()

        val pfd = runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return@withContext emptyList()

        val pdfiumCore = PdfiumCore(appContext)
        return@withContext pfd.use { descriptor ->
            runCatching {
                pdfiumCore.newDocument(descriptor).use { doc ->
                    val pageCount = doc.getPageCount().coerceAtLeast(1)

                    // Pass 1: Sample up to 24 evenly distributed pages to detect repeating header/footer strings
                    val sampleStep = (pageCount / 24).coerceAtLeast(1)
                    val topFreq = HashMap<String, Int>()
                    val bottomFreq = HashMap<String, Int>()
                    var sampledPages = 0

                    var sampleIdx = 0
                    while (sampleIdx < pageCount) {
                        val bands = inspectMarginLines(doc, sampleIdx)
                        if (bands != null) {
                            sampledPages++
                            bands.topKey?.let { topFreq[it] = (topFreq[it] ?: 0) + 1 }
                            bands.bottomKey?.let { bottomFreq[it] = (bottomFreq[it] ?: 0) + 1 }
                        }
                        sampleIdx += sampleStep
                    }

                    val repeatThreshold = (sampledPages * 0.30f).toInt().coerceAtLeast(2)
                    val repeatedHeaders = topFreq.filterValues { it >= repeatThreshold }.keys
                    val repeatedFooters = bottomFreq.filterValues { it >= repeatThreshold }.keys

                    // Pass 2: Extract lightweight per-page counts and body bounds
                    val entities = ArrayList<PdfPageMetricEntity>(pageCount)
                    for (pageIdx in 0 until pageCount) {
                        val extracted = extractSinglePageInternal(
                            doc = doc,
                            pageIndex = pageIdx,
                            repeatedHeaders = repeatedHeaders,
                            repeatedFooters = repeatedFooters
                        )
                        val body = extracted.bodyText
                        val weight = BookWordTokenizer.computeSpokenWeight(body)
                        val words = countQuickWords(body)
                        entities.add(
                            PdfPageMetricEntity(
                                bookId = book.id,
                                pageIndex = pageIdx,
                                charCount = extracted.fullText.length,
                                wordCount = words,
                                weightedCharCount = weight,
                                isScanned = extracted.isScanned,
                                bodyStartChar = extracted.bodyStartChar,
                                bodyEndChar = extracted.bodyEndChar
                            )
                        )
                    }

                    audiobookDao.upsertPdfPageMetrics(entities)
                    entities.map { it.toDomainModel() }
                }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Loads a single PDF page's text quickly for instant TTS start or word tokenization,
     * applying cached body bounds if available.
     */
    suspend fun getPageText(book: Book, pageIndex: Int): ExtractedPdfPageText = withContext(pdfIoDispatcher) {
        val cacheKey = "${book.id}:$pageIndex"
        pageTextCache.get(cacheKey)?.let { return@withContext it }

        val pdfFile = File(book.bookFilePath)
        if (!pdfFile.exists()) {
            return@withContext ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true)
        }

        val pfd = runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return@withContext ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true)

        val pdfiumCore = PdfiumCore(appContext)
        val extracted = pfd.use { descriptor ->
            runCatching {
                pdfiumCore.newDocument(descriptor).use { doc ->
                    val pageCount = doc.getPageCount()
                    if (pageIndex !in 0 until pageCount) {
                        return@use ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true)
                    }
                    extractSinglePageInternal(doc, pageIndex, emptySet(), emptySet())
                }
            }.getOrDefault(ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true))
        }

        pageTextCache.put(cacheKey, extracted)
        extracted
    }

    /**
     * Batch-extracts text for a contiguous chapter page range `[startPageIndex..endPageIndex]`
     * using a single native `PdfDocument` open/close cycle (fast & memory-safe).
     */
    suspend fun getPageRangeTexts(
        book: Book,
        startPageIndex: Int,
        endPageIndex: Int
    ): List<ExtractedPdfPageText> = withContext(pdfIoDispatcher) {
        val pdfFile = File(book.bookFilePath)
        if (!pdfFile.exists()) return@withContext emptyList()

        val cachedMetrics = audiobookDao.getPdfPageMetricsOnce(book.id).associateBy { it.pageIndex }

        val pfd = runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return@withContext emptyList()

        val pdfiumCore = PdfiumCore(appContext)
        return@withContext pfd.use { descriptor ->
            runCatching {
                pdfiumCore.newDocument(descriptor).use { doc ->
                    val pageCount = doc.getPageCount().coerceAtLeast(1)
                    val safeStart = startPageIndex.coerceIn(0, pageCount - 1)
                    val safeEnd = endPageIndex.coerceIn(safeStart, pageCount - 1)
                    val out = ArrayList<ExtractedPdfPageText>(safeEnd - safeStart + 1)

                    for (p in safeStart..safeEnd) {
                        val cacheKey = "${book.id}:$p"
                        val cached = pageTextCache.get(cacheKey)
                        if (cached != null) {
                            out.add(cached)
                            continue
                        }
                        val metric = cachedMetrics[p]
                        val extracted = if (metric != null) {
                            extractRawPageWithSavedBounds(doc, p, metric.bodyStartChar, metric.bodyEndChar)
                        } else {
                            extractSinglePageInternal(doc, p, emptySet(), emptySet())
                        }
                        pageTextCache.put(cacheKey, extracted)
                        out.add(extracted)
                    }
                    out
                }
            }.getOrDefault(emptyList())
        }
    }

    private data class MarginInspection(
        val topKey: String?,
        val bottomKey: String?
    )

    private fun inspectMarginLines(doc: PdfDocument, pageIndex: Int): MarginInspection? {
        return runCatching {
            doc.openPage(pageIndex)?.use { page ->
                val pageH = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)
                page.openTextPage().use { textPage ->
                    val count = textPage.textPageCountChars()
                    if (count <= 0) return@use null
                    val raw = textPage.textPageGetText(0, count).orEmpty()
                    if (raw.isBlank()) return@use null

                    val firstLineEnd = raw.indexOf('\n').takeIf { it > 0 } ?: minOf(raw.length, 64)
                    val lastLineStart = raw.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1)
                        ?: (raw.length - 64).coerceAtLeast(0)

                    val topBandKey = checkIfMarginBand(
                        textPage = textPage,
                        raw = raw,
                        start = 0,
                        end = firstLineEnd,
                        pageH = pageH,
                        expectTop = true
                    )
                    val bottomBandKey = checkIfMarginBand(
                        textPage = textPage,
                        raw = raw,
                        start = lastLineStart,
                        end = raw.length,
                        pageH = pageH,
                        expectTop = false
                    )
                    MarginInspection(topBandKey, bottomBandKey)
                }
            }
        }.getOrNull()
    }

    private fun checkIfMarginBand(
        textPage: io.legere.pdfiumandroid.PdfTextPage,
        raw: String,
        start: Int,
        end: Int,
        pageH: Float,
        expectTop: Boolean
    ): String? {
        if (end <= start) return null
        val snippet = raw.substring(start, end).trim()
        if (snippet.isEmpty() || snippet.length > 72) return null

        // Probe the first visible character's PDF point Y coordinate (PDF origin is bottom-left)
        var probeIdx = start
        while (probeIdx < end && raw[probeIdx].isWhitespace()) probeIdx++
        if (probeIdx >= end) return null

        val box = runCatching { textPage.textPageGetCharBox(probeIdx) }.getOrNull() ?: return null
        if (box.top == 0f && box.bottom == 0f) return null

        val centerYFromBottom = ((box.top + box.bottom) * 0.5f).coerceIn(0f, pageH)
        val fracFromTop = 1f - (centerYFromBottom / pageH)

        val inBand = if (expectTop) fracFromTop <= 0.085f else fracFromTop >= 0.915f
        return if (inBand) normalizeHeaderKey(snippet) else null
    }

    private fun normalizeHeaderKey(raw: String): String =
        raw.replace(Regex("[\\d٠-٩]+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)

    private fun extractRawPageWithSavedBounds(
        doc: PdfDocument,
        pageIndex: Int,
        savedBodyStart: Int,
        savedBodyEnd: Int
    ): ExtractedPdfPageText {
        return runCatching {
            doc.openPage(pageIndex)?.use { page ->
                page.openTextPage().use { textPage ->
                    val count = textPage.textPageCountChars()
                    if (count <= 0) {
                        return@use ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true)
                    }
                    val fullText = textPage.textPageGetText(0, count).orEmpty()
                    val safeStart = savedBodyStart.coerceIn(0, fullText.length)
                    val safeEnd = savedBodyEnd.coerceIn(safeStart, fullText.length)
                    ExtractedPdfPageText(
                        pageIndex = pageIndex,
                        fullText = fullText,
                        bodyStartChar = safeStart,
                        bodyEndChar = if (safeEnd > safeStart) safeEnd else fullText.length,
                        isScanned = fullText.isBlank()
                    )
                }
            }
        }.getOrNull() ?: ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true)
    }

    private fun extractSinglePageInternal(
        doc: PdfDocument,
        pageIndex: Int,
        repeatedHeaders: Set<String>,
        repeatedFooters: Set<String>
    ): ExtractedPdfPageText {
        return runCatching {
            doc.openPage(pageIndex)?.use { page ->
                val pageH = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)
                page.openTextPage().use { textPage ->
                    val count = textPage.textPageCountChars()
                    if (count <= 0) {
                        return@use ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true)
                    }
                    val fullText = textPage.textPageGetText(0, count).orEmpty()
                    if (fullText.isBlank()) {
                        return@use ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true)
                    }

                    var bodyStart = 0
                    var bodyEnd = fullText.length

                    // Check first line for page number or repeated running header in top 8.5%
                    val firstNl = fullText.indexOf('\n')
                    if (firstNl in 1 until (fullText.length - 10)) {
                        val firstLine = fullText.substring(0, firstNl).trim()
                        val topBandKey = checkIfMarginBand(textPage, fullText, 0, firstNl, pageH, expectTop = true)
                        if (topBandKey != null && (pageNumberRegex.matches(firstLine) || topBandKey in repeatedHeaders)) {
                            bodyStart = (firstNl + 1).coerceAtMost(fullText.length)
                        }
                    }

                    // Check last line for page number or repeated running footer in bottom 8.5%
                    val lastNl = fullText.lastIndexOf('\n')
                    if (lastNl > bodyStart + 10) {
                        val lastLine = fullText.substring(lastNl + 1).trim()
                        val bottomBandKey = checkIfMarginBand(
                            textPage,
                            fullText,
                            lastNl + 1,
                            fullText.length,
                            pageH,
                            expectTop = false
                        )
                        if (bottomBandKey != null && (pageNumberRegex.matches(lastLine) || bottomBandKey in repeatedFooters)) {
                            bodyEnd = lastNl.coerceAtLeast(bodyStart)
                        }
                    }

                    ExtractedPdfPageText(
                        pageIndex = pageIndex,
                        fullText = fullText,
                        bodyStartChar = bodyStart,
                        bodyEndChar = bodyEnd,
                        isScanned = false
                    )
                }
            }
        }.getOrNull() ?: ExtractedPdfPageText(pageIndex, "", 0, 0, isScanned = true)
    }

    private fun countQuickWords(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            val isLetter = Character.isLetterOrDigit(ch)
            if (isLetter && !inWord) {
                count++
                inWord = true
            } else if (!isLetter && !ch.isISOControl() && Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                inWord = false
            }
        }
        return count
    }
}
