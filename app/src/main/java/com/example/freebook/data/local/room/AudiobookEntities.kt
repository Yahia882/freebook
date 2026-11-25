package com.example.freebook.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.freebook.model.AlignmentProvider
import com.example.freebook.model.AlignmentStatus
import com.example.freebook.model.AudiobookChapterMapping
import com.example.freebook.model.AudiobookGroup
import com.example.freebook.model.AudiobookPlaybackState
import com.example.freebook.model.AudiobookTrack
import com.example.freebook.model.ChapterAlignmentRecord
import com.example.freebook.model.PdfPageMetric
import com.example.freebook.model.SyncAnchor

@Entity(
    tableName = "audiobook_groups",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "orderIndex"])]
)
data class AudiobookGroupEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val title: String,
    val orderIndex: Int,
    @ColumnInfo(defaultValue = "0") val isCollapsed: Boolean = false
) {
    fun toDomainModel(): AudiobookGroup = AudiobookGroup(
        id = id,
        bookId = bookId,
        title = title,
        orderIndex = orderIndex,
        isCollapsed = isCollapsed
    )

    companion object {
        fun fromDomainModel(model: AudiobookGroup): AudiobookGroupEntity = AudiobookGroupEntity(
            id = model.id,
            bookId = model.bookId,
            title = model.title,
            orderIndex = model.orderIndex,
            isCollapsed = model.isCollapsed
        )
    }
}

@Entity(
    tableName = "audiobook_tracks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AudiobookGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId", "orderIndex"]),
        Index(value = ["groupId", "orderIndex"])
    ]
)
data class AudiobookTrackEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val groupId: String,
    val sourceUri: String,
    @ColumnInfo(defaultValue = "0") val isCopiedLocally: Boolean = false,
    val title: String,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val clipStartMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val clipEndMs: Long = 0L,
    val orderIndex: Int,
    @ColumnInfo(defaultValue = "''") val audioHash: String = ""
) {
    fun toDomainModel(): AudiobookTrack = AudiobookTrack(
        id = id,
        bookId = bookId,
        groupId = groupId,
        sourceUri = sourceUri,
        isCopiedLocally = isCopiedLocally,
        title = title,
        durationMs = durationMs,
        clipStartMs = clipStartMs,
        clipEndMs = clipEndMs,
        orderIndex = orderIndex,
        audioHash = audioHash
    )

    companion object {
        fun fromDomainModel(model: AudiobookTrack): AudiobookTrackEntity = AudiobookTrackEntity(
            id = model.id,
            bookId = model.bookId,
            groupId = model.groupId,
            sourceUri = model.sourceUri,
            isCopiedLocally = model.isCopiedLocally,
            title = model.title,
            durationMs = model.durationMs,
            clipStartMs = model.clipStartMs,
            clipEndMs = model.clipEndMs,
            orderIndex = model.orderIndex,
            audioHash = model.audioHash
        )
    }
}

@Entity(
    tableName = "audiobook_mappings",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AudiobookGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["groupId"], unique = true)
    ]
)
data class AudiobookMappingEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val groupId: String,
    @ColumnInfo(defaultValue = "''") val bookChapterTitle: String = "",
    @ColumnInfo(defaultValue = "0") val bookStartPageIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val bookEndPageIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val bookStartCharOffset: Int = 0,
    @ColumnInfo(defaultValue = "0") val bookEndCharOffset: Int = 0,
    @ColumnInfo(defaultValue = "'[]'") val anchorsJson: String = "[]"
) {
    fun toDomainModel(): AudiobookChapterMapping = AudiobookChapterMapping(
        id = id,
        bookId = bookId,
        groupId = groupId,
        bookChapterTitle = bookChapterTitle,
        bookStartPageIndex = bookStartPageIndex,
        bookEndPageIndex = bookEndPageIndex,
        bookStartCharOffset = bookStartCharOffset,
        bookEndCharOffset = bookEndCharOffset,
        anchors = SyncAnchor.deserializeList(anchorsJson)
    )

    companion object {
        fun fromDomainModel(model: AudiobookChapterMapping): AudiobookMappingEntity = AudiobookMappingEntity(
            id = model.id,
            bookId = model.bookId,
            groupId = model.groupId,
            bookChapterTitle = model.bookChapterTitle,
            bookStartPageIndex = model.bookStartPageIndex,
            bookEndPageIndex = model.bookEndPageIndex,
            bookStartCharOffset = model.bookStartCharOffset,
            bookEndCharOffset = model.bookEndCharOffset,
            anchorsJson = SyncAnchor.serializeList(model.anchors)
        )
    }
}

/**
 * Unified Room cache table populated by ANY AudioAligner implementation
 * (Free Local MMS-FA ONNX, future Cloud Pro WhisperX+Fuzzy+CTC, or imported SMIL/JSON).
 */
@Entity(
    tableName = "chapter_alignments",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AudiobookMappingEntity::class,
            parentColumns = ["id"],
            childColumns = ["mappingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["mappingId"], unique = true)
    ]
)
data class ChapterAlignmentEntity(
    @PrimaryKey val mappingId: String,
    val bookId: String,
    @ColumnInfo(defaultValue = "'NONE'") val provider: String = AlignmentProvider.NONE.name,
    @ColumnInfo(defaultValue = "'NONE'") val status: String = AlignmentStatus.NONE.name,
    @ColumnInfo(defaultValue = "0") val progressPercent: Int = 0,
    @ColumnInfo(defaultValue = "0.0") val coverageRatio: Float = 0f,
    @ColumnInfo(defaultValue = "0.0") val avgConfidence: Float = 0f,
    @ColumnInfo(defaultValue = "''") val audioFingerprint: String = "",
    @ColumnInfo(defaultValue = "''") val textHash: String = "",
    @ColumnInfo(defaultValue = "1") val tokenizerVersion: Int = 1,
    @ColumnInfo(defaultValue = "0") val wordCount: Int = 0,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val packedTimingsBlob: ByteArray? = null,
    @ColumnInfo(defaultValue = "''") val errorMessage: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis()
) {
    fun toSummary(): ChapterAlignmentRecord = ChapterAlignmentRecord(
        mappingId = mappingId,
        bookId = bookId,
        provider = runCatching { AlignmentProvider.valueOf(provider) }.getOrDefault(AlignmentProvider.NONE),
        status = runCatching { AlignmentStatus.valueOf(status) }.getOrDefault(AlignmentStatus.NONE),
        progressPercent = progressPercent,
        coverageRatio = coverageRatio,
        avgConfidence = avgConfidence,
        audioFingerprint = audioFingerprint,
        textHash = textHash,
        tokenizerVersion = tokenizerVersion,
        wordCount = wordCount,
        errorMessage = errorMessage,
        updatedAt = updatedAt
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChapterAlignmentEntity) return false
        return mappingId == other.mappingId &&
            bookId == other.bookId &&
            provider == other.provider &&
            status == other.status &&
            progressPercent == other.progressPercent &&
            coverageRatio == other.coverageRatio &&
            avgConfidence == other.avgConfidence &&
            audioFingerprint == other.audioFingerprint &&
            textHash == other.textHash &&
            tokenizerVersion == other.tokenizerVersion &&
            wordCount == other.wordCount &&
            packedTimingsBlob.contentEquals(other.packedTimingsBlob) &&
            errorMessage == other.errorMessage &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = mappingId.hashCode()
        result = 31 * result + bookId.hashCode()
        result = 31 * result + provider.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + wordCount
        result = 31 * result + (packedTimingsBlob?.contentHashCode() ?: 0)
        return result
    }
}

@Entity(
    tableName = "pdf_page_metrics",
    primaryKeys = ["bookId", "pageIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"])]
)
data class PdfPageMetricEntity(
    val bookId: String,
    val pageIndex: Int,
    val charCount: Int,
    val wordCount: Int,
    val weightedCharCount: Int,
    val isScanned: Boolean,
    val bodyStartChar: Int,
    val bodyEndChar: Int
) {
    fun toDomainModel(): PdfPageMetric = PdfPageMetric(
        bookId = bookId,
        pageIndex = pageIndex,
        charCount = charCount,
        wordCount = wordCount,
        weightedCharCount = weightedCharCount,
        isScanned = isScanned,
        bodyStartChar = bodyStartChar,
        bodyEndChar = bodyEndChar
    )

    companion object {
        fun fromDomainModel(model: PdfPageMetric): PdfPageMetricEntity = PdfPageMetricEntity(
            bookId = model.bookId,
            pageIndex = model.pageIndex,
            charCount = model.charCount,
            wordCount = model.wordCount,
            weightedCharCount = model.weightedCharCount,
            isScanned = model.isScanned,
            bodyStartChar = model.bodyStartChar,
            bodyEndChar = model.bodyEndChar
        )
    }
}

@Entity(
    tableName = "audiobook_playback_state",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AudiobookPlaybackStateEntity(
    @PrimaryKey val bookId: String,
    @ColumnInfo(defaultValue = "''") val lastTrackId: String = "",
    @ColumnInfo(defaultValue = "0") val lastPositionMs: Long = 0L,
    @ColumnInfo(defaultValue = "1.0") val playbackSpeed: Float = 1.0f,
    @ColumnInfo(defaultValue = "1") val followSyncEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomainModel(): AudiobookPlaybackState = AudiobookPlaybackState(
        bookId = bookId,
        lastTrackId = lastTrackId,
        lastPositionMs = lastPositionMs,
        playbackSpeed = playbackSpeed,
        followSyncEnabled = followSyncEnabled,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomainModel(model: AudiobookPlaybackState): AudiobookPlaybackStateEntity =
            AudiobookPlaybackStateEntity(
                bookId = model.bookId,
                lastTrackId = model.lastTrackId,
                lastPositionMs = model.lastPositionMs,
                playbackSpeed = model.playbackSpeed,
                followSyncEnabled = model.followSyncEnabled,
                updatedAt = model.updatedAt
            )
    }
}
