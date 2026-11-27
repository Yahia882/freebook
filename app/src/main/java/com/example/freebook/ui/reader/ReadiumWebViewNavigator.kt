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
import com.example.freebook.data.EpubTocOffsetResolver
import com.example.freebook.data.RichEpubDocument
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
    var isRichMode: Boolean = false
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

    var onLocatorUpdated: ((charOffset: Int, endCharOffset: Int, currentPosition: Int, totalPositions: Int, visibleText: String) -> Unit)? = null
    var onSingleTapToggleHud: (() -> Unit)? = null
    var onInitialPositionRestored: (() -> Unit)? = null
    var onUserManualNavigation: (() -> Unit)? = null
    var onWordDoubleTap: ((charOffset: Int, wordText: String) -> Unit)? = null
    var onLinkHistoryChanged: ((canGoBack: Boolean) -> Unit)? = null
    var onExternalLinkClicked: ((url: String) -> Unit)? = null

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

        if (isRichMode) {
            val rawHref = locator.href.toString()
            val normHref = EpubTocOffsetResolver.normalizeHrefPath(rawHref)
            val fragment = locator.locations.fragments.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: rawHref.substringAfter('#', "").takeIf { it.isNotBlank() }
                    ?.let { EpubTocOffsetResolver.percentDecode(it) }
            if (normHref.isNotEmpty() && !normHref.equals("chapter.xhtml", ignoreCase = true)) {
                jumpToHref(
                    normalizedHref = normHref,
                    fragmentId = fragment,
                    fallbackCharOffset = targetOffset,
                    pushHistory = true
                )
                return true
            }
        }

        jumpToCharOffset(targetOffset)
        return true
    }

    override fun go(link: Link, animated: Boolean): Boolean {
        val mappedLocator = tocLocatorsByHref[link.href.toString()]
            ?: tocLocatorsByHref[link.href.toString().substringBefore('#')]
        if (mappedLocator != null) {
            return go(mappedLocator, animated)
        }
        if (isRichMode) {
            val rawHref = link.href.toString()
            val normHref = EpubTocOffsetResolver.normalizeHrefPath(rawHref)
            val frag = rawHref.substringAfter('#', "").takeIf { it.isNotBlank() }
                ?.let { EpubTocOffsetResolver.percentDecode(it) }
            if (normHref.isNotEmpty()) {
                jumpToHref(normHref, frag, fallbackCharOffset = 0, pushHistory = true)
                return true
            }
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

    fun jumpToHref(
        normalizedHref: String,
        fragmentId: String?,
        fallbackCharOffset: Int = 0,
        pushHistory: Boolean = false
    ) {
        val targetWebView = webView ?: return
        val hrefArg = JSONObject.quote(normalizedHref)
        val fragArg = JSONObject.quote(fragmentId.orEmpty())
        mainHandler.post {
            targetWebView.evaluateJavascript(
                "window.__readiumJumpToHref && window.__readiumJumpToHref($hrefArg, $fragArg, $fallbackCharOffset, $pushHistory);",
                null
            )
        }
    }

    fun goBackInBook() {
        val targetWebView = webView ?: return
        mainHandler.post {
            targetWebView.evaluateJavascript(
                "window.__readiumGoBackInHistory && window.__readiumGoBackInHistory();",
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
        val linkHex = linkColorForTheme(preferences.readerTheme)
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
            put("linkColor", linkHex)
            put("selBgColor", selBg)
            put("publisherStyles", preferences.publisherStyles)
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
            endCharOffset: Int,
            currentPosition: Int,
            totalPositions: Int,
            visibleText: String,
            isInitialRestore: Boolean
        ) {
            mainHandler.post {
                val safePos = currentPosition.coerceAtLeast(1)
                val safeTotal = totalPositions.coerceAtLeast(1)
                val safeOffset = charOffset.coerceAtLeast(0)
                val safeEndOffset = endCharOffset.coerceAtLeast(safeOffset)
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
                    safeEndOffset,
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
        fun onLinkHistoryChanged(canGoBack: Boolean) {
            mainHandler.post {
                onLinkHistoryChanged?.invoke(canGoBack)
            }
        }

        @JavascriptInterface
        fun onExternalLinkClicked(url: String) {
            mainHandler.post {
                onExternalLinkClicked?.invoke(url)
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

        fun linkColorForTheme(theme: ReaderTheme): String = when (theme) {
            ReaderTheme.LIGHT -> "#0B66D4"
            ReaderTheme.SEPIA -> "#1A5FA8"
            ReaderTheme.DARK -> "#5AA9FF"
            ReaderTheme.AMOLED -> "#4CA0F8"
        }

        /**
         * Builds the Readium XHTML document loaded into the Readium [WebView], supporting BOTH:
         * - Rich EPUB mode ([richDoc] != null): preserves real publisher XHTML, scoped CSS,
         *   cover/front-matter pages, embedded images/SVG/fonts, spine page breaks, and blue
         *   clickable internal hyperlinks while annotating leaf text blocks with canonical offsets
         * - Plain-text fallback mode ([richDoc] == null): wraps canonical paragraphs safely
         */
        fun buildReadiumDocumentHtml(
            book: Book,
            canonicalText: String,
            preferences: ReaderPreferences,
            initialCharOffset: Int,
            richDoc: RichEpubDocument? = null,
            initialPageIndex: Int = 0
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
            val linkHex = linkColorForTheme(preferences.readerTheme)
            val isDark = preferences.readerTheme.isDarkSurface
            val selBg = if (isDark) "rgba(250, 204, 21, 0.35)" else "rgba(253, 224, 71, 0.48)"
            val isRtl = book.readingDirection == BookReadingDirection.RTL
            val dirAttr = if (isRtl) "rtl" else "ltr"
            val langAttr = book.language.ifBlank { if (isRtl) "ar" else "en" }
            val isPaged = book.layoutType == PublicationLayoutType.REFLOWABLE &&
                preferences.navigationMode == ReaderNavigationMode.HORIZONTAL_SWIPE
            val isFixedLayout = book.layoutType == PublicationLayoutType.FIXED
            val isRichMode = richDoc != null
            val publisherStylesOn = preferences.publisherStyles
            val fitScale = when (preferences.fixedLayoutFitMode) {
                FixedLayoutFitMode.FIT_WIDTH -> 1.05f
                FixedLayoutFitMode.FIT_PAGE -> 1.0f
                FixedLayoutFitMode.ACTUAL_SIZE -> 1.22f
            }

            val bodyContentHtml: String = if (richDoc != null) {
                richDoc.sectionsHtml
            } else {
                val paragraphsHtml = StringBuilder(canonicalText.length + 4096)
                var cursor = 0
                val len = canonicalText.length
                while (cursor < len) {
                    val nextBreak = canonicalText.indexOf("\n\n", cursor)
                    val segEnd = if (nextBreak >= 0) nextBreak else len
                    val paragraphText = canonicalText.substring(cursor, segEnd)
                    if (paragraphText.isNotEmpty()) {
                        paragraphsHtml.append("<p class=\"r2-para\" data-r2-block=\"1\" data-r2-start=\"")
                            .append(cursor)
                            .append("\" data-r2-end=\"")
                            .append(segEnd)
                            .append("\" data-start=\"")
                            .append(cursor)
                            .append("\" data-end=\"")
                            .append(segEnd)
                            .append("\">")
                            .append(escapeHtml(paragraphText))
                            .append("</p>")
                    }
                    cursor = if (nextBreak >= 0) nextBreak + 2 else len
                }
                paragraphsHtml.toString()
            }

            val publisherCssTag = if (richDoc != null && richDoc.publisherCss.isNotBlank()) {
                "<style id=\"r2-publisher-styles\">\n${richDoc.publisherCss}\n</style>"
            } else {
                ""
            }
            val bodyClassAttr = buildString {
                append(if (isPaged) "paged-mode" else "scroll-mode")
                if (isRichMode) append(" rich-epub-mode")
                append(if (publisherStylesOn) " publisher-styles-on" else " publisher-styles-off")
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
    --RS__linkColor: $linkHex;
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
    font-family: Georgia, "Iowan Old Style", "Palatino Linotype", "Times New Roman", serif;
    font-size: var(--RS__fontSize);
    line-height: var(--RS__lineHeight);
    text-align: var(--RS__textAlign);
    direction: $dirAttr;
    -webkit-user-select: text;
    user-select: text;
    overflow-wrap: break-word;
    word-wrap: break-word;
    -webkit-hyphens: auto;
    hyphens: auto;
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
/* Rich EPUB editorial defaults (Apple Books-inspired typography & safe pagination) */
.r2-spine-doc {
    display: block;
    margin: 0;
    padding: 0;
}
.r2-spine-doc > .r2-body {
    margin: 0;
    padding: 0;
}
body.paged-mode .r2-spine-doc + .r2-spine-doc {
    break-before: column;
    -webkit-column-break-before: always;
    page-break-before: always;
}
body.scroll-mode .r2-spine-doc + .r2-spine-doc {
    margin-top: 2.4em;
    padding-top: 1.6em;
    border-top: 1px solid rgba(128, 128, 128, 0.22);
}
.r2-cover-page {
    text-align: center;
    break-inside: avoid;
    -webkit-column-break-inside: avoid;
}
body.paged-mode .r2-cover-page {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: calc(100vh - 122px);
}
.r2-cover-page .r2-body {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
}
.r2-cover-page img,
.r2-cover-page svg {
    max-width: 100% !important;
    max-height: calc(100vh - 130px) !important;
    width: auto;
    height: auto;
    object-fit: contain;
    margin: 0 auto;
    display: block;
}
.r2-cover-page svg {
    width: 100%;
    height: 100%;
}
.r2-spine-doc h1,
.r2-spine-doc h2,
.r2-spine-doc h3,
.r2-spine-doc h4,
.r2-spine-doc h5,
.r2-spine-doc h6 {
    line-height: 1.24;
    font-weight: 600;
    margin-top: 0.9em;
    margin-bottom: 0.5em;
    break-after: avoid;
    -webkit-column-break-after: avoid;
    page-break-after: avoid;
    -webkit-hyphens: none;
    hyphens: none;
}
.r2-spine-doc h1 { font-size: 1.65em; }
.r2-spine-doc h2 { font-size: 1.35em; }
.r2-spine-doc h3 { font-size: 1.15em; }
.r2-spine-doc h4 { font-size: 1.05em; }
.r2-spine-doc p {
    margin-top: 0;
    margin-bottom: 0.65em;
}
.r2-spine-doc blockquote {
    margin: 0.9em 1.2em;
    padding-left: 0.85em;
    border-left: 2px solid rgba(128, 128, 128, 0.35);
    font-style: italic;
}
.r2-spine-doc ul,
.r2-spine-doc ol {
    margin: 0.6em 0;
    padding-inline-start: 1.5em;
}
.r2-spine-doc li {
    margin-bottom: 0.25em;
}
.r2-spine-doc hr {
    border: 0;
    border-top: 1px solid rgba(128, 128, 128, 0.35);
    margin: 1.2em auto;
    width: 45%;
}
.r2-spine-doc img {
    max-width: 100% !important;
    max-height: calc(100vh - 150px) !important;
    height: auto !important;
    width: auto;
    object-fit: contain;
    break-inside: avoid;
    -webkit-column-break-inside: avoid;
}
.r2-spine-doc svg {
    max-width: 100% !important;
    max-height: calc(100vh - 150px) !important;
    break-inside: avoid;
    -webkit-column-break-inside: avoid;
}
.r2-spine-doc figure {
    margin: 0.9em auto;
    text-align: center;
    break-inside: avoid;
    -webkit-column-break-inside: avoid;
}
.r2-spine-doc figcaption {
    font-size: 0.88em;
    opacity: 0.78;
    margin-top: 0.35em;
}
.r2-spine-doc table {
    max-width: 100%;
    border-collapse: collapse;
    margin: 0.8em 0;
    font-size: 0.92em;
}
.r2-spine-doc th,
.r2-spine-doc td {
    padding: 0.35em 0.5em;
    border: 1px solid rgba(128, 128, 128, 0.28);
}
.r2-spine-doc pre,
.r2-spine-doc code {
    font-family: ui-monospace, "Cascadia Mono", "Roboto Mono", monospace;
    font-size: 0.88em;
}
.r2-spine-doc pre {
    white-space: pre-wrap;
    word-break: break-word;
    margin: 0.8em 0;
    padding: 0.6em 0.8em;
    background: rgba(128, 128, 128, 0.12);
    border-radius: 6px;
}
.r2-spine-doc sup,
.r2-spine-doc sub {
    font-size: 0.72em;
    line-height: 0;
}
.r2-spine-doc a[data-r2-link="internal"],
.r2-spine-doc a[data-r2-link="external"] {
    color: var(--RS__linkColor) !important;
    text-decoration: none;
    cursor: pointer;
    transition: opacity 0.12s ease;
}
.r2-spine-doc a[data-r2-link="internal"]:active,
.r2-spine-doc a[data-r2-link="external"]:active {
    opacity: 0.58;
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
$publisherCssTag
<style id="r2-theme-enforcement">
.r2-spine-doc a[data-r2-link="internal"],
.r2-spine-doc a[data-r2-link="external"],
.r2-spine-doc a[data-r2-link="internal"] *,
.r2-spine-doc a[data-r2-link="external"] * {
    color: var(--RS__linkColor) !important;
}
</style>
</head>
<body class="$bodyClassAttr">
<div id="r2-viewport">
    <div id="r2-book-content"><div id="r2-gpu-highlight-layer" aria-hidden="true"><div class="r2-gpu-pill-group" id="r2-pill-0"><div class="r2-gpu-pill-cap-l"></div><div class="r2-gpu-pill-mid"></div><div class="r2-gpu-pill-cap-r"></div></div><div class="r2-gpu-pill-group" id="r2-pill-1"><div class="r2-gpu-pill-cap-l"></div><div class="r2-gpu-pill-mid"></div><div class="r2-gpu-pill-cap-r"></div></div></div>$bodyContentHtml</div>
</div>
<script>
(function() {
    var state = {
        isPaged: $isPaged,
        isFixedLayout: $isFixedLayout,
        isRichMode: $isRichMode,
        publisherStyles: $publisherStylesOn,
        fitScale: $fitScale,
        isRtl: $isRtl,
        currentPage: 0,
        totalPages: 1,
        currentCharOffset: $initialCharOffset,
        initialPageIndex: ${initialPageIndex.coerceAtLeast(0)},
        isInitialRestorePending: true,
        isAudioWordTapEnabled: false,
        decorationsByGroup: {},
        lastNonEmptySelection: null,
        navHistoryStack: []
    };
    var lastProgrammaticNavTime = 0;
    var lastUserTouchScrollTime = 0;
    var lastManualNavNotifyTime = 0;

    window.__readiumSetAudioWordTapEnabled = function(enabled) {
        state.isAudioWordTapEnabled = !!enabled;
    };

    var viewportEl = document.getElementById('r2-viewport');
    var contentEl = document.getElementById('r2-book-content');
    var paragraphs = Array.prototype.slice.call(document.querySelectorAll('[data-r2-block="1"]'));
    var sections = Array.prototype.slice.call(document.querySelectorAll('section.r2-spine-doc'));

    function getBlockStart(el) {
        return parseInt((el && (el.dataset.r2Start || el.dataset.start)) || "0", 10);
    }

    function getBlockEnd(el) {
        return parseInt((el && (el.dataset.r2End || el.dataset.end)) || "0", 10);
    }

    function syncBodyClasses() {
        var cls = state.isPaged ? 'paged-mode' : 'scroll-mode';
        if (state.isRichMode) cls += ' rich-epub-mode';
        cls += state.publisherStyles ? ' publisher-styles-on' : ' publisher-styles-off';
        document.body.className = cls;
    }

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
            var s = getBlockStart(p);
            var e = getBlockEnd(p);
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

        var maxBlockEnd = firstOffset;
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
                    firstOffset = getBlockStart(p);
                    foundFirst = true;
                }
                var be = getBlockEnd(p);
                if (be > maxBlockEnd) maxBlockEnd = be;
                visibleParts.push(p.dataset.rawText || p.textContent || "");
            }
        }

        // Probe bottom edge of the viewport to get accurate page-end char offset even for multi-page paragraphs
        var lastOffset = maxBlockEnd;
        var bottomProbeXs = state.isRtl
            ? [Math.min(winW - 12, rawMargin + 16), Math.round(winW * 0.5), Math.min(winW - 12, rawMargin + 48)]
            : [Math.max(12, winW - rawMargin - 16), Math.round(winW * 0.5), Math.max(12, winW - rawMargin - 48)];
        var bottomProbeYs = [Math.max(60, winH - 74), Math.max(60, winH - 108), Math.max(60, winH - 150)];
        var foundBottom = false;
        for (var bpy = 0; bpy < bottomProbeYs.length && !foundBottom; bpy++) {
            for (var bpx = 0; bpx < bottomProbeXs.length && !foundBottom; bpx++) {
                var bHit = resolveWordAtPoint(bottomProbeXs[bpx], bottomProbeYs[bpy]);
                if (bHit && bHit.charOffset >= firstOffset) {
                    var bEnd = Math.max(bHit.charOffset, bHit.endCharOffset || bHit.charOffset);
                    if (maxBlockEnd <= firstOffset || bEnd <= maxBlockEnd + 64) {
                        lastOffset = bEnd;
                        foundBottom = true;
                    }
                }
            }
        }

        // On image-only cover/front-matter pages with no annotated text blocks, derive offset from visible spine section
        if (!foundFirst && sections.length > 0) {
            for (var si = 0; si < sections.length; si++) {
                var sec = sections[si];
                var sr = sec.getBoundingClientRect();
                var secVisible = state.isPaged
                    ? (sr.right > 8 && sr.left < winW - 8)
                    : (sr.bottom > 56 && sr.top < winH - 56);
                if (secVisible) {
                    firstOffset = parseInt(sec.dataset.r2CanonStart || "0", 10) || 0;
                    lastOffset = Math.max(firstOffset, parseInt(sec.dataset.r2CanonEnd || "0", 10) || firstOffset);
                    foundFirst = true;
                    break;
                }
            }
        }

        return {
            charOffset: firstOffset,
            endCharOffset: Math.max(firstOffset, lastOffset),
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
                loc.endCharOffset,
                posOneBased,
                state.totalPages,
                loc.visibleText,
                !!isInitialRestore
            );
        }
    }

    function notifyLinkHistoryChanged() {
        if (window.ReadiumBridge && window.ReadiumBridge.onLinkHistoryChanged) {
            window.ReadiumBridge.onLinkHistoryChanged(state.navHistoryStack.length > 0);
        }
    }

    function pushNavHistorySnapshot() {
        state.navHistoryStack.push({
            page: state.currentPage,
            scrollY: window.scrollY || 0,
            charOffset: state.currentCharOffset
        });
        if (state.navHistoryStack.length > 24) {
            state.navHistoryStack.shift();
        }
        notifyLinkHistoryChanged();
    }

    function findSectionByHref(targetHref) {
        if (!targetHref || sections.length === 0) return null;
        var clean = String(targetHref).replace(/^\/+/, '');
        var lower = clean.toLowerCase();
        for (var i = 0; i < sections.length; i++) {
            var s = sections[i];
            var h = (s.dataset.r2Href || '').replace(/^\/+/, '');
            if (h === clean || h.toLowerCase() === lower) return s;
            var all = (s.dataset.r2Hrefs || '').split('|');
            for (var j = 0; j < all.length; j++) {
                var candidate = all[j].replace(/^\/+/, '');
                if (candidate && (candidate === clean || candidate.toLowerCase() === lower)) return s;
            }
        }
        for (var k = 0; k < sections.length; k++) {
            var sec = sections[k];
            var sh = (sec.dataset.r2Href || '').toLowerCase();
            if (sh && (sh.endsWith('/' + lower) || lower.endsWith('/' + sh))) {
                return sec;
            }
        }
        return null;
    }

    function findElementByFragmentInSection(sectionEl, fragId) {
        if (!sectionEl || !fragId) return null;
        var lowerFrag = fragId.toLowerCase();
        var candidates = sectionEl.querySelectorAll('[id], [name], [data-r2-id]');
        var ciMatch = null;
        for (var i = 0; i < candidates.length; i++) {
            var el = candidates[i];
            var idVal = el.getAttribute('id') || '';
            var nameVal = el.getAttribute('name') || '';
            var r2IdVal = el.getAttribute('data-r2-id') || '';
            if (idVal === fragId || nameVal === fragId || r2IdVal === fragId) {
                return el;
            }
            if (!ciMatch && (
                idVal.toLowerCase() === lowerFrag ||
                nameVal.toLowerCase() === lowerFrag ||
                r2IdVal.toLowerCase() === lowerFrag
            )) {
                ciMatch = el;
            }
        }
        return ciMatch;
    }

    function measureFirstVisibleRectOfElement(sectionEl, targetEl) {
        if (!targetEl) return null;
        var contentRect = contentEl.getBoundingClientRect();

        function wrapRect(r) {
            if (!r) return null;
            return {
                x: r.left - contentRect.left,
                y: r.top - contentRect.top,
                w: r.width,
                h: r.height,
                clientLeft: r.left,
                clientRight: r.right,
                clientTop: r.top,
                clientBottom: r.bottom
            };
        }

        function firstTextRectInside(rootEl) {
            try {
                var walker = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, null, false);
                var tn = walker.nextNode();
                while (tn) {
                    var val = tn.nodeValue || '';
                    var idx = val.search(/\S/);
                    if (idx >= 0) {
                        var rng = document.createRange();
                        rng.setStart(tn, idx);
                        rng.setEnd(tn, Math.min(val.length, idx + 1));
                        var rects = rng.getClientRects();
                        if (rects && rects.length > 0 && rects[0].height > 0) {
                            return wrapRect(rects[0]);
                        }
                    }
                    tn = walker.nextNode();
                }
            } catch (err) {}
            return null;
        }

        // 1. Prefer the first visible glyph inside targetEl
        var insideTextRect = firstTextRectInside(targetEl);
        if (insideTextRect) return insideTextRect;

        // 2. Next check if targetEl is/contains an image or svg
        var mediaEl = (targetEl.tagName === 'IMG' || targetEl.tagName === 'SVG')
            ? targetEl
            : (targetEl.querySelector ? targetEl.querySelector('img, svg') : null);
        if (mediaEl) {
            var mr = mediaEl.getBoundingClientRect();
            if (mr && (mr.width > 0 || mr.height > 0)) {
                return wrapRect(mr);
            }
        }

        // 3. Empty anchor (e.g. <a id="sec_3"></a> before <h3>Title</h3>) -> advance to next text node in section
        if (sectionEl) {
            try {
                var secWalker = document.createTreeWalker(sectionEl, NodeFilter.SHOW_TEXT, null, false);
                secWalker.currentNode = targetEl;
                var nextTn = secWalker.nextNode();
                while (nextTn) {
                    var nv = nextTn.nodeValue || '';
                    var nIdx = nv.search(/\S/);
                    if (nIdx >= 0) {
                        var nRng = document.createRange();
                        nRng.setStart(nextTn, nIdx);
                        nRng.setEnd(nextTn, Math.min(nv.length, nIdx + 1));
                        var nRects = nRng.getClientRects();
                        if (nRects && nRects.length > 0 && nRects[0].height > 0) {
                            return wrapRect(nRects[0]);
                        }
                    }
                    nextTn = secWalker.nextNode();
                }
            } catch (err) {}
        }

        // 4. Direct bounding rect fallback
        var br = targetEl.getBoundingClientRect();
        if (br && (br.height > 0 || br.width > 0)) {
            return wrapRect(br);
        }
        return sectionEl ? wrapRect(sectionEl.getBoundingClientRect()) : null;
    }

    function jumpToMeasuredRect(measureFn) {
        state.totalPages = computeTotalPages();
        if (state.isPaged) {
            contentEl.style.transition = 'none';
            var stride = getPageStride();
            var rect = measureFn();
            if (!rect) return false;
            var targetPage = state.isRtl
                ? Math.max(0, Math.floor((stride - 1 - rect.x) / stride))
                : Math.max(0, Math.floor(rect.x / stride));
            targetPage = Math.max(0, Math.min(targetPage, state.totalPages - 1));
            applyPageTransform(targetPage, false);
            var verifyRect = measureFn();
            if (verifyRect) {
                var vpLeft = viewportEl ? viewportEl.getBoundingClientRect().left : 0;
                var relClientLeft = verifyRect.clientLeft - vpLeft;
                var pageDelta = 0;
                if (state.isRtl) {
                    if (relClientLeft < 0) {
                        pageDelta = Math.ceil(-relClientLeft / stride);
                    } else if (relClientLeft >= stride) {
                        pageDelta = -Math.floor(relClientLeft / stride);
                    }
                } else {
                    if (relClientLeft < 0 || relClientLeft >= stride) {
                        pageDelta = Math.floor(relClientLeft / stride);
                    }
                }
                if (pageDelta !== 0) {
                    var correctedPage = Math.max(0, Math.min(targetPage + pageDelta, state.totalPages - 1));
                    if (correctedPage !== targetPage) {
                        applyPageTransform(correctedPage, false);
                    }
                }
            }
            return true;
        } else {
            var scrollRect = measureFn();
            if (!scrollRect) return false;
            var targetTop = Math.max(0, window.scrollY + scrollRect.clientTop - 72);
            window.scrollTo({ top: targetTop, behavior: 'instant' });
            return true;
        }
    }

    window.__readiumJumpToHref = function(targetHref, targetFrag, fallbackCharOffset, pushHistory) {
        lastProgrammaticNavTime = Date.now();
        var sectionEl = findSectionByHref(targetHref);
        if (!sectionEl) {
            if (typeof fallbackCharOffset === 'number' && fallbackCharOffset >= 0) {
                if (pushHistory) pushNavHistorySnapshot();
                window.__readiumJumpToCharOffset(fallbackCharOffset);
            }
            return;
        }
        var targetEl = (targetFrag && targetFrag.length > 0)
            ? (findElementByFragmentInSection(sectionEl, targetFrag) || sectionEl)
            : sectionEl;

        if (pushHistory) {
            pushNavHistorySnapshot();
        }
        var jumped = jumpToMeasuredRect(function() {
            return measureFirstVisibleRectOfElement(sectionEl, targetEl);
        });
        if (!jumped && typeof fallbackCharOffset === 'number' && fallbackCharOffset >= 0) {
            window.__readiumJumpToCharOffset(fallbackCharOffset);
            return;
        }
        notifyLocator(false);
        state.isInitialRestorePending = false;
    };

    window.__readiumGoBackInHistory = function() {
        if (state.navHistoryStack.length === 0) {
            notifyLinkHistoryChanged();
            return;
        }
        var prev = state.navHistoryStack.pop();
        notifyLinkHistoryChanged();
        lastProgrammaticNavTime = Date.now();
        if (state.isPaged) {
            applyPageTransform(prev.page || 0, false);
            notifyLocator(false);
        } else {
            window.scrollTo({ top: Math.max(0, prev.scrollY || 0), behavior: 'instant' });
            notifyLocator(false);
        }
    };

    window.__readiumJumpToCharOffset = function(targetOffset) {
        lastProgrammaticNavTime = Date.now();
        state.currentCharOffset = Math.max(0, targetOffset || 0);
        state.totalPages = computeTotalPages();
        if (state.isPaged) {
            var jumped = jumpToMeasuredRect(function() {
                var r = measureJumpRectInContent(state.currentCharOffset);
                return (r && r.h > 0) ? r : null;
            });
            if (!jumped) {
                applyPageTransform(0, false);
                var fallbackPage = findParagraphPage(state.currentCharOffset);
                applyPageTransform(fallbackPage, false);
            }
        } else {
            var scrollJumped = jumpToMeasuredRect(function() {
                var r = measureJumpRectInContent(state.currentCharOffset);
                return (r && r.h > 0) ? r : null;
            });
            if (!scrollJumped) {
                for (var i = 0; i < paragraphs.length; i++) {
                    var p = paragraphs[i];
                    var s = getBlockStart(p);
                    var e = getBlockEnd(p);
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
        if (cfg.linkColor) {
            rootStyle.setProperty('--RS__linkColor', cfg.linkColor);
        }
        rootStyle.setProperty('--RS__selectionBackgroundColor', cfg.selBgColor);

        state.isPaged = !!cfg.isPaged;
        state.isFixedLayout = !!cfg.isFixedLayout;
        state.isRtl = !!cfg.isRtl;
        if (typeof cfg.publisherStyles === 'boolean') {
            state.publisherStyles = cfg.publisherStyles;
        }
        syncBodyClasses();
        syncPagedGeometry();
        if (!state.isPaged) {
            contentEl.style.transform = 'none';
        }
        if (typeof cfg.targetCharOffset === 'number' && cfg.targetCharOffset >= 0) {
            state.currentCharOffset = cfg.targetCharOffset;
        }
        setTimeout(function() {
            if (state.isRichMode && state.currentCharOffset <= 0 && state.isPaged) {
                applyPageTransform(state.currentPage, false);
                notifyLocator(false);
            } else {
                window.__readiumJumpToCharOffset(state.currentCharOffset);
            }
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
            if (cur.nodeType === 1) {
                if (cur.getAttribute('data-r2-block') === '1' || (cur.classList && cur.classList.contains('r2-para'))) {
                    return cur;
                }
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
            var pStart = getBlockStart(startPara);
            if (startPara.contains(range.startContainer)) {
                absStart = pStart + getCharOffsetInParagraph(startPara, range.startContainer, range.startOffset);
            } else {
                absStart = pStart;
            }
        }

        if (endPara) {
            var pEndStart = getBlockStart(endPara);
            var pEndMax = getBlockEnd(endPara);
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

    function unwrapDecorationMarks(container) {
        var marks = Array.prototype.slice.call(container.querySelectorAll('mark[data-decoration-id]'));
        for (var i = 0; i < marks.length; i++) {
            var mk = marks[i];
            var parent = mk.parentNode;
            if (!parent) continue;
            while (mk.firstChild) {
                parent.insertBefore(mk.firstChild, mk);
            }
            parent.removeChild(mk);
            parent.normalize();
        }
    }

    function applyIntervalsToRichBlock(block, intervals) {
        var textNodes = [];
        var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT, null, false);
        var node = walker.nextNode();
        var acc = 0;
        while (node) {
            var nLen = node.nodeValue ? node.nodeValue.length : 0;
            if (nLen > 0) {
                textNodes.push({ node: node, start: acc, end: acc + nLen });
                acc += nLen;
            }
            node = walker.nextNode();
        }
        if (textNodes.length === 0) return;

        // Apply slices right-to-left within each text node so earlier offsets stay valid
        for (var ti = 0; ti < textNodes.length; ti++) {
            var tn = textNodes[ti];
            var slices = [];
            for (var ii = 0; ii < intervals.length; ii++) {
                var iv = intervals[ii];
                var s = Math.max(tn.start, iv.start);
                var e = Math.min(tn.end, iv.end);
                if (e > s) {
                    slices.push({
                        localStart: s - tn.start,
                        localEnd: e - tn.start,
                        iv: iv
                    });
                }
            }
            for (var si = slices.length - 1; si >= 0; si--) {
                var sl = slices[si];
                var targetNode = tn.node;
                var valLen = targetNode.nodeValue ? targetNode.nodeValue.length : 0;
                if (sl.localStart >= valLen || sl.localEnd <= sl.localStart) continue;
                var clampedEnd = Math.min(valLen, sl.localEnd);
                if (clampedEnd < valLen) {
                    targetNode.splitText(clampedEnd);
                }
                var highlightNode = sl.localStart > 0 ? targetNode.splitText(sl.localStart) : targetNode;
                var mark = document.createElement('mark');
                mark.className = sl.iv.styleClass === 'underline'
                    ? 'r2-decoration-underline'
                    : 'r2-decoration-highlight';
                mark.setAttribute('data-decoration-id', sl.iv.id);
                mark.setAttribute('data-decoration-group', sl.iv.group);
                if (sl.iv.styleClass === 'underline') {
                    mark.style.borderBottomColor = sl.iv.tintCss;
                } else {
                    mark.style.backgroundColor = sl.iv.tintCss;
                }
                if (highlightNode.parentNode) {
                    highlightNode.parentNode.insertBefore(mark, highlightNode);
                    mark.appendChild(highlightNode);
                }
            }
        }
    }

    // Readium DecorableNavigator Decoration rendering engine (DOM-preserving for rich XHTML)
    window.readiumApplyDecorations = function(group, decorations, templates) {
        state.decorationsByGroup[group] = decorations || [];
        var allDecorations = [];
        Object.keys(state.decorationsByGroup).forEach(function(g) {
            (state.decorationsByGroup[g] || []).forEach(function(d) {
                allDecorations.push({ group: g, dec: d });
            });
        });

        paragraphs.forEach(function(p) {
            unwrapDecorationMarks(p);
            var raw = p.textContent || p.dataset.rawText || "";
            p.dataset.rawText = raw;
            var pStart = getBlockStart(p);
            var pEnd = getBlockEnd(p);
            var canonSpan = Math.max(1, pEnd - pStart);
            var intervals = [];

            allDecorations.forEach(function(entry) {
                var d = entry.dec;
                var dStart = typeof d.charOffset === 'number' ? d.charOffset : -1;
                var dEnd = typeof d.endCharOffset === 'number' ? d.endCharOffset : -1;
                var matchedByQuote = false;
                if (d.quote && d.quote.length > 0 && dEnd > dStart && dStart < pEnd && dEnd > pStart) {
                    var qIdx = raw.toLowerCase().indexOf(d.quote.toLowerCase());
                    if (qIdx >= 0) {
                        intervals.push({
                            start: qIdx,
                            end: Math.min(raw.length, qIdx + d.quote.length),
                            id: d.id,
                            group: entry.group,
                            tintCss: d.tintCss || 'rgba(250, 204, 21, 0.42)',
                            styleClass: d.styleClass || 'highlight'
                        });
                        matchedByQuote = true;
                    }
                }
                if (!matchedByQuote && dEnd > dStart && dStart < pEnd && dEnd > pStart) {
                    var ratio = raw.length / canonSpan;
                    var localStart = Math.max(0, Math.round((dStart - pStart) * ratio));
                    var localEnd = Math.min(raw.length, Math.round((dEnd - pStart) * ratio));
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
                } else if (!matchedByQuote && d.quote && d.quote.length > 0) {
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

            if (intervals.length === 0) return;

            intervals.sort(function(a, b) {
                if (a.start !== b.start) return a.start - b.start;
                return b.end - a.end;
            });
            var merged = [];
            var cursor = 0;
            for (var k = 0; k < intervals.length; k++) {
                var iv = intervals[k];
                var segStart = Math.max(iv.start, cursor);
                var segEnd = Math.min(iv.end, raw.length);
                if (segEnd <= segStart) continue;
                merged.push({
                    start: segStart,
                    end: segEnd,
                    id: iv.id,
                    group: iv.group,
                    tintCss: iv.tintCss,
                    styleClass: iv.styleClass
                });
                cursor = segEnd;
            }
            if (merged.length > 0) {
                applyIntervalsToRichBlock(p, merged);
            }
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
            var s = getBlockStart(p);
            var e = getBlockEnd(p);
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

    function mapCanonOffsetToDomLocal(para, canonOffset) {
        var pStart = getBlockStart(para);
        var pEnd = getBlockEnd(para);
        var domLen = (para.dataset.rawText || para.textContent || '').length;
        var canonSpan = Math.max(1, pEnd - pStart);
        var rel = Math.max(0, canonOffset - pStart);
        if (domLen <= 0) return 0;
        if (Math.abs(domLen - canonSpan) <= 2) {
            return Math.min(domLen, rel);
        }
        return Math.max(0, Math.min(domLen, Math.round(rel * (domLen / canonSpan))));
    }

    function measureJumpRectInContent(targetOffset) {
        var p = findParagraphForCharOffset(targetOffset);
        if (!p) return null;
        var raw = p.dataset.rawText || p.textContent || '';
        var rawLen = raw.length;
        if (rawLen === 0) return null;
        var localStart = Math.min(mapCanonOffsetToDomLocal(p, targetOffset), rawLen - 1);
        while (localStart < rawLen - 1 && /\s/.test(raw.charAt(localStart))) {
            localStart++;
        }
        var localEnd = Math.min(rawLen, localStart + 1);
        return measureDomRangeRectInContent(p, localStart, localEnd);
    }

    function measureDomRangeRectInContent(p, localStart, localEnd) {
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

    function measureWordRectInContent(startOffset, endOffset) {
        var p = findParagraphForCharOffset(startOffset);
        if (!p) return null;
        var rawLen = (p.dataset.rawText || p.textContent || '').length;
        if (rawLen === 0) return null;
        var localStart = Math.min(mapCanonOffsetToDomLocal(p, startOffset), rawLen - 1);
        var localEnd = Math.max(localStart + 1, Math.min(rawLen, mapCanonOffsetToDomLocal(p, endOffset)));
        return measureDomRangeRectInContent(p, localStart, localEnd);
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
        var pStart = getBlockStart(para);
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

    function findClosestLink(node, clientX, clientY) {
        var cur = node;
        while (cur && cur !== document.body) {
            if (cur.nodeType === 1 && cur.tagName && cur.tagName.toLowerCase() === 'a' && cur.hasAttribute('data-r2-link')) {
                return cur;
            }
            cur = cur.parentNode;
        }
        if (typeof clientX === 'number' && typeof clientY === 'number' && document.elementFromPoint) {
            var el = document.elementFromPoint(clientX, clientY);
            while (el && el !== document.body) {
                if (el.nodeType === 1 && el.tagName && el.tagName.toLowerCase() === 'a' && el.hasAttribute('data-r2-link')) {
                    return el;
                }
                el = el.parentNode;
            }
        }
        return null;
    }

    document.addEventListener('click', function(e) {
        var linkEl = findClosestLink(e.target);
        if (linkEl) {
            e.preventDefault();
            e.stopPropagation();
        }
    }, true);

    function notifyManualNavThrottled() {
        var now = Date.now();
        if (now - lastManualNavNotifyTime < 250) return;
        lastManualNavNotifyTime = now;
        if (window.ReadiumBridge && window.ReadiumBridge.onManualNavigation) {
            window.ReadiumBridge.onManualNavigation();
        }
    }

    // Touch / Swipe / Link-Tap / Single-Tap / Fast Double-Tap Word Jump handling inside Readium WebView
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
                var targetLink = findClosestLink(e.target, touch.clientX, touch.clientY);
                if (targetLink) {
                    if (pendingSingleTapTimer) {
                        clearTimeout(pendingSingleTapTimer);
                        pendingSingleTapTimer = null;
                    }
                    lastTapTime = 0;
                    var linkType = targetLink.getAttribute('data-r2-link');
                    if (linkType === 'internal') {
                        var tHref = targetLink.getAttribute('data-r2-target-href') || '';
                        var tFrag = targetLink.getAttribute('data-r2-target-frag') || '';
                        if (tHref.length > 0) {
                            notifyManualNavThrottled();
                            window.__readiumJumpToHref(tHref, tFrag, -1, true);
                        }
                        return;
                    } else if (linkType === 'external') {
                        var extUrl = targetLink.getAttribute('href') || '';
                        if (extUrl && window.ReadiumBridge && window.ReadiumBridge.onExternalLinkClicked) {
                            window.ReadiumBridge.onExternalLinkClicked(extUrl);
                        }
                        return;
                    }
                }

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

    var imgRefreshTimer = null;
    document.addEventListener('load', function(e) {
        if (e.target && e.target.tagName === 'IMG' && !state.isInitialRestorePending) {
            if (imgRefreshTimer) clearTimeout(imgRefreshTimer);
            imgRefreshTimer = setTimeout(function() {
                state.totalPages = computeTotalPages();
                if (state.isPaged) {
                    applyPageTransform(state.currentPage, false);
                }
                notifyLocator(false);
            }, 90);
        }
    }, true);

    window.addEventListener('resize', function() {
        setTimeout(function() {
            if (state.isRichMode && state.currentCharOffset <= 0 && state.isPaged) {
                applyPageTransform(state.currentPage, false);
                notifyLocator(false);
            } else {
                window.__readiumJumpToCharOffset(state.currentCharOffset);
            }
        }, 50);
    });

    window.addEventListener('load', function() {
        setTimeout(function() {
            if (state.isRichMode && state.currentCharOffset <= 0) {
                state.totalPages = computeTotalPages();
                if (state.isPaged) {
                    applyPageTransform(state.initialPageIndex || 0, false);
                } else {
                    window.scrollTo({ top: 0, behavior: 'instant' });
                }
                notifyLocator(true);
                state.isInitialRestorePending = false;
            } else {
                window.__readiumJumpToCharOffset(state.currentCharOffset);
            }
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
                                    totalProgression = totalProg,
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
