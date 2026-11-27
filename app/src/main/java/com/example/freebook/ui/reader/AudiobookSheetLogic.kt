package com.example.freebook.ui.reader

import com.example.freebook.audio.playback.ReorderedAudioEntry
import com.example.freebook.audio.sync.SyncChapter
import com.example.freebook.model.AudiobookChapterMapping
import com.example.freebook.model.AudiobookGroup
import com.example.freebook.model.AudiobookTrack
import java.util.Locale

internal sealed interface AudiobookListRow {
    val stableKey: String

    data class StandaloneTrack(
        val group: AudiobookGroup,
        val track: AudiobookTrack,
        val startOffsetMs: Long,
        val chapterNumber: Int
    ) : AudiobookListRow {
        override val stableKey: String get() = "track_${track.id}"
    }

    data class CategoryHeader(
        val group: AudiobookGroup,
        val trackCount: Int,
        val totalDurationMs: Long,
        val startOffsetMs: Long
    ) : AudiobookListRow {
        override val stableKey: String get() = "cat_${group.id}"
    }

    data class CategoryTrack(
        val group: AudiobookGroup,
        val track: AudiobookTrack,
        val startOffsetMs: Long,
        val chapterNumber: Int
    ) : AudiobookListRow {
        override val stableKey: String get() = "track_${track.id}"
    }

    data class EmptyCategoryPlaceholder(
        val group: AudiobookGroup
    ) : AudiobookListRow {
        override val stableKey: String get() = "empty_cat_${group.id}"
    }

    /** Shown only during Reorder Mode at the bottom of an expanded category so users can drag tracks cleanly out. */
    data class CategoryEndMarker(
        val group: AudiobookGroup
    ) : AudiobookListRow {
        override val stableKey: String get() = "cat_end_${group.id}"
    }
}

/**
 * Grouped section model used in normal browsing mode so each Category can smoothly animate
 * its embedded sub-files expanding/collapsing downward beneath its anchored header.
 */
internal sealed interface AudiobookNormalSection {
    val stableKey: String

    data class StandaloneTrack(
        val group: AudiobookGroup,
        val track: AudiobookTrack
    ) : AudiobookNormalSection {
        override val stableKey: String get() = "track_${track.id}"
    }

    data class CategoryBlock(
        val group: AudiobookGroup,
        val tracks: List<AudiobookTrack>,
        val totalDurationMs: Long
    ) : AudiobookNormalSection {
        override val stableKey: String get() = "cat_${group.id}"
    }
}

internal fun buildAudiobookNormalSections(
    groups: List<AudiobookGroup>,
    tracks: List<AudiobookTrack>
): List<AudiobookNormalSection> {
    val tracksByGroup = tracks.groupBy { it.groupId }
    return groups.map { group ->
        val groupTracks = tracksByGroup[group.id].orEmpty()
        val displayAsCategory = group.isCategory || groupTracks.size != 1
        if (!displayAsCategory) {
            AudiobookNormalSection.StandaloneTrack(
                group = group,
                track = groupTracks.first()
            )
        } else {
            AudiobookNormalSection.CategoryBlock(
                group = group,
                tracks = groupTracks,
                totalDurationMs = groupTracks.sumOf { it.effectiveDurationMs }
            )
        }
    }
}

internal data class ResolvedChapterRange(
    val startIndex: Int,
    val endIndex: Int,
    val title: String,
    val startPageIndex: Int,
    val endPageIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val sectionCount: Int
)

private val LEADING_NUMBER_PREFIX_REGEX = Regex("""^\d+\s*[-.):]\s*""")

internal fun formatChapterDisplayTitle(chapterNumber: Int, rawTitle: String): String {
    val cleaned = rawTitle.trim().ifBlank { "Chapter $chapterNumber" }
    return if (LEADING_NUMBER_PREFIX_REGEX.containsMatchIn(cleaned)) {
        cleaned
    } else {
        "$chapterNumber - $cleaned"
    }
}

/**
 * Formats timestamps cleanly for compact inline display beside chapter and category titles:
 * e.g. `31:00`, `4:05`, `1:20:00`.
 */
internal fun formatCompactDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%d:%02d", mins, secs)
    }
}

/**
 * Formats timestamps matching zero-padded `MM:SS` / `HH:MM:SS` notation.
 */
internal fun formatChapterTimestamp(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

internal fun buildAudiobookListRows(
    groups: List<AudiobookGroup>,
    tracks: List<AudiobookTrack>,
    isReorderMode: Boolean
): List<AudiobookListRow> {
    val tracksByGroup = tracks.groupBy { it.groupId }
    val result = ArrayList<AudiobookListRow>(tracks.size + groups.size * 2)
    var accMs = 0L
    var chapterNum = 1

    for (group in groups) {
        val groupTracks = tracksByGroup[group.id].orEmpty()
        val displayAsCategory = group.isCategory || groupTracks.size != 1

        if (!displayAsCategory) {
            val tr = groupTracks.first()
            result.add(
                AudiobookListRow.StandaloneTrack(
                    group = group,
                    track = tr,
                    startOffsetMs = accMs,
                    chapterNumber = chapterNum++
                )
            )
            accMs += tr.effectiveDurationMs
        } else {
            val catStartMs = accMs
            val catDurMs = groupTracks.sumOf { it.effectiveDurationMs }
            result.add(
                AudiobookListRow.CategoryHeader(
                    group = group,
                    trackCount = groupTracks.size,
                    totalDurationMs = catDurMs,
                    startOffsetMs = catStartMs
                )
            )
            if (group.isCollapsed) {
                // Advance timeline and numbering cleanly past collapsed category tracks
                accMs += catDurMs
                chapterNum += groupTracks.size
            } else {
                if (groupTracks.isEmpty()) {
                    result.add(AudiobookListRow.EmptyCategoryPlaceholder(group))
                } else {
                    for (tr in groupTracks) {
                        result.add(
                            AudiobookListRow.CategoryTrack(
                                group = group,
                                track = tr,
                                startOffsetMs = accMs,
                                chapterNumber = chapterNum++
                            )
                        )
                        accMs += tr.effectiveDurationMs
                    }
                }
                if (isReorderMode) {
                    result.add(AudiobookListRow.CategoryEndMarker(group))
                }
            }
        }
    }
    return result
}

/**
 * Re-normalizes a flat reorder row list so tracks sitting between a `CategoryHeader` and its
 * `CategoryEndMarker` automatically convert to `CategoryTrack`, tracks outside convert to
 * `StandaloneTrack`, empty expanded categories keep a placeholder, and all timestamps & chapter
 * numbers recompute cleanly.
 */
internal fun normalizeReorderRows(
    rawRows: List<AudiobookListRow>,
    collapsedCategoryTracks: Map<String, List<AudiobookTrack>> = emptyMap()
): List<AudiobookListRow> {
    val normalized = ArrayList<AudiobookListRow>(rawRows.size + 4)
    var openCategory: AudiobookGroup? = null
    var openCategoryHeaderIdx = -1
    var openCategoryTrackCount = 0
    var openCategoryDurationMs = 0L
    var accMs = 0L
    var chapterNum = 1

    for (row in rawRows) {
        when (row) {
            is AudiobookListRow.EmptyCategoryPlaceholder -> {
                // Re-inserted automatically when closing an empty expanded category
            }

            is AudiobookListRow.CategoryHeader -> {
                val catStartMs = accMs
                if (row.group.isCollapsed) {
                    val hidden = collapsedCategoryTracks[row.group.id].orEmpty()
                    val dur = hidden.sumOf { it.effectiveDurationMs }
                    normalized.add(
                        row.copy(
                            trackCount = hidden.size,
                            totalDurationMs = dur,
                            startOffsetMs = catStartMs
                        )
                    )
                    accMs += dur
                    chapterNum += hidden.size
                    openCategory = null
                    openCategoryHeaderIdx = -1
                } else {
                    openCategory = row.group
                    openCategoryHeaderIdx = normalized.size
                    openCategoryTrackCount = 0
                    openCategoryDurationMs = 0L
                    normalized.add(
                        row.copy(
                            trackCount = 0,
                            totalDurationMs = 0L,
                            startOffsetMs = catStartMs
                        )
                    )
                }
            }

            is AudiobookListRow.CategoryEndMarker -> {
                val cat = openCategory
                if (cat != null && cat.id == row.group.id) {
                    if (openCategoryTrackCount == 0) {
                        normalized.add(AudiobookListRow.EmptyCategoryPlaceholder(cat))
                    }
                    if (openCategoryHeaderIdx in normalized.indices) {
                        val hdr = normalized[openCategoryHeaderIdx] as? AudiobookListRow.CategoryHeader
                        if (hdr != null) {
                            normalized[openCategoryHeaderIdx] = hdr.copy(
                                trackCount = openCategoryTrackCount,
                                totalDurationMs = openCategoryDurationMs
                            )
                        }
                    }
                    normalized.add(row)
                    openCategory = null
                    openCategoryHeaderIdx = -1
                }
            }

            is AudiobookListRow.StandaloneTrack -> {
                val cat = openCategory
                if (cat != null) {
                    normalized.add(
                        AudiobookListRow.CategoryTrack(
                            group = cat,
                            track = row.track.copy(groupId = cat.id),
                            startOffsetMs = accMs,
                            chapterNumber = chapterNum++
                        )
                    )
                    openCategoryTrackCount++
                    openCategoryDurationMs += row.track.effectiveDurationMs
                } else {
                    normalized.add(
                        row.copy(
                            startOffsetMs = accMs,
                            chapterNumber = chapterNum++
                        )
                    )
                }
                accMs += row.track.effectiveDurationMs
            }

            is AudiobookListRow.CategoryTrack -> {
                val cat = openCategory
                if (cat != null) {
                    normalized.add(
                        AudiobookListRow.CategoryTrack(
                            group = cat,
                            track = row.track.copy(groupId = cat.id),
                            startOffsetMs = accMs,
                            chapterNumber = chapterNum++
                        )
                    )
                    openCategoryTrackCount++
                    openCategoryDurationMs += row.track.effectiveDurationMs
                } else {
                    // Track was dragged out of its category -> becomes a standalone top-level chapter
                    normalized.add(
                        AudiobookListRow.StandaloneTrack(
                            group = AudiobookGroup(
                                id = row.track.groupId,
                                bookId = row.track.bookId,
                                title = row.track.title,
                                isCategory = false
                            ),
                            track = row.track,
                            startOffsetMs = accMs,
                            chapterNumber = chapterNum++
                        )
                    )
                }
                accMs += row.track.effectiveDurationMs
            }
        }
    }

    return normalized
}

/**
 * Moves a draggable row (`StandaloneTrack`, `CategoryTrack`, or `CategoryHeader`) one step or
 * to [targetIndex] within [rows], preserving category nesting rules:
 * - Tracks can move freely across standalone positions, into categories, within categories, and past `CategoryEndMarker` to leave a category.
 * - Categories move as a whole contiguous block across top-level siblings (cannot nest inside another category).
 *
 * Returns the normalized updated row list and the new index of the dragged item.
 */
internal fun moveRowInReorderList(
    rows: List<AudiobookListRow>,
    fromIndex: Int,
    targetIndex: Int,
    collapsedCategoryTracks: Map<String, List<AudiobookTrack>> = emptyMap()
): Pair<List<AudiobookListRow>, Int> {
    if (fromIndex !in rows.indices || targetIndex !in rows.indices || fromIndex == targetIndex) {
        return rows to fromIndex
    }

    val moving = rows[fromIndex]
    val draggedKey = moving.stableKey

    return when (moving) {
        is AudiobookListRow.StandaloneTrack,
        is AudiobookListRow.CategoryTrack -> {
            val mutable = rows.toMutableList()
            val removed = mutable.removeAt(fromIndex)
            mutable.add(targetIndex.coerceIn(0, mutable.size), removed)
            val normalized = normalizeReorderRows(mutable, collapsedCategoryTracks)
            val newIdx = normalized.indexOfFirst { it.stableKey == draggedKey }.takeIf { it >= 0 } ?: fromIndex
            normalized to newIdx
        }

        is AudiobookListRow.CategoryHeader -> {
            val blocks = partitionTopLevelBlocks(rows)
            val myBlockIdx = blocks.indexOfFirst { block ->
                block.firstOrNull()?.stableKey == draggedKey
            }
            if (myBlockIdx < 0) return rows to fromIndex

            val movingDown = targetIndex > fromIndex
            val swapBlockIdx = if (movingDown) myBlockIdx + 1 else myBlockIdx - 1
            if (swapBlockIdx !in blocks.indices) return rows to fromIndex

            val mutableBlocks = blocks.toMutableList()
            val removedBlock = mutableBlocks.removeAt(myBlockIdx)
            mutableBlocks.add(swapBlockIdx, removedBlock)
            val flattened = mutableBlocks.flatten()
            val normalized = normalizeReorderRows(flattened, collapsedCategoryTracks)
            val newIdx = normalized.indexOfFirst { it.stableKey == draggedKey }.takeIf { it >= 0 } ?: fromIndex
            normalized to newIdx
        }

        else -> rows to fromIndex
    }
}

private fun partitionTopLevelBlocks(rows: List<AudiobookListRow>): List<List<AudiobookListRow>> {
    val blocks = mutableListOf<List<AudiobookListRow>>()
    var i = 0
    while (i < rows.size) {
        val row = rows[i]
        if (row is AudiobookListRow.CategoryHeader && !row.group.isCollapsed) {
            val catId = row.group.id
            val block = mutableListOf<AudiobookListRow>()
            block.add(row)
            i++
            while (i < rows.size) {
                val inner = rows[i]
                block.add(inner)
                i++
                if (inner is AudiobookListRow.CategoryEndMarker && inner.group.id == catId) {
                    break
                }
            }
            blocks.add(block)
        } else {
            blocks.add(listOf(row))
            i++
        }
    }
    return blocks
}

/**
 * Converts a normalized list of [AudiobookListRow] back into structured [ReorderedAudioEntry]
 * items ready for atomic persistence in [com.example.freebook.audio.playback.AudiobookRepository.applyReorderedStructure].
 */
internal fun extractReorderedEntries(
    rows: List<AudiobookListRow>,
    collapsedCategoryTracks: Map<String, List<AudiobookTrack>> = emptyMap()
): List<ReorderedAudioEntry> {
    val entries = mutableListOf<ReorderedAudioEntry>()
    var i = 0
    while (i < rows.size) {
        when (val row = rows[i]) {
            is AudiobookListRow.StandaloneTrack -> {
                entries.add(ReorderedAudioEntry.StandaloneTrack(row.track.id))
                i++
            }

            is AudiobookListRow.CategoryHeader -> {
                val catId = row.group.id
                if (row.group.isCollapsed) {
                    val hiddenIds = collapsedCategoryTracks[catId].orEmpty().map { it.id }
                    entries.add(ReorderedAudioEntry.Category(catId, hiddenIds))
                    i++
                } else {
                    val childIds = mutableListOf<String>()
                    i++
                    while (i < rows.size) {
                        val inner = rows[i]
                        when (inner) {
                            is AudiobookListRow.CategoryTrack -> {
                                if (inner.group.id == catId) {
                                    childIds.add(inner.track.id)
                                    i++
                                } else {
                                    break
                                }
                            }
                            is AudiobookListRow.EmptyCategoryPlaceholder -> {
                                if (inner.group.id == catId) i++ else break
                            }
                            is AudiobookListRow.CategoryEndMarker -> {
                                if (inner.group.id == catId) i++
                                break
                            }
                            else -> break
                        }
                    }
                    entries.add(ReorderedAudioEntry.Category(catId, childIds))
                }
            }

            else -> i++
        }
    }
    return entries
}

internal fun initialRangeForMapping(
    chapters: List<SyncChapter>,
    existingMapping: AudiobookChapterMapping?,
    isPdfBook: Boolean,
    currentBookPageIndex: Int,
    currentBookCharOffset: Int
): Pair<Int, Int> {
    if (chapters.isEmpty()) return 0 to 0
    val lastIdx = chapters.lastIndex

    if (existingMapping != null) {
        return if (isPdfBook) {
            val s = chapters.indexOfLast { it.startPageIndex <= existingMapping.bookStartPageIndex }
                .coerceIn(0, lastIdx)
            val e = chapters.indexOfLast { it.startPageIndex <= existingMapping.bookEndPageIndex }
                .coerceIn(s, lastIdx)
            s to e
        } else {
            val s = chapters.indexOfLast { it.startCharOffset <= existingMapping.bookStartCharOffset + 12 }
                .coerceIn(0, lastIdx)
            val e = if (existingMapping.bookEndCharOffset > existingMapping.bookStartCharOffset) {
                chapters.indexOfLast { it.startCharOffset < existingMapping.bookEndCharOffset - 12 }
                    .coerceIn(s, lastIdx)
            } else {
                s
            }
            s to e
        }
    }

    val currentIdx = if (isPdfBook) {
        chapters.indexOfLast { it.startPageIndex <= currentBookPageIndex }.coerceIn(0, lastIdx)
    } else {
        chapters.indexOfLast { it.startCharOffset <= currentBookCharOffset + 12 }.coerceIn(0, lastIdx)
    }
    return currentIdx to currentIdx
}

internal fun resolveChapterRange(
    chapters: List<SyncChapter>,
    startIndex: Int,
    endIndex: Int
): ResolvedChapterRange {
    require(chapters.isNotEmpty()) { "chapters must not be empty" }
    val s = startIndex.coerceIn(0, chapters.lastIndex)
    val e = endIndex.coerceIn(s, chapters.lastIndex)
    val first = chapters[s]
    val last = chapters[e]
    val title = if (s == e) {
        first.title
    } else {
        "${first.title} – ${last.title}"
    }
    val startPage = first.startPageIndex.coerceAtLeast(0)
    val endPage = maxOf(last.endPageIndex, startPage)
    val startChar = first.startCharOffset.coerceAtLeast(0)
    val endChar = maxOf(last.endCharOffset, startChar + 1)
    return ResolvedChapterRange(
        startIndex = s,
        endIndex = e,
        title = title,
        startPageIndex = startPage,
        endPageIndex = endPage,
        startCharOffset = startChar,
        endCharOffset = endChar,
        sectionCount = e - s + 1
    )
}
