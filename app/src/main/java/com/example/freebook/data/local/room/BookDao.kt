package com.example.freebook.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, createdAt DESC")
    fun observeAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isCustomNoteEntryOnly = 0 ORDER BY createdAt DESC")
    suspend fun getAllLibraryBooksOnce(): List<BookEntity>

    @Query("SELECT * FROM books WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getBookByContentHash(contentHash: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: String): BookEntity?

    /**
     * Uses `OnConflictStrategy.IGNORE` so the unique index on `contentHash`
     * enforces at the database level that identical content can never be inserted twice.
     * Returns -1L if ignored due to conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookOrIgnore(book: BookEntity): Long

    @Query(
        """
        UPDATE books
        SET currentPageIndex = :currentPageIndex,
            totalPages = :totalPages,
            readingCharOffset = :readingCharOffset,
            readingProgress = :readingProgress,
            isStarted = :isStarted,
            isFinished = :isFinished,
            lastReadAt = :lastReadAt
        WHERE id = :bookId
        """
    )
    suspend fun updateReadingProgress(
        bookId: String,
        currentPageIndex: Int,
        totalPages: Int,
        readingCharOffset: Int,
        readingProgress: Float,
        isStarted: Boolean,
        isFinished: Boolean,
        lastReadAt: Long
    )

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: String)
}
