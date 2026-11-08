package com.example.freebook.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.freebook.model.ReaderPreferences
import com.example.freebook.model.ReaderTextSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.readerPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bookfree_reader_preferences"
)

/**
 * Storage Tool #3: Jetpack DataStore Preferences.
 * Reserved strictly for lightweight user preferences (`isDarkTheme`, `fontSizeSp`, `enablePageAnimations`).
 * Never stores books, notes, or file contents.
 */
interface UserPreferencesRepository {
    val preferencesFlow: Flow<ReaderPreferences>
    suspend fun setDarkTheme(isDark: Boolean)
    suspend fun setFontSizeSp(fontSizeSp: Float)
    suspend fun setEnablePageAnimations(enabled: Boolean)
}

class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : UserPreferencesRepository {

    constructor(context: Context) : this(context.applicationContext.readerPreferencesDataStore)

    private object Keys {
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        val ENABLE_PAGE_ANIMATIONS = booleanPreferencesKey("enable_page_animations")
    }

    override val preferencesFlow: Flow<ReaderPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            ReaderPreferences(
                isDarkTheme = prefs[Keys.IS_DARK_THEME] ?: true,
                fontSizeSp = ReaderTextSize.fromSp(prefs[Keys.FONT_SIZE_SP] ?: ReaderTextSize.NORMAL.sp).sp,
                enablePageAnimations = prefs[Keys.ENABLE_PAGE_ANIMATIONS] ?: true
            )
        }

    override suspend fun setDarkTheme(isDark: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.IS_DARK_THEME] = isDark
        }
    }

    override suspend fun setFontSizeSp(fontSizeSp: Float) {
        dataStore.edit { prefs ->
            prefs[Keys.FONT_SIZE_SP] = ReaderTextSize.fromSp(fontSizeSp).sp
        }
    }

    override suspend fun setEnablePageAnimations(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ENABLE_PAGE_ANIMATIONS] = enabled
        }
    }
}
