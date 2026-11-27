package com.example.freebook.audio.sync

import android.util.LruCache
import com.example.freebook.model.ActiveWordHighlight
import com.example.freebook.model.AudiobookChapterMapping
import com.example.freebook.model.Book
import com.example.freebook.model.PdfPageMetric
import com.example.freebook.model.PublicationLayoutType
import com.example.freebook.ui.reader.PdfTocEntry
import com.example.freebook.ui.reader.ReadiumTocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Format-agnostic chapter entry exposed by [SyncableBook] for both EPUB and PDF.
 */
data class SyncChapter(
    val id: String,
    val title: String,
    val depth: Int,
    val startPageIndex: Int,
    val endPageIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int
)

/**
 * Navigation + highlight target issued to the active reader (EPUB WebView or PDF Compose viewer).
 */
data class SyncTarget(
    val pageIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val highlight: ActiveWordHighlight? = null,
    val shouldScrollOrTurnPage: Boolean = true
)

/**
 * Cumulative spoken-weight distribution across a mapped chapter range,
 * used for Tier-0 Instant Proportional Sync + 1-tap Sync Anchor calibration.
 */
class ChapterWeightDistribution(
    val isPdf: Boolean,
    val isScannedFallback: Boolean,
    val startPageIndex: Int,
    val endPageIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    /** Cumulative weights at end of each segment (page for PDF, paragraph/block for EPUB). */
    val cumulativeWeights: IntArray,
    /** Start page (PDF) or start charOffset (EPUB) of each segment. */
    val segmentPositions: IntArray
) {
    val totalWeight: Int
        get() = if (cumulativeWeights.isNotEmpty()) cumulativeWeights.last().coerceAtLeast(1) else 1

    fun weightAtPosition(pageIndex: Int, charOffset: Int): Int {
        if (segmentPositions.isEmpty()) return 0
        if (isPdf) {
            val idx = (pageIndex - startPageIndex).coerceIn(0, segmentPositions.lastIndex)
            return if (idx == 0) 0 else cumulativeWeights[idx - 1]
        }
        var bestIdx = 0
        for (i in segmentPositions.indices) {
            if (segmentPositions[i] <= charOffset) bestIdx = i else break
        }
        val prevW = if (bestIdx == 0) 0 else cumulativeWeights[bestIdx - 1]
        val segEndW = cumulativeWeights[bestIdx]
        val segW = (segEndW - prevW).coerceAtLeast(0)
        if (segW == 0) return prevW

        val segStart = segmentPositions[bestIdx]
        val segEnd = if (bestIdx + 1 < segmentPositions.size) {
            segmentPositions[bestIdx + 1]
        } else {
            endCharOffset.coerceAtLeast(segStart + 1)
        }
        val spanChars = (segEnd - segStart).coerceAtLeast(1)
        val frac = ((charOffset - segStart).toDouble() / spanChars.toDouble()).coerceIn(0.0, 1.0)
        val maxInSeg = if (bestIdx + 1 < segmentPositions.size) (segEndW - 1).coerceAtLeast(prevW) else segEndW
        return (prevW + (frac * segW).toInt()).coerceIn(prevW, maxInSeg)
    }

    /**
     * Resolves a target cumulative weight `[0..totalWeight]` into a `(pageIndex, charOffset)` pair.
     * Uses upper-bound comparison (`<=`) so the start weight of segment `i` (`cumulativeWeights[i - 1]`)
     * resolves directly to segment `i` rather than off-by-one back to segment `i - 1`.
     */
    fun resolveWeightToPosition(targetWeight: Int): Pair<Int, Int> {
        if (segmentPositions.isEmpty()) {
            return startPageIndex to startCharOffset
        }
        val clamped = targetWeight.coerceIn(0, totalWeight)
        var lo = 0
        var hi = cumulativeWeights.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulativeWeights[mid] <= clamped) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        if (isPdf) {
            return segmentPositions[lo] to 0
        }
        val prevW = if (lo == 0) 0 else cumulativeWeights[lo - 1]
        val segW = (cumulativeWeights[lo] - prevW).coerceAtLeast(1)
        val frac = ((clamped - prevW).toDouble() / segW.toDouble()).coerceIn(0.0, 1.0)
        val segStart = segmentPositions[lo]
        val segEnd = if (lo + 1 < segmentPositions.size) {
            segmentPositions[lo + 1]
        } else {
            endCharOffset.coerceAtLeast(segStart + 1)
        }
        val maxChar = (endCharOffset - 1).coerceAtLeast(startCharOffset)
        val resolvedChar = (segStart + (frac * (segEnd - segStart)).toInt()).coerceIn(startCharOffset, maxChar)
        return 0 to resolvedChar
    }
}

/**
 * Thin, unified Sync Bridge sitting above both [com.example.freebook.ui.reader.ReadiumWebViewNavigator]
 * (for EPUB) and [com.example.freebook.ui.reader.CustomPdfReader] (`io.legere:pdfiumandroid` for PDF).
 *
 * Keeps TTS, Audiobook playback, AudioAligner, and SyncResolver 100% agnostic to file format.
 */
interface SyncableBook {
    val book: Book
    val isPdf: Boolean

    fun getChapters(): List<SyncChapter>

    suspend fun getChapterWords(mapping: AudiobookChapterMapping): List<IndexedBookWord>

    suspend fun buildWeightDistribution(mapping: AudiobookChapterMapping): ChapterWeightDistribution

    suspend fun loadTtsChunksForUnit(unitIndex: Int, startCharOffset: Int = 0): List<TtsSpeechChunk>

    fun getTotalTtsUnits(): Int

    fun seekToPosition(target: SyncTarget)
}

/**
 * EPUB implementation of [SyncableBook] backed by canonical book text + Readium TOC entries.
 */
class EpubSyncableBook(
    override val book: Book,
    private val canonicalBookText: String,
    private val tocEntries: List<ReadiumTocEntry>,
    private val onSeekAndHighlight: (SyncTarget) -> Unit
) : SyncableBook {

    override val isPdf: Boolean = false

    override fun getChapters(): List<SyncChapter> {
        val totalLen = canonicalBookText.length.coerceAtLeast(1)
        if (tocEntries.isEmpty()) {
            return listOf(
                SyncChapter(
                    id = "epub_full_${book.id}",
                    title = book.title,
                    depth = 0,
                    startPageIndex = 0,
                    endPageIndex = 0,
                    startCharOffset = 0,
                    endCharOffset = totalLen
                )
            )
        }

        val resolvedOffsets = tocEntries.map { entry ->
            ((entry.locator?.locations?.otherLocations?.get("charOffset") as? Number)?.toInt()
                ?: entry.locator?.locations?.totalProgression?.let { (it * totalLen).toInt() }
                ?: 0).coerceIn(0, totalLen - 1)
        }

        return tocEntries.mapIndexed { idx, entry ->
            val start = resolvedOffsets[idx]
            val nextStart = resolvedOffsets.drop(idx + 1).firstOrNull { it > start } ?: totalLen
            SyncChapter(
                id = entry.id,
                title = entry.title,
                depth = entry.depth,
                startPageIndex = 0,
                endPageIndex = 0,
                startCharOffset = start,
                endCharOffset = nextStart.coerceAtLeast(start + 1).coerceAtMost(totalLen)
            )
        }
    }

    override suspend fun getChapterWords(mapping: AudiobookChapterMapping): List<IndexedBookWord> {
        if (canonicalBookText.isEmpty()) return emptyList()
        val safeStart = mapping.bookStartCharOffset.coerceIn(0, canonicalBookText.length)
        val safeEnd = if (mapping.bookEndCharOffset > safeStart) {
            mapping.bookEndCharOffset.coerceAtMost(canonicalBookText.length)
        } else {
            canonicalBookText.length
        }
        val slice = canonicalBookText.substring(safeStart, safeEnd)
        return BookWordTokenizer.tokenizeSegment(
            rawText = slice,
            pageIndex = 0,
            rawBaseOffset = safeStart,
            startWordIndex = 0,
            isFromPdf = false
        )
    }

    override suspend fun buildWeightDistribution(mapping: AudiobookChapterMapping): ChapterWeightDistribution {
        val safeStart = mapping.bookStartCharOffset.coerceIn(0, canonicalBookText.length)
        val safeEnd = if (mapping.bookEndCharOffset > safeStart) {
            mapping.bookEndCharOffset.coerceAtMost(canonicalBookText.length)
        } else {
            canonicalBookText.length
        }
        val positions = ArrayList<Int>()
        val weights = ArrayList<Int>()
        var running = 0
        var cursor = safeStart
        while (cursor < safeEnd) {
            val nextBreak = canonicalBookText.indexOf("\n\n", cursor).let {
                if (it in cursor until safeEnd) it + 2 else (cursor + 320).coerceAtMost(safeEnd)
            }
            val w = BookWordTokenizer.computeSpokenWeight(canonicalBookText, cursor, nextBreak).coerceAtLeast(1)
            running += w
            positions.add(cursor)
            weights.add(running)
            cursor = nextBreak
        }
        return ChapterWeightDistribution(
            isPdf = false,
            isScannedFallback = false,
            startPageIndex = 0,
            endPageIndex = 0,
            startCharOffset = safeStart,
            endCharOffset = safeEnd,
            cumulativeWeights = weights.toIntArray(),
            segmentPositions = positions.toIntArray()
        )
    }

    @Volatile
    private var cachedFullBookChunks: List<TtsSpeechChunk>? = null

    /**
     * Always returns chunks for the ENTIRE book (`0..canonicalBookText.length`) so the TTS
     * scrubber, sentence skip, and word jump can freely navigate to any chapter from start to end
     * without ever locking onto where TTS first started.
     */
    override suspend fun loadTtsChunksForUnit(unitIndex: Int, startCharOffset: Int): List<TtsSpeechChunk> {
        if (unitIndex != 0 || canonicalBookText.isEmpty()) return emptyList()
        cachedFullBookChunks?.let { return it }
        val built = withContext(Dispatchers.Default) {
            BookWordTokenizer.splitIntoTtsChunks(
                rawText = canonicalBookText,
                pageIndex = 0,
                rawBaseOffset = 0,
                isFromPdf = false
            )
        }
        cachedFullBookChunks = built
        return built
    }

    override fun getTotalTtsUnits(): Int = 1

    override fun seekToPosition(target: SyncTarget) {
        onSeekAndHighlight(target)
    }
}

/**
 * PDF implementation of [SyncableBook] backed directly by `io.legere:pdfiumandroid` + [PdfTextIndexer].
 */
class PdfSyncableBook(
    override val book: Book,
    private val totalPages: Int,
    private val tocEntries: List<PdfTocEntry>,
    private val pdfTextIndexer: PdfTextIndexer,
    private val onSeekAndHighlight: (SyncTarget) -> Unit
) : SyncableBook {

    override val isPdf: Boolean = true

    override fun getChapters(): List<SyncChapter> {
        val safeTotal = totalPages.coerceAtLeast(1)
        if (tocEntries.isEmpty()) {
            return listOf(
                SyncChapter(
                    id = "pdf_full_${book.id}",
                    title = "${book.title} (Pages 1–$safeTotal)",
                    depth = 0,
                    startPageIndex = 0,
                    endPageIndex = safeTotal - 1,
                    startCharOffset = 0,
                    endCharOffset = 0
                )
            )
        }

        return tocEntries.mapIndexed { idx, entry ->
            val startP = entry.pageIndex.coerceIn(0, safeTotal - 1)
            val nextStartP = tocEntries.drop(idx + 1).firstOrNull { it.pageIndex > startP }?.pageIndex
            val endP = ((nextStartP ?: safeTotal) - 1).coerceIn(startP, safeTotal - 1)
            SyncChapter(
                id = entry.id,
                title = entry.title,
                depth = entry.depth,
                startPageIndex = startP,
                endPageIndex = endP,
                startCharOffset = 0,
                endCharOffset = 0
            )
        }
    }

    override suspend fun getChapterWords(mapping: AudiobookChapterMapping): List<IndexedBookWord> {
        val safeTotal = totalPages.coerceAtLeast(1)
        val startP = mapping.bookStartPageIndex.coerceIn(0, safeTotal - 1)
        val endP = mapping.bookEndPageIndex.coerceIn(startP, safeTotal - 1)
        val pages = pdfTextIndexer.getPageRangeTexts(book, startP, endP)
        val allWords = ArrayList<IndexedBookWord>(pages.size * 220)
        var wordCursor = 0
        for (page in pages) {
            if (page.isScanned) continue
            val pageWords = BookWordTokenizer.tokenizeSegment(
                rawText = page.bodyText,
                pageIndex = page.pageIndex,
                rawBaseOffset = page.bodyStartChar,
                startWordIndex = wordCursor,
                isFromPdf = true
            )
            allWords.addAll(pageWords)
            wordCursor += pageWords.size
        }
        return allWords
    }

    override suspend fun buildWeightDistribution(mapping: AudiobookChapterMapping): ChapterWeightDistribution {
        val safeTotal = totalPages.coerceAtLeast(1)
        val startP = mapping.bookStartPageIndex.coerceIn(0, safeTotal - 1)
        val endP = mapping.bookEndPageIndex.coerceIn(startP, safeTotal - 1)
        val allMetrics: Map<Int, PdfPageMetric> = pdfTextIndexer.ensureBookMetricsIndexed(book)
            .associateBy { it.pageIndex }

        val pageCount = endP - startP + 1
        val positions = IntArray(pageCount)
        val cumulative = IntArray(pageCount)
        var nonScannedPages = 0
        var totalWeight = 0

        for (i in 0 until pageCount) {
            val p = startP + i
            positions[i] = p
            val metric = allMetrics[p]
            val w = if (metric != null && !metric.isScanned && metric.weightedCharCount > 0) {
                nonScannedPages++
                metric.weightedCharCount
            } else {
                0
            }
            totalWeight += w
            cumulative[i] = totalWeight
        }

        // Scanned PDF fallback: equal time-per-page division when no OCR text layer exists
        val isScanned = nonScannedPages == 0 || totalWeight == 0
        if (isScanned) {
            for (i in 0 until pageCount) {
                cumulative[i] = (i + 1) * 100
            }
        } else {
            // Give blank/illustration pages inside a text PDF a tiny weight so cumulative stays strictly monotonic
            var fixedRunning = 0
            for (i in 0 until pageCount) {
                val p = startP + i
                val metric = allMetrics[p]
                val w = (metric?.weightedCharCount ?: 0).coerceAtLeast(15)
                fixedRunning += w
                cumulative[i] = fixedRunning
            }
        }

        return ChapterWeightDistribution(
            isPdf = true,
            isScannedFallback = isScanned,
            startPageIndex = startP,
            endPageIndex = endP,
            startCharOffset = 0,
            endCharOffset = 0,
            cumulativeWeights = cumulative,
            segmentPositions = positions
        )
    }

    private val pageChunksCache = LruCache<Int, List<TtsSpeechChunk>>(12)

    /**
     * Always returns all TTS chunks for the full page body (`bodyStartChar..bodyEndChar`) so
     * skipping backward or double-tapping any word on the page works freely without truncation.
     */
    override suspend fun loadTtsChunksForUnit(unitIndex: Int, startCharOffset: Int): List<TtsSpeechChunk> {
        if (unitIndex !in 0 until totalPages.coerceAtLeast(1)) return emptyList()
        pageChunksCache.get(unitIndex)?.let { return it }
        val pageText = pdfTextIndexer.getPageText(book, unitIndex)
        if (pageText.isScanned || pageText.bodyText.isBlank()) return emptyList()

        val slice = pageText.fullText.substring(pageText.bodyStartChar, pageText.bodyEndChar)
        val built = withContext(Dispatchers.Default) {
            BookWordTokenizer.splitIntoTtsChunks(
                rawText = slice,
                pageIndex = unitIndex,
                rawBaseOffset = pageText.bodyStartChar,
                isFromPdf = true
            )
        }
        pageChunksCache.put(unitIndex, built)
        return built
    }

    override fun getTotalTtsUnits(): Int = totalPages.coerceAtLeast(1)

    override fun seekToPosition(target: SyncTarget) {
        onSeekAndHighlight(target)
    }
}

fun isFixedPdfBook(book: Book): Boolean = book.layoutType == PublicationLayoutType.FIXED
