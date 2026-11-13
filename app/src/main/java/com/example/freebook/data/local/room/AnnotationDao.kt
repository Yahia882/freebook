package com.example.freebook.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    @Query("SELECT * FROM annotations ORDER BY pageNumber ASC, createdAt ASC")
    fun observeAllAnnotations(): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY pageNumber ASC, createdAt ASC")
    fun observeAnnotationsForBook(bookId: String): Flow<List<AnnotationEntity>>

    @Query(
        "SELECT * FROM annotations WHERE bookId = :bookId AND type = :type AND pageNumber = :pageNumber LIMIT 1"
    )
    suspend fun findAnnotationByBookTypeAndPage(
        bookId: String,
        type: String,
        pageNumber: Int
    ): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotation(annotation: AnnotationEntity)

    @Query("UPDATE annotations SET highlightColorName = :highlightColorName WHERE id = :annotationId")
    suspend fun updateAnnotationHighlightColor(annotationId: String, highlightColorName: String)

    @Query("DELETE FROM annotations WHERE id = :annotationId")
    suspend fun deleteAnnotationById(annotationId: String)
}
