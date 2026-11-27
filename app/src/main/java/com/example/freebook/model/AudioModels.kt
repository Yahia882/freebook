package com.example.freebook.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// =========================================================================
// UNIFIED WORD HIGHLIGHTING MODELS (SHARED BY TTS & AUDIOBOOK ALIGNMENT)
// =========================================================================

enum class ActiveHighlightSource {
    NONE,
    TTS,
    AUDIOBOOK
}

/**
 * Represents the currently spoken word across either EPUB (canonical char offsets)
 * or PDF (0-based pageIndex + page-local char offsets).
 * Consumed by GPU-composited overlays in both ReadiumWebViewNavigator and CustomPdfReader.
 */
data class ActiveWordHighlight(
    val source: ActiveHighlightSource = ActiveHighlightSource.NONE,
    val pageIndex: Int = 0,
    val startCharOffset: Int = 0,
    val endCharOffset: Int = 0,
    val wordText: String = "",
    val estimatedDurationMs: Long = 180L,
    val progressFraction: Float = 0f,
    val isPaused: Boolean = false,
    val shouldAutoFollow: Boolean = true,
    val sequenceId: Long = 0L
) {
    val isActive: Boolean
        get() = source != ActiveHighlightSource.NONE && endCharOffset > startCharOffset
}

// =========================================================================
// TTS ENGINE, MODEL, VOICE & STATE MODELS
// =========================================================================

object TtsEngineIds {
    const val OFF = "off"
    const val BUILT_IN_SYSTEM = "builtin_system"
}

/**
 * Pluggable TTS model descriptor displayed in the TTS Dock's "Model" picker.
 * Future 3rd-party engines (Kokoro, Piper, Sherpa-ONNX, cloud TTS) register here seamlessly.
 */
data class TtsModelDescriptor(
    val id: String,
    val name: String,
    val shortLabel: String,
    val description: String,
    val isAvailable: Boolean = true,
    val isOffOption: Boolean = false
)

data class TtsVoiceInfo(
    val id: String,
    val engineId: String,
    val displayName: String,
    val shortName: String,
    val localeTag: String,
    val languageDisplay: String,
    val isOffline: Boolean,
    val qualityScore: Int,
    val matchesBookLanguage: Boolean
)

enum class TtsPlaybackStatus {
    OFF,
    INITIALIZING,
    READY,
    PLAYING,
    PAUSED,
    MISSING_LANGUAGE_DATA,
    ERROR
}

data class TtsUiState(
    val selectedModelId: String = TtsEngineIds.OFF,
    val selectedModelName: String = "Off",
    val availableModels: List<TtsModelDescriptor> = emptyList(),
    val status: TtsPlaybackStatus = TtsPlaybackStatus.OFF,
    val selectedVoice: TtsVoiceInfo? = null,
    val availableVoices: List<TtsVoiceInfo> = emptyList(),
    val speechRate: Float = 1.0f,
    val sleepTimerRemainingMs: Long? = null,
    val activeHighlight: ActiveWordHighlight? = null,
    val progressFraction: Float = 0f,
    val currentTimeLabel: String = "0:00",
    val remainingTimeLabel: String = "0:00",
    val isUserDetachedFromSync: Boolean = false,
    val liveTtsPageIndex: Int = -1,
    val liveTtsCharOffset: Int = -1,
    val missingLanguageTag: String? = null,
    val errorMessage: String? = null
) {
    val isOff: Boolean get() = selectedModelId == TtsEngineIds.OFF || status == TtsPlaybackStatus.OFF
    val isPlaying: Boolean get() = status == TtsPlaybackStatus.PLAYING
    val isPaused: Boolean get() = status == TtsPlaybackStatus.PAUSED
}

// =========================================================================
// AUDIOBOOK GROUPS, MULTI-FILE TRACKS, MAPPINGS & ALIGNMENT MODELS
// =========================================================================

data class AudiobookGroup(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val title: String,
    val orderIndex: Int = 0,
    val isCollapsed: Boolean = false,
    val isCategory: Boolean = false
)

data class AudiobookTrack(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val groupId: String,
    val sourceUri: String,
    val isCopiedLocally: Boolean = false,
    val title: String,
    val durationMs: Long = 0L,
    val clipStartMs: Long = 0L,
    val clipEndMs: Long = 0L,
    val orderIndex: Int = 0,
    val audioHash: String = ""
) {
    val effectiveDurationMs: Long
        get() = when {
            clipEndMs > clipStartMs -> clipEndMs - clipStartMs
            durationMs > 0L -> durationMs
            else -> 0L
        }
}

data class SyncAnchor(
    val audioMs: Long,
    val bookPageIndex: Int,
    val bookCharOffset: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun serializeList(anchors: List<SyncAnchor>): String {
            if (anchors.isEmpty()) return "[]"
            val arr = JSONArray()
            for (a in anchors) {
                arr.put(
                    JSONObject().apply {
                        put("audioMs", a.audioMs)
                        put("page", a.bookPageIndex)
                        put("char", a.bookCharOffset)
                        put("ts", a.createdAt)
                    }
                )
            }
            return arr.toString()
        }

        fun deserializeList(raw: String): List<SyncAnchor> {
            if (raw.isBlank() || raw == "[]") return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(
                            SyncAnchor(
                                audioMs = o.optLong("audioMs", 0L),
                                bookPageIndex = o.optInt("page", 0),
                                bookCharOffset = o.optInt("char", 0),
                                createdAt = o.optLong("ts", 0L)
                            )
                        )
                    }
                }.sortedBy { it.audioMs }
            }.getOrDefault(emptyList())
        }

        /**
         * Inserts [newAnchor] while replacing nearby anchors (<3s) and pruning any stale anchors
         * whose book position conflicts with monotonic forward progression relative to [newAnchor].
         */
        fun mergeMonotonic(
            existing: List<SyncAnchor>,
            newAnchor: SyncAnchor,
            isPdf: Boolean
        ): List<SyncAnchor> {
            val newPos = if (isPdf) newAnchor.bookPageIndex else newAnchor.bookCharOffset
            val filtered = existing.filter { a ->
                if (kotlin.math.abs(a.audioMs - newAnchor.audioMs) < 3_000L) return@filter false
                val aPos = if (isPdf) a.bookPageIndex else a.bookCharOffset
                if (a.audioMs < newAnchor.audioMs) aPos <= newPos else aPos >= newPos
            }
            return (filtered + newAnchor).sortedBy { it.audioMs }
        }
    }
}

data class AudiobookChapterMapping(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val groupId: String,
    /** Empty string = category/group-level mapping (overrides track mappings in the same group). Non-empty = individual track mapping. */
    val trackId: String = "",
    val bookChapterTitle: String = "",
    val bookStartPageIndex: Int = 0,
    val bookEndPageIndex: Int = 0,
    val bookStartCharOffset: Int = 0,
    val bookEndCharOffset: Int = 0,
    val anchors: List<SyncAnchor> = emptyList()
) {
    val isTrackLevel: Boolean get() = trackId.isNotBlank()
    val isCategoryLevel: Boolean get() = trackId.isBlank()
}

object AudiobookMappingResolver {
    /**
     * Resolves the effective mapping for a single [track]:
     * a category/group-level mapping (`trackId == ""`) always overrides any individual track mapping inside that category.
     */
    fun resolveForTrack(
        track: AudiobookTrack,
        mappings: List<AudiobookChapterMapping>
    ): AudiobookChapterMapping? {
        val groupOverride = mappings.firstOrNull { it.groupId == track.groupId && it.isCategoryLevel }
        if (groupOverride != null) return groupOverride
        return mappings.firstOrNull { it.trackId == track.id }
    }

    /**
     * Returns all currently active mappings (excluding track-level mappings that are overridden by their parent category mapping).
     */
    fun activeMappings(mappings: List<AudiobookChapterMapping>): List<AudiobookChapterMapping> {
        val overriddenGroupIds = mappings
            .asSequence()
            .filter { it.isCategoryLevel }
            .map { it.groupId }
            .toSet()
        return mappings.filter { it.isCategoryLevel || it.groupId !in overriddenGroupIds }
    }
}

enum class AlignmentProvider {
    NONE,
    LOCAL_MMS_FA,
    CLOUD_PRO,
    IMPORTED
}

enum class AlignmentStatus {
    NONE,
    QUEUED,
    RUNNING,
    READY,
    NEEDS_CLOUD_RECOMMENDED,
    FAILED
}

data class ChapterAlignmentRecord(
    val mappingId: String,
    val bookId: String,
    val provider: AlignmentProvider = AlignmentProvider.NONE,
    val status: AlignmentStatus = AlignmentStatus.NONE,
    val progressPercent: Int = 0,
    val coverageRatio: Float = 0f,
    val avgConfidence: Float = 0f,
    val audioFingerprint: String = "",
    val textHash: String = "",
    val tokenizerVersion: Int = 1,
    val wordCount: Int = 0,
    val errorMessage: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Compact, zero-GC primitive parallel arrays representing word-level alignment timestamps
 * for one mapped chapter (`bookWordIndex -> startMs, endMs, pageIndex, startChar, endChar`).
 * Serialized as a GZIP binary blob in Room (~40-100 KB per hour-long chapter).
 */
class PackedWordTimings(
    val wordIndices: IntArray,
    val startMs: IntArray,
    val endMs: IntArray,
    val pageIndices: IntArray,
    val startChars: IntArray,
    val endChars: IntArray
) {
    val size: Int get() = wordIndices.size

    fun isEmpty(): Boolean = size == 0

    /**
     * O(log N) binary search for the spoken word active at [audioMs].
     * Returns index into parallel arrays, or -1 if audioMs falls in a silent/unmatched gap.
     */
    fun findIndexAtAudioMs(audioMs: Long): Int {
        val count = size
        if (count == 0) return -1
        val target = audioMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (target < startMs[0]) return -1

        var lo = 0
        var hi = count - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (startMs[mid] <= target) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return -1
        // Allow small 450ms hold past word end for smooth visual gliding between words
        return if (target <= endMs[best] + 450) best else -1
    }

    /**
     * Reverse lookup (Text -> Audio): finds the earliest timestamp matching a book position.
     */
    fun findAudioMsForBookPosition(pageIndex: Int, charOffset: Int): Long? {
        if (size == 0) return null
        for (i in 0 until size) {
            if (pageIndices[i] > pageIndex) {
                return startMs[i].toLong()
            }
            if (pageIndices[i] == pageIndex && endChars[i] >= charOffset) {
                return startMs[i].toLong()
            }
        }
        return startMs[size - 1].toLong()
    }

    fun toGzipBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            DataOutputStream(gzip).use { dos ->
                dos.writeInt(FORMAT_VERSION)
                dos.writeInt(size)
                for (i in 0 until size) {
                    dos.writeInt(wordIndices[i])
                    dos.writeInt(startMs[i])
                    dos.writeInt(endMs[i])
                    dos.writeInt(pageIndices[i])
                    dos.writeInt(startChars[i])
                    dos.writeInt(endChars[i])
                }
            }
        }
        return baos.toByteArray()
    }

    companion object {
        private const val FORMAT_VERSION = 1

        val EMPTY = PackedWordTimings(
            IntArray(0), IntArray(0), IntArray(0),
            IntArray(0), IntArray(0), IntArray(0)
        )

        fun fromGzipBytes(bytes: ByteArray?): PackedWordTimings {
            if (bytes == null || bytes.isEmpty()) return EMPTY
            return runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                    DataInputStream(gzip).use { dis ->
                        val version = dis.readInt()
                        if (version != FORMAT_VERSION) return EMPTY
                        val count = dis.readInt().coerceAtLeast(0)
                        val wi = IntArray(count)
                        val sm = IntArray(count)
                        val em = IntArray(count)
                        val pi = IntArray(count)
                        val sc = IntArray(count)
                        val ec = IntArray(count)
                        for (i in 0 until count) {
                            wi[i] = dis.readInt()
                            sm[i] = dis.readInt()
                            em[i] = dis.readInt()
                            pi[i] = dis.readInt()
                            sc[i] = dis.readInt()
                            ec[i] = dis.readInt()
                        }
                        PackedWordTimings(wi, sm, em, pi, sc, ec)
                    }
                }
            }.getOrDefault(EMPTY)
        }
    }
}

data class PdfPageMetric(
    val bookId: String,
    val pageIndex: Int,
    val charCount: Int,
    val wordCount: Int,
    val weightedCharCount: Int,
    val isScanned: Boolean,
    val bodyStartChar: Int,
    val bodyEndChar: Int
)

data class AudiobookPlaybackState(
    val bookId: String,
    val lastTrackId: String = "",
    val lastPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val followSyncEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

data class AudiobookUiState(
    val bookId: String = "",
    val groups: List<AudiobookGroup> = emptyList(),
    val tracks: List<AudiobookTrack> = emptyList(),
    val mappings: List<AudiobookChapterMapping> = emptyList(),
    val alignments: Map<String, ChapterAlignmentRecord> = emptyMap(),
    val isPlaying: Boolean = false,
    val activeTrackId: String? = null,
    val activeGroupId: String? = null,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val currentChapterPositionMs: Long = 0L,
    val currentChapterDurationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerRemainingMs: Long? = null,
    val followSyncEnabled: Boolean = true,
    val isUserDetachedFromSync: Boolean = false,
    val liveAudioPageIndex: Int = -1,
    val liveAudioCharOffset: Int = -1,
    val activeHighlight: ActiveWordHighlight? = null,
    val isLocalPackInstalled: Boolean = false,
    val localPackDownloadProgress: Int? = null,
    val statusNotice: String? = null
) {
    val hasAudio: Boolean get() = tracks.isNotEmpty()
}
