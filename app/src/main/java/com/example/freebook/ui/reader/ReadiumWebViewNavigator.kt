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
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decorationsByGroup = LinkedHashMap<String, List<Decoration>>()
    private val listenersByGroup = LinkedHashMap<String, CopyOnWriteArrayList<DecorableNavigator.Listener>>()
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

    override fun go(locator: Locator, animated: Boolean): Boolean {
        _currentLocator.value = locator
        val targetOffset = (locator.locations.otherLocations["charOffset"] as? Number)?.toInt() ?: 0
        jumpToCharOffset(targetOffset)
        return true
    }

    override fun go(link: Link, animated: Boolean): Boolean {
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
                val prog = ((safePos - 1).toDouble() / safeTotal.toDouble()).coerceIn(0.0, 1.0)
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
    width: 100vw;
}
body.scroll-mode {
    overflow-x: hidden;
    overflow-y: auto;
    height: auto;
    min-height: 100vh;
}
#r2-viewport {
    width: 100vw;
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
    padding: 58px var(--RS__pageMargins) 56px var(--RS__pageMargins);
}
body.paged-mode #r2-book-content {
    height: calc(100vh - 114px);
    margin-top: 58px;
    margin-bottom: 56px;
    padding: 0 var(--RS__pageMargins);
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
    <div id="r2-book-content">$paragraphsHtml</div>
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
        decorationsByGroup: {},
        lastNonEmptySelection: null
    };

    var contentEl = document.getElementById('r2-book-content');
    var paragraphs = Array.prototype.slice.call(document.querySelectorAll('p.r2-para'));

    paragraphs.forEach(function(p) {
        p.dataset.rawText = p.textContent || "";
    });

    function getPageStride() {
        return Math.max(1, window.innerWidth);
    }

    function computeTotalPages() {
        if (!state.isPaged) {
            var docH = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight, 1);
            var winH = Math.max(window.innerHeight, 1);
            return Math.max(1, Math.ceil(docH / winH));
        }
        var scrollW = contentEl.scrollWidth || window.innerWidth;
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
        state.currentCharOffset = Math.max(0, targetOffset || 0);
        state.totalPages = computeTotalPages();
        if (state.isPaged) {
            applyPageTransform(0, false);
            var targetPage = findParagraphPage(state.currentCharOffset);
            applyPageTransform(targetPage, false);
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

    // Touch / Swipe / Single-Tap handling inside Readium WebView
    var touchStartX = 0;
    var touchStartY = 0;
    var touchStartTime = 0;
    var touchMoved = false;

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
        }
    }, { passive: true });

    document.addEventListener('touchend', function(e) {
        var duration = Date.now() - touchStartTime;
        var touch = (e.changedTouches && e.changedTouches[0]) ? e.changedTouches[0] : null;
        if (!touch) return;
        var dx = touch.clientX - touchStartX;
        var dy = touch.clientY - touchStartY;
        var hasSelection = window.getSelection && window.getSelection().toString().trim().length > 0;

        if (!touchMoved && duration < 450) {
            if (!hasSelection) {
                var targetMark = findClosestMark(e.target, touch.clientX, touch.clientY);
                if (targetMark && window.ReadiumBridge && window.ReadiumBridge.onDecorationActivated) {
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
                if (window.ReadiumBridge && window.ReadiumBridge.onSingleTap) {
                    window.ReadiumBridge.onSingleTap();
                }
            }
            return;
        }

        if (state.isPaged && !hasSelection && Math.abs(dx) > Math.abs(dy) * 1.3 && Math.abs(dx) > 36) {
            var nextDelta;
            if (state.isRtl) {
                nextDelta = dx > 0 ? 1 : -1;
            } else {
                nextDelta = dx < 0 ? 1 : -1;
            }
            applyPageTransform(state.currentPage + nextDelta, true);
            setTimeout(function() { notifyLocator(false); }, 230);
        }
    }, { passive: true });

    window.addEventListener('scroll', function() {
        if (!state.isPaged && !state.isInitialRestorePending) {
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
