package com.example.freebook.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * Storage Tool #1: Internal/External File Storage.
 * Stores raw EPUB/PDF/TXT files, extracted book text files, and downsampled cover images on disk.
 * Room and DataStore never store book text or image bytes — only file paths returned by this class.
 */
interface BookFileStorage {
    suspend fun saveRawBookFile(bytes: ByteArray, extension: String = "epub"): String
    suspend fun saveExtractedTextFile(text: String): String
    suspend fun saveCoverImageBytes(imageBytes: ByteArray): String?
    suspend fun readBookText(textContentFilePath: String): String
    suspend fun deleteBookFiles(
        rawFilePath: String?,
        textContentFilePath: String?,
        coverImagePath: String?
    )
    suspend fun cleanupLegacyDemoFile()
}

class InternalBookFileStorage(
    private val context: Context
) : BookFileStorage {

    private val rawBooksDir: File
        get() = File(context.filesDir, "books_raw").apply { mkdirs() }

    private val textBooksDir: File
        get() = File(context.filesDir, "books_text").apply { mkdirs() }

    private val coversDir: File
        get() = File(context.filesDir, "covers").apply { mkdirs() }

    override suspend fun saveRawBookFile(bytes: ByteArray, extension: String): String =
        withContext(Dispatchers.IO) {
            val cleanExt = extension.trimStart('.').ifBlank { "epub" }
            val outFile = File(rawBooksDir, "book_${UUID.randomUUID()}.$cleanExt")
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile.absolutePath
        }

    override suspend fun saveExtractedTextFile(text: String): String =
        withContext(Dispatchers.IO) {
            val outFile = File(textBooksDir, "text_${UUID.randomUUID()}.txt")
            outFile.writeText(text, Charsets.UTF_8)
            outFile.absolutePath
        }

    override suspend fun saveCoverImageBytes(imageBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            if (imageBytes.isEmpty()) return@withContext null
            try {
                // Decode bounds first and downsample large cover images to save disk & memory
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, boundsOpts)
                var sampleSize = 1
                val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight)
                while (maxDim / sampleSize > 900) {
                    sampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts)
                val outFile = File(coversDir, "cover_${UUID.randomUUID()}.webp")
                if (bitmap != null) {
                    FileOutputStream(outFile).use { out ->
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 86, out)
                    }
                    bitmap.recycle()
                } else {
                    FileOutputStream(outFile).use { it.write(imageBytes) }
                }
                outFile.absolutePath
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun readBookText(textContentFilePath: String): String =
        withContext(Dispatchers.IO) {
            if (textContentFilePath.isBlank()) return@withContext ""
            val file = File(textContentFilePath)
            if (!file.exists()) return@withContext ""
            runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        }

    override suspend fun deleteBookFiles(
        rawFilePath: String?,
        textContentFilePath: String?,
        coverImagePath: String?
    ) = withContext(Dispatchers.IO) {
        listOfNotNull(rawFilePath, textContentFilePath, coverImagePath)
            .filter { it.isNotBlank() }
            .forEach { path ->
                runCatching {
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
            }
    }

    override suspend fun cleanupLegacyDemoFile() = withContext(Dispatchers.IO) {
        val legacyFile = File(context.filesDir, "bookfree_state_v1.json")
        if (legacyFile.exists()) {
            runCatching { legacyFile.delete() }
        }
    }
}
