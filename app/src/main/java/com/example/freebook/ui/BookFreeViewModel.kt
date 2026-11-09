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
import com.example.freebook.model.FixedLayoutFitMode
import com.example.freebook.model.HighlightColor
import com.example.freebook.model.ReaderNavigationMode
import com.example.freebook.model.ReaderPreferences
import com.example.freebook.model.ReaderTextAlign
import com.example.freebook.model.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

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
                locatorJson = book.locatorJson,
                readingProgress = book.readingProgress,
                isStarted = true,
                isFinished = book.isFinished
            )
        }
    }

    /**
     * Persists the reader position as a stable layout-independent locator (`charOffset` + `locatorJson` + `progress`).
     */
    fun updateReadingLocator(
        bookId: String,
        charOffset: Int,
        totalChars: Int,
        derivedPositionIndex: Int,
        derivedPositionCount: Int
    ) {
        val safeTotalChars = totalChars.coerceAtLeast(1)
        val safeOffset = charOffset.coerceIn(0, (safeTotalChars - 1).coerceAtLeast(0))
        val safePositionCount = derivedPositionCount.coerceAtLeast(1)
        val isAtEnd = safePositionCount > 1 && derivedPositionIndex >= safePositionCount - 1
        val progression = (safeOffset.toDouble() / safeTotalChars.toDouble()).coerceIn(0.0, 1.0)
        val progressPercent = if (isAtEnd) {
            100f
        } else {
            (progression.toFloat() * 100f).coerceIn(1f, 99.9f)
        }
        val locatorJson = String.format(
            Locale.US,
            "{\"locations\":{\"progression\":%.5f,\"totalProgression\":%.5f,\"position\":%d,\"charOffset\":%d}}",
            progression,
            progression,
            derivedPositionIndex + 1,
            safeOffset
        )

        viewModelScope.launch {
            bookRepository.updateReadingProgress(
                bookId = bookId,
                currentPageIndex = derivedPositionIndex.coerceAtLeast(0),
                totalPages = safePositionCount,
                readingCharOffset = safeOffset,
                locatorJson = locatorJson,
                readingProgress = progressPercent,
                isStarted = true,
                isFinished = isAtEnd
            )
        }
    }

    fun addAnnotation(
        bookId: String,
        type: AnnotationType,
        pageNumber: Int,
        charOffset: Int = 0,
        endCharOffset: Int = 0,
        text: String,
        quotedText: String = "",
        highlightColorName: String = HighlightColor.YELLOW.name,
        showToast: Boolean = false
    ) {
        viewModelScope.launch {
            bookRepository.addAnnotation(
                bookId = bookId,
                type = type,
                pageNumber = pageNumber,
                charOffset = charOffset,
                endCharOffset = endCharOffset,
                text = text,
                quotedText = quotedText,
                highlightColorName = highlightColorName
            )
            if (showToast) {
                val label = when (type) {
                    AnnotationType.HIGHLIGHT -> "Highlight"
                    AnnotationType.BOOKMARK -> "Bookmark"
                    AnnotationType.NOTE -> "Note"
                }
                transientState.update {
                    it.copy(toastMessage = "$label saved")
                }
            }
        }
    }

    fun updateAnnotationHighlightColor(annotationId: String, highlightColor: HighlightColor) {
        viewModelScope.launch {
            bookRepository.updateAnnotationHighlightColor(annotationId, highlightColor)
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

    fun setReaderTheme(theme: ReaderTheme) {
        viewModelScope.launch {
            userPreferencesRepository.setReaderTheme(theme)
        }
    }

    fun setFontSizeMultiplier(multiplier: Float) {
        viewModelScope.launch {
            userPreferencesRepository.setFontSizeMultiplier(multiplier)
        }
    }

    fun setLineHeightMultiplier(multiplier: Float) {
        viewModelScope.launch {
            userPreferencesRepository.setLineHeightMultiplier(multiplier)
        }
    }

    fun setPageMarginDp(marginDp: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setPageMarginDp(marginDp)
        }
    }

    fun setTextAlignment(alignment: ReaderTextAlign) {
        viewModelScope.launch {
            userPreferencesRepository.setTextAlignment(alignment)
        }
    }

    fun setPublisherStyles(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setPublisherStyles(enabled)
        }
    }

    fun setNavigationMode(mode: ReaderNavigationMode) {
        viewModelScope.launch {
            userPreferencesRepository.setNavigationMode(mode)
        }
    }

    fun setFixedLayoutFitMode(fitMode: FixedLayoutFitMode) {
        viewModelScope.launch {
            userPreferencesRepository.setFixedLayoutFitMode(fitMode)
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
