package com.example.freebook.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.freebook.data.EpubArchiveResourceServer
import com.example.freebook.data.EpubParser
import com.example.freebook.data.EpubRichDocumentBuilder
import com.example.freebook.data.RichEpubDocument
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.BookReadingDirection
import com.example.freebook.model.FixedLayoutFitMode
import com.example.freebook.model.HighlightColor
import com.example.freebook.model.PublicationLayoutType
import com.example.freebook.model.ReaderFontSizeStep
import com.example.freebook.model.ReaderNavigationMode
import com.example.freebook.model.ReaderPreferences
import com.example.freebook.model.ReaderTextAlign
import com.example.freebook.model.ReaderTheme
import com.example.freebook.ui.components.BookmarkRibbonIcon
import com.example.freebook.ui.components.CloseCrossIcon
import com.example.freebook.ui.components.MoreHorizDotsIcon
import com.example.freebook.ui.components.NotePencilIcon
import com.example.freebook.ui.components.SearchVectorIcon
import com.example.freebook.ui.reader.CustomPdfReader
import com.example.freebook.ui.reader.PdfTocEntry
import com.example.freebook.ui.reader.ReaderAudioDockMode
import com.example.freebook.ui.reader.ReaderAudioOverlaysAndSheets
import com.example.freebook.ui.reader.ReaderAudioTtsDock
import com.example.freebook.ui.reader.ReadiumActionModeSession
import com.example.freebook.ui.reader.ReadiumSelectionWebView
import com.example.freebook.ui.reader.ReadiumTocEntry
import com.example.freebook.ui.reader.ReadiumWebViewNavigator
import com.example.freebook.ui.reader.rememberReaderAudioState
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import java.util.Locale
import kotlin.math.roundToInt

private data class ReflowableTocLoadState(
    val isLoading: Boolean,
    val entries: List<ReadiumTocEntry>
)

private data class RichEpubLoadState(
    val isResolved: Boolean,
    val document: RichEpubDocument?
)

private data class EpubResourceServerState(
    val isReady: Boolean,
    val server: EpubArchiveResourceServer?
)

private fun normalizeCanonicalText(raw: String): String =
    raw.replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
@Composable
fun BookReaderScreen(
    book: Book,
    bookAnnotations: List<BookAnnotation>,
    preferences: ReaderPreferences,
    loadBookText: suspend (Book) -> String,
    onRegisterActionModeSession: (ReadiumActionModeSession?) -> Unit,
    onSelectReaderTheme: (ReaderTheme) -> Unit,
    onSelectFontSizeMultiplier: (Float) -> Unit,
    onSelectLineHeightMultiplier: (Float) -> Unit,
    onSelectPageMarginDp: (Int) -> Unit,
    onSelectTextAlignment: (ReaderTextAlign) -> Unit,
    onSelectPublisherStyles: (Boolean) -> Unit,
    onSelectNavigationMode: (ReaderNavigationMode) -> Unit,
    onSelectFixedLayoutFitMode: (FixedLayoutFitMode) -> Unit,
    onLocatorChanged: (
        charOffset: Int,
        totalChars: Int,
        derivedPosition: Int,
        derivedTotalPositions: Int,
        rawLocatorJson: String?,
        explicitProgressPercent: Float?
    ) -> Unit,
    onAddAnnotation: (
        type: AnnotationType,
        pageNumber: Int,
        charOffset: Int,
        endCharOffset: Int,
        text: String,
        quotedText: String,
        highlightColorName: String
    ) -> Unit,
    onUpdateAnnotationColor: (BookAnnotation, HighlightColor) -> Unit = { _, _ -> },
    onDeleteAnnotation: (BookAnnotation) -> Unit,
    onCloseReader: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = LocalBookFreePalette.current
    val coroutineScope = rememberCoroutineScope()

    val activeReaderTheme = preferences.readerTheme
    val readerBgColor = remember(activeReaderTheme) { Color(activeReaderTheme.backgroundArgb) }
    val readerTextColor = remember(activeReaderTheme) { Color(activeReaderTheme.textArgb) }
    val isDarkReaderSurface = activeReaderTheme.isDarkSurface

    var isActiveReaderHudVisible by rememberSaveable { mutableStateOf(false) }
    var isBottomBarExpanded by rememberSaveable { mutableStateOf(false) }
    var isAudioDockExpanded by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }
    var audioDockMode by rememberSaveable { mutableStateOf(ReaderAudioDockMode.TTS) }
    val dockPagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    var showThemesBottomSheet by rememberSaveable { mutableStateOf(false) }
    var showTocBottomSheet by rememberSaveable { mutableStateOf(false) }
    var activeHighlightColor by rememberSaveable { mutableStateOf(HighlightColor.YELLOW) }

    LaunchedEffect(isActiveReaderHudVisible, isBottomBarExpanded) {
        if (!isActiveReaderHudVisible) {
            isBottomBarExpanded = false
            isAudioDockExpanded = false
            showOverflowMenu = false
        } else if (!isBottomBarExpanded) {
            showOverflowMenu = false
        }
    }

    LaunchedEffect(dockPagerState.currentPage, dockPagerState.isScrollInProgress) {
        if (dockPagerState.currentPage != 0 || dockPagerState.isScrollInProgress) {
            showOverflowMenu = false
        }
    }

    val canonicalBookText by produceState(initialValue = "", key1 = book.id, key2 = book.textContentFilePath) {
        value = withContext(Dispatchers.Default) {
            normalizeCanonicalText(loadBookText(book))
        }
    }

    val epubResourceServerState by produceState(
        initialValue = EpubResourceServerState(
            isReady = book.layoutType != PublicationLayoutType.REFLOWABLE || book.bookFilePath.isBlank(),
            server = null
        ),
        key1 = book.id,
        key2 = book.bookFilePath,
        key3 = book.coverImagePath
    ) {
        val opened = if (book.layoutType == PublicationLayoutType.REFLOWABLE && book.bookFilePath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                EpubArchiveResourceServer.open(book.bookFilePath, book.coverImagePath)
            }
        } else {
            null
        }
        value = EpubResourceServerState(isReady = true, server = opened)
        awaitDispose {
            opened?.close()
        }
    }
    val epubResourceServer = epubResourceServerState.server

    val richEpubLoadState by produceState(
        initialValue = RichEpubLoadState(
            isResolved = book.layoutType == PublicationLayoutType.FIXED || book.bookFilePath.isBlank(),
            document = null
        ),
        key1 = book.id,
        key2 = canonicalBookText.length,
        key3 = epubResourceServerState
    ) {
        if (book.layoutType != PublicationLayoutType.REFLOWABLE || book.bookFilePath.isBlank()) {
            value = RichEpubLoadState(isResolved = true, document = null)
            return@produceState
        }
        if (canonicalBookText.isEmpty() || !epubResourceServerState.isReady) {
            value = RichEpubLoadState(isResolved = false, document = null)
            return@produceState
        }
        val loaded = EpubRichDocumentBuilder.loadRichEpubDocument(
            context = context,
            book = book,
            canonicalBookText = canonicalBookText,
            resourceServer = epubResourceServerState.server
        )
        value = RichEpubLoadState(isResolved = true, document = loaded)
    }

    var isWebViewDocumentReady by remember(book.id) { mutableStateOf(false) }
    var canNavigateBackInBook by remember(book.id) { mutableStateOf(false) }
    var isPdfDocumentReady by remember(book.id) { mutableStateOf(false) }
    var pdfJumpToPage by remember(book.id) { mutableStateOf<((Int) -> Unit)?>(null) }
    var pdfTocEntries by remember(book.id) { mutableStateOf<List<PdfTocEntry>>(emptyList()) }
    var currentPdfPageIndex by rememberSaveable(book.id) {
        mutableIntStateOf(book.currentPageIndex.coerceAtLeast(0))
    }
    var pdfIsRtl by rememberSaveable(book.id) {
        mutableStateOf(book.readingDirection == BookReadingDirection.RTL)
    }
    var hasManualPdfRtlOverride by rememberSaveable(book.id) { mutableStateOf(false) }
    val isBookContentLoaded = if (book.layoutType == PublicationLayoutType.FIXED) {
        isPdfDocumentReady
    } else {
        canonicalBookText.isNotEmpty() && richEpubLoadState.isResolved && isWebViewDocumentReady
    }

    // Stable locator state (canonical character offset, derived position, derived total positions)
    var currentCharOffset by rememberSaveable(book.id) {
        mutableIntStateOf(book.resolveSavedCharOffset(0))
    }
    var derivedCurrentPosition by rememberSaveable(book.id) {
        mutableIntStateOf((book.currentPageIndex + 1).coerceAtLeast(1))
    }
    var derivedTotalPositions by rememberSaveable(book.id) {
        mutableIntStateOf(book.totalPages.coerceAtLeast(1))
    }
    var currentVisibleText by remember { mutableStateOf("") }
    var isInitialPositionRestored by remember(book.id) { mutableStateOf(false) }
    var currentPdfProgressPercent by rememberSaveable(book.id) {
        mutableFloatStateOf(book.readingProgress.coerceIn(0.1f, 100f))
    }

    LaunchedEffect(book.id, book.layoutType, canonicalBookText.length) {
        if (
            book.layoutType == PublicationLayoutType.REFLOWABLE &&
            canonicalBookText.isNotEmpty() &&
            !isInitialPositionRestored &&
            currentCharOffset == 0
        ) {
            val savedOffset = book.resolveSavedCharOffset(canonicalBookText.length)
            if (savedOffset > 0) {
                currentCharOffset = savedOffset
            }
        }
    }

    val readingPercent by remember(
        book.layoutType,
        currentPdfProgressPercent,
        currentCharOffset,
        canonicalBookText.length,
        derivedCurrentPosition,
        derivedTotalPositions
    ) {
        derivedStateOf {
            if (book.layoutType == PublicationLayoutType.FIXED) {
                if (derivedTotalPositions > 1 && derivedCurrentPosition >= derivedTotalPositions) {
                    100f
                } else {
                    currentPdfProgressPercent.coerceIn(0.1f, 99.9f)
                }
            } else if (canonicalBookText.isEmpty()) {
                0.1f
            } else if (derivedTotalPositions > 1 && derivedCurrentPosition >= derivedTotalPositions) {
                100f
            } else {
                (currentCharOffset.toFloat() / canonicalBookText.length.toFloat() * 100f).coerceIn(0.1f, 99.9f)
            }
        }
    }

    var showAddNoteDialog by rememberSaveable { mutableStateOf(false) }
    var noteInputText by rememberSaveable { mutableStateOf("") }
    var coveredPassageForNote by rememberSaveable { mutableStateOf("") }
    var coveredStartOffsetForNote by rememberSaveable { mutableIntStateOf(-1) }
    var coveredEndOffsetForNote by rememberSaveable { mutableIntStateOf(-1) }
    var showSearchInBookDialog by rememberSaveable { mutableStateOf(false) }
    var showBookmarksAndHighlightsDialog by rememberSaveable { mutableStateOf(false) }
    var annotationPendingDelete by remember { mutableStateOf<BookAnnotation?>(null) }
    var tappedHighlightId by remember { mutableStateOf<String?>(null) }
    var tappedHighlightRect by remember { mutableStateOf<RectF?>(null) }

    val tappedHighlightAnnotation = remember(bookAnnotations, tappedHighlightId) {
        tappedHighlightId?.let { targetId ->
            bookAnnotations.firstOrNull { it.id == targetId }
        }
    }

    // Readium DecorableNavigator instance managing the WebView and Readium Decorations
    val readiumNavigator = remember(book.id) { ReadiumWebViewNavigator() }

    LaunchedEffect(readiumNavigator, canonicalBookText.length) {
        readiumNavigator.totalCanonicalChars = canonicalBookText.length
    }

    val webViewCurrentLocator by readiumNavigator.currentLocator.collectAsState()

    val reflowableTocState by produceState(
        initialValue = ReflowableTocLoadState(
            isLoading = book.layoutType == PublicationLayoutType.REFLOWABLE && book.bookFilePath.isNotBlank(),
            entries = emptyList()
        ),
        key1 = book.id,
        key2 = canonicalBookText.length
    ) {
        if (book.layoutType != PublicationLayoutType.REFLOWABLE || book.bookFilePath.isBlank()) {
            value = ReflowableTocLoadState(isLoading = false, entries = emptyList())
            return@produceState
        }
        if (canonicalBookText.isEmpty()) {
            value = ReflowableTocLoadState(isLoading = true, entries = emptyList())
            return@produceState
        }
        val cached = EpubParser.getCachedReflowableTableOfContents(book, canonicalBookText.length)
        if (cached != null) {
            readiumNavigator.registerTableOfContents(cached)
            value = ReflowableTocLoadState(isLoading = false, entries = cached)
            return@produceState
        }
        value = ReflowableTocLoadState(isLoading = true, entries = emptyList())
        val loaded = EpubParser.loadReflowableTableOfContents(
            context = context,
            book = book,
            canonicalBookText = canonicalBookText
        )
        readiumNavigator.registerTableOfContents(loaded)
        value = ReflowableTocLoadState(isLoading = false, entries = loaded)
    }
    val reflowableTocEntries = reflowableTocState.entries

    val currentProgressionFraction by remember(
        book.layoutType,
        webViewCurrentLocator,
        derivedCurrentPosition,
        derivedTotalPositions,
        readingPercent,
        isInitialPositionRestored,
        isPdfDocumentReady
    ) {
        derivedStateOf {
            if (book.layoutType == PublicationLayoutType.FIXED) {
                val safeTotal = derivedTotalPositions.coerceAtLeast(1)
                if (safeTotal <= 1 || derivedCurrentPosition >= safeTotal) {
                    1f
                } else {
                    (derivedCurrentPosition.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
                }
            } else {
                val locatorProg = webViewCurrentLocator.locations.totalProgression?.toFloat()
                when {
                    derivedTotalPositions > 1 && derivedCurrentPosition >= derivedTotalPositions -> 1f
                    isInitialPositionRestored && locatorProg != null -> locatorProg.coerceIn(0f, 1f)
                    else -> (readingPercent / 100f).coerceIn(0f, 1f)
                }
            }
        }
    }

    val animatedProgressFill by animateFloatAsState(
        targetValue = currentProgressionFraction,
        animationSpec = if (preferences.enablePageAnimations) tween(durationMillis = 220) else snap(),
        label = "contents_progress_fill"
    )
    val displayProgressInt = (currentProgressionFraction * 100f).roundToInt().coerceIn(0, 100)

    val latestCharOffset by rememberUpdatedState(currentCharOffset)
    val latestTotalChars by rememberUpdatedState(canonicalBookText.length)
    val latestDerivedPos by rememberUpdatedState(derivedCurrentPosition)
    val latestDerivedTotalPos by rememberUpdatedState(derivedTotalPositions)
    val latestIsRestored by rememberUpdatedState(isInitialPositionRestored)
    val latestIsPdfReady by rememberUpdatedState(isPdfDocumentReady)
    val latestPdfPageIndex by rememberUpdatedState(currentPdfPageIndex)
    val latestPdfProgressPercent by rememberUpdatedState(currentPdfProgressPercent)
    val latestOnLocatorChanged by rememberUpdatedState(onLocatorChanged)
    val latestOnAddAnnotation by rememberUpdatedState(onAddAnnotation)
    val latestCanonicalText by rememberUpdatedState(canonicalBookText)

    val audioState = rememberReaderAudioState(
        book = book,
        canonicalBookText = canonicalBookText,
        reflowableTocEntries = reflowableTocEntries,
        pdfTocEntries = pdfTocEntries,
        derivedTotalPositions = derivedTotalPositions,
        latestPdfPageIndexProvider = { latestPdfPageIndex },
        pdfJumpToPageProvider = { pdfJumpToPage },
        readiumNavigator = readiumNavigator,
        isDarkReaderSurface = isDarkReaderSurface,
        isWebViewDocumentReady = isWebViewDocumentReady
    )

    // Register Readium DecorableNavigator.Listener so pressing a highlighted text on the page
    // opens the floating Highlight Color Picker for that highlight
    DisposableEffect(readiumNavigator) {
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                tappedHighlightId = event.decoration.id
                tappedHighlightRect = event.rect
                return true
            }
        }
        readiumNavigator.addDecorationListener("highlights", listener)
        onDispose {
            readiumNavigator.removeDecorationListener(listener)
        }
    }

    // Wire navigator callbacks
    SideEffectOrUpdateNavigatorCallbacks(
        navigator = readiumNavigator,
        onInitialPositionRestored = {
            isInitialPositionRestored = true
        },
        onLocatorUpdated = { offset, posOneBased, totalPos, visibleText ->
            if (posOneBased != derivedCurrentPosition) {
                tappedHighlightId = null
            }
            currentCharOffset = offset
            derivedCurrentPosition = posOneBased
            derivedTotalPositions = totalPos
            if (visibleText.isNotBlank()) {
                currentVisibleText = visibleText
            }
            if (isInitialPositionRestored && latestTotalChars > 0) {
                latestOnLocatorChanged(
                    offset,
                    latestTotalChars,
                    (posOneBased - 1).coerceAtLeast(0),
                    totalPos,
                    null,
                    null
                )
            }
        },
        onSingleTapToggleHud = {
            if (tappedHighlightId != null) {
                tappedHighlightId = null
            } else {
                isActiveReaderHudVisible = !isActiveReaderHudVisible
            }
        },
        onUserManualNavigation = {
            audioState.onUserManuallyNavigatedBook(fromExplicitJump = false)
        },
        onWordDoubleTap = { charOffset, _ ->
            audioState.handleWordDoubleTap(
                pageIndex = 0,
                charOffset = charOffset,
                audioDockMode = audioDockMode,
                context = context
            )
        },
        onLinkHistoryChanged = { canGoBack ->
            canNavigateBackInBook = canGoBack
        },
        onExternalLinkClicked = { url ->
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }
    )

    fun resolveSelectionRange(
        selectedText: String,
        jsStartOffset: Int,
        jsEndOffset: Int
    ): Pair<Int, Int> {
        val text = latestCanonicalText
        if (text.isEmpty() || selectedText.isEmpty()) {
            return latestCharOffset to latestCharOffset
        }
        if (jsStartOffset >= 0 && jsEndOffset > jsStartOffset && jsStartOffset < text.length) {
            return jsStartOffset.coerceIn(0, text.length) to jsEndOffset.coerceIn(0, text.length)
        }
        val searchOrigin = (latestCharOffset - 400).coerceAtLeast(0)
        val foundStart = text.indexOf(selectedText, startIndex = searchOrigin, ignoreCase = true)
            .takeIf { it >= 0 }
            ?: text.indexOf(selectedText, ignoreCase = true).takeIf { it >= 0 }
            ?: latestCharOffset
        val foundEnd = (foundStart + selectedText.length).coerceAtMost(text.length)
        return foundStart to foundEnd
    }

    val activeSession = remember(readiumNavigator) {
        ReadiumActionModeSession(
            navigator = readiumNavigator,
            readerTheme = activeReaderTheme,
            isDarkMode = isDarkReaderSurface,
            onHighlightSelected = { selectedText, jsStart, jsEnd, _ ->
                val cleaned = selectedText.trim()
                if (cleaned.isNotEmpty()) {
                    val (startOffset, endOffset) = resolveSelectionRange(cleaned, jsStart, jsEnd)
                    tappedHighlightId = null
                    latestOnAddAnnotation(
                        AnnotationType.HIGHLIGHT,
                        latestDerivedPos,
                        startOffset,
                        endOffset,
                        cleaned,
                        "",
                        HighlightColor.YELLOW.name
                    )
                }
            },
            onCopySelected = { selectedText ->
                ReadiumWebViewNavigator.copyTextToSystemClipboard(context, selectedText)
            },
            onAddNoteForSelected = { selectedText, jsStart, jsEnd ->
                val cleaned = selectedText.trim()
                if (cleaned.isNotEmpty()) {
                    val (startOffset, endOffset) = resolveSelectionRange(cleaned, jsStart, jsEnd)
                    tappedHighlightId = null
                    coveredPassageForNote = cleaned
                    coveredStartOffsetForNote = startOffset
                    coveredEndOffsetForNote = endOffset
                    noteInputText = ""
                    showAddNoteDialog = true
                }
            }
        )
    }

    LaunchedEffect(activeSession, activeReaderTheme, isDarkReaderSurface) {
        activeSession.readerTheme = activeReaderTheme
        activeSession.isDarkMode = isDarkReaderSurface
        onRegisterActionModeSession(activeSession)
    }

    // Register ActionMode session with MainActivity so MainActivity.onActionModeStarted
    // clears the default OS menu and injects Highlight (Yellow), Add Note, and Copy
    DisposableEffect(activeSession) {
        onRegisterActionModeSession(activeSession)
        onDispose {
            onRegisterActionModeSession(null)
        }
    }

    // Apply highlights visually using Readium's built-in DecorableNavigator and Decoration API
    LaunchedEffect(
        bookAnnotations,
        canonicalBookText.length,
        isDarkReaderSurface,
        isWebViewDocumentReady
    ) {
        if (!isWebViewDocumentReady || canonicalBookText.isEmpty()) return@LaunchedEffect
        val decorations = bookAnnotations.mapNotNull { annotation ->
            ReadiumWebViewNavigator.annotationToReadiumDecoration(
                annotation = annotation,
                book = book,
                totalChars = canonicalBookText.length,
                isDarkSurface = isDarkReaderSurface
            )
        }
        readiumNavigator.applyDecorations(decorations, group = "highlights")
    }

    // Propagate Readium EpubPreferences (Theme, 8-step Font Size, Line Height, Margins, Alignment, Navigation Mode)
    LaunchedEffect(
        preferences,
        book.layoutType,
        book.readingDirection,
        isWebViewDocumentReady
    ) {
        if (!isWebViewDocumentReady) return@LaunchedEffect
        readiumNavigator.applyPreferencesInWebView(
            book = book,
            preferences = preferences,
            savedCharOffset = currentCharOffset
        )
    }

    // Flush current locator when exiting the reader or when the app goes to background
    fun flushCurrentLocator() {
        if (book.layoutType == PublicationLayoutType.FIXED) {
            if (latestIsPdfReady) {
                latestOnLocatorChanged(
                    0,
                    1,
                    latestPdfPageIndex.coerceAtLeast(0),
                    latestDerivedTotalPos.coerceAtLeast(1),
                    null,
                    latestPdfProgressPercent
                )
            }
        } else if (latestIsRestored && latestTotalChars > 0) {
            latestOnLocatorChanged(
                latestCharOffset,
                latestTotalChars,
                (latestDerivedPos - 1).coerceAtLeast(0),
                latestDerivedTotalPos,
                null,
                null
            )
        }
    }

    DisposableEffect(lifecycleOwner, book.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                flushCurrentLocator()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushCurrentLocator()
        }
    }

    fun jumpToLocator(targetCharOffset: Int, fallbackPageNumber: Int) {
        audioState.onUserManuallyNavigatedBook(fromExplicitJump = true)
        if (book.layoutType == PublicationLayoutType.FIXED) {
            val targetPage = if (fallbackPageNumber > 0) fallbackPageNumber else 1
            pdfJumpToPage?.invoke(targetPage)
            derivedCurrentPosition = targetPage.coerceIn(1, derivedTotalPositions)
            return
        }
        if (canonicalBookText.isEmpty()) return
        val resolvedOffset = if (targetCharOffset > 0 || fallbackPageNumber <= 1) {
            targetCharOffset.coerceIn(0, (canonicalBookText.length - 1).coerceAtLeast(0))
        } else {
            val fraction = (fallbackPageNumber - 1).toFloat() / derivedTotalPositions.coerceAtLeast(1).toFloat()
            (fraction * canonicalBookText.length).toInt().coerceIn(0, (canonicalBookText.length - 1).coerceAtLeast(0))
        }
        currentCharOffset = resolvedOffset
        readiumNavigator.jumpToCharOffset(resolvedOffset)
    }

    val activePdfPageNumber by remember(
        currentPdfPageIndex,
        derivedTotalPositions
    ) {
        derivedStateOf {
            (currentPdfPageIndex + 1).coerceIn(1, derivedTotalPositions.coerceAtLeast(1))
        }
    }

    val currentPositionBookmark = remember(
        book.layoutType,
        bookAnnotations,
        activePdfPageNumber,
        derivedCurrentPosition,
        currentCharOffset
    ) {
        if (book.layoutType == PublicationLayoutType.FIXED) {
            bookAnnotations.firstOrNull { ann ->
                ann.type == AnnotationType.BOOKMARK && ann.pageNumber == activePdfPageNumber
            }
        } else {
            bookAnnotations.firstOrNull { ann ->
                ann.type == AnnotationType.BOOKMARK &&
                    (ann.pageNumber == derivedCurrentPosition ||
                        (ann.charOffset > 0 && kotlin.math.abs(ann.charOffset - currentCharOffset) < 280))
            }
        }
    }
    val isCurrentPositionBookmarked = currentPositionBookmark != null

    val allBookAnnotationsSorted = remember(bookAnnotations) {
        bookAnnotations.sortedWith(
            compareBy<BookAnnotation> { it.pageNumber }
                .thenBy { it.charOffset }
                .thenBy { it.createdAt }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(readerBgColor),
        contentAlignment = Alignment.Center
    ) {
        // =========================================================================
        // READER HOST (Fixed-Layout PDF via CustomPdfReader vs Reflowable EPUB via Readium WebView)
        // =========================================================================
        if (book.layoutType == PublicationLayoutType.FIXED) {
            CustomPdfReader(
                book = book,
                bookAnnotations = bookAnnotations,
                navigationMode = preferences.navigationMode,
                readerTheme = activeReaderTheme,
                isRtl = pdfIsRtl,
                onDocumentReady = { totalPages ->
                    derivedTotalPositions = totalPages
                    isPdfDocumentReady = true
                },
                onPageChanged = { pageIndex, totalPages, progressPercent ->
                    val pageNumber = (pageIndex + 1).coerceIn(1, totalPages.coerceAtLeast(1))
                    if (pageNumber != derivedCurrentPosition) {
                        tappedHighlightId = null
                    }
                    currentPdfPageIndex = pageIndex
                    derivedCurrentPosition = pageNumber
                    derivedTotalPositions = totalPages
                    currentPdfProgressPercent = progressPercent
                    currentCharOffset = 0
                    currentVisibleText = "Page $pageNumber"
                    latestOnLocatorChanged(
                        0,
                        1,
                        pageIndex.coerceAtLeast(0),
                        totalPages.coerceAtLeast(1),
                        null,
                        progressPercent
                    )
                },
                onSingleTap = {
                    if (tappedHighlightId != null) {
                        tappedHighlightId = null
                    } else {
                        isActiveReaderHudVisible = !isActiveReaderHudVisible
                    }
                },
                onHighlightSelection = { pageNumberOneBased, startCharOffset, endCharOffset, selectedText ->
                    tappedHighlightId = null
                    latestOnAddAnnotation(
                        AnnotationType.HIGHLIGHT,
                        pageNumberOneBased,
                        startCharOffset,
                        endCharOffset,
                        selectedText,
                        "",
                        HighlightColor.YELLOW.name
                    )
                },
                onAddNoteForSelection = { pageNumberOneBased, startCharOffset, endCharOffset, selectedText ->
                    tappedHighlightId = null
                    derivedCurrentPosition = pageNumberOneBased
                    coveredPassageForNote = selectedText
                    coveredStartOffsetForNote = startCharOffset
                    coveredEndOffsetForNote = endCharOffset
                    noteInputText = ""
                    showAddNoteDialog = true
                },
                onCopySelection = { selectedText ->
                    ReadiumWebViewNavigator.copyTextToSystemClipboard(context, selectedText)
                },
                onTapSavedHighlight = { annotation, anchorRect ->
                    tappedHighlightId = annotation.id
                    tappedHighlightRect = anchorRect
                },
                onBindNavigator = { jumper ->
                    pdfJumpToPage = jumper
                },
                onTableOfContentsLoaded = { entries ->
                    pdfTocEntries = entries
                },
                onAutoDetectRtl = { detectedRtl ->
                    if (!hasManualPdfRtlOverride) {
                        pdfIsRtl = detectedRtl
                    }
                },
                activeWordHighlight = audioState.activeWordHighlight,
                isAudioWordTapEnabled = audioState.isAudioWordTapEnabled,
                onWordDoubleTap = { pageIndex, charOffset ->
                    audioState.handleWordDoubleTap(
                        pageIndex = pageIndex,
                        charOffset = charOffset,
                        audioDockMode = audioDockMode,
                        context = context
                    )
                },
                onUserManualPageChange = {
                    audioState.onUserManuallyNavigatedBook(fromExplicitJump = false)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (canonicalBookText.isNotEmpty() && richEpubLoadState.isResolved) {
            val richDoc = richEpubLoadState.document
            val bookBaseUrl = "https://readium.local/book/${book.id}/"
            LaunchedEffect(readiumNavigator, richDoc) {
                readiumNavigator.isRichMode = (richDoc != null)
            }
            val initialHtml = remember(book.id, canonicalBookText, richDoc) {
                val initialOffset = book.resolveSavedCharOffset(canonicalBookText.length)
                readiumNavigator.isRichMode = (richDoc != null)
                ReadiumWebViewNavigator.buildReadiumDocumentHtml(
                    book = book,
                    canonicalText = canonicalBookText,
                    preferences = preferences,
                    initialCharOffset = initialOffset,
                    richDoc = richDoc,
                    initialPageIndex = book.currentPageIndex.coerceAtLeast(0)
                )
            }

            val latestSession by rememberUpdatedState(activeSession)
            val latestResourceServer by rememberUpdatedState(epubResourceServer)

            AndroidView(
                modifier = Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxSize(),
                factory = { ctx ->
                    ReadiumSelectionWebView(ctx, readiumNavigator).apply {
                        actionModeSessionProvider = { latestSession }
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.allowFileAccess = false
                        settings.builtInZoomControls = (book.layoutType == PublicationLayoutType.FIXED)
                        settings.displayZoomControls = false
                        settings.setSupportZoom(book.layoutType == PublicationLayoutType.FIXED)

                        addJavascriptInterface(readiumNavigator.JsBridge(), "ReadiumBridge")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val urlStr = request?.url?.toString() ?: return null
                                return latestResourceServer?.interceptRequest(urlStr, bookBaseUrl)
                                    ?: super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val urlStr = request?.url?.toString().orEmpty()
                                if (
                                    (urlStr.startsWith("http://", ignoreCase = true) ||
                                        urlStr.startsWith("https://", ignoreCase = true)) &&
                                    !urlStr.startsWith(bookBaseUrl)
                                ) {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)))
                                    }
                                }
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                readiumNavigator.isPageLoaded = true
                                isWebViewDocumentReady = true
                                readiumNavigator.reapplyAllDecorations()
                                coroutineScope.launch {
                                    val decorations = bookAnnotations.mapNotNull { ann ->
                                        ReadiumWebViewNavigator.annotationToReadiumDecoration(
                                            annotation = ann,
                                            book = book,
                                            totalChars = canonicalBookText.length,
                                            isDarkSurface = isDarkReaderSurface
                                        )
                                    }
                                    readiumNavigator.applyDecorations(decorations, group = "highlights")
                                }
                            }
                        }

                        readiumNavigator.webView = this
                        loadDataWithBaseURL(
                            bookBaseUrl,
                            initialHtml,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                update = { webView ->
                    webView.actionModeSessionProvider = { latestSession }
                    readiumNavigator.webView = webView
                    webView.settings.builtInZoomControls = (book.layoutType == PublicationLayoutType.FIXED)
                    webView.settings.setSupportZoom(book.layoutType == PublicationLayoutType.FIXED)
                },
                onRelease = { webView ->
                    readiumNavigator.isPageLoaded = false
                    if (readiumNavigator.webView === webView) {
                        readiumNavigator.webView = null
                    }
                    webView.destroy()
                }
            )
        }

        // =========================================================================
        // FLOATING HIGHLIGHT COLOR PICKER (Shown when pressing highlighted text)
        // =========================================================================
        if (isBookContentLoaded && tappedHighlightAnnotation != null) {
            val activeHighlight = tappedHighlightAnnotation
            val rect = tappedHighlightRect
            val topYDp = remember(rect) {
                val rawTop = rect?.top ?: 180f
                val rawBottom = rect?.bottom ?: 220f
                if (rawTop > 96f) {
                    (rawTop - 68f).coerceAtLeast(52f)
                } else {
                    (rawBottom + 12f).coerceAtMost(560f)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        tappedHighlightId = null
                    }
            ) {
                val pillBg = when (activeReaderTheme) {
                    ReaderTheme.DARK, ReaderTheme.AMOLED -> Color(0xFF27272A)
                    ReaderTheme.SEPIA -> Color(0xFFFDFBF7)
                    ReaderTheme.LIGHT -> Color(0xFFFFFFFF)
                }
                val pillBorder = when (activeReaderTheme) {
                    ReaderTheme.DARK, ReaderTheme.AMOLED -> Color(0xFF3F3F46)
                    ReaderTheme.SEPIA -> Color(0xFFD6C7B2)
                    ReaderTheme.LIGHT -> Color(0xFFD4D4D8)
                }
                val pillText = when (activeReaderTheme) {
                    ReaderTheme.DARK, ReaderTheme.AMOLED -> Color(0xFFFAFAFA)
                    ReaderTheme.SEPIA -> Color(0xFF3E2C23)
                    ReaderTheme.LIGHT -> Color(0xFF18181B)
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = topYDp.dp)
                        .shadow(
                            elevation = 16.dp,
                            shape = RoundedCornerShape(26.dp)
                        )
                        .border(1.dp, pillBorder, RoundedCornerShape(26.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* consume click inside pill */ },
                    shape = RoundedCornerShape(26.dp),
                    color = pillBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HighlightColor.entries.forEach { colorOption ->
                            val isSelected = activeHighlight.highlightColor == colorOption
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorOption.solidArgb))
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) pillText else Color.Black.copy(alpha = 0.2f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        onUpdateAnnotationColor(activeHighlight, colorOption)
                                        tappedHighlightId = null
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF18181B))
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(pillBorder)
                        )

                        Text(
                            text = "Remove",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFEF4444),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onDeleteAnnotation(activeHighlight)
                                    tappedHighlightId = null
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
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
                    .background(readerBgColor)
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
                        color = readerTextColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = book.author,
                        fontSize = 13.sp,
                        color = readerTextColor.copy(alpha = 0.65f),
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
                        color = readerTextColor,
                        trackColor = readerTextColor.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Loading book...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = readerTextColor.copy(alpha = 0.65f)
                    )
                }
            }
        }

        // =========================================================================
        // TOP HEADER: Book title stays permanently centered in place;
        // tapping the page ONLY shows the single Exit (✕) button on the right.
        // =========================================================================
        if (isBookContentLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp, start = 20.dp, end = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = book.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = readerTextColor.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    if (isCurrentPositionBookmarked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        BookmarkRibbonIcon(
                            color = readerTextColor.copy(alpha = 0.85f),
                            filled = true,
                            size = 14.dp
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isActiveReaderHudVisible,
                    enter = if (preferences.enablePageAnimations) fadeIn() else EnterTransition.None,
                    exit = if (preferences.enablePageAnimations) fadeOut() else ExitTransition.None,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDarkReaderSurface) Color(0xFF27272A) else Color(0xFFE4E4E7)
                            )
                            .clickable {
                                flushCurrentLocator()
                                onCloseReader()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        CloseCrossIcon(
                            color = if (isDarkReaderSurface) Color(0xFFD4D4D8) else Color(0xFF27272A),
                            size = 14.dp
                        )
                    }
                }
            }
        }

        // =========================================================================
        // DISTRACTION-FREE FOOTER + IN-BOOK LINK BACK BUTTON (Apple Books-style)
        // =========================================================================
        AnimatedVisibility(
            visible = isBookContentLoaded && !isActiveReaderHudVisible,
            enter = if (preferences.enablePageAnimations) fadeIn() else EnterTransition.None,
            exit = if (preferences.enablePageAnimations) fadeOut() else ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 18.dp)
            ) {
                Text(
                    text = "$derivedCurrentPosition",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = readerTextColor.copy(alpha = 0.55f)
                )
            }
        }

        AnimatedVisibility(
            visible = isBookContentLoaded &&
                book.layoutType == PublicationLayoutType.REFLOWABLE &&
                canNavigateBackInBook &&
                !isActiveReaderHudVisible,
            enter = if (preferences.enablePageAnimations) fadeIn() + scaleIn(initialScale = 0.88f) else EnterTransition.None,
            exit = if (preferences.enablePageAnimations) fadeOut() + scaleOut(targetScale = 0.88f) else ExitTransition.None,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 12.dp)
        ) {
            val backPillBg = if (isDarkReaderSurface) {
                Color(0xFF27272A).copy(alpha = 0.86f)
            } else {
                Color(0xFFE4E4E7).copy(alpha = 0.88f)
            }
            val backPillBorder = if (isDarkReaderSurface) {
                Color.White.copy(alpha = 0.12f)
            } else {
                Color.Black.copy(alpha = 0.10f)
            }
            val backIconColor = if (isDarkReaderSurface) {
                Color(0xFFF4F4F5)
            } else {
                Color(0xFF18181B)
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(backPillBg)
                    .border(1.dp, backPillBorder, CircleShape)
                    .clickable {
                        readiumNavigator.goBackInBook()
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(18.dp)) {
                    val strokeW = 2.dp.toPx()
                    val strokeStyle = Stroke(
                        width = strokeW,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                    // Curved return arrow (↩)
                    val shaftPath = Path().apply {
                        moveTo(size.width * 0.22f, size.height * 0.36f)
                        lineTo(size.width * 0.62f, size.height * 0.36f)
                        cubicTo(
                            size.width * 0.86f, size.height * 0.36f,
                            size.width * 0.86f, size.height * 0.78f,
                            size.width * 0.58f, size.height * 0.78f
                        )
                        lineTo(size.width * 0.36f, size.height * 0.78f)
                    }
                    val headPath = Path().apply {
                        moveTo(size.width * 0.40f, size.height * 0.18f)
                        lineTo(size.width * 0.18f, size.height * 0.36f)
                        lineTo(size.width * 0.40f, size.height * 0.54f)
                    }
                    drawPath(shaftPath, color = backIconColor, style = strokeStyle)
                    drawPath(headPath, color = backIconColor, style = strokeStyle)
                }
            }
        }

        // =========================================================================
        // SWIPEABLE BOTTOM FLOATING DOCKS (PAGE 0: READING DOCK, PAGE 1: AUDIO/TTS DOCK)
        // =========================================================================
        AnimatedVisibility(
            visible = isBookContentLoaded && isActiveReaderHudVisible,
            enter = if (preferences.enablePageAnimations) fadeIn() + slideInVertically { it / 2 } else EnterTransition.None,
            exit = if (preferences.enablePageAnimations) fadeOut() + slideOutVertically { it / 2 } else ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            var cumulativeDragY by remember { mutableFloatStateOf(0f) }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
            ) {
                val barCornerShape = RoundedCornerShape(24.dp)
                val dockSurfaceColor = if (palette.isDark) {
                    Color(0xFF1C1C1E).copy(alpha = 0.92f)
                } else {
                    Color(0xFFF4F4F6).copy(alpha = 0.90f)
                }
                val dockBorderColor = if (palette.isDark) {
                    Color.White.copy(alpha = 0.08f)
                } else {
                    Color.Black.copy(alpha = 0.08f)
                }
                val dockSheenBrush = Brush.verticalGradient(
                    colors = if (palette.isDark) {
                        listOf(
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    }
                )
                val dockButtonFill = if (palette.isDark) {
                    Color.White.copy(alpha = 0.07f)
                } else {
                    Color.Black.copy(alpha = 0.05f)
                }
                val dockButtonBorder = if (palette.isDark) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color.Black.copy(alpha = 0.10f)
                }
                val dockSizeAnimSpec = if (preferences.enablePageAnimations) {
                    spring<IntSize>(dampingRatio = 0.88f, stiffness = 480f)
                } else {
                    snap()
                }
                var isVerticalDockDragActive by remember { mutableStateOf(false) }

                LaunchedEffect(isActiveReaderHudVisible, dockPagerState.currentPage) {
                    isVerticalDockDragActive = false
                }

                HorizontalPager(
                    state = dockPagerState,
                    userScrollEnabled = !isVerticalDockDragActive,
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) { dockPage ->
                    if (dockPage == 0) {
                        Box(
                            contentAlignment = Alignment.BottomCenter,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    try {
                                        detectVerticalDragGestures(
                                            onDragStart = {
                                                isVerticalDockDragActive = true
                                                cumulativeDragY = 0f
                                            },
                                            onDragEnd = {
                                                isVerticalDockDragActive = false
                                                cumulativeDragY = 0f
                                            },
                                            onDragCancel = {
                                                isVerticalDockDragActive = false
                                                cumulativeDragY = 0f
                                            },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                cumulativeDragY += dragAmount
                                                if (cumulativeDragY < -24f && !isBottomBarExpanded) {
                                                    isBottomBarExpanded = true
                                                    cumulativeDragY = 0f
                                                } else if (cumulativeDragY > 24f && isBottomBarExpanded) {
                                                    isBottomBarExpanded = false
                                                    cumulativeDragY = 0f
                                                }
                                            }
                                        )
                                    } finally {
                                        isVerticalDockDragActive = false
                                    }
                                }
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .shadow(
                                        elevation = if (palette.isDark) 24.dp else 10.dp,
                                        shape = barCornerShape,
                                        ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.65f else 0.12f),
                                        spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.75f else 0.16f)
                        )
                        .border(1.dp, dockBorderColor, barCornerShape)
                        .animateContentSize(animationSpec = dockSizeAnimSpec),
                    shape = barCornerShape,
                    color = dockSurfaceColor
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(dockSheenBrush)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Swipe Handle Pill
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

                        // Primary Row: Add Note, Page Progress ("X of Y" with "of" locked to center), Bookmark Toggle, Search
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .height(36.dp)
                                    .clip(CircleShape)
                                    .background(dockButtonFill)
                                    .border(1.dp, dockButtonBorder, CircleShape)
                                    .clickable {
                                        coveredPassageForNote = ""
                                        coveredStartOffsetForNote = -1
                                        coveredEndOffsetForNote = -1
                                        noteInputText = ""
                                        showAddNoteDialog = true
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Add Note",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textPrimary
                                )
                            }

                            val safeTotalPages = derivedTotalPositions.coerceAtLeast(1)
                            val safeCurrentPage = derivedCurrentPosition.coerceIn(1, safeTotalPages)
                            Row(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxWidth()
                                    .padding(horizontal = 88.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$safeCurrentPage",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textMuted,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = " of ",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textMuted,
                                    maxLines = 1
                                )
                                Text(
                                    text = "$safeTotalPages",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textMuted,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                    textAlign = TextAlign.Start,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Bookmark Button: shows pressed state & saves the current page
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(dockButtonFill)
                                        .border(1.dp, dockButtonBorder, CircleShape)
                                        .clickable {
                                            if (book.layoutType == PublicationLayoutType.FIXED) {
                                                val matchingPdfBookmarks = bookAnnotations.filter { ann ->
                                                    ann.type == AnnotationType.BOOKMARK &&
                                                        ann.pageNumber == activePdfPageNumber
                                                }
                                                if (matchingPdfBookmarks.isNotEmpty()) {
                                                    matchingPdfBookmarks.forEach { onDeleteAnnotation(it) }
                                                } else {
                                                    onAddAnnotation(
                                                        AnnotationType.BOOKMARK,
                                                        activePdfPageNumber,
                                                        0,
                                                        0,
                                                        "Page $activePdfPageNumber",
                                                        "",
                                                        HighlightColor.YELLOW.name
                                                    )
                                                }
                                            } else {
                                                val existing = currentPositionBookmark
                                                if (existing != null) {
                                                    onDeleteAnnotation(existing)
                                                } else {
                                                    val wholePageContent = currentVisibleText
                                                        .ifBlank { "Page $derivedCurrentPosition" }
                                                    val endOffset = (currentCharOffset + wholePageContent.length)
                                                        .coerceAtMost(canonicalBookText.length)
                                                    onAddAnnotation(
                                                        AnnotationType.BOOKMARK,
                                                        derivedCurrentPosition,
                                                        currentCharOffset,
                                                        endOffset,
                                                        wholePageContent,
                                                        "",
                                                        HighlightColor.YELLOW.name
                                                    )
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BookmarkRibbonIcon(
                                        color = palette.textPrimary,
                                        filled = isCurrentPositionBookmarked,
                                        size = 18.dp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(dockButtonFill)
                                        .border(1.dp, dockButtonBorder, CircleShape)
                                        .clickable { showSearchInBookDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    SearchVectorIcon(color = palette.textPrimary, size = 18.dp)
                                }
                            }
                        }

                        // Row 2 (Expanded): Flexible "Contents · {progress}%" pill + Fixed-Width Overflow (•••) pill
                        AnimatedVisibility(
                            visible = isBottomBarExpanded,
                            enter = if (preferences.enablePageAnimations) {
                                expandVertically(animationSpec = dockSizeAnimSpec) + fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 180,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            } else {
                                EnterTransition.None
                            },
                            exit = if (preferences.enablePageAnimations) {
                                shrinkVertically(animationSpec = dockSizeAnimSpec) + fadeOut(
                                    animationSpec = tween(
                                        durationMillis = 110,
                                        easing = FastOutLinearInEasing
                                    )
                                )
                            } else {
                                ExitTransition.None
                            }
                        ) {
                            val density = LocalDensity.current
                            val overflowMenuTransitionState = remember { MutableTransitionState(false) }
                            LaunchedEffect(showOverflowMenu) {
                                overflowMenuTransitionState.targetState = showOverflowMenu
                            }
                            val popoverPositionProvider = remember(density) {
                                object : PopupPositionProvider {
                                    override fun calculatePosition(
                                        anchorBounds: IntRect,
                                        windowSize: IntSize,
                                        layoutDirection: LayoutDirection,
                                        popupContentSize: IntSize
                                    ): IntOffset {
                                        val horizontalShadowPadPx = with(density) { 12.dp.roundToPx() }
                                        val edgeMarginPx = with(density) { 8.dp.roundToPx() }
                                        val x = (anchorBounds.right - popupContentSize.width + horizontalShadowPadPx)
                                            .coerceIn(
                                                edgeMarginPx,
                                                (windowSize.width - popupContentSize.width - edgeMarginPx)
                                                    .coerceAtLeast(edgeMarginPx)
                                            )
                                        val y = (anchorBounds.top - popupContentSize.height)
                                            .coerceAtLeast(edgeMarginPx)
                                        return IntOffset(x, y)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Left (Flexible): "Contents · {progress}%" with live progress fill
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(CircleShape)
                                        .background(dockButtonFill)
                                        .border(1.dp, dockButtonBorder, CircleShape)
                                        .clickable {
                                            showTocBottomSheet = true
                                        }
                                ) {
                                    if (animatedProgressFill > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .align(AbsoluteAlignment.CenterLeft)
                                                .fillMaxHeight()
                                                .fillMaxWidth(animatedProgressFill.coerceIn(0f, 1f))
                                                .background(
                                                    if (palette.isDark) Color.White.copy(alpha = 0.14f)
                                                    else Color.Black.copy(alpha = 0.09f)
                                                )
                                        )
                                    }

                                    Text(
                                        text = "Contents · ${displayProgressInt}%",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(horizontal = 14.dp)
                                    )
                                }

                                // Right (Fixed-width): Overflow / More pill button (•••) with anchored floating popover
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .width(60.dp)
                                            .height(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (showOverflowMenu) {
                                                    if (palette.isDark) Color.White.copy(alpha = 0.15f)
                                                    else Color.Black.copy(alpha = 0.10f)
                                                } else {
                                                    dockButtonFill
                                                }
                                            )
                                            .border(1.dp, dockButtonBorder, CircleShape)
                                            .clickable { showOverflowMenu = !showOverflowMenu },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        MoreHorizDotsIcon(
                                            color = palette.textPrimary,
                                            size = 18.dp
                                        )
                                    }

                                    if (overflowMenuTransitionState.currentState || overflowMenuTransitionState.targetState) {
                                        Popup(
                                            popupPositionProvider = popoverPositionProvider,
                                            onDismissRequest = { showOverflowMenu = false },
                                            properties = PopupProperties(focusable = showOverflowMenu)
                                        ) {
                                            val slideOffsetPx = with(density) { 12.dp.roundToPx() }
                                            val popoverTransformOrigin = TransformOrigin(0.85f, 1f)

                                            androidx.compose.animation.AnimatedVisibility(
                                                visibleState = overflowMenuTransitionState,
                                                enter = if (preferences.enablePageAnimations) {
                                                    fadeIn(
                                                        animationSpec = tween(
                                                            durationMillis = 180,
                                                            easing = FastOutSlowInEasing
                                                        )
                                                    ) + scaleIn(
                                                        initialScale = 0.92f,
                                                        transformOrigin = popoverTransformOrigin,
                                                        animationSpec = tween(
                                                            durationMillis = 180,
                                                            easing = FastOutSlowInEasing
                                                        )
                                                    ) + slideInVertically(
                                                        initialOffsetY = { slideOffsetPx },
                                                        animationSpec = tween(
                                                            durationMillis = 180,
                                                            easing = FastOutSlowInEasing
                                                        )
                                                    )
                                                } else {
                                                    EnterTransition.None
                                                },
                                                exit = if (preferences.enablePageAnimations) {
                                                    fadeOut(
                                                        animationSpec = tween(
                                                            durationMillis = 140,
                                                            easing = FastOutLinearInEasing
                                                        )
                                                    ) + scaleOut(
                                                        targetScale = 0.92f,
                                                        transformOrigin = popoverTransformOrigin,
                                                        animationSpec = tween(
                                                            durationMillis = 140,
                                                            easing = FastOutLinearInEasing
                                                        )
                                                    ) + slideOutVertically(
                                                        targetOffsetY = { slideOffsetPx },
                                                        animationSpec = tween(
                                                            durationMillis = 140,
                                                            easing = FastOutLinearInEasing
                                                        )
                                                    )
                                                } else {
                                                    ExitTransition.None
                                                }
                                            ) {
                                                val popoverShape = RoundedCornerShape(20.dp)
                                                Box(
                                                    modifier = Modifier.padding(
                                                        start = 12.dp,
                                                        end = 12.dp,
                                                        top = 8.dp,
                                                        bottom = 12.dp
                                                    )
                                                ) {
                                                    Surface(
                                                        modifier = Modifier
                                                            .width(228.dp)
                                                            .shadow(
                                                                elevation = if (palette.isDark) 24.dp else 10.dp,
                                                                shape = popoverShape,
                                                                ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.65f else 0.12f),
                                                                spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.75f else 0.16f)
                                                            )
                                                            .border(1.dp, dockBorderColor, popoverShape),
                                                        shape = popoverShape,
                                                        color = dockSurfaceColor
                                                    ) {
                                                        Column(
                                                            modifier = Modifier
                                                                .background(dockSheenBrush)
                                                                .padding(vertical = 6.dp)
                                                        ) {
                                                            // Menu Item 1: Themes & Settings
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable {
                                                                        showOverflowMenu = false
                                                                        showThemesBottomSheet = true
                                                                    }
                                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier.size(24.dp),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    Text(
                                                                        text = "Aa",
                                                                        fontFamily = FontFamily.Serif,
                                                                        fontWeight = FontWeight.SemiBold,
                                                                        fontSize = 15.sp,
                                                                        lineHeight = 15.sp,
                                                                        color = palette.textPrimary,
                                                                        style = TextStyle(
                                                                            platformStyle = PlatformTextStyle(
                                                                                includeFontPadding = false
                                                                            )
                                                                        ),
                                                                        modifier = Modifier.offset(y = (-1).dp)
                                                                    )
                                                                }

                                                                Text(
                                                                    text = "Theme",
                                                                    fontSize = 14.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = palette.textPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }

                                                            HorizontalDivider(
                                                                color = palette.borderSubtle,
                                                                thickness = 1.dp,
                                                                modifier = Modifier.padding(horizontal = 12.dp)
                                                            )

                                                            // Menu Item 2: Notebook + plain trailing count
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable {
                                                                        showOverflowMenu = false
                                                                        showBookmarksAndHighlightsDialog = true
                                                                    }
                                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier.size(24.dp),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    NotePencilIcon(
                                                                        color = palette.textPrimary,
                                                                        size = 18.dp
                                                                    )
                                                                }

                                                                Text(
                                                                    text = "Notebook",
                                                                    fontSize = 14.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = palette.textPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f)
                                                                )

                                                                Text(
                                                                    text = "${allBookAnnotationsSorted.size}",
                                                                    fontSize = 13.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = palette.textMuted,
                                                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                                                    maxLines = 1
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
                        }
                    }
                }
                        }
                    } else {
                        // =========================================================
                        // PAGE 1: COLLAPSIBLE AUDIO / TTS PLAYBACK DOCK
                        // =========================================================
                        ReaderAudioTtsDock(
                            audioState = audioState,
                            audioDockMode = audioDockMode,
                            onChangeAudioDockMode = { audioDockMode = it },
                            isAudioDockExpanded = isAudioDockExpanded,
                            onSetAudioDockExpanded = { isAudioDockExpanded = it },
                            onVerticalDragActiveChanged = { isVerticalDockDragActive = it },
                            enablePageAnimations = preferences.enablePageAnimations,
                            dockSizeAnimSpec = dockSizeAnimSpec,
                            barCornerShape = barCornerShape,
                            dockSurfaceColor = dockSurfaceColor,
                            dockBorderColor = dockBorderColor,
                            dockButtonFill = dockButtonFill,
                            dockButtonBorder = dockButtonBorder,
                            dockSheenBrush = dockSheenBrush,
                            currentPdfPageIndex = currentPdfPageIndex,
                            currentCharOffset = currentCharOffset,
                            derivedCurrentPosition = derivedCurrentPosition,
                            currentVisibleText = currentVisibleText,
                            onClipCaptured = { clip ->
                                coveredPassageForNote = "[Audio ${clip.timestampLabel} · ${clip.trackTitle}] ${clip.quotedText}".trim()
                                coveredStartOffsetForNote = clip.bookCharOffset
                                coveredEndOffsetForNote = clip.bookCharOffset
                                noteInputText = ""
                                showAddNoteDialog = true
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Swipe Page Indicator Dots (Page 0 = Reading Dock, Page 1 = Audio/TTS Dock)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(2) { pageIndex ->
                        val isSelectedPage = dockPagerState.currentPage == pageIndex
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelectedPage) palette.textPrimary
                                    else palette.textMuted.copy(alpha = 0.45f)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    coroutineScope.launch {
                                        if (preferences.enablePageAnimations) {
                                            dockPagerState.animateScrollToPage(pageIndex)
                                        } else {
                                            dockPagerState.scrollToPage(pageIndex)
                                        }
                                    }
                                }
                        )
                    }
                }
            }
        }

        // =========================================================================
        // MODAL BOTTOM SHEET: "Table of Contents" (PDF outline or Readium EPUB TOC)
        // =========================================================================
        if (showTocBottomSheet) {
            val tocSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val isFixedLayout = book.layoutType == PublicationLayoutType.FIXED
            val activeTocEntryId = remember(
                isFixedLayout,
                pdfTocEntries,
                reflowableTocEntries,
                currentCharOffset,
                currentPdfPageIndex
            ) {
                if (isFixedLayout) {
                    if (pdfTocEntries.isEmpty()) {
                        null
                    } else {
                        pdfTocEntries.lastOrNull { entry ->
                            entry.pageIndex <= currentPdfPageIndex
                        }?.id ?: pdfTocEntries.first().id
                    }
                } else {
                    if (reflowableTocEntries.isEmpty()) {
                        null
                    } else {
                        reflowableTocEntries.lastOrNull { entry ->
                            val offset = (entry.locator?.locations?.otherLocations?.get("charOffset") as? Number)?.toInt()
                            offset != null && offset <= currentCharOffset + 12
                        }?.id ?: reflowableTocEntries.first().id
                    }
                }
            }

            ModalBottomSheet(
                onDismissRequest = { showTocBottomSheet = false },
                sheetState = tocSheetState,
                containerColor = palette.menuSurface,
                contentColor = palette.textPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Contents",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "${book.title} · ${displayProgressInt}% read",
                            fontSize = 12.sp,
                            color = palette.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val hasTocEntries = if (isFixedLayout) pdfTocEntries.isNotEmpty() else reflowableTocEntries.isNotEmpty()
                    val isTocLoading = if (isFixedLayout) !isPdfDocumentReady else reflowableTocState.isLoading
                    if (isTocLoading && !hasTocEntries) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = palette.textPrimary,
                                trackColor = palette.borderSubtle
                            )
                            Text(
                                text = "Loading table of contents...",
                                fontSize = 13.5.sp,
                                color = palette.textMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (!hasTocEntries) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No table of contents available for this book.",
                                fontSize = 13.5.sp,
                                color = palette.textMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (isFixedLayout) {
                        val safeTotalPages = derivedTotalPositions.coerceAtLeast(1)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = pdfTocEntries,
                                key = { it.id },
                                contentType = { "pdf_toc_entry" }
                            ) { entry ->
                                val isCurrentChapter = entry.id == activeTocEntryId
                                val indentDp = (entry.depth.coerceIn(0, 4) * 14).dp
                                val entryPercent = (((entry.pageIndex + 1).toFloat() / safeTotalPages.toFloat()) * 100f)
                                    .roundToInt()
                                    .coerceIn(0, 100)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = indentDp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isCurrentChapter) {
                                                if (palette.isDark) Color.White.copy(alpha = 0.08f)
                                                else Color.Black.copy(alpha = 0.05f)
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isCurrentChapter) palette.borderSubtle else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            audioState.onUserManuallyNavigatedBook(fromExplicitJump = true)
                                            pdfJumpToPage?.invoke(entry.pageIndex + 1)
                                            showTocBottomSheet = false
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = entry.title,
                                        fontSize = if (entry.depth == 0) 14.sp else 13.sp,
                                        fontWeight = if (isCurrentChapter) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCurrentChapter) palette.textPrimary else palette.textSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "p.${entry.pageIndex + 1} · ${entryPercent}%",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textMuted
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = reflowableTocEntries,
                                key = { it.id },
                                contentType = { "toc_chapter_entry" }
                            ) { entry ->
                                val isCurrentChapter = entry.id == activeTocEntryId
                                val indentDp = (entry.depth.coerceIn(0, 4) * 14).dp

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = indentDp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isCurrentChapter) {
                                                if (palette.isDark) Color.White.copy(alpha = 0.08f)
                                                else Color.Black.copy(alpha = 0.05f)
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isCurrentChapter) palette.borderSubtle else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            audioState.onUserManuallyNavigatedBook(fromExplicitJump = true)
                                            val targetLoc = entry.locator
                                            if (targetLoc != null) {
                                                val offset = (targetLoc.locations.otherLocations["charOffset"] as? Number)?.toInt()
                                                if (offset != null) {
                                                    currentCharOffset = offset
                                                }
                                                readiumNavigator.go(targetLoc, animated = false)
                                            } else {
                                                readiumNavigator.go(entry.link, animated = false)
                                            }
                                            showTocBottomSheet = false
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = entry.title,
                                        fontSize = if (entry.depth == 0) 14.sp else 13.sp,
                                        fontWeight = if (isCurrentChapter) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCurrentChapter) palette.textPrimary else palette.textSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )

                                    val entryPercent = entry.locator?.locations?.totalProgression?.let {
                                        (it * 100.0).roundToInt().coerceIn(0, 100)
                                    }
                                    if (entryPercent != null) {
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "${entryPercent}%",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.textMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // MODAL BOTTOM SHEET: "Themes & Typography" (EPUB) / "Reading Theme" (PDF)
        // =========================================================================
        if (showThemesBottomSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val isPdfSheet = book.layoutType == PublicationLayoutType.FIXED
            ModalBottomSheet(
                onDismissRequest = { showThemesBottomSheet = false },
                sheetState = sheetState,
                containerColor = palette.menuSurface,
                contentColor = palette.textPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(
                        text = if (isPdfSheet) "Reading Theme" else "Themes & Typography",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )

                    // 1. 4 Color Themes (Light, Sepia, Dark, Black / AMOLED)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "COLOR THEME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            color = palette.textMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReaderTheme.entries.forEach { themeOption ->
                                val isSelected = themeOption == activeReaderTheme
                                val bgCol = Color(themeOption.backgroundArgb)
                                val txtCol = Color(themeOption.textArgb)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(bgCol)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 1.dp,
                                            color = if (isSelected) Color(0xFFFACC15)
                                            else Color.Gray.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { onSelectReaderTheme(themeOption) }
                                        .padding(vertical = 12.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Aa",
                                        fontFamily = FontFamily.Serif,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = txtCol
                                    )
                                    Text(
                                        text = themeOption.label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = txtCol,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (book.layoutType == PublicationLayoutType.REFLOWABLE) {
                        // Navigation Mode Selector (Horizontal Swipe vs Scrolling)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "NAVIGATION MODE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                                color = palette.textMuted
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ReaderNavigationMode.entries.forEach { navMode ->
                                    val isSelected = preferences.navigationMode == navMode
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) palette.dockTabActiveBg
                                                else palette.surfaceColor
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) palette.dockTabActiveBg
                                                else palette.borderSubtle,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable { onSelectNavigationMode(navMode) }
                                            .padding(vertical = 11.dp, horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = navMode.label,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) palette.dockTabActiveText
                                            else palette.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // 2. 8-Step Font Size Multiplier (0.8x .. 2.0x)
                        val activeStep = preferences.fontSizeStep
                        val steps = ReaderFontSizeStep.entries
                        val activeStepIdx = steps.indexOf(activeStep).coerceAtLeast(0)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TEXT SIZE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.8.sp,
                                    color = palette.textMuted
                                )
                                Text(
                                    text = "${activeStep.friendlyName} • ${activeStep.multiplier}× (${activeStep.computedSp}sp)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textSecondary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.surfaceColor)
                                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
                                        .clickable(enabled = activeStepIdx > 0) {
                                            onSelectFontSizeMultiplier(steps[activeStepIdx - 1].multiplier)
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "A−",
                                        fontFamily = FontFamily.Serif,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (activeStepIdx > 0) palette.textPrimary else palette.textMuted
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.surfaceColor)
                                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
                                        .clickable(enabled = activeStepIdx < steps.lastIndex) {
                                            onSelectFontSizeMultiplier(steps[activeStepIdx + 1].multiplier)
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "A+",
                                        fontFamily = FontFamily.Serif,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (activeStepIdx < steps.lastIndex) palette.textPrimary else palette.textMuted
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                steps.forEach { step ->
                                    val selected = step == activeStep
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (selected) palette.dockTabActiveBg
                                                else palette.surfaceColor
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (selected) palette.dockTabActiveBg
                                                else palette.borderSubtle,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable { onSelectFontSizeMultiplier(step.multiplier) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = step.shortLabel,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selected) palette.dockTabActiveText
                                                else palette.textPrimary
                                            )
                                            Text(
                                                text = "${step.multiplier}×",
                                                fontSize = 9.sp,
                                                color = if (selected) palette.dockTabActiveText.copy(alpha = 0.8f)
                                                else palette.textMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Line Height & Margins
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "LINE HEIGHT",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textMuted
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(1.3f to "Tight", 1.5f to "Normal", 1.8f to "Relaxed").forEach { (lh, label) ->
                                        val sel = kotlin.math.abs(preferences.lineHeightMultiplier - lh) < 0.08f
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) palette.dockTabActiveBg else palette.surfaceColor)
                                                .border(1.dp, palette.borderSubtle, RoundedCornerShape(8.dp))
                                                .clickable { onSelectLineHeightMultiplier(lh) }
                                                .padding(vertical = 7.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (sel) palette.dockTabActiveText else palette.textPrimary
                                            )
                                        }
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "MARGINS",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textMuted
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(16 to "Narrow", 24 to "Normal", 36 to "Wide").forEach { (mDp, label) ->
                                        val sel = preferences.pageMarginDp == mDp
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) palette.dockTabActiveBg else palette.surfaceColor)
                                                .border(1.dp, palette.borderSubtle, RoundedCornerShape(8.dp))
                                                .clickable { onSelectPageMarginDp(mDp) }
                                                .padding(vertical = 7.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (sel) palette.dockTabActiveText else palette.textPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Alignment & Publisher Styles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ReaderTextAlign.entries.forEach { alignOpt ->
                                    val sel = preferences.textAlignment == alignOpt && !preferences.publisherStyles
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) palette.dockTabActiveBg else palette.surfaceColor)
                                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(8.dp))
                                            .clickable {
                                                onSelectPublisherStyles(false)
                                                onSelectTextAlignment(alignOpt)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = alignOpt.label,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (sel) palette.dockTabActiveText else palette.textPrimary
                                        )
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Publisher Styles",
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                                Switch(
                                    checked = preferences.publisherStyles,
                                    onCheckedChange = { onSelectPublisherStyles(it) }
                                )
                            }
                        }
                    } else {
                        // PDF-only options: Navigation Mode (Swipe / Scroll) & Manual RTL Swipe Direction
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "NAVIGATION MODE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                                color = palette.textMuted
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ReaderNavigationMode.entries.forEach { navMode ->
                                    val isSelected = preferences.navigationMode == navMode
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) palette.dockTabActiveBg
                                                else palette.surfaceColor
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) palette.dockTabActiveBg
                                                else palette.borderSubtle,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable { onSelectNavigationMode(navMode) }
                                            .padding(vertical = 11.dp, horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = navMode.label,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) palette.dockTabActiveText
                                            else palette.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SWIPE DIRECTION",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.8.sp,
                                    color = palette.textMuted
                                )
                                Text(
                                    text = if (hasManualPdfRtlOverride) "Manual" else "Auto-detected",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textMuted
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    false to "Left to Right (LTR)",
                                    true to "Right to Left (RTL)"
                                ).forEach { (rtlOption, label) ->
                                    val isSelected = pdfIsRtl == rtlOption
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) palette.dockTabActiveBg
                                                else palette.surfaceColor
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) palette.dockTabActiveBg
                                                else palette.borderSubtle,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                hasManualPdfRtlOverride = true
                                                pdfIsRtl = rtlOption
                                            }
                                            .padding(vertical = 11.dp, horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) palette.dockTabActiveText
                                            else palette.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // DIALOG: Bookmarks, Highlights & Notes (Ordered by Position, Tap to Jump)
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
                            text = "Notebook",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${book.title} • Tap any item to jump to its location",
                            fontSize = 12.sp,
                            color = palette.textMuted
                        )
                    }
                },
                text = {
                    if (allBookAnnotationsSorted.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No bookmarks, highlights, or notes yet in this book.\nPress the bookmark icon, tap Add Note, or cover words on the page.",
                                fontSize = 13.5.sp,
                                color = palette.textMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = allBookAnnotationsSorted,
                                key = { it.id },
                                contentType = { "reader_annotation_item" }
                            ) { item ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.surfaceColor)
                                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
                                        .combinedClickable(
                                            onClick = {
                                                jumpToLocator(item.charOffset, item.pageNumber)
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
                                                when (item.type) {
                                                    AnnotationType.BOOKMARK -> {
                                                        BookmarkRibbonIcon(
                                                            color = palette.textPrimary,
                                                            filled = true,
                                                            size = 14.dp
                                                        )
                                                    }
                                                    AnnotationType.HIGHLIGHT -> {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(10.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(item.highlightColor.solidArgb))
                                                        )
                                                    }
                                                    AnnotationType.NOTE -> {
                                                        NotePencilIcon(
                                                            color = palette.textPrimary,
                                                            size = 14.dp
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = item.type.name,
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

                                        if (item.type == AnnotationType.NOTE && item.quotedText.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(palette.appBackground.copy(alpha = 0.6f))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "\"${item.quotedText}\"",
                                                    fontSize = 11.5.sp,
                                                    fontStyle = FontStyle.Italic,
                                                    color = palette.textMuted,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        val displayAnnotationText = if (
                                            book.layoutType == PublicationLayoutType.FIXED &&
                                            item.type == AnnotationType.BOOKMARK
                                        ) {
                                            "Page ${item.pageNumber}"
                                        } else {
                                            item.text
                                        }

                                        Text(
                                            text = displayAnnotationText,
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
        // DIALOG: Add Note (Note-only field; no Highlight toggle inside dialog)
        // =========================================================================
        if (showAddNoteDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddNoteDialog = false
                    coveredPassageForNote = ""
                    coveredStartOffsetForNote = -1
                    coveredEndOffsetForNote = -1
                },
                containerColor = palette.menuSurface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textPrimary,
                title = {
                    Column {
                        Text(
                            text = "Add Note",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${book.title} • Page $derivedCurrentPosition",
                            fontSize = 12.sp,
                            color = palette.textMuted
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (coveredPassageForNote.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surfaceColor)
                                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = "ASSOCIATED COVERED TEXT",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.7.sp,
                                        color = palette.textMuted
                                    )
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
                        }

                        OutlinedTextField(
                            value = noteInputText,
                            onValueChange = { noteInputText = it },
                            label = { Text("Type your note...") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val cleaned = noteInputText.trim()
                            if (cleaned.isNotEmpty()) {
                                val isFixedPdf = book.layoutType == PublicationLayoutType.FIXED
                                val targetPageNumber = if (isFixedPdf) {
                                    activePdfPageNumber
                                } else {
                                    derivedCurrentPosition
                                }
                                val (exactStart, exactEnd) = if (isFixedPdf) {
                                    if (coveredStartOffsetForNote >= 0 && coveredEndOffsetForNote >= coveredStartOffsetForNote) {
                                        coveredStartOffsetForNote to coveredEndOffsetForNote
                                    } else {
                                        0 to 0
                                    }
                                } else if (coveredPassageForNote.isNotBlank()) {
                                    resolveSelectionRange(
                                        coveredPassageForNote.trim(),
                                        coveredStartOffsetForNote,
                                        coveredEndOffsetForNote
                                    )
                                } else {
                                    currentCharOffset to currentCharOffset
                                }
                                onAddAnnotation(
                                    AnnotationType.NOTE,
                                    targetPageNumber,
                                    exactStart,
                                    exactEnd,
                                    cleaned,
                                    coveredPassageForNote.trim(),
                                    HighlightColor.YELLOW.name
                                )
                                showAddNoteDialog = false
                                coveredPassageForNote = ""
                                coveredStartOffsetForNote = -1
                                coveredEndOffsetForNote = -1
                                noteInputText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.textPrimary,
                            contentColor = palette.appBackground
                        )
                    ) {
                        Text("Save Note", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAddNoteDialog = false
                            coveredPassageForNote = ""
                            coveredStartOffsetForNote = -1
                            coveredEndOffsetForNote = -1
                        }
                    ) {
                        Text("Cancel", color = palette.textMuted)
                    }
                }
            )
        }

        // =========================================================================
        // DIALOG: Search inside the current Book (jumps to locator offset)
        // =========================================================================
        if (showSearchInBookDialog) {
            var query by rememberSaveable { mutableStateOf("") }
            val paragraphsWithOffsets = remember(canonicalBookText) {
                val result = mutableListOf<Pair<Int, String>>()
                var idx = 0
                val parts = canonicalBookText.split("\n\n")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.isNotEmpty()) {
                        val found = canonicalBookText.indexOf(trimmed, idx).coerceAtLeast(idx)
                        result.add(found to trimmed)
                        idx = found + trimmed.length
                    }
                }
                result
            }

            val matchingPassages = remember(query, paragraphsWithOffsets) {
                val q = query.trim()
                if (q.isEmpty()) {
                    paragraphsWithOffsets.take(40)
                } else {
                    paragraphsWithOffsets.filter { (_, text) ->
                        text.contains(q, ignoreCase = true)
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
                            label = { Text("Search text in book...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = matchingPassages,
                                key = { it.first },
                                contentType = { "search_match" }
                            ) { (charOffset, snippet) ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.surfaceColor)
                                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                        .clickable {
                                            jumpToLocator(charOffset, 1)
                                            showSearchInBookDialog = false
                                            isActiveReaderHudVisible = false
                                        }
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = snippet,
                                        fontSize = 12.5.sp,
                                        color = palette.textPrimary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
        // FLOATING "RETURN TO AUDIO" PILL + TTS & AUDIOBOOK BOTTOM SHEETS
        // =========================================================================
        ReaderAudioOverlaysAndSheets(
            book = book,
            audioState = audioState,
            audioDockMode = audioDockMode,
            currentPdfPageIndex = currentPdfPageIndex,
            currentCharOffset = currentCharOffset,
            derivedTotalPositions = derivedTotalPositions,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 62.dp)
        )

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
                    val previewText = if (
                        book.layoutType == PublicationLayoutType.FIXED &&
                        targetAnn.type == AnnotationType.BOOKMARK
                    ) {
                        "Page ${targetAnn.pageNumber}"
                    } else {
                        targetAnn.text
                    }
                    Text(
                        text = "Delete this $label from Page ${targetAnn.pageNumber}?\n\n\"$previewText\"",
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

@Composable
private fun SideEffectOrUpdateNavigatorCallbacks(
    navigator: ReadiumWebViewNavigator,
    onInitialPositionRestored: () -> Unit,
    onLocatorUpdated: (charOffset: Int, currentPosition: Int, totalPositions: Int, visibleText: String) -> Unit,
    onSingleTapToggleHud: () -> Unit,
    onUserManualNavigation: () -> Unit = {},
    onWordDoubleTap: (charOffset: Int, wordText: String) -> Unit = { _, _ -> },
    onLinkHistoryChanged: (canGoBack: Boolean) -> Unit = {},
    onExternalLinkClicked: (url: String) -> Unit = {}
) {
    val latestOnInitialRestored by rememberUpdatedState(onInitialPositionRestored)
    val latestOnLocatorUpdated by rememberUpdatedState(onLocatorUpdated)
    val latestOnSingleTap by rememberUpdatedState(onSingleTapToggleHud)
    val latestOnManualNav by rememberUpdatedState(onUserManualNavigation)
    val latestOnWordDoubleTap by rememberUpdatedState(onWordDoubleTap)
    val latestOnLinkHistoryChanged by rememberUpdatedState(onLinkHistoryChanged)
    val latestOnExternalLinkClicked by rememberUpdatedState(onExternalLinkClicked)

    DisposableEffect(navigator) {
        navigator.onInitialPositionRestored = { latestOnInitialRestored() }
        navigator.onLocatorUpdated = { offset, pos, total, text ->
            latestOnLocatorUpdated(offset, pos, total, text)
        }
        navigator.onSingleTapToggleHud = { latestOnSingleTap() }
        navigator.onUserManualNavigation = { latestOnManualNav() }
        navigator.onWordDoubleTap = { offset, word ->
            latestOnWordDoubleTap(offset, word)
        }
        navigator.onLinkHistoryChanged = { canGoBack ->
            latestOnLinkHistoryChanged(canGoBack)
        }
        navigator.onExternalLinkClicked = { url ->
            latestOnExternalLinkClicked(url)
        }
        onDispose {
            navigator.onInitialPositionRestored = null
            navigator.onLocatorUpdated = null
            navigator.onSingleTapToggleHud = null
            navigator.onUserManualNavigation = null
            navigator.onWordDoubleTap = null
            navigator.onLinkHistoryChanged = null
            navigator.onExternalLinkClicked = null
        }
    }
}
