package com.example.freebook.ui.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.BookReadingDirection
import com.example.freebook.model.HighlightColor
import com.example.freebook.model.ReaderNavigationMode
import com.example.freebook.model.ReaderTheme
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.Bookmark
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps a white-backed PDF bitmap to seamlessly blend with the active [ReaderTheme].
 * - LIGHT: original crisp white page (#FFFFFF)
 * - SEPIA: warm cream paper tint (#F4ECD8)
 * - DARK: inverted luminance mapping white paper to #121212 and dark ink to soft off-white #E0E0E0
 * - AMOLED: inverted luminance mapping white paper to true black #000000 and dark ink to #CCCCCC
 */
private fun pdfThemeColorFilter(readerTheme: ReaderTheme): ColorFilter? = when (readerTheme) {
    ReaderTheme.LIGHT -> null
    ReaderTheme.SEPIA -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                244f / 255f, 0f, 0f, 0f, 0f,
                0f, 236f / 255f, 0f, 0f, 0f,
                0f, 0f, 216f / 255f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    ReaderTheme.DARK -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -0.808f, 0f, 0f, 0f, 224f,
                0f, -0.808f, 0f, 0f, 224f,
                0f, 0f, -0.808f, 0f, 224f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    ReaderTheme.AMOLED -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -0.80f, 0f, 0f, 0f, 204f,
                0f, -0.80f, 0f, 0f, 204f,
                0f, 0f, -0.80f, 0f, 204f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

private fun detectPdfIsMostlyRtl(doc: PdfDocument, pageCount: Int, fallbackTitle: String): Boolean {
    val sample = StringBuilder()
    sample.append(fallbackTitle).append(' ')
    runCatching { doc.getDocumentMeta().title }.getOrNull()?.let { sample.append(it).append(' ') }

    val pagesToScan = minOf(5, pageCount)
    for (i in 0 until pagesToScan) {
        if (sample.length >= 1200) break
        runCatching {
            doc.openPage(i)?.use { page ->
                page.openTextPage().use { textPage ->
                    val count = textPage.textPageCountChars().coerceAtMost(400)
                    if (count > 0) {
                        val text = textPage.textPageGetText(0, count)
                        if (!text.isNullOrBlank()) {
                            sample.append(text).append(' ')
                        }
                    }
                }
            }
        }
    }

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

private data class PdfCropFractions(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val widthFrac: Float get() = (right - left).coerceIn(0.25f, 1f)
    val heightFrac: Float get() = (bottom - top).coerceIn(0.25f, 1f)
}

/**
 * Extracted text and normalized `[0f..1f]` character bounding boxes for a single rendered PDF page.
 * Each `charRects[i]` corresponds to `fullText[i]` in the cropped page's display coordinate space.
 */
private data class PdfPageTextLayout(
    val fullText: String,
    val charRects: List<RectF>
)

private data class PdfActiveSelection(
    val pageIndex: Int,
    val startIndex: Int,
    val endIndex: Int
)

private enum class ActiveHandleDrag {
    NONE,
    START,
    END
}

/**
 * Detects the tight content bounding box of a PDF page (trimming baked-in whitespace margins)
 * by rendering a tiny low-res probe bitmap and scanning for non-white pixels.
 * Falls back to uncropped bounds for blank or sparse pages so content is never over-zoomed.
 */
private fun detectContentCropFractions(
    page: PdfPage,
    rawW: Int,
    rawH: Int
): PdfCropFractions {
    val probeW = 160
    val probeH = (probeW.toFloat() * rawH.toFloat() / rawW.toFloat())
        .roundToInt()
        .coerceIn(80, 320)

    val probe = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
    return try {
        probe.eraseColor(AndroidColor.WHITE)
        page.renderPageBitmap(
            bitmap = probe,
            startX = 0,
            startY = 0,
            drawSizeX = probeW,
            drawSizeY = probeH,
            renderAnnot = true
        )
        val pixels = IntArray(probeW * probeH)
        probe.getPixels(pixels, 0, probeW, 0, 0, probeW, probeH)

        var minX = probeW
        var minY = probeH
        var maxX = -1
        var maxY = -1

        for (y in 0 until probeH) {
            val rowOffset = y * probeW
            for (x in 0 until probeW) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (r < 245 || g < 245 || b < 245) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return PdfCropFractions()
        }

        val contentWFrac = (maxX - minX + 1).toFloat() / probeW.toFloat()
        val contentHFrac = (maxY - minY + 1).toFloat() / probeH.toFloat()
        if (contentWFrac < 0.2f || contentHFrac < 0.12f) {
            // Very sparse / title-only page: keep full page framing
            return PdfCropFractions()
        }

        // Keep a small breathing margin (~2.5% horizontal, ~2% vertical) and avoid extreme zooms
        val left = (minX.toFloat() / probeW.toFloat() - 0.025f).coerceIn(0f, 0.35f)
        val right = ((maxX + 1).toFloat() / probeW.toFloat() + 0.025f).coerceIn(0.65f, 1f)
        val top = (minY.toFloat() / probeH.toFloat() - 0.02f).coerceIn(0f, 0.35f)
        val bottom = ((maxY + 1).toFloat() / probeH.toFloat() + 0.02f).coerceIn(0.65f, 1f)

        PdfCropFractions(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    } catch (_: Exception) {
        PdfCropFractions()
    } finally {
        probe.recycle()
    }
}

/**
 * Extracts every character and its normalized `[0f..1f]` display-space [RectF] using [PdfPage.openTextPage]
 * and [PdfPage.mapRectToDevice] with the exact same crop offsets and scale used by `renderPageBitmap`.
 */
private fun extractPageTextLayout(
    page: PdfPage,
    startX: Int,
    startY: Int,
    fullDrawW: Int,
    fullDrawH: Int,
    renderWidth: Int,
    renderHeight: Int
): PdfPageTextLayout {
    return runCatching {
        page.openTextPage().use { textPage ->
            val charCount = textPage.textPageCountChars()
            if (charCount <= 0) return@use PdfPageTextLayout("", emptyList())
            val fullText = textPage.textPageGetText(0, charCount).orEmpty()
            val count = minOf(charCount, fullText.length)
            if (count <= 0) return@use PdfPageTextLayout("", emptyList())

            val invW = 1f / renderWidth.coerceAtLeast(1).toFloat()
            val invH = 1f / renderHeight.coerceAtLeast(1).toFloat()
            val rects = ArrayList<RectF>(count)

            for (i in 0 until count) {
                val box = runCatching { textPage.textPageGetCharBox(i) }.getOrNull()
                if (box == null || (box.left == 0f && box.right == 0f && box.top == 0f && box.bottom == 0f)) {
                    rects.add(RectF())
                } else {
                    val dev = page.mapRectToDevice(
                        startX = startX,
                        startY = startY,
                        sizeX = fullDrawW,
                        sizeY = fullDrawH,
                        rotate = 0,
                        coords = box
                    )
                    val l = (min(dev.left, dev.right) * invW).coerceIn(0f, 1f)
                    val r = (max(dev.left, dev.right) * invW).coerceIn(0f, 1f)
                    val t = (min(dev.top, dev.bottom) * invH).coerceIn(0f, 1f)
                    val b = (max(dev.top, dev.bottom) * invH).coerceIn(0f, 1f)
                    if (r > l && b > t) {
                        rects.add(RectF(l, t, r, b))
                    } else {
                        rects.add(RectF())
                    }
                }
            }
            PdfPageTextLayout(fullText = fullText, charRects = rects)
        }
    }.getOrDefault(PdfPageTextLayout("", emptyList()))
}

private fun findNearestCharIndex(
    layout: PdfPageTextLayout,
    nx: Float,
    ny: Float,
    maxDistance: Float = 0.08f
): Int? {
    val rects = layout.charRects
    if (rects.isEmpty()) return null

    // 1. Direct containment hit (with tiny vertical tolerance)
    for (i in rects.indices) {
        val r = rects[i]
        if (r.isEmpty) continue
        if (nx >= r.left - 0.004f && nx <= r.right + 0.004f && ny >= r.top - 0.006f && ny <= r.bottom + 0.006f) {
            return i
        }
    }

    // 2. Nearest character center weighted slightly in favor of same line
    var bestIdx: Int? = null
    var bestDist = maxDistance
    for (i in rects.indices) {
        val r = rects[i]
        if (r.isEmpty) continue
        val cx = (r.left + r.right) * 0.5f
        val cy = (r.top + r.bottom) * 0.5f
        val dx = nx - cx
        val dy = (ny - cy) * 1.35f
        val d = hypot(dx, dy)
        if (d < bestDist) {
            bestDist = d
            bestIdx = i
        }
    }
    return bestIdx
}

private fun expandToWordBounds(text: String, anchorIdx: Int): IntRange {
    if (text.isEmpty()) return 0..0
    val safeIdx = anchorIdx.coerceIn(0, text.lastIndex)
    if (!isWordChar(text[safeIdx])) return safeIdx..safeIdx

    var start = safeIdx
    while (start > 0 && isWordChar(text[start - 1])) {
        start--
    }
    var end = safeIdx
    while (end < text.lastIndex && isWordChar(text[end + 1])) {
        end++
    }
    return start..end
}

private fun isWordChar(ch: Char): Boolean =
    Character.isLetterOrDigit(ch) || ch == '\'' || ch == '\u2019' || ch == '-' ||
        Character.getType(ch) == Character.NON_SPACING_MARK.toInt()

/**
 * Merges consecutive character bounding boxes on the same visual line into smooth,
 * contiguous normalized [RectF] highlight bands.
 */
private fun mergeRangeToLineRects(
    charRects: List<RectF>,
    startIndex: Int,
    endIndexInclusive: Int
): List<RectF> {
    if (charRects.isEmpty()) return emptyList()
    val safeStart = min(startIndex, endIndexInclusive).coerceIn(0, charRects.lastIndex)
    val safeEnd = max(startIndex, endIndexInclusive).coerceIn(0, charRects.lastIndex)

    val merged = mutableListOf<RectF>()
    var current: RectF? = null

    for (i in safeStart..safeEnd) {
        val r = charRects[i]
        if (r.isEmpty) continue
        val cur = current
        if (cur == null) {
            current = RectF(r)
        } else {
            val curHeight = cur.height().coerceAtLeast(0.005f)
            val verticalOverlap = min(cur.bottom, r.bottom) - max(cur.top, r.top)
            val sameLine = verticalOverlap >= curHeight * 0.35f ||
                abs(cur.centerY() - r.centerY()) <= curHeight * 0.55f
            if (sameLine) {
                cur.left = min(cur.left, r.left)
                cur.top = min(cur.top, r.top)
                cur.right = max(cur.right, r.right)
                cur.bottom = max(cur.bottom, r.bottom)
            } else {
                merged.add(cur)
                current = RectF(r)
            }
        }
    }
    if (current != null) merged.add(current)
    return merged
}

private fun extractSelectionSnippet(
    fullText: String,
    startIndex: Int,
    endIndexInclusive: Int
): String {
    if (fullText.isEmpty()) return ""
    val s = min(startIndex, endIndexInclusive).coerceIn(0, fullText.lastIndex)
    val e = max(startIndex, endIndexInclusive).coerceIn(0, fullText.lastIndex)
    return fullText.substring(s, e + 1)
        .replace("\r\n", " ")
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * Lightweight table-of-contents entry extracted directly from a PDF outline via `io.legere:pdfiumandroid`.
 */
data class PdfTocEntry(
    val id: String,
    val title: String,
    val depth: Int,
    val pageIndex: Int
)

private class ManagedPdfDocument(
    val fileDescriptor: ParcelFileDescriptor,
    val document: PdfDocument,
    val pageCount: Int,
    val tocEntries: List<PdfTocEntry>,
    val defaultAspectRatio: Float,
    val detectedRtl: Boolean
) {
    fun closeQuietly() {
        runCatching { document.close() }
        runCatching { fileDescriptor.close() }
    }
}

private fun flattenPdfBookmarks(
    bookmarks: List<Bookmark>,
    totalPages: Int,
    depth: Int = 0,
    out: MutableList<PdfTocEntry> = mutableListOf()
): List<PdfTocEntry> {
    for (bm in bookmarks) {
        val title = bm.title?.trim().orEmpty()
        if (title.isNotEmpty()) {
            val safePageIdx = bm.pageIdx.toInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            out.add(
                PdfTocEntry(
                    id = "pdf_toc_${out.size}_${safePageIdx}",
                    title = title,
                    depth = depth,
                    pageIndex = safePageIdx
                )
            )
        }
        if (bm.children.isNotEmpty()) {
            flattenPdfBookmarks(bm.children, totalPages, depth + 1, out)
        }
    }
    return out
}

/**
 * Custom Jetpack Compose PDF reader backed directly by `io.legere:pdfiumandroid`.
 *
 * - All native PDFium operations are serialized on a single-threaded IO dispatcher so the UI
 *   never freezes and native calls never race.
 * - Pages are rendered asynchronously on demand into a memory-bounded [LruCache].
 * - Supports vertical scroll ([LazyColumn]) and horizontal swipe ([HorizontalPager]) with RTL,
 *   pinch-to-zoom, long-press text selection with draggable handles, floating Contextual Action
 *   Menu (Highlight, Add Note, Copy), and Room-persisted highlight rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun CustomPdfReader(
    book: Book,
    bookAnnotations: List<BookAnnotation>,
    navigationMode: ReaderNavigationMode,
    readerTheme: ReaderTheme,
    isRtl: Boolean,
    onDocumentReady: (totalPages: Int) -> Unit,
    onPageChanged: (pageIndex: Int, totalPages: Int, progressPercent: Float) -> Unit,
    onSingleTap: () -> Unit,
    onHighlightSelection: (pageNumberOneBased: Int, startCharOffset: Int, endCharOffset: Int, selectedText: String) -> Unit,
    onAddNoteForSelection: (pageNumberOneBased: Int, startCharOffset: Int, endCharOffset: Int, selectedText: String) -> Unit,
    onCopySelection: (selectedText: String) -> Unit,
    onTapSavedHighlight: (annotation: BookAnnotation, anchorRect: RectF) -> Unit = { _, _ -> },
    onBindNavigator: ((jumpToPageOneBased: (Int) -> Unit) -> Unit)? = null,
    onTableOfContentsLoaded: (List<PdfTocEntry>) -> Unit = {},
    onAutoDetectRtl: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val themeBgColor = remember(readerTheme) { Color(readerTheme.backgroundArgb) }
    val themeTextColor = remember(readerTheme) { Color(readerTheme.textArgb) }
    val pageColorFilter = remember(readerTheme) { pdfThemeColorFilter(readerTheme) }

    val latestOnDocumentReady by rememberUpdatedState(onDocumentReady)
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnSingleTap by rememberUpdatedState(onSingleTap)
    val latestOnHighlightSelection by rememberUpdatedState(onHighlightSelection)
    val latestOnAddNoteForSelection by rememberUpdatedState(onAddNoteForSelection)
    val latestOnCopySelection by rememberUpdatedState(onCopySelection)
    val latestOnTapSavedHighlight by rememberUpdatedState(onTapSavedHighlight)
    val latestOnBindNavigator by rememberUpdatedState(onBindNavigator)
    val latestOnTableOfContentsLoaded by rememberUpdatedState(onTableOfContentsLoaded)
    val latestOnAutoDetectRtl by rememberUpdatedState(onAutoDetectRtl)

    // Native PDFium is not thread-safe for concurrent calls on the same document.
    val pdfDispatcher: CoroutineDispatcher = remember(book.id) {
        Dispatchers.IO.limitedParallelism(1)
    }

    // Memory-bounded bitmap cache (up to 1/8th of max heap, capped at 64 MB) keyed by 0-based page index
    val bitmapCache = remember(book.id) {
        val maxKb = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L)
            .toInt()
            .coerceIn(32 * 1024, 64 * 1024)
        object : LruCache<Int, Bitmap>(maxKb) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    // Per-page extracted text & normalized character bounding boxes cache
    val textLayoutCache = remember(book.id) {
        LruCache<Int, PdfPageTextLayout>(16)
    }

    val pageAspectRatios = remember(book.id) { mutableStateMapOf<Int, Float>() }

    var managedDoc by remember(book.id) { mutableStateOf<ManagedPdfDocument?>(null) }
    var loadError by remember(book.id) { mutableStateOf<String?>(null) }

    // Track current 0-based page index across Swipe <-> Scroll mode switches
    var activePageIndex by remember(book.id) {
        mutableIntStateOf(book.currentPageIndex.coerceAtLeast(0))
    }

    // Active text selection state shared across pages
    var activeSelection by remember(book.id) { mutableStateOf<PdfActiveSelection?>(null) }
    var isDraggingSelection by remember(book.id) { mutableStateOf(false) }

    // Pinch-to-zoom & pan state
    var zoomScale by remember(book.id) { mutableFloatStateOf(1f) }
    var panOffset by remember(book.id) { mutableStateOf(Offset.Zero) }

    // Group saved highlights by 1-based page number for O(1) lookup per page
    val highlightsByPageOneBased = remember(bookAnnotations) {
        bookAnnotations
            .filter { it.type == AnnotationType.HIGHLIGHT && it.endCharOffset >= it.charOffset }
            .groupBy { it.pageNumber }
    }

    // Open the PDF document off the main thread
    LaunchedEffect(book.id, book.bookFilePath) {
        loadError = null
        val opened = withContext(pdfDispatcher) {
            try {
                val file = File(book.bookFilePath)
                if (!file.exists()) return@withContext null
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdfiumCore = PdfiumCore(context.applicationContext)
                val doc = pdfiumCore.newDocument(pfd)
                val count = doc.getPageCount().coerceAtLeast(1)
                val toc = runCatching {
                    flattenPdfBookmarks(doc.getTableOfContents(), count)
                }.getOrDefault(emptyList())
                val firstAspect = runCatching {
                    doc.openPage(0)?.use { page ->
                        val w = page.getPageWidthPoint().coerceAtLeast(1)
                        val h = page.getPageHeightPoint().coerceAtLeast(1)
                        (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.5f)
                    }
                }.getOrNull() ?: 0.707f

                val detectedRtl = if (book.readingDirection == BookReadingDirection.RTL) {
                    true
                } else {
                    detectPdfIsMostlyRtl(doc, count, book.title)
                }

                ManagedPdfDocument(
                    fileDescriptor = pfd,
                    document = doc,
                    pageCount = count,
                    tocEntries = toc,
                    defaultAspectRatio = firstAspect,
                    detectedRtl = detectedRtl
                )
            } catch (_: Exception) {
                null
            }
        }

        if (opened != null) {
            managedDoc = opened
            activePageIndex = book.currentPageIndex.coerceIn(0, opened.pageCount - 1)
            latestOnAutoDetectRtl(opened.detectedRtl)
            latestOnTableOfContentsLoaded(opened.tocEntries)
            latestOnDocumentReady(opened.pageCount)
        } else {
            loadError = "Unable to open PDF file."
            latestOnDocumentReady(book.totalPages.coerceAtLeast(1))
        }
    }

    // Close native PDFium resources and clear caches when leaving the reader
    DisposableEffect(book.id) {
        onDispose {
            bitmapCache.evictAll()
            textLayoutCache.evictAll()
            val docToClose = managedDoc
            managedDoc = null
            if (docToClose != null) {
                kotlinx.coroutines.CoroutineScope(pdfDispatcher).launch {
                    docToClose.closeQuietly()
                }
            }
        }
    }

    // Reset zoom & selection when switching navigation mode
    LaunchedEffect(navigationMode) {
        zoomScale = 1f
        panOffset = Offset.Zero
        activeSelection = null
        isDraggingSelection = false
    }

    val errorMessage = loadError
    val loadedDoc = managedDoc

    if (errorMessage != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(themeBgColor)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { latestOnSingleTap() })
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = errorMessage,
                color = themeTextColor.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    if (loadedDoc == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(themeBgColor)
        )
        return
    }

    val totalPages = loadedDoc.pageCount

    // Calculate crisp render target width in pixels from the device screen width and density
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val targetWidthPx = remember(configuration.screenWidthDp, density.density) {
        (configuration.screenWidthDp * density.density).roundToInt().coerceAtLeast(360)
    }

    fun emitProgress(pageIdx: Int) {
        val safeIdx = pageIdx.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        if (safeIdx != activePageIndex) {
            activeSelection = null
            isDraggingSelection = false
        }
        activePageIndex = safeIdx
        val progressPercent = if (totalPages <= 1 || safeIdx >= totalPages - 1) {
            100f
        } else {
            ((safeIdx + 1).toFloat() / totalPages.toFloat() * 100f).coerceIn(0.1f, 99.9f)
        }
        latestOnPageChanged(safeIdx, totalPages, progressPercent)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBgColor)
            .pointerInput(book.id) {
                detectTapGestures(
                    onTap = {
                        if (activeSelection != null) {
                            activeSelection = null
                            isDraggingSelection = false
                        } else {
                            latestOnSingleTap()
                        }
                    },
                    onDoubleTap = {
                        activeSelection = null
                        isDraggingSelection = false
                        if (zoomScale > 1.05f) {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        } else {
                            zoomScale = 2.25f
                        }
                    }
                )
            }
            .pointerInput(book.id, navigationMode) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = zoomScale
                    var pan = panOffset
                    val touchSlop = viewConfiguration.touchSlop
                    var pastTouchSlop = false

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val pointerCount = event.changes.count { it.pressed }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (pointerCount >= 2 || zoom > 1.02f) {
                                if (!pastTouchSlop) {
                                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                    val zoomMotion = abs(1f - zoomChange) * centroidSize
                                    val panMotion = panChange.getDistance()
                                    if (zoomMotion > touchSlop || panMotion > touchSlop || pointerCount >= 2) {
                                        pastTouchSlop = true
                                    }
                                }

                                if (pastTouchSlop) {
                                    val newZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                                    val maxX = ((size.width * (newZoom - 1f)) / 2f).coerceAtLeast(0f)
                                    val maxY = ((size.height * (newZoom - 1f)) / 2f).coerceAtLeast(0f)
                                    val newPan = if (newZoom <= 1.01f) {
                                        Offset.Zero
                                    } else {
                                        Offset(
                                            x = (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                            y = (pan.y + panChange.y).coerceIn(-maxY, maxY)
                                        )
                                    }
                                    zoom = newZoom
                                    pan = newPan
                                    zoomScale = newZoom
                                    panOffset = newPan

                                    if (pointerCount >= 2 || newZoom > 1.02f) {
                                        event.changes.forEach { change ->
                                            if (change.positionChanged()) {
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val scrollEnabled = zoomScale <= 1.02f && !isDraggingSelection

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
        ) {
            if (navigationMode == ReaderNavigationMode.VERTICAL_SCROLL) {
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = activePageIndex.coerceIn(0, totalPages - 1)
                )

                LaunchedEffect(loadedDoc, listState) {
                    latestOnBindNavigator?.invoke { targetPageOneBased ->
                        val targetIdx = (targetPageOneBased - 1).coerceIn(0, totalPages - 1)
                        coroutineScope.launch {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                            activeSelection = null
                            isDraggingSelection = false
                            listState.scrollToItem(targetIdx)
                            emitProgress(targetIdx)
                        }
                    }

                    snapshotFlow { listState.firstVisibleItemIndex }
                        .distinctUntilChanged()
                        .collect { visibleIdx ->
                            emitProgress(visibleIdx)
                        }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = scrollEnabled,
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        count = totalPages,
                        key = { pageIdx -> "${book.id}_scroll_page_$pageIdx" }
                    ) { pageIdx ->
                        PdfPageBitmapItem(
                            document = loadedDoc.document,
                            pageIndex = pageIdx,
                            targetWidthPx = targetWidthPx,
                            aspectRatio = pageAspectRatios[pageIdx] ?: loadedDoc.defaultAspectRatio,
                            onAspectRatioResolved = { ratio -> pageAspectRatios[pageIdx] = ratio },
                            bitmapCache = bitmapCache,
                            textLayoutCache = textLayoutCache,
                            pdfDispatcher = pdfDispatcher,
                            readerTheme = readerTheme,
                            themeBgColor = themeBgColor,
                            themeTextColor = themeTextColor,
                            colorFilter = pageColorFilter,
                            savedHighlights = highlightsByPageOneBased[pageIdx + 1].orEmpty(),
                            activeSelection = activeSelection?.takeIf { it.pageIndex == pageIdx },
                            isDraggingSelection = isDraggingSelection,
                            onUpdateSelection = { sel, dragging ->
                                activeSelection = sel
                                isDraggingSelection = dragging
                            },
                            onDismissSelection = {
                                activeSelection = null
                                isDraggingSelection = false
                            },
                            onSingleTapEmpty = {
                                if (activeSelection != null) {
                                    activeSelection = null
                                    isDraggingSelection = false
                                } else {
                                    latestOnSingleTap()
                                }
                            },
                            onHighlightSelection = { startIdx, endIdx, text ->
                                latestOnHighlightSelection(pageIdx + 1, startIdx, endIdx, text)
                                activeSelection = null
                                isDraggingSelection = false
                            },
                            onAddNoteForSelection = { startIdx, endIdx, text ->
                                latestOnAddNoteForSelection(pageIdx + 1, startIdx, endIdx, text)
                                activeSelection = null
                                isDraggingSelection = false
                            },
                            onCopySelection = { text ->
                                latestOnCopySelection(text)
                                activeSelection = null
                                isDraggingSelection = false
                            },
                            onTapSavedHighlight = latestOnTapSavedHighlight,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                val pagerState = rememberPagerState(
                    initialPage = activePageIndex.coerceIn(0, totalPages - 1),
                    pageCount = { totalPages }
                )

                LaunchedEffect(loadedDoc, pagerState) {
                    latestOnBindNavigator?.invoke { targetPageOneBased ->
                        val targetIdx = (targetPageOneBased - 1).coerceIn(0, totalPages - 1)
                        coroutineScope.launch {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                            activeSelection = null
                            isDraggingSelection = false
                            pagerState.scrollToPage(targetIdx)
                            emitProgress(targetIdx)
                        }
                    }

                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { currentPage ->
                            zoomScale = 1f
                            panOffset = Offset.Zero
                            emitProgress(currentPage)
                        }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(0.dp),
                    pageSpacing = 12.dp,
                    reverseLayout = isRtl,
                    verticalAlignment = Alignment.CenterVertically,
                    userScrollEnabled = scrollEnabled,
                    beyondViewportPageCount = 1,
                    key = { pageIdx -> "${book.id}_pager_page_$pageIdx" }
                ) { pageIdx ->
                    PdfPageBitmapItem(
                        document = loadedDoc.document,
                        pageIndex = pageIdx,
                        targetWidthPx = targetWidthPx,
                        aspectRatio = pageAspectRatios[pageIdx] ?: loadedDoc.defaultAspectRatio,
                        onAspectRatioResolved = { ratio -> pageAspectRatios[pageIdx] = ratio },
                        bitmapCache = bitmapCache,
                        textLayoutCache = textLayoutCache,
                        pdfDispatcher = pdfDispatcher,
                        readerTheme = readerTheme,
                        themeBgColor = themeBgColor,
                        themeTextColor = themeTextColor,
                        colorFilter = pageColorFilter,
                        savedHighlights = highlightsByPageOneBased[pageIdx + 1].orEmpty(),
                        activeSelection = activeSelection?.takeIf { it.pageIndex == pageIdx },
                        isDraggingSelection = isDraggingSelection,
                        onUpdateSelection = { sel, dragging ->
                            activeSelection = sel
                            isDraggingSelection = dragging
                        },
                        onDismissSelection = {
                            activeSelection = null
                            isDraggingSelection = false
                        },
                        onSingleTapEmpty = {
                            if (activeSelection != null) {
                                activeSelection = null
                                isDraggingSelection = false
                            } else {
                                latestOnSingleTap()
                            }
                        },
                        onHighlightSelection = { startIdx, endIdx, text ->
                            latestOnHighlightSelection(pageIdx + 1, startIdx, endIdx, text)
                            activeSelection = null
                            isDraggingSelection = false
                        },
                        onAddNoteForSelection = { startIdx, endIdx, text ->
                            latestOnAddNoteForSelection(pageIdx + 1, startIdx, endIdx, text)
                            activeSelection = null
                            isDraggingSelection = false
                        },
                        onCopySelection = { text ->
                            latestOnCopySelection(text)
                            activeSelection = null
                            isDraggingSelection = false
                        },
                        onTapSavedHighlight = latestOnTapSavedHighlight,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPageBitmapItem(
    document: PdfDocument,
    pageIndex: Int,
    targetWidthPx: Int,
    aspectRatio: Float,
    onAspectRatioResolved: (Float) -> Unit,
    bitmapCache: LruCache<Int, Bitmap>,
    textLayoutCache: LruCache<Int, PdfPageTextLayout>,
    pdfDispatcher: CoroutineDispatcher,
    readerTheme: ReaderTheme,
    themeBgColor: Color,
    themeTextColor: Color,
    colorFilter: ColorFilter?,
    savedHighlights: List<BookAnnotation>,
    activeSelection: PdfActiveSelection?,
    isDraggingSelection: Boolean,
    onUpdateSelection: (PdfActiveSelection?, Boolean) -> Unit,
    onDismissSelection: () -> Unit,
    onSingleTapEmpty: () -> Unit,
    onHighlightSelection: (startCharOffset: Int, endCharOffset: Int, selectedText: String) -> Unit,
    onAddNoteForSelection: (startCharOffset: Int, endCharOffset: Int, selectedText: String) -> Unit,
    onCopySelection: (selectedText: String) -> Unit,
    onTapSavedHighlight: (annotation: BookAnnotation, anchorRect: RectF) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var renderedBitmap by remember(pageIndex, targetWidthPx) {
        val cached = bitmapCache.get(pageIndex)
        mutableStateOf(
            if (cached != null && !cached.isRecycled && cached.width == targetWidthPx) cached else null
        )
    }

    var textLayout by remember(pageIndex, targetWidthPx) {
        mutableStateOf(textLayoutCache.get(pageIndex))
    }

    var pageBoxSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(document, pageIndex, targetWidthPx) {
        val cachedBmp = bitmapCache.get(pageIndex)
        val cachedText = textLayoutCache.get(pageIndex)
        if (cachedBmp != null && !cachedBmp.isRecycled && cachedBmp.width == targetWidthPx && cachedText != null) {
            renderedBitmap = cachedBmp
            textLayout = cachedText
            return@LaunchedEffect
        }

        val result: Triple<Bitmap, Float, PdfPageTextLayout>? = withContext(pdfDispatcher) {
            runCatching {
                document.openPage(pageIndex)?.use { page ->
                    val rawW = page.getPageWidthPoint().coerceAtLeast(1)
                    val rawH = page.getPageHeightPoint().coerceAtLeast(1)
                    val crop = detectContentCropFractions(page, rawW, rawH)

                    val croppedRatio = ((rawW * crop.widthFrac) / (rawH * crop.heightFrac))
                        .coerceIn(0.35f, 2.5f)
                    val renderWidth = targetWidthPx.coerceAtLeast(360)
                    val renderHeight = (renderWidth / croppedRatio).roundToInt().coerceAtLeast(1)

                    val fullDrawW = (renderWidth / crop.widthFrac).roundToInt().coerceAtLeast(renderWidth)
                    val fullDrawH = (renderHeight / crop.heightFrac).roundToInt().coerceAtLeast(renderHeight)
                    val startX = -(crop.left * fullDrawW).roundToInt()
                    val startY = -(crop.top * fullDrawH).roundToInt()

                    val bitmap = if (cachedBmp != null && !cachedBmp.isRecycled && cachedBmp.width == renderWidth) {
                        cachedBmp
                    } else {
                        Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(AndroidColor.WHITE)
                            page.renderPageBitmap(
                                bitmap = bmp,
                                startX = startX,
                                startY = startY,
                                drawSizeX = fullDrawW,
                                drawSizeY = fullDrawH,
                                renderAnnot = true
                            )
                        }
                    }

                    val extractedText = cachedText ?: extractPageTextLayout(
                        page = page,
                        startX = startX,
                        startY = startY,
                        fullDrawW = fullDrawW,
                        fullDrawH = fullDrawH,
                        renderWidth = renderWidth,
                        renderHeight = renderHeight
                    )

                    Triple(bitmap, croppedRatio, extractedText)
                }
            }.getOrNull()
        }

        if (result != null) {
            val (bmp, ratio, extractedText) = result
            bitmapCache.put(pageIndex, bmp)
            textLayoutCache.put(pageIndex, extractedText)
            onAspectRatioResolved(ratio)
            renderedBitmap = bmp
            textLayout = extractedText
        }
    }

    val bmp = renderedBitmap
    val layout = textLayout
    val resolvedRatio = if (bmp != null && !bmp.isRecycled && bmp.height > 0) {
        bmp.width.toFloat() / bmp.height.toFloat()
    } else {
        aspectRatio.coerceAtLeast(0.1f)
    }

    val latestLayout by rememberUpdatedState(layout)
    val latestActiveSelection by rememberUpdatedState(activeSelection)
    val latestSavedHighlights by rememberUpdatedState(savedHighlights)
    val latestPageBoxSize by rememberUpdatedState(pageBoxSize)
    val latestOnUpdateSelection by rememberUpdatedState(onUpdateSelection)
    val latestOnSingleTapEmpty by rememberUpdatedState(onSingleTapEmpty)
    val latestOnTapSavedHighlight by rememberUpdatedState(onTapSavedHighlight)

    // Precompute merged line rects for active selection
    val activeSelectionRects = remember(layout, activeSelection) {
        if (layout != null && activeSelection != null) {
            mergeRangeToLineRects(layout.charRects, activeSelection.startIndex, activeSelection.endIndex)
        } else {
            emptyList()
        }
    }

    // Precompute merged line rects for saved Room highlights on this page
    val savedHighlightRects = remember(layout, savedHighlights) {
        if (layout == null || savedHighlights.isEmpty()) {
            emptyList()
        } else {
            savedHighlights.mapNotNull { ann ->
                val rects = mergeRangeToLineRects(layout.charRects, ann.charOffset, ann.endCharOffset)
                if (rects.isNotEmpty()) ann to rects else null
            }
        }
    }

    val latestSavedHighlightRects by rememberUpdatedState(savedHighlightRects)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(resolvedRatio)
            .background(themeBgColor)
            .onSizeChanged { pageBoxSize = it },
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.FillBounds,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxSize()
            )

            // Transparent selection & highlight Canvas overlay
            val handleRadiusPx = with(density) { 8.dp.toPx() }
            val handleHitRadiusPx = with(density) { 26.dp.toPx() }
            val selectionFillColor = if (readerTheme.isDarkSurface) {
                Color(0x5560A5FA)
            } else {
                Color(0x553B82F6)
            }
            val handleColor = Color(0xFF3B82F6)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Tap: dismiss active selection, or open Highlight Color Picker on saved highlight, or toggle HUD
                    .pointerInput(pageIndex, layout != null) {
                        detectTapGestures(
                            onTap = { tapOffset ->
                                val boxW = latestPageBoxSize.width.toFloat()
                                val boxH = latestPageBoxSize.height.toFloat()
                                if (latestActiveSelection != null) {
                                    latestOnSingleTapEmpty()
                                    return@detectTapGestures
                                }
                                if (boxW > 0f && boxH > 0f) {
                                    val nx = tapOffset.x / boxW
                                    val ny = tapOffset.y / boxH
                                    val hit = latestSavedHighlightRects.firstOrNull { (_, rects) ->
                                        rects.any { r ->
                                            nx >= r.left - 0.01f && nx <= r.right + 0.01f &&
                                                ny >= r.top - 0.01f && ny <= r.bottom + 0.01f
                                        }
                                    }
                                    if (hit != null) {
                                        val union = RectF(hit.second.first())
                                        hit.second.forEach { union.union(it) }
                                        val dpRect = RectF(
                                            with(density) { (union.left * boxW).toDp().value },
                                            with(density) { (union.top * boxH).toDp().value },
                                            with(density) { (union.right * boxW).toDp().value },
                                            with(density) { (union.bottom * boxH).toDp().value }
                                        )
                                        latestOnTapSavedHighlight(hit.first, dpRect)
                                        return@detectTapGestures
                                    }
                                }
                                latestOnSingleTapEmpty()
                            }
                        )
                    }
                    // Long-press + drag to start and extend text selection without breaking swipe/scroll
                    .pointerInput(pageIndex, layout != null) {
                        var anchorStart = 0
                        var anchorEnd = 0
                        var currentDragPos = Offset.Zero

                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                val currentLayout = latestLayout ?: return@detectDragGesturesAfterLongPress
                                val boxW = latestPageBoxSize.width.toFloat()
                                val boxH = latestPageBoxSize.height.toFloat()
                                if (boxW <= 0f || boxH <= 0f) return@detectDragGesturesAfterLongPress

                                val nx = (startOffset.x / boxW).coerceIn(0f, 1f)
                                val ny = (startOffset.y / boxH).coerceIn(0f, 1f)
                                val hitIdx = findNearestCharIndex(currentLayout, nx, ny) ?: return@detectDragGesturesAfterLongPress

                                val wordRange = expandToWordBounds(currentLayout.fullText, hitIdx)
                                anchorStart = wordRange.first
                                anchorEnd = wordRange.last
                                currentDragPos = startOffset
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnUpdateSelection(
                                    PdfActiveSelection(
                                        pageIndex = pageIndex,
                                        startIndex = anchorStart,
                                        endIndex = anchorEnd
                                    ),
                                    true
                                )
                            },
                            onDrag = { change, dragAmount ->
                                val currentLayout = latestLayout ?: return@detectDragGesturesAfterLongPress
                                val boxW = latestPageBoxSize.width.toFloat()
                                val boxH = latestPageBoxSize.height.toFloat()
                                if (boxW <= 0f || boxH <= 0f) return@detectDragGesturesAfterLongPress

                                change.consume()
                                currentDragPos += dragAmount
                                val nx = (currentDragPos.x / boxW).coerceIn(0f, 1f)
                                val ny = (currentDragPos.y / boxH).coerceIn(0f, 1f)
                                val dragIdx = findNearestCharIndex(currentLayout, nx, ny, maxDistance = 0.14f)
                                    ?: return@detectDragGesturesAfterLongPress

                                val newStart = min(anchorStart, dragIdx)
                                val newEnd = max(anchorEnd, dragIdx)
                                latestOnUpdateSelection(
                                    PdfActiveSelection(
                                        pageIndex = pageIndex,
                                        startIndex = newStart,
                                        endIndex = newEnd
                                    ),
                                    true
                                )
                            },
                            onDragEnd = {
                                val sel = latestActiveSelection
                                if (sel != null) {
                                    latestOnUpdateSelection(sel, false)
                                }
                            },
                            onDragCancel = {
                                val sel = latestActiveSelection
                                if (sel != null) {
                                    latestOnUpdateSelection(sel, false)
                                }
                            }
                        )
                    }
                    // Draggable start / end selection handles when a selection is active
                    .then(
                        if (activeSelection != null && activeSelectionRects.isNotEmpty()) {
                            Modifier.pointerInput(pageIndex, activeSelection.pageIndex) {
                                var draggingHandle = ActiveHandleDrag.NONE
                                var dragPos = Offset.Zero

                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val boxW = latestPageBoxSize.width.toFloat()
                                        val boxH = latestPageBoxSize.height.toFloat()
                                        val sel = latestActiveSelection ?: return@detectDragGestures
                                        val curLayout = latestLayout ?: return@detectDragGestures
                                        val rects = mergeRangeToLineRects(curLayout.charRects, sel.startIndex, sel.endIndex)
                                        if (rects.isEmpty() || boxW <= 0f || boxH <= 0f) return@detectDragGestures

                                        val startHandle = Offset(rects.first().left * boxW, rects.first().bottom * boxH + handleRadiusPx)
                                        val endHandle = Offset(rects.last().right * boxW, rects.last().bottom * boxH + handleRadiusPx)

                                        val dStart = (offset - startHandle).getDistance()
                                        val dEnd = (offset - endHandle).getDistance()

                                        draggingHandle = when {
                                            dStart <= handleHitRadiusPx && dStart <= dEnd -> ActiveHandleDrag.START
                                            dEnd <= handleHitRadiusPx -> ActiveHandleDrag.END
                                            else -> ActiveHandleDrag.NONE
                                        }
                                        if (draggingHandle != ActiveHandleDrag.NONE) {
                                            dragPos = offset
                                            latestOnUpdateSelection(sel, true)
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        if (draggingHandle == ActiveHandleDrag.NONE) return@detectDragGestures
                                        val curLayout = latestLayout ?: return@detectDragGestures
                                        val sel = latestActiveSelection ?: return@detectDragGestures
                                        val boxW = latestPageBoxSize.width.toFloat()
                                        val boxH = latestPageBoxSize.height.toFloat()
                                        if (boxW <= 0f || boxH <= 0f) return@detectDragGestures

                                        change.consume()
                                        dragPos += dragAmount
                                        val nx = (dragPos.x / boxW).coerceIn(0f, 1f)
                                        val ny = ((dragPos.y - handleRadiusPx) / boxH).coerceIn(0f, 1f)
                                        val idx = findNearestCharIndex(curLayout, nx, ny, maxDistance = 0.14f)
                                            ?: return@detectDragGestures

                                        val updated = when (draggingHandle) {
                                            ActiveHandleDrag.START -> PdfActiveSelection(
                                                pageIndex = pageIndex,
                                                startIndex = min(idx, sel.endIndex),
                                                endIndex = max(idx, sel.endIndex)
                                            )
                                            ActiveHandleDrag.END -> PdfActiveSelection(
                                                pageIndex = pageIndex,
                                                startIndex = min(sel.startIndex, idx),
                                                endIndex = max(sel.startIndex, idx)
                                            )
                                            ActiveHandleDrag.NONE -> sel
                                        }
                                        latestOnUpdateSelection(updated, true)
                                    },
                                    onDragEnd = {
                                        if (draggingHandle != ActiveHandleDrag.NONE) {
                                            draggingHandle = ActiveHandleDrag.NONE
                                            latestActiveSelection?.let { latestOnUpdateSelection(it, false) }
                                        }
                                    },
                                    onDragCancel = {
                                        if (draggingHandle != ActiveHandleDrag.NONE) {
                                            draggingHandle = ActiveHandleDrag.NONE
                                            latestActiveSelection?.let { latestOnUpdateSelection(it, false) }
                                        }
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                val w = size.width
                val h = size.height
                val corner = CornerRadius(4f, 4f)

                // 1. Draw saved Room highlights for this page
                for ((annotation, rects) in savedHighlightRects) {
                    val markerColor = Color(
                        if (readerTheme.isDarkSurface) annotation.highlightColor.markerBgDarkArgb
                        else annotation.highlightColor.markerBgLightArgb
                    )
                    for (r in rects) {
                        val leftPx = r.left * w
                        val topPx = r.top * h
                        val rectW = ((r.right - r.left) * w).coerceAtLeast(2f)
                        val rectH = ((r.bottom - r.top) * h).coerceAtLeast(4f)
                        drawRoundRect(
                            color = markerColor,
                            topLeft = Offset(leftPx, topPx),
                            size = Size(rectW, rectH),
                            cornerRadius = corner
                        )
                    }
                }

                // 2. Draw active text selection + start/end draggable handles
                if (activeSelectionRects.isNotEmpty()) {
                    for (r in activeSelectionRects) {
                        val leftPx = r.left * w
                        val topPx = r.top * h
                        val rectW = ((r.right - r.left) * w).coerceAtLeast(2f)
                        val rectH = ((r.bottom - r.top) * h).coerceAtLeast(4f)
                        drawRoundRect(
                            color = selectionFillColor,
                            topLeft = Offset(leftPx, topPx),
                            size = Size(rectW, rectH),
                            cornerRadius = corner
                        )
                    }

                    val firstRect = activeSelectionRects.first()
                    val lastRect = activeSelectionRects.last()

                    // Start handle
                    val startX = firstRect.left * w
                    drawLine(
                        color = handleColor,
                        start = Offset(startX, firstRect.top * h),
                        end = Offset(startX, firstRect.bottom * h + handleRadiusPx),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        color = handleColor,
                        radius = handleRadiusPx,
                        center = Offset(startX, firstRect.bottom * h + handleRadiusPx)
                    )

                    // End handle
                    val endX = lastRect.right * w
                    drawLine(
                        color = handleColor,
                        start = Offset(endX, lastRect.top * h),
                        end = Offset(endX, lastRect.bottom * h + handleRadiusPx),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        color = handleColor,
                        radius = handleRadiusPx,
                        center = Offset(endX, lastRect.bottom * h + handleRadiusPx)
                    )
                }
            }

            // 3. Floating Contextual Action Menu (CRM) Popup above the selected text
            if (activeSelection != null && !isDraggingSelection && activeSelectionRects.isNotEmpty() && layout != null && pageBoxSize.width > 0 && pageBoxSize.height > 0) {
                val selectedText = remember(layout, activeSelection) {
                    extractSelectionSnippet(layout.fullText, activeSelection.startIndex, activeSelection.endIndex)
                }

                if (selectedText.isNotBlank()) {
                    val unionRect = remember(activeSelectionRects) {
                        RectF(activeSelectionRects.first()).apply {
                            activeSelectionRects.forEach { union(it) }
                        }
                    }

                    val pillHeightPx = with(density) { 46.dp.roundToPx() }
                    val pillApproxWidthPx = with(density) { 246.dp.roundToPx() }
                    val marginPx = with(density) { 12.dp.roundToPx() }
                    val gapPx = with(density) { 10.dp.roundToPx() }

                    val selCenterX = ((unionRect.left + unionRect.right) * 0.5f * pageBoxSize.width).roundToInt()
                    val selTopY = (unionRect.top * pageBoxSize.height).roundToInt()
                    val selBottomY = (unionRect.bottom * pageBoxSize.height).roundToInt()

                    val popupX = (selCenterX - pillApproxWidthPx / 2)
                        .coerceIn(marginPx, (pageBoxSize.width - pillApproxWidthPx - marginPx).coerceAtLeast(marginPx))

                    val preferAboveY = selTopY - pillHeightPx - gapPx
                    val popupY = if (preferAboveY >= marginPx) {
                        preferAboveY
                    } else {
                        (selBottomY + gapPx + with(density) { 16.dp.roundToPx() })
                            .coerceAtMost((pageBoxSize.height - pillHeightPx - marginPx).coerceAtLeast(marginPx))
                    }

                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(popupX, popupY),
                        onDismissRequest = onDismissSelection,
                        properties = PopupProperties(focusable = false)
                    ) {
                        PdfFloatingActionMenuPill(
                            readerTheme = readerTheme,
                            onHighlight = {
                                onHighlightSelection(
                                    activeSelection.startIndex,
                                    activeSelection.endIndex,
                                    selectedText
                                )
                            },
                            onAddNote = {
                                onAddNoteForSelection(
                                    activeSelection.startIndex,
                                    activeSelection.endIndex,
                                    selectedText
                                )
                            },
                            onCopy = {
                                onCopySelection(selectedText)
                            }
                        )
                    }
                }
            }
        } else {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = themeTextColor.copy(alpha = 0.45f)
            )
        }
    }
}

/**
 * Floating Contextual Action Menu (CRM) pill for PDF text selection.
 * Mirrors the EPUB ReadiumActionModeSession pill (Highlight, Add Note, Copy).
 */
@Composable
private fun PdfFloatingActionMenuPill(
    readerTheme: ReaderTheme,
    onHighlight: () -> Unit,
    onAddNote: () -> Unit,
    onCopy: () -> Unit
) {
    val pillBg = when (readerTheme) {
        ReaderTheme.DARK, ReaderTheme.AMOLED -> Color(0xFF27272A)
        ReaderTheme.SEPIA -> Color(0xFFFDFBF7)
        ReaderTheme.LIGHT -> Color(0xFFFFFFFF)
    }
    val pillBorder = when (readerTheme) {
        ReaderTheme.DARK, ReaderTheme.AMOLED -> Color(0xFF3F3F46)
        ReaderTheme.SEPIA -> Color(0xFFD6C7B2)
        ReaderTheme.LIGHT -> Color(0xFFD4D4D8)
    }
    val pillText = when (readerTheme) {
        ReaderTheme.DARK, ReaderTheme.AMOLED -> Color(0xFFFAFAFA)
        ReaderTheme.SEPIA -> Color(0xFF3E2C23)
        ReaderTheme.LIGHT -> Color(0xFF18181B)
    }

    Surface(
        modifier = Modifier
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(26.dp)
            )
            .border(1.dp, pillBorder, RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        color = pillBg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onHighlight)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(HighlightColor.YELLOW.solidArgb))
                )
                Text(
                    text = "Highlight",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = pillText
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(pillBorder)
            )

            Text(
                text = "Add Note",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = pillText,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onAddNote)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(pillBorder)
            )

            Text(
                text = "Copy",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = pillText,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
