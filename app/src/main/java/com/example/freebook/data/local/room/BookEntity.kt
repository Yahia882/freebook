package com.example.freebook.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.freebook.model.Book

/**
 * Storage Tool #2: Room Relational Entity for Book Metadata & Progress.
 * Note:
 * - `contentHash` is uniquely indexed (`unique = true`) to enforce objective content identity.
 * - Never stores large book text or cover bytes — only `bookFilePath`, `textContentFilePath`, and `coverImagePath`.
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
    val readingProgress: Float,
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
        readingProgress = readingProgress,
        isFinished = isFinished,
        isStarted = isStarted,
        isCustomNoteEntryOnly = isCustomNoteEntryOnly,
        createdAt = createdAt,
        lastReadAt = lastReadAt
    )
}
