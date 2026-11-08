package com.example.freebook.di

import android.content.Context
import com.example.freebook.data.BookRepository
import com.example.freebook.data.OfflineFirstBookRepository
import com.example.freebook.data.local.preferences.DataStoreUserPreferencesRepository
import com.example.freebook.data.local.preferences.UserPreferencesRepository
import com.example.freebook.data.local.room.BookFreeDatabase
import com.example.freebook.data.storage.BookFileStorage
import com.example.freebook.data.storage.InternalBookFileStorage

/**
 * Manual Dependency Injection Container following Android & KMP best practices.
 * Facilitates unit/instrumentation testing by allowing tests to swap in fake implementations
 * of [BookRepository], [UserPreferencesRepository], and [BookFileStorage] with zero boilerplate.
 */
interface AppContainer {
    val bookFileStorage: BookFileStorage
    val bookRepository: BookRepository
    val userPreferencesRepository: UserPreferencesRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    private val database: BookFreeDatabase by lazy {
        BookFreeDatabase.getInstance(appContext)
    }

    override val bookFileStorage: BookFileStorage by lazy {
        InternalBookFileStorage(appContext)
    }

    override val bookRepository: BookRepository by lazy {
        OfflineFirstBookRepository(
            bookDao = database.bookDao(),
            annotationDao = database.annotationDao(),
            fileStorage = bookFileStorage
        )
    }

    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        DataStoreUserPreferencesRepository(appContext)
    }
}
