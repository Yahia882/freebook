package com.example.freebook

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
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
import com.example.freebook.ui.BookFreeViewModel
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BookFreeApp()
        }
    }
}

@Composable
fun BookFreeApp(
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
                .windowInsetsPadding(WindowInsets.safeDrawing)
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
                            fontSizeSp = fontSizeSp,
                            enablePageAnimations = enablePageAnimations,
                            loadBookText = { targetBook -> viewModel.loadBookText(targetBook) },
                            onFontSizeChange = { newSize -> viewModel.setFontSizeSp(newSize) },
                            onPageChanged = { charOffset, totalChars, pageIndex, pageCount ->
                                viewModel.updateReadingProgress(
                                    bookId = book.id,
                                    charOffset = charOffset,
                                    totalChars = totalChars,
                                    pageIndex = pageIndex,
                                    pageCount = pageCount
                                )
                            },
                            onAddAnnotation = { type, pageNumber, text ->
                                viewModel.addAnnotation(
                                    bookId = book.id,
                                    type = type,
                                    pageNumber = pageNumber,
                                    text = text,
                                    showToast = true
                                )
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
