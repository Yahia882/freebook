package com.example.freebook.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.freebook.BookFreeApplication
import com.example.freebook.data.BookRepository
import com.example.freebook.data.EpubParser
import com.example.freebook.data.local.preferences.UserPreferencesRepository
import com.example.freebook.data.storage.BookFileStorage
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.BookImportResult
import com.example.freebook.model.ReaderPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class BookFreeUiState(
    val books: List<Book> = emptyList(),
    val annotations: List<BookAnnotation> = emptyList(),
    val preferences: ReaderPreferences = ReaderPreferences(),
    val pendingFuzzyDuplicate: BookImportResult.FuzzyDuplicateRequiresConfirmation? = null,
    val pendingCustomCoverPath: String? = null,
    val toastMessage: String? = null
)

private data class TransientUiState(
    val pendingFuzzyDuplicate: BookImportResult.FuzzyDuplicateRequiresConfirmation? = null,
    val pendingCustomCoverPath: String? = null,
    val toastMessage: String? = null
)

class BookFreeViewModel(
    private val bookRepository: BookRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val bookFileStorage: BookFileStorage
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientUiState())

    val uiState: StateFlow<BookFreeUiState> = combine(
        bookRepository.booksFlow,
        bookRepository.annotationsFlow,
        userPreferencesRepository.preferencesFlow,
        transientState
    ) { books, annotations, prefs, transient ->
        BookFreeUiState(
            books = books,
            annotations = annotations,
            preferences = prefs,
            pendingFuzzyDuplicate = transient.pendingFuzzyDuplicate,
            pendingCustomCoverPath = transient.pendingCustomCoverPath,
            toastMessage = transient.toastMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BookFreeUiState()
    )

    fun importBook(context: Context, uri: Uri) {
        viewModelScope.launch {
            when (val result = bookRepository.importBookFromUri(context.applicationContext, uri)) {
                is BookImportResult.Imported -> {
                    transientState.update {
                        it.copy(toastMessage = "Imported \"${result.book.title}\"")
                    }
                }
                is BookImportResult.ExactDuplicateSilentBlock -> {
                    // Exact-content duplicate blocked silently & staged files already cleaned up.
                }
                is BookImportResult.FuzzyDuplicateRequiresConfirmation -> {
                    transientState.update {
                        it.copy(pendingFuzzyDuplicate = result)
                    }
                }
                is BookImportResult.Error -> {
                    transientState.update {
                        it.copy(toastMessage = result.message)
                    }
                }
            }
        }
    }

    fun confirmFuzzyDuplicateImport() {
        val pending = transientState.value.pendingFuzzyDuplicate ?: return
        transientState.update { it.copy(pendingFuzzyDuplicate = null) }
        viewModelScope.launch {
            val imported = bookRepository.confirmFuzzyDuplicateImport(pending.stagedImport)
            if (imported != null) {
                transientState.update {
                    it.copy(toastMessage = "Imported \"${imported.title}\"")
                }
            }
        }
    }

    fun dismissFuzzyDuplicateImport() {
        val pending = transientState.value.pendingFuzzyDuplicate ?: return
        transientState.update { it.copy(pendingFuzzyDuplicate = null) }
        viewModelScope.launch {
            bookRepository.discardStagedImport(pending.stagedImport)
        }
    }

    fun pickCustomCoverImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            val savedPath = EpubParser.copyCustomCoverImage(
                context = context.applicationContext,
                uri = uri,
                fileStorage = bookFileStorage
            )
            transientState.update { it.copy(pendingCustomCoverPath = savedPath) }
        }
    }

    fun clearPendingCustomCover(deleteFileIfUnused: Boolean = true) {
        val oldPath = transientState.value.pendingCustomCoverPath
        transientState.update { it.copy(pendingCustomCoverPath = null) }
        if (deleteFileIfUnused && !oldPath.isNullOrBlank()) {
            viewModelScope.launch {
                runCatching { File(oldPath).delete() }
            }
        }
    }

    fun addCustomNoteNotebook(title: String, author: String, coverImagePath: String?) {
        viewModelScope.launch {
            val added = bookRepository.addCustomNoteNotebook(
                title = title,
                author = author,
                coverImagePath = coverImagePath
            )
            transientState.update {
                it.copy(
                    pendingCustomCoverPath = null,
                    toastMessage = "Added \"${added.title}\" to Notes"
                )
            }
        }
    }

    suspend fun loadBookText(book: Book): String {
        return bookRepository.loadBookText(book)
    }

    fun markBookStarted(book: Book) {
        if (book.isStarted) return
        viewModelScope.launch {
            bookRepository.updateReadingProgress(
                bookId = book.id,
                currentPageIndex = book.currentPageIndex,
                totalPages = book.totalPages.coerceAtLeast(1),
                readingCharOffset = book.readingCharOffset,
                readingProgress = book.readingProgress,
                isStarted = true,
                isFinished = book.isFinished
            )
        }
    }

    /**
     * Persists the reader position on EVERY page change (forward, backward, or back to page 1).
     * The position is stored as a layout-independent character offset so it survives
     * text-size / screen-size changes, which alter the number of pages.
     */
    fun updateReadingProgress(
        bookId: String,
        charOffset: Int,
        totalChars: Int,
        pageIndex: Int,
        pageCount: Int
    ) {
        val safeTotalChars = totalChars.coerceAtLeast(1)
        val safeOffset = charOffset.coerceIn(0, safeTotalChars - 1)
        val safePageCount = pageCount.coerceAtLeast(1)
        val isLastPage = safePageCount > 1 && pageIndex >= safePageCount - 1
        val progress = if (isLastPage) {
            100f
        } else {
            (safeOffset.toFloat() / safeTotalChars.toFloat() * 100f).coerceIn(1f, 99.9f)
        }

        viewModelScope.launch {
            bookRepository.updateReadingProgress(
                bookId = bookId,
                currentPageIndex = pageIndex.coerceAtLeast(0),
                totalPages = safePageCount,
                readingCharOffset = safeOffset,
                readingProgress = progress,
                isStarted = true,
                isFinished = isLastPage
            )
        }
    }

    fun addAnnotation(
        bookId: String,
        type: AnnotationType,
        pageNumber: Int,
        text: String,
        showToast: Boolean = false
    ) {
        viewModelScope.launch {
            bookRepository.addAnnotation(
                bookId = bookId,
                type = type,
                pageNumber = pageNumber,
                text = text
            )
            if (showToast) {
                val label = when (type) {
                    AnnotationType.HIGHLIGHT -> "Highlight"
                    AnnotationType.BOOKMARK -> "Bookmark"
                    AnnotationType.NOTE -> "Note"
                }
                transientState.update {
                    it.copy(toastMessage = "$label saved (Page $pageNumber)")
                }
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            bookRepository.deleteBook(book)
            transientState.update {
                it.copy(toastMessage = "Deleted \"${book.title}\"")
            }
        }
    }

    fun deleteAnnotation(annotation: BookAnnotation) {
        viewModelScope.launch {
            bookRepository.deleteAnnotation(annotation.id)
            val label = when (annotation.type) {
                AnnotationType.HIGHLIGHT -> "Highlight"
                AnnotationType.BOOKMARK -> "Bookmark"
                AnnotationType.NOTE -> "Note"
            }
            transientState.update {
                it.copy(toastMessage = "$label deleted")
            }
        }
    }

    fun setDarkTheme(isDark: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDarkTheme(isDark)
        }
    }

    fun setFontSizeSp(fontSizeSp: Float) {
        viewModelScope.launch {
            userPreferencesRepository.setFontSizeSp(fontSizeSp)
        }
    }

    fun setEnablePageAnimations(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setEnablePageAnimations(enabled)
        }
    }

    fun consumeToastMessage() {
        transientState.update { it.copy(toastMessage = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as BookFreeApplication
                val container = application.container
                BookFreeViewModel(
                    bookRepository = container.bookRepository,
                    userPreferencesRepository = container.userPreferencesRepository,
                    bookFileStorage = container.bookFileStorage
                )
            }
        }
    }
}
