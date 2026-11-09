package com.example.freebook.ui.screens

import android.content.ClipboardManager as AndroidClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.ReaderTextSize
import com.example.freebook.ui.components.BookmarkRibbonIcon
import com.example.freebook.ui.components.CloseCrossIcon
import com.example.freebook.ui.components.NotePencilIcon
import com.example.freebook.ui.components.SearchVectorIcon
import com.example.freebook.ui.pagination.PageLayout
import com.example.freebook.ui.pagination.ViewportPaginator
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Custom [TextToolbar] that replaces the default system Cut/Copy/Paste bar with an inline
 * Floating Contextual Action Bar supporting Highlight, Add Note, and Copy when the user
 * covers words on the page.
 */
private class ReaderContextualTextToolbar : TextToolbar {
    override var status: TextToolbarStatus by mutableStateOf(TextToolbarStatus.Hidden)
        private set

    var selectionRect: Rect by mutableStateOf(Rect.Zero)
        private set

    var onCopyRequested: (() -> Unit)? by mutableStateOf(null)
        private set

    var onSelectAllRequested: (() -> Unit)? by mutableStateOf(null)
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.selectionRect = rect
        this.onCopyRequested = onCopyRequested
        this.onSelectAllRequested = onSelectAllRequested
        this.status = TextToolbarStatus.Shown
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
    }
}

/**
 * Wraps the platform [Clipboard] and [ClipboardManager] so we can capture the exact covered/selected
 * text when `onCopyRequested` is triggered by our Floating Contextual Action Bar.
 */
@Suppress("DEPRECATION")
private class SelectionCaptureController(
    private val delegateClipboard: Clipboard,
    private val delegateClipboardManager: ClipboardManager
) : Clipboard, ClipboardManager {
    private var pendingCaptureCallback: ((String) -> Unit)? = null
    private var suppressSystemClipboardWrite: Boolean = false

    fun requestSelectedText(
        suppressSystemClip: Boolean,
        triggerCopy: (() -> Unit)?,
        onCaptured: (String) -> Unit
    ) {
        if (triggerCopy == null) {
            onCaptured("")
            return
        }
        pendingCaptureCallback = onCaptured
        suppressSystemClipboardWrite = suppressSystemClip
        triggerCopy.invoke()
    }

    private fun deliverCaptured(rawText: String) {
        val cb = pendingCaptureCallback
        pendingCaptureCallback = null
        val shouldSuppress = suppressSystemClipboardWrite
        suppressSystemClipboardWrite = false
        cb?.invoke(rawText.trim())
    }

    override suspend fun getClipEntry(): ClipEntry? = delegateClipboard.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val extracted = clipEntry?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(null)
            ?.toString()
            .orEmpty()
        val shouldSuppress = suppressSystemClipboardWrite
        deliverCaptured(extracted)
        if (!shouldSuppress) {
            delegateClipboard.setClipEntry(clipEntry)
        }
    }

    override val nativeClipboard: NativeClipboard
        get() = delegateClipboard.nativeClipboard

    override fun setText(annotatedString: AnnotatedString) {
        val shouldSuppress = suppressSystemClipboardWrite
        deliverCaptured(annotatedString.text)
        if (!shouldSuppress) {
            delegateClipboardManager.setText(annotatedString)
        }
    }

    override fun getText(): AnnotatedString? = delegateClipboardManager.getText()

    override fun hasText(): Boolean = delegateClipboardManager.hasText()
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookReaderScreen(
    book: Book,
    bookAnnotations: List<BookAnnotation>,
    isDarkTheme: Boolean,
    fontSizeSp: Float,
    enablePageAnimations: Boolean,
    loadBookText: suspend (Book) -> String,
    onToggleTheme: (Boolean) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onPageChanged: (charOffset: Int, totalChars: Int, pageIndex: Int, pageCount: Int) -> Unit,
    onAddAnnotation: (type: AnnotationType, pageNumber: Int, text: String) -> Unit,
    onDeleteAnnotation: (BookAnnotation) -> Unit,
    onCloseReader: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val baseDensity = LocalDensity.current
    // Reader typography is independent of the system font scale: sizes are predefined presets
    // (Normal / Large / Extra Large), so text can never scale out of the viewport.
    val readerDensity = remember(baseDensity.density) { Density(baseDensity.density, fontScale = 1f) }

    val delegateClipboard = LocalClipboard.current
    val delegateClipboardManager = LocalClipboardManager.current
    val selectionCapture = remember(delegateClipboard, delegateClipboardManager) {
        SelectionCaptureController(delegateClipboard, delegateClipboardManager)
    }
    val contextualTextToolbar = remember { ReaderContextualTextToolbar() }

    var rootPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    var isActiveReaderHudVisible by rememberSaveable { mutableStateOf(false) }
    var isBottomBarExpanded by rememberSaveable { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isActiveReaderHudVisible) {
        if (!isActiveReaderHudVisible) {
            isBottomBarExpanded = false
        }
    }

    val cleanedBookText by produceState(initialValue = "", key1 = book.id, key2 = book.textContentFilePath) {
        value = withContext(Dispatchers.Default) {
            ViewportPaginator.cleanText(loadBookText(book))
        }
    }

    val textSizePreset = remember(fontSizeSp) { ReaderTextSize.fromSp(fontSizeSp) }

    val readerTextStyle = remember(textSizePreset, palette.readerText) {
        TextStyle(
            fontFamily = FontFamily.Serif,
            fontSize = textSizePreset.sp.sp,
            lineHeight = (textSizePreset.sp * 1.5f).sp,
            color = palette.readerText,
            textAlign = TextAlign.Justify,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Proportional,
                trim = LineHeightStyle.Trim.None
            )
        )
    }

    // Reading position is a layout-independent character offset (single source of truth).
    // It survives text-size / screen-size changes, which change the number of pages.
    var currentCharOffset by rememberSaveable(book.id) { mutableIntStateOf(book.readingCharOffset) }

    CompositionLocalProvider(LocalDensity provides readerDensity) {
        val textMeasurer = rememberTextMeasurer(cacheSize = 64)

        // Deterministic pagination for the (text, style, viewport) triple.
        val pageLayout by produceState<PageLayout?>(
            initialValue = null,
            key1 = cleanedBookText,
            key2 = viewportSize,
            key3 = readerTextStyle
        ) {
            if (cleanedBookText.isEmpty() || viewportSize.width <= 0 || viewportSize.height <= 0) {
                value = null
            } else {
                delay(80)
                value = withContext(Dispatchers.Default) {
                    ViewportPaginator.paginate(
                        cleanedText = cleanedBookText,
                        textMeasurer = textMeasurer,
                        textStyle = readerTextStyle,
                        density = readerDensity,
                        availableWidthPx = viewportSize.width,
                        availableHeightPx = viewportSize.height
                    )
                }
            }
        }

        val layoutSnapshot = pageLayout
        val isBookContentLoaded = layoutSnapshot != null
        val pageCount = layoutSnapshot?.pageCount ?: 1

        val pagerState = rememberPagerState(
            initialPage = 0,
            pageCount = { pageCount }
        )

        // 1) Restore position for each new layout, 2) then persist EVERY page change (any direction).
        LaunchedEffect(layoutSnapshot) {
            val layout = layoutSnapshot ?: return@LaunchedEffect
            pagerState.scrollToPage(layout.pageIndexForOffset(currentCharOffset))
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { pageIdx ->
                    contextualTextToolbar.hide()
                    focusManager.clearFocus()
                    val offset = layout.startOffsetOf(pageIdx)
                    currentCharOffset = offset
                    onPageChanged(offset, layout.text.length, pageIdx, layout.pageCount)
                }
        }

        val currentDisplayPage = (pagerState.currentPage + 1).coerceIn(1, pageCount)
        val readingPercent = remember(pagerState.currentPage, pageCount) {
            ((pagerState.currentPage + 1).toFloat() / pageCount.toFloat() * 100f).coerceIn(0.1f, 100f)
        }

        var showAddNoteOrHighlightDialog by rememberSaveable { mutableStateOf(false) }
        var initialDialogType by rememberSaveable { mutableStateOf(AnnotationType.NOTE) }
        var initialDialogText by rememberSaveable { mutableStateOf("") }
        var coveredPassageForNote by rememberSaveable { mutableStateOf("") }
        var showSearchInBookDialog by rememberSaveable { mutableStateOf(false) }
        var showBookmarksAndHighlightsDialog by rememberSaveable { mutableStateOf(false) }
        var annotationPendingDelete by remember { mutableStateOf<BookAnnotation?>(null) }

        val currentPageAnnotations = remember(bookAnnotations, currentDisplayPage) {
            bookAnnotations.filter { it.pageNumber == currentDisplayPage }
        }
        val currentPageBookmark = remember(bookAnnotations, currentDisplayPage) {
            bookAnnotations.firstOrNull {
                it.type == AnnotationType.BOOKMARK && it.pageNumber == currentDisplayPage
            }
        }
        val isCurrentPageBookmarked = currentPageBookmark != null

        val bookmarksAndHighlightsList = remember(bookAnnotations) {
            bookAnnotations
                .filter { it.type == AnnotationType.BOOKMARK || it.type == AnnotationType.HIGHLIGHT }
                .sortedWith(compareBy<BookAnnotation> { it.pageNumber }.thenBy { it.createdAt })
        }

        val currentPageText = layoutSnapshot?.pageText(pagerState.currentPage).orEmpty()

        val selectionColors = remember(palette.isDark) {
            TextSelectionColors(
                handleColor = if (palette.isDark) Color(0xFFFACC15) else Color(0xFFD97706),
                backgroundColor = if (palette.isDark) Color(0x55FACC15) else Color(0x66FDE047)
            )
        }

        CompositionLocalProvider(
            LocalDensity provides baseDensity,
            LocalClipboard provides selectionCapture,
            LocalClipboardManager provides selectionCapture,
            LocalTextToolbar provides contextualTextToolbar,
            LocalTextSelectionColors provides selectionColors
        ) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(palette.readerBackground)
                    .onGloballyPositioned { coords ->
                        rootPositionInWindow = coords.positionInRoot()
                        rootSize = coords.size
                    },
                contentAlignment = Alignment.Center
            ) {
                // Adaptive container: centers reading column comfortably on phones, foldables, and tablets
                CompositionLocalProvider(LocalDensity provides readerDensity) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(horizontal = 26.dp, vertical = 62.dp)
                            .onSizeChanged { newSize ->
                                if (newSize != viewportSize && newSize.width > 0 && newSize.height > 0) {
                                    viewportSize = newSize
                                }
                            }
                    ) {
                        if (isBookContentLoaded) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { pageIdx ->
                                val pageText = layoutSnapshot?.pageText(pageIdx).orEmpty()

                                // Highlights are matched by their text so they stay correct across layouts.
                                // Only HIGHLIGHT annotations get yellow marker background (not whole-page BOOKMARKs).
                                val annotatedPageText = remember(pageText, bookAnnotations, palette.highlightMarkerBg) {
                                    buildAnnotatedString {
                                        append(pageText)
                                        bookAnnotations.forEach { ann ->
                                            if (ann.type == AnnotationType.HIGHLIGHT) {
                                                val q = ann.text.trim()
                                                if (q.isNotEmpty() && q.length <= pageText.length) {
                                                    var startIdx = pageText.indexOf(q, ignoreCase = true)
                                                    while (startIdx >= 0) {
                                                        addStyle(
                                                            style = SpanStyle(background = palette.highlightMarkerBg),
                                                            start = startIdx,
                                                            end = (startIdx + q.length).coerceAtMost(pageText.length)
                                                        )
                                                        startIdx = pageText.indexOf(q, startIdx + q.length, ignoreCase = true)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clipToBounds()
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(
                                                    requireUnconsumed = false,
                                                    pass = PointerEventPass.Final
                                                )
                                                val downTime = System.currentTimeMillis()
                                                val downPos = down.position
                                                val slop = viewConfiguration.touchSlop
                                                var movedTooMuch = false
                                                var upTime = downTime

                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                    if ((change.position - downPos).getDistance() > slop) {
                                                        movedTooMuch = true
                                                    }
                                                    if (!change.pressed) {
                                                        upTime = System.currentTimeMillis()
                                                        break
                                                    }
                                                }

                                                val duration = upTime - downTime
                                                if (!movedTooMuch && duration < 280L) {
                                                    if (contextualTextToolbar.status == TextToolbarStatus.Shown) {
                                                        contextualTextToolbar.hide()
                                                        focusManager.clearFocus()
                                                    } else {
                                                        isActiveReaderHudVisible = !isActiveReaderHudVisible
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.TopStart
                                ) {
                                    SelectionContainer(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Text(
                                            text = annotatedPageText,
                                            style = readerTextStyle,
                                            overflow = TextOverflow.Visible,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // LOADING SCREEN WITH LOADING BAR UNTIL BOOK CONTENT IS LOADED
                // =========================================================================
                AnimatedVisibility(
                    visible = !isBookContentLoaded,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.readerBackground)
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = book.title,
                                fontFamily = FontFamily.Serif,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = book.author,
                                fontSize = 13.sp,
                                color = palette.textMuted,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(200.dp)
                                    .height(4.dp)
                                    .clip(CircleShape),
                                color = palette.textPrimary,
                                trackColor = palette.borderSubtle
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Loading book...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.textMuted
                            )
                        }
                    }
                }

                // =========================================================================
                // FLOATING CONTEXTUAL ACTION BAR WHEN COVERING WORDS
                // =========================================================================
                if (isBookContentLoaded && contextualTextToolbar.status == TextToolbarStatus.Shown) {
                    val selRect = contextualTextToolbar.selectionRect
                    val density = LocalDensity.current
                    val barWidthPx = with(density) { 290.dp.toPx() }
                    val barHeightPx = with(density) { 48.dp.toPx() }
                    val marginPx = with(density) { 12.dp.toPx() }

                    val localCenterX = (selRect.center.x - rootPositionInWindow.x)
                    val localTopY = (selRect.top - rootPositionInWindow.y)
                    val localBottomY = (selRect.bottom - rootPositionInWindow.y)

                    val maxX = (rootSize.width - barWidthPx - marginPx).coerceAtLeast(marginPx)
                    val targetX = (localCenterX - barWidthPx / 2f).coerceIn(marginPx, maxX)

                    val targetY = if (localTopY - barHeightPx - marginPx >= with(density) { 56.dp.toPx() }) {
                        localTopY - barHeightPx - marginPx
                    } else {
                        (localBottomY + marginPx).coerceAtMost(
                            (rootSize.height - barHeightPx - marginPx).coerceAtLeast(marginPx)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        Surface(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        x = targetX.roundToInt(),
                                        y = targetY.roundToInt()
                                    )
                                }
                                .shadow(
                                    elevation = 14.dp,
                                    shape = CircleShape
                                )
                                .border(1.dp, palette.dockBorder, CircleShape),
                            shape = CircleShape,
                            color = palette.menuSurface
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Highlight covered words directly
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(palette.highlightMarkerBg)
                                        .clickable {
                                            val copyAction = contextualTextToolbar.onCopyRequested
                                            val targetPage = currentDisplayPage
                                            selectionCapture.requestSelectedText(
                                                suppressSystemClip = true,
                                                triggerCopy = copyAction
                                            ) { coveredText ->
                                                if (coveredText.isNotEmpty()) {
                                                    onAddAnnotation(
                                                        AnnotationType.HIGHLIGHT,
                                                        targetPage,
                                                        coveredText
                                                    )
                                                }
                                            }
                                            contextualTextToolbar.hide()
                                            focusManager.clearFocus()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEAB308))
                                    )
                                    Text(
                                        text = "Highlight",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
                                }

                                // Add Note for covered words
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(palette.surfaceColor)
                                        .border(1.dp, palette.borderSubtle, CircleShape)
                                        .clickable {
                                            val copyAction = contextualTextToolbar.onCopyRequested
                                            selectionCapture.requestSelectedText(
                                                suppressSystemClip = true,
                                                triggerCopy = copyAction
                                            ) { coveredText ->
                                                initialDialogType = AnnotationType.NOTE
                                                coveredPassageForNote = coveredText
                                                initialDialogText = ""
                                                showAddNoteOrHighlightDialog = true
                                            }
                                            contextualTextToolbar.hide()
                                            focusManager.clearFocus()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    NotePencilIcon(color = palette.textPrimary, size = 14.dp)
                                    Text(
                                        text = "Add Note",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
                                }

                                // Copy covered words
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            val copyAction = contextualTextToolbar.onCopyRequested
                                            selectionCapture.requestSelectedText(
                                                suppressSystemClip = false,
                                                triggerCopy = copyAction
                                            ) { }
                                            contextualTextToolbar.hide()
                                            focusManager.clearFocus()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Copy",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // STATE 1: Distraction-Free Header & Page Footer
                // =========================================================================
                AnimatedVisibility(
                    visible = isBookContentLoaded && !isActiveReaderHudVisible,
                    enter = if (enablePageAnimations) fadeIn() else EnterTransition.None,
                    exit = if (enablePageAnimations) fadeOut() else ExitTransition.None,
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, start = 24.dp, end = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = book.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isCurrentPageBookmarked) {
                            Spacer(modifier = Modifier.width(8.dp))
                            BookmarkRibbonIcon(
                                color = palette.textPrimary,
                                filled = true,
                                size = 14.dp
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isBookContentLoaded && !isActiveReaderHudVisible,
                    enter = if (enablePageAnimations) fadeIn() else EnterTransition.None,
                    exit = if (enablePageAnimations) fadeOut() else ExitTransition.None,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        Text(
                            text = "$currentDisplayPage",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textMuted
                        )
                        if (currentPageAnnotations.isNotEmpty()) {
                            val firstAnn = currentPageAnnotations.first()
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(palette.surfaceColor)
                                    .border(1.dp, palette.borderSubtle, CircleShape)
                                    .combinedClickable(
                                        onClick = { isActiveReaderHudVisible = true },
                                        onLongClick = { annotationPendingDelete = firstAnn }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "${currentPageAnnotations.size} saved",
                                    fontSize = 11.sp,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                }

                // =========================================================================
                // STATE 2: Active Book Reader HUD Overlays
                // =========================================================================
                AnimatedVisibility(
                    visible = isBookContentLoaded && isActiveReaderHudVisible,
                    enter = if (enablePageAnimations) fadeIn() + slideInVertically { -it / 2 } else EnterTransition.None,
                    exit = if (enablePageAnimations) fadeOut() + slideOutVertically { -it / 2 } else ExitTransition.None,
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.glassBackground)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = book.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        )

                        // Center Page Counter
                        Text(
                            text = "$currentDisplayPage of $pageCount",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textMuted,
                            modifier = Modifier.padding(end = 14.dp)
                        )

                        // Right Circular Close Button (✕)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    if (palette.isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)
                                )
                                .clickable { onCloseReader() },
                            contentAlignment = Alignment.Center
                        ) {
                            CloseCrossIcon(
                                color = if (palette.isDark) Color(0xFFD4D4D8) else Color(0xFF27272A),
                                size = 14.dp
                            )
                        }
                    }
                }

                // =========================================================================
                // EXTENDABLE BOTTOM FLOATING BAR (SWIPE UP TO EXTEND VERTICALLY)
                // =========================================================================
                AnimatedVisibility(
                    visible = isBookContentLoaded && isActiveReaderHudVisible,
                    enter = if (enablePageAnimations) fadeIn() + slideInVertically { it / 2 } else EnterTransition.None,
                    exit = if (enablePageAnimations) fadeOut() + slideOutVertically { it / 2 } else ExitTransition.None,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    var cumulativeDragY by remember { mutableStateOf(0f) }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                            .pointerInput(isBottomBarExpanded) {
                                detectVerticalDragGestures(
                                    onDragStart = { cumulativeDragY = 0f },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        cumulativeDragY += dragAmount
                                        if (cumulativeDragY < -28f && !isBottomBarExpanded) {
                                            isBottomBarExpanded = true
                                            cumulativeDragY = 0f
                                        } else if (cumulativeDragY > 28f && isBottomBarExpanded) {
                                            isBottomBarExpanded = false
                                            cumulativeDragY = 0f
                                        }
                                    }
                                )
                            }
                    ) {
                        val barCornerShape = RoundedCornerShape(if (isBottomBarExpanded) 28.dp else 30.dp)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .shadow(
                                    elevation = if (palette.isDark) 24.dp else 8.dp,
                                    shape = barCornerShape,
                                    ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.6f else 0.12f),
                                    spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.7f else 0.16f)
                                )
                                .border(1.dp, palette.dockBorder, barCornerShape)
                                .animateContentSize(),
                            shape = barCornerShape,
                            color = palette.glassBackground
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Swipe Handle Pill (tap or swipe up/down to extend/collapse)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isBottomBarExpanded = !isBottomBarExpanded }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(36.dp)
                                            .height(4.dp)
                                            .clip(CircleShape)
                                            .background(palette.textMuted.copy(alpha = 0.6f))
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Primary Bottom Bar Row: Add Note, Percentage, Bookmark Toggle, Search
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(
                                                if (palette.isDark) Color.White.copy(alpha = 0.11f)
                                                else Color.Black.copy(alpha = 0.08f)
                                            )
                                            .clickable {
                                                initialDialogType = AnnotationType.NOTE
                                                coveredPassageForNote = ""
                                                initialDialogText = ""
                                                showAddNoteOrHighlightDialog = true
                                            }
                                            .padding(horizontal = 13.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        NotePencilIcon(color = palette.readerText, size = 16.dp)
                                        Text(
                                            text = "Add Note",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.readerText
                                        )
                                    }

                                    Text(
                                        text = String.format(Locale.US, "%.1f%%", readingPercent),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textMuted
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Bookmark Button: shows pressed state & saves the whole page
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isCurrentPageBookmarked) palette.dockTabActiveBg
                                                    else Color.Transparent
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isCurrentPageBookmarked) palette.dockTabActiveBg
                                                    else palette.borderSubtle,
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    val existing = currentPageBookmark
                                                    if (existing != null) {
                                                        onDeleteAnnotation(existing)
                                                    } else {
                                                        val wholePageContent = currentPageText
                                                            .trim()
                                                            .ifBlank { "Page $currentDisplayPage" }
                                                        onAddAnnotation(
                                                            AnnotationType.BOOKMARK,
                                                            currentDisplayPage,
                                                            wholePageContent
                                                        )
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            BookmarkRibbonIcon(
                                                color = if (isCurrentPageBookmarked) palette.dockTabActiveText
                                                else palette.readerText,
                                                filled = isCurrentPageBookmarked,
                                                size = 19.dp
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .border(1.dp, palette.borderSubtle, CircleShape)
                                                .clickable { showSearchInBookDialog = true },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            SearchVectorIcon(color = palette.readerText, size = 18.dp)
                                        }
                                    }
                                }

                                // Extended Options (Text Scaling, Themes, Bookmarks & Highlights)
                                AnimatedVisibility(
                                    visible = isBottomBarExpanded,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 14.dp, bottom = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        HorizontalDivider(color = palette.borderSubtle, thickness = 1.dp)

                                        // 1. Text Scaling Options
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "TEXT SIZE",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                letterSpacing = 0.8.sp,
                                                color = palette.textMuted
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                ReaderTextSize.entries.forEach { preset ->
                                                    val selected = preset == textSizePreset
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clip(RoundedCornerShape(14.dp))
                                                            .background(
                                                                if (selected) palette.dockTabActiveBg
                                                                else palette.surfaceColor.copy(alpha = 0.65f)
                                                            )
                                                            .border(
                                                                width = 1.dp,
                                                                color = if (selected) palette.dockTabActiveBg
                                                                else palette.borderSubtle,
                                                                shape = RoundedCornerShape(14.dp)
                                                            )
                                                            .clickable { onFontSizeChange(preset.sp) }
                                                            .padding(vertical = 10.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                                        ) {
                                                            Text(
                                                                text = "A",
                                                                fontFamily = FontFamily.Serif,
                                                                fontSize = when (preset) {
                                                                    ReaderTextSize.NORMAL -> 14.sp
                                                                    ReaderTextSize.LARGE -> 17.sp
                                                                    ReaderTextSize.EXTRA_LARGE -> 20.sp
                                                                },
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (selected) palette.dockTabActiveText
                                                                else palette.textPrimary
                                                            )
                                                            Text(
                                                                text = preset.label,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = if (selected) palette.dockTabActiveText.copy(alpha = 0.85f)
                                                                else palette.textMuted
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 2. Page Theme Options (Light / Dark)
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "THEME",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                letterSpacing = 0.8.sp,
                                                color = palette.textMuted
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                // Light Pages Option
                                                val isLightSelected = !isDarkTheme
                                                Row(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .background(
                                                            if (isLightSelected) palette.dockTabActiveBg
                                                            else palette.surfaceColor.copy(alpha = 0.65f)
                                                        )
                                                        .border(
                                                            width = 1.dp,
                                                            color = if (isLightSelected) palette.dockTabActiveBg
                                                            else palette.borderSubtle,
                                                            shape = RoundedCornerShape(14.dp)
                                                        )
                                                        .clickable { onToggleTheme(false) }
                                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xFFFAF8F3))
                                                            .border(1.dp, Color(0xFF71717A), CircleShape)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "Light Pages",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (isLightSelected) palette.dockTabActiveText
                                                        else palette.textPrimary
                                                    )
                                                }

                                                // Dark Pages Option
                                                val isDarkSelected = isDarkTheme
                                                Row(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .background(
                                                            if (isDarkSelected) palette.dockTabActiveBg
                                                            else palette.surfaceColor.copy(alpha = 0.65f)
                                                        )
                                                        .border(
                                                            width = 1.dp,
                                                            color = if (isDarkSelected) palette.dockTabActiveBg
                                                            else palette.borderSubtle,
                                                            shape = RoundedCornerShape(14.dp)
                                                        )
                                                        .clickable { onToggleTheme(true) }
                                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xFF101012))
                                                            .border(1.dp, Color(0xFF71717A), CircleShape)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "Dark Pages",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (isDarkSelected) palette.dockTabActiveText
                                                        else palette.textPrimary
                                                    )
                                                }
                                            }
                                        }

                                        // 3. Bookmarks & Highlights Option
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(palette.surfaceColor)
                                                .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                                                .clickable {
                                                    showBookmarksAndHighlightsDialog = true
                                                }
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                BookmarkRibbonIcon(
                                                    color = palette.textPrimary,
                                                    filled = bookmarksAndHighlightsList.isNotEmpty(),
                                                    size = 18.dp
                                                )
                                                Text(
                                                    text = "Bookmarks & Highlights",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.textPrimary
                                                )
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(palette.dockTabActiveBg)
                                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = "${bookmarksAndHighlightsList.size}",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.dockTabActiveText
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // DIALOG: Bookmarks & Highlights (Ordered by Page Number, Tap to Transfer)
            // =========================================================================
            if (showBookmarksAndHighlightsDialog) {
                AlertDialog(
                    onDismissRequest = { showBookmarksAndHighlightsDialog = false },
                    containerColor = palette.menuSurface,
                    titleContentColor = palette.textPrimary,
                    textContentColor = palette.textPrimary,
                    title = {
                        Column {
                            Text(
                                text = "Bookmarks & Highlights",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${book.title} • Tap any entry to go to its page",
                                fontSize = 12.sp,
                                color = palette.textMuted
                            )
                        }
                    },
                    text = {
                        if (bookmarksAndHighlightsList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No bookmarks or highlights yet in this book.\nPress the bookmark button to save a page or cover words to highlight.",
                                    fontSize = 13.5.sp,
                                    color = palette.textMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 340.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = bookmarksAndHighlightsList,
                                    key = { it.id },
                                    contentType = { "bookmark_or_highlight" }
                                ) { item ->
                                    val isBookmark = item.type == AnnotationType.BOOKMARK
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(palette.surfaceColor)
                                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = {
                                                    val targetPageIndex = (item.pageNumber - 1)
                                                        .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                                                    coroutineScope.launch {
                                                        pagerState.scrollToPage(targetPageIndex)
                                                    }
                                                    showBookmarksAndHighlightsDialog = false
                                                    isBottomBarExpanded = false
                                                    isActiveReaderHudVisible = false
                                                },
                                                onLongClick = {
                                                    annotationPendingDelete = item
                                                }
                                            )
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    if (isBookmark) {
                                                        BookmarkRibbonIcon(
                                                            color = palette.textPrimary,
                                                            filled = true,
                                                            size = 14.dp
                                                        )
                                                    } else {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(8.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xFFEAB308))
                                                        )
                                                    }
                                                    Text(
                                                        text = if (isBookmark) "BOOKMARK" else "HIGHLIGHT",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        letterSpacing = 0.8.sp,
                                                        color = palette.textMuted
                                                    )
                                                }

                                                Text(
                                                    text = "PAGE ${item.pageNumber}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 0.6.sp,
                                                    color = palette.textPrimary
                                                )
                                            }

                                            Text(
                                                text = item.text,
                                                fontSize = 13.sp,
                                                color = palette.textSecondary,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showBookmarksAndHighlightsDialog = false }) {
                            Text("Close", color = palette.textPrimary)
                        }
                    }
                )
            }

            // =========================================================================
            // DIALOG: Add Note or Highlight on the Current Page
            // =========================================================================
            if (showAddNoteOrHighlightDialog) {
                var selectedType by remember(initialDialogType) { mutableStateOf(initialDialogType) }
                var textInput by remember(initialDialogText) { mutableStateOf(initialDialogText) }

                val selectableTypes = remember {
                    listOf(AnnotationType.NOTE, AnnotationType.HIGHLIGHT)
                }

                AlertDialog(
                    onDismissRequest = {
                        showAddNoteOrHighlightDialog = false
                        coveredPassageForNote = ""
                    },
                    containerColor = palette.menuSurface,
                    titleContentColor = palette.textPrimary,
                    textContentColor = palette.textPrimary,
                    title = {
                        Column {
                            Text(
                                text = if (selectedType == AnnotationType.HIGHLIGHT) "Add Highlight" else "Add Note",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${book.title} • Page $currentDisplayPage (syncs to Notes)",
                                fontSize = 12.sp,
                                color = palette.textMuted
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (coveredPassageForNote.isBlank()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    selectableTypes.forEach { type ->
                                        val selected = selectedType == type
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (selected) palette.dockTabActiveBg else Color.Transparent)
                                                .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                                .clickable { selectedType = type }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (type == AnnotationType.NOTE) "NOTE" else "HIGHLIGHT",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (selected) Color.White else palette.textMuted
                                            )
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.surfaceColor)
                                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "\"$coveredPassageForNote\"",
                                        fontSize = 12.5.sp,
                                        fontStyle = FontStyle.Italic,
                                        color = palette.textSecondary,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                label = {
                                    Text(
                                        if (selectedType == AnnotationType.HIGHLIGHT) {
                                            "Highlight passage on Page $currentDisplayPage"
                                        } else {
                                            "Write your note for Page $currentDisplayPage"
                                        }
                                    )
                                },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val cleaned = textInput.trim()
                                val finalNoteText = when {
                                    coveredPassageForNote.isNotBlank() && cleaned.isNotEmpty() ->
                                        "\"$coveredPassageForNote\"\n\n$cleaned"
                                    coveredPassageForNote.isNotBlank() ->
                                        coveredPassageForNote
                                    else -> cleaned
                                }
                                if (finalNoteText.isNotEmpty()) {
                                    onAddAnnotation(selectedType, currentDisplayPage, finalNoteText)
                                    showAddNoteOrHighlightDialog = false
                                    coveredPassageForNote = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.textPrimary,
                                contentColor = palette.appBackground
                            )
                        ) {
                            Text("Save to Notes", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showAddNoteOrHighlightDialog = false
                                coveredPassageForNote = ""
                            }
                        ) {
                            Text("Cancel", color = palette.textMuted)
                        }
                    }
                )
            }

            // =========================================================================
            // DIALOG: Search inside the current Book
            // =========================================================================
            if (showSearchInBookDialog) {
                var query by rememberSaveable { mutableStateOf("") }
                val allPages = remember(layoutSnapshot) {
                    layoutSnapshot?.let { l -> List(l.pageCount) { l.pageText(it) } } ?: emptyList()
                }
                val matchingPages = remember(query, allPages) {
                    if (query.isBlank()) {
                        allPages.mapIndexed { idx, content -> idx to content }
                    } else {
                        allPages.mapIndexedNotNull { idx, content ->
                            if (content.contains(query.trim(), ignoreCase = true)) idx to content else null
                        }
                    }
                }

                AlertDialog(
                    onDismissRequest = { showSearchInBookDialog = false },
                    containerColor = palette.menuSurface,
                    titleContentColor = palette.textPrimary,
                    textContentColor = palette.textPrimary,
                    title = {
                        Text(
                            text = "Search in ${book.title}",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Search text or jump to page...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = matchingPages,
                                    key = { it.first },
                                    contentType = { "search_match" }
                                ) { (pageIdx, content) ->
                                    val dispPage = pageIdx + 1
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(palette.surfaceColor)
                                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                            .clickable {
                                                coroutineScope.launch {
                                                    pagerState.scrollToPage(pageIdx)
                                                }
                                                showSearchInBookDialog = false
                                            }
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = "PAGE $dispPage",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.textMuted
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = content,
                                                fontSize = 12.5.sp,
                                                color = palette.textPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSearchInBookDialog = false }) {
                            Text("Close", color = palette.textPrimary)
                        }
                    }
                )
            }

            // =========================================================================
            // DIALOG: Long-press Delete Note/Highlight/Bookmark Confirmation
            // =========================================================================
            annotationPendingDelete?.let { targetAnn ->
                val label = when (targetAnn.type) {
                    AnnotationType.HIGHLIGHT -> "Highlight"
                    AnnotationType.BOOKMARK -> "Bookmark"
                    AnnotationType.NOTE -> "Note"
                }
                AlertDialog(
                    onDismissRequest = { annotationPendingDelete = null },
                    containerColor = palette.menuSurface,
                    titleContentColor = palette.textPrimary,
                    textContentColor = palette.textPrimary,
                    title = {
                        Text(
                            text = "Delete $label",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    text = {
                        Text(
                            text = "Delete this $label from Page ${targetAnn.pageNumber}?\n\n\"${targetAnn.text}\"",
                            fontSize = 14.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.textSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onDeleteAnnotation(targetAnn)
                                annotationPendingDelete = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFDC2626),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Delete", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { annotationPendingDelete = null }) {
                            Text("Cancel", color = palette.textMuted)
                        }
                    }
                )
            }
        }
    }
}
