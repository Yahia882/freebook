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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.freebook.data.BookRepository
import com.example.freebook.data.EpubParser
import com.example.freebook. data.PersistedAppState
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.CoverStyle
import com.example.freebook.ui.screens.AboutScreen
import com.example.freebook.ui.screens.BookNotesDetailScreen
import com.example.freebook.ui.screens.BookReaderScreen
import com.example.freebook.ui.screens.LibraryScreen
import com.example.freebook.ui.screens.NotesListScreen
import com.example.freebook.ui.theme.FreebookTheme
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScreenDestination {
    data object Library : ScreenDestination
    data object NotesList : ScreenDestination
    data class BookNotesDetail(val bookId: String) : ScreenDestination
    data class BookReader(val bookId: String) : ScreenDestination
    data class About(val previous: ScreenDestination) : ScreenDestination
}

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
fun BookFreeApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val initialState = remember { BookRepository.loadState(context) }
    var isDarkTheme by remember { mutableStateOf(initialState.isDarkTheme) }
    var books by remember { mutableStateOf(initialState.books) }
    var annotations by remember { mutableStateOf(initialState.annotations) }
    var currentScreen by remember { mutableStateOf<ScreenDestination>(ScreenDestination.Library) }
    var pendingCustomCoverPath by remember { mutableStateOf<String?>(null) }

    fun persist(
        newDark: Boolean = isDarkTheme,
        newBooks: List<Book> = books,
        newAnnotations: List<BookAnnotation> = annotations
    ) {
        isDarkTheme = newDark
        books = newBooks
        annotations = newAnnotations
        coroutineScope.launch(Dispatchers.IO) {
            BookRepository.saveState(
                context,
                PersistedAppState(
                    isDarkTheme = newDark,
                    books = newBooks,
                    annotations = newAnnotations
                )
            )
        }
    }

    // EPUB Document Picker Launcher for Library '+' button
    val epubPickerLauncher = rememberLauncherForActivityResult(
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

            coroutineScope.launch {
                val importedBook = withContext(Dispatchers.IO) {
                    EpubParser.parseEpubFromUri(context, uri)
                }
                if (importedBook != null) {
                    val updatedBooks = listOf(importedBook) + books
                    persist(newBooks = updatedBooks)
                    Toast.makeText(
                        context,
                        "Imported \"${importedBook.title}\" (${importedBook.pages.size} pages) & synced with Notes",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Could not read the selected EPUB file",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Cover Image Picker Launcher for Notes '+' custom book note entry dialog
    val coverImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    EpubParser.copyCustomCoverImage(context, uri)
                }
                pendingCustomCoverPath = savedPath
            }
        }
    }

    // Handle system Back button
    BackHandler(enabled = currentScreen != ScreenDestination.Library) {
        currentScreen = when (val dest = currentScreen) {
            is ScreenDestination.About -> dest.previous
            is ScreenDestination.BookNotesDetail -> ScreenDestination.NotesList
            is ScreenDestination.BookReader -> ScreenDestination.Library
            is ScreenDestination.NotesList -> ScreenDestination.Library
            ScreenDestination.Library -> ScreenDestination.Library
        }
    }

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
                        books = books,
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = { newDark -> persist(newDark = newDark) },
                        onOpenAbout = {
                            currentScreen = ScreenDestination.About(ScreenDestination.Library)
                        },
                        onImportEpubClick = {
                            epubPickerLauncher.launch(
                                arrayOf("application/epub+zip", "application/octet-stream", "text/plain", "*/*")
                            )
                        },
                        onBookClick = { clickedBook ->
                            // Mark book as started when opened
                            val updatedBooks = books.map { b ->
                                if (b.id == clickedBook.id) b.copy(isStarted = true) else b
                            }
                            persist(newBooks = updatedBooks)
                            currentScreen = ScreenDestination.BookReader(clickedBook.id)
                        },
                        onNavigateToNotes = {
                            currentScreen = ScreenDestination.NotesList
                        }
                    )
                }

                ScreenDestination.NotesList -> {
                    NotesListScreen(
                        books = books,
                        annotations = annotations,
                        isDarkTheme = isDarkTheme,
                        pendingCustomCoverPath = pendingCustomCoverPath,
                        onClearPendingCustomCover = { pendingCustomCoverPath = null },
                        onPickCustomCoverImage = {
                            coverImagePickerLauncher.launch("image/*")
                        },
                        onAddCustomBookNoteEntry = { title, author, coverPath, coverStyle ->
                            val newBookEntry = Book(
                                title = title,
                                author = author,
                                coverStyle = coverStyle,
                                coverImagePath = coverPath,
                                pages = listOf("Notes notebook for $title"),
                                isCustomNoteEntryOnly = true
                            )
                            val updatedBooks = listOf(newBookEntry) + books
                            persist(newBooks = updatedBooks)
                            pendingCustomCoverPath = null
                            Toast.makeText(
                                context,
                                "Added \"$title\" to Notes",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onToggleTheme = { newDark -> persist(newDark = newDark) },
                        onOpenAbout = {
                            currentScreen = ScreenDestination.About(ScreenDestination.NotesList)
                        },
                        onBookNoteClick = { book ->
                            currentScreen = ScreenDestination.BookNotesDetail(book.id)
                        },
                        onNavigateToLibrary = {
                            currentScreen = ScreenDestination.Library
                        }
                    )
                }

                is ScreenDestination.BookNotesDetail -> {
                    val book = books.find { it.id == dest.bookId }
                    if (book == null) {
                        currentScreen = ScreenDestination.NotesList
                    } else {
                        val bookAnnotations = annotations.filter { it.bookId == book.id }
                        BookNotesDetailScreen(
                            book = book,
                            annotations = bookAnnotations,
                            isDarkTheme = isDarkTheme,
                            onBack = { currentScreen = ScreenDestination.NotesList },
                            onAddAnnotation = { type, pageNum, text ->
                                val newAnnotation = BookAnnotation(
                                    bookId = book.id,
                                    type = type,
                                    pageNumber = pageNum,
                                    text = text,
                                    timestampLabel = "Added today"
                                )
                                persist(newAnnotations = annotations + newAnnotation)
                            },
                            onToggleTheme = { newDark -> persist(newDark = newDark) },
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
                    val book = books.find { it.id == dest.bookId }
                    if (book == null) {
                        currentScreen = ScreenDestination.Library
                    } else {
                        val bookAnnotations = annotations.filter { it.bookId == book.id }
                        BookReaderScreen(
                            book = book,
                            bookAnnotations = bookAnnotations,
                            onPageChanged = { newPageIdx ->
                                if (newPageIdx != book.currentPageIndex || !book.isStarted) {
                                    val reachedEnd = newPageIdx >= book.pages.size - 1 && book.pages.size > 1
                                    val updatedBooks = books.map { b ->
                                        if (b.id == book.id) {
                                            b.copy(
                                                currentPageIndex = newPageIdx,
                                                isStarted = true,
                                                isFinished = b.isFinished || reachedEnd,
                                                fixedProgressPercent = if (newPageIdx == 0) b.fixedProgressPercent else null
                                            )
                                        } else {
                                            b
                                        }
                                    }
                                    persist(newBooks = updatedBooks)
                                }
                            },
                            onAddAnnotation = { type, pageNumber, text ->
                                val newAnnotation = BookAnnotation(
                                    bookId = book.id,
                                    type = type,
                                    pageNumber = pageNumber,
                                    text = text,
                                    timestampLabel = "Added today"
                                )
                                persist(newAnnotations = annotations + newAnnotation)
                                Toast.makeText(
                                    context,
                                    "${if (type == AnnotationType.HIGHLIGHT) "Highlight" else "Note"} synced to Notes (Page $pageNumber)",
                                    Toast.LENGTH_SHORT
                                ).show()
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
    }
}
