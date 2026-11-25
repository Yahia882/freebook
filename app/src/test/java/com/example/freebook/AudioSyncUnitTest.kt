package com.example.freebook

import com.example.freebook.audio.playback.AudiobookRepository
import com.example.freebook.audio.sync.BookWordTokenizer
import com.example.freebook.audio.sync.ChapterWeightDistribution
import com.example.freebook.audio.sync.EpubSyncableBook
import com.example.freebook.model.Book
import com.example.freebook.model.PackedWordTimings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSyncUnitTest {

    @Test
    fun bookWordTokenizer_preservesExactOriginalOffsetsAcrossArabicDiacriticsAndTatweel() {
        val raw = "مَرْحَـبًا بالعالَم"
        val words = BookWordTokenizer.tokenizeSegment(
            rawText = raw,
            pageIndex = 2,
            rawBaseOffset = 100,
            startWordIndex = 0,
            isFromPdf = false
        )

        assertEquals(2, words.size)
        assertEquals(2, words[0].pageIndex)
        assertEquals(100, words[0].startCharOffset)
        assertTrue(words[0].endCharOffset > words[0].startCharOffset)
        assertEquals("مرحبا", words[0].normalizedToken)
        assertEquals("بالعالم", words[1].normalizedToken)
    }

    @Test
    fun bookWordTokenizer_normalizesArabicPdfPresentationFormsAndMapsBackToOriginalIndices() {
        // Presentation forms for "سلام" (FE9B=س, FEDF=ل, FE8E=ا, FEE1=م)
        val rawPdf = "\uFEB3\uFEDF\uFE8E\uFEE1"
        val norm = BookWordTokenizer.normalizeForSpeech(rawPdf, rawBaseOffset = 10, isFromPdf = true)

        assertEquals("سلام", norm.text)
        val mapped = norm.mapSpanToOriginal(0, norm.text.length)
        assertNotNull(mapped)
        assertEquals(10, mapped!!.first)
        assertEquals(14, mapped.second)
    }

    @Test
    fun bookWordTokenizer_splitsLongTextIntoSentenceChunksWithValidBackMappings() {
        val raw = "First sentence ends here. Second sentence follows cleanly! And a third one?"
        val chunks = BookWordTokenizer.splitIntoTtsChunks(
            rawText = raw,
            pageIndex = 0,
            rawBaseOffset = 0,
            isFromPdf = false,
            maxChunkChars = 42
        )

        assertTrue(chunks.size >= 2)
        val firstChunk = chunks.first()
        val mapped = firstChunk.mapRangeToOriginal(0, 5)
        assertNotNull(mapped)
        assertEquals("First", raw.substring(mapped!!.first, mapped.second))
    }

    @Test
    fun packedWordTimings_gzipRoundTripAndBinarySearchLookup() {
        val original = PackedWordTimings(
            wordIndices = intArrayOf(0, 1, 2),
            startMs = intArrayOf(1000, 1500, 3000),
            endMs = intArrayOf(1400, 1900, 3500),
            pageIndices = intArrayOf(0, 0, 1),
            startChars = intArrayOf(0, 6, 0),
            endChars = intArrayOf(5, 12, 4)
        )

        val bytes = original.toGzipBytes()
        val restored = PackedWordTimings.fromGzipBytes(bytes)

        assertEquals(3, restored.size)
        assertEquals(-1, restored.findIndexAtAudioMs(500L))
        assertEquals(0, restored.findIndexAtAudioMs(1200L))
        assertEquals(1, restored.findIndexAtAudioMs(1600L))
        // Unmatched silent gap (>450ms past word 1 end at 1900ms, before word 2 start at 3000ms)
        assertEquals(-1, restored.findIndexAtAudioMs(2600L))
        assertEquals(2, restored.findIndexAtAudioMs(3200L))

        // Reverse lookup (Text -> Audio)
        assertEquals(3000L, restored.findAudioMsForBookPosition(pageIndex = 1, charOffset = 2))
    }

    @Test
    fun chapterWeightDistribution_resolvesWeightedPdfPagesMonotonically() {
        val dist = ChapterWeightDistribution(
            isPdf = true,
            isScannedFallback = false,
            startPageIndex = 10,
            endPageIndex = 12,
            startCharOffset = 0,
            endCharOffset = 0,
            cumulativeWeights = intArrayOf(300, 650, 1000),
            segmentPositions = intArrayOf(10, 11, 12)
        )

        assertEquals(10 to 0, dist.resolveWeightToPosition(150))
        assertEquals(11 to 0, dist.resolveWeightToPosition(500))
        assertEquals(12 to 0, dist.resolveWeightToPosition(900))
    }

    @Test
    fun audiobookRepository_naturalSortOrdersChaptersNumerically() {
        val unsorted = listOf("Chapter 10.mp3", "Chapter 2.mp3", "Chapter 1.mp3", "Chapter 11.mp3")
        val sorted = unsorted.sortedWith { a, b -> AudiobookRepository.compareNatural(a, b) }
        assertEquals(
            listOf("Chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3", "Chapter 11.mp3"),
            sorted
        )
    }

    @Test
    fun ttsSpeechChunk_findSpokenWordStartMapsMidWordTapToWordBeginning() {
        val raw = "The quick brown fox jumps over the lazy dog."
        val chunks = BookWordTokenizer.splitIntoTtsChunks(
            rawText = raw,
            pageIndex = 0,
            rawBaseOffset = 0,
            isFromPdf = false
        )
        assertEquals(1, chunks.size)
        val chunk = chunks.first()
        // Tap on 'o' inside "brown" (index 12 -> word "brown" starts at index 10)
        val wordStart = chunk.findSpokenWordStartForOriginalOffset(12)
        assertEquals(10, wordStart)
        assertTrue(chunk.spokenText.substring(wordStart).startsWith("brown"))
    }

    @Test
    fun epubSyncableBook_loadTtsChunksAlwaysCoversFullBookRegardlessOfStartOffset() = runBlocking {
        val fullText = buildString {
            append("Chapter 1 opens the story with an introduction.\n\n")
            append("Chapter 2 develops the characters and setting.\n\n")
            append("Chapter 3 reaches the turning point.\n\n")
            append("Chapter 4 builds the climax.\n\n")
            append("Chapter 5 concludes the entire book cleanly.")
        }
        val book = Book(
            id = "test_epub_1",
            title = "Five Chapter Test",
            author = "Author",
            bookFilePath = "",
            textContentFilePath = ""
        )
        val syncable = EpubSyncableBook(
            book = book,
            canonicalBookText = fullText,
            tocEntries = emptyList(),
            onSeekAndHighlight = {}
        )

        val chapter5Offset = fullText.indexOf("Chapter 5")
        assertTrue(chapter5Offset > 100)

        // Even when starting in Chapter 5, returned chunks MUST still start at Chapter 1 (offset 0)
        // so scrubber, skip-back, and word jumps can freely access all 4 preceding chapters!
        val chunks = syncable.loadTtsChunksForUnit(unitIndex = 0, startCharOffset = chapter5Offset)
        assertTrue(chunks.size >= 5)
        assertEquals(0, chunks.first().docStartChar)
        assertTrue(chunks.first().spokenText.startsWith("Chapter 1"))
        assertTrue(chunks.last().spokenText.contains("Chapter 5"))
    }
}
