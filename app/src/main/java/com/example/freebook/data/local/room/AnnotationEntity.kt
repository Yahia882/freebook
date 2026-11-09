package com.example.freebook.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.HighlightColor

/**
 * Storage Tool #2: Room Relational Entity for Book Notes, Highlights & Bookmarks.
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
    @ColumnInfo(defaultValue = "0") val charOffset: Int = 0,
    @ColumnInfo(defaultValue = "0") val endCharOffset: Int = 0,
    val text: String,
    @ColumnInfo(defaultValue = "''") val quotedText: String = "",
    @ColumnInfo(defaultValue = "'YELLOW'") val highlightColorName: String = HighlightColor.YELLOW.name,
    val timestampLabel: String,
    val createdAt: Long
) {
    fun toDomainModel(): BookAnnotation = BookAnnotation(
        id = id,
        bookId = bookId,
        type = runCatching { AnnotationType.valueOf(type) }.getOrDefault(AnnotationType.NOTE),
        pageNumber = pageNumber,
        charOffset = charOffset,
        endCharOffset = endCharOffset,
        text = text,
        quotedText = quotedText,
        highlightColorName = highlightColorName.ifBlank { HighlightColor.YELLOW.name },
        timestampLabel = timestampLabel,
        createdAt = createdAt
    )

    companion object {
        fun fromDomainModel(model: BookAnnotation): AnnotationEntity = AnnotationEntity(
            id = model.id,
            bookId = model.bookId,
            type = model.type.name,
            pageNumber = model.pageNumber,
            charOffset = model.charOffset,
            endCharOffset = model.endCharOffset,
            text = model.text,
            quotedText = model.quotedText,
            highlightColorName = model.highlightColorName,
            timestampLabel = model.timestampLabel,
            createdAt = model.createdAt
        )
    }
}
