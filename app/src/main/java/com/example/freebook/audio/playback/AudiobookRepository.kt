package com.example.freebook.audio.playback

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.freebook.audio.sync.SyncChapter
import com.example.freebook.data.local.room.AudiobookDao
import com.example.freebook.data.local.room.AudiobookGroupEntity
import com.example.freebook.data.local.room.AudiobookMappingEntity
import com.example.freebook.data.local.room.AudiobookPlaybackStateEntity
import com.example.freebook.data.local.room.AudiobookTrackEntity
import com.example.freebook.model.AudiobookChapterMapping
import com.example.freebook.model.AudiobookGroup
import com.example.freebook.model.AudiobookPlaybackState
import com.example.freebook.model.AudiobookTrack
import com.example.freebook.model.ChapterAlignmentRecord
import com.example.freebook.model.PackedWordTimings
import com.example.freebook.model.SyncAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class BookAudiobookBundle(
    val groups: List<AudiobookGroup> = emptyList(),
    val tracks: List<AudiobookTrack> = emptyList(),
    val mappings: List<AudiobookChapterMapping> = emptyList(),
    val alignments: Map<String, ChapterAlignmentRecord> = emptyMap()
)

sealed interface ReorderedAudioEntry {
    /** A standalone top-level audio chapter (not embedded in any Category). */
    data class StandaloneTrack(val trackId: String) : ReorderedAudioEntry

    /** An explicit Category along with the ordered list of trackIds embedded inside it. */
    data class Category(val groupId: String, val trackIds: List<String>) : ReorderedAudioEntry
}

/**
 * Manages multi-file audiobook imports (SAF persistable URIs or local copy), natural numeric sorting,
 * category/chapter grouping, manual + 1-tap auto-matched book mappings, Sync Anchors,
 * and unified `chapter_alignments` cache reads.
 */
class AudiobookRepository(
    private val appContext: Context,
    private val audiobookDao: AudiobookDao
) {
    private val localAudioDir: File
        get() = File(appContext.filesDir, "audiobooks_local").apply { mkdirs() }

    fun observeBookBundle(bookId: String): Flow<BookAudiobookBundle> = combine(
        audiobookDao.observeGroupsForBook(bookId).map { list -> list.map { it.toDomainModel() } },
        audiobookDao.observeTracksForBook(bookId).map { list -> list.map { it.toDomainModel() } },
        audiobookDao.observeMappingsForBook(bookId).map { list -> list.map { it.toDomainModel() } },
        audiobookDao.observeAlignmentsForBook(bookId).map { list ->
            list.associate { it.mappingId to it.toSummary() }
        }
    ) { groups, tracks, mappings, alignments ->
        BookAudiobookBundle(
            groups = groups,
            tracks = tracks,
            mappings = mappings,
            alignments = alignments
        )
    }

    /**
     * Imports one or multiple user-picked audio files (`.mp3`, `.m4a`, `.m4b`, `.ogg`, `.opus`, `.wav`, `.flac`).
     * Uses natural numeric sorting (`Ch 2` before `Ch 10`), takes persistable SAF URI permissions
     * (zero 1 GB storage duplication by default), and creates a chapter group per file (or adds into [targetGroupId]).
     */
    suspend fun importAudioFiles(
        bookId: String,
        uris: List<Uri>,
        targetGroupId: String? = null,
        copyToAppStorage: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0

        data class StagedAudio(
            val uri: Uri,
            val fileName: String,
            val displayTitle: String,
            val durationMs: Long
        )

        val staged = uris.mapNotNull { uri ->
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = resolveFileName(uri) ?: "Audio Track"
            val meta = extractAudioMetadata(uri, name)
            StagedAudio(
                uri = uri,
                fileName = name,
                displayTitle = meta.first,
                durationMs = meta.second
            )
        }.sortedWith { a, b -> compareNatural(a.fileName, b.fileName) }

        if (staged.isEmpty()) return@withContext 0

        val existingGroups = audiobookDao.getGroupsForBookOnce(bookId)
        val existingTracks = audiobookDao.getTracksForBookOnce(bookId)
        var nextGroupOrder = (existingGroups.maxOfOrNull { it.orderIndex } ?: -1) + 1
        var nextTrackOrder = (existingTracks.maxOfOrNull { it.orderIndex } ?: -1) + 1

        val newGroups = mutableListOf<AudiobookGroupEntity>()
        val newTracks = mutableListOf<AudiobookTrackEntity>()

        for (item in staged) {
            val resolvedSource = if (copyToAppStorage) {
                copyAudioToInternal(item.uri, item.fileName) ?: item.uri.toString()
            } else {
                item.uri.toString()
            }
            val audioHash = computeFastFingerprint(resolvedSource, item.fileName, item.durationMs)

            val groupId = if (targetGroupId != null && existingGroups.any { it.id == targetGroupId }) {
                targetGroupId
            } else {
                val createdGroupId = UUID.randomUUID().toString()
                newGroups.add(
                    AudiobookGroupEntity(
                        id = createdGroupId,
                        bookId = bookId,
                        title = item.displayTitle,
                        orderIndex = nextGroupOrder++,
                        isCollapsed = false,
                        isCategory = false
                    )
                )
                createdGroupId
            }

            newTracks.add(
                AudiobookTrackEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    groupId = groupId,
                    sourceUri = resolvedSource,
                    isCopiedLocally = copyToAppStorage,
                    title = item.displayTitle,
                    durationMs = item.durationMs,
                    clipStartMs = 0L,
                    clipEndMs = 0L,
                    orderIndex = nextTrackOrder++,
                    audioHash = audioHash
                )
            )
        }

        if (newGroups.isNotEmpty()) {
            audiobookDao.upsertGroups(newGroups)
        }
        if (newTracks.isNotEmpty()) {
            audiobookDao.upsertTracks(newTracks)
        }
        newTracks.size
    }

    suspend fun createGroup(bookId: String, title: String): AudiobookGroup = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim().ifBlank { "New Category" }
        val existing = audiobookDao.getGroupsForBookOnce(bookId)
        val nextIdx = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val model = AudiobookGroup(
            bookId = bookId,
            title = cleanTitle,
            orderIndex = nextIdx,
            isCategory = true
        )
        audiobookDao.upsertGroup(AudiobookGroupEntity.fromDomainModel(model))
        model
    }

    suspend fun renameGroup(groupId: String, newTitle: String) = withContext(Dispatchers.IO) {
        val cleaned = newTitle.trim()
        if (cleaned.isNotEmpty()) {
            audiobookDao.renameGroup(groupId, cleaned)
        }
    }

    suspend fun renameTrack(trackId: String, newTitle: String) = withContext(Dispatchers.IO) {
        val cleaned = newTitle.trim()
        if (cleaned.isEmpty()) return@withContext
        val track = audiobookDao.getTrackById(trackId) ?: return@withContext
        audiobookDao.renameTrack(trackId, cleaned)
        // Keep standalone wrapper group title synced with its single track
        val groups = audiobookDao.getGroupsForBookOnce(track.bookId)
        val parent = groups.firstOrNull { it.id == track.groupId }
        if (parent != null && !parent.isCategory) {
            val siblings = audiobookDao.getTracksForGroupOnce(parent.id)
            if (siblings.size <= 1) {
                audiobookDao.renameGroup(parent.id, cleaned)
            }
        }
    }

    suspend fun setGroupCollapsed(groupId: String, collapsed: Boolean) = withContext(Dispatchers.IO) {
        audiobookDao.setGroupCollapsed(groupId, collapsed)
    }

    suspend fun moveTrackToGroup(trackId: String, targetGroupId: String) = withContext(Dispatchers.IO) {
        val track = audiobookDao.getTrackById(trackId) ?: return@withContext
        if (track.groupId == targetGroupId) return@withContext
        val oldGroupId = track.groupId
        audiobookDao.moveTrackToGroup(trackId, targetGroupId)
        // Migrate individual track mapping to stay attached to this track in its new category
        val trackMapping = audiobookDao.getMappingByTrackId(trackId)
        if (trackMapping != null) {
            audiobookDao.reassignMappingOwner(trackMapping.id, targetGroupId, trackId)
        } else {
            // If leaving a dissolved standalone wrapper group that held a legacy group mapping, preserve it on this track
            val oldGroup = audiobookDao.getGroupsForBookOnce(track.bookId).firstOrNull { it.id == oldGroupId }
            val remainingInOld = audiobookDao.getTracksForGroupOnce(oldGroupId)
            if (oldGroup != null && !oldGroup.isCategory && remainingInOld.isEmpty()) {
                val legacyMap = audiobookDao.getGroupMappingByGroupId(oldGroupId)
                if (legacyMap != null) {
                    audiobookDao.reassignMappingOwner(legacyMap.id, targetGroupId, trackId)
                }
                audiobookDao.deleteGroupById(oldGroupId)
            }
        }
    }

    suspend fun deleteGroup(groupId: String) = withContext(Dispatchers.IO) {
        val tracks = audiobookDao.getTracksForGroupOnce(groupId)
        audiobookDao.deleteGroupById(groupId)
        tracks.forEach { t ->
            if (t.isCopiedLocally) {
                runCatching { File(t.sourceUri).delete() }
            }
        }
    }

    suspend fun deleteTrack(track: AudiobookTrack) = withContext(Dispatchers.IO) {
        audiobookDao.deleteMappingByTrackId(track.id)
        audiobookDao.deleteTrackById(track.id)
        if (track.isCopiedLocally) {
            runCatching { File(track.sourceUri).delete() }
        }
        // Clean up wrapper group if this was a standalone chapter
        val groups = audiobookDao.getGroupsForBookOnce(track.bookId)
        val parent = groups.firstOrNull { it.id == track.groupId }
        if (parent != null && !parent.isCategory) {
            val remaining = audiobookDao.getTracksForGroupOnce(parent.id)
            if (remaining.isEmpty()) {
                audiobookDao.deleteGroupById(parent.id)
            }
        }
    }

    /**
     * Persists the full reordered hierarchy produced by drag-and-drop across standalone chapters and categories.
     * Moves tracks cleanly into/out of categories while preserving individual track mappings and avoiding FK cascades.
     */
    suspend fun applyReorderedStructure(
        bookId: String,
        entries: List<ReorderedAudioEntry>
    ) = withContext(Dispatchers.IO) {
        val existingGroups = audiobookDao.getGroupsForBookOnce(bookId).associateBy { it.id }
        val existingTracks = audiobookDao.getTracksForBookOnce(bookId).associateBy { it.id }
        val existingMappings = audiobookDao.getMappingsForBookOnce(bookId)

        val updatedGroups = mutableListOf<AudiobookGroupEntity>()
        val updatedTracks = mutableListOf<AudiobookTrackEntity>()
        val keptGroupIds = mutableSetOf<String>()
        val claimedStandaloneWrapperIds = mutableSetOf<String>()
        val mappingReassignments = mutableListOf<Triple<String, String, String>>() // (mappingId, newGroupId, newTrackId)

        val trackMappingsByTrackId = existingMappings
            .filter { it.trackId.isNotBlank() }
            .associateBy { it.trackId }
        val groupMappingsByGroupId = existingMappings
            .filter { it.trackId.isBlank() }
            .associateBy { it.groupId }
        val trackCountByOldGroup = existingTracks.values.groupingBy { it.groupId }.eachCount()

        var groupOrder = 0
        var trackOrder = 0

        for (entry in entries) {
            when (entry) {
                is ReorderedAudioEntry.Category -> {
                    val existing = existingGroups[entry.groupId] ?: continue
                    val updatedCat = existing.copy(
                        orderIndex = groupOrder++,
                        isCategory = true
                    )
                    updatedGroups.add(updatedCat)
                    keptGroupIds.add(updatedCat.id)

                    for (tid in entry.trackIds) {
                        val tr = existingTracks[tid] ?: continue
                        val oldGroupId = tr.groupId
                        updatedTracks.add(
                            tr.copy(
                                groupId = updatedCat.id,
                                orderIndex = trackOrder++
                            )
                        )
                        val existingTrackMap = trackMappingsByTrackId[tid]
                        if (existingTrackMap != null && existingTrackMap.groupId != updatedCat.id) {
                            mappingReassignments.add(Triple(existingTrackMap.id, updatedCat.id, tid))
                        } else if (existingTrackMap == null && oldGroupId != updatedCat.id) {
                            // Preserve a standalone chapter's legacy group mapping when dragged into a category
                            val oldGroup = existingGroups[oldGroupId]
                            val legacyMap = groupMappingsByGroupId[oldGroupId]
                            if (oldGroup != null && !oldGroup.isCategory && (trackCountByOldGroup[oldGroupId] ?: 0) == 1 && legacyMap != null) {
                                mappingReassignments.add(Triple(legacyMap.id, updatedCat.id, tid))
                            }
                        }
                    }
                }

                is ReorderedAudioEntry.StandaloneTrack -> {
                    val tr = existingTracks[entry.trackId] ?: continue
                    val oldGroup = existingGroups[tr.groupId]
                    val canReuseOldWrapper = oldGroup != null &&
                        !oldGroup.isCategory &&
                        oldGroup.id !in claimedStandaloneWrapperIds &&
                        oldGroup.id !in keptGroupIds

                    val wrapperGroup = if (canReuseOldWrapper) {
                        claimedStandaloneWrapperIds.add(oldGroup.id)
                        oldGroup.copy(
                            title = tr.title,
                            orderIndex = groupOrder++,
                            isCategory = false,
                            isCollapsed = false
                        )
                    } else {
                        AudiobookGroupEntity(
                            id = UUID.randomUUID().toString(),
                            bookId = bookId,
                            title = tr.title,
                            orderIndex = groupOrder++,
                            isCollapsed = false,
                            isCategory = false
                        )
                    }

                    updatedGroups.add(wrapperGroup)
                    keptGroupIds.add(wrapperGroup.id)
                    updatedTracks.add(
                        tr.copy(
                            groupId = wrapperGroup.id,
                            orderIndex = trackOrder++
                        )
                    )

                    val existingTrackMap = trackMappingsByTrackId[tr.id]
                    if (existingTrackMap != null && existingTrackMap.groupId != wrapperGroup.id) {
                        mappingReassignments.add(Triple(existingTrackMap.id, wrapperGroup.id, tr.id))
                    }
                }
            }
        }

        // 1. Upsert all kept & newly created groups first (satisfies FKs)
        if (updatedGroups.isNotEmpty()) {
            audiobookDao.upsertGroups(updatedGroups)
        }
        // 2. Upsert all tracks with new groupIds and sequential orderIndices
        if (updatedTracks.isNotEmpty()) {
            audiobookDao.upsertTracks(updatedTracks)
        }
        // 3. Reassign moved mappings BEFORE removing dissolved wrapper groups (prevents CASCADE delete)
        for ((mappingId, newGroupId, newTrackId) in mappingReassignments) {
            audiobookDao.reassignMappingOwner(mappingId, newGroupId, newTrackId)
        }
        // 4. Delete dissolved standalone wrapper groups
        for (oldId in existingGroups.keys) {
            if (oldId !in keptGroupIds) {
                audiobookDao.deleteGroupById(oldId)
            }
        }
    }

    suspend fun moveGroupOrder(bookId: String, groupId: String, moveUp: Boolean) = withContext(Dispatchers.IO) {
        val groups = audiobookDao.getGroupsForBookOnce(bookId).toMutableList()
        val idx = groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return@withContext
        val target = if (moveUp) idx - 1 else idx + 1
        if (target !in groups.indices) return@withContext
        val item = groups.removeAt(idx)
        groups.add(target, item)
        val updatedGroups = groups.mapIndexed { i, g -> g.copy(orderIndex = i) }
        audiobookDao.upsertGroups(updatedGroups)

        // Keep global track order aligned with group order
        val allTracks = audiobookDao.getTracksForBookOnce(bookId)
        val tracksByGroup = allTracks.groupBy { it.groupId }
        var trackOrder = 0
        val reorderedTracks = mutableListOf<AudiobookTrackEntity>()
        for (g in updatedGroups) {
            tracksByGroup[g.id].orEmpty().forEach { t ->
                reorderedTracks.add(t.copy(orderIndex = trackOrder++))
            }
        }
        if (reorderedTracks.isNotEmpty()) {
            audiobookDao.upsertTracks(reorderedTracks)
        }
    }

    /**
     * Maps either a Category ([trackId] == `""`) or an individual audio track ([trackId] non-empty)
     * to a book chapter range or manual PDF page / EPUB char range.
     * Clears stale word alignment cache if the mapped book range changed.
     */
    suspend fun saveChapterMapping(
        bookId: String,
        groupId: String,
        trackId: String = "",
        chapterTitle: String,
        startPageIndex: Int,
        endPageIndex: Int,
        startCharOffset: Int,
        endCharOffset: Int
    ): AudiobookChapterMapping = withContext(Dispatchers.IO) {
        val cleanTrackId = trackId.trim()
        val existing = if (cleanTrackId.isNotBlank()) {
            audiobookDao.getMappingByTrackId(cleanTrackId)
        } else {
            audiobookDao.getGroupMappingByGroupId(groupId)
        }

        // If saving a track-level mapping for a standalone wrapper that previously had a legacy group-level mapping,
        // migrate/replace that legacy group-level row so no duplicate mapping remains on the wrapper group.
        val legacyWrapperMapping = if (cleanTrackId.isNotBlank()) {
            val parent = audiobookDao.getGroupsForBookOnce(bookId).firstOrNull { it.id == groupId }
            if (parent != null && !parent.isCategory) {
                audiobookDao.getGroupMappingByGroupId(groupId)
            } else {
                null
            }
        } else {
            null
        }
        val effectiveExisting = existing ?: legacyWrapperMapping

        val rangeChanged = effectiveExisting != null && (
            effectiveExisting.bookStartPageIndex != startPageIndex ||
                effectiveExisting.bookEndPageIndex != endPageIndex ||
                effectiveExisting.bookStartCharOffset != startCharOffset ||
                effectiveExisting.bookEndCharOffset != endCharOffset
            )

        val mapping = AudiobookChapterMapping(
            id = effectiveExisting?.id ?: UUID.randomUUID().toString(),
            bookId = bookId,
            groupId = groupId,
            trackId = cleanTrackId,
            bookChapterTitle = chapterTitle,
            bookStartPageIndex = startPageIndex.coerceAtLeast(0),
            bookEndPageIndex = endPageIndex.coerceAtLeast(startPageIndex.coerceAtLeast(0)),
            bookStartCharOffset = startCharOffset.coerceAtLeast(0),
            bookEndCharOffset = endCharOffset.coerceAtLeast(startCharOffset.coerceAtLeast(0)),
            anchors = if (rangeChanged) emptyList() else effectiveExisting?.toDomainModel()?.anchors.orEmpty()
        )
        audiobookDao.upsertMapping(AudiobookMappingEntity.fromDomainModel(mapping))
        if (rangeChanged) {
            audiobookDao.deleteAlignmentForMapping(mapping.id)
        }
        mapping
    }

    suspend fun removeMapping(mappingId: String) = withContext(Dispatchers.IO) {
        audiobookDao.deleteMappingById(mappingId)
    }

    /**
     * Adds or updates a 1-tap "Sync Here" calibration anchor inside [mappingId],
     * eliminating proportional sync drift without running AI.
     */
    suspend fun addSyncAnchor(mappingId: String, anchor: SyncAnchor) = withContext(Dispatchers.IO) {
        val existing = audiobookDao.getMappingById(mappingId)?.toDomainModel() ?: return@withContext
        // Replace any anchor within 3 seconds of the new one to keep anchors clean and monotonic
        val filtered = existing.anchors
            .filterNot { kotlin.math.abs(it.audioMs - anchor.audioMs) < 3_000L }
            .plus(anchor)
            .sortedBy { it.audioMs }
        audiobookDao.updateMappingAnchors(mappingId, SyncAnchor.serializeList(filtered))
    }

    suspend fun clearSyncAnchors(mappingId: String) = withContext(Dispatchers.IO) {
        audiobookDao.updateMappingAnchors(mappingId, "[]")
    }

    /**
     * 1-tap Zero-AI Auto-Match: pairs imported audio tracks against [chapters] by chapter numbers
     * and normalized title similarity. Returns count of chapters matched.
     */
    suspend fun autoMatchGroupsToChapters(
        bookId: String,
        chapters: List<SyncChapter>
    ): Int = withContext(Dispatchers.IO) {
        val groups = audiobookDao.getGroupsForBookOnce(bookId).map { it.toDomainModel() }
        val allTracks = audiobookDao.getTracksForBookOnce(bookId).map { it.toDomainModel() }
        if (groups.isEmpty() || chapters.isEmpty()) return@withContext 0

        val groupRank = groups.mapIndexed { idx, g -> g.id to idx }.toMap()
        val orderedTracks = allTracks.sortedWith(
            compareBy<AudiobookTrack> { groupRank[it.groupId] ?: Int.MAX_VALUE }
                .thenBy { it.orderIndex }
        )
        val usedChapterIds = mutableSetOf<String>()
        var matched = 0

        for ((tIdx, track) in orderedTracks.withIndex()) {
            val trackNum = extractLeadingChapterNumber(track.title)
            val normTrack = normalizeMatchKey(track.title)

            // 1. Exact/contains title match
            var best: SyncChapter? = chapters.firstOrNull { ch ->
                ch.id !in usedChapterIds &&
                    normTrack.length >= 3 &&
                    (normalizeMatchKey(ch.title) == normTrack ||
                        normalizeMatchKey(ch.title).contains(normTrack) ||
                        normTrack.contains(normalizeMatchKey(ch.title)))
            }

            // 2. Matching chapter number (e.g., "Ch 03" <-> "Chapter 3")
            if (best == null && trackNum != null) {
                best = chapters.firstOrNull { ch ->
                    ch.id !in usedChapterIds && extractLeadingChapterNumber(ch.title) == trackNum
                }
            }

            // 3. Positional fallback if track index maps cleanly into chapter list
            if (best == null && tIdx in chapters.indices && chapters[tIdx].id !in usedChapterIds) {
                best = chapters[tIdx]
            }

            if (best != null) {
                usedChapterIds.add(best.id)
                saveChapterMapping(
                    bookId = bookId,
                    groupId = track.groupId,
                    trackId = track.id,
                    chapterTitle = best.title,
                    startPageIndex = best.startPageIndex,
                    endPageIndex = best.endPageIndex,
                    startCharOffset = best.startCharOffset,
                    endCharOffset = best.endCharOffset
                )
                matched++
            }
        }
        matched
    }

    suspend fun loadPackedTimings(mappingId: String): PackedWordTimings = withContext(Dispatchers.IO) {
        val entity = audiobookDao.getAlignmentForMapping(mappingId) ?: return@withContext PackedWordTimings.EMPTY
        PackedWordTimings.fromGzipBytes(entity.packedTimingsBlob)
    }

    suspend fun loadPlaybackState(bookId: String): AudiobookPlaybackState? = withContext(Dispatchers.IO) {
        audiobookDao.getPlaybackStateOnce(bookId)?.toDomainModel()
    }

    suspend fun savePlaybackState(state: AudiobookPlaybackState) = withContext(Dispatchers.IO) {
        audiobookDao.upsertPlaybackState(AudiobookPlaybackStateEntity.fromDomainModel(state))
    }

    private fun extractAudioMetadata(uri: Uri, fallbackFileName: String): Pair<String, Long> {
        val cleanFallback = fallbackFileName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Audio Track" }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf { it.isNotBlank() } ?: cleanFallback
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            title to duration
        } catch (_: Exception) {
            cleanFallback to 0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) {
                    return c.getString(col)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun copyAudioToInternal(uri: Uri, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "mp3").lowercase(Locale.ROOT)
        val outFile = File(localAudioDir, "audio_${UUID.randomUUID()}.$ext")
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return null
            outFile.absolutePath
        }.getOrNull()
    }

    private fun computeFastFingerprint(source: String, fileName: String, durationMs: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$source|$fileName|$durationMs".toByteArray(Charsets.UTF_8))
        return md.digest().take(12).joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun extractLeadingChapterNumber(title: String): Int? {
        val m = Regex("""(?:chapter|ch\.?|part|track|الفصل|الباب)?\s*0*(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(title)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun normalizeMatchKey(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        /**
         * Natural alphanumeric comparator so `"Chapter 2.mp3"` sorts before `"Chapter 10.mp3"`.
         */
        fun compareNatural(a: String, b: String): Int {
            var ia = 0
            var ib = 0
            while (ia < a.length && ib < b.length) {
                val ca = a[ia]
                val cb = b[ib]
                if (ca.isDigit() && cb.isDigit()) {
                    val startA = ia
                    while (ia < a.length && a[ia].isDigit()) ia++
                    val startB = ib
                    while (ib < b.length && b[ib].isDigit()) ib++
                    val numA = a.substring(startA, ia).toBigIntegerOrNull()
                    val numB = b.substring(startB, ib).toBigIntegerOrNull()
                    if (numA != null && numB != null && numA != numB) {
                        return numA.compareTo(numB)
                    }
                } else {
                    val diff = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (diff != 0) return diff
                    ia++
                    ib++
                }
            }
            return a.length - b.length
        }
    }
}
