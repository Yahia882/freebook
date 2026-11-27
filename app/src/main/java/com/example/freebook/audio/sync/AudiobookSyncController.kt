package com.example.freebook.audio.sync

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.freebook.audio.align.ChapterAlignmentWorker
import com.example.freebook.audio.align.LocalMmsFaPackManager
import com.example.freebook.audio.playback.AudiobookPlaybackService
import com.example.freebook.audio.playback.AudiobookRepository
import com.example.freebook.audio.playback.BookAudiobookBundle
import com.example.freebook.model.ActiveHighlightSource
import com.example.freebook.model.ActiveWordHighlight
import com.example.freebook.model.AlignmentProvider
import com.example.freebook.model.AlignmentStatus
import com.example.freebook.model.AudiobookChapterMapping
import com.example.freebook.model.AudiobookMappingResolver
import com.example.freebook.model.AudiobookPlaybackState
import com.example.freebook.model.AudiobookTrack
import com.example.freebook.model.AudiobookUiState
import com.example.freebook.model.PackedWordTimings
import com.example.freebook.model.SyncAnchor
import com.google.common.util.concurrent.ListenableFuture
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
import java.io.File
import java.util.Locale

data class AudiobookClipSnapshot(
    val trackTitle: String,
    val timestampLabel: String,
    val audioPositionMs: Long,
    val bookPageNumberOneBased: Int,
    val bookCharOffset: Int,
    val quotedText: String
)

/**
 * Coordinates Media3 multi-file audiobook playback with the unified [SyncResolver]:
 *
 * - Priority 1 (Word-Aligned via Local MMS-FA or future Cloud Pro): `O(log N)` binary search
 *   into cached [PackedWordTimings] -> feeds the exact same GPU highlight pill used by TTS!
 * - Priority 2 (Free Tier Instant Sync — zero download, zero AI): piecewise linear interpolation
 *   between user [SyncAnchor]s across punctuation-weighted [ChapterWeightDistribution] -> auto-turns
 *   EPUB/PDF pages smoothly right away!
 * - Supports manual scroll detach guard ("Return to Audio"), bidirectional Text -> Audio seeking,
 *   1-tap "Sync Here" anchor calibration, speed control, sleep timer, and Clip capture.
 */
class AudiobookSyncController(
    private val appContext: Context,
    private val repository: AudiobookRepository,
    private val packManager: LocalMmsFaPackManager,
    private val onWillStartPlayback: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(AudiobookUiState())
    val uiState: StateFlow<AudiobookUiState> = _uiState.asStateFlow()

    private var activeBook: SyncableBook? = null
    private var currentBundle: BookAudiobookBundle = BookAudiobookBundle()
    private var orderedTracks: List<AudiobookTrack> = emptyList()
    private var trackGlobalOffsetsMs: LongArray = LongArray(0)
    private var totalPlaylistDurationMs: Long = 0L

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: Player? = null
    private var ownsDirectExoPlayer: Boolean = false
    private var loadedPlaylistSignature: String = ""

    private var bundleJob: Job? = null
    private var packStatusJob: Job? = null
    private var syncTickerJob: Job? = null
    private var sleepTimerJob: Job? = null

    // Runtime SyncResolver caches keyed by mappingId
    private val packedTimingsCache = HashMap<String, PackedWordTimings>()
    private val weightDistCache = HashMap<String, ChapterWeightDistribution>()
    private var lastDispatchedWordIdx: Int = -1
    private var lastDispatchedMappingId: String? = null
    private var lastAutoPageIndex: Int = -1
    private var lastAutoCharOffset: Int = -1
    private var lastProgrammaticSeekMs: Long = 0L
    private var highlightSeq: Long = 0L
    private var lastReaderPageIndex: Int = -1
    private var lastReaderStartChar: Int = -1
    private var lastReaderEndChar: Int = -1

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startSyncTicker()
            } else {
                saveResumeStateAsync()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshPositionSnapshot()
            maybePrefetchNextChapterAlignment()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshPositionSnapshot()
            if (playbackState == Player.STATE_ENDED) {
                _uiState.update { it.copy(isPlaying = false, activeHighlight = null) }
                saveResumeStateAsync()
            }
        }
    }

    init {
        connectToPlaybackService()
        packStatusJob = scope.launch {
            packManager.status.collect { st ->
                _uiState.update {
                    it.copy(
                        isLocalPackInstalled = st.isInstalled,
                        localPackDownloadProgress = st.downloadProgressPercent,
                        statusNotice = st.statusMessage ?: it.statusNotice
                    )
                }
            }
        }
    }

    private fun connectToPlaybackService() {
        val token = SessionToken(appContext, ComponentName(appContext, AudiobookPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller != null) {
                    val previous = player
                    if (previous != null && ownsDirectExoPlayer) {
                        previous.removeListener(playerListener)
                        previous.release()
                        ownsDirectExoPlayer = false
                    }
                    player = controller
                    controller.addListener(playerListener)
                    syncPlaylistIntoPlayer(autoRestoreState = true)
                }
            },
            ContextCompat.getMainExecutor(appContext)
        )
    }

    private fun ensurePlayerReady(): Player {
        player?.let { return it }
        // Instant local ExoPlayer fallback if MediaSessionService bind hasn't completed yet
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        val exo = ExoPlayer.Builder(appContext)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(playerListener)
        player = exo
        ownsDirectExoPlayer = true
        return exo
    }

    fun bindBook(syncableBook: SyncableBook?) {
        val newId = syncableBook?.book?.id
        if (activeBook?.book?.id == newId && syncableBook != null) {
            activeBook = syncableBook
            return
        }

        saveResumeStateAsync()
        pause()
        bundleJob?.cancel()
        packedTimingsCache.clear()
        weightDistCache.clear()
        loadedPlaylistSignature = ""
        lastDispatchedWordIdx = -1
        lastDispatchedMappingId = null
        activeBook = syncableBook

        if (syncableBook == null) {
            _uiState.value = AudiobookUiState(
                isLocalPackInstalled = packManager.isModelInstalled()
            )
            return
        }

        val bookId = syncableBook.book.id
        _uiState.update {
            it.copy(
                bookId = bookId,
                activeHighlight = null,
                isUserDetachedFromSync = false,
                isLocalPackInstalled = packManager.isModelInstalled()
            )
        }

        bundleJob = scope.launch {
            repository.observeBookBundle(bookId).collect { bundle ->
                currentBundle = bundle
                rebuildPlaylistOrder(bundle)
                // Invalidate cached timings/weights for mappings whose alignment updated
                bundle.alignments.forEach { (mappingId, rec) ->
                    if (rec.status == AlignmentStatus.READY || rec.status == AlignmentStatus.NEEDS_CLOUD_RECOMMENDED) {
                        packedTimingsCache.remove(mappingId)
                    }
                }
                _uiState.update { state ->
                    state.copy(
                        bookId = bookId,
                        groups = bundle.groups,
                        tracks = orderedTracks,
                        mappings = bundle.mappings,
                        alignments = bundle.alignments,
                        totalDurationMs = totalPlaylistDurationMs
                    )
                }
                syncPlaylistIntoPlayer(autoRestoreState = loadedPlaylistSignature.isEmpty())
            }
        }
    }

    fun updateBookHandle(syncableBook: SyncableBook) {
        activeBook = syncableBook
    }

    fun togglePlayPause() {
        if (orderedTracks.isEmpty()) return
        val p = ensurePlayerReady()
        syncPlaylistIntoPlayer(autoRestoreState = false)
        if (p.isPlaying) {
            pause()
        } else {
            onWillStartPlayback()
            p.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed)
            p.prepare()
            p.play()
            startSyncTicker()
            maybePrefetchNextChapterAlignment()
        }
    }

    fun pause() {
        player?.pause()
        syncTickerJob?.cancel()
        _uiState.update {
            it.copy(
                isPlaying = false,
                activeHighlight = it.activeHighlight?.copy(isPaused = true)
            )
        }
        saveResumeStateAsync()
    }

    fun stopAndClearHighlight() {
        pause()
        _uiState.update { it.copy(activeHighlight = null) }
    }

    fun skipByMs(deltaMs: Long) {
        val p = player ?: return
        if (orderedTracks.isEmpty()) return
        val currentGlobal = computeCurrentGlobalPositionMs(p)
        seekToGlobalPositionMs((currentGlobal + deltaMs).coerceIn(0L, totalPlaylistDurationMs.coerceAtLeast(0L)))
    }

    fun seekToFraction(fraction: Float) {
        if (totalPlaylistDurationMs <= 0L) return
        val target = (totalPlaylistDurationMs * fraction.coerceIn(0f, 1f)).toLong()
        seekToGlobalPositionMs(target)
    }

    fun seekToGlobalPositionMs(globalMs: Long) {
        val p = ensurePlayerReady()
        syncPlaylistIntoPlayer(autoRestoreState = false)
        if (orderedTracks.isEmpty()) return

        val clamped = globalMs.coerceIn(0L, totalPlaylistDurationMs.coerceAtLeast(0L))
        var targetIdx = 0
        for (i in trackGlobalOffsetsMs.indices) {
            if (trackGlobalOffsetsMs[i] <= clamped) {
                targetIdx = i
            } else {
                break
            }
        }
        val offsetInTrack = (clamped - trackGlobalOffsetsMs[targetIdx]).coerceAtLeast(0L)
        p.seekTo(targetIdx, offsetInTrack)
        lastDispatchedWordIdx = -1
        refreshPositionSnapshot()
        resolveAndDispatchSyncNow(forcePageJump = true)
    }

    fun playTrack(trackId: String, autoPlay: Boolean = true) {
        val idx = orderedTracks.indexOfFirst { it.id == trackId }
        if (idx < 0) return
        val p = ensurePlayerReady()
        syncPlaylistIntoPlayer(autoRestoreState = false)
        p.seekTo(idx, 0L)
        if (autoPlay) {
            onWillStartPlayback()
            p.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed)
            p.prepare()
            p.play()
            startSyncTicker()
        }
        lastDispatchedWordIdx = -1
        refreshPositionSnapshot()
        resolveAndDispatchSyncNow(forcePageJump = true)
    }

    fun playGroup(groupId: String, autoPlay: Boolean = true) {
        val firstTrack = orderedTracks.firstOrNull { it.groupId == groupId } ?: return
        playTrack(firstTrack.id, autoPlay)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        player?.playbackParameters = PlaybackParameters(clamped)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        saveResumeStateAsync()
    }

    fun setFollowSyncEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                followSyncEnabled = enabled,
                isUserDetachedFromSync = false
            )
        }
        if (enabled) {
            resolveAndDispatchSyncNow(forcePageJump = true)
        }
        saveResumeStateAsync()
    }

    /**
     * Called when the user manually swipes/scrolls/jumps the book while audiobook is playing.
     * Pauses auto-follow gracefully instead of fighting the user's finger.
     */
    fun onUserManuallyScrolledBook(fromExplicitJump: Boolean = false) {
        if (!_uiState.value.isPlaying || !_uiState.value.followSyncEnabled) return
        if (!fromExplicitJump && System.currentTimeMillis() - lastProgrammaticSeekMs < 800L) return
        _uiState.update {
            it.copy(
                isUserDetachedFromSync = true,
                activeHighlight = it.activeHighlight?.copy(shouldAutoFollow = false)
            )
        }
    }

    /**
     * Returns true if an active (non-overridden) audiobook mapping covers `(pageIndex, charOffset)`.
     */
    fun hasMappingForPosition(pageIndex: Int, charOffset: Int): Boolean {
        val book = activeBook ?: return false
        if (orderedTracks.isEmpty() || currentBundle.mappings.isEmpty()) return false
        val effectiveMappings = AudiobookMappingResolver.activeMappings(currentBundle.mappings)
        return effectiveMappings.any { m ->
            if (book.isPdf) {
                pageIndex in m.bookStartPageIndex..m.bookEndPageIndex
            } else {
                charOffset in m.bookStartCharOffset..m.bookEndCharOffset
            }
        }
    }

    /**
     * Called when the reader's visible page / character range changes (after a swipe, scroll, or jump).
     * If the user manually navigated BACK onto the page where the audiobook is currently playing,
     * automatically clears [AudiobookUiState.isUserDetachedFromSync] so the return pill hides and sync resumes.
     */
    fun onReaderViewportChanged(pageIndex: Int, startChar: Int, endChar: Int) {
        lastReaderPageIndex = pageIndex
        lastReaderStartChar = startChar
        lastReaderEndChar = endChar.coerceAtLeast(startChar)

        val book = activeBook ?: return
        val state = _uiState.value
        if (!state.isUserDetachedFromSync || !state.isPlaying) return
        if (isPositionVisibleInViewport(
                isPdf = book.isPdf,
                audioPageIndex = state.liveAudioPageIndex,
                audioCharOffset = state.liveAudioCharOffset,
                readerPageIndex = pageIndex,
                readerStartChar = startChar,
                readerEndChar = lastReaderEndChar
            )
        ) {
            lastAutoPageIndex = state.liveAudioPageIndex
            lastAutoCharOffset = state.liveAudioCharOffset
            _uiState.update {
                it.copy(
                    isUserDetachedFromSync = false,
                    activeHighlight = it.activeHighlight?.copy(shouldAutoFollow = true)
                )
            }
        }
    }

    private fun isPositionVisibleInViewport(
        isPdf: Boolean,
        audioPageIndex: Int,
        audioCharOffset: Int,
        readerPageIndex: Int,
        readerStartChar: Int,
        readerEndChar: Int
    ): Boolean {
        return if (isPdf) {
            audioPageIndex >= 0 && readerPageIndex >= 0 && audioPageIndex == readerPageIndex
        } else {
            audioCharOffset >= 0 && readerStartChar >= 0 &&
                audioCharOffset in (readerStartChar - 12)..(maxOf(readerEndChar, readerStartChar + 1) + 12)
        }
    }

    /**
     * Snaps the reader immediately back to the live audiobook word/page ("Return to Audio").
     */
    fun reattachSyncToAudio() {
        _uiState.update { it.copy(isUserDetachedFromSync = false, followSyncEnabled = true) }
        lastAutoPageIndex = -1
        lastAutoCharOffset = -1
        resolveAndDispatchSyncNow(forcePageJump = true)
    }

    /**
     * 1-tap "Sync Here" anchor calibration: pins current live audio timestamp to the user's
     * current book position (page/charOffset) inside the active mapped chapter or track.
     */
    fun calibrateSyncHere(currentPageIndex: Int, currentCharOffset: Int): Boolean {
        val book = activeBook ?: return false
        val p = player ?: return false
        val activeIdx = p.currentMediaItemIndex.takeIf { it in orderedTracks.indices } ?: return false
        val track = orderedTracks[activeIdx]
        val mapping = AudiobookMappingResolver.resolveForTrack(track, currentBundle.mappings) ?: return false
        val chapterAudioMs = if (mapping.isTrackLevel) {
            p.currentPosition.coerceAtLeast(0L)
        } else {
            computeChapterAudioPositionMs(track.groupId, activeIdx, p.currentPosition)
        }

        val newAnchor = SyncAnchor(
            audioMs = chapterAudioMs,
            bookPageIndex = currentPageIndex,
            bookCharOffset = currentCharOffset
        )
        val mergedAnchors = SyncAnchor.mergeMonotonic(
            existing = mapping.anchors,
            newAnchor = newAnchor,
            isPdf = book.isPdf
        )

        // Immediately update in-memory bundle and last-synced position BEFORE clearing detach state,
        // so the 40ms sync ticker NEVER reads stale pre-anchor Room state and jumps back a page.
        val updatedMappings = currentBundle.mappings.map { m ->
            if (m.id == mapping.id) m.copy(anchors = mergedAnchors) else m
        }
        currentBundle = currentBundle.copy(mappings = updatedMappings)
        lastAutoPageIndex = currentPageIndex
        lastAutoCharOffset = currentCharOffset
        lastProgrammaticSeekMs = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                mappings = updatedMappings,
                isUserDetachedFromSync = false,
                liveAudioPageIndex = currentPageIndex,
                liveAudioCharOffset = currentCharOffset,
                statusNotice = "Synced audio (${formatMs(chapterAudioMs)}) to current position"
            )
        }

        scope.launch {
            repository.addSyncAnchor(
                mappingId = mapping.id,
                anchor = newAnchor,
                isPdf = book.isPdf
            )
        }
        return true
    }

    /**
     * Bidirectional Text -> Audio seek ("Play Audio from Here" / fast double-tap on word):
     * jumps audiobook to exact word millisecond if aligned, or proportional estimate if unaligned.
     * Respects Category > Track mapping precedence.
     * Returns `true` if a mapped audiobook chapter/track matched the tapped book position.
     */
    fun seekAudioToBookPosition(pageIndex: Int, charOffset: Int, startPlaying: Boolean = true): Boolean {
        val book = activeBook ?: return false
        val effectiveMappings = AudiobookMappingResolver.activeMappings(currentBundle.mappings)
        val mapping = effectiveMappings.firstOrNull { m ->
            if (book.isPdf) {
                pageIndex in m.bookStartPageIndex..m.bookEndPageIndex
            } else {
                charOffset in m.bookStartCharOffset..m.bookEndCharOffset
            }
        } ?: return false

        val targetTracks = if (mapping.isTrackLevel) {
            orderedTracks.filter { it.id == mapping.trackId }
        } else {
            orderedTracks.filter { it.groupId == mapping.groupId }
        }
        if (targetTracks.isEmpty()) return false
        val firstGlobalTrackIdx = orderedTracks.indexOfFirst { it.id == targetTracks.first().id }
        if (firstGlobalTrackIdx < 0) return false

        _uiState.update { it.copy(isUserDetachedFromSync = false, followSyncEnabled = true) }
        lastProgrammaticSeekMs = System.currentTimeMillis()

        scope.launch {
            val startGlobalMs = trackGlobalOffsetsMs[firstGlobalTrackIdx]
            val scopeDurationMs = targetTracks.sumOf { it.effectiveDurationMs }.coerceAtLeast(1L)

            // 1. Exact ms lookup if word alignment is cached
            val timings = getOrLoadTimings(mapping.id)
            val exactChapterMs = if (!timings.isEmpty()) {
                timings.findAudioMsForBookPosition(pageIndex, charOffset)
            } else {
                null
            }

            val chapterMs = exactChapterMs ?: run {
                // 2. Proportional estimate using weight distribution + anchors
                val dist = getOrBuildWeightDistribution(book, mapping)
                val targetW = dist.weightAtPosition(pageIndex, charOffset)
                val frac = (targetW.toDouble() / dist.totalWeight.toDouble()).coerceIn(0.0, 1.0)
                (frac * scopeDurationMs).toLong()
            }

            seekToGlobalPositionMs(startGlobalMs + chapterMs)
            if (startPlaying && !_uiState.value.isPlaying) {
                togglePlayPause()
            }
        }
        return true
    }

    fun captureCurrentAudioClip(
        currentPageNumberOneBased: Int,
        currentCharOffset: Int,
        currentVisibleSnippet: String
    ): AudiobookClipSnapshot? {
        val p = player ?: return null
        val idx = p.currentMediaItemIndex.takeIf { it in orderedTracks.indices } ?: return null
        val track = orderedTracks[idx]
        val posMs = p.currentPosition.coerceAtLeast(0L)
        val hlWord = _uiState.value.activeHighlight?.wordText.orEmpty()
        val quote = currentVisibleSnippet.trim().take(220).ifBlank { hlWord }
        return AudiobookClipSnapshot(
            trackTitle = track.title,
            timestampLabel = formatMs(posMs),
            audioPositionMs = posMs,
            bookPageNumberOneBased = currentPageNumberOneBased.coerceAtLeast(1),
            bookCharOffset = currentCharOffset.coerceAtLeast(0),
            quotedText = quote
        )
    }

    fun enqueueChapterAlignment(
        mappingId: String,
        provider: AlignmentProvider = AlignmentProvider.LOCAL_MMS_FA,
        priority: Boolean = true
    ) {
        val bookId = activeBook?.book?.id ?: return
        ChapterAlignmentWorker.enqueueChapterAlignment(
            context = appContext,
            bookId = bookId,
            mappingId = mappingId,
            provider = provider,
            isActiveChapterPriority = priority
        )
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
        saveResumeStateAsync()
        syncTickerJob?.cancel()
        sleepTimerJob?.cancel()
        bundleJob?.cancel()
        packStatusJob?.cancel()
        player?.removeListener(playerListener)
        if (ownsDirectExoPlayer) {
            player?.release()
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        player = null
        scope.cancel()
    }

    private fun rebuildPlaylistOrder(bundle: BookAudiobookBundle) {
        val groupRank = bundle.groups.mapIndexed { idx, g -> g.id to idx }.toMap()
        orderedTracks = bundle.tracks.sortedWith(
            compareBy<AudiobookTrack> { groupRank[it.groupId] ?: Int.MAX_VALUE }
                .thenBy { it.orderIndex }
        )
        val offsets = LongArray(orderedTracks.size)
        var acc = 0L
        for (i in orderedTracks.indices) {
            offsets[i] = acc
            acc += orderedTracks[i].effectiveDurationMs
        }
        trackGlobalOffsetsMs = offsets
        totalPlaylistDurationMs = acc
    }

    private fun syncPlaylistIntoPlayer(autoRestoreState: Boolean) {
        val p = player ?: return
        val signature = orderedTracks.joinToString("|") {
            "${it.id}:${it.sourceUri}:${it.clipStartMs}:${it.clipEndMs}"
        }
        if (signature == loadedPlaylistSignature) return
        loadedPlaylistSignature = signature

        if (orderedTracks.isEmpty()) {
            p.clearMediaItems()
            return
        }

        val bookTitle = activeBook?.book?.title.orEmpty()
        val items = orderedTracks.map { track ->
            val uri = if (track.sourceUri.startsWith("/")) {
                Uri.fromFile(File(track.sourceUri))
            } else {
                Uri.parse(track.sourceUri)
            }
            val builder = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setAlbumTitle(bookTitle)
                        .build()
                )
            if (track.clipEndMs > track.clipStartMs) {
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(track.clipStartMs)
                        .setEndPositionMs(track.clipEndMs)
                        .build()
                )
            }
            builder.build()
        }

        p.setMediaItems(items, /* resetPosition = */ true)
        p.prepare()

        if (autoRestoreState) {
            val bookId = activeBook?.book?.id ?: return
            scope.launch {
                val saved = repository.loadPlaybackState(bookId) ?: return@launch
                _uiState.update {
                    it.copy(
                        playbackSpeed = saved.playbackSpeed,
                        followSyncEnabled = saved.followSyncEnabled
                    )
                }
                p.playbackParameters = PlaybackParameters(saved.playbackSpeed)
                val savedIdx = orderedTracks.indexOfFirst { it.id == saved.lastTrackId }
                if (savedIdx >= 0) {
                    p.seekTo(savedIdx, saved.lastPositionMs.coerceAtLeast(0L))
                }
                refreshPositionSnapshot()
            }
        }
    }

    private fun startSyncTicker() {
        syncTickerJob?.cancel()
        syncTickerJob = scope.launch {
            while (isActive) {
                refreshPositionSnapshot()
                resolveAndDispatchSyncNow(forcePageJump = false)
                delay(40L) // ~25Hz sync clock; GPU animations interpolate smoothly at 60/120Hz!
            }
        }
    }

    private fun refreshPositionSnapshot() {
        val p = player ?: return
        if (orderedTracks.isEmpty()) return
        val idx = p.currentMediaItemIndex.coerceIn(0, orderedTracks.lastIndex)
        val track = orderedTracks[idx]
        val globalPos = computeCurrentGlobalPositionMs(p)
        val chPos = computeChapterAudioPositionMs(track.groupId, idx, p.currentPosition)
        val chDur = orderedTracks
            .filter { it.groupId == track.groupId }
            .sumOf { it.effectiveDurationMs }
            .coerceAtLeast(p.duration.coerceAtLeast(1L))

        _uiState.update {
            it.copy(
                activeTrackId = track.id,
                activeGroupId = track.groupId,
                currentPositionMs = globalPos,
                totalDurationMs = totalPlaylistDurationMs.coerceAtLeast(chDur),
                currentChapterPositionMs = chPos,
                currentChapterDurationMs = chDur
            )
        }
    }

    private fun resolveAndDispatchSyncNow(forcePageJump: Boolean) {
        val state = _uiState.value
        val book = activeBook ?: return
        val p = player ?: return
        if (orderedTracks.isEmpty() || !state.followSyncEnabled) return

        val idx = p.currentMediaItemIndex.takeIf { it in orderedTracks.indices } ?: return
        val track = orderedTracks[idx]
        val mapping = AudiobookMappingResolver.resolveForTrack(track, currentBundle.mappings) ?: return
        val chapterAudioMs = if (mapping.isTrackLevel) {
            p.currentPosition.coerceAtLeast(0L)
        } else {
            computeChapterAudioPositionMs(track.groupId, idx, p.currentPosition)
        }
        val chapterDurationMs = if (mapping.isTrackLevel) {
            track.effectiveDurationMs.coerceAtLeast(p.duration.coerceAtLeast(1L))
        } else {
            orderedTracks
                .filter { it.groupId == track.groupId }
                .sumOf { it.effectiveDurationMs }
                .coerceAtLeast(p.duration.coerceAtLeast(1L))
        }

        scope.launch {
            val alignRecord = currentBundle.alignments[mapping.id]
            val hasWordAlignment = alignRecord != null &&
                (alignRecord.status == AlignmentStatus.READY ||
                    alignRecord.status == AlignmentStatus.NEEDS_CLOUD_RECOMMENDED)

            if (hasWordAlignment) {
                val timings = getOrLoadTimings(mapping.id)
                if (!timings.isEmpty()) {
                    val found = timings.findIndexAtAudioMs(chapterAudioMs)
                    if (found < 0) {
                        // Audio is inside an intro/outro/skipped gap: gently fade out word pill
                        if (_uiState.value.activeHighlight != null) {
                            _uiState.update { it.copy(activeHighlight = null) }
                        }
                        return@launch
                    }

                    val wordStartMs = timings.startMs[found]
                    val wordEndMs = timings.endMs[found].coerceAtLeast(wordStartMs + 60)
                    val wordProgress = ((chapterAudioMs - wordStartMs).toFloat() / (wordEndMs - wordStartMs).toFloat())
                        .coerceIn(0f, 1f)

                    if (mapping.id != lastDispatchedMappingId || found != lastDispatchedWordIdx || forcePageJump) {
                        lastDispatchedMappingId = mapping.id
                        lastDispatchedWordIdx = found
                        val pageIdx = timings.pageIndices[found]
                        val startChar = timings.startChars[found]
                        val endChar = timings.endChars[found]

                        val wasDetached = _uiState.value.isUserDetachedFromSync
                        val caughtUpToViewport = wasDetached && isPositionVisibleInViewport(
                            isPdf = book.isPdf,
                            audioPageIndex = pageIdx,
                            audioCharOffset = startChar,
                            readerPageIndex = lastReaderPageIndex,
                            readerStartChar = lastReaderStartChar,
                            readerEndChar = lastReaderEndChar
                        )
                        val isStillDetached = wasDetached && !caughtUpToViewport
                        val shouldFollowNow = forcePageJump ||
                            (_uiState.value.followSyncEnabled && !isStillDetached)
                        val hl = ActiveWordHighlight(
                            source = ActiveHighlightSource.AUDIOBOOK,
                            pageIndex = pageIdx,
                            startCharOffset = startChar,
                            endCharOffset = endChar,
                            estimatedDurationMs = (wordEndMs - wordStartMs).toLong().coerceIn(80L, 900L),
                            progressFraction = wordProgress,
                            isPaused = !p.isPlaying,
                            shouldAutoFollow = shouldFollowNow,
                            sequenceId = ++highlightSeq
                        )
                        _uiState.update {
                            it.copy(
                                activeHighlight = hl,
                                liveAudioPageIndex = pageIdx,
                                liveAudioCharOffset = startChar,
                                isUserDetachedFromSync = if (caughtUpToViewport) false else it.isUserDetachedFromSync
                            )
                        }
                        if (shouldFollowNow && (forcePageJump || pageIdx != lastAutoPageIndex)) {
                            lastAutoPageIndex = pageIdx
                            lastAutoCharOffset = startChar
                            lastProgrammaticSeekMs = System.currentTimeMillis()
                        }
                        book.seekToPosition(
                            SyncTarget(
                                pageIndex = pageIdx,
                                startCharOffset = startChar,
                                endCharOffset = endChar,
                                highlight = hl,
                                shouldScrollOrTurnPage = shouldFollowNow
                            )
                        )
                    }
                    return@launch
                }
            }

            // Tier-0 Free Instant Sync: piecewise linear interpolation across Sync Anchors + weighted chapter distribution
            val dist = getOrBuildWeightDistribution(book, mapping)
            val targetWeight = interpolateWeightWithAnchors(
                audioMs = chapterAudioMs,
                chapterDurationMs = chapterDurationMs,
                anchors = mapping.anchors,
                dist = dist
            )
            val (targetPage, targetChar) = dist.resolveWeightToPosition(targetWeight)

            val prevLivePage = _uiState.value.liveAudioPageIndex
            val prevLiveChar = _uiState.value.liveAudioCharOffset
            val livePositionChanged = targetPage != prevLivePage || targetChar != prevLiveChar
            val wasDetached = _uiState.value.isUserDetachedFromSync
            // Auto-reattach if live audio naturally crossed into the page the user is viewing
            val caughtUpToViewport = wasDetached && livePositionChanged && isPositionVisibleInViewport(
                isPdf = book.isPdf,
                audioPageIndex = targetPage,
                audioCharOffset = targetChar,
                readerPageIndex = lastReaderPageIndex,
                readerStartChar = lastReaderStartChar,
                readerEndChar = lastReaderEndChar
            )
            val isStillDetached = wasDetached && !caughtUpToViewport

            if (livePositionChanged || caughtUpToViewport) {
                _uiState.update {
                    it.copy(
                        liveAudioPageIndex = targetPage,
                        liveAudioCharOffset = targetChar,
                        isUserDetachedFromSync = if (caughtUpToViewport) false else it.isUserDetachedFromSync
                    )
                }
            }

            if (isStillDetached && !forcePageJump) {
                return@launch
            }

            val changed = if (book.isPdf) {
                targetPage != lastAutoPageIndex
            } else {
                kotlin.math.abs(targetChar - lastAutoCharOffset) > 120
            }

            if (changed || forcePageJump) {
                lastAutoPageIndex = targetPage
                lastAutoCharOffset = targetChar
                lastProgrammaticSeekMs = System.currentTimeMillis()
                book.seekToPosition(
                    SyncTarget(
                        pageIndex = targetPage,
                        startCharOffset = targetChar,
                        endCharOffset = targetChar,
                        highlight = null,
                        shouldScrollOrTurnPage = true
                    )
                )
            }
        }
    }

    /**
     * Piecewise linear interpolation between user Sync Anchors `(0ms -> 0 weight) ... (anchor_i) ... (T -> totalWeight)`.
     */
    private fun interpolateWeightWithAnchors(
        audioMs: Long,
        chapterDurationMs: Long,
        anchors: List<SyncAnchor>,
        dist: ChapterWeightDistribution
    ): Int {
        val totalDur = chapterDurationMs.coerceAtLeast(1L)
        val totalW = dist.totalWeight
        val clampedMs = audioMs.coerceIn(0L, totalDur)

        if (anchors.isEmpty()) {
            return ((clampedMs.toDouble() / totalDur.toDouble()) * totalW).toInt()
        }

        // Build ordered knot points (timeMs -> weight)
        var prevTime = 0L
        var prevWeight = 0
        for (a in anchors) {
            val t = a.audioMs.coerceIn(0L, totalDur)
            val w = dist.weightAtPosition(a.bookPageIndex, a.bookCharOffset)
            if (clampedMs <= t) {
                val spanT = (t - prevTime).coerceAtLeast(1L)
                val frac = (clampedMs - prevTime).toDouble() / spanT.toDouble()
                return (prevWeight + frac * (w - prevWeight)).toInt().coerceIn(0, totalW)
            }
            if (t >= prevTime && w >= prevWeight) {
                prevTime = t
                prevWeight = w
            }
        }

        val tailT = (totalDur - prevTime).coerceAtLeast(1L)
        val frac = (clampedMs - prevTime).toDouble() / tailT.toDouble()
        return (prevWeight + frac * (totalW - prevWeight)).toInt().coerceIn(0, totalW)
    }

    private fun maybePrefetchNextChapterAlignment() {
        if (!packManager.isModelInstalled()) return
        val state = _uiState.value
        val activeTrackId = state.activeTrackId ?: return
        val activeIdx = orderedTracks.indexOfFirst { it.id == activeTrackId }
        if (activeIdx < 0) return
        val activeTrack = orderedTracks[activeIdx]

        // Lazy alignment: active track/category first (expedited), then next track/category when idle
        val activeMap = AudiobookMappingResolver.resolveForTrack(activeTrack, state.mappings)
        if (activeMap != null) {
            val rec = state.alignments[activeMap.id]
            if (rec == null || rec.status == AlignmentStatus.NONE) {
                enqueueChapterAlignment(activeMap.id, AlignmentProvider.LOCAL_MMS_FA, priority = true)
            }
        }
        val nextTrack = orderedTracks.drop(activeIdx + 1).firstOrNull { candidate ->
            val candidateMap = AudiobookMappingResolver.resolveForTrack(candidate, state.mappings)
            candidateMap != null && candidateMap.id != activeMap?.id
        } ?: return
        val nextMap = AudiobookMappingResolver.resolveForTrack(nextTrack, state.mappings) ?: return
        val nextRec = state.alignments[nextMap.id]
        if (nextRec == null || nextRec.status == AlignmentStatus.NONE) {
            enqueueChapterAlignment(nextMap.id, AlignmentProvider.LOCAL_MMS_FA, priority = false)
        }
    }

    private suspend fun getOrLoadTimings(mappingId: String): PackedWordTimings {
        packedTimingsCache[mappingId]?.let { return it }
        val loaded = repository.loadPackedTimings(mappingId)
        if (!loaded.isEmpty()) {
            packedTimingsCache[mappingId] = loaded
        }
        return loaded
    }

    private suspend fun getOrBuildWeightDistribution(
        book: SyncableBook,
        mapping: AudiobookChapterMapping
    ): ChapterWeightDistribution {
        val key = "${mapping.id}:${mapping.bookStartPageIndex}:${mapping.bookEndPageIndex}:${mapping.bookStartCharOffset}:${mapping.bookEndCharOffset}"
        weightDistCache[key]?.let { return it }
        val built = book.buildWeightDistribution(mapping)
        weightDistCache[key] = built
        return built
    }

    private fun computeCurrentGlobalPositionMs(p: Player): Long {
        val idx = p.currentMediaItemIndex.takeIf { it in trackGlobalOffsetsMs.indices } ?: return 0L
        return (trackGlobalOffsetsMs[idx] + p.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun computeChapterAudioPositionMs(groupId: String, activeTrackIdx: Int, positionInTrackMs: Long): Long {
        var acc = 0L
        for (i in 0 until activeTrackIdx.coerceAtMost(orderedTracks.size)) {
            if (orderedTracks[i].groupId == groupId) {
                acc += orderedTracks[i].effectiveDurationMs
            }
        }
        return acc + positionInTrackMs.coerceAtLeast(0L)
    }

    private fun saveResumeStateAsync() {
        val bookId = activeBook?.book?.id ?: return
        val p = player ?: return
        val idx = p.currentMediaItemIndex.takeIf { it in orderedTracks.indices } ?: return
        val trackId = orderedTracks[idx].id
        val pos = p.currentPosition.coerceAtLeast(0L)
        val st = _uiState.value
        scope.launch {
            repository.savePlaybackState(
                AudiobookPlaybackState(
                    bookId = bookId,
                    lastTrackId = trackId,
                    lastPositionMs = pos,
                    playbackSpeed = st.playbackSpeed,
                    followSyncEnabled = st.followSyncEnabled,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    companion object {
        fun formatMs(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val hrs = totalSec / 3600
            val mins = (totalSec % 3600) / 60
            val secs = totalSec % 60
            return if (hrs > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
            } else {
                String.format(Locale.US, "%d:%02d", mins, secs)
            }
        }
    }
}
