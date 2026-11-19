package com.example.freebook.ui.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.Book
import com.example.freebook.model.BookReadingDirection
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
 * - Supports both vertical continuous scrolling ([LazyColumn]) and horizontal paged swiping
 *   ([HorizontalPager]), pinch-to-zoom, pan when zoomed, double-tap zoom, and HUD tap toggling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun CustomPdfReader(
    book: Book,
    navigationMode: ReaderNavigationMode,
    readerTheme: ReaderTheme,
    isRtl: Boolean,
    onDocumentReady: (totalPages: Int) -> Unit,
    onPageChanged: (pageIndex: Int, totalPages: Int, progressPercent: Float) -> Unit,
    onSingleTap: () -> Unit,
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

    val pageAspectRatios = remember(book.id) { mutableStateMapOf<Int, Float>() }

    var managedDoc by remember(book.id) { mutableStateOf<ManagedPdfDocument?>(null) }
    var loadError by remember(book.id) { mutableStateOf<String?>(null) }

    // Track current 0-based page index across Swipe <-> Scroll mode switches
    var activePageIndex by remember(book.id) {
        mutableIntStateOf(book.currentPageIndex.coerceAtLeast(0))
    }

    // Pinch-to-zoom & pan state
    var zoomScale by remember(book.id) { mutableFloatStateOf(1f) }
    var panOffset by remember(book.id) { mutableStateOf(Offset.Zero) }

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

    // Close native PDFium resources and clear cache when leaving the reader
    DisposableEffect(book.id) {
        onDispose {
            bitmapCache.evictAll()
            val docToClose = managedDoc
            managedDoc = null
            if (docToClose != null) {
                // Close on background thread to avoid blocking composition disposal
                kotlinx.coroutines.CoroutineScope(pdfDispatcher).launch {
                    docToClose.closeQuietly()
                }
            }
        }
    }

    // Reset zoom when switching navigation mode
    LaunchedEffect(navigationMode) {
        zoomScale = 1f
        panOffset = Offset.Zero
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
        // Parent BookReaderScreen displays the full-screen loading indicator until onDocumentReady fires
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
                    onTap = { latestOnSingleTap() },
                    onDoubleTap = {
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

                                    // Consume only when pinching or panning while zoomed in,
                                    // so normal 1x vertical scroll / horizontal swipe works untouched.
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
                    userScrollEnabled = zoomScale <= 1.02f,
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
                            pdfDispatcher = pdfDispatcher,
                            themeBgColor = themeBgColor,
                            themeTextColor = themeTextColor,
                            colorFilter = pageColorFilter,
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
                    userScrollEnabled = zoomScale <= 1.02f,
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
                        pdfDispatcher = pdfDispatcher,
                        themeBgColor = themeBgColor,
                        themeTextColor = themeTextColor,
                        colorFilter = pageColorFilter,
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
    pdfDispatcher: CoroutineDispatcher,
    themeBgColor: Color,
    themeTextColor: Color,
    colorFilter: ColorFilter?,
    modifier: Modifier = Modifier
) {
    var renderedBitmap by remember(pageIndex, targetWidthPx) {
        val cached = bitmapCache.get(pageIndex)
        mutableStateOf(
            if (cached != null && !cached.isRecycled && cached.width == targetWidthPx) cached else null
        )
    }

    LaunchedEffect(document, pageIndex, targetWidthPx) {
        val cached = bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled && cached.width == targetWidthPx) {
            renderedBitmap = cached
            return@LaunchedEffect
        }

        val result: Pair<Bitmap, Float>? = withContext(pdfDispatcher) {
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

                    val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.renderPageBitmap(
                        bitmap = bitmap,
                        startX = startX,
                        startY = startY,
                        drawSizeX = fullDrawW,
                        drawSizeY = fullDrawH,
                        renderAnnot = true
                    )
                    bitmap to croppedRatio
                }
            }.getOrNull()
        }

        if (result != null) {
            val (bmp, ratio) = result
            bitmapCache.put(pageIndex, bmp)
            onAspectRatioResolved(ratio)
            renderedBitmap = bmp
        }
    }

    val bmp = renderedBitmap
    val resolvedRatio = if (bmp != null && !bmp.isRecycled && bmp.height > 0) {
        bmp.width.toFloat() / bmp.height.toFloat()
    } else {
        aspectRatio.coerceAtLeast(0.1f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(resolvedRatio)
            .background(themeBgColor),
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
        } else {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = themeTextColor.copy(alpha = 0.45f)
            )
        }
    }
}
