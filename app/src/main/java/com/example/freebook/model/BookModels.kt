package com.example.freebook.model

import java.util.UUID

enum class CoverStyle {
    ARCH_LIGHT,       // "A Room Of One's Own" - Light cover with dark rounded arch
    CIRCLE_PEDESTAL,  // "Meditations" - Soft gray cover with circle and pedestal lines
    OVAL_DARK,        // "The Book Of Tea" - Dark charcoal cover with minimal oval
    DEFAULT_EDITORIAL // Default minimalist cover for imported EPUBs or custom book note entries
}

enum class AnnotationType {
    HIGHLIGHT,
    NOTE
}

enum class LibraryFilter(val label: String) {
    ALL("All"),
    CONTINUE("Continue"),
    FINISHED("Finished")
}

data class BookAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val type: AnnotationType,
    val pageNumber: Int,
    val text: String,
    val timestampLabel: String = "Added today",
    val createdAt: Long = System.currentTimeMillis()
)

data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val coverStyle: CoverStyle = CoverStyle.DEFAULT_EDITORIAL,
    val coverImagePath: String? = null,
    val pages: List<String>,
    val currentPageIndex: Int = 0,
    val displayPageOffset: Int = 1,
    val displayTotalPages: Int = pages.size.coerceAtLeast(1),
    val isFinished: Boolean = false,
    val isStarted: Boolean = false,
    val isCustomNoteEntryOnly: Boolean = false,
    val fixedProgressPercent: Int? = null
) {
    val currentDisplayPage: Int
        get() = (currentPageIndex + displayPageOffset).coerceIn(1, displayTotalPages.coerceAtLeast(1))

    val progressPercentage: Float
        get() {
            if (isFinished) return 100f
            if (!isStarted && currentPageIndex == 0 && fixedProgressPercent == null) return 0f
            fixedProgressPercent?.let {
                if (currentPageIndex == 0) return it.toFloat()
            }
            if (pages.size <= 1) return if (isFinished) 100f else 50f
            return ((currentPageIndex + 1).toFloat() / pages.size.toFloat() * 100f).coerceIn(1f, 99.9f)
        }

    val statusText: String
        get() = when {
            isFinished -> "Finished"
            !isStarted && fixedProgressPercent == null && currentPageIndex == 0 -> "New"
            else -> "${progressPercentage.toInt()}%"
        }
}
