package com.example.freebook

import com.example.freebook.data.storage.ContentIdentityHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {

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
