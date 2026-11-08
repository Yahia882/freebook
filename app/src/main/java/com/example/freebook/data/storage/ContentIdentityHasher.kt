package com.example.freebook.data.storage

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Enforces "Identity = content, not location":
 * - Normalizes text so minor formatting/whitespace differences produce the same canonical text.
 * - Computes SHA-256 hex digest of normalized content for exact-duplicate blocking.
 * - Computes fuzzy similarity across normalized title, author, and character length to warn & confirm on near-duplicates.
 */
object ContentIdentityHasher {

    private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun normalizeForHash(rawText: String): String {
        val nfc = Normalizer.normalize(rawText, Normalizer.Form.NFC)
        return nfc
            .lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    fun normalizeMetadataField(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        return nfc
            .lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    fun sha256Hex(normalizedText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(normalizedText.toByteArray(Charsets.UTF_8))
        return buildString(hashBytes.size * 2) {
            for (b in hashBytes) {
                append(String.format(Locale.US, "%02x", b))
            }
        }
    }

    /**
     * Evaluates whether a newly staged book is a fuzzy duplicate of an existing book
     * (when their exact content hashes differ).
     * Returns a human-readable reason if it is a fuzzy match, or null if distinct.
     */
    fun detectFuzzyDuplicateReason(
        newNormTitle: String,
        newNormAuthor: String,
        newCharLength: Int,
        existingNormTitle: String,
        existingNormAuthor: String,
        existingCharLength: Int
    ): String? {
        if (newNormTitle.isBlank() || existingNormTitle.isBlank()) return null

        val titleSimilarity = stringSimilarity(newNormTitle, existingNormTitle)
        val authorSimilarity = if (newNormAuthor.isNotBlank() && existingNormAuthor.isNotBlank()) {
            stringSimilarity(newNormAuthor, existingNormAuthor)
        } else {
            0f
        }

        val lengthRatio = if (newCharLength > 0 && existingCharLength > 0) {
            min(newCharLength, existingCharLength).toFloat() / max(newCharLength, existingCharLength).toFloat()
        } else {
            0f
        }

        return when {
            newNormTitle == existingNormTitle && newNormAuthor == existingNormAuthor ->
                "A book with the same title and author already exists in your library, though its text edition/formatting differs."

            newNormTitle == existingNormTitle ->
                "A book with the exact same title already exists in your library (possibly a different translation or edition)."

            titleSimilarity >= 0.78f && (authorSimilarity >= 0.70f || lengthRatio >= 0.80f) ->
                "A very similar book (${(titleSimilarity * 100).toInt()}% title match) is already in your library."

            titleSimilarity >= 0.86f ->
                "A book with a nearly identical title (${(titleSimilarity * 100).toInt()}% match) is already in your library."

            else -> null
        }
    }

    fun stringSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f

        val tokensA = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(' ').filter { it.isNotBlank() }.toSet()
        val jaccard = if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            val intersection = tokensA.intersect(tokensB).size.toFloat()
            val union = tokensA.union(tokensB).size.toFloat()
            intersection / union
        } else {
            0f
        }

        // Overlap coefficient for edition/subtitle variations (e.g., "Meditations (Translated)" vs "Meditations")
        val containment = if (tokensA.isNotEmpty() && tokensB.isNotEmpty() && min(a.length, b.length) >= 4) {
            val intersection = tokensA.intersect(tokensB).size.toFloat()
            val minTokens = min(tokensA.size, tokensB.size).toFloat()
            (intersection / minTokens) * 0.85f
        } else {
            0f
        }

        val editSim = levenshteinSimilarity(a.take(120), b.take(120))
        return max(max(jaccard, containment), editSim)
    }

    private fun levenshteinSimilarity(s1: String, s2: String): Float {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1f
        val prev = IntArray(s2.length + 1) { it }
        val curr = IntArray(s2.length + 1)
        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, prev.size)
        }
        val dist = prev[s2.length]
        return (1f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }
}
