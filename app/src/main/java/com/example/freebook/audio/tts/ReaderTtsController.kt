package com.example.freebook.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.example.freebook.audio.sync.SyncTarget
import com.example.freebook.audio.sync.SyncableBook
import com.example.freebook.audio.sync.TtsSpeechChunk
import com.example.freebook.model.ActiveHighlightSource
import com.example.freebook.model.ActiveWordHighlight
import com.example.freebook.model.TtsEngineIds
import com.example.freebook.model.TtsModelDescriptor
import com.example.freebook.model.TtsPlaybackStatus
import com.example.freebook.model.TtsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Unified TTS Controller shared identically across EPUB (`ReadiumWebViewNavigator`)
 * and PDF (`CustomPdfReader` / `io.legere:pdfiumandroid`) via [SyncableBook].
 *
 * State semantics:
 * - Initial model is **Off** (`activeHighlight = null`, zero highlighting anywhere).
 * - Default active model is **Built-in (System)** (extensible with future 3rd-party [TtsEngine]s).
 * - **Pause**: stops synthesis immediately and **freezes** the highlight on the exact current word;
 *   pressing Play resumes cleanly from that exact word.
 * - **Change Model -> Off**: quits TTS completely, stops audio, and removes all highlighting.
 */
class ReaderTtsController(
    private val appContext: Context,
    private val engines: List<TtsEngine>,
    private val onWillStartPlayback: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val offDescriptor = TtsModelDescriptor(
        id = TtsEngineIds.OFF,
        name = "Off",
        shortLabel = "Off",
        description = "Turn off TTS and clear all word highlighting",
        isAvailable = true,
        isOffOption = true
    )

    private val enginesById: Map<String, TtsEngine> = engines.associateBy { it.descriptor.id }
    private val defaultEngineId: String = engines.firstOrNull()?.descriptor?.id ?: TtsEngineIds.BUILT_IN_SYSTEM

    private val allModels: List<TtsModelDescriptor> = buildList {
        add(offDescriptor)
        addAll(engines.map { it.descriptor })
    }

    private val _uiState = MutableStateFlow(
        TtsUiState(
            selectedModelId = TtsEngineIds.OFF,
            selectedModelName = offDescriptor.shortLabel,
            availableModels = allModels,
            status = TtsPlaybackStatus.OFF
        )
    )
    val uiState: StateFlow<TtsUiState> = _uiState.asStateFlow()

    private var activeBook: SyncableBook? = null
    private var activeEngine: TtsEngine? = null
    private var engineEventJob: Job? = null
    private var fallbackCadenceJob: Job? = null
    private var sleepTimerJob: Job? = null

    private var currentUnitIndex: Int = 0
    private var unitChunks: List<TtsSpeechChunk> = emptyList()
    private var cumulativeChunkWeights: IntArray = IntArray(0)
    private var totalUnitWeight: Int = 1
    private var currentChunkIndex: Int = 0
    private var pendingStartInChunkSpoken: Int = 0
    private var activeChunkSpokenOffset: Int = 0
    private var activeUtteranceId: String? = null
    private var receivedNativeRangeForUtterance: Boolean = false
    private var lastWordTimestampMs: Long = 0L
    private var smoothedWordDurationMs: Long = 220L
    private var highlightSequence: Long = 0L
    private var lastAutoFollowedPageIndex: Int = -1
    private var lastReaderPageIndex: Int = -1
    private var lastReaderStartChar: Int = -1
    private var lastReaderEndChar: Int = -1
    private var lastProgrammaticJumpMs: Long = 0L

    // Exact paused anchor so Play resumes on the exact paused word
    private var pausedPageIndex: Int = 0
    private var pausedCharOffset: Int = 0
    private var hasPausedAnchor: Boolean = false

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (_uiState.value.isPlaying) {
                pause()
            }
        }
    }

    fun bindBook(syncableBook: SyncableBook?) {
        if (activeBook?.book?.id == syncableBook?.book?.id && activeBook != null && syncableBook != null) {
            activeBook = syncableBook
            return
        }
        // Switching books resets playback cleanly to Off
        quitToOff()
        activeBook = syncableBook
    }

    fun updateBookHandle(syncableBook: SyncableBook) {
        activeBook = syncableBook
    }

    /**
     * Selects a TTS model from the Model button picker.
     * - Selecting [TtsEngineIds.OFF] quits TTS and removes all highlighting immediately.
     * - Selecting an engine initializes it, refreshes the Voice button list, and preserves position.
     */
    fun selectModel(
        modelId: String,
        currentPageIndex: Int,
        currentCharOffset: Int,
        autoStartIfSwitchingFromOff: Boolean = false
    ) {
        if (modelId == TtsEngineIds.OFF) {
            quitToOff()
            return
        }

        val targetEngine = enginesById[modelId] ?: return
        val wasPlaying = _uiState.value.isPlaying
        val wasOff = _uiState.value.isOff

        scope.launch {
            if (activeEngine != null && activeEngine !== targetEngine) {
                activeEngine?.stop()
                engineEventJob?.cancel()
                fallbackCadenceJob?.cancel()
            }

            activeEngine = targetEngine
            _uiState.update {
                it.copy(
                    selectedModelId = targetEngine.descriptor.id,
                    selectedModelName = targetEngine.descriptor.shortLabel,
                    status = TtsPlaybackStatus.INITIALIZING,
                    errorMessage = null,
                    missingLanguageTag = null
                )
            }

            val lang = activeBook?.book?.language ?: "en"
            val initResult = targetEngine.initialize(lang)
            targetEngine.setSpeechRate(_uiState.value.speechRate)
            val voices = targetEngine.getVoices(lang)
            val selectedId = targetEngine.getSelectedVoiceId()
            val chosenVoice = voices.firstOrNull { it.id == selectedId } ?: voices.firstOrNull()

            if (!initResult.success) {
                _uiState.update {
                    it.copy(
                        status = if (initResult.missingLanguageTag != null) {
                            TtsPlaybackStatus.MISSING_LANGUAGE_DATA
                        } else {
                            TtsPlaybackStatus.ERROR
                        },
                        availableVoices = voices,
                        selectedVoice = chosenVoice,
                        missingLanguageTag = initResult.missingLanguageTag,
                        errorMessage = initResult.errorMessage
                    )
                }
                return@launch
            }

            observeEngineEvents(targetEngine)
            _uiState.update {
                it.copy(
                    status = if (hasPausedAnchor && !wasOff) TtsPlaybackStatus.PAUSED else TtsPlaybackStatus.READY,
                    availableVoices = voices,
                    selectedVoice = chosenVoice,
                    missingLanguageTag = initResult.missingLanguageTag,
                    errorMessage = null
                )
            }

            if (wasPlaying || (wasOff && autoStartIfSwitchingFromOff)) {
                val startPage = if (hasPausedAnchor) pausedPageIndex else currentPageIndex
                val startChar = if (hasPausedAnchor) pausedCharOffset else currentCharOffset
                startFrom(startPage, startChar)
            }
        }
    }

    /**
     * Play / Pause toggle wired to the TTS dock center button.
     * - If currently Playing -> Pause (stops audio, keeps highlight frozen on current word).
     * - If currently Paused -> Resume from exact paused word (preserving free-browse detach if user browsed away).
     * - If currently Off -> auto-activates default Built-in engine and starts from current reader position.
     */
    fun togglePlayPause(currentPageIndex: Int, currentCharOffset: Int) {
        val state = _uiState.value
        when {
            state.isPlaying -> pause()
            state.isPaused && activeEngine != null -> {
                startFrom(
                    pageIndex = if (hasPausedAnchor) pausedPageIndex else currentPageIndex,
                    charOffset = if (hasPausedAnchor) pausedCharOffset else currentCharOffset,
                    preserveDetached = state.isUserDetachedFromSync
                )
            }
            state.isOff || activeEngine == null -> {
                selectModel(
                    modelId = defaultEngineId,
                    currentPageIndex = currentPageIndex,
                    currentCharOffset = currentCharOffset,
                    autoStartIfSwitchingFromOff = true
                )
            }
            else -> {
                startFrom(currentPageIndex, currentCharOffset, preserveDetached = false)
            }
        }
    }

    /**
     * Fast word-jump action (triggered by fast double-tap on any word in EPUB/PDF or tapping
     * "Read Here" in the detached floating pill).
     * Re-attaches auto-follow and immediately starts speaking from that exact word.
     */
    fun jumpToWordAndPlay(pageIndex: Int, charOffset: Int): Boolean {
        if (_uiState.value.isOff || activeEngine == null) return false
        lastProgrammaticJumpMs = System.currentTimeMillis()
        _uiState.update { it.copy(isUserDetachedFromSync = false) }
        startFrom(pageIndex = pageIndex, charOffset = charOffset, preserveDetached = false)
        return true
    }

    /**
     * Called when the user manually swipes, scrolls, or jumps (TOC / Search / Bookmark)
     * away from the page TTS is currently reading or paused on.
     * Lets the user browse freely without TTS yanking them back.
     */
    fun onUserManuallyNavigated(fromExplicitJump: Boolean = false) {
        val state = _uiState.value
        if (state.isOff) return
        if (!state.isPlaying && !state.isPaused) return
        if (!fromExplicitJump && System.currentTimeMillis() - lastProgrammaticJumpMs < 750L) return
        if (state.isUserDetachedFromSync) return

        _uiState.update {
            it.copy(
                isUserDetachedFromSync = true,
                activeHighlight = it.activeHighlight?.copy(shouldAutoFollow = false)
            )
        }
    }

    /**
     * Called whenever the reader's visible page/character range changes.
     * Automatically clears detached state & hides the return pill when the user manually
     * swipes or scrolls back onto the page where TTS is currently speaking/paused.
     */
    fun onReaderViewportChanged(pageIndex: Int, startChar: Int, endChar: Int) {
        lastReaderPageIndex = pageIndex
        lastReaderStartChar = startChar
        lastReaderEndChar = endChar.coerceAtLeast(startChar)

        val book = activeBook ?: return
        val state = _uiState.value
        if (state.isOff || !state.isUserDetachedFromSync) return
        if (!state.isPlaying && !state.isPaused) return

        val ttsPage = state.activeHighlight?.pageIndex
            ?: if (hasPausedAnchor) pausedPageIndex else state.liveTtsPageIndex
        val ttsChar = state.activeHighlight?.startCharOffset
            ?: if (hasPausedAnchor) pausedCharOffset else state.liveTtsCharOffset

        val isVisible = if (book.isPdf) {
            ttsPage >= 0 && pageIndex >= 0 && ttsPage == pageIndex
        } else {
            ttsChar >= 0 && startChar >= 0 &&
                ttsChar in (startChar - 12)..(maxOf(lastReaderEndChar, startChar + 1) + 12)
        }
        if (isVisible) {
            lastAutoFollowedPageIndex = ttsPage
            _uiState.update {
                it.copy(
                    isUserDetachedFromSync = false,
                    activeHighlight = it.activeHighlight?.copy(shouldAutoFollow = !it.isPaused)
                )
            }
        }
    }

    /**
     * Re-attaches auto-follow ("Return to TTS") and immediately navigates the reader
     * back to the active or paused TTS word/page.
     */
    fun reattachFollowSync() {
        val book = activeBook ?: return
        val state = _uiState.value
        if (state.isOff) return

        lastProgrammaticJumpMs = System.currentTimeMillis()
        val currentHl = state.activeHighlight
        if (currentHl != null && currentHl.isActive) {
            val refreshed = currentHl.copy(
                shouldAutoFollow = true,
                sequenceId = ++highlightSequence
            )
            lastAutoFollowedPageIndex = refreshed.pageIndex
            _uiState.update {
                it.copy(
                    isUserDetachedFromSync = false,
                    activeHighlight = refreshed
                )
            }
            book.seekToPosition(
                SyncTarget(
                    pageIndex = refreshed.pageIndex,
                    startCharOffset = refreshed.startCharOffset,
                    endCharOffset = refreshed.endCharOffset,
                    highlight = refreshed,
                    shouldScrollOrTurnPage = true
                )
            )
        } else {
            _uiState.update { it.copy(isUserDetachedFromSync = false) }
            if (hasPausedAnchor) {
                book.seekToPosition(
                    SyncTarget(
                        pageIndex = pausedPageIndex,
                        startCharOffset = pausedCharOffset,
                        endCharOffset = pausedCharOffset,
                        highlight = null,
                        shouldScrollOrTurnPage = true
                    )
                )
            }
        }
    }

    fun startFrom(
        pageIndex: Int,
        charOffset: Int,
        preserveDetached: Boolean = false
    ) {
        val book = activeBook ?: return
        val engine = activeEngine ?: run {
            selectModel(defaultEngineId, pageIndex, charOffset, autoStartIfSwitchingFromOff = true)
            return
        }

        scope.launch {
            onWillStartPlayback()
            requestAudioFocus()
            fallbackCadenceJob?.cancel()
            engine.stop()

            if (!preserveDetached) {
                lastProgrammaticJumpMs = System.currentTimeMillis()
            }

            currentUnitIndex = if (book.isPdf) {
                pageIndex.coerceIn(0, (book.getTotalTtsUnits() - 1).coerceAtLeast(0))
            } else {
                0
            }

            val loaded = loadNextNonEmptyUnit(book, currentUnitIndex, charOffset)
            if (!loaded) {
                _uiState.update {
                    it.copy(
                        status = TtsPlaybackStatus.READY,
                        errorMessage = if (book.isPdf) {
                            "No readable text layer found on this PDF page (scanned image)."
                        } else {
                            "No readable text found."
                        }
                    )
                }
                return@launch
            }

            hasPausedAnchor = false
            _uiState.update {
                it.copy(
                    status = TtsPlaybackStatus.PLAYING,
                    isUserDetachedFromSync = if (preserveDetached) it.isUserDetachedFromSync else false,
                    errorMessage = null
                )
            }
            speakCurrentChunk()
        }
    }

    /**
     * Pauses TTS playback: stops audio immediately while **keeping the highlight frozen
     * on the exact word that was being spoken** so Start continues from that word.
     */
    fun pause() {
        fallbackCadenceJob?.cancel()
        activeUtteranceId = null
        activeEngine?.stop()
        abandonAudioFocus()

        val currentHl = _uiState.value.activeHighlight
        if (currentHl != null && currentHl.isActive) {
            pausedPageIndex = currentHl.pageIndex
            pausedCharOffset = currentHl.startCharOffset
            hasPausedAnchor = true
        }

        _uiState.update {
            it.copy(
                status = if (it.selectedModelId == TtsEngineIds.OFF) {
                    TtsPlaybackStatus.OFF
                } else {
                    TtsPlaybackStatus.PAUSED
                },
                activeHighlight = currentHl?.copy(isPaused = true, shouldAutoFollow = false)
            )
        }
    }

    /**
     * Quits TTS completely (triggered by choosing Model -> Off, switching books, or closing reader).
     * Clears ALL word highlighting immediately.
     */
    fun quitToOff() {
        fallbackCadenceJob?.cancel()
        sleepTimerJob?.cancel()
        engineEventJob?.cancel()
        activeUtteranceId = null
        activeEngine?.stop()
        activeEngine = null
        abandonAudioFocus()

        unitChunks = emptyList()
        cumulativeChunkWeights = IntArray(0)
        totalUnitWeight = 1
        currentChunkIndex = 0
        pendingStartInChunkSpoken = 0
        activeChunkSpokenOffset = 0
        lastAutoFollowedPageIndex = -1
        hasPausedAnchor = false

        _uiState.update {
            it.copy(
                selectedModelId = TtsEngineIds.OFF,
                selectedModelName = offDescriptor.shortLabel,
                status = TtsPlaybackStatus.OFF,
                selectedVoice = null,
                availableVoices = emptyList(),
                activeHighlight = null,
                isUserDetachedFromSync = false,
                sleepTimerRemainingMs = null,
                missingLanguageTag = null,
                errorMessage = null
            )
        }
    }

    fun selectVoice(voiceId: String) {
        val engine = activeEngine ?: return
        val wasPlaying = _uiState.value.isPlaying
        scope.launch {
            val ok = engine.selectVoice(voiceId)
            if (ok) {
                val chosen = _uiState.value.availableVoices.firstOrNull { it.id == voiceId }
                _uiState.update { it.copy(selectedVoice = chosen) }
                if (wasPlaying) {
                    val hl = _uiState.value.activeHighlight
                    if (hl != null && hl.isActive) {
                        startFrom(
                            pageIndex = hl.pageIndex,
                            charOffset = hl.startCharOffset,
                            preserveDetached = _uiState.value.isUserDetachedFromSync
                        )
                    } else {
                        speakCurrentChunk()
                    }
                }
            }
        }
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.5f)
        activeEngine?.setSpeechRate(clamped)
        _uiState.update { it.copy(speechRate = clamped) }
        if (_uiState.value.isPlaying) {
            val hl = _uiState.value.activeHighlight
            if (hl != null && hl.isActive) {
                startFrom(
                    pageIndex = hl.pageIndex,
                    charOffset = hl.startCharOffset,
                    preserveDetached = _uiState.value.isUserDetachedFromSync
                )
            }
        }
    }

    fun skipSentence(deltaChunks: Int) {
        val book = activeBook ?: return
        if (_uiState.value.isOff || activeEngine == null) return
        val wasPlaying = _uiState.value.isPlaying

        scope.launch {
            fallbackCadenceJob?.cancel()
            activeEngine?.stop()
            lastProgrammaticJumpMs = System.currentTimeMillis()
            _uiState.update { it.copy(isUserDetachedFromSync = false) }
            pendingStartInChunkSpoken = 0

            if (unitChunks.isEmpty()) {
                val loaded = loadNextNonEmptyUnit(book, currentUnitIndex, 0)
                if (!loaded) return@launch
            }

            val targetIdx = currentChunkIndex + deltaChunks
            when {
                targetIdx in unitChunks.indices -> {
                    currentChunkIndex = targetIdx
                }
                targetIdx < 0 && book.isPdf && currentUnitIndex > 0 -> {
                    var prevPage = currentUnitIndex - 1
                    var foundPrev = false
                    while (prevPage >= 0) {
                        val prevChunks = book.loadTtsChunksForUnit(prevPage, 0)
                        if (prevChunks.isNotEmpty()) {
                            currentUnitIndex = prevPage
                            setUnitChunks(prevChunks)
                            currentChunkIndex = prevChunks.lastIndex
                            foundPrev = true
                            break
                        }
                        prevPage--
                    }
                    if (!foundPrev) currentChunkIndex = 0
                }
                targetIdx >= unitChunks.size && book.isPdf && currentUnitIndex + 1 < book.getTotalTtsUnits() -> {
                    if (loadNextNonEmptyUnit(book, currentUnitIndex + 1, 0)) {
                        currentChunkIndex = 0
                    }
                }
                else -> {
                    currentChunkIndex = currentChunkIndex.coerceIn(0, (unitChunks.size - 1).coerceAtLeast(0))
                }
            }

            val chunk = unitChunks.getOrNull(currentChunkIndex) ?: return@launch
            updateProgressLabels()
            emitHighlightForSpan(
                chunk = chunk,
                startInSpoken = 0,
                endInSpoken = findFirstWordEndFrom(chunk.spokenText, 0),
                isPaused = !wasPlaying,
                estimatedDurationMs = 200L,
                forceScroll = true
            )
            if (wasPlaying) {
                speakCurrentChunk()
            } else {
                pausedPageIndex = chunk.pageIndex
                pausedCharOffset = chunk.docStartChar
                hasPausedAnchor = true
            }
        }
    }

    fun seekToFraction(fraction: Float) {
        val book = activeBook ?: return
        if (_uiState.value.isOff) return
        val safeFrac = fraction.coerceIn(0f, 1f)
        val wasPlaying = _uiState.value.isPlaying

        scope.launch {
            fallbackCadenceJob?.cancel()
            activeEngine?.stop()
            lastProgrammaticJumpMs = System.currentTimeMillis()
            _uiState.update { it.copy(isUserDetachedFromSync = false) }
            pendingStartInChunkSpoken = 0

            if (book.isPdf) {
                val targetPage = ((book.getTotalTtsUnits() - 1) * safeFrac).toInt()
                    .coerceIn(0, (book.getTotalTtsUnits() - 1).coerceAtLeast(0))
                if (wasPlaying) {
                    startFrom(targetPage, 0, preserveDetached = false)
                } else {
                    val loaded = loadNextNonEmptyUnit(book, targetPage, 0)
                    val firstChunk = if (loaded) unitChunks.firstOrNull() else null
                    pausedPageIndex = firstChunk?.pageIndex ?: targetPage
                    pausedCharOffset = firstChunk?.docStartChar ?: 0
                    hasPausedAnchor = true
                    updateProgressLabels()
                    if (firstChunk != null) {
                        emitHighlightForSpan(
                            chunk = firstChunk,
                            startInSpoken = 0,
                            endInSpoken = findFirstWordEndFrom(firstChunk.spokenText, 0),
                            isPaused = true,
                            estimatedDurationMs = 180L,
                            forceScroll = true
                        )
                    } else {
                        book.seekToPosition(SyncTarget(targetPage, 0, 0, highlight = null, shouldScrollOrTurnPage = true))
                    }
                }
            } else {
                if (unitChunks.isEmpty()) {
                    val loaded = loadNextNonEmptyUnit(book, 0, 0)
                    if (!loaded) return@launch
                }
                val targetIdx = ((unitChunks.size - 1).coerceAtLeast(0) * safeFrac).toInt()
                val chunk = unitChunks.getOrNull(targetIdx) ?: return@launch
                currentChunkIndex = targetIdx
                updateProgressLabels()
                if (wasPlaying) {
                    emitHighlightForSpan(
                        chunk = chunk,
                        startInSpoken = 0,
                        endInSpoken = findFirstWordEndFrom(chunk.spokenText, 0),
                        isPaused = false,
                        estimatedDurationMs = 180L,
                        forceScroll = true
                    )
                    speakCurrentChunk()
                } else {
                    pausedPageIndex = 0
                    pausedCharOffset = chunk.docStartChar
                    hasPausedAnchor = true
                    emitHighlightForSpan(
                        chunk = chunk,
                        startInSpoken = 0,
                        endInSpoken = findFirstWordEndFrom(chunk.spokenText, 0),
                        isPaused = true,
                        estimatedDurationMs = 180L,
                        forceScroll = true
                    )
                }
            }
        }
    }

    fun setSleepTimerMinutes(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _uiState.update { it.copy(sleepTimerRemainingMs = null) }
            return
        }
        val endTime = System.currentTimeMillis() + minutes * 60_000L
        _uiState.update { it.copy(sleepTimerRemainingMs = minutes * 60_000L) }
        sleepTimerJob = scope.launch {
            while (isActive) {
                val rem = (endTime - System.currentTimeMillis()).coerceAtLeast(0L)
                _uiState.update { it.copy(sleepTimerRemainingMs = if (rem > 0L) rem else null) }
                if (rem <= 0L) {
                    if (_uiState.value.isPlaying) pause()
                    break
                }
                delay(1000L)
            }
        }
    }

    fun release() {
        quitToOff()
        engines.forEach { it.release() }
        scope.cancel()
    }

    private fun setUnitChunks(chunks: List<TtsSpeechChunk>) {
        unitChunks = chunks
        val cum = IntArray(chunks.size)
        var acc = 0
        for (i in chunks.indices) {
            acc += chunks[i].spokenWeight
            cum[i] = acc
        }
        cumulativeChunkWeights = cum
        totalUnitWeight = acc.coerceAtLeast(1)
    }

    private fun findChunkIndexForCharOffset(chunks: List<TtsSpeechChunk>, charOffset: Int): Int {
        if (chunks.isEmpty() || charOffset <= 0) return 0
        var lo = 0
        var hi = chunks.lastIndex
        var candidate = chunks.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = chunks[mid]
            if (charOffset < c.docStartChar) {
                candidate = mid
                hi = mid - 1
            } else if (charOffset >= c.docEndChar) {
                lo = mid + 1
            } else {
                return mid
            }
        }
        return candidate.coerceIn(0, chunks.lastIndex)
    }

    private suspend fun loadNextNonEmptyUnit(
        book: SyncableBook,
        startUnit: Int,
        firstUnitCharOffset: Int
    ): Boolean {
        val totalUnits = book.getTotalTtsUnits().coerceAtLeast(1)
        var u = startUnit.coerceIn(0, totalUnits - 1)
        var first = true
        while (u < totalUnits) {
            val chunks = book.loadTtsChunksForUnit(u, 0)
            val useOffset = if (first) firstUnitCharOffset else 0
            first = false
            if (chunks.isNotEmpty()) {
                currentUnitIndex = u
                setUnitChunks(chunks)
                if (useOffset > 0) {
                    val idx = findChunkIndexForCharOffset(chunks, useOffset)
                    currentChunkIndex = idx
                    pendingStartInChunkSpoken = chunks[idx].findSpokenWordStartForOriginalOffset(useOffset)
                } else {
                    currentChunkIndex = 0
                    pendingStartInChunkSpoken = 0
                }
                return true
            }
            u++
        }
        return false
    }

    private fun observeEngineEvents(engine: TtsEngine) {
        engineEventJob?.cancel()
        engineEventJob = scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is TtsEngineEvent.UtteranceStarted -> {
                        if (event.utteranceId != activeUtteranceId) return@collect
                        receivedNativeRangeForUtterance = false
                        lastWordTimestampMs = System.currentTimeMillis()
                        val chunk = unitChunks.getOrNull(currentChunkIndex) ?: return@collect
                        val startInSpoken = activeChunkSpokenOffset
                        val endInSpoken = findFirstWordEndFrom(chunk.spokenText, startInSpoken)
                        // Immediately show first word and arm automatic cadence fallback for engines/APIs without onRangeStart
                        emitHighlightForSpan(
                            chunk = chunk,
                            startInSpoken = startInSpoken,
                            endInSpoken = endInSpoken,
                            isPaused = false,
                            estimatedDurationMs = smoothedWordDurationMs
                        )
                        scheduleFallbackCadenceIfNeeded(event.utteranceId, chunk, startInSpoken)
                    }

                    is TtsEngineEvent.WordRangeStarted -> {
                        if (event.utteranceId != activeUtteranceId) return@collect
                        receivedNativeRangeForUtterance = true
                        fallbackCadenceJob?.cancel()
                        val now = System.currentTimeMillis()
                        val delta = (now - lastWordTimestampMs).coerceIn(70L, 650L)
                        smoothedWordDurationMs = ((smoothedWordDurationMs * 3L) + delta) / 4L
                        lastWordTimestampMs = now

                        val chunk = unitChunks.getOrNull(currentChunkIndex) ?: return@collect
                        emitHighlightForSpan(
                            chunk = chunk,
                            startInSpoken = activeChunkSpokenOffset + event.startChar,
                            endInSpoken = activeChunkSpokenOffset + event.endChar,
                            isPaused = false,
                            estimatedDurationMs = smoothedWordDurationMs
                        )
                    }

                    is TtsEngineEvent.UtteranceDone -> {
                        if (event.utteranceId != activeUtteranceId) return@collect
                        fallbackCadenceJob?.cancel()
                        if (_uiState.value.isPlaying) {
                            advanceToNextChunk()
                        }
                    }

                    is TtsEngineEvent.UtteranceError -> {
                        if (event.utteranceId != activeUtteranceId) return@collect
                        fallbackCadenceJob?.cancel()
                        _uiState.update {
                            it.copy(
                                status = TtsPlaybackStatus.PAUSED,
                                errorMessage = event.message
                            )
                        }
                    }
                }
            }
        }
    }

    private fun speakCurrentChunk() {
        val engine = activeEngine ?: return
        val chunk = unitChunks.getOrNull(currentChunkIndex) ?: return
        val baseOffset = pendingStartInChunkSpoken.coerceIn(0, (chunk.spokenText.length - 1).coerceAtLeast(0))
        activeChunkSpokenOffset = baseOffset
        pendingStartInChunkSpoken = 0

        val textToSpeak = if (baseOffset > 0) chunk.spokenText.substring(baseOffset) else chunk.spokenText
        if (textToSpeak.isBlank()) {
            advanceToNextChunk()
            return
        }

        val utterId = "${chunk.chunkId}_${System.nanoTime()}"
        activeUtteranceId = utterId
        receivedNativeRangeForUtterance = false
        updateProgressLabels()
        engine.speak(utterId, textToSpeak)
    }

    private fun advanceToNextChunk() {
        val book = activeBook ?: return
        pendingStartInChunkSpoken = 0
        if (currentChunkIndex + 1 < unitChunks.size) {
            currentChunkIndex++
            speakCurrentChunk()
            return
        }
        if (book.isPdf && currentUnitIndex + 1 < book.getTotalTtsUnits()) {
            scope.launch {
                val loaded = loadNextNonEmptyUnit(book, currentUnitIndex + 1, 0)
                if (loaded && _uiState.value.isPlaying) {
                    speakCurrentChunk()
                } else {
                    finishBookPlayback()
                }
            }
        } else {
            finishBookPlayback()
        }
    }

    private fun finishBookPlayback() {
        fallbackCadenceJob?.cancel()
        activeUtteranceId = null
        abandonAudioFocus()
        _uiState.update {
            it.copy(
                status = TtsPlaybackStatus.READY,
                progressFraction = 1f,
                activeHighlight = it.activeHighlight?.copy(isPaused = true, shouldAutoFollow = false)
            )
        }
    }

    /**
     * Smooth fallback cadence estimator: if a TTS voice (or API 24–25) doesn't emit `onRangeStart`
     * within 320ms of `onStart`, glides across words using speech-rate-scaled character weights,
     * resynced on every sentence boundary.
     */
    private fun scheduleFallbackCadenceIfNeeded(
        utteranceId: String,
        chunk: TtsSpeechChunk,
        startOffsetInChunk: Int
    ) {
        fallbackCadenceJob?.cancel()
        fallbackCadenceJob = scope.launch {
            delay(320L)
            if (receivedNativeRangeForUtterance || activeUtteranceId != utteranceId || !_uiState.value.isPlaying) {
                return@launch
            }
            val words = extractWordSpans(chunk.spokenText, startOffsetInChunk)
            if (words.size <= 1) return@launch
            val rate = _uiState.value.speechRate.coerceIn(0.5f, 2.5f)
            val msPerChar = (62f / rate)
            for (idx in 1 until words.size) {
                if (receivedNativeRangeForUtterance || activeUtteranceId != utteranceId || !_uiState.value.isPlaying) {
                    break
                }
                val (s, e) = words[idx]
                val estDur = (((e - s).coerceAtLeast(2) * msPerChar) + 55f).roundToLong().coerceIn(90L, 650L)
                emitHighlightForSpan(chunk, s, e, isPaused = false, estimatedDurationMs = estDur)
                delay(estDur)
            }
        }
    }

    private fun emitHighlightForSpan(
        chunk: TtsSpeechChunk,
        startInSpoken: Int,
        endInSpoken: Int,
        isPaused: Boolean,
        estimatedDurationMs: Long,
        forceScroll: Boolean = false
    ) {
        val book = activeBook ?: return
        val mapped = chunk.mapRangeToOriginal(startInSpoken, endInSpoken) ?: return
        val safeStart = startInSpoken.coerceIn(0, chunk.spokenText.length)
        val safeEnd = endInSpoken.coerceIn(safeStart, chunk.spokenText.length)
        val wordText = chunk.spokenText.substring(safeStart, safeEnd)

        val wasDetached = _uiState.value.isUserDetachedFromSync
        val caughtUpToViewport = wasDetached && if (book.isPdf) {
            chunk.pageIndex >= 0 && lastReaderPageIndex >= 0 && chunk.pageIndex == lastReaderPageIndex
        } else {
            mapped.first >= 0 && lastReaderStartChar >= 0 &&
                mapped.first in (lastReaderStartChar - 12)..(maxOf(lastReaderEndChar, lastReaderStartChar + 1) + 12)
        }
        val isStillDetached = wasDetached && !caughtUpToViewport
        val shouldFollow = forceScroll || (!isStillDetached && !isPaused)

        val hl = ActiveWordHighlight(
            source = ActiveHighlightSource.TTS,
            pageIndex = chunk.pageIndex,
            startCharOffset = mapped.first,
            endCharOffset = mapped.second,
            wordText = wordText,
            estimatedDurationMs = estimatedDurationMs,
            progressFraction = 0f,
            isPaused = isPaused,
            shouldAutoFollow = shouldFollow,
            sequenceId = ++highlightSequence
        )

        pausedPageIndex = hl.pageIndex
        pausedCharOffset = hl.startCharOffset
        hasPausedAnchor = true

        if (shouldFollow && (forceScroll || hl.pageIndex != lastAutoFollowedPageIndex)) {
            lastAutoFollowedPageIndex = hl.pageIndex
            lastProgrammaticJumpMs = System.currentTimeMillis()
        }

        _uiState.update {
            it.copy(
                activeHighlight = hl,
                liveTtsPageIndex = hl.pageIndex,
                liveTtsCharOffset = hl.startCharOffset,
                isUserDetachedFromSync = if (caughtUpToViewport) false else it.isUserDetachedFromSync
            )
        }
        book.seekToPosition(
            SyncTarget(
                pageIndex = hl.pageIndex,
                startCharOffset = hl.startCharOffset,
                endCharOffset = hl.endCharOffset,
                highlight = hl,
                shouldScrollOrTurnPage = shouldFollow
            )
        )
    }

    private fun updateProgressLabels() {
        val book = activeBook ?: return
        val rate = _uiState.value.speechRate.coerceIn(0.5f, 2.5f)
        val frac: Float
        val remainingWeight: Int
        val elapsedWeight: Int

        if (book.isPdf) {
            val totalUnits = book.getTotalTtsUnits().coerceAtLeast(1)
            val inPageFrac = if (unitChunks.isEmpty()) 0f else currentChunkIndex.toFloat() / unitChunks.size.toFloat()
            frac = ((currentUnitIndex + inPageFrac) / totalUnits.toFloat()).coerceIn(0f, 1f)
            elapsedWeight = (frac * totalUnits * 1400f).toInt()
            remainingWeight = ((1f - frac) * totalUnits * 1400f).toInt()
        } else {
            val totalW = totalUnitWeight.coerceAtLeast(1)
            val done = if (currentChunkIndex > 0 && cumulativeChunkWeights.isNotEmpty()) {
                cumulativeChunkWeights[(currentChunkIndex - 1).coerceIn(0, cumulativeChunkWeights.lastIndex)]
            } else {
                0
            }
            frac = (done.toFloat() / totalW.toFloat()).coerceIn(0f, 1f)
            elapsedWeight = done
            remainingWeight = (totalW - done).coerceAtLeast(0)
        }

        // Approximate ~15 spoken-weight units per second at 1.0x
        val charsPerSec = 15.0 * rate
        val elapsedSec = (elapsedWeight / charsPerSec).roundToLong().coerceAtLeast(0L)
        val remSec = (remainingWeight / charsPerSec).roundToLong().coerceAtLeast(0L)

        _uiState.update {
            it.copy(
                progressFraction = frac,
                currentTimeLabel = formatDurationSeconds(elapsedSec),
                remainingTimeLabel = formatDurationSeconds(remSec)
            )
        }
    }

    private fun findFirstWordEndFrom(text: String, fromIndex: Int): Int {
        var i = fromIndex.coerceIn(0, text.length)
        while (i < text.length && text[i].isWhitespace()) i++
        val start = i
        while (i < text.length && !text[i].isWhitespace()) i++
        return i.coerceAtLeast((start + 1).coerceAtMost(text.length))
    }

    private fun extractWordSpans(text: String, fromIndex: Int = 0): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = fromIndex.coerceIn(0, text.length)
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            val s = i
            while (i < text.length && !text[i].isWhitespace()) i++
            if (i > s) out.add(s to i)
        }
        return out
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
    }

    companion object {
        fun formatDurationSeconds(totalSeconds: Long): String {
            val s = totalSeconds.coerceAtLeast(0L)
            val hrs = s / 3600
            val mins = (s % 3600) / 60
            val secs = s % 60
            return if (hrs > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
            } else {
                String.format(Locale.US, "%d:%02d", mins, secs)
            }
        }
    }
}
