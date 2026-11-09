package com.example.freebook.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.freebook.model.Book
import com.example.freebook.model.BookReadingDirection
import com.example.freebook.model.PublicationLayoutType

/**
 * Storage Tool #2: Room Relational Entity for Book Metadata & Stable Locator Progress.
 */
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index(value = ["normalizedTitle"]),
        Index(value = ["isCustomNoteEntryOnly", "lastReadAt"])
    ]
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val normalizedTitle: String,
    val normalizedAuthor: String,
    val contentHash: String,
    val bookFilePath: String,
    val textContentFilePath: String,
    val coverImagePath: String?,
    val currentPageIndex: Int,
    val totalPages: Int,
    @ColumnInfo(defaultValue = "0") val readingCharOffset: Int,
    @ColumnInfo(defaultValue = "''") val locatorJson: String = "",
    val readingProgress: Float,
    @ColumnInfo(defaultValue = "'REFLOWABLE'") val layoutType: String = PublicationLayoutType.REFLOWABLE.name,
    @ColumnInfo(defaultValue = "'LTR'") val readingDirection: String = BookReadingDirection.LTR.name,
    @ColumnInfo(defaultValue = "'en'") val language: String = "en",
    val isFinished: Boolean,
    val isStarted: Boolean,
    val isCustomNoteEntryOnly: Boolean,
    val normalizedCharLength: Int,
    val createdAt: Long,
    val lastReadAt: Long
) {
    fun toDomainModel(): Book = Book(
        id = id,
        title = title,
        author = author,
        contentHash = contentHash,
        bookFilePath = bookFilePath,
        textContentFilePath = textContentFilePath,
        coverImagePath = coverImagePath,
        currentPageIndex = currentPageIndex,
        totalPages = totalPages,
        readingCharOffset = readingCharOffset,
        locatorJson = locatorJson,
        readingProgress = readingProgress,
        layoutType = runCatching { PublicationLayoutType.valueOf(layoutType) }
            .getOrDefault(PublicationLayoutType.REFLOWABLE),
        readingDirection = runCatching { BookReadingDirection.valueOf(readingDirection) }
            .getOrDefault(BookReadingDirection.LTR),
        language = language.ifBlank { "en" },
        isFinished = isFinished,
        isStarted = isStarted,
        isCustomNoteEntryOnly = isCustomNoteEntryOnly,
        createdAt = createdAt,
        lastReadAt = lastReadAt
    )
}
