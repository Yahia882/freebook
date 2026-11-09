package com.example.freebook.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
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
import com.example.freebook.ui.components.NotePencilIcon
import com.example.freebook.ui.components.SearchVectorIcon
import com.example.freebook.ui.reader.ReadiumActionModeSession
import com.example.freebook.ui.reader.ReadiumSelectionWebView
import com.example.freebook.ui.reader.ReadiumWebViewNavigator
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.shared.ExperimentalReadiumApi
import java.util.Locale

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
    onLocatorChanged: (charOffset: Int, totalChars: Int, derivedPosition: Int, derivedTotalPositions: Int) -> Unit,
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
    var showThemesBottomSheet by rememberSaveable { mutableStateOf(false) }
    var activeHighlightColor by rememberSaveable { mutableStateOf(HighlightColor.YELLOW) }

    LaunchedEffect(isActiveReaderHudVisible) {
        if (!isActiveReaderHudVisible) {
            isBottomBarExpanded = false
        }
    }

    val canonicalBookText by produceState(initialValue = "", key1 = book.id, key2 = book.textContentFilePath) {
        value = withContext(Dispatchers.Default) {
            normalizeCanonicalText(loadBookText(book))
        }
    }

    var isWebViewDocumentReady by remember(book.id) { mutableStateOf(false) }
    val isBookContentLoaded = canonicalBookText.isNotEmpty() && isWebViewDocumentReady

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

    LaunchedEffect(book.id, canonicalBookText.length) {
        if (canonicalBookText.isNotEmpty() && !isInitialPositionRestored && currentCharOffset == 0) {
            val savedOffset = book.resolveSavedCharOffset(canonicalBookText.length)
            if (savedOffset > 0) {
                currentCharOffset = savedOffset
            }
        }
    }

    val readingPercent by remember(
        currentCharOffset,
        canonicalBookText.length,
        derivedCurrentPosition,
        derivedTotalPositions
    ) {
        derivedStateOf {
            if (canonicalBookText.isEmpty()) {
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

    val latestCharOffset by rememberUpdatedState(currentCharOffset)
    val latestTotalChars by rememberUpdatedState(canonicalBookText.length)
    val latestDerivedPos by rememberUpdatedState(derivedCurrentPosition)
    val latestDerivedTotalPos by rememberUpdatedState(derivedTotalPositions)
    val latestIsRestored by rememberUpdatedState(isInitialPositionRestored)
    val latestOnLocatorChanged by rememberUpdatedState(onLocatorChanged)
    val latestOnAddAnnotation by rememberUpdatedState(onAddAnnotation)
    val latestCanonicalText by rememberUpdatedState(canonicalBookText)

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
                    totalPos
                )
            }
        },
        onSingleTapToggleHud = {
            if (tappedHighlightId != null) {
                tappedHighlightId = null
            } else {
                isActiveReaderHudVisible = !isActiveReaderHudVisible
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
        if (latestIsRestored && latestTotalChars > 0) {
            latestOnLocatorChanged(
                latestCharOffset,
                latestTotalChars,
                (latestDerivedPos - 1).coerceAtLeast(0),
                latestDerivedTotalPos
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

    val currentPositionBookmark = remember(bookAnnotations, derivedCurrentPosition, currentCharOffset) {
        bookAnnotations.firstOrNull { ann ->
            ann.type == AnnotationType.BOOKMARK &&
                (ann.pageNumber == derivedCurrentPosition ||
                    (ann.charOffset > 0 && kotlin.math.abs(ann.charOffset - currentCharOffset) < 280))
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
        // READIUM WEBVIEW HOST (Managed by ReadiumWebViewNavigator : DecorableNavigator)
        // =========================================================================
        if (canonicalBookText.isNotEmpty()) {
            val initialHtml = remember(book.id, canonicalBookText) {
                val initialOffset = book.resolveSavedCharOffset(canonicalBookText.length)
                ReadiumWebViewNavigator.buildReadiumDocumentHtml(
                    book = book,
                    canonicalText = canonicalBookText,
                    preferences = preferences,
                    initialCharOffset = initialOffset
                )
            }

            val latestSession by rememberUpdatedState(activeSession)

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
                        settings.builtInZoomControls = (book.layoutType == PublicationLayoutType.FIXED)
                        settings.displayZoomControls = false
                        settings.setSupportZoom(book.layoutType == PublicationLayoutType.FIXED)

                        addJavascriptInterface(readiumNavigator.JsBridge(), "ReadiumBridge")
                        webViewClient = object : WebViewClient() {
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
                            "https://readium.local/book/${book.id}/",
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
        // DISTRACTION-FREE FOOTER
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

        // =========================================================================
        // EXTENDABLE BOTTOM FLOATING BAR (SWIPE UP TO EXTEND VERTICALLY)
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
                    .padding(bottom = 20.dp)
                    .pointerInput(isBottomBarExpanded) {
                        detectVerticalDragGestures(
                            onDragStart = { cumulativeDragY = 0f },
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

                        // Primary Row: Add Note, Percentage, Bookmark Toggle, Search
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
                                        coveredPassageForNote = ""
                                        coveredStartOffsetForNote = -1
                                        coveredEndOffsetForNote = -1
                                        noteInputText = ""
                                        showAddNoteDialog = true
                                    }
                                    .padding(horizontal = 13.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                NotePencilIcon(color = palette.textPrimary, size = 16.dp)
                                Text(
                                    text = "Add Note",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textPrimary
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
                                // Bookmark Button: shows pressed state & saves the whole visible page
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isCurrentPositionBookmarked) palette.dockTabActiveBg
                                            else Color.Transparent
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isCurrentPositionBookmarked) palette.dockTabActiveBg
                                            else palette.borderSubtle,
                                            shape = CircleShape
                                        )
                                        .clickable {
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
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BookmarkRibbonIcon(
                                        color = if (isCurrentPositionBookmarked) palette.dockTabActiveText
                                        else palette.textPrimary,
                                        filled = isCurrentPositionBookmarked,
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
                                    SearchVectorIcon(color = palette.textPrimary, size = 18.dp)
                                }
                            }
                        }

                        // Extended Options: "Themes" Button + "Bookmarks & Highlights" Button
                        AnimatedVisibility(
                            visible = isBottomBarExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp, bottom = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                HorizontalDivider(color = palette.borderSubtle, thickness = 1.dp)

                                // 1. "Themes" Button -> Opens Bottom Sheet with Navigation Mode, Text Size steps & 4 Color Themes
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(palette.surfaceColor)
                                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                                        .clickable {
                                            showThemesBottomSheet = true
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(readerBgColor)
                                                .border(1.dp, palette.textMuted, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "A",
                                                fontFamily = FontFamily.Serif,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = readerTextColor
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "Themes",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.textPrimary
                                            )
                                            Text(
                                                text = "${activeReaderTheme.label} • ${preferences.fontSizeStep.friendlyName} (${preferences.fontSizeStep.multiplier}×)",
                                                fontSize = 11.5.sp,
                                                color = palette.textMuted
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Customize",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textSecondary
                                    )
                                }

                                // 2. "Bookmarks & Highlights" Button (shows Bookmarks, Highlights, and Notes)
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
                                            filled = allBookAnnotationsSorted.isNotEmpty(),
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
                                            text = "${allBookAnnotationsSorted.size}",
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

        // =========================================================================
        // MODAL BOTTOM SHEET: "Themes & Typography"
        // =========================================================================
        if (showThemesBottomSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                        text = "Themes & Typography",
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
                        // Fixed-Layout Fit Modes
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "FIXED-LAYOUT FIT MODE (PINCH TO ZOOM ENABLED)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                                color = palette.textMuted
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FixedLayoutFitMode.entries.forEach { mode ->
                                    val sel = preferences.fixedLayoutFitMode == mode
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (sel) palette.dockTabActiveBg else palette.surfaceColor)
                                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                            .clickable { onSelectFixedLayoutFitMode(mode) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = mode.label,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (sel) palette.dockTabActiveText else palette.textPrimary
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
                            text = "Bookmarks, Highlights & Notes",
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
                                val (exactStart, exactEnd) = if (coveredPassageForNote.isNotBlank()) {
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
                                    derivedCurrentPosition,
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

@Composable
private fun SideEffectOrUpdateNavigatorCallbacks(
    navigator: ReadiumWebViewNavigator,
    onInitialPositionRestored: () -> Unit,
    onLocatorUpdated: (charOffset: Int, currentPosition: Int, totalPositions: Int, visibleText: String) -> Unit,
    onSingleTapToggleHud: () -> Unit
) {
    val latestOnInitialRestored by rememberUpdatedState(onInitialPositionRestored)
    val latestOnLocatorUpdated by rememberUpdatedState(onLocatorUpdated)
    val latestOnSingleTap by rememberUpdatedState(onSingleTapToggleHud)

    DisposableEffect(navigator) {
        navigator.onInitialPositionRestored = { latestOnInitialRestored() }
        navigator.onLocatorUpdated = { offset, pos, total, text ->
            latestOnLocatorUpdated(offset, pos, total, text)
        }
        navigator.onSingleTapToggleHud = { latestOnSingleTap() }
        onDispose {
            navigator.onInitialPositionRestored = null
            navigator.onLocatorUpdated = null
            navigator.onSingleTapToggleHud = null
        }
    }
}
