package com.example.freebook

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.freebook.model.HighlightColor
import com.example.freebook.model.ReaderTheme
import com.example.freebook.ui.BookFreeViewModel
import com.example.freebook.ui.reader.ReadiumActionModeCallbackWrapper
import com.example.freebook.ui.reader.ReadiumActionModeSession
import com.example.freebook.ui.reader.ReadiumWebViewNavigator
import com.example.freebook.ui.screens.AboutScreen
import com.example.freebook.ui.screens.BookNotesDetailScreen
import com.example.freebook.ui.screens.BookReaderScreen
import com.example.freebook.ui.screens.LibraryScreen
import com.example.freebook.ui.screens.NotesListScreen
import com.example.freebook.ui.theme.FreebookTheme
import com.example.freebook.ui.theme.LocalBookFreePalette

sealed interface ScreenDestination {
    data object Library : ScreenDestination
    data object NotesList : ScreenDestination
    data class BookNotesDetail(val bookId: String) : ScreenDestination
    data class BookReader(val bookId: String) : ScreenDestination
    data class About(val previous: ScreenDestination) : ScreenDestination
}

private val ScreenDestinationSaver: Saver<ScreenDestination, List<String>> = Saver(
    save = { dest ->
        when (dest) {
            ScreenDestination.Library -> listOf("Library")
            ScreenDestination.NotesList -> listOf("NotesList")
            is ScreenDestination.BookNotesDetail -> listOf("BookNotesDetail", dest.bookId)
            is ScreenDestination.BookReader -> listOf("BookReader", dest.bookId)
            is ScreenDestination.About -> {
                val prevKey = when (val p = dest.previous) {
                    ScreenDestination.Library -> "Library"
                    ScreenDestination.NotesList -> "NotesList"
                    is ScreenDestination.BookNotesDetail -> "BookNotesDetail:${p.bookId}"
                    is ScreenDestination.BookReader -> "BookReader:${p.bookId}"
                    is ScreenDestination.About -> "Library"
                }
                listOf("About", prevKey)
            }
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "NotesList" -> ScreenDestination.NotesList
            "BookNotesDetail" -> ScreenDestination.BookNotesDetail(saved.getOrElse(1) { "" })
            "BookReader" -> ScreenDestination.BookReader(saved.getOrElse(1) { "" })
            "About" -> {
                val rawPrev = saved.getOrElse(1) { "Library" }
                val prev = when {
                    rawPrev == "NotesList" -> ScreenDestination.NotesList
                    rawPrev.startsWith("BookNotesDetail:") ->
                        ScreenDestination.BookNotesDetail(rawPrev.substringAfter(':'))
                    rawPrev.startsWith("BookReader:") ->
                        ScreenDestination.BookReader(rawPrev.substringAfter(':'))
                    else -> ScreenDestination.Library
                }
                ScreenDestination.About(prev)
            }
            else -> ScreenDestination.Library
        }
    }
)

class MainActivity : ComponentActivity() {

    private var activeActionModeSession: ReadiumActionModeSession? = null
    private var currentActionMode: ActionMode? = null
    private val hookedPopupRoots = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun syncActionModeThemeAndConfiguration(isDark: Boolean) {
        val styleRes = if (isDark) R.style.Theme_Freebook_Dark else R.style.Theme_Freebook_Light
        val nightBits = if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        resources?.configuration?.let { cfg ->
            cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
        }
        setTheme(styleRes)
        theme?.applyStyle(styleRes, true)
    }

    fun registerReadiumActionModeSession(session: ReadiumActionModeSession?) {
        activeActionModeSession = session
        if (session != null) {
            session.onStyleActionModePopup = {
                scheduleFloatingPopupStyling(session)
            }
            syncActionModeThemeAndConfiguration(session.isDarkMode)
        } else {
            currentActionMode?.finish()
            currentActionMode = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BookFreeApp(
                onRegisterActionModeSession = { registerReadiumActionModeSession(it) }
            )
        }
    }

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? {
        val session = activeActionModeSession
        if (session != null) {
            syncActionModeThemeAndConfiguration(session.isDarkMode)
            val wrapped = if (callback is ReadiumActionModeCallbackWrapper) {
                callback
            } else {
                ReadiumActionModeCallbackWrapper(callback, session)
            }
            return super.onWindowStartingActionMode(wrapped)
        }
        return super.onWindowStartingActionMode(callback)
    }

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        val session = activeActionModeSession
        if (session != null) {
            syncActionModeThemeAndConfiguration(session.isDarkMode)
            val wrapped = if (callback is ReadiumActionModeCallbackWrapper) {
                callback
            } else {
                ReadiumActionModeCallbackWrapper(callback, session)
            }
            return super.onWindowStartingActionMode(wrapped, type)
        }
        return super.onWindowStartingActionMode(callback, type)
    }

    /**
     * Overrides the Activity's [onActionModeStarted] to clear the default OS text selection menu
     * and inject custom options (`Highlight`, `Add Note`, `Copy`) styled to match the reader theme.
     */
    override fun onActionModeStarted(mode: ActionMode?) {
        val session = activeActionModeSession
        if (session != null) {
            syncActionModeThemeAndConfiguration(session.isDarkMode)
        }
        super.onActionModeStarted(mode)
        if (session == null || mode == null) return
        currentActionMode = mode

        mode.menu?.let { menu ->
            ReadiumWebViewNavigator.populateCustomActionModeMenu(mode, menu, session)
        }
        scheduleFloatingPopupStyling(session)
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        if (currentActionMode === mode) {
            currentActionMode = null
        }
        super.onActionModeFinished(mode)
    }

    private fun scheduleFloatingPopupStyling(session: ReadiumActionModeSession) {
        styleFloatingActionModePopup(session)
        window.decorView.post {
            styleFloatingActionModePopup(session)
        }
        window.decorView.postDelayed({
            if (currentActionMode != null) {
                styleFloatingActionModePopup(session)
            }
        }, 48L)
    }

    private fun styleFloatingActionModePopup(session: ReadiumActionModeSession) {
        val (pillBgColor, pillBorderColor, pillTextColor) = when (session.readerTheme) {
            ReaderTheme.DARK, ReaderTheme.AMOLED -> Triple(
                AndroidColor.parseColor("#27272A"),
                AndroidColor.parseColor("#3F3F46"),
                AndroidColor.parseColor("#FAFAFA")
            )
            ReaderTheme.SEPIA -> Triple(
                AndroidColor.parseColor("#FDFBF7"),
                AndroidColor.parseColor("#D6C7B2"),
                AndroidColor.parseColor("#3E2C23")
            )
            ReaderTheme.LIGHT -> Triple(
                AndroidColor.parseColor("#FFFFFF"),
                AndroidColor.parseColor("#D4D4D8"),
                AndroidColor.parseColor("#18181B")
            )
        }

        val density = resources.displayMetrics.density

        fun createPillBackground(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(pillBgColor)
                setStroke((1f * density).toInt().coerceAtLeast(1), pillBorderColor)
            }
        }

        fun styleTextViewsRecursively(view: View) {
            if (view is TextView) {
                view.setTextColor(pillTextColor)
                view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    styleTextViewsRecursively(view.getChildAt(i))
                }
            }
        }

        fun findAndStylePopupContainer(root: View): Boolean {
            val tagStr = (root.tag as? String).orEmpty().lowercase()
            val resName = runCatching {
                if (root.id != View.NO_ID) resources.getResourceEntryName(root.id).lowercase() else ""
            }.getOrDefault("")
            val clsName = root.javaClass.name.lowercase()

            if (tagStr == "floating_toolbar" ||
                resName == "floating_popup_container" ||
                clsName.contains("actionbarcontextview") ||
                resName.contains("action_context_bar")
            ) {
                root.background = createPillBackground()
                styleTextViewsRecursively(root)
                return true
            }

            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    if (findAndStylePopupContainer(root.getChildAt(i))) {
                        return true
                    }
                }
            }
            return false
        }

        fun stylePopupRoot(popupRoot: View) {
            if (!findAndStylePopupContainer(popupRoot)) {
                // Fallback: find the first child ViewGroup with a non-null background inside the popup window
                if (popupRoot is ViewGroup) {
                    var targetContainer: ViewGroup? = null
                    fun locateFirstBackedGroup(group: ViewGroup) {
                        if (targetContainer != null) return
                        if (group !== popupRoot && group.background != null) {
                            targetContainer = group
                            return
                        }
                        for (i in 0 until group.childCount) {
                            val child = group.getChildAt(i)
                            if (child is ViewGroup) {
                                locateFirstBackedGroup(child)
                            }
                        }
                    }
                    locateFirstBackedGroup(popupRoot)
                    val container = targetContainer ?: popupRoot
                    container.background = createPillBackground()
                    styleTextViewsRecursively(container)
                }
            }
        }

        // 1. Style any in-window ActionMode views if present
        findAndStylePopupContainer(window.decorView)

        // 2. Style FloatingToolbar PopupWindow views via WindowInspector (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rootViews = runCatching { WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
            for (rootView in rootViews) {
                if (rootView !== window.decorView) {
                    stylePopupRoot(rootView)
                    if (hookedPopupRoots.add(rootView)) {
                        rootView.viewTreeObserver?.addOnPreDrawListener(
                            object : ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    if (!rootView.isAttachedToWindow) {
                                        rootView.viewTreeObserver?.removeOnPreDrawListener(this)
                                        hookedPopupRoots.remove(rootView)
                                        return true
                                    }
                                    val active = activeActionModeSession
                                    if (active != null && currentActionMode != null) {
                                        stylePopupRoot(rootView)
                                    }
                                    return true
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BookFreeApp(
    onRegisterActionModeSession: (ReadiumActionModeSession?) -> Unit = {},
    viewModel: BookFreeViewModel = viewModel(factory = BookFreeViewModel.Factory)
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var currentScreen by rememberSaveable(stateSaver = ScreenDestinationSaver) {
        mutableStateOf<ScreenDestination>(ScreenDestination.Library)
    }

    LaunchedEffect(uiState.toastMessage) {
        val msg = uiState.toastMessage
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeToastMessage()
        }
    }

    // Document Picker Launcher for EPUB / PDF / TXT books
    val bookPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.importBook(context, uri)
        }
    }

    // Cover Image Picker Launcher for Custom Book Note Entry dialog
    val coverImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.pickCustomCoverImage(context, uri)
        }
    }

    BackHandler(enabled = currentScreen != ScreenDestination.Library) {
        currentScreen = when (val dest = currentScreen) {
            is ScreenDestination.About -> dest.previous
            is ScreenDestination.BookNotesDetail -> ScreenDestination.NotesList
            is ScreenDestination.BookReader -> ScreenDestination.Library
            is ScreenDestination.NotesList -> ScreenDestination.Library
            ScreenDestination.Library -> ScreenDestination.Library
        }
    }

    val isDarkTheme = uiState.preferences.isDarkTheme
    val fontSizeSp = uiState.preferences.fontSizeSp
    val enablePageAnimations = uiState.preferences.enablePageAnimations

    FreebookTheme(darkTheme = isDarkTheme) {
        val palette = LocalBookFreePalette.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (currentScreen is ScreenDestination.BookReader) {
                        palette.readerBackground
                    } else {
                        palette.appBackground
                    }
                )
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
        ) {
            when (val dest = currentScreen) {
                ScreenDestination.Library -> {
                    LibraryScreen(
                        books = uiState.books,
                        isDarkTheme = isDarkTheme,
                        enablePageAnimations = enablePageAnimations,
                        onToggleTheme = { viewModel.setDarkTheme(it) },
                        onTogglePageAnimations = { viewModel.setEnablePageAnimations(it) },
                        onOpenAbout = {
                            currentScreen = ScreenDestination.About(ScreenDestination.Library)
                        },
                        onImportEpubClick = {
                            bookPickerLauncher.launch(
                                arrayOf(
                                    "application/epub+zip",
                                    "application/pdf",
                                    "text/plain",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        onBookClick = { clickedBook ->
                            viewModel.markBookStarted(clickedBook)
                            currentScreen = ScreenDestination.BookReader(clickedBook.id)
                        },
                        onDeleteBook = { bookToDelete ->
                            viewModel.deleteBook(bookToDelete)
                        },
                        onNavigateToNotes = {
                            currentScreen = ScreenDestination.NotesList
                        }
                    )
                }

                ScreenDestination.NotesList -> {
                    NotesListScreen(
                        books = uiState.books,
                        annotations = uiState.annotations,
                        isDarkTheme = isDarkTheme,
                        enablePageAnimations = enablePageAnimations,
                        pendingCustomCoverPath = uiState.pendingCustomCoverPath,
                        onClearPendingCustomCover = { viewModel.clearPendingCustomCover() },
                        onPickCustomCoverImage = {
                            coverImagePickerLauncher.launch("image/*")
                        },
                        onAddCustomBookNoteEntry = { title, author, coverPath ->
                            viewModel.addCustomNoteNotebook(title, author, coverPath)
                        },
                        onToggleTheme = { viewModel.setDarkTheme(it) },
                        onTogglePageAnimations = { viewModel.setEnablePageAnimations(it) },
                        onOpenAbout = {
                            currentScreen = ScreenDestination.About(ScreenDestination.NotesList)
                        },
                        onBookNoteClick = { book ->
                            currentScreen = ScreenDestination.BookNotesDetail(book.id)
                        },
                        onDeleteBook = { bookToDelete ->
                            viewModel.deleteBook(bookToDelete)
                        },
                        onNavigateToLibrary = {
                            currentScreen = ScreenDestination.Library
                        }
                    )
                }

                is ScreenDestination.BookNotesDetail -> {
                    val book = uiState.books.find { it.id == dest.bookId }
                    if (book == null) {
                        currentScreen = ScreenDestination.NotesList
                    } else {
                        val bookAnnotations = uiState.annotations.filter { it.bookId == book.id }
                        BookNotesDetailScreen(
                            book = book,
                            annotations = bookAnnotations,
                            isDarkTheme = isDarkTheme,
                            enablePageAnimations = enablePageAnimations,
                            onBack = { currentScreen = ScreenDestination.NotesList },
                            onAddAnnotation = { type, pageNum, text ->
                                viewModel.addAnnotation(
                                    bookId = book.id,
                                    type = type,
                                    pageNumber = pageNum,
                                    text = text
                                )
                            },
                            onDeleteAnnotation = { annotation ->
                                viewModel.deleteAnnotation(annotation)
                            },
                            onToggleTheme = { viewModel.setDarkTheme(it) },
                            onTogglePageAnimations = { viewModel.setEnablePageAnimations(it) },
                            onOpenAbout = {
                                currentScreen = ScreenDestination.About(dest)
                            },
                            onNavigateToLibrary = {
                                currentScreen = ScreenDestination.Library
                            },
                            onNavigateToNotesList = {
                                currentScreen = ScreenDestination.NotesList
                            }
                        )
                    }
                }

                is ScreenDestination.BookReader -> {
                    val book = uiState.books.find { it.id == dest.bookId }
                    if (book == null) {
                        currentScreen = ScreenDestination.Library
                    } else {
                        val bookAnnotations = uiState.annotations.filter { it.bookId == book.id }
                        BookReaderScreen(
                            book = book,
                            bookAnnotations = bookAnnotations,
                            preferences = uiState.preferences,
                            loadBookText = { targetBook -> viewModel.loadBookText(targetBook) },
                            onRegisterActionModeSession = onRegisterActionModeSession,
                            onSelectReaderTheme = { viewModel.setReaderTheme(it) },
                            onSelectFontSizeMultiplier = { viewModel.setFontSizeMultiplier(it) },
                            onSelectLineHeightMultiplier = { viewModel.setLineHeightMultiplier(it) },
                            onSelectPageMarginDp = { viewModel.setPageMarginDp(it) },
                            onSelectTextAlignment = { viewModel.setTextAlignment(it) },
                            onSelectPublisherStyles = { viewModel.setPublisherStyles(it) },
                            onSelectNavigationMode = { viewModel.setNavigationMode(it) },
                            onSelectFixedLayoutFitMode = { viewModel.setFixedLayoutFitMode(it) },
                            onLocatorChanged = { charOffset, totalChars, derivedPosIdx, derivedPosCount ->
                                viewModel.updateReadingLocator(
                                    bookId = book.id,
                                    charOffset = charOffset,
                                    totalChars = totalChars,
                                    derivedPositionIndex = derivedPosIdx,
                                    derivedPositionCount = derivedPosCount
                                )
                            },
                            onAddAnnotation = { type, pageNumber, charOffset, endCharOffset, text, quotedText, highlightColorName ->
                                viewModel.addAnnotation(
                                    bookId = book.id,
                                    type = type,
                                    pageNumber = pageNumber,
                                    charOffset = charOffset,
                                    endCharOffset = endCharOffset,
                                    text = text,
                                    quotedText = quotedText,
                                    highlightColorName = highlightColorName,
                                    showToast = true
                                )
                            },
                            onUpdateAnnotationColor = { annotation, color ->
                                viewModel.updateAnnotationHighlightColor(annotation.id, color)
                            },
                            onDeleteAnnotation = { annotation ->
                                viewModel.deleteAnnotation(annotation)
                            },
                            onCloseReader = {
                                currentScreen = ScreenDestination.Library
                            }
                        )
                    }
                }

                is ScreenDestination.About -> {
                    AboutScreen(
                        onBack = { currentScreen = dest.previous }
                    )
                }
            }
        }

        // Fuzzy Duplicate Warning + Confirmation Dialog
        uiState.pendingFuzzyDuplicate?.let { fuzzyDuplicate ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissFuzzyDuplicateImport() },
                containerColor = palette.menuSurface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textPrimary,
                title = {
                    Text(
                        text = "Similar Book Already in Library",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = fuzzyDuplicate.reason,
                            fontSize = 14.sp,
                            color = palette.textSecondary
                        )
                        Text(
                            text = "Existing: \"${fuzzyDuplicate.existingBook.title}\" by ${fuzzyDuplicate.existingBook.author}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "New Import: \"${fuzzyDuplicate.stagedImport.title}\" by ${fuzzyDuplicate.stagedImport.author}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Would you like to keep both editions in your library?",
                            fontSize = 13.sp,
                            color = palette.textMuted
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.confirmFuzzyDuplicateImport() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.textPrimary,
                            contentColor = palette.appBackground
                        )
                    ) {
                        Text("Import Anyway", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissFuzzyDuplicateImport() }) {
                        Text("Discard", color = palette.textMuted)
                    }
                }
            )
        }
    }
}
