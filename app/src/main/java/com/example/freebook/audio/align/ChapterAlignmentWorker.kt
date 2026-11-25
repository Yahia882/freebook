package com.example.freebook.audio.align

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.freebook.BookFreeApplication
import com.example.freebook.R
import com.example.freebook.audio.sync.BookWordTokenizer
import com.example.freebook.data.local.room.ChapterAlignmentEntity
import com.example.freebook.model.AlignmentProvider
import com.example.freebook.model.AlignmentStatus
import com.example.freebook.model.PublicationLayoutType

/**
 * Lazy per-chapter background alignment worker running inside WorkManager.
 * Executes whichever [AudioAligner] is requested (currently Free Local MMS-300M-FA ONNX;
 * ready for future Phase 3 Cloud Pro) and writes timestamps into the unified
 * `chapter_alignments` Room table.
 */
class ChapterAlignmentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val mappingId = inputData.getString(KEY_MAPPING_ID) ?: return Result.failure()
        val providerName = inputData.getString(KEY_PROVIDER) ?: AlignmentProvider.LOCAL_MMS_FA.name
        val provider = runCatching { AlignmentProvider.valueOf(providerName) }
            .getOrDefault(AlignmentProvider.LOCAL_MMS_FA)

        val app = applicationContext as? BookFreeApplication ?: return Result.failure()
        val container = app.container
        val dao = container.audiobookDao
        val bookDao = container.bookDao

        val bookEntity = bookDao.getBookById(bookId) ?: return Result.failure()
        val book = bookEntity.toDomainModel()
        val mappingEntity = dao.getMappingById(mappingId) ?: return Result.failure()
        val mapping = mappingEntity.toDomainModel()
        val groupTracks = dao.getTracksForGroupOnce(mapping.groupId).map { it.toDomainModel() }
        if (groupTracks.isEmpty()) return Result.failure()

        val audioSlices = groupTracks.map {
            AudioSlice(
                uri = it.sourceUri,
                clipStartMs = it.clipStartMs,
                clipEndMs = it.clipEndMs,
                durationMs = it.durationMs
            )
        }
        val audioFingerprint = groupTracks.joinToString("|") {
            "${it.audioHash.ifBlank { it.sourceUri }}:${it.clipStartMs}:${it.clipEndMs}"
        }

        // Extract deterministic chapter words via shared tokenizer (same for EPUB and PDF)
        val bookWords = if (book.layoutType == PublicationLayoutType.FIXED) {
            val pages = container.pdfTextIndexer.getPageRangeTexts(
                book = book,
                startPageIndex = mapping.bookStartPageIndex,
                endPageIndex = mapping.bookEndPageIndex
            )
            val all = ArrayList<com.example.freebook.audio.sync.IndexedBookWord>()
            var idx = 0
            for (p in pages) {
                if (p.isScanned) continue
                val pw = BookWordTokenizer.tokenizeSegment(
                    rawText = p.bodyText,
                    pageIndex = p.pageIndex,
                    rawBaseOffset = p.bodyStartChar,
                    startWordIndex = idx,
                    isFromPdf = true
                )
                all.addAll(pw)
                idx += pw.size
            }
            all
        } else {
            val fullText = container.bookRepository.loadBookText(book)
            val safeStart = mapping.bookStartCharOffset.coerceIn(0, fullText.length)
            val safeEnd = if (mapping.bookEndCharOffset > safeStart) {
                mapping.bookEndCharOffset.coerceAtMost(fullText.length)
            } else {
                fullText.length
            }
            BookWordTokenizer.tokenizeSegment(
                rawText = fullText.substring(safeStart, safeEnd),
                pageIndex = 0,
                rawBaseOffset = safeStart,
                startWordIndex = 0,
                isFromPdf = false
            )
        }

        if (bookWords.isEmpty()) {
            dao.upsertAlignment(
                ChapterAlignmentEntity(
                    mappingId = mappingId,
                    bookId = bookId,
                    provider = provider.name,
                    status = AlignmentStatus.FAILED.name,
                    errorMessage = "No extractable text found in mapped book range.",
                    updatedAt = System.currentTimeMillis()
                )
            )
            return Result.failure()
        }

        val textHash = BookWordTokenizer.computeWordsHash(bookWords)

        // Skip redundant alignment if exact same audio + text + tokenizer is already cached!
        val existing = dao.getAlignmentForMapping(mappingId)
        if (existing != null &&
            existing.status == AlignmentStatus.READY.name &&
            existing.audioFingerprint == audioFingerprint &&
            existing.textHash == textHash &&
            existing.tokenizerVersion == BookWordTokenizer.TOKENIZER_VERSION &&
            existing.packedTimingsBlob != null
        ) {
            return Result.success()
        }

        dao.upsertAlignment(
            ChapterAlignmentEntity(
                mappingId = mappingId,
                bookId = bookId,
                provider = provider.name,
                status = AlignmentStatus.RUNNING.name,
                progressPercent = 2,
                audioFingerprint = audioFingerprint,
                textHash = textHash,
                tokenizerVersion = BookWordTokenizer.TOKENIZER_VERSION,
                updatedAt = System.currentTimeMillis()
            )
        )

        runCatching {
            setForeground(createForegroundInfo(mapping.bookChapterTitle.ifBlank { book.title }, 2))
        }

        val aligner: AudioAligner = when (provider) {
            AlignmentProvider.CLOUD_PRO -> container.cloudProAligner
            else -> container.localCtcAligner
        }

        val request = AlignmentRequest(
            mappingId = mappingId,
            bookId = bookId,
            audioSlices = audioSlices,
            bookWords = bookWords,
            languageTag = book.language,
            audioFingerprint = audioFingerprint,
            textHash = textHash
        )

        var succeeded = false
        aligner.alignChapter(request).collect { progress ->
            when (progress) {
                AlignmentProgress.Preparing -> {
                    dao.updateAlignmentStatus(mappingId, AlignmentStatus.RUNNING.name, 5)
                }
                is AlignmentProgress.Running -> {
                    dao.updateAlignmentStatus(mappingId, AlignmentStatus.RUNNING.name, progress.percent)
                    runCatching {
                        setForeground(
                            createForegroundInfo(
                                mapping.bookChapterTitle.ifBlank { book.title },
                                progress.percent
                            )
                        )
                    }
                }
                is AlignmentProgress.Completed -> {
                    val blob = progress.timings.toGzipBytes()
                    val finalStatus = if (progress.recommendsCloudPro) {
                        AlignmentStatus.NEEDS_CLOUD_RECOMMENDED
                    } else {
                        AlignmentStatus.READY
                    }
                    dao.upsertAlignment(
                        ChapterAlignmentEntity(
                            mappingId = mappingId,
                            bookId = bookId,
                            provider = progress.provider.name,
                            status = finalStatus.name,
                            progressPercent = 100,
                            coverageRatio = progress.coverageRatio,
                            avgConfidence = progress.avgConfidence,
                            audioFingerprint = audioFingerprint,
                            textHash = textHash,
                            tokenizerVersion = BookWordTokenizer.TOKENIZER_VERSION,
                            wordCount = progress.timings.size,
                            packedTimingsBlob = blob,
                            errorMessage = if (progress.recommendsCloudPro) {
                                "Edition mismatch detected. Cloud Pro Alignment recommended when available."
                            } else {
                                ""
                            },
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    succeeded = true
                }
                is AlignmentProgress.Failed -> {
                    dao.upsertAlignment(
                        ChapterAlignmentEntity(
                            mappingId = mappingId,
                            bookId = bookId,
                            provider = provider.name,
                            status = AlignmentStatus.FAILED.name,
                            progressPercent = 0,
                            audioFingerprint = audioFingerprint,
                            textHash = textHash,
                            tokenizerVersion = BookWordTokenizer.TOKENIZER_VERSION,
                            errorMessage = progress.message,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        return if (succeeded) Result.success() else Result.failure()
    }

    private fun createForegroundInfo(chapterTitle: String, progress: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audiobook Word Alignment",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Aligning: $chapterTitle")
            .setContentText("Computing word-level sync ($progress%)")
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val KEY_BOOK_ID = "book_id"
        private const val KEY_MAPPING_ID = "mapping_id"
        private const val KEY_PROVIDER = "provider"
        private const val CHANNEL_ID = "freebook_alignment_channel"
        private const val NOTIFICATION_ID = 4401

        fun enqueueChapterAlignment(
            context: Context,
            bookId: String,
            mappingId: String,
            provider: AlignmentProvider = AlignmentProvider.LOCAL_MMS_FA,
            isActiveChapterPriority: Boolean = true
        ) {
            val data = Data.Builder()
                .putString(KEY_BOOK_ID, bookId)
                .putString(KEY_MAPPING_ID, mappingId)
                .putString(KEY_PROVIDER, provider.name)
                .build()

            val builder = OneTimeWorkRequestBuilder<ChapterAlignmentWorker>()
                .setInputData(data)

            if (isActiveChapterPriority) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } else {
                builder.setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
            }

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "align_chapter_$mappingId",
                ExistingWorkPolicy.KEEP,
                builder.build()
            )
        }
    }
}
