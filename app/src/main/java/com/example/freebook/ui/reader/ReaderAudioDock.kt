package com.example.freebook.ui.reader

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.freebook.BookFreeApplication
import com.example.freebook.audio.sync.AudiobookClipSnapshot
import com.example.freebook.audio.sync.AudiobookSyncController
import com.example.freebook.audio.sync.EpubSyncableBook
import com.example.freebook.audio.sync.PdfSyncableBook
import com.example.freebook.audio.sync.SyncChapter
import com.example.freebook.audio.sync.SyncableBook
import com.example.freebook.audio.tts.AndroidSystemTtsEngine
import com.example.freebook.audio.tts.ReaderTtsController
import com.example.freebook.di.AppContainer
import com.example.freebook.model.ActiveWordHighlight
import com.example.freebook.model.AlignmentProvider
import com.example.freebook.model.AudiobookUiState
import com.example.freebook.model.Book
import com.example.freebook.model.PublicationLayoutType
import com.example.freebook.model.ReaderNavigationMode
import com.example.freebook.model.TtsEngineIds
import com.example.freebook.model.TtsUiState
import com.example.freebook.ui.components.AudioWaveformIcon
import com.example.freebook.ui.components.ChapterListIcon
import com.example.freebook.ui.components.ChevronDirection
import com.example.freebook.ui.components.CircularSkipArrowIcon
import com.example.freebook.ui.components.DirectionChevronIcon
import com.example.freebook.ui.components.PauseBarsIcon
import com.example.freebook.ui.components.PlayTriangleIcon
import com.example.freebook.ui.components.SkipSecondsIcon
import com.example.freebook.ui.components.SleepTimerIcon
import com.example.freebook.ui.components.SwapHorizArrowsIcon
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.launch

enum class ReaderAudioDockMode {
    TTS,
    AUDIOBOOK
}

/**
 * Isolated state holder for TTS + Audiobook controllers and sheets so [BookReaderScreen]
 * stays lean and avoids massive composable register frames.
 */
class ReaderAudioStateHolder(
    val appContainer: AppContainer,
    val ttsController: ReaderTtsController,
    val audiobookController: AudiobookSyncController,
    val ttsState: TtsUiState,
    val audiobookState: AudiobookUiState,
    val activeWordHighlight: ActiveWordHighlight?,
    val syncChapters: List<SyncChapter>,
    val showTtsModelSheet: Boolean,
    val setShowTtsModelSheet: (Boolean) -> Unit,
    val showTtsVoiceSheet: Boolean,
    val setShowTtsVoiceSheet: (Boolean) -> Unit,
    val showSpeedSheet: Boolean,
    val setShowSpeedSheet: (Boolean) -> Unit,
    val showSleepTimerSheet: Boolean,
    val setShowSleepTimerSheet: (Boolean) -> Unit,
    val showAudiobookManagerSheet: Boolean,
    val setShowAudiobookManagerSheet: (Boolean) -> Unit
) {
    val isAudioWordTapEnabled: Boolean
        get() = !ttsState.isOff || audiobookState.hasAudio

    fun onUserManuallyNavigatedBook(fromExplicitJump: Boolean = false) {
        ttsController.onUserManuallyNavigated(fromExplicitJump = fromExplicitJump)
        audiobookController.onUserManuallyScrolledBook(fromExplicitJump = fromExplicitJump)
    }

    fun onReaderViewportChanged(pageIndex: Int, startChar: Int, endChar: Int) {
        ttsController.onReaderViewportChanged(
            pageIndex = pageIndex,
            startChar = startChar,
            endChar = endChar
        )
        audiobookController.onReaderViewportChanged(
            pageIndex = pageIndex,
            startChar = startChar,
            endChar = endChar
        )
    }

    /**
     * Handles fast double-tap on any word in EPUB or PDF:
     * jumps the active audio engine (TTS or Audiobook) directly to that exact word
     * without conflicting with long-press highlighting/text selection.
     */
    fun handleWordDoubleTap(
        pageIndex: Int,
        charOffset: Int,
        audioDockMode: ReaderAudioDockMode,
        context: Context
    ) {
        val hasMappedAudio = audiobookController.hasMappingForPosition(pageIndex, charOffset)
        when {
            audiobookState.isPlaying -> {
                val jumped = audiobookController.seekAudioToBookPosition(
                    pageIndex = pageIndex,
                    charOffset = charOffset,
                    startPlaying = true
                )
                if (!jumped) {
                    Toast.makeText(
                        context,
                        "Map this chapter in Audiobook & Sync to jump audio here",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            !ttsState.isOff && (ttsState.isPlaying || audioDockMode == ReaderAudioDockMode.TTS || !hasMappedAudio) -> {
                ttsController.jumpToWordAndPlay(pageIndex = pageIndex, charOffset = charOffset)
            }
            hasMappedAudio -> {
                audiobookController.seekAudioToBookPosition(
                    pageIndex = pageIndex,
                    charOffset = charOffset,
                    startPlaying = true
                )
            }
            !ttsState.isOff -> {
                ttsController.jumpToWordAndPlay(pageIndex = pageIndex, charOffset = charOffset)
            }
            audiobookState.hasAudio -> {
                Toast.makeText(
                    context,
                    "Map this chapter in Audiobook & Sync to jump audio here",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

@Composable
fun rememberReaderAudioState(
    book: Book,
    canonicalBookText: String,
    reflowableTocEntries: List<ReadiumTocEntry>,
    pdfTocEntries: List<PdfTocEntry>,
    derivedTotalPositions: Int,
    latestPdfPageIndexProvider: () -> Int,
    pdfJumpToPageProvider: () -> ((Int) -> Unit)?,
    readiumNavigator: ReadiumWebViewNavigator,
    isDarkReaderSurface: Boolean,
    isWebViewDocumentReady: Boolean
): ReaderAudioStateHolder {
    val context = LocalContext.current
    val appContainer = remember(context) {
        (context.applicationContext as BookFreeApplication).container
    }

    var showTtsModelSheet by rememberSaveable { mutableStateOf(false) }
    var showTtsVoiceSheet by rememberSaveable { mutableStateOf(false) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showSleepTimerSheet by rememberSaveable { mutableStateOf(false) }
    var showAudiobookManagerSheet by rememberSaveable { mutableStateOf(false) }

    val audiobookRef = remember { arrayOfNulls<AudiobookSyncController>(1) }
    val ttsController = remember(appContainer) {
        ReaderTtsController(
            appContext = context.applicationContext,
            engines = appContainer.createTtsEngines(),
            onWillStartPlayback = { audiobookRef[0]?.pause() }
        )
    }
    val audiobookController = remember(appContainer, ttsController) {
        AudiobookSyncController(
            appContext = context.applicationContext,
            repository = appContainer.audiobookRepository,
            packManager = appContainer.localMmsFaPackManager,
            onWillStartPlayback = {
                if (ttsController.uiState.value.isPlaying) {
                    ttsController.pause()
                }
            }
        ).also { audiobookRef[0] = it }
    }

    DisposableEffect(ttsController, audiobookController) {
        onDispose {
            ttsController.release()
            audiobookController.release()
        }
    }

    val ttsState by ttsController.uiState.collectAsStateWithLifecycle()
    val audiobookState by audiobookController.uiState.collectAsStateWithLifecycle()

    val activeWordHighlight = remember(ttsState.activeHighlight, audiobookState.activeHighlight) {
        ttsState.activeHighlight?.takeIf { it.isActive }
            ?: audiobookState.activeHighlight?.takeIf { it.isActive }
    }

    val latestPdfPageIndexProviderState by rememberUpdatedState(latestPdfPageIndexProvider)
    val latestPdfJumpToPageProviderState by rememberUpdatedState(pdfJumpToPageProvider)

    val syncableBook: SyncableBook = remember(
        book.id,
        book.layoutType,
        canonicalBookText,
        reflowableTocEntries,
        pdfTocEntries,
        derivedTotalPositions
    ) {
        if (book.layoutType == PublicationLayoutType.FIXED) {
            PdfSyncableBook(
                book = book,
                totalPages = derivedTotalPositions.coerceAtLeast(1),
                tocEntries = pdfTocEntries,
                pdfTextIndexer = appContainer.pdfTextIndexer,
                onSeekAndHighlight = { target ->
                    if (target.shouldScrollOrTurnPage && target.pageIndex != latestPdfPageIndexProviderState()) {
                        latestPdfJumpToPageProviderState()?.invoke(target.pageIndex + 1)
                    }
                }
            )
        } else {
            EpubSyncableBook(
                book = book,
                canonicalBookText = canonicalBookText,
                tocEntries = reflowableTocEntries,
                onSeekAndHighlight = { target ->
                    if (target.shouldScrollOrTurnPage && target.highlight == null) {
                        readiumNavigator.jumpToCharOffset(target.startCharOffset)
                    }
                }
            )
        }
    }

    LaunchedEffect(book.id, syncableBook) {
        ttsController.bindBook(syncableBook)
        audiobookController.bindBook(syncableBook)
    }

    val isAudioWordTapEnabled = !ttsState.isOff || audiobookState.hasAudio
    LaunchedEffect(
        book.layoutType,
        isAudioWordTapEnabled,
        isWebViewDocumentReady
    ) {
        if (book.layoutType == PublicationLayoutType.REFLOWABLE && isWebViewDocumentReady) {
            readiumNavigator.setAudioWordTapEnabled(isAudioWordTapEnabled)
        }
    }

    LaunchedEffect(
        book.layoutType,
        activeWordHighlight,
        isDarkReaderSurface,
        isWebViewDocumentReady
    ) {
        if (book.layoutType == PublicationLayoutType.REFLOWABLE && isWebViewDocumentReady) {
            readiumNavigator.updateLiveWordHighlight(
                highlight = activeWordHighlight,
                isDarkSurface = isDarkReaderSurface
            )
        }
    }

    val syncChapters = remember(syncableBook) { syncableBook.getChapters() }

    return ReaderAudioStateHolder(
        appContainer = appContainer,
        ttsController = ttsController,
        audiobookController = audiobookController,
        ttsState = ttsState,
        audiobookState = audiobookState,
        activeWordHighlight = activeWordHighlight,
        syncChapters = syncChapters,
        showTtsModelSheet = showTtsModelSheet,
        setShowTtsModelSheet = { showTtsModelSheet = it },
        showTtsVoiceSheet = showTtsVoiceSheet,
        setShowTtsVoiceSheet = { showTtsVoiceSheet = it },
        showSpeedSheet = showSpeedSheet,
        setShowSpeedSheet = { showSpeedSheet = it },
        showSleepTimerSheet = showSleepTimerSheet,
        setShowSleepTimerSheet = { showSleepTimerSheet = it },
        showAudiobookManagerSheet = showAudiobookManagerSheet,
        setShowAudiobookManagerSheet = { showAudiobookManagerSheet = it }
    )
}

@Composable
fun ReaderAudioTtsDock(
    audioState: ReaderAudioStateHolder,
    audioDockMode: ReaderAudioDockMode,
    onChangeAudioDockMode: (ReaderAudioDockMode) -> Unit,
    isAudioDockExpanded: Boolean,
    onSetAudioDockExpanded: (Boolean) -> Unit,
    onVerticalDragActiveChanged: (Boolean) -> Unit,
    enablePageAnimations: Boolean,
    dockSizeAnimSpec: FiniteAnimationSpec<IntSize>,
    barCornerShape: RoundedCornerShape,
    dockSurfaceColor: Color,
    dockBorderColor: Color,
    dockButtonFill: Color,
    dockButtonBorder: Color,
    dockSheenBrush: Brush,
    currentPdfPageIndex: Int,
    currentCharOffset: Int,
    derivedCurrentPosition: Int,
    currentVisibleText: String,
    onClipCaptured: (AudiobookClipSnapshot) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalBookFreePalette.current
    val ttsState = audioState.ttsState
    val audiobookState = audioState.audiobookState

    var cumulativeAudioDragY by remember { mutableFloatStateOf(0f) }
    val latestIsAudioDockExpanded by rememberUpdatedState(isAudioDockExpanded)
    val latestOnSetAudioDockExpanded by rememberUpdatedState(onSetAudioDockExpanded)
    val latestOnVerticalDragActiveChanged by rememberUpdatedState(onVerticalDragActiveChanged)
    val togglePillFill = if (palette.isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.Black.copy(alpha = 0.09f)
    }

    fun triggerClipCapture() {
        val clip = audioState.audiobookController.captureCurrentAudioClip(
            currentPageNumberOneBased = derivedCurrentPosition,
            currentCharOffset = currentCharOffset,
            currentVisibleSnippet = currentVisibleText
        )
        if (clip != null) {
            onClipCaptured(clip)
        } else {
            Toast.makeText(context, "Import & play an audiobook to save a clip", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                try {
                    detectVerticalDragGestures(
                        onDragStart = {
                            latestOnVerticalDragActiveChanged(true)
                            cumulativeAudioDragY = 0f
                        },
                        onDragEnd = {
                            latestOnVerticalDragActiveChanged(false)
                            cumulativeAudioDragY = 0f
                        },
                        onDragCancel = {
                            latestOnVerticalDragActiveChanged(false)
                            cumulativeAudioDragY = 0f
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            cumulativeAudioDragY += dragAmount
                            if (cumulativeAudioDragY < -24f && !latestIsAudioDockExpanded) {
                                latestOnSetAudioDockExpanded(true)
                                cumulativeAudioDragY = 0f
                            } else if (cumulativeAudioDragY > 24f && latestIsAudioDockExpanded) {
                                latestOnSetAudioDockExpanded(false)
                                cumulativeAudioDragY = 0f
                            }
                        }
                    )
                } finally {
                    latestOnVerticalDragActiveChanged(false)
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
                .border(1.dp, dockBorderColor, barCornerShape),
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSetAudioDockExpanded(!isAudioDockExpanded) }
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

                AnimatedContent(
                    targetState = isAudioDockExpanded,
                    transitionSpec = {
                        if (enablePageAnimations) {
                            ((fadeIn(
                                animationSpec = tween(
                                    durationMillis = 180,
                                    delayMillis = 45,
                                    easing = FastOutSlowInEasing
                                )
                            ) + scaleIn(
                                initialScale = 0.96f,
                                transformOrigin = TransformOrigin(0.5f, 1f),
                                animationSpec = tween(
                                    durationMillis = 200,
                                    delayMillis = 35,
                                    easing = FastOutSlowInEasing
                                )
                            )) togetherWith fadeOut(
                                animationSpec = tween(
                                    durationMillis = 90,
                                    easing = FastOutLinearInEasing
                                )
                            )).using(
                                SizeTransform(clip = true) { _, _ -> dockSizeAnimSpec }
                            )
                        } else {
                            (EnterTransition.None togetherWith ExitTransition.None).using(
                                SizeTransform(clip = true) { _, _ -> snap() }
                            )
                        }
                    },
                    label = "audio_dock_expand_collapse"
                ) { expanded ->
                    if (!expanded) {
                        CollapsedAudioDockContent(
                            audioState = audioState,
                            audioDockMode = audioDockMode,
                            onChangeAudioDockMode = onChangeAudioDockMode,
                            enablePageAnimations = enablePageAnimations,
                            togglePillFill = togglePillFill,
                            dockButtonBorder = dockButtonBorder,
                            currentPdfPageIndex = currentPdfPageIndex,
                            currentCharOffset = currentCharOffset,
                            onTriggerClip = ::triggerClipCapture
                        )
                    } else {
                        ExpandedAudioDockContent(
                            audioState = audioState,
                            audioDockMode = audioDockMode,
                            onChangeAudioDockMode = onChangeAudioDockMode,
                            enablePageAnimations = enablePageAnimations,
                            dockBorderColor = dockBorderColor,
                            dockButtonFill = dockButtonFill,
                            dockButtonBorder = dockButtonBorder,
                            togglePillFill = togglePillFill,
                            currentPdfPageIndex = currentPdfPageIndex,
                            currentCharOffset = currentCharOffset,
                            onTriggerClip = ::triggerClipCapture
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedAudioDockContent(
    audioState: ReaderAudioStateHolder,
    audioDockMode: ReaderAudioDockMode,
    onChangeAudioDockMode: (ReaderAudioDockMode) -> Unit,
    enablePageAnimations: Boolean,
    togglePillFill: Color,
    dockButtonBorder: Color,
    currentPdfPageIndex: Int,
    currentCharOffset: Int,
    onTriggerClip: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val ttsState = audioState.ttsState
    val audiobookState = audioState.audiobookState

    Crossfade(
        targetState = audioDockMode,
        animationSpec = if (enablePageAnimations) tween(180) else snap(),
        label = "collapsed_audio_dock_mode"
    ) { mode ->
        val isPlaying = if (mode == ReaderAudioDockMode.TTS) ttsState.isPlaying else audiobookState.isPlaying
        val speed = if (mode == ReaderAudioDockMode.TTS) ttsState.speechRate else audiobookState.playbackSpeed
        val speedLabel = if (speed % 1f == 0f) "${speed.toInt()}.0x" else "${speed}x"
        val hasTimer = if (mode == ReaderAudioDockMode.TTS) {
            ttsState.sleepTimerRemainingMs != null
        } else {
            audiobookState.sleepTimerRemainingMs != null
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        if (mode == ReaderAudioDockMode.TTS) {
                            audioState.ttsController.togglePlayPause(currentPdfPageIndex, currentCharOffset)
                        } else if (audiobookState.hasAudio) {
                            audioState.audiobookController.togglePlayPause()
                        } else {
                            audioState.setShowAudiobookManagerSheet(true)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    PauseBarsIcon(color = palette.textPrimary, size = 22.dp)
                } else {
                    PlayTriangleIcon(color = palette.textPrimary, size = 22.dp)
                }
            }

            if (mode == ReaderAudioDockMode.TTS) {
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .clickable { audioState.setShowSpeedSheet(true) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = speedLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textSecondary,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { audioState.setShowSleepTimerSheet(true) },
                    contentAlignment = Alignment.Center
                ) {
                    SleepTimerIcon(
                        color = if (hasTimer) Color(0xFF38BDF8) else palette.textSecondary,
                        size = 20.dp
                    )
                }

                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .clickable { audioState.setShowTtsModelSheet(true) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (ttsState.isOff) "Off" else ttsState.selectedModelName,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (ttsState.isOff) palette.textMuted else palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .clickable { audioState.setShowTtsVoiceSheet(true) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Voice",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (ttsState.isOff) palette.textMuted else palette.textSecondary,
                        maxLines = 1
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onTriggerClip)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clip",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textSecondary,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .clickable { audioState.setShowSpeedSheet(true) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = speedLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textSecondary,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { audioState.setShowSleepTimerSheet(true) },
                    contentAlignment = Alignment.Center
                ) {
                    SleepTimerIcon(
                        color = if (hasTimer) Color(0xFF38BDF8) else palette.textSecondary,
                        size = 20.dp
                    )
                }

                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .clickable { audioState.setShowAudiobookManagerSheet(true) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Book",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textSecondary,
                        maxLines = 1
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(togglePillFill)
                    .border(1.dp, dockButtonBorder, CircleShape)
                    .clickable {
                        onChangeAudioDockMode(
                            if (audioDockMode == ReaderAudioDockMode.TTS) {
                                ReaderAudioDockMode.AUDIOBOOK
                            } else {
                                ReaderAudioDockMode.TTS
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                SwapHorizArrowsIcon(color = palette.textPrimary, size = 16.dp)
            }
        }
    }
}

@Composable
private fun ExpandedAudioDockContent(
    audioState: ReaderAudioStateHolder,
    audioDockMode: ReaderAudioDockMode,
    onChangeAudioDockMode: (ReaderAudioDockMode) -> Unit,
    enablePageAnimations: Boolean,
    dockBorderColor: Color,
    dockButtonFill: Color,
    dockButtonBorder: Color,
    togglePillFill: Color,
    currentPdfPageIndex: Int,
    currentCharOffset: Int,
    onTriggerClip: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val ttsState = audioState.ttsState
    val audiobookState = audioState.audiobookState
    val isTtsMode = audioDockMode == ReaderAudioDockMode.TTS
    val isCurrentPlaying = if (isTtsMode) ttsState.isPlaying else audiobookState.isPlaying
    val liveFraction = if (isTtsMode) {
        ttsState.progressFraction.coerceIn(0f, 1f)
    } else if (audiobookState.totalDurationMs > 0L) {
        (audiobookState.currentPositionMs.toFloat() / audiobookState.totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val leftTimeText = if (isTtsMode) ttsState.currentTimeLabel else AudiobookSyncController.formatMs(audiobookState.currentPositionMs)
    val rightTimeText = if (isTtsMode) ttsState.remainingTimeLabel else AudiobookSyncController.formatMs(audiobookState.totalDurationMs)
    val speedVal = if (isTtsMode) ttsState.speechRate else audiobookState.playbackSpeed
    val speedCompactLabel = if (speedVal % 1f == 0f) "${speedVal.toInt()}.0x" else "${speedVal}x"
    val timerActive = if (isTtsMode) ttsState.sleepTimerRemainingMs != null else audiobookState.sleepTimerRemainingMs != null
    val fillWeight = liveFraction.coerceIn(0.001f, 0.999f)
    val remWeight = (1f - fillWeight).coerceAtLeast(0.001f)
    val activeTrackTitle = remember(audiobookState.activeTrackId, audiobookState.tracks) {
        val active = audiobookState.tracks.firstOrNull { it.id == audiobookState.activeTrackId }
            ?: audiobookState.tracks.firstOrNull()
        active?.title?.takeIf { it.isNotBlank() }
    }
    val squircleShape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Subtle centered mode title right beneath the drag handle
        Text(
            text = if (isTtsMode) "TTS" else "Audiobook",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = palette.textMuted,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Slim scrubber track with crisp circular thumb
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(isTtsMode, audiobookState.totalDurationMs) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        if (isTtsMode) audioState.ttsController.seekToFraction(frac)
                        else audioState.audiobookController.seekToFraction(frac)
                    }
                }
                .pointerInput(isTtsMode, audiobookState.totalDurationMs) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        if (isTtsMode) audioState.ttsController.seekToFraction(frac)
                        else audioState.audiobookController.seekToFraction(frac)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .clip(CircleShape)
                    .background(
                        if (palette.isDark) Color.White.copy(alpha = 0.22f)
                        else Color.Black.copy(alpha = 0.16f)
                    )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(fillWeight)
                        .height(3.5.dp)
                        .clip(CircleShape)
                        .background(palette.textPrimary)
                )
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .shadow(3.dp, CircleShape)
                        .clip(CircleShape)
                        .background(palette.textPrimary)
                )
                Spacer(modifier = Modifier.weight(remWeight))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = leftTimeText,
                fontSize = 12.sp,
                color = palette.textMuted,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            Text(
                text = rightTimeText,
                fontSize = 12.sp,
                color = palette.textMuted,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.size(width = 44.dp, height = 38.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { audioState.setShowSleepTimerSheet(true) },
                    contentAlignment = Alignment.Center
                ) {
                    SleepTimerIcon(
                        color = if (timerActive) Color(0xFF38BDF8) else palette.textPrimary,
                        size = 21.dp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable {
                        if (isTtsMode) audioState.ttsController.skipSentence(-1)
                        else audioState.audiobookController.skipByMs(-15_000L)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isTtsMode) {
                    CircularSkipArrowIcon(forward = false, color = palette.textPrimary, size = 28.dp)
                } else {
                    SkipSecondsIcon(seconds = 15, forward = false, color = palette.textPrimary, size = 30.dp)
                }
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {
                        if (isTtsMode) {
                            audioState.ttsController.togglePlayPause(currentPdfPageIndex, currentCharOffset)
                        } else if (audiobookState.hasAudio) {
                            audioState.audiobookController.togglePlayPause()
                        } else {
                            audioState.setShowAudiobookManagerSheet(true)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isCurrentPlaying) {
                    PauseBarsIcon(color = palette.textPrimary, size = 32.dp)
                } else {
                    PlayTriangleIcon(color = palette.textPrimary, size = 34.dp)
                }
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable {
                        if (isTtsMode) audioState.ttsController.skipSentence(1)
                        else audioState.audiobookController.skipByMs(15_000L)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isTtsMode) {
                    CircularSkipArrowIcon(forward = true, color = palette.textPrimary, size = 28.dp)
                } else {
                    SkipSecondsIcon(seconds = 15, forward = true, color = palette.textPrimary, size = 30.dp)
                }
            }

            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 38.dp)
                    .clip(CircleShape)
                    .clickable { audioState.setShowSpeedSheet(true) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = speedCompactLabel,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = dockBorderColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(10.dp))

        Crossfade(
            targetState = audioDockMode,
            animationSpec = if (enablePageAnimations) tween(180) else snap(),
            modifier = Modifier.fillMaxWidth(),
            label = "audio_dock_bottom_options"
        ) { mode ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mode == ReaderAudioDockMode.TTS) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .widthIn(min = 78.dp)
                                .height(46.dp)
                                .clip(squircleShape)
                                .background(dockButtonFill)
                                .border(1.dp, dockButtonBorder, squircleShape)
                                .clickable { audioState.setShowTtsModelSheet(true) }
                                .padding(horizontal = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Model",
                                fontSize = 13.sp,
                                lineHeight = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "(${ttsState.selectedModelName})",
                                fontSize = 11.5.sp,
                                lineHeight = 13.sp,
                                color = palette.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .widthIn(min = 76.dp)
                                .height(46.dp)
                                .clip(squircleShape)
                                .background(dockButtonFill)
                                .border(1.dp, dockButtonBorder, squircleShape)
                                .clickable { audioState.setShowTtsVoiceSheet(true) }
                                .padding(horizontal = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Voice",
                                fontSize = 13.sp,
                                lineHeight = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "(${ttsState.selectedVoice?.shortName ?: if (ttsState.isOff) "Off" else "Auto"})",
                                fontSize = 11.5.sp,
                                lineHeight = 13.sp,
                                color = palette.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    // Book / Chapters squircle showing active chapter + vertical divider + chapter list icon
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(squircleShape)
                            .background(dockButtonFill)
                            .border(1.dp, dockButtonBorder, squircleShape)
                            .clickable { audioState.setShowAudiobookManagerSheet(true) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Chapters",
                                fontSize = 13.sp,
                                lineHeight = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = activeTrackTitle ?: "Import audio",
                                fontSize = 11.5.sp,
                                lineHeight = 13.sp,
                                color = palette.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(dockBorderColor)
                        )

                        ChapterListIcon(color = palette.textPrimary, size = 16.dp)
                    }

                    // Clip squircle
                    Row(
                        modifier = Modifier
                            .height(46.dp)
                            .clip(squircleShape)
                            .background(dockButtonFill)
                            .border(1.dp, dockButtonBorder, squircleShape)
                            .clickable(onClick = onTriggerClip)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AudioWaveformIcon(color = palette.textPrimary, size = 15.dp)
                        Text(
                            text = "Clip",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            maxLines = 1
                        )
                    }
                }

                // Distinct full-capsule mode toggle pill on the far right
                Row(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(CircleShape)
                        .background(togglePillFill)
                        .border(1.dp, dockButtonBorder, CircleShape)
                        .clickable {
                            onChangeAudioDockMode(
                                if (audioDockMode == ReaderAudioDockMode.TTS) {
                                    ReaderAudioDockMode.AUDIOBOOK
                                } else {
                                    ReaderAudioDockMode.TTS
                                }
                            )
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SwapHorizArrowsIcon(color = palette.textPrimary, size = 14.dp)
                    Text(
                        text = if (mode == ReaderAudioDockMode.TTS) "Audio" else "TTS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
    }
}

enum class ReturnToAudioEdge {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM
}

/**
 * Determines whether the currently playing audio/TTS position is already visible on the user's
 * screen (`null` -> hide the return pill) or which screen edge / arrow direction the pill should take.
 */
internal fun resolveReturnToAudioEdge(
    isPdf: Boolean,
    isVerticalScroll: Boolean,
    isRtl: Boolean,
    readerPageIndex: Int,
    readerStartChar: Int,
    readerEndChar: Int,
    audioPageIndex: Int,
    audioCharOffset: Int
): ReturnToAudioEdge? {
    val fallbackEdge = when {
        isVerticalScroll -> ReturnToAudioEdge.TOP
        isRtl -> ReturnToAudioEdge.RIGHT
        else -> ReturnToAudioEdge.LEFT
    }

    val audioIsBefore: Boolean = if (isPdf) {
        if (audioPageIndex < 0 || readerPageIndex < 0) return fallbackEdge
        if (audioPageIndex == readerPageIndex) return null
        audioPageIndex < readerPageIndex
    } else {
        if (audioCharOffset < 0 || readerStartChar < 0) return fallbackEdge
        val safeEnd = maxOf(readerEndChar, readerStartChar + 1)
        if (audioCharOffset in (readerStartChar - 12)..(safeEnd + 12)) return null
        audioCharOffset < readerStartChar
    }

    return when {
        isVerticalScroll -> if (audioIsBefore) ReturnToAudioEdge.TOP else ReturnToAudioEdge.BOTTOM
        isRtl -> if (audioIsBefore) ReturnToAudioEdge.RIGHT else ReturnToAudioEdge.LEFT
        else -> if (audioIsBefore) ReturnToAudioEdge.LEFT else ReturnToAudioEdge.RIGHT
    }
}

@Composable
fun ReaderAudioOverlaysAndSheets(
    book: Book,
    audioState: ReaderAudioStateHolder,
    audioDockMode: ReaderAudioDockMode,
    currentPdfPageIndex: Int,
    currentCharOffset: Int,
    currentVisibleEndCharOffset: Int,
    derivedTotalPositions: Int,
    navigationMode: ReaderNavigationMode,
    isRtlBook: Boolean,
    isDarkReaderSurface: Boolean,
    isReaderHudVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingImportTargetGroupId by remember { mutableStateOf<String?>(null) }

    val audiobookImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val targetGroupId = pendingImportTargetGroupId
        pendingImportTargetGroupId = null
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val added = audioState.appContainer.audiobookRepository.importAudioFiles(
                    bookId = book.id,
                    uris = uris,
                    targetGroupId = targetGroupId
                )
                Toast.makeText(
                    context,
                    if (added > 0) "Imported $added audio file(s)" else "No audio files imported",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val onnxPackImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val res = audioState.appContainer.localMmsFaPackManager.importModelFromUri(uri)
                Toast.makeText(
                    context,
                    if (res.isSuccess) "Local Word Highlighting Pack installed"
                    else (res.exceptionOrNull()?.message ?: "Import failed"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun openInstallTtsData(ctx: Context) {
        runCatching {
            ctx.startActivity(AndroidSystemTtsEngine.createInstallLanguageDataIntent())
        }.onFailure {
            Toast.makeText(ctx, "Open Android Text-to-Speech settings to install voice data.", Toast.LENGTH_SHORT).show()
        }
    }

    val isPdfBook = book.layoutType == PublicationLayoutType.FIXED
    val isVerticalScroll = navigationMode == ReaderNavigationMode.VERTICAL_SCROLL

    val isTtsDetached = !audioState.ttsState.isOff &&
        (audioState.ttsState.isPlaying || audioState.ttsState.isPaused) &&
        audioState.ttsState.isUserDetachedFromSync
    val isAudiobookDetached = !isTtsDetached &&
        audioState.audiobookState.isPlaying &&
        audioState.audiobookState.isUserDetachedFromSync

    val playingPageIndex = if (isTtsDetached) {
        audioState.ttsState.activeHighlight?.pageIndex ?: audioState.ttsState.liveTtsPageIndex
    } else {
        audioState.audiobookState.liveAudioPageIndex
    }
    val playingCharOffset = if (isTtsDetached) {
        audioState.ttsState.activeHighlight?.startCharOffset ?: audioState.ttsState.liveTtsCharOffset
    } else {
        audioState.audiobookState.liveAudioCharOffset
    }

    val activeReturnEdge: ReturnToAudioEdge? = if (isTtsDetached || isAudiobookDetached) {
        resolveReturnToAudioEdge(
            isPdf = isPdfBook,
            isVerticalScroll = isVerticalScroll,
            isRtl = isRtlBook,
            readerPageIndex = currentPdfPageIndex,
            readerStartChar = currentCharOffset,
            readerEndChar = currentVisibleEndCharOffset,
            audioPageIndex = playingPageIndex,
            audioCharOffset = playingCharOffset
        )
    } else {
        null
    }

    val pillLabel = if (isTtsDetached) "TTS" else "Audio"
    val onReturnToAudioClick: () -> Unit = {
        if (isTtsDetached) {
            audioState.ttsController.reattachFollowSync()
        } else {
            audioState.audiobookController.reattachSyncToAudio()
        }
    }

    val topScrollPad by animateDpAsState(
        targetValue = if (isReaderHudVisible) 76.dp else 44.dp,
        animationSpec = tween(durationMillis = 200),
        label = "stitch_pill_top_pad"
    )
    val bottomScrollPad by animateDpAsState(
        targetValue = if (isReaderHudVisible) 136.dp else 44.dp,
        animationSpec = tween(durationMillis = 200),
        label = "stitch_pill_bottom_pad"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Paginated LEFT edge (< Audio)
        AnimatedVisibility(
            visible = activeReturnEdge == ReturnToAudioEdge.LEFT,
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(200)) { -it },
            exit = fadeOut(tween(140)) + slideOutHorizontally(tween(160)) { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
        ) {
            StitchReturnToAudioPill(
                edge = ReturnToAudioEdge.LEFT,
                label = pillLabel,
                isDarkSurface = isDarkReaderSurface,
                onClick = onReturnToAudioClick
            )
        }

        // Paginated RIGHT edge (Audio >)
        AnimatedVisibility(
            visible = activeReturnEdge == ReturnToAudioEdge.RIGHT,
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(200)) { it },
            exit = fadeOut(tween(140)) + slideOutHorizontally(tween(160)) { it },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
        ) {
            StitchReturnToAudioPill(
                edge = ReturnToAudioEdge.RIGHT,
                label = pillLabel,
                isDarkSurface = isDarkReaderSurface,
                onClick = onReturnToAudioClick
            )
        }

        // Scrolling TOP edge (^ / Audio)
        AnimatedVisibility(
            visible = activeReturnEdge == ReturnToAudioEdge.TOP,
            enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { -it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = topScrollPad)
        ) {
            StitchReturnToAudioPill(
                edge = ReturnToAudioEdge.TOP,
                label = pillLabel,
                isDarkSurface = isDarkReaderSurface,
                onClick = onReturnToAudioClick
            )
        }

        // Scrolling BOTTOM edge (Audio / v)
        AnimatedVisibility(
            visible = activeReturnEdge == ReturnToAudioEdge.BOTTOM,
            enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = bottomScrollPad)
        ) {
            StitchReturnToAudioPill(
                edge = ReturnToAudioEdge.BOTTOM,
                label = pillLabel,
                isDarkSurface = isDarkReaderSurface,
                onClick = onReturnToAudioClick
            )
        }
    }

    if (audioState.showTtsModelSheet) {
        TtsModelPickerSheet(
            ttsState = audioState.ttsState,
            onSelectModel = { modelId ->
                audioState.ttsController.selectModel(
                    modelId = modelId,
                    currentPageIndex = currentPdfPageIndex,
                    currentCharOffset = currentCharOffset,
                    autoStartIfSwitchingFromOff = false
                )
            },
            onInstallLanguagePack = { openInstallTtsData(context) },
            onDismiss = { audioState.setShowTtsModelSheet(false) }
        )
    }

    if (audioState.showTtsVoiceSheet) {
        TtsVoicePickerSheet(
            ttsState = audioState.ttsState,
            bookLanguageTag = book.language,
            onSelectVoice = { voiceId -> audioState.ttsController.selectVoice(voiceId) },
            onEnableDefaultModel = {
                audioState.ttsController.selectModel(
                    modelId = TtsEngineIds.BUILT_IN_SYSTEM,
                    currentPageIndex = currentPdfPageIndex,
                    currentCharOffset = currentCharOffset,
                    autoStartIfSwitchingFromOff = false
                )
            },
            onInstallLanguagePack = { openInstallTtsData(context) },
            onDismiss = { audioState.setShowTtsVoiceSheet(false) }
        )
    }

    if (audioState.showSpeedSheet) {
        val isTtsMode = audioDockMode == ReaderAudioDockMode.TTS
        PlaybackSpeedSheet(
            title = if (isTtsMode) "TTS Speed" else "Audiobook Speed",
            currentSpeed = if (isTtsMode) audioState.ttsState.speechRate else audioState.audiobookState.playbackSpeed,
            onSelectSpeed = { speed ->
                if (isTtsMode) audioState.ttsController.setSpeechRate(speed)
                else audioState.audiobookController.setPlaybackSpeed(speed)
            },
            onDismiss = { audioState.setShowSpeedSheet(false) }
        )
    }

    if (audioState.showSleepTimerSheet) {
        val isTtsMode = audioDockMode == ReaderAudioDockMode.TTS
        SleepTimerSheet(
            remainingMs = if (isTtsMode) audioState.ttsState.sleepTimerRemainingMs else audioState.audiobookState.sleepTimerRemainingMs,
            onSelectMinutes = { mins ->
                if (isTtsMode) audioState.ttsController.setSleepTimerMinutes(mins)
                else audioState.audiobookController.setSleepTimerMinutes(mins)
            },
            onDismiss = { audioState.setShowSleepTimerSheet(false) }
        )
    }

    if (audioState.showAudiobookManagerSheet) {
        AudiobookManagerSheet(
            audiobookState = audioState.audiobookState,
            bookChapters = audioState.syncChapters,
            isPdfBook = book.layoutType == PublicationLayoutType.FIXED,
            totalPdfPages = derivedTotalPositions.coerceAtLeast(1),
            currentBookPageIndex = currentPdfPageIndex,
            currentBookCharOffset = currentCharOffset,
            onImportAudioFiles = { targetGroupId ->
                pendingImportTargetGroupId = targetGroupId
                audiobookImportLauncher.launch(
                    arrayOf("audio/*", "application/ogg", "application/octet-stream")
                )
            },
            onCreateGroup = { title ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.createGroup(book.id, title)
                }
            },
            onRenameGroup = { groupId, newTitle ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.renameGroup(groupId, newTitle)
                }
            },
            onRenameTrack = { trackId, newTitle ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.renameTrack(trackId, newTitle)
                }
            },
            onToggleGroupCollapsed = { groupId, collapsed ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.setGroupCollapsed(groupId, collapsed)
                }
            },
            onApplyReorderedStructure = { entries ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.applyReorderedStructure(book.id, entries)
                }
            },
            onDeleteGroup = { groupId ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.deleteGroup(groupId)
                }
            },
            onPlayTrack = { trackId ->
                audioState.audiobookController.playTrack(trackId, autoPlay = true)
                audioState.setShowAudiobookManagerSheet(false)
            },
            onDeleteTrack = { track ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.deleteTrack(track)
                }
            },
            onAutoMatchChapters = {
                coroutineScope.launch {
                    val matched = audioState.appContainer.audiobookRepository.autoMatchGroupsToChapters(
                        bookId = book.id,
                        chapters = audioState.syncChapters
                    )
                    Toast.makeText(
                        context,
                        if (matched > 0) "Auto-matched $matched chapter(s)" else "No matching chapters found",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onSaveMapping = { groupId, trackId, title, startP, endP, startC, endC ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.saveChapterMapping(
                        bookId = book.id,
                        groupId = groupId,
                        trackId = trackId,
                        chapterTitle = title,
                        startPageIndex = startP,
                        endPageIndex = endP,
                        startCharOffset = startC,
                        endCharOffset = endC
                    )
                }
            },
            onRemoveMapping = { mappingId ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.removeMapping(mappingId)
                }
            },
            onCalibrateSyncHere = {
                val ok = audioState.audiobookController.calibrateSyncHere(
                    currentPageIndex = currentPdfPageIndex,
                    currentCharOffset = currentCharOffset
                )
                Toast.makeText(
                    context,
                    if (ok) "Sync anchor saved at current position" else "Map the active audio chapter first",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onClearAnchors = { mappingId ->
                coroutineScope.launch {
                    audioState.appContainer.audiobookRepository.clearSyncAnchors(mappingId)
                }
            },
            onDownloadLocalPack = {
                coroutineScope.launch {
                    val res = audioState.appContainer.localMmsFaPackManager.downloadPack()
                    if (res.isFailure) {
                        Toast.makeText(
                            context,
                            res.exceptionOrNull()?.message ?: "Download failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onImportLocalPackFile = {
                onnxPackImportLauncher.launch(arrayOf("*/*"))
            },
            onDeleteLocalPack = {
                coroutineScope.launch {
                    audioState.appContainer.localMmsFaPackManager.deletePack()
                }
            },
            onAlignChapterLocal = { mappingId ->
                audioState.audiobookController.enqueueChapterAlignment(
                    mappingId = mappingId,
                    provider = AlignmentProvider.LOCAL_MMS_FA,
                    priority = true
                )
                Toast.makeText(context, "Chapter word alignment queued", Toast.LENGTH_SHORT).show()
            },
            onToggleFollowSync = { enabled ->
                audioState.audiobookController.setFollowSyncEnabled(enabled)
            },
            onDismiss = { audioState.setShowAudiobookManagerSheet(false) }
        )
    }
}

/**
 * Minimalist directional pill modeled directly on the Stitch Normal & Scrolling Reader designs:
 * - Paginated (`LEFT` / `RIGHT`): horizontal frosted capsule (`< Audio` / `Audio >`)
 * - Scrolling (`TOP` / `BOTTOM`): vertical stacked frosted capsule (`^` above `Audio` / `Audio` above `v`)
 */
@Composable
private fun StitchReturnToAudioPill(
    edge: ReturnToAudioEdge,
    label: String,
    isDarkSurface: Boolean,
    onClick: () -> Unit
) {
    val pillBg = if (isDarkSurface) {
        Color.White.copy(alpha = 0.86f)
    } else {
        Color(0xFF141416).copy(alpha = 0.86f)
    }
    val pillBorder = if (isDarkSurface) {
        Color.White.copy(alpha = 0.42f)
    } else {
        Color.White.copy(alpha = 0.16f)
    }
    val pillContentColor = if (isDarkSurface) {
        Color(0xFF0A0A0C)
    } else {
        Color.White
    }

    Surface(
        shape = CircleShape,
        color = pillBg,
        shadowElevation = 8.dp,
        modifier = Modifier
            .border(1.dp, pillBorder, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        when (edge) {
            ReturnToAudioEdge.LEFT -> {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    DirectionChevronIcon(
                        direction = ChevronDirection.LEFT,
                        color = pillContentColor,
                        size = 15.dp
                    )
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        color = pillContentColor,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }

            ReturnToAudioEdge.RIGHT -> {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        color = pillContentColor,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    DirectionChevronIcon(
                        direction = ChevronDirection.RIGHT,
                        color = pillContentColor,
                        size = 15.dp
                    )
                }
            }

            ReturnToAudioEdge.TOP -> {
                Column(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    DirectionChevronIcon(
                        direction = ChevronDirection.UP,
                        color = pillContentColor,
                        size = 15.dp
                    )
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        color = pillContentColor,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }

            ReturnToAudioEdge.BOTTOM -> {
                Column(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        color = pillContentColor,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    DirectionChevronIcon(
                        direction = ChevronDirection.DOWN,
                        color = pillContentColor,
                        size = 15.dp
                    )
                }
            }
        }
    }
}
