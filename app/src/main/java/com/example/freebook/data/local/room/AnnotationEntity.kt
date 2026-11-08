package com.example.freebook.data.local.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.BookAnnotation

/**
 * Storage Tool #2: Room Relational Entity for Book Notes & Highlights.
 * Indexed by `(bookId, pageNumber)` for fast relational queries across thousands of records,
 * and cascades deletions automatically when a parent `BookEntity` is removed.
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId", "pageNumber", "createdAt"])
    ]
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val type: String,
    val pageNumber: Int,
    val text: String,
    val timestampLabel: String,
    val createdAt: Long
) {
    fun toDomainModel(): BookAnnotation = BookAnnotation(
        id = id,
        bookId = bookId,
        type = runCatching { AnnotationType.valueOf(type) }.getOrDefault(AnnotationType.NOTE),
        pageNumber = pageNumber,
        text = text,
        timestampLabel = timestampLabel,
        createdAt = createdAt
    )

    companion object {
        fun fromDomainModel(model: BookAnnotation): AnnotationEntity = AnnotationEntity(
            id = model.id,
            bookId = model.bookId,
            type = model.type.name,
            pageNumber = model.pageNumber,
            text = model.text,
            timestampLabel = model.timestampLabel,
            createdAt = model.createdAt
        )
    }
}
