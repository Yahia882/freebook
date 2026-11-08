package com.example.freebook.ui.pagination

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.min

/**
 * Immutable result of paginating a book for one specific layout (viewport + text style).
 * Pages are described by character ranges into [text], so a reading position can be stored as a
 * layout-independent character offset and mapped back to a page for ANY layout.
 */
class PageLayout(
    val text: String,
    private val starts: IntArray,
    private val ends: IntArray
) {
    val pageCount: Int get() = starts.size

    fun pageText(index: Int): String {
        if (index !in starts.indices) return ""
        return text.substring(starts[index], ends[index]).trimEnd()
    }

    fun startOffsetOf(index: Int): Int = starts.getOrElse(index) { 0 }

    /** Index of the page containing [charOffset] (largest page start <= offset). */
    fun pageIndexForOffset(charOffset: Int): Int {
        if (starts.isEmpty()) return 0
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= charOffset) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/**
 * Deterministically slices book text into pages that strictly fit the viewport.
 * Same input (text, style, viewport px, density) always yields the same pages.
 */
object ViewportPaginator {

    private const val MAX_WINDOW_CHARS = 3200

    /** Canonical text used for BOTH pagination and offsets. Must stay deterministic. */
    fun cleanText(raw: String): String =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    fun paginate(
        cleanedText: String,
        textMeasurer: TextMeasurer,
        textStyle: TextStyle,
        density: Density,
        availableWidthPx: Int,
        availableHeightPx: Int
    ): PageLayout {
        val total = cleanedText.length
        if (total == 0 || availableWidthPx <= 60 || availableHeightPx <= 60) {
            return PageLayout(cleanedText, intArrayOf(0), intArrayOf(total))
        }

        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var cursor = 0

        while (cursor < total) {
            while (cursor < total && cleanedText[cursor].isWhitespace()) cursor++
            if (cursor >= total) break

            val windowEnd = min(cursor + MAX_WINDOW_CHARS, total)
            val windowText = cleanedText.substring(cursor, windowEnd)

            val layout = textMeasurer.measure(
                text = AnnotatedString(windowText),
                style = textStyle,
                constraints = Constraints(maxWidth = availableWidthPx),
                density = density
            )

            var lastFittingLine = 0
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= availableHeightPx) lastFittingLine = line else break
            }

            var sliceEnd = layout.getLineEnd(lastFittingLine, visibleEnd = true)
                .coerceIn(1, windowText.length)

            // Don't split a word across pages (unless the window ends the book).
            if (windowEnd < total || sliceEnd < windowText.length) {
                if (sliceEnd < windowText.length &&
                    !windowText[sliceEnd - 1].isWhitespace() &&
                    !windowText[sliceEnd].isWhitespace()
                ) {
                    val lineStart = layout.getLineStart(lastFittingLine)
                    val lastSpace = windowText.lastIndexOf(' ', startIndex = sliceEnd - 1)
                    if (lastSpace > lineStart) sliceEnd = lastSpace + 1
                }
            }

            starts.add(cursor)
            ends.add(cursor + sliceEnd)
            cursor += sliceEnd
        }

        if (starts.isEmpty()) return PageLayout(cleanedText, intArrayOf(0), intArrayOf(total))
        return PageLayout(cleanedText, starts.toIntArray(), ends.toIntArray())
    }
}
