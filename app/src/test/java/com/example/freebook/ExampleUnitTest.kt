package com.example.freebook

import com.example.freebook.data.storage.ContentIdentityHasher
import com.example.freebook.ui.pagination.PageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun pageLayout_mapsCharOffsetToCorrectPage_forForwardBackwardAndFirstPage() {
        val text = "a".repeat(100)
        val layout = PageLayout(text, intArrayOf(0, 30, 60), intArrayOf(30, 60, 100))

        assertEquals(0, layout.pageIndexForOffset(0))
        assertEquals(0, layout.pageIndexForOffset(29))
        assertEquals(1, layout.pageIndexForOffset(30))
        assertEquals(2, layout.pageIndexForOffset(99))
        // Different layout (e.g. bigger text => more pages) restores the same reading position
        val bigger = PageLayout(text, intArrayOf(0, 20, 40, 60, 80), intArrayOf(20, 40, 60, 80, 100))
        assertEquals(bigger.pageIndexForOffset(layout.startOffsetOf(1)), 1)
        assertEquals(0, bigger.pageIndexForOffset(layout.startOffsetOf(0)))
    }

    @Test
    fun contentIdentityHasher_producesIdenticalHashForEquivalentNormalizedText() {
        val rawA = "  Hello,   World!\n\nThis is a Book. "
        val rawB = "hello world! this is a book"

        val normA = ContentIdentityHasher.normalizeForHash(rawA)
        val normB = ContentIdentityHasher.normalizeForHash(rawB)

        assertEquals(normA, normB)
        assertEquals(
            ContentIdentityHasher.sha256Hex(normA),
            ContentIdentityHasher.sha256Hex(normB)
        )
    }

    @Test
    fun contentIdentityHasher_detectsFuzzyDuplicateAndAllowsDistinctBooks() {
        val fuzzyReason = ContentIdentityHasher.detectFuzzyDuplicateReason(
            newNormTitle = ContentIdentityHasher.normalizeMetadataField("Meditations (Translated)"),
            newNormAuthor = ContentIdentityHasher.normalizeMetadataField("Marcus Aurelius"),
            newCharLength = 42000,
            existingNormTitle = ContentIdentityHasher.normalizeMetadataField("Meditations"),
            existingNormAuthor = ContentIdentityHasher.normalizeMetadataField("Marcus Aurelius"),
            existingCharLength = 41500
        )
        assertNotNull(fuzzyReason)

        val distinctReason = ContentIdentityHasher.detectFuzzyDuplicateReason(
            newNormTitle = ContentIdentityHasher.normalizeMetadataField("The Republic"),
            newNormAuthor = ContentIdentityHasher.normalizeMetadataField("Plato"),
            newCharLength = 80000,
            existingNormTitle = ContentIdentityHasher.normalizeMetadataField("Meditations"),
            existingNormAuthor = ContentIdentityHasher.normalizeMetadataField("Marcus Aurelius"),
            existingCharLength = 41500
        )
        assertNull(distinctReason)
    }
}
