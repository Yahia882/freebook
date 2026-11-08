package com.example.freebook.data

import android.content.Context
import android.net.Uri
import com.example.freebook.data.local.room.AnnotationDao
import com.example.freebook.data.local.room.AnnotationEntity
import com.example.freebook.data.local.room.BookDao
import com.example.freebook.data.local.room.BookEntity
import com.example.freebook.data.storage.BookFileStorage
import com.example.freebook.data.storage.ContentIdentityHasher
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.BookImportResult
import com.example.freebook.model.StagedBookImport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface BookRepository {
    val booksFlow: Flow<List<Book>>
    val annotationsFlow: Flow<List<BookAnnotation>>

    suspend fun importBookFromUri(context: Context, uri: Uri): BookImportResult
    suspend fun confirmFuzzyDuplicateImport(stagedImport: StagedBookImport): Book?
    suspend fun discardStagedImport(stagedImport: StagedBookImport)
    suspend fun addCustomNoteNotebook(
        title: String,
        author: String,
        coverImagePath: String?
    ): Book
    suspend fun loadBookText(book: Book): String
    suspend fun updateReadingProgress(
        bookId: String,
        currentPageIndex: Int,
        totalPages: Int,
        readingCharOffset: Int,
        readingProgress: Float,
        isStarted: Boolean,
        isFinished: Boolean
    )
    suspend fun addAnnotation(
        bookId: String,
        type: AnnotationType,
        pageNumber: Int,
        text: String
    ): BookAnnotation
    suspend fun deleteBook(book: Book)
    suspend fun deleteAnnotation(annotationId: String)
}

class OfflineFirstBookRepository(
    private val bookDao: BookDao,
    private val annotationDao: AnnotationDao,
    private val fileStorage: BookFileStorage
) : BookRepository {

    override val booksFlow: Flow<List<Book>> = bookDao.observeAllBooks()
        .map { entities -> entities.map { it.toDomainModel() } }

    override val annotationsFlow: Flow<List<BookAnnotation>> = annotationDao.observeAllAnnotations()
        .map { entities -> entities.map { it.toDomainModel() } }

    override suspend fun importBookFromUri(context: Context, uri: Uri): BookImportResult {
        fileStorage.cleanupLegacyDemoFile()

        val staged = EpubParser.stageBookFromUri(context, uri, fileStorage)
            ?: return BookImportResult.Error("Could not read the selected file.")

        // 1. Objective Check: Exact content hash match -> Block silently and clean up copied files
        val existingExact = bookDao.getBookByContentHash(staged.contentHash)
        if (existingExact != null) {
            fileStorage.deleteBookFiles(
                rawFilePath = staged.rawFilePath,
                textContentFilePath = staged.textContentFilePath,
                coverImagePath = staged.coverImagePath
            )
            return BookImportResult.ExactDuplicateSilentBlock(existingExact.toDomainModel())
        }

        // 2. Subjective Check: Fuzzy duplicate detection across existing library books -> Warn + Confirm
        val existingLibraryBooks = bookDao.getAllLibraryBooksOnce()
        for (candidate in existingLibraryBooks) {
            val fuzzyReason = ContentIdentityHasher.detectFuzzyDuplicateReason(
                newNormTitle = staged.normalizedTitle,
                newNormAuthor = staged.normalizedAuthor,
                newCharLength = staged.normalizedCharLength,
                existingNormTitle = candidate.normalizedTitle,
                existingNormAuthor = candidate.normalizedAuthor,
                existingCharLength = candidate.normalizedCharLength
            )
            if (fuzzyReason != null) {
                return BookImportResult.FuzzyDuplicateRequiresConfirmation(
                    stagedImport = staged,
                    existingBook = candidate.toDomainModel(),
                    reason = fuzzyReason
                )
            }
        }

        // 3. No duplicate -> Commit to Room database
        val committed = insertStagedBook(staged)
        return if (committed != null) {
            BookImportResult.Imported(committed)
        } else {
            // Unique index on `contentHash` rejected insertion (race condition exact duplicate)
            fileStorage.deleteBookFiles(
                rawFilePath = staged.rawFilePath,
                textContentFilePath = staged.textContentFilePath,
                coverImagePath = staged.coverImagePath
            )
            val current = bookDao.getBookByContentHash(staged.contentHash)?.toDomainModel()
            if (current != null) {
                BookImportResult.ExactDuplicateSilentBlock(current)
            } else {
                BookImportResult.Error("Could not import book.")
            }
        }
    }

    override suspend fun confirmFuzzyDuplicateImport(stagedImport: StagedBookImport): Book? {
        return insertStagedBook(stagedImport)
    }

    override suspend fun discardStagedImport(stagedImport: StagedBookImport) {
        fileStorage.deleteBookFiles(
            rawFilePath = stagedImport.rawFilePath,
            textContentFilePath = stagedImport.textContentFilePath,
            coverImagePath = stagedImport.coverImagePath
        )
    }

    private suspend fun insertStagedBook(staged: StagedBookImport): Book? {
        val now = System.currentTimeMillis()
        val entity = BookEntity(
            id = UUID.randomUUID().toString(),
            title = staged.title,
            author = staged.author,
            normalizedTitle = staged.normalizedTitle,
            normalizedAuthor = staged.normalizedAuthor,
            contentHash = staged.contentHash,
            bookFilePath = staged.rawFilePath,
            textContentFilePath = staged.textContentFilePath,
            coverImagePath = staged.coverImagePath,
            currentPageIndex = 0,
            totalPages = staged.estimatedTotalPages.coerceAtLeast(1),
            readingCharOffset = 0,
            readingProgress = 0f,
            isFinished = false,
            isStarted = false,
            isCustomNoteEntryOnly = false,
            normalizedCharLength = staged.normalizedCharLength,
            createdAt = now,
            lastReadAt = now
        )
        val rowId = bookDao.insertBookOrIgnore(entity)
        if (rowId == -1L) {
            fileStorage.deleteBookFiles(
                rawFilePath = staged.rawFilePath,
                textContentFilePath = staged.textContentFilePath,
                coverImagePath = staged.coverImagePath
            )
            return null
        }
        return entity.toDomainModel()
    }

    override suspend fun addCustomNoteNotebook(
        title: String,
        author: String,
        coverImagePath: String?
    ): Book {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val uniqueNotebookHash = ContentIdentityHasher.sha256Hex("notebook_${id}_${title}_$author")
        val entity = BookEntity(
            id = id,
            title = title,
            author = author,
            normalizedTitle = ContentIdentityHasher.normalizeMetadataField(title),
            normalizedAuthor = ContentIdentityHasher.normalizeMetadataField(author),
            contentHash = uniqueNotebookHash,
            bookFilePath = "",
            textContentFilePath = "",
            coverImagePath = coverImagePath,
            currentPageIndex = 0,
            totalPages = 1,
            readingCharOffset = 0,
            readingProgress = 0f,
            isFinished = false,
            isStarted = false,
            isCustomNoteEntryOnly = true,
            normalizedCharLength = 0,
            createdAt = now,
            lastReadAt = now
        )
        bookDao.insertBookOrIgnore(entity)
        return entity.toDomainModel()
    }

    override suspend fun loadBookText(book: Book): String {
        val raw = fileStorage.readBookText(book.textContentFilePath)
        return raw.ifBlank {
            "${book.title}\nby ${book.author}"
        }
    }

    override suspend fun updateReadingProgress(
        bookId: String,
        currentPageIndex: Int,
        totalPages: Int,
        readingCharOffset: Int,
        readingProgress: Float,
        isStarted: Boolean,
        isFinished: Boolean
    ) {
        bookDao.updateReadingProgress(
            bookId = bookId,
            currentPageIndex = currentPageIndex.coerceAtLeast(0),
            totalPages = totalPages.coerceAtLeast(1),
            readingCharOffset = readingCharOffset.coerceAtLeast(0),
            readingProgress = readingProgress.coerceIn(0f, 100f),
            isStarted = isStarted,
            isFinished = isFinished,
            lastReadAt = System.currentTimeMillis()
        )
    }

    override suspend fun addAnnotation(
        bookId: String,
        type: AnnotationType,
        pageNumber: Int,
        text: String
    ): BookAnnotation {
        val model = BookAnnotation(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            type = type,
            pageNumber = pageNumber.coerceAtLeast(1),
            text = text,
            timestampLabel = "Added today",
            createdAt = System.currentTimeMillis()
        )
        annotationDao.insertAnnotation(AnnotationEntity.fromDomainModel(model))
        return model
    }

    override suspend fun deleteBook(book: Book) {
        val stored = bookDao.getBookById(book.id)
        bookDao.deleteBookById(book.id)
        fileStorage.deleteBookFiles(
            rawFilePath = stored?.bookFilePath ?: book.bookFilePath,
            textContentFilePath = stored?.textContentFilePath ?: book.textContentFilePath,
            coverImagePath = stored?.coverImagePath ?: book.coverImagePath
        )
    }

    override suspend fun deleteAnnotation(annotationId: String) {
        annotationDao.deleteAnnotationById(annotationId)
    }
}
