package com.example.freebook.ui.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.freebook.R
import com.example.freebook.model.ActiveWordHighlight
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.BookReadingDirection
import com.example.freebook.model.FixedLayoutFitMode
import com.example.freebook.model.HighlightColor
import com.example.freebook.model.PublicationLayoutType
import com.example.freebook.model.ReaderFontSizeStep
import com.example.freebook.model.ReaderNavigationMode
import com.example.freebook.model.ReaderPreferences
import com.example.freebook.model.ReaderTextAlign
import com.example.freebook.model.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

/**
 * Flattened entry from Readium's `publication.tableOfContents`, carrying its original
 * Readium [Link], resolved [Locator], display title, and hierarchy depth.
 */
data class ReadiumTocEntry(
    val id: String,
    val title: String,
    val depth: Int,
    val link: Link,
    val locator: Locator?
)

/**
 * Snapshot of the most recent non-empty text selection inside the Readium [WebView].
 * Updated continuously on DOM `selectionchange` so tapping an ActionMode button never
 * loses the selected text or character offsets even if the WebView collapses the selection.
 */
data class CachedWebViewSelection(
    val text: String,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Active ActionMode session registered with [com.example.freebook.MainActivity] while
 * the Readium reader screen is open. Allows `MainActivity.onActionModeStarted` and
 * [ReadiumSelectionWebView] to clear the default OS menu, style the floating pill to match
 * the active reader theme, evaluate JS on the Readium [WebView] to retrieve selected text,
 * and save highlights (defaulting to Yellow) via Readium's [DecorableNavigator] and [Decoration] API.
 */
class ReadiumActionModeSession(
    val navigator: ReadiumWebViewNavigator,
    var readerTheme: ReaderTheme = ReaderTheme.DARK,
    var isDarkMode: Boolean = true,
    var onStyleActionModePopup: (() -> Unit)? = null,
    val onHighlightSelected: (
        selectedText: String,
        startCharOffset: Int,
        endCharOffset: Int,
        color: HighlightColor
    ) -> Unit,
    val onCopySelected: (selectedText: String) -> Unit,
    val onAddNoteForSelected: (
        selectedText: String,
        startCharOffset: Int,
        endCharOffset: Int
    ) -> Unit
)

/**
 * Custom Readium [WebView] that intercepts [startActionMode] so Chromium's
 * `SelectActionModeCallback.onPrepareActionMode` cannot re-inject default OS items
 * (`Select all`, `Read aloud`, `Web search`) after `MainActivity.onActionModeStarted` runs,
 * and syncs the host theme and `uiMode` before Android's `FloatingToolbar` is inflated.
 */
class ReadiumSelectionWebView(
    context: Context,
    private val navigator: ReadiumWebViewNavigator
) : WebView(context) {

    var actionModeSessionProvider: (() -> ReadiumActionModeSession?)? = null

    private fun findHostActivity(ctx: Context): Activity? {
        var current: Context? = ctx
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun applyHostDarkModeTheme(isDark: Boolean) {
        val styleRes = if (isDark) R.style.Theme_Freebook_Dark else R.style.Theme_Freebook_Light
        val nightBits = if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        context.resources?.configuration?.let { cfg ->
            cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
        }
        context.theme?.applyStyle(styleRes, true)
        findHostActivity(context)?.let { act ->
            act.resources?.configuration?.let { cfg ->
                cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
            }
            act.theme?.applyStyle(styleRes, true)
        }
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        val session = actionModeSessionProvider?.invoke()
        if (session != null) {
            applyHostDarkModeTheme(session.isDarkMode)
            val wrapped = if (callback is ReadiumActionModeCallbackWrapper) {
                callback
            } else {
                ReadiumActionModeCallbackWrapper(callback, session)
            }
            return super.startActionMode(wrapped)
        }
        return super.startActionMode(callback)
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        val session = actionModeSessionProvider?.invoke()
        if (session != null) {
            applyHostDarkModeTheme(session.isDarkMode)
            val wrapped = if (callback is ReadiumActionModeCallbackWrapper) {
                callback
            } else {
                ReadiumActionModeCallbackWrapper(callback, session)
            }
            return super.startActionMode(wrapped, type)
        }
        return super.startActionMode(callback, type)
    }
}

/**
 * Wraps the WebView's [ActionMode.Callback2] so that on both [onCreateActionMode] and
 * every subsequent [onPrepareActionMode] (triggered when selection handles move),
 * the default OS menu is cleared and replaced with `Highlight`, `Add Note`, and `Copy`.
 */
internal class ReadiumActionModeCallbackWrapper(
    private val delegate: ActionMode.Callback?,
    private val session: ReadiumActionModeSession
) : ActionMode.Callback2() {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        delegate?.onCreateActionMode(mode, menu)
        ReadiumWebViewNavigator.populateCustomActionModeMenu(mode, menu, session)
        session.onStyleActionModePopup?.invoke()
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        delegate?.onPrepareActionMode(mode, menu)
        ReadiumWebViewNavigator.populateCustomActionModeMenu(mode, menu, session)
        session.onStyleActionModePopup?.invoke()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        return ReadiumWebViewNavigator.handleCustomActionModeItemClicked(mode, item, session)
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        delegate?.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (delegate is ActionMode.Callback2) {
            delegate.onGetContentRect(mode, view, outRect)
        } else {
            super.onGetContentRect(mode, view, outRect)
        }
    }
}

/**
 * Readium WebView Navigator implementing Readium's [DecorableNavigator] interface.
 * Renders reflowable and fixed-layout publications in an [android.webkit.WebView],
 * applies Readium [org.readium.r2.navigator.epub.EpubPreferences] settings,
 * manages visual highlights through Readium's [Decoration] and [HtmlDecorationTemplates] API,
 * and provides JavaScript evaluation for `ActionMode` (`Highlight`, `Add Note`, `Copy`).
 */
@OptIn(ExperimentalReadiumApi::class)
class ReadiumWebViewNavigator : DecorableNavigator {

    var webView: WebView? = null
        internal set

    var isPageLoaded: Boolean = false
        internal set

    @Volatile
    var lastNonEmptySelection: CachedWebViewSelection? = null
        internal set

    @Volatile
    var totalCanonicalChars: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decorationsByGroup = LinkedHashMap<String, List<Decoration>>()
    private val listenersByGroup = LinkedHashMap<String, CopyOnWriteArrayList<DecorableNavigator.Listener>>()
    private val tocLocatorsByHref = LinkedHashMap<String, Locator>()
    private val decorationTemplates: HtmlDecorationTemplates = HtmlDecorationTemplates.defaultTemplates()

    private val _currentLocator = MutableStateFlow(
        Locator(
            href = Url("chapter.xhtml")!!,
            mediaType = MediaType.XHTML
        )
    )
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    var onLocatorUpdated: ((charOffset: Int, currentPosition: Int, totalPositions: Int, visibleText: String) -> Unit)? = null
    var onSingleTapToggleHud: (() -> Unit)? = null
    var onInitialPositionRestored: (() -> Unit)? = null
    var onUserManualNavigation: (() -> Unit)? = null
    var onWordDoubleTap: ((charOffset: Int, wordText: String) -> Unit)? = null

    @Volatile
    private var audioWordTapEnabled: Boolean = false

    fun registerTableOfContents(entries: List<ReadiumTocEntry>) {
        tocLocatorsByHref.clear()
        for (entry in entries) {
            val loc = entry.locator ?: continue
            tocLocatorsByHref[entry.link.href.toString()] = loc
            tocLocatorsByHref[loc.href.toString()] = loc
        }
    }

    override fun go(locator: Locator, animated: Boolean): Boolean {
        _currentLocator.value = locator
        val explicitOffset = (locator.locations.otherLocations["charOffset"] as? Number)?.toInt()
        val targetOffset = explicitOffset
            ?: locator.locations.totalProgression?.let { prog ->
                if (totalCanonicalChars > 0) {
                    (prog * totalCanonicalChars).toInt().coerceIn(0, (totalCanonicalChars - 1).coerceAtLeast(0))
                } else {
                    0
                }
            }
            ?: 0
        jumpToCharOffset(targetOffset)
        return true
    }

    override fun go(link: Link, animated: Boolean): Boolean {
        val mappedLocator = tocLocatorsByHref[link.href.toString()]
            ?: tocLocatorsByHref[link.href.toString().substringBefore('#')]
        if (mappedLocator != null) {
            return go(mappedLocator, animated)
        }
        jumpToCharOffset(0)
        return true
    }

    override fun <T : Decoration.Style> supportsDecorationStyle(style: KClass<T>): Boolean {
        return style == Decoration.Style.Highlight::class ||
            style == Decoration.Style.Underline::class
    }

    override suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        decorationsByGroup[group] = decorations
        pushGroupDecorationsToWebView(group, decorations)
    }

    override fun addDecorationListener(group: String, listener: DecorableNavigator.Listener) {
        val list = listenersByGroup.getOrPut(group) { CopyOnWriteArrayList() }
        if (!list.contains(listener)) {
            list.add(listener)
        }
    }

    override fun removeDecorationListener(listener: DecorableNavigator.Listener) {
        listenersByGroup.values.forEach { it.remove(listener) }
    }

    internal fun reapplyAllDecorations() {
        for ((group, decorations) in decorationsByGroup) {
            pushGroupDecorationsToWebView(group, decorations)
        }
        setAudioWordTapEnabled(audioWordTapEnabled)
    }

    private fun pushGroupDecorationsToWebView(group: String, decorations: List<Decoration>) {
        val targetWebView = webView ?: return
        if (!isPageLoaded) return

        val templatesJson = runCatching { decorationTemplates.toJSON().toString() }.getOrDefault("{}")
        val decorationsJsonArray = JSONArray()
        for (decoration in decorations) {
            val style = decoration.style
            val tintArgb = when (style) {
                is Decoration.Style.Highlight -> style.tint
                is Decoration.Style.Underline -> style.tint
                else -> 0x77FDE047
            }
            val isUnderline = style is Decoration.Style.Underline
            val alphaInt = (tintArgb ushr 24) and 0xFF
            val r = (tintArgb ushr 16) and 0xFF
            val g = (tintArgb ushr 8) and 0xFF
            val b = tintArgb and 0xFF
            val alphaFloat = if (alphaInt in 1..254) {
                (alphaInt / 255f).coerceIn(0.25f, 0.85f)
            } else {
                0.42f
            }
            val tintCss = String.format(Locale.US, "rgba(%d, %d, %d, %.2f)", r, g, b, alphaFloat)

            val startOffset = (decoration.extras["charOffset"] as? Number)?.toInt()
                ?: (decoration.locator.locations.otherLocations["charOffset"] as? Number)?.toInt()
                ?: 0
            val endOffset = (decoration.extras["endCharOffset"] as? Number)?.toInt()
                ?: (decoration.locator.locations.otherLocations["endCharOffset"] as? Number)?.toInt()
                ?: 0
            val quote = (decoration.extras["quote"] as? String)
                ?: decoration.locator.text.highlight.orEmpty()

            val itemObj = JSONObject().apply {
                put("id", decoration.id)
                put("locator", decoration.locator.toJSON())
                put("styleClass", if (isUnderline) "underline" else "highlight")
                put("tintCss", tintCss)
                put("charOffset", startOffset)
                put("endCharOffset", endOffset)
                put("quote", quote)
            }
            decorationsJsonArray.put(itemObj)
        }

        val groupEscaped = JSONObject.quote(group)
        val script = "window.readiumApplyDecorations && window.readiumApplyDecorations($groupEscaped, $decorationsJsonArray, $templatesJson);"
        mainHandler.post {
            targetWebView.evaluateJavascript(script, null)
        }
    }

    /**
     * Evaluates JavaScript on the Readium [WebView] to retrieve the currently selected text
     * along with its canonical character range `[startCharOffset, endCharOffset)`.
     * Falls back to [lastNonEmptySelection] if the WebView selection was just collapsed by a tap.
     */
    fun evaluateSelectedText(
        clearSelectionAfter: Boolean = false,
        onResult: (selectedText: String, startCharOffset: Int, endCharOffset: Int) -> Unit
    ) {
        val snapshotFallback = lastNonEmptySelection
        val targetWebView = webView
        if (targetWebView == null) {
            if (clearSelectionAfter) {
                lastNonEmptySelection = null
            }
            onResult(
                snapshotFallback?.text.orEmpty(),
                snapshotFallback?.startCharOffset ?: -1,
                snapshotFallback?.endCharOffset ?: -1
            )
            return
        }

        val js = """
            (function() {
                if (window.__readiumGetSelectionPayload) {
                    return window.__readiumGetSelectionPayload($clearSelectionAfter);
                }
                var sel = window.getSelection ? window.getSelection().toString() : "";
                if ($clearSelectionAfter && window.getSelection) {
                    window.getSelection().removeAllRanges();
                }
                return JSON.stringify({ text: sel, startOffset: -1, endOffset: -1 });
            })();
        """.trimIndent()

        targetWebView.evaluateJavascript(js) { rawEval ->
            val decodedJson = decodeJsEvalString(rawEval)
            var text = ""
            var startOffset = -1
            var endOffset = -1
            if (decodedJson.startsWith("{")) {
                runCatching {
                    val obj = JSONObject(decodedJson)
                    text = obj.optString("text", "").trim()
                    startOffset = obj.optInt("startOffset", -1)
                    endOffset = obj.optInt("endOffset", -1)
                }
            } else {
                text = decodedJson.trim()
            }

            if (text.isBlank() && snapshotFallback != null && snapshotFallback.text.isNotBlank()) {
                text = snapshotFallback.text
                startOffset = snapshotFallback.startCharOffset
                endOffset = snapshotFallback.endCharOffset
            }

            if (clearSelectionAfter) {
                lastNonEmptySelection = null
            }

            onResult(text, startOffset, endOffset)
        }
    }

    fun clearWebViewSelection() {
        lastNonEmptySelection = null
        val targetWebView = webView ?: return
        mainHandler.post {
            targetWebView.evaluateJavascript(
                "window.__readiumClearSelection && window.__readiumClearSelection();",
                null
            )
        }
    }

    fun jumpToCharOffset(charOffset: Int) {
        val targetWebView = webView ?: return
        mainHandler.post {
            targetWebView.evaluateJavascript(
                "window.__readiumJumpToCharOffset && window.__readiumJumpToCharOffset($charOffset);",
                null
            )
        }
    }

    fun setAudioWordTapEnabled(enabled: Boolean) {
        audioWordTapEnabled = enabled
        val targetWebView = webView ?: return
        if (!isPageLoaded) return
        mainHandler.post {
            targetWebView.evaluateJavascript(
                "window.__readiumSetAudioWordTapEnabled && window.__readiumSetAudioWordTapEnabled($enabled);",
                null
            )
        }
    }

    /**
     * Drives the GPU-composited (transform + opacity only, zero DOM mutation) smooth word
     * highlight overlay inside the Readium WebView for both TTS and aligned Audiobooks.
     * Passing `null` or inactive highlight fades out and removes all live highlighting.
     */
    fun updateLiveWordHighlight(
        highlight: ActiveWordHighlight?,
        isDarkSurface: Boolean
    ) {
        val targetWebView = webView ?: return
        if (!isPageLoaded) return
        if (highlight == null || !highlight.isActive) {
            clearLiveWordHighlight()
            return
        }
        val script = "window.__readiumSetGpuWordHighlight && window.__readiumSetGpuWordHighlight(" +
            "${highlight.startCharOffset}, ${highlight.endCharOffset}, " +
            "${highlight.estimatedDurationMs.coerceIn(70L, 800L)}, ${highlight.isPaused}, " +
            "$isDarkSurface, ${highlight.shouldAutoFollow});"
        mainHandler.post {
            targetWebView.evaluateJavascript(script, null)
        }
    }

    fun clearLiveWordHighlight() {
        val targetWebView = webView ?: return
        if (!isPageLoaded) return
        mainHandler.post {
            targetWebView.evaluateJavascript(
                "window.__readiumClearGpuWordHighlight && window.__readiumClearGpuWordHighlight();",
                null
            )
        }
    }

    fun applyPreferencesInWebView(
        book: Book,
        preferences: ReaderPreferences,
        savedCharOffset: Int
    ) {
        val targetWebView = webView ?: return
        if (!isPageLoaded) return

        val epubPrefs = preferences.toReadiumEpubPreferences()
        val fontSizeMultiplier = (epubPrefs.fontSize ?: 1.0).toFloat()
        val fontSizePx = (ReaderFontSizeStep.BASE_FONT_SIZE_SP * fontSizeMultiplier).coerceIn(12f, 36f)
        val lineHeight = (epubPrefs.lineHeight ?: 1.5).toFloat()
        val marginPx = if (preferences.publisherStyles) 24 else preferences.pageMarginDp
        val textAlignCss = if (preferences.publisherStyles) {
            "start"
        } else {
            when (preferences.textAlignment) {
                ReaderTextAlign.JUSTIFY -> "justify"
                ReaderTextAlign.START -> "start"
            }
        }
        val bgHex = String.format(Locale.US, "#%06X", 0xFFFFFF and preferences.readerTheme.backgroundArgb.toInt())
        val textHex = String.format(Locale.US, "#%06X", 0xFFFFFF and preferences.readerTheme.textArgb.toInt())
        val isDark = preferences.readerTheme.isDarkSurface
        val selBg = if (isDark) "rgba(250, 204, 21, 0.35)" else "rgba(253, 224, 71, 0.48)"
        val isPaged = book.layoutType == PublicationLayoutType.REFLOWABLE &&
            preferences.navigationMode == ReaderNavigationMode.HORIZONTAL_SWIPE
        val isRtl = book.readingDirection == BookReadingDirection.RTL
        val fitScale = when (preferences.fixedLayoutFitMode) {
            FixedLayoutFitMode.FIT_WIDTH -> 1.05f
            FixedLayoutFitMode.FIT_PAGE -> 1.0f
            FixedLayoutFitMode.ACTUAL_SIZE -> 1.22f
        }

        val configJson = JSONObject().apply {
            put("fontSizePx", fontSizePx)
            put("lineHeight", lineHeight)
            put("marginPx", marginPx)
            put("textAlign", textAlignCss)
            put("bgColor", bgHex)
            put("textColor", textHex)
            put("selBgColor", selBg)
            put("isPaged", isPaged)
            put("isFixedLayout", book.layoutType == PublicationLayoutType.FIXED)
            put("fitScale", fitScale)
            put("isRtl", isRtl)
            put("targetCharOffset", savedCharOffset)
        }

        mainHandler.post {
            targetWebView.evaluateJavascript(
                "window.__readiumUpdateSettings && window.__readiumUpdateSettings($configJson);",
                null
            )
        }
    }

    internal inner class JsBridge {
        @JavascriptInterface
        fun onLocatorChanged(
            charOffset: Int,
            currentPosition: Int,
            totalPositions: Int,
            visibleText: String,
            isInitialRestore: Boolean
        ) {
            mainHandler.post {
                val safePos = currentPosition.coerceAtLeast(1)
                val safeTotal = totalPositions.coerceAtLeast(1)
                val safeOffset = charOffset.coerceAtLeast(0)
                val totalChars = totalCanonicalChars
                val prog = when {
                    safeTotal > 1 && safePos >= safeTotal -> 1.0
                    totalChars > 0 -> (safeOffset.toDouble() / totalChars.toDouble()).coerceIn(0.0, 1.0)
                    safeTotal > 1 -> ((safePos - 1).toDouble() / (safeTotal - 1).toDouble()).coerceIn(0.0, 1.0)
                    else -> 0.0
                }
                _currentLocator.value = Locator(
                    href = Url("chapter.xhtml")!!,
                    mediaType = MediaType.XHTML,
                    locations = Locator.Locations(
                        progression = prog,
                        totalProgression = prog,
                        position = safePos,
                        otherLocations = mapOf("charOffset" to safeOffset)
                    ),
                    text = Locator.Text(highlight = visibleText.take(200))
                )
                if (isInitialRestore) {
                    onInitialPositionRestored?.invoke()
                }
                onLocatorUpdated?.invoke(
                    safeOffset,
                    safePos,
                    safeTotal,
                    visibleText
                )
            }
        }

        @JavascriptInterface
        fun onSelectionChanged(selectedText: String, startOffset: Int, endOffset: Int) {
            val cleaned = selectedText.trim()
            if (cleaned.isNotEmpty()) {
                lastNonEmptySelection = CachedWebViewSelection(
                    text = cleaned,
                    startCharOffset = startOffset,
                    endCharOffset = endOffset,
                    timestampMs = System.currentTimeMillis()
                )
            }
        }

        @JavascriptInterface
        fun onSingleTap() {
            mainHandler.post {
                onSingleTapToggleHud?.invoke()
            }
        }

        @JavascriptInterface
        fun onManualNavigation() {
            mainHandler.post {
                onUserManualNavigation?.invoke()
            }
        }

        @JavascriptInterface
        fun onWordDoubleTap(charOffset: Int, wordText: String) {
            mainHandler.post {
                onWordDoubleTap?.invoke(charOffset.coerceAtLeast(0), wordText)
            }
        }

        @JavascriptInterface
        fun onDecorationActivated(
            group: String,
            decorationId: String,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float
        ) {
            mainHandler.post {
                val groupList = decorationsByGroup[group].orEmpty()
                val target = groupList.firstOrNull { it.id == decorationId } ?: return@post
                val listeners = listenersByGroup[group].orEmpty()
                val rect = RectF(left, top, right, bottom)
                val point = PointF((left + right) / 2f, top)
                val event = DecorableNavigator.OnActivatedEvent(
                    decoration = target,
                    group = group,
                    rect = rect,
                    point = point
                )
                listeners.forEach { it.onDecorationActivated(event) }
            }
        }
    }

    companion object {
        const val MENU_ID_HIGHLIGHT = 1001
        const val MENU_ID_ADD_NOTE = 1002
        const val MENU_ID_COPY = 1003

        /**
         * Clears the default OS selection menu (`Copy`, `Select all`, `Read aloud`, `Translate`, etc.)
         * and injects the custom `Highlight` (defaults to Yellow), `Add Note`, and `Copy` actions.
         */
        fun populateCustomActionModeMenu(
            mode: ActionMode,
            menu: Menu,
            session: ReadiumActionModeSession
        ) {
            menu.clear()

            // 1. Highlight (Always defaults to Yellow)
            menu.add(Menu.NONE, MENU_ID_HIGHLIGHT, 1, "Highlight")
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener { item ->
                    handleCustomActionModeItemClicked(mode, item, session)
                }

            // 2. Add Note (associated with the covered text)
            menu.add(Menu.NONE, MENU_ID_ADD_NOTE, 2, "Add Note")
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener { item ->
                    handleCustomActionModeItemClicked(mode, item, session)
                }

            // 3. Copy
            menu.add(Menu.NONE, MENU_ID_COPY, 3, "Copy")
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener { item ->
                    handleCustomActionModeItemClicked(mode, item, session)
                }
        }

        fun handleCustomActionModeItemClicked(
            mode: ActionMode,
            item: MenuItem,
            session: ReadiumActionModeSession
        ): Boolean {
            return when (item.itemId) {
                MENU_ID_HIGHLIGHT -> {
                    session.navigator.evaluateSelectedText(clearSelectionAfter = true) { selectedText, startOffset, endOffset ->
                        if (selectedText.isNotBlank()) {
                            session.onHighlightSelected(
                                selectedText,
                                startOffset,
                                endOffset,
                                HighlightColor.YELLOW
                            )
                        }
                        runCatching { mode.finish() }
                    }
                    true
                }

                MENU_ID_ADD_NOTE -> {
                    session.navigator.evaluateSelectedText(clearSelectionAfter = true) { selectedText, startOffset, endOffset ->
                        if (selectedText.isNotBlank()) {
                            session.onAddNoteForSelected(selectedText, startOffset, endOffset)
                        }
                        runCatching { mode.finish() }
                    }
                    true
                }

                MENU_ID_COPY -> {
                    session.navigator.evaluateSelectedText(clearSelectionAfter = true) { selectedText, _, _ ->
                        if (selectedText.isNotBlank()) {
                            session.onCopySelected(selectedText)
                        }
                        runCatching { mode.finish() }
                    }
                    true
                }

                else -> false
            }
        }

        /**
         * Converts a [BookAnnotation] (`HIGHLIGHT` or `NOTE` with `quotedText`) into a Readium
         * [Decoration] using [Decoration.Style.Highlight].
         */
        fun annotationToReadiumDecoration(
            annotation: BookAnnotation,
            book: Book,
            totalChars: Int,
            isDarkSurface: Boolean
        ): Decoration? {
            if (annotation.type == AnnotationType.BOOKMARK) return null
            val quoteText = when (annotation.type) {
                AnnotationType.HIGHLIGHT -> annotation.text.trim()
                AnnotationType.NOTE -> annotation.quotedText.trim()
                AnnotationType.BOOKMARK -> ""
            }
            if (quoteText.isEmpty() && annotation.endCharOffset <= annotation.charOffset) {
                return null
            }
            val safeTotal = totalChars.coerceAtLeast(1)
            val progression = (annotation.charOffset.toDouble() / safeTotal.toDouble()).coerceIn(0.0, 1.0)
            val colorEnum = annotation.highlightColor
            val tintArgb = (if (isDarkSurface) colorEnum.markerBgDarkArgb else colorEnum.markerBgLightArgb).toInt()
            val hrefUrl = Url("book/${book.id}.xhtml") ?: Url("chapter.xhtml")!!

            val locator = Locator(
                href = hrefUrl,
                mediaType = MediaType.XHTML,
                title = book.title,
                locations = Locator.Locations(
                    progression = progression,
                    totalProgression = progression,
                    position = annotation.pageNumber.coerceAtLeast(1),
                    otherLocations = mapOf(
                        "charOffset" to annotation.charOffset,
                        "endCharOffset" to annotation.endCharOffset
                    )
                ),
                text = Locator.Text(highlight = quoteText)
            )

            return Decoration(
                id = annotation.id,
                locator = locator,
                style = Decoration.Style.Highlight(
                    tint = tintArgb,
                    isActive = false
                ),
                extras = mapOf(
                    "charOffset" to annotation.charOffset,
                    "endCharOffset" to annotation.endCharOffset,
                    "quote" to quoteText
                )
            )
        }

        fun decodeJsEvalString(raw: String?): String {
            if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return ""
            return runCatching {
                JSONArray("[$raw]").getString(0)
            }.getOrElse {
                raw.removeSurrounding("\"")
            }
        }

        fun copyTextToSystemClipboard(context: Context, text: String) {
            val cleaned = text.trim()
            if (cleaned.isEmpty()) return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Book Quote", cleaned))
        }

        /**
         * Builds the Readium XHTML document loaded into the Readium [WebView], including:
         * - Publication-driven `dir="rtl"` / `dir="ltr"` and `lang` attributes
         * - Paragraph nodes annotated with exact canonical character offsets `[data-start, data-end)`
         * - Horizontal Swipe multi-column pagination & Vertical Scroll layout engines
         * - Readium `Decoration` & `HtmlDecorationTemplates` rendering engine (`window.readiumApplyDecorations`)
         * - Selection offset extractor (`window.__readiumGetSelectionPayload`) with live `selectionchange` caching
         */
        fun buildReadiumDocumentHtml(
            book: Book,
            canonicalText: String,
            preferences: ReaderPreferences,
            initialCharOffset: Int
        ): String {
            val epubPrefs = preferences.toReadiumEpubPreferences()
            val fontSizeMultiplier = (epubPrefs.fontSize ?: 1.0).toFloat()
            val fontSizePx = (ReaderFontSizeStep.BASE_FONT_SIZE_SP * fontSizeMultiplier).coerceIn(12f, 36f)
            val lineHeight = (epubPrefs.lineHeight ?: 1.5).toFloat()
            val marginPx = if (preferences.publisherStyles) 24 else preferences.pageMarginDp
            val textAlignCss = if (preferences.publisherStyles) {
                "start"
            } else {
                when (preferences.textAlignment) {
                    ReaderTextAlign.JUSTIFY -> "justify"
                    ReaderTextAlign.START -> "start"
                }
            }
            val bgHex = String.format(Locale.US, "#%06X", 0xFFFFFF and preferences.readerTheme.backgroundArgb.toInt())
            val textHex = String.format(Locale.US, "#%06X", 0xFFFFFF and preferences.readerTheme.textArgb.toInt())
            val isDark = preferences.readerTheme.isDarkSurface
            val selBg = if (isDark) "rgba(250, 204, 21, 0.35)" else "rgba(253, 224, 71, 0.48)"
            val isRtl = book.readingDirection == BookReadingDirection.RTL
            val dirAttr = if (isRtl) "rtl" else "ltr"
            val langAttr = book.language.ifBlank { if (isRtl) "ar" else "en" }
            val isPaged = book.layoutType == PublicationLayoutType.REFLOWABLE &&
                preferences.navigationMode == ReaderNavigationMode.HORIZONTAL_SWIPE
            val isFixedLayout = book.layoutType == PublicationLayoutType.FIXED
            val fitScale = when (preferences.fixedLayoutFitMode) {
                FixedLayoutFitMode.FIT_WIDTH -> 1.05f
                FixedLayoutFitMode.FIT_PAGE -> 1.0f
                FixedLayoutFitMode.ACTUAL_SIZE -> 1.22f
            }

            // Build paragraph HTML with exact canonical character offsets
            val paragraphsHtml = StringBuilder(canonicalText.length + 4096)
            var cursor = 0
            val len = canonicalText.length
            while (cursor < len) {
                val nextBreak = canonicalText.indexOf("\n\n", cursor)
                val segEnd = if (nextBreak >= 0) nextBreak else len
                val paragraphText = canonicalText.substring(cursor, segEnd)
                if (paragraphText.isNotEmpty()) {
                    paragraphsHtml.append("<p class=\"r2-para\" data-start=\"")
                        .append(cursor)
                        .append("\" data-end=\"")
                        .append(segEnd)
                        .append("\">")
                        .append(escapeHtml(paragraphText))
                        .append("</p>")
                }
                cursor = if (nextBreak >= 0) nextBreak + 2 else len
            }

            return """
<!DOCTYPE html>
<html dir="$dirAttr" lang="$langAttr">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.8, maximum-scale=3.5, user-scalable=${if (isFixedLayout) "yes" else "no"}" />
<style>
:root {
    --RS__fontSize: ${fontSizePx}px;
    --RS__lineHeight: $lineHeight;
    --RS__pageMargins: ${marginPx}px;
    --RS__textAlign: $textAlignCss;
    --RS__backgroundColor: $bgHex;
    --RS__textColor: $textHex;
    --RS__selectionBackgroundColor: $selBg;
}
* {
    box-sizing: border-box;
    -webkit-tap-highlight-color: transparent;
}
::selection {
    background: var(--RS__selectionBackgroundColor);
}
html, body {
    margin: 0;
    padding: 0;
    width: 100%;
    height: 100%;
    background-color: var(--RS__backgroundColor);
    color: var(--RS__textColor);
    font-family: Georgia, "Times New Roman", serif;
    font-size: var(--RS__fontSize);
    line-height: var(--RS__lineHeight);
    text-align: var(--RS__textAlign);
    direction: $dirAttr;
    -webkit-user-select: text;
    user-select: text;
}
body.paged-mode {
    overflow: hidden;
    height: 100vh;
    width: 100%;
}
body.scroll-mode {
    overflow-x: hidden;
    overflow-y: auto;
    height: auto;
    min-height: 100vh;
}
#r2-viewport {
    width: 100%;
    height: 100vh;
    overflow: hidden;
    position: relative;
}
body.scroll-mode #r2-viewport {
    height: auto;
    min-height: 100vh;
    overflow: visible;
}
#r2-book-content {
    position: relative;
    padding: 58px var(--RS__pageMargins) 56px var(--RS__pageMargins);
}
#r2-gpu-highlight-layer {
    position: absolute;
    top: 0;
    left: 0;
    width: 0;
    height: 0;
    pointer-events: none;
    z-index: 3;
    overflow: visible;
}
.r2-gpu-pill-group {
    position: absolute;
    top: 0;
    left: 0;
    width: 0;
    height: 0;
    pointer-events: none;
    opacity: 0;
    will-change: opacity;
}
.r2-gpu-pill-cap-l,
.r2-gpu-pill-mid,
.r2-gpu-pill-cap-r {
    position: absolute;
    top: 0;
    left: 0;
    height: 24px;
    transform-origin: 0 0;
    will-change: transform;
    backface-visibility: hidden;
    background-color: rgba(56, 189, 248, 0.30);
}
.r2-gpu-pill-cap-l {
    width: 5px;
    border-radius: 5px 0 0 5px;
}
.r2-gpu-pill-mid {
    width: 1px;
}
.r2-gpu-pill-cap-r {
    width: 5px;
    border-radius: 0 5px 5px 0;
}
body.paged-mode #r2-book-content {
    width: 100%;
    height: calc(100vh - 114px);
    margin-top: 58px;
    margin-bottom: 56px;
    margin-left: auto;
    margin-right: auto;
    padding: 0 var(--RS__pageMargins);
    column-count: 1;
    column-width: calc(100vw - (var(--RS__pageMargins) * 2));
    column-gap: calc(var(--RS__pageMargins) * 2);
    column-fill: auto;
    will-change: transform;
    transition: transform 0.22s cubic-bezier(0.22, 1, 0.36, 1);
}
p.r2-para {
    margin: 0 0 0.85em 0;
    white-space: pre-wrap;
    word-wrap: break-word;
}
mark.r2-decoration-highlight {
    color: inherit;
    border-radius: 3px;
    padding: 1px 0;
    cursor: pointer;
}
mark.r2-decoration-underline {
    background: transparent !important;
    color: inherit;
    border-bottom: 2px solid currentColor;
    cursor: pointer;
}
</style>
</head>
<body class="${if (isPaged) "paged-mode" else "scroll-mode"}">
<div id="r2-viewport">
    <div id="r2-book-content"><div id="r2-gpu-highlight-layer" aria-hidden="true"><div class="r2-gpu-pill-group" id="r2-pill-0"><div class="r2-gpu-pill-cap-l"></div><div class="r2-gpu-pill-mid"></div><div class="r2-gpu-pill-cap-r"></div></div><div class="r2-gpu-pill-group" id="r2-pill-1"><div class="r2-gpu-pill-cap-l"></div><div class="r2-gpu-pill-mid"></div><div class="r2-gpu-pill-cap-r"></div></div></div>$paragraphsHtml</div>
</div>
<script>
(function() {
    var state = {
        isPaged: $isPaged,
        isFixedLayout: $isFixedLayout,
        fitScale: $fitScale,
        isRtl: $isRtl,
        currentPage: 0,
        totalPages: 1,
        currentCharOffset: $initialCharOffset,
        isInitialRestorePending: true,
        isAudioWordTapEnabled: false,
        decorationsByGroup: {},
        lastNonEmptySelection: null
    };
    var lastProgrammaticNavTime = 0;
    var lastUserTouchScrollTime = 0;
    var lastManualNavNotifyTime = 0;

    window.__readiumSetAudioWordTapEnabled = function(enabled) {
        state.isAudioWordTapEnabled = !!enabled;
    };

    var viewportEl = document.getElementById('r2-viewport');
    var contentEl = document.getElementById('r2-book-content');
    var paragraphs = Array.prototype.slice.call(document.querySelectorAll('p.r2-para'));

    paragraphs.forEach(function(p) {
        p.dataset.rawText = p.textContent || "";
    });

    function syncPagedGeometry() {
        if (!state.isPaged) {
            contentEl.style.width = '';
            contentEl.style.columnCount = '';
            contentEl.style.columnWidth = '';
            contentEl.style.columnGap = '';
            contentEl.style.paddingLeft = '';
            contentEl.style.paddingRight = '';
            return;
        }
        var rawW = (viewportEl && viewportEl.getBoundingClientRect().width) ||
            document.documentElement.clientWidth ||
            window.innerWidth;
        var pageW = Math.max(1, Math.floor(rawW));
        var rawMargin = parseFloat(
            getComputedStyle(document.documentElement).getPropertyValue('--RS__pageMargins')
        ) || 24;
        var marginPx = Math.max(8, Math.round(rawMargin));
        var colW = Math.max(40, pageW - (marginPx * 2));
        var colGap = marginPx * 2;

        state.pageStride = pageW;
        contentEl.style.width = pageW + 'px';
        contentEl.style.paddingLeft = marginPx + 'px';
        contentEl.style.paddingRight = marginPx + 'px';
        contentEl.style.columnCount = '1';
        contentEl.style.columnWidth = colW + 'px';
        contentEl.style.columnGap = colGap + 'px';
    }

    function getPageStride() {
        if (state.isPaged && state.pageStride > 0) {
            return state.pageStride;
        }
        var rawW = (viewportEl && viewportEl.getBoundingClientRect().width) ||
            document.documentElement.clientWidth ||
            window.innerWidth;
        return Math.max(1, Math.floor(rawW));
    }

    function computeTotalPages() {
        syncPagedGeometry();
        if (!state.isPaged) {
            var docH = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight, 1);
            var winH = Math.max(window.innerHeight, 1);
            return Math.max(1, Math.ceil(docH / winH));
        }
        var scrollW = contentEl.scrollWidth || getPageStride();
        var stride = getPageStride();
        return Math.max(1, Math.round(scrollW / stride));
    }

    function applyPageTransform(pageIdx, animated) {
        state.totalPages = computeTotalPages();
        state.currentPage = Math.max(0, Math.min(pageIdx, state.totalPages - 1));
        if (!state.isPaged) return;
        contentEl.style.transition = animated ? 'transform 0.22s cubic-bezier(0.22, 1, 0.36, 1)' : 'none';
        var stride = getPageStride();
        var shift = state.isRtl ? (state.currentPage * stride) : -(state.currentPage * stride);
        contentEl.style.transform = 'translate3d(' + shift + 'px, 0, 0)';
    }

    function findParagraphPage(targetOffset) {
        var bestPara = paragraphs[0];
        for (var i = 0; i < paragraphs.length; i++) {
            var p = paragraphs[i];
            var s = parseInt(p.dataset.start || "0", 10);
            var e = parseInt(p.dataset.end || "0", 10);
            if (targetOffset >= s && targetOffset <= e) {
                bestPara = p;
                break;
            }
            if (s <= targetOffset) {
                bestPara = p;
            }
        }
        if (!bestPara) return 0;
        if (!state.isPaged) {
            return Math.max(0, Math.floor(bestPara.offsetTop / Math.max(1, window.innerHeight)));
        }
        var rect = bestPara.getBoundingClientRect();
        var currentShift = state.isRtl ? -(state.currentPage * getPageStride()) : (state.currentPage * getPageStride());
        var absLeft = rect.left + currentShift;
        var page = state.isRtl
            ? Math.round(-absLeft / getPageStride())
            : Math.round(absLeft / getPageStride());
        return Math.max(0, Math.min(page, state.totalPages - 1));
    }

    function computeCurrentViewportLocator() {
        var winW = window.innerWidth;
        var winH = window.innerHeight;
        var firstOffset = state.currentCharOffset;
        var foundFirst = false;
        var visibleParts = [];

        // Probe the exact top-start visible word first so long multi-page paragraphs report
        // the exact visible text on the current page rather than a paragraph start pages earlier.
        var rawMargin = parseFloat(
            getComputedStyle(document.documentElement).getPropertyValue('--RS__pageMargins')
        ) || 24;
        var probeXs = state.isRtl
            ? [Math.max(12, winW - rawMargin - 16), Math.round(winW * 0.5), Math.max(12, winW - rawMargin - 48)]
            : [Math.min(winW - 12, rawMargin + 16), Math.round(winW * 0.5), Math.min(winW - 12, rawMargin + 48)];
        var probeYs = [74, 108, 150];
        for (var py = 0; py < probeYs.length && !foundFirst; py++) {
            for (var px = 0; px < probeXs.length && !foundFirst; px++) {
                var hit = resolveWordAtPoint(probeXs[px], probeYs[py]);
                if (hit && hit.charOffset >= 0) {
                    firstOffset = hit.charOffset;
                    foundFirst = true;
                }
            }
        }

        for (var i = 0; i < paragraphs.length; i++) {
            var p = paragraphs[i];
            var r = p.getBoundingClientRect();
            var isVisible = false;
            if (state.isPaged) {
                isVisible = (r.right > 8 && r.left < winW - 8 && r.bottom > 40 && r.top < winH - 40);
            } else {
                isVisible = (r.bottom > 56 && r.top < winH - 56);
            }
            if (isVisible) {
                if (!foundFirst) {
                    firstOffset = parseInt(p.dataset.start || "0", 10);
                    foundFirst = true;
                }
                visibleParts.push(p.dataset.rawText || p.textContent || "");
            }
        }
        return {
            charOffset: firstOffset,
            visibleText: visibleParts.join("\n\n").slice(0, 1200)
        };
    }

    function notifyLocator(isInitialRestore) {
        state.totalPages = computeTotalPages();
        var loc = computeCurrentViewportLocator();
        if (!isInitialRestore) {
            state.currentCharOffset = loc.charOffset;
        }
        var posOneBased = state.isPaged
            ? (state.currentPage + 1)
            : Math.min(state.totalPages, Math.floor(window.scrollY / Math.max(1, window.innerHeight)) + 1);
        if (window.ReadiumBridge && window.ReadiumBridge.onLocatorChanged) {
            window.ReadiumBridge.onLocatorChanged(
                state.currentCharOffset,
                posOneBased,
                state.totalPages,
                loc.visibleText,
                !!isInitialRestore
            );
        }
    }

    window.__readiumJumpToCharOffset = function(targetOffset) {
        lastProgrammaticNavTime = Date.now();
        state.currentCharOffset = Math.max(0, targetOffset || 0);
        state.totalPages = computeTotalPages();
        if (state.isPaged) {
            var stride = getPageStride();
            var exactRect = measureWordRectInContent(state.currentCharOffset, state.currentCharOffset + 1);
            var targetPage;
            if (exactRect && exactRect.h > 0) {
                targetPage = state.isRtl
                    ? Math.max(0, Math.floor((stride - 1 - exactRect.x) / stride))
                    : Math.max(0, Math.floor(exactRect.x / stride));
                targetPage = Math.max(0, Math.min(targetPage, state.totalPages - 1));
            } else {
                applyPageTransform(0, false);
                targetPage = findParagraphPage(state.currentCharOffset);
            }
            applyPageTransform(targetPage, false);
        } else {
            var scrollRect = measureWordRectInContent(state.currentCharOffset, state.currentCharOffset + 1);
            if (scrollRect && scrollRect.h > 0) {
                var targetTop = Math.max(0, window.scrollY + scrollRect.clientTop - 72);
                window.scrollTo({ top: targetTop, behavior: 'instant' });
            } else {
                for (var i = 0; i < paragraphs.length; i++) {
                    var p = paragraphs[i];
                    var s = parseInt(p.dataset.start || "0", 10);
                    var e = parseInt(p.dataset.end || "0", 10);
                    if (state.currentCharOffset >= s && state.currentCharOffset <= e) {
                        window.scrollTo({ top: Math.max(0, p.offsetTop - 64), behavior: 'instant' });
                        break;
                    }
                }
            }
        }
        notifyLocator(state.isInitialRestorePending);
        state.isInitialRestorePending = false;
    };

    window.__readiumUpdateSettings = function(cfg) {
        if (!cfg) return;
        var rootStyle = document.documentElement.style;
        rootStyle.setProperty('--RS__fontSize', cfg.fontSizePx + 'px');
        rootStyle.setProperty('--RS__lineHeight', String(cfg.lineHeight));
        rootStyle.setProperty('--RS__pageMargins', cfg.marginPx + 'px');
        rootStyle.setProperty('--RS__textAlign', cfg.textAlign);
        rootStyle.setProperty('--RS__backgroundColor', cfg.bgColor);
        rootStyle.setProperty('--RS__textColor', cfg.textColor);
        rootStyle.setProperty('--RS__selectionBackgroundColor', cfg.selBgColor);

        state.isPaged = !!cfg.isPaged;
        state.isFixedLayout = !!cfg.isFixedLayout;
        state.isRtl = !!cfg.isRtl;
        document.body.className = state.isPaged ? 'paged-mode' : 'scroll-mode';
        syncPagedGeometry();
        if (!state.isPaged) {
            contentEl.style.transform = 'none';
        }
        if (typeof cfg.targetCharOffset === 'number' && cfg.targetCharOffset >= 0) {
            state.currentCharOffset = cfg.targetCharOffset;
        }
        setTimeout(function() {
            window.__readiumJumpToCharOffset(state.currentCharOffset);
        }, 40);
    };

    function getCharOffsetInParagraph(para, container, offset) {
        try {
            var preRange = document.createRange();
            preRange.selectNodeContents(para);
            preRange.setEnd(container, offset);
            return preRange.toString().length;
        } catch (e) {
            return 0;
        }
    }

    function findEnclosingParagraph(node) {
        var cur = node;
        while (cur && cur !== document.body) {
            if (cur.nodeType === 1 && cur.classList && cur.classList.contains('r2-para')) {
                return cur;
            }
            cur = cur.parentNode;
        }
        return null;
    }

    function computeCurrentSelectionObject() {
        var sel = window.getSelection ? window.getSelection() : null;
        if (!sel || sel.rangeCount === 0) return null;
        var text = (sel.toString() || "").trim();
        if (!text) return null;

        var range = sel.getRangeAt(0);
        var startPara = findEnclosingParagraph(range.startContainer);
        var endPara = findEnclosingParagraph(range.endContainer);

        if (!startPara || !endPara) {
            for (var i = 0; i < paragraphs.length; i++) {
                var p = paragraphs[i];
                try {
                    if (range.intersectsNode(p)) {
                        if (!startPara) startPara = p;
                        endPara = p;
                    }
                } catch (err) {}
            }
        }

        var absStart = -1;
        var absEnd = -1;

        if (startPara) {
            var pStart = parseInt(startPara.dataset.start || "0", 10);
            if (startPara.contains(range.startContainer)) {
                absStart = pStart + getCharOffsetInParagraph(startPara, range.startContainer, range.startOffset);
            } else {
                absStart = pStart;
            }
        }

        if (endPara) {
            var pEndStart = parseInt(endPara.dataset.start || "0", 10);
            var pEndMax = parseInt(endPara.dataset.end || "0", 10);
            if (endPara.contains(range.endContainer)) {
                absEnd = pEndStart + getCharOffsetInParagraph(endPara, range.endContainer, range.endOffset);
            } else {
                absEnd = pEndMax;
            }
        }

        if (absStart >= 0 && absEnd <= absStart) {
            absEnd = absStart + text.length;
        }

        return {
            text: text,
            startOffset: absStart,
            endOffset: absEnd
        };
    }

    document.addEventListener('selectionchange', function() {
        var current = computeCurrentSelectionObject();
        if (current && current.text.length > 0) {
            state.lastNonEmptySelection = current;
            if (window.ReadiumBridge && window.ReadiumBridge.onSelectionChanged) {
                window.ReadiumBridge.onSelectionChanged(
                    current.text,
                    current.startOffset,
                    current.endOffset
                );
            }
        }
    });

    window.__readiumClearSelection = function() {
        state.lastNonEmptySelection = null;
        if (window.getSelection) {
            window.getSelection().removeAllRanges();
        }
    };

    window.__readiumGetSelectionPayload = function(clearAfter) {
        var current = computeCurrentSelectionObject() || state.lastNonEmptySelection;
        if (clearAfter) {
            state.lastNonEmptySelection = null;
            if (window.getSelection) {
                window.getSelection().removeAllRanges();
            }
        }
        if (!current) {
            return JSON.stringify({ text: "", startOffset: -1, endOffset: -1 });
        }
        return JSON.stringify(current);
    };

    function escapeHtmlJs(str) {
        return str
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    // Readium DecorableNavigator Decoration rendering engine
    window.readiumApplyDecorations = function(group, decorations, templates) {
        state.decorationsByGroup[group] = decorations || [];
        var allDecorations = [];
        Object.keys(state.decorationsByGroup).forEach(function(g) {
            (state.decorationsByGroup[g] || []).forEach(function(d) {
                allDecorations.push({ group: g, dec: d });
            });
        });

        paragraphs.forEach(function(p) {
            var raw = p.dataset.rawText || "";
            var pStart = parseInt(p.dataset.start || "0", 10);
            var pEnd = parseInt(p.dataset.end || "0", 10);
            var intervals = [];

            allDecorations.forEach(function(entry) {
                var d = entry.dec;
                var dStart = typeof d.charOffset === 'number' ? d.charOffset : -1;
                var dEnd = typeof d.endCharOffset === 'number' ? d.endCharOffset : -1;
                if (dEnd > dStart && dStart < pEnd && dEnd > pStart) {
                    var localStart = Math.max(0, dStart - pStart);
                    var localEnd = Math.min(raw.length, dEnd - pStart);
                    if (localEnd > localStart) {
                        intervals.push({
                            start: localStart,
                            end: localEnd,
                            id: d.id,
                            group: entry.group,
                            tintCss: d.tintCss || 'rgba(250, 204, 21, 0.42)',
                            styleClass: d.styleClass || 'highlight'
                        });
                    }
                } else if (d.quote && d.quote.length > 0) {
                    var qLower = d.quote.toLowerCase();
                    var rawLower = raw.toLowerCase();
                    var idx = rawLower.indexOf(qLower);
                    while (idx >= 0) {
                        intervals.push({
                            start: idx,
                            end: Math.min(raw.length, idx + d.quote.length),
                            id: d.id,
                            group: entry.group,
                            tintCss: d.tintCss || 'rgba(250, 204, 21, 0.42)',
                            styleClass: d.styleClass || 'highlight'
                        });
                        idx = rawLower.indexOf(qLower, idx + d.quote.length);
                    }
                }
            });

            if (intervals.length === 0) {
                if (p.innerHTML !== escapeHtmlJs(raw)) {
                    p.textContent = raw;
                }
                return;
            }

            intervals.sort(function(a, b) {
                if (a.start !== b.start) return a.start - b.start;
                return b.end - a.end;
            });
            var htmlOut = "";
            var cursor = 0;
            intervals.forEach(function(iv) {
                var segStart = Math.max(iv.start, cursor);
                var segEnd = Math.min(iv.end, raw.length);
                if (segEnd <= segStart) return;
                if (segStart > cursor) {
                    htmlOut += escapeHtmlJs(raw.slice(cursor, segStart));
                }
                var cls = iv.styleClass === 'underline' ? 'r2-decoration-underline' : 'r2-decoration-highlight';
                var bgStyle = iv.styleClass === 'underline'
                    ? ('border-bottom-color:' + iv.tintCss + ';')
                    : ('background-color:' + iv.tintCss + ';');
                htmlOut += '<mark class="' + cls + '" data-decoration-id="' + iv.id + '" data-decoration-group="' + iv.group + '" style="' + bgStyle + '">' +
                    escapeHtmlJs(raw.slice(segStart, segEnd)) +
                    '</mark>';
                cursor = segEnd;
            });
            if (cursor < raw.length) {
                htmlOut += escapeHtmlJs(raw.slice(cursor));
            }
            p.innerHTML = htmlOut;
        });
    };

    // =========================================================================
    // GPU-COMPOSITED SMOOTH WORD HIGHLIGHT OVERLAY (ZERO DOM MUTATION PER WORD)
    // Animates strictly via transform: translate3d/scale + opacity on Chromium's
    // GPU compositor thread with 2 pooled 3-part pills (glide same line, cross-fade new line).
    // =========================================================================
    var gpuPills = [
        {
            group: document.getElementById('r2-pill-0'),
            capL: document.querySelector('#r2-pill-0 .r2-gpu-pill-cap-l'),
            mid: document.querySelector('#r2-pill-0 .r2-gpu-pill-mid'),
            capR: document.querySelector('#r2-pill-0 .r2-gpu-pill-cap-r'),
            rect: null,
            visible: false
        },
        {
            group: document.getElementById('r2-pill-1'),
            capL: document.querySelector('#r2-pill-1 .r2-gpu-pill-cap-l'),
            mid: document.querySelector('#r2-pill-1 .r2-gpu-pill-mid'),
            capR: document.querySelector('#r2-pill-1 .r2-gpu-pill-cap-r'),
            rect: null,
            visible: false
        }
    ];
    var activePillIdx = 0;

    function computePillTransforms(r) {
        var padX = 3;
        var padY = 2;
        var x = r.x - padX;
        var y = r.y - padY;
        var w = Math.max(12, r.w + padX * 2);
        var h = Math.max(14, r.h + padY * 2);
        var capW = 5;
        var sy = h / 24.0;
        var midW = Math.max(0.5, w - capW * 2 + 1.0);
        return {
            capL: 'translate3d(' + x.toFixed(2) + 'px,' + y.toFixed(2) + 'px,0) scaleY(' + sy.toFixed(4) + ')',
            mid: 'translate3d(' + (x + capW - 0.5).toFixed(2) + 'px,' + y.toFixed(2) + 'px,0) scale(' + midW.toFixed(2) + ',' + sy.toFixed(4) + ')',
            capR: 'translate3d(' + (x + w - capW).toFixed(2) + 'px,' + y.toFixed(2) + 'px,0) scaleY(' + sy.toFixed(4) + ')'
        };
    }

    function applyPillColor(pill, isDark, isPaused) {
        var bg = isDark
            ? (isPaused ? 'rgba(96, 165, 250, 0.28)' : 'rgba(56, 189, 248, 0.36)')
            : (isPaused ? 'rgba(59, 130, 246, 0.22)' : 'rgba(14, 165, 233, 0.28)');
        pill.capL.style.backgroundColor = bg;
        pill.mid.style.backgroundColor = bg;
        pill.capR.style.backgroundColor = bg;
    }

    function animatePillToRect(pill, fromRect, toRect, durationMs, fadeIn) {
        var toT = computePillTransforms(toRect);
        var opts = {
            duration: Math.max(70, Math.min(180, durationMs)),
            easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
            fill: 'forwards'
        };
        if (fromRect && pill.capL.animate) {
            var fromT = computePillTransforms(fromRect);
            pill.capL.animate([{ transform: fromT.capL }, { transform: toT.capL }], opts);
            pill.mid.animate([{ transform: fromT.mid }, { transform: toT.mid }], opts);
            pill.capR.animate([{ transform: fromT.capR }, { transform: toT.capR }], opts);
        } else {
            pill.capL.style.transform = toT.capL;
            pill.mid.style.transform = toT.mid;
            pill.capR.style.transform = toT.capR;
        }
        if (fadeIn && pill.group.animate) {
            pill.group.animate([{ opacity: 0 }, { opacity: 1 }], {
                duration: 120,
                easing: 'ease-out',
                fill: 'forwards'
            });
        } else {
            pill.group.style.opacity = '1';
        }
        pill.rect = toRect;
        pill.visible = true;
    }

    function fadeOutPill(pill, durationMs) {
        if (!pill || !pill.visible) return;
        pill.visible = false;
        pill.rect = null;
        if (pill.group.animate) {
            pill.group.animate([{ opacity: 1 }, { opacity: 0 }], {
                duration: durationMs || 110,
                easing: 'ease-in',
                fill: 'forwards'
            });
        } else {
            pill.group.style.opacity = '0';
        }
    }

    function findParagraphForCharOffset(offset) {
        var lo = 0;
        var hi = paragraphs.length - 1;
        while (lo <= hi) {
            var mid = (lo + hi) >> 1;
            var p = paragraphs[mid];
            var s = parseInt(p.dataset.start || '0', 10);
            var e = parseInt(p.dataset.end || '0', 10);
            if (offset < s) {
                hi = mid - 1;
            } else if (offset >= e) {
                lo = mid + 1;
            } else {
                return p;
            }
        }
        return paragraphs[Math.max(0, Math.min(lo, paragraphs.length - 1))] || null;
    }

    function resolveDomTextPoint(para, localCharOffset) {
        var walker = document.createTreeWalker(para, NodeFilter.SHOW_TEXT, null, false);
        var node = walker.nextNode();
        var acc = 0;
        var lastNode = null;
        while (node) {
            lastNode = node;
            var len = node.nodeValue ? node.nodeValue.length : 0;
            if (localCharOffset <= acc + len) {
                return { node: node, offset: Math.max(0, localCharOffset - acc) };
            }
            acc += len;
            node = walker.nextNode();
        }
        if (lastNode) {
            return { node: lastNode, offset: lastNode.nodeValue ? lastNode.nodeValue.length : 0 };
        }
        return null;
    }

    function measureWordRectInContent(startOffset, endOffset) {
        var p = findParagraphForCharOffset(startOffset);
        if (!p) return null;
        var pStart = parseInt(p.dataset.start || '0', 10);
        var pEnd = parseInt(p.dataset.end || '0', 10);
        var localStart = Math.max(0, startOffset - pStart);
        var localEnd = Math.max(localStart + 1, Math.min(pEnd - pStart, endOffset - pStart));

        var ptStart = resolveDomTextPoint(p, localStart);
        var ptEnd = resolveDomTextPoint(p, localEnd);
        if (!ptStart || !ptEnd) return null;

        try {
            var range = document.createRange();
            range.setStart(ptStart.node, ptStart.offset);
            range.setEnd(ptEnd.node, ptEnd.offset);
            var rects = range.getClientRects();
            if (!rects || rects.length === 0) return null;
            var best = rects[0];
            for (var i = 0; i < rects.length; i++) {
                if (rects[i].width > 1 && rects[i].height > 1) {
                    best = rects[i];
                    break;
                }
            }
            var contentRect = contentEl.getBoundingClientRect();
            return {
                x: best.left - contentRect.left,
                y: best.top - contentRect.top,
                w: best.width,
                h: best.height,
                clientLeft: best.left,
                clientRight: best.right,
                clientTop: best.top,
                clientBottom: best.bottom
            };
        } catch (err) {
            return null;
        }
    }

    window.__readiumClearGpuWordHighlight = function() {
        fadeOutPill(gpuPills[0], 130);
        fadeOutPill(gpuPills[1], 130);
    };

    window.__readiumSetGpuWordHighlight = function(startOffset, endOffset, estDurationMs, isPaused, isDark, autoFollow) {
        var r = measureWordRectInContent(startOffset, endOffset);
        if (!r || r.w <= 0 || r.h <= 0) return;

        // Auto page-turn or smooth scroll ONLY when autoFollow is enabled (user has not detached to browse freely)
        if (autoFollow !== false) {
            var winW = window.innerWidth;
            var winH = window.innerHeight;
            if (state.isPaged) {
                var stride = getPageStride();
                if (r.clientLeft < 4 || r.clientRight > winW - 4) {
                    var targetPage = state.isRtl
                        ? Math.max(0, Math.floor((stride - 1 - r.x) / stride))
                        : Math.max(0, Math.floor(r.x / stride));
                    targetPage = Math.max(0, Math.min(targetPage, state.totalPages - 1));
                    if (targetPage !== state.currentPage) {
                        lastProgrammaticNavTime = Date.now();
                        applyPageTransform(targetPage, true);
                        setTimeout(function() { notifyLocator(false); }, 230);
                    }
                }
            } else {
                if (r.clientTop < 76 || r.clientBottom > winH - 132) {
                    lastProgrammaticNavTime = Date.now();
                    var targetScrollY = Math.max(0, window.scrollY + r.clientTop - Math.round(winH * 0.34));
                    window.scrollTo({ top: targetScrollY, behavior: 'smooth' });
                }
            }
        }

        var curPill = gpuPills[activePillIdx];
        var prevRect = curPill.visible ? curPill.rect : null;
        var sameLine = prevRect !== null &&
            Math.abs(r.y - prevRect.y) <= Math.max(prevRect.h, r.h) * 0.65 &&
            Math.abs(r.x - prevRect.x) < getPageStride() * 0.85;

        var animDur = Math.max(85, Math.min(165, Math.round((estDurationMs || 180) * 0.55)));

        if (sameLine) {
            applyPillColor(curPill, isDark, isPaused);
            animatePillToRect(curPill, prevRect, r, animDur, false);
        } else {
            // New visual line or first word: cross-fade cleanly so pill never sweeps diagonally across text
            fadeOutPill(curPill, 100);
            activePillIdx = 1 - activePillIdx;
            var nextPill = gpuPills[activePillIdx];
            applyPillColor(nextPill, isDark, isPaused);
            var startRect = {
                x: state.isRtl ? (r.x + r.w * 0.35) : r.x,
                y: r.y,
                w: Math.max(10, r.w * 0.65),
                h: r.h
            };
            animatePillToRect(nextPill, startRect, r, 125, true);
        }
    };

    function findClosestMark(node, clientX, clientY) {
        var cur = node;
        while (cur && cur !== document.body) {
            if (cur.nodeType === 1 && cur.tagName && cur.tagName.toLowerCase() === 'mark' && cur.hasAttribute('data-decoration-id')) {
                return cur;
            }
            cur = cur.parentNode;
        }
        if (typeof clientX === 'number' && typeof clientY === 'number' && document.elementFromPoint) {
            var el = document.elementFromPoint(clientX, clientY);
            while (el && el !== document.body) {
                if (el.nodeType === 1 && el.tagName && el.tagName.toLowerCase() === 'mark' && el.hasAttribute('data-decoration-id')) {
                    return el;
                }
                el = el.parentNode;
            }
        }
        return null;
    }

    function isWordBoundaryChar(ch) {
        if (!ch) return true;
        return /[\s.,!?;:"'()\[\]{}«»“”‘’،؛؟—–\-\/|\\<>]/.test(ch);
    }

    function resolveWordAtPoint(clientX, clientY) {
        var container = null;
        var offset = 0;
        try {
            if (document.caretRangeFromPoint) {
                var rng = document.caretRangeFromPoint(clientX, clientY);
                if (rng) {
                    container = rng.startContainer;
                    offset = rng.startOffset;
                }
            } else if (document.caretPositionFromPoint) {
                var pos = document.caretPositionFromPoint(clientX, clientY);
                if (pos) {
                    container = pos.offsetNode;
                    offset = pos.offset;
                }
            }
        } catch (err) {
            return null;
        }
        if (!container) return null;
        var para = findEnclosingParagraph(container);
        if (!para) return null;

        var raw = para.dataset.rawText || para.textContent || "";
        if (!raw.length) return null;
        var pStart = parseInt(para.dataset.start || "0", 10);
        var local = getCharOffsetInParagraph(para, container, offset);
        local = Math.max(0, Math.min(local, raw.length - 1));

        // If caret landed on whitespace/punctuation, step slightly toward nearest word char
        if (isWordBoundaryChar(raw.charAt(local))) {
            if (local > 0 && !isWordBoundaryChar(raw.charAt(local - 1))) {
                local = local - 1;
            } else {
                var fwd = local;
                while (fwd < raw.length && isWordBoundaryChar(raw.charAt(fwd)) && fwd - local < 16) {
                    fwd++;
                }
                if (fwd < raw.length && !isWordBoundaryChar(raw.charAt(fwd))) {
                    local = fwd;
                }
            }
        }

        var wStart = local;
        while (wStart > 0 && !isWordBoundaryChar(raw.charAt(wStart - 1))) {
            wStart--;
        }
        var wEnd = local;
        while (wEnd < raw.length && !isWordBoundaryChar(raw.charAt(wEnd))) {
            wEnd++;
        }
        var wordText = raw.slice(wStart, wEnd).trim();
        return {
            charOffset: pStart + wStart,
            text: wordText
        };
    }

    function notifyManualNavThrottled() {
        var now = Date.now();
        if (now - lastManualNavNotifyTime < 250) return;
        lastManualNavNotifyTime = now;
        if (window.ReadiumBridge && window.ReadiumBridge.onManualNavigation) {
            window.ReadiumBridge.onManualNavigation();
        }
    }

    // Touch / Swipe / Single-Tap / Fast Double-Tap Word Jump handling inside Readium WebView
    var touchStartX = 0;
    var touchStartY = 0;
    var touchStartTime = 0;
    var touchMoved = false;
    var lastTapTime = 0;
    var lastTapX = 0;
    var lastTapY = 0;
    var pendingSingleTapTimer = null;

    document.addEventListener('touchstart', function(e) {
        if (!e.touches || e.touches.length !== 1) {
            touchMoved = true;
            return;
        }
        touchStartX = e.touches[0].clientX;
        touchStartY = e.touches[0].clientY;
        touchStartTime = Date.now();
        touchMoved = false;
    }, { passive: true });

    document.addEventListener('touchmove', function(e) {
        if (!e.touches || e.touches.length !== 1) return;
        var dx = e.touches[0].clientX - touchStartX;
        var dy = e.touches[0].clientY - touchStartY;
        if (Math.hypot(dx, dy) > 12) {
            touchMoved = true;
            if (!state.isPaged && Math.abs(dy) > 16) {
                lastUserTouchScrollTime = Date.now();
                notifyManualNavThrottled();
            }
        }
    }, { passive: true });

    document.addEventListener('touchend', function(e) {
        var now = Date.now();
        var duration = now - touchStartTime;
        var touch = (e.changedTouches && e.changedTouches[0]) ? e.changedTouches[0] : null;
        if (!touch) return;
        var dx = touch.clientX - touchStartX;
        var dy = touch.clientY - touchStartY;
        var hasSelection = window.getSelection && window.getSelection().toString().trim().length > 0;

        if (!touchMoved && duration < 360) {
            // Fast Double-Tap on a word -> jump TTS / Audiobook directly to that word (no conflict with long-press selection!)
            var isFastDoubleTap = state.isAudioWordTapEnabled &&
                lastTapTime > 0 &&
                (now - lastTapTime) < 300 &&
                Math.hypot(touch.clientX - lastTapX, touch.clientY - lastTapY) < 52;

            if (isFastDoubleTap) {
                if (pendingSingleTapTimer) {
                    clearTimeout(pendingSingleTapTimer);
                    pendingSingleTapTimer = null;
                }
                lastTapTime = 0;
                var tappedWord = resolveWordAtPoint(touch.clientX, touch.clientY);
                if (window.getSelection) {
                    window.getSelection().removeAllRanges();
                }
                setTimeout(function() {
                    if (window.getSelection) window.getSelection().removeAllRanges();
                }, 35);
                if (tappedWord && tappedWord.charOffset >= 0 && window.ReadiumBridge && window.ReadiumBridge.onWordDoubleTap) {
                    window.ReadiumBridge.onWordDoubleTap(tappedWord.charOffset, tappedWord.text || "");
                    return;
                }
            }

            if (!hasSelection) {
                var targetMark = findClosestMark(e.target, touch.clientX, touch.clientY);
                if (targetMark && window.ReadiumBridge && window.ReadiumBridge.onDecorationActivated) {
                    lastTapTime = now;
                    lastTapX = touch.clientX;
                    lastTapY = touch.clientY;
                    var rect = targetMark.getBoundingClientRect();
                    window.ReadiumBridge.onDecorationActivated(
                        targetMark.getAttribute('data-decoration-group') || 'highlights',
                        targetMark.getAttribute('data-decoration-id') || '',
                        rect.left,
                        rect.top,
                        rect.right,
                        rect.bottom
                    );
                    return;
                }

                lastTapTime = now;
                lastTapX = touch.clientX;
                lastTapY = touch.clientY;

                if (state.isAudioWordTapEnabled) {
                    if (pendingSingleTapTimer) clearTimeout(pendingSingleTapTimer);
                    pendingSingleTapTimer = setTimeout(function() {
                        pendingSingleTapTimer = null;
                        if (window.ReadiumBridge && window.ReadiumBridge.onSingleTap) {
                            window.ReadiumBridge.onSingleTap();
                        }
                    }, 260);
                } else {
                    if (window.ReadiumBridge && window.ReadiumBridge.onSingleTap) {
                        window.ReadiumBridge.onSingleTap();
                    }
                }
            }
            return;
        }

        if (state.isPaged && !hasSelection && Math.abs(dx) > Math.abs(dy) * 1.3 && Math.abs(dx) > 36) {
            if (pendingSingleTapTimer) {
                clearTimeout(pendingSingleTapTimer);
                pendingSingleTapTimer = null;
            }
            lastTapTime = 0;
            var nextDelta;
            if (state.isRtl) {
                nextDelta = dx > 0 ? 1 : -1;
            } else {
                nextDelta = dx < 0 ? 1 : -1;
            }
            applyPageTransform(state.currentPage + nextDelta, true);
            notifyManualNavThrottled();
            setTimeout(function() { notifyLocator(false); }, 230);
        }
    }, { passive: true });

    window.addEventListener('scroll', function() {
        if (!state.isPaged && !state.isInitialRestorePending) {
            var now = Date.now();
            if ((now - lastProgrammaticNavTime) > 550 && (now - lastUserTouchScrollTime) < 1500) {
                notifyManualNavThrottled();
            }
            notifyLocator(false);
        }
    }, { passive: true });

    window.addEventListener('resize', function() {
        setTimeout(function() {
            window.__readiumJumpToCharOffset(state.currentCharOffset);
        }, 50);
    });

    window.addEventListener('load', function() {
        setTimeout(function() {
            window.__readiumJumpToCharOffset(state.currentCharOffset);
        }, 30);
    });
})();
</script>
</body>
</html>
            """.trimIndent()
        }

        fun flattenTableOfContentsLinks(
            publication: Publication,
            links: List<Link> = publication.tableOfContents,
            resolveCharOffset: ((Link, Locator?) -> Int?)? = null,
            totalChars: Int = 0
        ): List<ReadiumTocEntry> {
            val out = mutableListOf<ReadiumTocEntry>()
            var counter = 0

            fun walk(items: List<Link>, depth: Int) {
                for (link in items) {
                    val idx = counter++
                    val baseLocator = publication.locatorFromLink(link)
                    val resolvedOffset = resolveCharOffset?.invoke(link, baseLocator)
                    val finalLocator = if (resolvedOffset != null) {
                        val safeTotal = totalChars.coerceAtLeast(1)
                        val totalProg = (resolvedOffset.toDouble() / safeTotal.toDouble()).coerceIn(0.0, 1.0)
                        if (baseLocator != null) {
                            baseLocator.copy(
                                title = link.title ?: baseLocator.title,
                                locations = baseLocator.locations.copy(
                                    totalProgression = baseLocator.locations.totalProgression ?: totalProg,
                                    otherLocations = baseLocator.locations.otherLocations + ("charOffset" to resolvedOffset)
                                )
                            )
                        } else {
                            Locator(
                                href = Url("chapter.xhtml")!!,
                                mediaType = MediaType.XHTML,
                                title = link.title,
                                locations = Locator.Locations(
                                    progression = totalProg,
                                    totalProgression = totalProg,
                                    otherLocations = mapOf("charOffset" to resolvedOffset)
                                )
                            )
                        }
                    } else {
                        baseLocator
                    }

                    val rawHref = link.href.toString()
                    val fallbackLabel = rawHref
                        .substringBefore('#')
                        .substringAfterLast('/')
                        .substringBeforeLast('.')
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .trim()
                        .ifBlank { "Chapter ${idx + 1}" }
                    val displayTitle = link.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackLabel

                    out.add(
                        ReadiumTocEntry(
                            id = "${idx}_$rawHref",
                            title = displayTitle,
                            depth = depth,
                            link = link,
                            locator = finalLocator
                        )
                    )
                    if (link.children.isNotEmpty()) {
                        walk(link.children, depth + 1)
                    }
                }
            }

            walk(links, 0)
            return out
        }

        private fun escapeHtml(raw: String): String {
            val sb = StringBuilder(raw.length + 64)
            for (ch in raw) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&#39;")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
