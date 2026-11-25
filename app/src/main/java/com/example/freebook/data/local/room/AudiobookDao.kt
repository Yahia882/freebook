package com.example.freebook.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookDao {

    // ---- Groups (Categories / Chapters) ----

    @Query("SELECT * FROM audiobook_groups WHERE bookId = :bookId ORDER BY orderIndex ASC")
    fun observeGroupsForBook(bookId: String): Flow<List<AudiobookGroupEntity>>

    @Query("SELECT * FROM audiobook_groups WHERE bookId = :bookId ORDER BY orderIndex ASC")
    suspend fun getGroupsForBookOnce(bookId: String): List<AudiobookGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: AudiobookGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(groups: List<AudiobookGroupEntity>)

    @Query("UPDATE audiobook_groups SET title = :title WHERE id = :groupId")
    suspend fun renameGroup(groupId: String, title: String)

    @Query("UPDATE audiobook_groups SET isCollapsed = :collapsed WHERE id = :groupId")
    suspend fun setGroupCollapsed(groupId: String, collapsed: Boolean)

    @Query("DELETE FROM audiobook_groups WHERE id = :groupId")
    suspend fun deleteGroupById(groupId: String)

    // ---- Tracks ----

    @Query("SELECT * FROM audiobook_tracks WHERE bookId = :bookId ORDER BY orderIndex ASC")
    fun observeTracksForBook(bookId: String): Flow<List<AudiobookTrackEntity>>

    @Query("SELECT * FROM audiobook_tracks WHERE bookId = :bookId ORDER BY orderIndex ASC")
    suspend fun getTracksForBookOnce(bookId: String): List<AudiobookTrackEntity>

    @Query("SELECT * FROM audiobook_tracks WHERE groupId = :groupId ORDER BY orderIndex ASC")
    suspend fun getTracksForGroupOnce(groupId: String): List<AudiobookTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: AudiobookTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<AudiobookTrackEntity>)

    @Query("UPDATE audiobook_tracks SET groupId = :targetGroupId WHERE id = :trackId")
    suspend fun moveTrackToGroup(trackId: String, targetGroupId: String)

    @Query("DELETE FROM audiobook_tracks WHERE id = :trackId")
    suspend fun deleteTrackById(trackId: String)

    // ---- Chapter Mappings ----

    @Query("SELECT * FROM audiobook_mappings WHERE bookId = :bookId")
    fun observeMappingsForBook(bookId: String): Flow<List<AudiobookMappingEntity>>

    @Query("SELECT * FROM audiobook_mappings WHERE bookId = :bookId")
    suspend fun getMappingsForBookOnce(bookId: String): List<AudiobookMappingEntity>

    @Query("SELECT * FROM audiobook_mappings WHERE id = :mappingId LIMIT 1")
    suspend fun getMappingById(mappingId: String): AudiobookMappingEntity?

    @Query("SELECT * FROM audiobook_mappings WHERE groupId = :groupId LIMIT 1")
    suspend fun getMappingByGroupId(groupId: String): AudiobookMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMapping(mapping: AudiobookMappingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMappings(mappings: List<AudiobookMappingEntity>)

    @Query("UPDATE audiobook_mappings SET anchorsJson = :anchorsJson WHERE id = :mappingId")
    suspend fun updateMappingAnchors(mappingId: String, anchorsJson: String)

    @Query("DELETE FROM audiobook_mappings WHERE id = :mappingId")
    suspend fun deleteMappingById(mappingId: String)

    // ---- Unified AudioAligner Cache (chapter_alignments) ----

    @Query("SELECT * FROM chapter_alignments WHERE bookId = :bookId")
    fun observeAlignmentsForBook(bookId: String): Flow<List<ChapterAlignmentEntity>>

    @Query("SELECT * FROM chapter_alignments WHERE mappingId = :mappingId LIMIT 1")
    suspend fun getAlignmentForMapping(mappingId: String): ChapterAlignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlignment(alignment: ChapterAlignmentEntity)

    @Query("UPDATE chapter_alignments SET status = :status, progressPercent = :progressPercent, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE mappingId = :mappingId")
    suspend fun updateAlignmentStatus(
        mappingId: String,
        status: String,
        progressPercent: Int,
        errorMessage: String = "",
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM chapter_alignments WHERE mappingId = :mappingId")
    suspend fun deleteAlignmentForMapping(mappingId: String)

    // ---- PDF Page Metrics Cache ----

    @Query("SELECT * FROM pdf_page_metrics WHERE bookId = :bookId ORDER BY pageIndex ASC")
    suspend fun getPdfPageMetricsOnce(bookId: String): List<PdfPageMetricEntity>

    @Query("SELECT COUNT(*) FROM pdf_page_metrics WHERE bookId = :bookId")
    suspend fun countPdfPageMetrics(bookId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPdfPageMetrics(metrics: List<PdfPageMetricEntity>)

    @Query("DELETE FROM pdf_page_metrics WHERE bookId = :bookId")
    suspend fun deletePdfPageMetricsForBook(bookId: String)

    // ---- Playback Resume State ----

    @Query("SELECT * FROM audiobook_playback_state WHERE bookId = :bookId LIMIT 1")
    suspend fun getPlaybackStateOnce(bookId: String): AudiobookPlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackState(state: AudiobookPlaybackStateEntity)
}
