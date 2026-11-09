package com.example.freebook.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.freebook.model.FixedLayoutFitMode
import com.example.freebook.model.ReaderFontSizeStep
import com.example.freebook.model.ReaderNavigationMode
import com.example.freebook.model.ReaderPreferences
import com.example.freebook.model.ReaderTextAlign
import com.example.freebook.model.ReaderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.readerPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bookfree_reader_preferences"
)

interface UserPreferencesRepository {
    val preferencesFlow: Flow<ReaderPreferences>
    suspend fun setDarkTheme(isDark: Boolean)
    suspend fun setReaderTheme(theme: ReaderTheme)
    suspend fun setNavigationMode(mode: ReaderNavigationMode)
    suspend fun setFontSizeMultiplier(multiplier: Float)
    suspend fun setLineHeightMultiplier(multiplier: Float)
    suspend fun setPageMarginDp(marginDp: Int)
    suspend fun setTextAlignment(alignment: ReaderTextAlign)
    suspend fun setPublisherStyles(enabled: Boolean)
    suspend fun setFixedLayoutFitMode(fitMode: FixedLayoutFitMode)
    suspend fun setEnablePageAnimations(enabled: Boolean)
}

class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : UserPreferencesRepository {

    constructor(context: Context) : this(context.applicationContext.readerPreferencesDataStore)

    private object Keys {
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val NAVIGATION_MODE = stringPreferencesKey("navigation_mode")
        val FONT_SIZE_MULTIPLIER = floatPreferencesKey("font_size_multiplier")
        val LINE_HEIGHT_MULTIPLIER = floatPreferencesKey("line_height_multiplier")
        val PAGE_MARGIN_DP = intPreferencesKey("page_margin_dp")
        val TEXT_ALIGNMENT = stringPreferencesKey("text_alignment")
        val PUBLISHER_STYLES = booleanPreferencesKey("publisher_styles")
        val FIXED_LAYOUT_FIT_MODE = stringPreferencesKey("fixed_layout_fit_mode")
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
            val savedThemeName = prefs[Keys.READER_THEME]
            val isDark = prefs[Keys.IS_DARK_THEME] ?: true
            val resolvedReaderTheme = if (savedThemeName != null) {
                ReaderTheme.fromName(savedThemeName)
            } else {
                if (isDark) ReaderTheme.DARK else ReaderTheme.LIGHT
            }
            val navMode = runCatching {
                ReaderNavigationMode.valueOf(
                    prefs[Keys.NAVIGATION_MODE] ?: ReaderNavigationMode.HORIZONTAL_SWIPE.name
                )
            }.getOrDefault(ReaderNavigationMode.HORIZONTAL_SWIPE)
            val rawMultiplier = prefs[Keys.FONT_SIZE_MULTIPLIER] ?: ReaderFontSizeStep.STEP_3.multiplier
            val step = ReaderFontSizeStep.fromMultiplier(rawMultiplier)
            val lineHeight = (prefs[Keys.LINE_HEIGHT_MULTIPLIER] ?: 1.5f).coerceIn(1.1f, 2.2f)
            val marginDp = (prefs[Keys.PAGE_MARGIN_DP] ?: 24).coerceIn(12, 48)
            val alignment = runCatching {
                ReaderTextAlign.valueOf(prefs[Keys.TEXT_ALIGNMENT] ?: ReaderTextAlign.JUSTIFY.name)
            }.getOrDefault(ReaderTextAlign.JUSTIFY)
            val fitMode = runCatching {
                FixedLayoutFitMode.valueOf(prefs[Keys.FIXED_LAYOUT_FIT_MODE] ?: FixedLayoutFitMode.FIT_PAGE.name)
            }.getOrDefault(FixedLayoutFitMode.FIT_PAGE)

            ReaderPreferences(
                isDarkTheme = isDark,
                readerTheme = resolvedReaderTheme,
                navigationMode = navMode,
                fontSizeMultiplier = step.multiplier,
                lineHeightMultiplier = lineHeight,
                pageMarginDp = marginDp,
                textAlignment = alignment,
                publisherStyles = prefs[Keys.PUBLISHER_STYLES] ?: false,
                fixedLayoutFitMode = fitMode,
                enablePageAnimations = prefs[Keys.ENABLE_PAGE_ANIMATIONS] ?: true
            )
        }

    override suspend fun setDarkTheme(isDark: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.IS_DARK_THEME] = isDark
            prefs[Keys.READER_THEME] = if (isDark) ReaderTheme.DARK.name else ReaderTheme.LIGHT.name
        }
    }

    override suspend fun setReaderTheme(theme: ReaderTheme) {
        dataStore.edit { prefs ->
            prefs[Keys.READER_THEME] = theme.name
            prefs[Keys.IS_DARK_THEME] = theme.isDarkSurface
        }
    }

    override suspend fun setNavigationMode(mode: ReaderNavigationMode) {
        dataStore.edit { prefs ->
            prefs[Keys.NAVIGATION_MODE] = mode.name
        }
    }

    override suspend fun setFontSizeMultiplier(multiplier: Float) {
        dataStore.edit { prefs ->
            prefs[Keys.FONT_SIZE_MULTIPLIER] = ReaderFontSizeStep.fromMultiplier(multiplier).multiplier
        }
    }

    override suspend fun setLineHeightMultiplier(multiplier: Float) {
        dataStore.edit { prefs ->
            prefs[Keys.LINE_HEIGHT_MULTIPLIER] = multiplier.coerceIn(1.1f, 2.2f)
        }
    }

    override suspend fun setPageMarginDp(marginDp: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.PAGE_MARGIN_DP] = marginDp.coerceIn(12, 48)
        }
    }

    override suspend fun setTextAlignment(alignment: ReaderTextAlign) {
        dataStore.edit { prefs ->
            prefs[Keys.TEXT_ALIGNMENT] = alignment.name
        }
    }

    override suspend fun setPublisherStyles(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.PUBLISHER_STYLES] = enabled
        }
    }

    override suspend fun setFixedLayoutFitMode(fitMode: FixedLayoutFitMode) {
        dataStore.edit { prefs ->
            prefs[Keys.FIXED_LAYOUT_FIT_MODE] = fitMode.name
        }
    }

    override suspend fun setEnablePageAnimations(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ENABLE_PAGE_ANIMATIONS] = enabled
        }
    }
}
