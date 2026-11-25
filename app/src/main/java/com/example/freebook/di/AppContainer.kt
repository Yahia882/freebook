package com.example.freebook.di

import android.content.Context
import com.example.freebook.audio.align.AudioAligner
import com.example.freebook.audio.align.CloudProAlignerStub
import com.example.freebook.audio.align.LocalCtcAudioAligner
import com.example.freebook.audio.align.LocalMmsFaPackManager
import com.example.freebook.audio.playback.AudiobookRepository
import com.example.freebook.audio.sync.PdfTextIndexer
import com.example.freebook.audio.tts.AndroidSystemTtsEngine
import com.example.freebook.audio.tts.TtsEngine
import com.example.freebook.data.BookRepository
import com.example.freebook.data.OfflineFirstBookRepository
import com.example.freebook.data.local.preferences.DataStoreUserPreferencesRepository
import com.example.freebook.data.local.preferences.UserPreferencesRepository
import com.example.freebook.data.local.room.AudiobookDao
import com.example.freebook.data.local.room.BookDao
import com.example.freebook.data.local.room.BookFreeDatabase
import com.example.freebook.data.storage.BookFileStorage
import com.example.freebook.data.storage.InternalBookFileStorage

/**
 * Manual Dependency Injection Container following Android & KMP best practices.
 */
interface AppContainer {
    val bookDao: BookDao
    val audiobookDao: AudiobookDao
    val bookFileStorage: BookFileStorage
    val bookRepository: BookRepository
    val userPreferencesRepository: UserPreferencesRepository
    val pdfTextIndexer: PdfTextIndexer
    val localMmsFaPackManager: LocalMmsFaPackManager
    val localCtcAligner: AudioAligner
    val cloudProAligner: AudioAligner
    val audiobookRepository: AudiobookRepository
    fun createTtsEngines(): List<TtsEngine>
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    private val database: BookFreeDatabase by lazy {
        BookFreeDatabase.getInstance(appContext)
    }

    override val bookDao: BookDao by lazy { database.bookDao() }
    override val audiobookDao: AudiobookDao by lazy { database.audiobookDao() }

    override val bookFileStorage: BookFileStorage by lazy {
        InternalBookFileStorage(appContext)
    }

    override val bookRepository: BookRepository by lazy {
        OfflineFirstBookRepository(
            bookDao = bookDao,
            annotationDao = database.annotationDao(),
            fileStorage = bookFileStorage
        )
    }

    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        DataStoreUserPreferencesRepository(appContext)
    }

    override val pdfTextIndexer: PdfTextIndexer by lazy {
        PdfTextIndexer(
            appContext = appContext,
            audiobookDao = audiobookDao
        )
    }

    override val localMmsFaPackManager: LocalMmsFaPackManager by lazy {
        LocalMmsFaPackManager(appContext)
    }

    override val localCtcAligner: AudioAligner by lazy {
        LocalCtcAudioAligner(
            appContext = appContext,
            packManager = localMmsFaPackManager
        )
    }

    override val cloudProAligner: AudioAligner by lazy {
        CloudProAlignerStub()
    }

    override val audiobookRepository: AudiobookRepository by lazy {
        AudiobookRepository(
            appContext = appContext,
            audiobookDao = audiobookDao
        )
    }

    override fun createTtsEngines(): List<TtsEngine> = listOf(
        AndroidSystemTtsEngine(appContext)
        // Future 3rd-party TTS engines (Kokoro, Piper, Sherpa-ONNX, etc.) register here.
    )
}
