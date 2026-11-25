package com.example.freebook.audio.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.freebook.model.TtsEngineIds
import com.example.freebook.model.TtsModelDescriptor
import com.example.freebook.model.TtsVoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Built-in Android [TextToSpeech] engine implementation supporting Arabic, English, and
 * all installed system languages with real word-boundary (`onRangeStart`) tracking on API 26+.
 */
class AndroidSystemTtsEngine(
    private val appContext: Context
) : TtsEngine {

    override val descriptor: TtsModelDescriptor = TtsModelDescriptor(
        id = TtsEngineIds.BUILT_IN_SYSTEM,
        name = "Built-in (System)",
        shortLabel = "Built-in",
        description = "Android system TTS (supports Arabic & 50+ languages)",
        isAvailable = true,
        isOffOption = false
    )

    private val _events = MutableSharedFlow<TtsEngineEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<TtsEngineEvent> = _events.asSharedFlow()

    private val initMutex = Mutex()
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var selectedVoiceId: String? = null
    private var currentSpeechRate: Float = 1.0f

    override suspend fun initialize(preferredLanguageTag: String): TtsInitResult = initMutex.withLock {
        val existing = tts
        if (initialized && existing != null) {
            return@withLock configureLanguageAndDefaultVoice(existing, preferredLanguageTag)
        }

        val ready = CompletableDeferred<Int>()
        val instance = withContext(Dispatchers.Main) {
            TextToSpeech(appContext) { status ->
                if (!ready.isCompleted) {
                    ready.complete(status)
                }
            }
        }

        val status = ready.await()
        if (status != TextToSpeech.SUCCESS) {
            runCatching { instance.shutdown() }
            return@withLock TtsInitResult(
                success = false,
                errorMessage = "System Text-to-Speech engine could not be initialized."
            )
        }

        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null) {
                    _events.tryEmit(TtsEngineEvent.UtteranceStarted(utteranceId))
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) {
                    _events.tryEmit(TtsEngineEvent.UtteranceDone(utteranceId))
                }
            }

            @Deprecated("Deprecated in Android API", ReplaceWith("onError(utteranceId, errorCode)"))
            override fun onError(utteranceId: String?) {
                if (utteranceId != null) {
                    _events.tryEmit(TtsEngineEvent.UtteranceError(utteranceId, "TTS synthesis failed"))
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != null) {
                    _events.tryEmit(TtsEngineEvent.UtteranceError(utteranceId, "TTS error ($errorCode)"))
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != null && end > start) {
                    _events.tryEmit(TtsEngineEvent.WordRangeStarted(utteranceId, start, end))
                }
            }
        })

        instance.setSpeechRate(currentSpeechRate)
        tts = instance
        initialized = true
        return@withLock configureLanguageAndDefaultVoice(instance, preferredLanguageTag)
    }

    private fun configureLanguageAndDefaultVoice(
        engine: TextToSpeech,
        preferredLanguageTag: String
    ): TtsInitResult {
        val targetLocale = parseLocale(preferredLanguageTag)
        val availability = runCatching { engine.isLanguageAvailable(targetLocale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)

        // Check if an installed offline/local voice matches the language prefix (e.g., "ar")
        val voices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        val langPrefix = targetLocale.language.lowercase(Locale.ROOT)
        val matchingVoices = voices
            .filter { it.locale.language.equals(langPrefix, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
            )

        if (matchingVoices.isNotEmpty()) {
            // Keep previously chosen voice if it still matches this book's language
            val existingMatch = matchingVoices.firstOrNull { it.name == selectedVoiceId }
            val chosen = existingMatch ?: matchingVoices.first()
            runCatching { engine.voice = chosen }
            selectedVoiceId = chosen.name
            return TtsInitResult(success = true)
        }

        if (availability == TextToSpeech.LANG_MISSING_DATA) {
            return TtsInitResult(
                success = false,
                missingLanguageTag = targetLocale.toLanguageTag(),
                errorMessage = "Voice pack for ${targetLocale.displayLanguage} is not installed yet."
            )
        }

        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            runCatching { engine.language = targetLocale }
            selectedVoiceId = runCatching { engine.voice?.name }.getOrNull()
            return TtsInitResult(success = true)
        }

        // Fall back to default system voice if book language is unsupported by current engine
        val fallbackVoice = runCatching { engine.defaultVoice ?: engine.voice }.getOrNull()
        selectedVoiceId = fallbackVoice?.name
        return TtsInitResult(
            success = true,
            missingLanguageTag = if (langPrefix.isNotBlank()) targetLocale.toLanguageTag() else null
        )
    }

    override suspend fun getVoices(preferredLanguageTag: String): List<TtsVoiceInfo> = withContext(Dispatchers.Default) {
        val engine = tts ?: return@withContext emptyList()
        val targetLang = parseLocale(preferredLanguageTag).language.lowercase(Locale.ROOT)
        val rawVoices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (rawVoices.isEmpty()) return@withContext emptyList()

        // Group friendly numbering per language+country so names like "ar-xa-x-arz-local" look clean
        val counters = HashMap<String, Int>()
        val sortedRaw = rawVoices
            .filter { it.locale != null && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(
                compareByDescending<Voice> { it.locale.language.equals(targetLang, ignoreCase = true) }
                    .thenByDescending { !it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.locale.displayName }
                    .thenBy { it.name }
            )

        sortedRaw.map { voice ->
            val locale = voice.locale
            val langName = locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
            val countryCode = locale.country.uppercase(Locale.ROOT)
            val baseKey = if (countryCode.isNotBlank()) "$langName ($countryCode)" else langName
            val num = (counters[baseKey] ?: 0) + 1
            counters[baseKey] = num

            val isOffline = !voice.isNetworkConnectionRequired
            val modeTag = if (isOffline) "Offline" else "Network"
            val qualityTag = when {
                voice.quality >= Voice.QUALITY_VERY_HIGH -> "HD"
                voice.quality >= Voice.QUALITY_HIGH -> "High"
                else -> ""
            }
            val suffix = buildString {
                append(modeTag)
                if (qualityTag.isNotEmpty()) append(" · ").append(qualityTag)
            }
            val matchesBook = locale.language.equals(targetLang, ignoreCase = true)

            TtsVoiceInfo(
                id = voice.name,
                engineId = descriptor.id,
                displayName = "$baseKey · Voice $num ($suffix)",
                shortName = "$langName $num",
                localeTag = locale.toLanguageTag(),
                languageDisplay = baseKey,
                isOffline = isOffline,
                qualityScore = voice.quality,
                matchesBookLanguage = matchesBook
            )
        }
    }

    override suspend fun selectVoice(voiceId: String): Boolean {
        val engine = tts ?: return false
        val target = runCatching { engine.voices?.firstOrNull { it.name == voiceId } }.getOrNull()
            ?: return false
        val result = runCatching { engine.setVoice(target) }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.SUCCESS) {
            selectedVoiceId = target.name
            return true
        }
        return false
    }

    override fun getSelectedVoiceId(): String? = selectedVoiceId

    override fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.5f, 3.0f)
        runCatching { tts?.setSpeechRate(currentSpeechRate) }
    }

    override fun speak(utteranceId: String, text: String) {
        val engine = tts ?: return
        engine.setSpeechRate(currentSpeechRate)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun release() {
        initialized = false
        selectedVoiceId = null
        val engine = tts
        tts = null
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
    }

    private fun parseLocale(tag: String): Locale {
        val cleaned = tag.trim().ifBlank { "en" }
        val normalized = cleaned.replace('_', '-')
        val locale = Locale.forLanguageTag(normalized)
        return if (locale.language.isNullOrBlank()) Locale.ENGLISH else locale
    }

    companion object {
        fun createInstallLanguageDataIntent(): Intent =
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
