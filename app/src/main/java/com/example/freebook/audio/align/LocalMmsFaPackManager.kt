package com.example.freebook.audio.align

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class LocalPackStatus(
    val isInstalled: Boolean = false,
    val modelSizeBytes: Long = 0L,
    val downloadProgressPercent: Int? = null,
    val statusMessage: String? = null
)

/**
 * Manages the optional, on-demand **Local Word Highlighting Pack** (quantized Meta MMS-300M-FA INT8 ONNX).
 * Kept out of the APK bundle so the app remains lightweight; stored under `filesDir/models/mms_fa/`.
 *
 * Supports both:
 * 1. Downloading the ONNX model + vocab pack on demand from a configurable URL, and
 * 2. Importing a local `.onnx` file directly from the user's device storage.
 */
class LocalMmsFaPackManager(
    private val appContext: Context
) {
    private val modelDir: File
        get() = File(appContext.filesDir, "models/mms_fa").apply { mkdirs() }

    val modelFile: File
        get() = File(modelDir, MODEL_FILE_NAME)

    val vocabFile: File
        get() = File(modelDir, VOCAB_FILE_NAME)

    private val _status = MutableStateFlow(computeCurrentStatus())
    val status: StateFlow<LocalPackStatus> = _status.asStateFlow()

    fun refreshStatus(): LocalPackStatus {
        val current = computeCurrentStatus()
        _status.value = current
        return current
    }

    fun isModelInstalled(): Boolean {
        val f = modelFile
        return f.exists() && f.length() > MIN_VALID_MODEL_BYTES
    }

    /**
     * Loads vocabulary mapping (token string -> output logit index) from `vocab.txt`
     * if present, or falls back to the canonical Meta MMS-300M-FA 29-symbol romanized CTC dictionary.
     */
    fun loadVocabulary(): Map<String, Int> {
        val vf = vocabFile
        if (vf.exists() && vf.length() > 16) {
            val map = LinkedHashMap<String, Int>()
            runCatching {
                vf.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    val token = line.trim()
                    if (token.isNotEmpty()) {
                        map[token] = idx
                    }
                }
            }
            if (map.isNotEmpty()) return map
        }
        return DEFAULT_MMS_FA_VOCAB
    }

    /**
     * Imports a user-selected quantized MMS-300M-FA `.onnx` file directly into local storage.
     */
    suspend fun importModelFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(
            downloadProgressPercent = 5,
            statusMessage = "Importing ONNX model..."
        )
        runCatching {
            val tmp = File(modelDir, "$MODEL_FILE_NAME.tmp")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                }
            } ?: error("Unable to open selected model file.")

            if (tmp.length() < MIN_VALID_MODEL_BYTES) {
                tmp.delete()
                error("Selected file does not look like a valid ONNX model.")
            }
            if (modelFile.exists()) modelFile.delete()
            check(tmp.renameTo(modelFile)) { "Failed to finalize imported model file." }
            ensureDefaultVocabFile()
            _status.value = computeCurrentStatus().copy(statusMessage = "Local alignment pack ready.")
        }.onFailure { err ->
            _status.value = computeCurrentStatus().copy(statusMessage = err.message)
        }
    }

    /**
     * Downloads the quantized MMS-300M-FA ONNX pack on demand with streaming progress reporting.
     */
    suspend fun downloadPack(modelUrl: String = DEFAULT_MODEL_URL): Result<Unit> = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(
            downloadProgressPercent = 0,
            statusMessage = "Connecting to model server..."
        )
        val tmp = File(modelDir, "$MODEL_FILE_NAME.download")
        runCatching {
            val conn = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    error("Download failed (HTTP $code). You can also import a local MMS-FA .onnx file.")
                }
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var lastEmit = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val pct = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(1, 99)
                            } else {
                                ((downloaded / (1024L * 1024L)).toInt() % 95).coerceAtLeast(1)
                            }
                            if (pct != lastEmit) {
                                lastEmit = pct
                                _status.value = _status.value.copy(
                                    downloadProgressPercent = pct,
                                    statusMessage = "Downloading pack ($pct%)..."
                                )
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (tmp.length() < MIN_VALID_MODEL_BYTES) {
                tmp.delete()
                error("Downloaded file is incomplete.")
            }
            if (modelFile.exists()) modelFile.delete()
            check(tmp.renameTo(modelFile)) { "Failed to store model file." }
            ensureDefaultVocabFile()
            _status.value = computeCurrentStatus().copy(statusMessage = "Local Word Highlighting Pack installed.")
        }.onFailure { err ->
            runCatching { tmp.delete() }
            _status.value = computeCurrentStatus().copy(statusMessage = err.message)
        }
    }

    suspend fun deletePack() = withContext(Dispatchers.IO) {
        runCatching { modelFile.delete() }
        runCatching { vocabFile.delete() }
        _status.value = computeCurrentStatus().copy(statusMessage = "Local pack removed.")
    }

    private fun ensureDefaultVocabFile() {
        if (!vocabFile.exists()) {
            runCatching {
                val ordered = DEFAULT_MMS_FA_VOCAB.entries.sortedBy { it.value }.map { it.key }
                vocabFile.writeText(ordered.joinToString("\n"), Charsets.UTF_8)
            }
        }
    }

    private fun computeCurrentStatus(): LocalPackStatus {
        val installed = isModelInstalled()
        return LocalPackStatus(
            isInstalled = installed,
            modelSizeBytes = if (installed) modelFile.length() else 0L,
            downloadProgressPercent = null,
            statusMessage = null
        )
    }

    companion object {
        const val MODEL_FILE_NAME = "mms_300m_fa_int8.onnx"
        const val VOCAB_FILE_NAME = "vocab.txt"
        private const val MIN_VALID_MODEL_BYTES = 1_000_000L

        // Hugging Face ONNX community mirror for quantized Meta MMS-300M-FA
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/MahmoudAshraf/mms-300m-1130-forced-aligner/resolve/main/onnx/model_quantized.onnx"

        // Standard Meta MMS-300M-FA uroman CTC vocabulary (blank=0, a-z, apostrophe, star wildcard)
        val DEFAULT_MMS_FA_VOCAB: Map<String, Int> = linkedMapOf(
            "-" to 0,
            "a" to 1, "i" to 2, "e" to 3, "n" to 4, "o" to 5, "u" to 6, "t" to 7, "s" to 8,
            "r" to 9, "m" to 10, "k" to 11, "l" to 12, "d" to 13, "g" to 14, "h" to 15, "y" to 16,
            "b" to 17, "p" to 18, "w" to 19, "c" to 20, "v" to 21, "j" to 22, "z" to 23, "f" to 24,
            "'" to 25, "q" to 26, "x" to 27, "*" to 28
        )
    }
}
