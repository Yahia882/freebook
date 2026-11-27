package com.example.freebook

import com.example.freebook.audio.playback.ReorderedAudioEntry
import com.example.freebook.audio.sync.SyncChapter
import com.example.freebook.model.AudiobookChapterMapping
import com.example.freebook.model.AudiobookGroup
import com.example.freebook.model.AudiobookMappingResolver
import com.example.freebook.model.AudiobookTrack
import com.example.freebook.ui.reader.AudiobookListRow
import com.example.freebook.ui.reader.buildAudiobookListRows
import com.example.freebook.ui.reader.extractReorderedEntries
import com.example.freebook.ui.reader.formatChapterDisplayTitle
import com.example.freebook.ui.reader.formatChapterTimestamp
import com.example.freebook.ui.reader.initialRangeForMapping
import com.example.freebook.ui.reader.moveRowInReorderList
import com.example.freebook.ui.reader.resolveChapterRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookManagerLogicUnitTest {

    @Test
    fun mappingResolver_categoryMappingOverridesIndividualTrackMappings() {
        val track1 = AudiobookTrack(id = "t1", bookId = "b1", groupId = "cat1", sourceUri = "u1", title = "Part A")
        val track2 = AudiobookTrack(id = "t2", bookId = "b1", groupId = "cat1", sourceUri = "u2", title = "Part B")
        val standaloneTrack = AudiobookTrack(id = "t3", bookId = "b1", groupId = "g3", sourceUri = "u3", title = "Ch 3")

        val track1Map = AudiobookChapterMapping(
            id = "m_t1", bookId = "b1", groupId = "cat1", trackId = "t1",
            bookChapterTitle = "Chapter 1", bookStartCharOffset = 0, bookEndCharOffset = 1000
        )
        val track2Map = AudiobookChapterMapping(
            id = "m_t2", bookId = "b1", groupId = "cat1", trackId = "t2",
            bookChapterTitle = "Chapter 2", bookStartCharOffset = 1000, bookEndCharOffset = 2000
        )
        val standaloneMap = AudiobookChapterMapping(
            id = "m_t3", bookId = "b1", groupId = "g3", trackId = "t3",
            bookChapterTitle = "Chapter 3", bookStartCharOffset = 2000, bookEndCharOffset = 3000
        )

        // Without a category mapping, each track resolves its own individual mapping
        val withoutCatOverride = listOf(track1Map, track2Map, standaloneMap)
        assertEquals("m_t1", AudiobookMappingResolver.resolveForTrack(track1, withoutCatOverride)?.id)
        assertEquals("m_t2", AudiobookMappingResolver.resolveForTrack(track2, withoutCatOverride)?.id)
        assertEquals(3, AudiobookMappingResolver.activeMappings(withoutCatOverride).size)

        // Once the category itself is mapped, it overrides every track inside that category
        val catOverride = AudiobookChapterMapping(
            id = "m_cat1", bookId = "b1", groupId = "cat1", trackId = "",
            bookChapterTitle = "Chapters 1 – 2", bookStartCharOffset = 0, bookEndCharOffset = 2000
        )
        val withCatOverride = listOf(track1Map, track2Map, standaloneMap, catOverride)
        assertEquals("m_cat1", AudiobookMappingResolver.resolveForTrack(track1, withCatOverride)?.id)
        assertEquals("m_cat1", AudiobookMappingResolver.resolveForTrack(track2, withCatOverride)?.id)
        assertEquals("m_t3", AudiobookMappingResolver.resolveForTrack(standaloneTrack, withCatOverride)?.id)

        // activeMappings excludes overridden track mappings so bidirectional book->audio seek uses the category mapping
        val activeIds = AudiobookMappingResolver.activeMappings(withCatOverride).map { it.id }.toSet()
        assertEquals(setOf("m_cat1", "m_t3"), activeIds)
    }

    @Test
    fun dragReorder_movesStandaloneTrackIntoCategoryAndOutAgain() {
        val g1 = AudiobookGroup(id = "g1", bookId = "b1", title = "Intro", orderIndex = 0, isCategory = false)
        val cat = AudiobookGroup(id = "cat1", bookId = "b1", title = "Part II", orderIndex = 1, isCategory = true)
        val g3 = AudiobookGroup(id = "g3", bookId = "b1", title = "Outro", orderIndex = 2, isCategory = false)

        val t1 = AudiobookTrack(id = "t1", bookId = "b1", groupId = "g1", sourceUri = "u1", title = "Intro", durationMs = 60_000L, orderIndex = 0)
        val t3 = AudiobookTrack(id = "t3", bookId = "b1", groupId = "g3", sourceUri = "u3", title = "Outro", durationMs = 120_000L, orderIndex = 1)

        // Initial reorder rows: [Standalone(t1), CategoryHeader(cat1), EmptyPlaceholder(cat1), EndMarker(cat1), Standalone(t3)]
        val rows0 = buildAudiobookListRows(listOf(g1, cat, g3), listOf(t1, t3), isReorderMode = true)
        assertEquals(5, rows0.size)
        assertTrue(rows0[0] is AudiobookListRow.StandaloneTrack)
        assertTrue(rows0[1] is AudiobookListRow.CategoryHeader)
        assertTrue(rows0[2] is AudiobookListRow.EmptyCategoryPlaceholder)
        assertTrue(rows0[3] is AudiobookListRow.CategoryEndMarker)
        assertTrue(rows0[4] is AudiobookListRow.StandaloneTrack)

        // Drag t1 (index 0) down past CategoryHeader(cat1) (index 1) -> nests inside cat1!
        val (rows1, idx1) = moveRowInReorderList(rows0, fromIndex = 0, targetIndex = 1)
        assertEquals(1, idx1)
        assertTrue(rows1[1] is AudiobookListRow.CategoryTrack)
        assertEquals("cat1", (rows1[1] as AudiobookListRow.CategoryTrack).group.id)
        val entriesNested = extractReorderedEntries(rows1)
        assertEquals(
            listOf(
                ReorderedAudioEntry.Category("cat1", listOf("t1")),
                ReorderedAudioEntry.StandaloneTrack("t3")
            ),
            entriesNested
        )

        // Now drag t1 (index 1) down past EndMarker(cat1) (index 2) -> pulls OUT of cat1 to standalone!
        val (rows2, idx2) = moveRowInReorderList(rows1, fromIndex = 1, targetIndex = 2)
        assertTrue(rows2[idx2] is AudiobookListRow.StandaloneTrack)
        assertEquals("t1", (rows2[idx2] as AudiobookListRow.StandaloneTrack).track.id)
        val entriesOut = extractReorderedEntries(rows2)
        assertEquals(
            listOf(
                ReorderedAudioEntry.Category("cat1", emptyList()),
                ReorderedAudioEntry.StandaloneTrack("t1"),
                ReorderedAudioEntry.StandaloneTrack("t3")
            ),
            entriesOut
        )
    }

    @Test
    fun dragReorder_movesEntireCategoryBlockAsUnit() {
        val cat = AudiobookGroup(id = "cat1", bookId = "b1", title = "Part I", orderIndex = 0, isCategory = true)
        val g2 = AudiobookGroup(id = "g2", bookId = "b1", title = "Epilogue", orderIndex = 1, isCategory = false)
        val t1 = AudiobookTrack(id = "t1", bookId = "b1", groupId = "cat1", sourceUri = "u1", title = "Ch 1", durationMs = 30_000L, orderIndex = 0)
        val t2 = AudiobookTrack(id = "t2", bookId = "b1", groupId = "cat1", sourceUri = "u2", title = "Ch 2", durationMs = 30_000L, orderIndex = 1)
        val t3 = AudiobookTrack(id = "t3", bookId = "b1", groupId = "g2", sourceUri = "u3", title = "Epilogue", durationMs = 30_000L, orderIndex = 2)

        val rows0 = buildAudiobookListRows(listOf(cat, g2), listOf(t1, t2, t3), isReorderMode = true)
        val (rows1, newCatIdx) = moveRowInReorderList(rows0, fromIndex = 0, targetIndex = 1)
        assertEquals(1, newCatIdx)
        val entries = extractReorderedEntries(rows1)
        assertEquals(
            listOf(
                ReorderedAudioEntry.StandaloneTrack("t3"),
                ReorderedAudioEntry.Category("cat1", listOf("t1", "t2"))
            ),
            entries
        )
    }

    @Test
    fun rangeMapper_spansMultipleTocChaptersAccurately() {
        val chapters = listOf(
            SyncChapter("c1", "Chapter 1", 0, 0, 10, 0, 5000),
            SyncChapter("c2", "Chapter 2", 0, 11, 22, 5000, 12000),
            SyncChapter("c3", "Chapter 3", 0, 23, 35, 12000, 19000),
            SyncChapter("c4", "Chapter 4", 0, 36, 50, 19000, 28000)
        )

        val resolved = resolveChapterRange(chapters, startIndex = 0, endIndex = 3)
        assertEquals("Chapter 1 – Chapter 4", resolved.title)
        assertEquals(0, resolved.startPageIndex)
        assertEquals(50, resolved.endPageIndex)
        assertEquals(0, resolved.startCharOffset)
        assertEquals(28000, resolved.endCharOffset)
        assertEquals(4, resolved.sectionCount)

        // Round-trip existing multi-chapter mapping back into initial range handle indices
        val saved = AudiobookChapterMapping(
            id = "m1",
            bookId = "b1",
            groupId = "g1",
            trackId = "t1",
            bookChapterTitle = resolved.title,
            bookStartPageIndex = resolved.startPageIndex,
            bookEndPageIndex = resolved.endPageIndex,
            bookStartCharOffset = resolved.startCharOffset,
            bookEndCharOffset = resolved.endCharOffset
        )
        assertEquals(0 to 3, initialRangeForMapping(chapters, saved, isPdfBook = false, currentBookPageIndex = 0, currentBookCharOffset = 0))
        assertEquals(0 to 3, initialRangeForMapping(chapters, saved, isPdfBook = true, currentBookPageIndex = 0, currentBookCharOffset = 0))
    }

    @Test
    fun formattingHelpers_matchReferenceStyleAndAvoidDuplicateNumbers() {
        assertEquals("00:00", formatChapterTimestamp(0L))
        assertEquals("42:38", formatChapterTimestamp((42 * 60 + 38) * 1000L))
        assertEquals("01:14:35", formatChapterTimestamp((74 * 60 + 35) * 1000L))

        assertEquals("1 - Chapters 1 - 4", formatChapterDisplayTitle(1, "Chapters 1 - 4"))
        assertEquals("1 - Chapters 1 - 4", formatChapterDisplayTitle(1, "1 - Chapters 1 - 4"))
    }
}
