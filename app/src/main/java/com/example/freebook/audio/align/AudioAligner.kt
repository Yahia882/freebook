package com.example.freebook.audio.align

import com.example.freebook.audio.sync.IndexedBookWord
import com.example.freebook.model.AlignmentProvider
import com.example.freebook.model.PackedWordTimings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One audio source segment belonging to a mapped chapter (handles single files,
 * multi-file grouped chapters, and virtual `.m4b` chapter clips identically).
 */
data class AudioSlice(
    val uri: String,
    val clipStartMs: Long = 0L,
    val clipEndMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val effectiveDurationMs: Long
        get() = when {
            clipEndMs > clipStartMs -> clipEndMs - clipStartMs
            durationMs > 0L -> durationMs
            else -> 0L
        }
}

data class AlignmentRequest(
    val mappingId: String,
    val bookId: String,
    val audioSlices: List<AudioSlice>,
    val bookWords: List<IndexedBookWord>,
    val languageTag: String,
    val audioFingerprint: String,
    val textHash: String
)

sealed interface AlignmentProgress {
    data object Preparing : AlignmentProgress
    data class Running(val percent: Int, val stageLabel: String = "Aligning...") : AlignmentProgress
    data class Completed(
        val provider: AlignmentProvider,
        val timings: PackedWordTimings,
        val coverageRatio: Float,
        val avgConfidence: Float,
        val recommendsCloudPro: Boolean = false
    ) : AlignmentProgress
    data class Failed(val message: String, val recommendsCloudPro: Boolean = false) : AlignmentProgress
}

/**
 * Unified forced-alignment contract shared across:
 * - Free Tier: [LocalCtcAudioAligner] (on-demand quantized Meta MMS-300M-FA INT8 ONNX)
 * - Future Paid Tier (Phase 3): [CloudProAlignerStub] (local 16kHz Opus/AAC pre-compression +
 *   3-stage Whisper large-v3-turbo -> Fuzzy Diff -> CTC server pipeline)
 *
 * Every implementation writes the exact same [PackedWordTimings] into Room's `chapter_alignments`
 * table (`bookWordIndex -> startMs, endMs, pageIndex, startChar, endChar`).
 */
interface AudioAligner {
    val provider: AlignmentProvider
    suspend fun isAvailable(): Boolean
    fun alignChapter(request: AlignmentRequest): Flow<AlignmentProgress>
}

/**
 * Phase 3 extension stub reserved for the future Cloud Pro Aligner
 * (pre-compress 16 kHz mono Opus/AAC -> Whisper large-v3-turbo -> Fuzzy Diff -> CTC Forced Align).
 */
class CloudProAlignerStub : AudioAligner {
    override val provider: AlignmentProvider = AlignmentProvider.CLOUD_PRO

    override suspend fun isAvailable(): Boolean = false

    override fun alignChapter(request: AlignmentRequest): Flow<AlignmentProgress> = flow {
        emit(
            AlignmentProgress.Failed(
                message = "Cloud Pro Alignment will be enabled in a future update. Use Instant Sync or the Local Word-Highlighting Pack for now.",
                recommendsCloudPro = false
            )
        )
    }
}
