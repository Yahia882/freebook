package com.example.freebook.audio.sync

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * A single tokenized word mapped deterministically back to its exact document location.
 * For EPUB: `pageIndex == 0` and `[startCharOffset, endCharOffset)` are canonical text offsets.
 * For PDF: `pageIndex` is the 0-based page index and `[startCharOffset, endCharOffset)` are
 * page-local character indices matching `PdfPageTextLayout.charRects`.
 */
data class IndexedBookWord(
    val bookWordIndex: Int,
    val normalizedToken: String,
    val originalText: String,
    val pageIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val spokenWeight: Int
)

/**
 * Normalized text ready for TTS synthesis or CTC alignment, retaining a 1:1 character map
 * `spokenToOriginalStart[i]` / `spokenToOriginalEnd[i]` so callbacks like Android TTS
 * `onRangeStart(start, end)` map back to exact original EPUB/PDF character indices without
 * Arabic diacritic, tatweel, or PDF presentation-form drift.
 */
class NormalizedTextWithMap(
    val text: String,
    val originalStartIndices: IntArray,
    val originalEndIndices: IntArray
) {
    fun mapSpanToOriginal(startInSpoken: Int, endInSpoken: Int): Pair<Int, Int>? {
        if (text.isEmpty() || originalStartIndices.isEmpty()) return null
        val s = startInSpoken.coerceIn(0, originalStartIndices.lastIndex)
        val e = (endInSpoken - 1).coerceIn(s, originalEndIndices.lastIndex)
        var minOrig = Int.MAX_VALUE
        var maxOrig = Int.MIN_VALUE
        for (i in s..e) {
            val os = originalStartIndices[i]
            val oe = originalEndIndices[i]
            if (os >= 0 && oe > os) {
                if (os < minOrig) minOrig = os
                if (oe > maxOrig) maxOrig = oe
            }
        }
        return if (minOrig < maxOrig) minOrig to maxOrig else null
    }
}

data class TtsSpeechChunk(
    val chunkId: String,
    val pageIndex: Int,
    val spokenText: String,
    val originalStartIndices: IntArray,
    val originalEndIndices: IntArray,
    val docStartChar: Int,
    val docEndChar: Int,
    val spokenWeight: Int
) {
    fun mapRangeToOriginal(start: Int, end: Int): Pair<Int, Int>? {
        if (spokenText.isEmpty() || originalStartIndices.isEmpty()) return null
        val s = start.coerceIn(0, originalStartIndices.lastIndex)
        val e = (end - 1).coerceIn(s, originalEndIndices.lastIndex)
        var minOrig = Int.MAX_VALUE
        var maxOrig = Int.MIN_VALUE
        for (i in s..e) {
            val os = originalStartIndices[i]
            val oe = originalEndIndices[i]
            if (os >= 0 && oe > os) {
                if (os < minOrig) minOrig = os
                if (oe > maxOrig) maxOrig = oe
            }
        }
        return if (minOrig < maxOrig) minOrig to maxOrig else null
    }

    /**
     * Maps an original document character offset (e.g., from fast double-tapping a word or
     * resuming after pause) back to the word-start index inside [spokenText].
     * Works identically on LTR, RTL, and visual-order-reversed Arabic PDF lines.
     */
    fun findSpokenWordStartForOriginalOffset(origCharOffset: Int): Int {
        if (spokenText.isEmpty() || originalStartIndices.isEmpty()) return 0
        if (origCharOffset <= docStartChar) return 0

        var matchIdx = -1
        for (i in originalStartIndices.indices) {
            val os = originalStartIndices[i]
            val oe = originalEndIndices[i]
            if (origCharOffset >= os && origCharOffset < oe) {
                matchIdx = i
                break
            }
        }
        if (matchIdx < 0) {
            var bestDist = Int.MAX_VALUE
            for (i in originalStartIndices.indices) {
                val os = originalStartIndices[i]
                if (os >= origCharOffset && (os - origCharOffset) < bestDist) {
                    bestDist = os - origCharOffset
                    matchIdx = i
                }
            }
        }
        if (matchIdx <= 0) return 0

        // Snap backward to start of word so synthesis never starts mid-word
        var wordStart = matchIdx
        while (wordStart > 0 && !spokenText[wordStart - 1].isWhitespace()) {
            wordStart--
        }
        return wordStart
    }
}

/**
 * Shared deterministic tokenizer & multilingual normalizer (English, Arabic, CJK, etc.).
 * Ensures `bookWordIndex` is 100% reproducible across app restarts and across Local / Cloud aligners.
 */
object BookWordTokenizer {

    const val TOKENIZER_VERSION = 1

    private const val TATWEEL = '\u0640'

    private fun isArabicPresentationForm(ch: Char): Boolean {
        val c = ch.code
        return (c in 0xFB50..0xFDFF) || (c in 0xFE70..0xFEFF)
    }

    private fun isArabicLetterOrMark(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x0600..0x06FF) || (c in 0x0750..0x077F) ||
            (c in 0x08A0..0x08FF) || isArabicPresentationForm(ch)
    }

    private fun isCjkIdeograph(ch: Char): Boolean {
        val ub = Character.UnicodeBlock.of(ch)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            ub == Character.UnicodeBlock.HIRAGANA ||
            ub == Character.UnicodeBlock.KATAKANA ||
            ub == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private fun isWordChar(ch: Char): Boolean {
        return Character.isLetterOrDigit(ch) ||
            ch == '\'' || ch == '\u2019' || ch == '-' ||
            Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
    }

    /**
     * Normalizes raw EPUB or PDF text into clean logical spoken text while recording exact
     * index mappings back to the original character range `[rawBaseOffset + i, rawBaseOffset + i + 1)`.
     *
     * Handles Arabic PDF quirks:
     * - Normalizes Arabic Presentation Forms-A/B via NFKC so Android TTS & MMS-FA read real Arabic letters.
     * - Detects visual-order reversed Arabic runs (common in PDFium streams using presentation forms)
     *   and restores logical reading order while keeping each character's original PDF index intact!
     * - Strips Arabic tatweel (`ـ`) and collapses excessive whitespace without losing highlight accuracy.
     */
    fun normalizeForSpeech(
        rawText: String,
        rawBaseOffset: Int = 0,
        isFromPdf: Boolean = false
    ): NormalizedTextWithMap {
        if (rawText.isEmpty()) {
            return NormalizedTextWithMap("", IntArray(0), IntArray(0))
        }

        val sb = StringBuilder(rawText.length)
        val starts = ArrayList<Int>(rawText.length)
        val ends = ArrayList<Int>(rawText.length)

        var prevWasSpace = false

        fun appendMapped(ch: Char, origStart: Int, origEnd: Int) {
            if (ch == TATWEEL) return
            if (ch == '\r') return
            if (ch.isWhitespace()) {
                val spaceChar = if (ch == '\n') '\n' else ' '
                if (spaceChar == ' ' && prevWasSpace) return
                sb.append(spaceChar)
                starts.add(origStart)
                ends.add(origEnd)
                prevWasSpace = true
                return
            }
            prevWasSpace = false
            sb.append(ch)
            starts.add(origStart)
            ends.add(origEnd)
        }

        // Process line-by-line so PDF Arabic presentation-form visual reversal can be repaired cleanly
        var lineStart = 0
        while (lineStart < rawText.length) {
            var lineEnd = rawText.indexOf('\n', lineStart)
            val hasNewline = lineEnd >= 0
            if (!hasNewline) lineEnd = rawText.length

            val lineSlice = rawText.substring(lineStart, lineEnd)
            val shouldFixVisualRtl = isFromPdf && looksLikeVisualOrderArabicPdfLine(lineSlice)

            if (shouldFixVisualRtl) {
                // Reverse visual-order Arabic runs back to logical order while preserving original indices
                var idx = lineSlice.length - 1
                while (idx >= 0) {
                    val ch = lineSlice[idx]
                    val origIdx = rawBaseOffset + lineStart + idx
                    if (isArabicPresentationForm(ch)) {
                        val norm = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC)
                        // Multi-char ligatures (like LAM-ALEF) are already in logical order after NFKC
                        for (nch in norm) {
                            appendMapped(nch, origIdx, origIdx + 1)
                        }
                    } else {
                        appendMapped(ch, origIdx, origIdx + 1)
                    }
                    idx--
                }
            } else {
                for (i in lineSlice.indices) {
                    val ch = lineSlice[i]
                    val origIdx = rawBaseOffset + lineStart + i
                    if (isFromPdf && isArabicPresentationForm(ch)) {
                        val norm = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC)
                        for (nch in norm) {
                            appendMapped(nch, origIdx, origIdx + 1)
                        }
                    } else {
                        appendMapped(ch, origIdx, origIdx + 1)
                    }
                }
            }

            if (hasNewline) {
                val nlOrig = rawBaseOffset + lineEnd
                appendMapped('\n', nlOrig, nlOrig + 1)
                lineStart = lineEnd + 1
            } else {
                break
            }
        }

        return NormalizedTextWithMap(
            text = sb.toString(),
            originalStartIndices = starts.toIntArray(),
            originalEndIndices = ends.toIntArray()
        )
    }

    /**
     * Heuristic: PDF generators that emit isolated/initial/medial/final Arabic Presentation Forms
     * very commonly store glyphs in visual left-to-right order (final form at start of run, initial at end).
     */
    private fun looksLikeVisualOrderArabicPdfLine(line: String): Boolean {
        var presentationCount = 0
        var arabicCount = 0
        var visualOrderScore = 0

        for (i in line.indices) {
            val ch = line[i]
            if (isArabicLetterOrMark(ch)) arabicCount++
            if (isArabicPresentationForm(ch)) {
                presentationCount++
                // Check common Arabic Presentation Forms-B final/initial ordering within word runs
                val code = ch.code
                val isFinalForm = (code in 0xFE8E..0xFEFE) && ((code - 0xFE80) % 4 == 2)
                val isInitialForm = (code in 0xFE8B..0xFEFB) && ((code - 0xFE80) % 4 == 3)
                val prevIsArabic = i > 0 && isArabicLetterOrMark(line[i - 1])
                val nextIsArabic = i + 1 < line.length && isArabicLetterOrMark(line[i + 1])
                if (isFinalForm && !prevIsArabic && nextIsArabic) visualOrderScore++
                if (isInitialForm && prevIsArabic && !nextIsArabic) visualOrderScore++
            }
        }
        return arabicCount >= 4 && presentationCount >= 3 && visualOrderScore >= 1
    }

    /**
     * Computes punctuation-weighted spoken length for accurate zero-AI proportional sync
     * across Latin, Arabic, and CJK languages.
     */
    fun computeSpokenWeight(text: CharSequence, start: Int = 0, end: Int = text.length): Int {
        if (end <= start) return 0
        var weight = 0
        var i = start.coerceAtLeast(0)
        val safeEnd = end.coerceAtMost(text.length)
        while (i < safeEnd) {
            val ch = text[i]
            weight += when {
                isCjkIdeograph(ch) -> 3
                Character.isLetterOrDigit(ch) -> 1
                ch == '.' || ch == '!' || ch == '?' || ch == '\u061F' || ch == '。' || ch == '！' || ch == '？' -> 10
                ch == ',' || ch == ';' || ch == ':' || ch == '\u060C' || ch == '\u061B' || ch == '،' -> 4
                ch == '\n' -> {
                    if (i + 1 < safeEnd && text[i + 1] == '\n') 14 else 3
                }
                else -> 0
            }
            i++
        }
        return weight
    }

    /**
     * Deterministically tokenizes a text segment into [IndexedBookWord]s starting at [startWordIndex].
     */
    fun tokenizeSegment(
        rawText: String,
        pageIndex: Int,
        rawBaseOffset: Int = 0,
        startWordIndex: Int = 0,
        isFromPdf: Boolean = false
    ): List<IndexedBookWord> {
        val normalized = normalizeForSpeech(
            rawText = rawText,
            rawBaseOffset = rawBaseOffset,
            isFromPdf = isFromPdf
        )
        val s = normalized.text
        if (s.isBlank()) return emptyList()

        val result = ArrayList<IndexedBookWord>(s.length / 5 + 8)
        var wordIdx = startWordIndex
        var i = 0
        val len = s.length

        while (i < len) {
            val ch = s[i]
            if (isCjkIdeograph(ch)) {
                val origSpan = normalized.mapSpanToOriginal(i, i + 1)
                if (origSpan != null) {
                    result.add(
                        IndexedBookWord(
                            bookWordIndex = wordIdx++,
                            normalizedToken = ch.toString(),
                            originalText = ch.toString(),
                            pageIndex = pageIndex,
                            startCharOffset = origSpan.first,
                            endCharOffset = origSpan.second,
                            spokenWeight = 3
                        )
                    )
                }
                i++
            } else if (isWordChar(ch)) {
                val start = i
                while (i < len && isWordChar(s[i]) && !isCjkIdeograph(s[i])) {
                    i++
                }
                val tokenRaw = s.substring(start, i)
                val cleanToken = normalizeWordTokenForAlignment(tokenRaw)
                if (cleanToken.isNotEmpty()) {
                    val origSpan = normalized.mapSpanToOriginal(start, i)
                    if (origSpan != null) {
                        // Look ahead for trailing punctuation pause weight
                        var trailingPause = 0
                        if (i < len) {
                            trailingPause = computeSpokenWeight(s, i, (i + 2).coerceAtMost(len))
                        }
                        result.add(
                            IndexedBookWord(
                                bookWordIndex = wordIdx++,
                                normalizedToken = cleanToken,
                                originalText = tokenRaw,
                                pageIndex = pageIndex,
                                startCharOffset = origSpan.first,
                                endCharOffset = origSpan.second,
                                spokenWeight = (cleanToken.length + trailingPause).coerceAtLeast(1)
                            )
                        )
                    }
                }
            } else {
                i++
            }
        }
        return result
    }

    /**
     * Normalizes a single word for CTC / fuzzy alignment (strips Arabic diacritics & punctuation, lowercases).
     */
    fun normalizeWordTokenForAlignment(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch == TATWEEL) continue
            if (Character.isLetterOrDigit(ch)) {
                // Normalize Alef variants in Arabic for stronger matching
                val mapped = when (ch) {
                    '\u0622', '\u0623', '\u0625', '\u0671' -> '\u0627'
                    '\u0649' -> '\u064A'
                    else -> ch.lowercaseChar()
                }
                sb.append(mapped)
            }
        }
        return sb.toString()
    }

    /**
     * Splits text into TTS-safe sentence/clause chunks (<= [maxChunkChars]) with exact
     * per-character back-mappings into original EPUB/PDF offsets.
     */
    fun splitIntoTtsChunks(
        rawText: String,
        pageIndex: Int,
        rawBaseOffset: Int = 0,
        isFromPdf: Boolean = false,
        maxChunkChars: Int = 260
    ): List<TtsSpeechChunk> {
        val norm = normalizeForSpeech(rawText, rawBaseOffset, isFromPdf)
        val text = norm.text
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<TtsSpeechChunk>()
        var cursor = 0
        val total = text.length
        var chunkCounter = 0

        while (cursor < total) {
            while (cursor < total && text[cursor].isWhitespace()) cursor++
            if (cursor >= total) break

            val hardLimit = (cursor + maxChunkChars).coerceAtMost(total)
            var splitAt = -1

            // 1. Prefer sentence boundary or paragraph break within [cursor + 40, hardLimit]
            val minPreferred = (cursor + 32).coerceAtMost(hardLimit)
            for (i in cursor until hardLimit) {
                val c = text[i]
                if (c == '\n' && i + 1 < total && text[i + 1] == '\n') {
                    splitAt = i + 1
                    break
                }
                if (i >= minPreferred && (c == '.' || c == '!' || c == '?' || c == '\u061F' || c == '。' || c == '！' || c == '？')) {
                    val nextIsBoundary = (i + 1 >= total) || text[i + 1].isWhitespace() || text[i + 1] == '"' || text[i + 1] == '”'
                    if (nextIsBoundary) {
                        splitAt = (i + 1).coerceAtMost(total)
                        break
                    }
                }
            }

            // 2. Fall back to clause boundary (comma/semicolon/newline) near the end
            if (splitAt <= cursor && hardLimit < total) {
                for (i in (hardLimit - 1) downTo minPreferred) {
                    val c = text[i]
                    if (c == ',' || c == ';' || c == ':' || c == '\u060C' || c == '\n') {
                        splitAt = i + 1
                        break
                    }
                }
            }

            // 3. Fall back to last whitespace before hardLimit
            if (splitAt <= cursor && hardLimit < total) {
                for (i in (hardLimit - 1) downTo (cursor + 1)) {
                    if (text[i].isWhitespace()) {
                        splitAt = i
                        break
                    }
                }
            }

            if (splitAt <= cursor) {
                splitAt = hardLimit
            }

            // Trim trailing whitespace from slice
            var sliceEnd = splitAt
            while (sliceEnd > cursor && text[sliceEnd - 1].isWhitespace()) sliceEnd--

            if (sliceEnd > cursor) {
                val spoken = text.substring(cursor, sliceEnd)
                if (spoken.any { Character.isLetterOrDigit(it) }) {
                    val starts = norm.originalStartIndices.copyOfRange(cursor, sliceEnd)
                    val ends = norm.originalEndIndices.copyOfRange(cursor, sliceEnd)
                    val docStart = starts.minOrNull() ?: rawBaseOffset
                    val docEnd = ends.maxOrNull() ?: (docStart + spoken.length)
                    chunks.add(
                        TtsSpeechChunk(
                            chunkId = "tts_${pageIndex}_${chunkCounter++}_$docStart",
                            pageIndex = pageIndex,
                            spokenText = spoken,
                            originalStartIndices = starts,
                            originalEndIndices = ends,
                            docStartChar = docStart,
                            docEndChar = docEnd,
                            spokenWeight = computeSpokenWeight(spoken).coerceAtLeast(1)
                        )
                    )
                }
            }
            cursor = splitAt
        }

        return chunks
    }

    /**
     * Computes deterministic SHA-256 fingerprint of a chapter's tokenized word list.
     */
    fun computeWordsHash(words: List<IndexedBookWord>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (w in words) {
            digest.update(w.normalizedToken.toByteArray(Charsets.UTF_8))
            digest.update(0x1F)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }
}
