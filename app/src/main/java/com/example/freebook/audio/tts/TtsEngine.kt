package com.example.freebook.audio.tts

import com.example.freebook.model.TtsModelDescriptor
import com.example.freebook.model.TtsVoiceInfo
import kotlinx.coroutines.flow.SharedFlow

/**
 * Events emitted by any [TtsEngine] implementation (Android System TTS, future Kokoro, Piper, ONNX, etc.).
 */
sealed interface TtsEngineEvent {
    data class UtteranceStarted(val utteranceId: String) : TtsEngineEvent
    data class WordRangeStarted(
        val utteranceId: String,
        val startChar: Int,
        val endChar: Int
    ) : TtsEngineEvent
    data class UtteranceDone(val utteranceId: String) : TtsEngineEvent
    data class UtteranceError(val utteranceId: String, val message: String) : TtsEngineEvent
}

data class TtsInitResult(
    val success: Boolean,
    val missingLanguageTag: String? = null,
    val errorMessage: String? = null
)

/**
 * Pluggable TTS engine interface.
 * Implement this interface to add future 3rd-party TTS engines (Kokoro, Piper, Sherpa-ONNX, Cloud TTS)
 * and register them in the TTS Model picker without changing reader or highlight code.
 */
interface TtsEngine {
    val descriptor: TtsModelDescriptor
    val events: SharedFlow<TtsEngineEvent>

    suspend fun initialize(preferredLanguageTag: String): TtsInitResult
    suspend fun getVoices(preferredLanguageTag: String): List<TtsVoiceInfo>
    suspend fun selectVoice(voiceId: String): Boolean
    fun getSelectedVoiceId(): String?
    fun setSpeechRate(rate: Float)
    fun speak(utteranceId: String, text: String)
    fun stop()
    fun release()
}
