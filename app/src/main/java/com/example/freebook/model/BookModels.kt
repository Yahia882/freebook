package com.example.freebook.model

import java.util.UUID

enum class AnnotationType {
    HIGHLIGHT,
    NOTE,
    BOOKMARK
}

enum class LibraryFilter(val label: String) {
    ALL("All"),
    CONTINUE("Continue"),
    FINISHED("Finished")
}

/**
 * Predefined reader text sizes (like Apple Books). Larger sizes simply produce more pages,
 * and the reading position is preserved via character offset.
 */
enum class ReaderTextSize(val label: String, val sp: Float) {
    NORMAL("Normal", 17f),
    LARGE("Large", 19.5f),
    EXTRA_LARGE("Extra Large", 22f);

    companion object {
        fun fromSp(value: Float): ReaderTextSize =
            entries.minByOrNull { kotlin.math.abs(it.sp - value) } ?: NORMAL
    }
}

data class ReaderPreferences(
    val isDarkTheme: Boolean = true,
    val fontSizeSp: Float = ReaderTextSize.NORMAL.sp,
    val enablePageAnimations: Boolean = true
)

data class BookAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val type: AnnotationType,
    val pageNumber: Int,
    val text: String,
    val timestampLabel: String = "Added today",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Lightweight domain representation of a book.
 * Never holds large book text or bitmap bytes in memory/DB/DataStore —
 * only file paths (`bookFilePath`, `textContentFilePath`, `coverImagePath`).
 */
data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val contentHash: String = "",
    val bookFilePath: String = "",
    val textContentFilePath: String = "",
    val coverImagePath: String? = null,
    val currentPageIndex: Int = 0,
    val totalPages: Int = 1,
    /** Layout-independent reading position: character offset of the first visible char in the cleaned text. */
    val readingCharOffset: Int = 0,
    val readingProgress: Float = 0f,
    val isFinished: Boolean = false,
    val isStarted: Boolean = false,
    val isCustomNoteEntryOnly: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = System.currentTimeMillis()
) {
    val currentDisplayPage: Int
        get() = (currentPageIndex + 1).coerceIn(1, totalPages.coerceAtLeast(1))

    val progressPercentage: Float
        get() {
            if (isFinished) return 100f
            if (!isStarted && currentPageIndex == 0 && readingProgress <= 0f) return 0f
            if (readingProgress > 0f) return readingProgress.coerceIn(1f, 99.9f)
            if (totalPages <= 1) return if (isFinished) 100f else 50f
            return ((currentPageIndex + 1).toFloat() / totalPages.toFloat() * 100f).coerceIn(1f, 99.9f)
        }

    val statusText: String
        get() = when {
            isFinished -> "Finished"
            !isStarted && currentPageIndex == 0 && readingProgress <= 0f -> "New"
            else -> "${progressPercentage.toInt()}%"
        }
}

/**
 * Staged files and computed hashes for a newly picked book before committing to Room.
 */
data class StagedBookImport(
    val title: String,
    val author: String,
    val normalizedTitle: String,
    val normalizedAuthor: String,
    val contentHash: String,
    val rawFilePath: String,
    val textContentFilePath: String,
    val coverImagePath: String?,
    val estimatedTotalPages: Int,
    val normalizedCharLength: Int
)

sealed interface BookImportResult {
    data class Imported(val book: Book) : BookImportResult
    data class ExactDuplicateSilentBlock(val existingBook: Book) : BookImportResult
    data class FuzzyDuplicateRequiresConfirmation(
        val stagedImport: StagedBookImport,
        val existingBook: Book,
        val reason: String
    ) : BookImportResult
    data class Error(val message: String) : BookImportResult
}
