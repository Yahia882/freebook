package com.example.freebook.data

import java.util.Locale
import kotlin.math.abs

/**
 * Pure-Kotlin, fast EPUB spine + `#fragment` offset resolver that maps EPUB reading-order
 * XHTML files and TOC links (including nested `#id`/`#name`/`xml:id` sub-headings) onto exact
 * character offsets inside `canonicalBookText`.
 *
 * Designed to run in milliseconds without invoking `HtmlCompat.fromHtml` per TOC item and with
 * zero Android framework dependencies so every edge case is directly unit-testable on the JVM.
 */
internal object EpubTocOffsetResolver {

    data class SpineDocument(
        val hrefKeys: List<String>,
        val rawHtml: String
    )

    data class ResolvedSpineSlice(
        val spineIndex: Int,
        val startOrig: Int,
        val endOrig: Int,
        val startNorm: Int,
        val endNorm: Int,
        val matched: Boolean,
        val rawHtml: String
    ) {
        private var cachedFragmentTagEnds: Map<String, Int>? = null
        private var cachedFragmentTagEndsLower: Map<String, Int>? = null

        fun findFragmentContentStart(fragmentId: String): Int? {
            if (fragmentId.isBlank() || rawHtml.isEmpty()) return null
            val exactMap = cachedFragmentTagEnds ?: buildFragmentIndex().also { (exact, lower) ->
                cachedFragmentTagEnds = exact
                cachedFragmentTagEndsLower = lower
            }.first
            exactMap[fragmentId]?.let { return it }
            return cachedFragmentTagEndsLower?.get(fragmentId.lowercase(Locale.ROOT))
        }

        private fun buildFragmentIndex(): Pair<Map<String, Int>, Map<String, Int>> {
            val exact = HashMap<String, Int>()
            val lower = HashMap<String, Int>()
            indexHtmlAnchorIds(rawHtml) { id, contentStart ->
                if (!exact.containsKey(id)) {
                    exact[id] = contentStart
                }
                val lowerId = id.lowercase(Locale.ROOT)
                if (!lower.containsKey(lowerId)) {
                    lower[lowerId] = contentStart
                }
            }
            return exact to lower
        }
    }

    /**
     * Compact normalized projection of `canonicalBookText` mapping every normalized word/digit
     * character back to its exact index in `canonicalBookText`.
     *
     * Collapses whitespace, punctuation, quotes, dashes, and `\uFFFC` (`[OBJ]`) into single
     * spaces so differences in smart quotes, line breaks, or embedded `<img>` glyphs never break
     * TOC matching.
     */
    class CanonicalSearchIndex(val canonicalText: String) {
        val normText: String
        private val origIndices: IntArray

        val normLength: Int
            get() = normText.length

        init {
            val sb = StringBuilder(canonicalText.length)
            val indices = IntArray(canonicalText.length)
            var outLen = 0
            var lastWasSpace = true

            var i = 0
            val len = canonicalText.length
            while (i < len) {
                val ch = canonicalText[i]
                if (isIgnoredInWordChar(ch)) {
                    i++
                    continue
                }
                if (Character.isLetterOrDigit(ch)) {
                    sb.append(ch.lowercaseChar())
                    indices[outLen++] = i
                    lastWasSpace = false
                } else if (!lastWasSpace) {
                    sb.append(' ')
                    indices[outLen++] = i
                    lastWasSpace = true
                }
                i++
            }
            if (outLen > 0 && sb[outLen - 1] == ' ') {
                outLen--
                sb.setLength(outLen)
            }
            normText = sb.toString()
            origIndices = indices.copyOf(outLen)
        }

        fun normToOrig(normIndex: Int): Int {
            if (origIndices.isEmpty() || canonicalText.isEmpty()) return 0
            val clamped = normIndex.coerceIn(0, origIndices.lastIndex)
            return origIndices[clamped].coerceIn(0, (canonicalText.length - 1).coerceAtLeast(0))
        }

        /**
         * Finds a word-aligned occurrence of [normQuery] within `[startNorm, endNorm]`.
         * When multiple occurrences exist and [expectedRelativePos] (`0f..1f`) is provided,
         * returns the occurrence whose relative position inside `[startNorm, endNorm]` is closest
         * to [expectedRelativePos].
         */
        fun findWordAligned(
            normQuery: String,
            startNorm: Int = 0,
            endNorm: Int = normLength,
            expectedRelativePos: Float? = null
        ): Int {
            val query = normQuery.trim()
            if (query.isEmpty() || normText.isEmpty()) return -1
            val safeStart = startNorm.coerceIn(0, normLength)
            val safeEnd = endNorm.coerceIn(safeStart, normLength)
            if (safeEnd - safeStart < query.length) return -1

            val maxSearchStart = safeEnd - query.length
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            val span = (safeEnd - safeStart).coerceAtLeast(1).toFloat()

            var searchFrom = safeStart
            var matchCount = 0
            while (searchFrom <= maxSearchStart) {
                val found = normText.indexOf(query, startIndex = searchFrom)
                if (found < 0 || found > maxSearchStart) break

                val leftOk = (found == 0 || normText[found - 1] == ' ')
                val rightEnd = found + query.length
                val rightOk = (rightEnd == normLength || normText[rightEnd] == ' ')
                if (leftOk && rightOk) {
                    if (expectedRelativePos == null) {
                        return found
                    }
                    val rel = (found - safeStart).toFloat() / span
                    val dist = abs(rel - expectedRelativePos)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = found
                    }
                    matchCount++
                    if (matchCount >= 24 && dist < 0.02f) break
                }
                searchFrom = found + 1
            }
            return bestIdx
        }
    }

    /**
     * Stateful resolved book index supporting fast `O(1)` spine lookup and `O(1)` fragment
     * lookup per TOC entry.
     */
    class ResolvedBookTocIndex(
        val searchIndex: CanonicalSearchIndex,
        val slicesInOrder: List<ResolvedSpineSlice>,
        private val slicesByNormalizedPath: Map<String, ResolvedSpineSlice>,
        private val slicesByLowerPath: Map<String, ResolvedSpineSlice>
    ) {
        fun findSliceForHref(vararg candidateHrefs: String?): ResolvedSpineSlice? {
            for (raw in candidateHrefs) {
                if (raw.isNullOrBlank()) continue
                val norm = normalizeHrefPath(raw)
                if (norm.isEmpty()) continue
                slicesByNormalizedPath[norm]?.let { return it }
                val lower = norm.lowercase(Locale.ROOT)
                slicesByLowerPath[lower]?.let { return it }
            }
            // Suffix fallback for relative paths like "../Text/ch01.xhtml" vs "OEBPS/Text/ch01.xhtml"
            for (raw in candidateHrefs) {
                if (raw.isNullOrBlank()) continue
                val lower = normalizeHrefPath(raw).lowercase(Locale.ROOT)
                if (lower.isEmpty()) continue
                val suffixMatch = slicesByLowerPath.entries.firstOrNull { (k, _) ->
                    k.endsWith("/$lower") || lower.endsWith("/$k")
                }?.value
                if (suffixMatch != null) return suffixMatch
            }
            return null
        }

        /**
         * Resolves a TOC entry's exact character offset in `canonicalBookText`.
         *
         * @param candidateHrefs Candidate href strings (e.g. Readium locator href and raw TOC link href).
         * @param fragmentId Optional `#fragment` anchor id (raw or percent-encoded).
         * @param tocTitle Optional TOC entry title used as a normalized fallback if anchor text fails.
         * @param fallbackSearchStartOrig Optional canonical char offset hint if href lookup fails completely.
         */
        fun resolveTocCharOffset(
            candidateHrefs: List<String?>,
            fragmentId: String?,
            tocTitle: String?,
            fallbackSearchStartOrig: Int = 0
        ): Int {
            val totalLen = searchIndex.canonicalText.length
            if (totalLen == 0) return 0

            val slice = findSliceForHref(*candidateHrefs.toTypedArray())
            val cleanFrag = fragmentId?.trim()?.takeIf { it.isNotEmpty() }?.let { percentDecode(it) }

            if (slice != null) {
                if (!cleanFrag.isNullOrBlank() && slice.rawHtml.isNotEmpty() && slice.endNorm > slice.startNorm) {
                    val contentStart = slice.findFragmentContentStart(cleanFrag)
                    if (contentStart != null) {
                        val expectedRel = (contentStart.toFloat() / slice.rawHtml.length.coerceAtLeast(1).toFloat())
                            .coerceIn(0f, 1f)
                        val foundNorm = matchHtmlWindowInSlice(
                            rawHtml = slice.rawHtml,
                            htmlStartIndex = contentStart,
                            sliceStartNorm = slice.startNorm,
                            sliceEndNorm = slice.endNorm,
                            expectedRelativePos = expectedRel
                        )
                        if (foundNorm >= 0) {
                            return searchIndex.normToOrig(foundNorm)
                        }
                    }

                    // Fragment ID was missing or had no text right after it -> normalized title fallback inside slice
                    val titleNormMatch = matchTocTitleInRange(
                        tocTitle = tocTitle,
                        startNorm = slice.startNorm,
                        endNorm = slice.endNorm,
                        expectedRelativePos = contentStart?.let {
                            (it.toFloat() / slice.rawHtml.length.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        }
                    )
                    if (titleNormMatch >= 0) {
                        return searchIndex.normToOrig(titleNormMatch)
                    }
                }
                return slice.startOrig.coerceIn(0, (totalLen - 1).coerceAtLeast(0))
            }

            // Last-resort fallback when href failed to resolve to any spine item: search normalized title
            if (!tocTitle.isNullOrBlank()) {
                val hintNorm = origToNormApprox(fallbackSearchStartOrig)
                val forwardMatch = matchTocTitleInRange(
                    tocTitle = tocTitle,
                    startNorm = hintNorm,
                    endNorm = searchIndex.normLength,
                    expectedRelativePos = null
                )
                if (forwardMatch >= 0) {
                    return searchIndex.normToOrig(forwardMatch)
                }
                if (hintNorm > 0) {
                    val globalMatch = matchTocTitleInRange(
                        tocTitle = tocTitle,
                        startNorm = 0,
                        endNorm = searchIndex.normLength,
                        expectedRelativePos = null
                    )
                    if (globalMatch >= 0) {
                        return searchIndex.normToOrig(globalMatch)
                    }
                }
            }

            return 0
        }

        private fun matchHtmlWindowInSlice(
            rawHtml: String,
            htmlStartIndex: Int,
            sliceStartNorm: Int,
            sliceEndNorm: Int,
            expectedRelativePos: Float
        ): Int {
            var plainWindow = extractPlainTextFromHtml(
                html = rawHtml,
                startIndex = htmlStartIndex,
                maxHtmlScanChars = 4096,
                maxOutputChars = 240
            )
            var normWindow = normalizeForIndex(plainWindow)
            if (normWindow.length < 24 && htmlStartIndex + 4096 < rawHtml.length) {
                plainWindow = extractPlainTextFromHtml(
                    html = rawHtml,
                    startIndex = htmlStartIndex,
                    maxHtmlScanChars = 16384,
                    maxOutputChars = 240
                )
                normWindow = normalizeForIndex(plainWindow)
            }
            if (normWindow.isEmpty()) return -1

            // Try progressively shorter complete-word probes (long multi-line probe first for uniqueness)
            val probeLengths = intArrayOf(120, 64, 32, 16)
            for (maxLen in probeLengths) {
                val probe = truncateToWordBoundary(normWindow, maxLen)
                if (probe.length < 3) continue
                val found = searchIndex.findWordAligned(
                    normQuery = probe,
                    startNorm = sliceStartNorm,
                    endNorm = sliceEndNorm,
                    expectedRelativePos = expectedRelativePos
                )
                if (found >= 0) return found
                if (normWindow.length <= maxLen) break
            }

            // Fallback for mid-word pagebreak/anchor spans (where left/right boundary isn't a space)
            for (maxLen in intArrayOf(64, 24)) {
                val probe = truncateToWordBoundary(normWindow, maxLen)
                if (probe.length < 12) continue
                val rawFound = searchIndex.normText.indexOf(probe, startIndex = sliceStartNorm)
                if (rawFound in sliceStartNorm..(sliceEndNorm - probe.length)) {
                    return rawFound
                }
                if (normWindow.length <= maxLen) break
            }

            // If anchor fell right on a short word or single token (< 16 chars)
            if (normWindow.length in 1..15) {
                val found = searchIndex.findWordAligned(
                    normQuery = normWindow,
                    startNorm = sliceStartNorm,
                    endNorm = sliceEndNorm,
                    expectedRelativePos = expectedRelativePos
                )
                if (found >= 0) return found
            }

            // Skip-first-word recovery in case an unusual entity or dropped glyph sat right at index 0
            val firstSpace = normWindow.indexOf(' ')
            if (firstSpace > 0 && normWindow.length - firstSpace > 16) {
                val shifted = truncateToWordBoundary(normWindow.substring(firstSpace + 1).trim(), 72)
                if (shifted.length >= 12) {
                    val found = searchIndex.findWordAligned(
                        normQuery = shifted,
                        startNorm = sliceStartNorm,
                        endNorm = sliceEndNorm,
                        expectedRelativePos = expectedRelativePos
                    )
                    if (found >= 0) {
                        return (found - firstSpace - 1).coerceAtLeast(sliceStartNorm)
                    }
                }
            }
            return -1
        }

        private fun matchTocTitleInRange(
            tocTitle: String?,
            startNorm: Int,
            endNorm: Int,
            expectedRelativePos: Float?
        ): Int {
            if (tocTitle.isNullOrBlank()) return -1
            val fullNorm = normalizeForIndex(tocTitle)
            if (fullNorm.isEmpty()) return -1

            val exact = searchIndex.findWordAligned(
                normQuery = fullNorm,
                startNorm = startNorm,
                endNorm = endNorm,
                expectedRelativePos = expectedRelativePos
            )
            if (exact >= 0) return exact

            // Strip leading "Chapter N:" / "Part I:" / "1." prefixes if TOC adds labels absent from heading
            val afterColon = tocTitle.substringAfter(':', "").trim()
            if (afterColon.length >= 4) {
                val subNorm = normalizeForIndex(afterColon)
                if (subNorm.length >= 4 && subNorm != fullNorm) {
                    val found = searchIndex.findWordAligned(
                        normQuery = subNorm,
                        startNorm = startNorm,
                        endNorm = endNorm,
                        expectedRelativePos = expectedRelativePos
                    )
                    if (found >= 0) return found
                }
            }

            return -1
        }

        private fun origToNormApprox(origOffset: Int): Int {
            if (origOffset <= 0 || searchIndex.normLength == 0) return 0
            var lo = 0
            var hi = searchIndex.normLength - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (searchIndex.normToOrig(mid) < origOffset) {
                    lo = mid + 1
                } else {
                    hi = mid
                }
            }
            return lo
        }
    }

    /**
     * Builds a [ResolvedBookTocIndex] aligning every spine XHTML document in `readingOrder`
     * onto `canonicalBookText` with anti-drift guards and exact inter-chapter boundaries.
     */
    fun buildBookIndex(
        canonicalBookText: String,
        spineDocuments: List<SpineDocument>
    ): ResolvedBookTocIndex {
        val searchIndex = CanonicalSearchIndex(canonicalBookText)
        val totalNorm = searchIndex.normLength
        val totalOrig = canonicalBookText.length

        data class RawMatch(
            val doc: SpineDocument,
            val matchedNorm: Int,
            val normLen: Int
        )

        val rawMatches = ArrayList<RawMatch>(spineDocuments.size)
        var minNextNorm = 0
        var expectedNextNorm = 0
        var prevNormLen = 0

        for (doc in spineDocuments) {
            val plain = extractPlainTextFromHtml(doc.rawHtml)
            val normChapter = normalizeForIndex(plain)

            if (normChapter.isEmpty() || totalNorm == 0) {
                rawMatches.add(RawMatch(doc, matchedNorm = -1, normLen = 0))
                continue
            }

            val isLikelyDroppedAtImport = plain.trim().length <= 15
            val matchedNorm = findSpineStartNorm(
                searchIndex = searchIndex,
                normChapter = normChapter,
                minNextNorm = minNextNorm,
                expectedNextNorm = expectedNextNorm,
                prevNormLen = prevNormLen,
                strictWindowOnly = isLikelyDroppedAtImport
            )

            if (matchedNorm >= 0) {
                minNextNorm = (matchedNorm + 1).coerceAtMost(totalNorm)
                expectedNextNorm = (matchedNorm + normChapter.length).coerceAtMost(totalNorm)
                prevNormLen = normChapter.length
            }
            rawMatches.add(RawMatch(doc, matchedNorm = matchedNorm, normLen = normChapter.length))
        }

        // Backward pass: compute exact [startNorm, endNorm] boundaries from the next matched spine item
        val slices = arrayOfNulls<ResolvedSpineSlice>(rawMatches.size)
        var nextKnownStartNorm = totalNorm
        var nextKnownStartOrig = totalOrig

        for (i in rawMatches.lastIndex downTo 0) {
            val rm = rawMatches[i]
            if (rm.matchedNorm >= 0) {
                val startNorm = rm.matchedNorm
                val endNorm = nextKnownStartNorm.coerceAtLeast(startNorm)
                val startOrig = searchIndex.normToOrig(startNorm)
                val endOrig = nextKnownStartOrig.coerceAtLeast(startOrig)
                slices[i] = ResolvedSpineSlice(
                    spineIndex = i,
                    startOrig = startOrig,
                    endOrig = endOrig,
                    startNorm = startNorm,
                    endNorm = endNorm,
                    matched = true,
                    rawHtml = rm.doc.rawHtml
                )
                nextKnownStartNorm = startNorm
                nextKnownStartOrig = startOrig
            } else {
                // Dropped front-matter / cover / short divider page -> point cleanly to the next matched chapter
                val clampedNorm = nextKnownStartNorm.coerceIn(0, (totalNorm - 1).coerceAtLeast(0))
                val clampedOrig = if (nextKnownStartOrig >= totalOrig) {
                    (totalOrig - 1).coerceAtLeast(0)
                } else {
                    nextKnownStartOrig.coerceAtLeast(0)
                }
                slices[i] = ResolvedSpineSlice(
                    spineIndex = i,
                    startOrig = clampedOrig,
                    endOrig = clampedOrig,
                    startNorm = clampedNorm,
                    endNorm = clampedNorm,
                    matched = false,
                    rawHtml = rm.doc.rawHtml
                )
            }
        }

        val resolvedList = slices.map { it!! }
        val byNorm = LinkedHashMap<String, ResolvedSpineSlice>()
        val byLower = LinkedHashMap<String, ResolvedSpineSlice>()

        for (i in resolvedList.indices) {
            val slice = resolvedList[i]
            for (rawKey in spineDocuments[i].hrefKeys) {
                val normKey = normalizeHrefPath(rawKey)
                if (normKey.isNotEmpty()) {
                    byNorm[normKey] = slice
                    byLower[normKey.lowercase(Locale.ROOT)] = slice
                }
            }
        }

        return ResolvedBookTocIndex(
            searchIndex = searchIndex,
            slicesInOrder = resolvedList,
            slicesByNormalizedPath = byNorm,
            slicesByLowerPath = byLower
        )
    }

    private fun findSpineStartNorm(
        searchIndex: CanonicalSearchIndex,
        normChapter: String,
        minNextNorm: Int,
        expectedNextNorm: Int,
        prevNormLen: Int,
        strictWindowOnly: Boolean
    ): Int {
        val totalNorm = searchIndex.normLength
        if (normChapter.isEmpty() || totalNorm == 0) return -1

        val probes = ArrayList<Pair<String, Int>>(4)
        for (maxLen in intArrayOf(120, 64, 32)) {
            val p = truncateToWordBoundary(normChapter, maxLen)
            if (p.length >= 6 && probes.none { it.first == p }) {
                probes.add(p to 0)
            }
            if (normChapter.length <= maxLen) break
        }
        if (probes.isEmpty() && normChapter.length >= 4) {
            probes.add(normChapter to 0)
        }
        // Shifted probe in case the very first token had an unusual entity or dropped glyph
        if (normChapter.length >= 64) {
            val skipIdx = normChapter.indexOf(' ', startIndex = 16)
            if (skipIdx > 0 && normChapter.length - skipIdx >= 24) {
                val shifted = truncateToWordBoundary(normChapter.substring(skipIdx + 1).trim(), 72)
                if (shifted.length >= 16) {
                    probes.add(shifted to (skipIdx + 1))
                }
            }
        }

        if (probes.isEmpty()) return -1

        // 1. Prefer a focused window right around expectedNextNorm (fast and prevents premature mid-chapter hits)
        val backMargin = maxOf(256, prevNormLen / 6)
        val winStart = (expectedNextNorm - backMargin).coerceIn(minNextNorm, totalNorm)
        val winEnd = if (strictWindowOnly) {
            (expectedNextNorm + 512).coerceAtMost(totalNorm)
        } else {
            (expectedNextNorm + maxOf(4096, prevNormLen)).coerceAtMost(totalNorm)
        }

        for ((probe, shiftBack) in probes) {
            val found = searchIndex.findWordAligned(probe, startNorm = winStart, endNorm = winEnd)
            if (found >= 0) {
                return (found - shiftBack).coerceAtLeast(minNextNorm)
            }
        }

        if (strictWindowOnly) return -1

        // 2. Fallback: full forward scan from minNextNorm
        for ((probe, shiftBack) in probes) {
            val found = searchIndex.findWordAligned(probe, startNorm = minNextNorm, endNorm = totalNorm)
            if (found >= 0) {
                return (found - shiftBack).coerceAtLeast(minNextNorm)
            }
        }

        return -1
    }

    /**
     * Single-pass pure-Kotlin HTML-to-plain-text extractor that skips `<head>`, `<style>`,
     * `<script>`, `<noscript>`, and `<!-- comments -->`, preserves word separation across block
     * elements, keeps inline elements joined, and decodes numeric & named HTML entities.
     */
    fun extractPlainTextFromHtml(
        html: String,
        startIndex: Int = 0,
        maxHtmlScanChars: Int = Int.MAX_VALUE,
        maxOutputChars: Int = Int.MAX_VALUE
    ): String {
        val len = html.length
        if (startIndex >= len) return ""
        val scanEnd = if (maxHtmlScanChars == Int.MAX_VALUE || startIndex > len - maxHtmlScanChars) {
            len
        } else {
            startIndex + maxHtmlScanChars
        }

        val out = StringBuilder(minOf(scanEnd - startIndex, maxOutputChars.coerceAtMost(65536)).coerceAtLeast(64))
        var i = startIndex

        while (i < scanEnd && out.length < maxOutputChars) {
            val ch = html[i]
            if (ch == '<') {
                // HTML comment <!-- ... -->
                if (i + 3 < len && html[i + 1] == '!' && html[i + 2] == '-' && html[i + 3] == '-') {
                    val commentEnd = html.indexOf("-->", i + 4)
                    i = if (commentEnd >= 0) commentEnd + 3 else scanEnd
                    continue
                }
                // CDATA <![CDATA[ ... ]]>
                if (i + 8 < len && html.regionMatches(i, "<![CDATA[", 0, 9, ignoreCase = true)) {
                    val cdataEnd = html.indexOf("]]>", i + 9)
                    if (cdataEnd >= 0) {
                        out.append(html, i + 9, minOf(cdataEnd, i + 9 + (maxOutputChars - out.length)))
                        i = cdataEnd + 3
                    } else {
                        i = scanEnd
                    }
                    continue
                }

                // Read tag name (skip optional leading '/')
                var nameStart = i + 1
                val isClosing = nameStart < len && html[nameStart] == '/'
                if (isClosing) nameStart++
                var nameEnd = nameStart
                while (nameEnd < len) {
                    val tc = html[nameEnd]
                    if (tc.isLetterOrDigit() || tc == ':' || tc == '-' || tc == '_') {
                        nameEnd++
                    } else {
                        break
                    }
                }
                val tagName = html.substring(nameStart, nameEnd).lowercase(Locale.ROOT).substringAfter(':')

                // Advance to closing '>' respecting quoted attribute values
                val tagEnd = findClosingAngleBracket(html, nameEnd)
                if (tagEnd < 0) {
                    i = scanEnd
                    break
                }
                val isSelfClosing = tagEnd > i && html[tagEnd - 1] == '/'
                i = tagEnd + 1

                // Skip body of non-visible tags completely
                if (!isClosing && !isSelfClosing && tagName in skippedSubtreeTags) {
                    val closeIdx = findClosingTag(html, tagName, i)
                    i = if (closeIdx >= 0) closeIdx else scanEnd
                    continue
                }

                // Emit separator for block-level tags so headings/paragraphs never glue together
                if (tagName in blockSeparatorTags && out.isNotEmpty()) {
                    val last = out[out.length - 1]
                    if (last != '\n' && last != ' ') {
                        out.append('\n')
                    }
                }
            } else if (ch == '&') {
                val semi = findEntitySemicolon(html, i + 1, minOf(len, i + 16))
                if (semi > i + 1) {
                    val entity = html.substring(i + 1, semi)
                    val decoded = decodeHtmlEntity(entity)
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
                out.append(ch)
                i++
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Scans [rawHtml] in a single pass (skipping comments, `<head>`, `<style>`, `<script>`)
     * and invokes [onAnchor] with `(idValue, indexImmediatelyAfterClosingAngleBracket)` for
     * every `id`, `name`, or `xml:id` attribute found.
     */
    internal fun indexHtmlAnchorIds(
        rawHtml: String,
        onAnchor: (id: String, contentStartIndex: Int) -> Unit
    ) {
        val len = rawHtml.length
        var i = 0
        while (i < len) {
            val lt = rawHtml.indexOf('<', i)
            if (lt < 0) break

            // Skip comments
            if (lt + 3 < len && rawHtml[lt + 1] == '!' && rawHtml[lt + 2] == '-' && rawHtml[lt + 3] == '-') {
                val commentEnd = rawHtml.indexOf("-->", lt + 4)
                i = if (commentEnd >= 0) commentEnd + 3 else len
                continue
            }
            // Skip closing tags, declarations, processing instructions
            if (lt + 1 >= len || rawHtml[lt + 1] == '/' || rawHtml[lt + 1] == '!' || rawHtml[lt + 1] == '?') {
                val gt = findClosingAngleBracket(rawHtml, lt + 1)
                i = if (gt >= 0) gt + 1 else len
                continue
            }

            // Read tag name
            var p = lt + 1
            while (p < len && (rawHtml[p].isLetterOrDigit() || rawHtml[p] == ':' || rawHtml[p] == '-' || rawHtml[p] == '_')) {
                p++
            }
            val tagName = rawHtml.substring(lt + 1, p).lowercase(Locale.ROOT).substringAfter(':')

            val gt = findClosingAngleBracket(rawHtml, p)
            if (gt < 0) break
            val contentStart = gt + 1

            if (tagName in skippedSubtreeTags && (gt == 0 || rawHtml[gt - 1] != '/')) {
                val closeEnd = findClosingTag(rawHtml, tagName, contentStart)
                i = if (closeEnd >= 0) closeEnd else len
                continue
            }

            // Parse attributes inside [p, gt)
            var attrCursor = p
            while (attrCursor < gt) {
                while (attrCursor < gt && rawHtml[attrCursor].isWhitespace()) attrCursor++
                if (attrCursor >= gt || rawHtml[attrCursor] == '/') break

                val attrNameStart = attrCursor
                while (attrCursor < gt && !rawHtml[attrCursor].isWhitespace() && rawHtml[attrCursor] != '=' && rawHtml[attrCursor] != '/') {
                    attrCursor++
                }
                val attrName = rawHtml.substring(attrNameStart, attrCursor).lowercase(Locale.ROOT)

                while (attrCursor < gt && rawHtml[attrCursor].isWhitespace()) attrCursor++
                if (attrCursor >= gt || rawHtml[attrCursor] != '=') {
                    continue
                }
                attrCursor++ // skip '='
                while (attrCursor < gt && rawHtml[attrCursor].isWhitespace()) attrCursor++
                if (attrCursor >= gt) break

                val valueStart: Int
                val valueEnd: Int
                val quote = rawHtml[attrCursor]
                if (quote == '"' || quote == '\'') {
                    attrCursor++
                    valueStart = attrCursor
                    while (attrCursor < gt && rawHtml[attrCursor] != quote) attrCursor++
                    valueEnd = attrCursor
                    if (attrCursor < gt) attrCursor++ // skip closing quote
                } else {
                    valueStart = attrCursor
                    while (attrCursor < gt && !rawHtml[attrCursor].isWhitespace() && rawHtml[attrCursor] != '/') {
                        attrCursor++
                    }
                    valueEnd = attrCursor
                }

                if ((attrName == "id" || attrName == "name" || attrName == "xml:id") && valueEnd > valueStart) {
                    val rawVal = rawHtml.substring(valueStart, valueEnd).trim()
                    if (rawVal.isNotEmpty()) {
                        onAnchor(rawVal, contentStart)
                    }
                }
            }

            i = contentStart
        }
    }

    fun normalizeForIndex(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        var lastWasSpace = true
        for (i in text.indices) {
            val ch = text[i]
            if (isIgnoredInWordChar(ch)) continue
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch.lowercaseChar())
                lastWasSpace = false
            } else if (!lastWasSpace) {
                sb.append(' ')
                lastWasSpace = true
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    fun normalizeHrefPath(rawHref: String): String {
        val withoutFragOrQuery = rawHref
            .substringBefore('#')
            .substringBefore('?')
            .trim()
        if (withoutFragOrQuery.isEmpty()) return ""
        val decoded = percentDecode(withoutFragOrQuery).replace('\\', '/')
        val segments = ArrayList<String>(8)
        for (part in decoded.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(part)
            }
        }
        return segments.joinToString("/")
    }

    fun percentDecode(input: String): String {
        if (!input.contains('%')) return input
        val bytes = ByteArray(input.length)
        var byteCount = 0
        var i = 0
        val len = input.length
        val out = StringBuilder(len)

        fun flushBytes() {
            if (byteCount > 0) {
                out.append(String(bytes, 0, byteCount, Charsets.UTF_8))
                byteCount = 0
            }
        }

        while (i < len) {
            val ch = input[i]
            if (ch == '%' && i + 2 < len) {
                val hi = hexDigit(input[i + 1])
                val lo = hexDigit(input[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes[byteCount++] = ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            flushBytes()
            out.append(ch)
            i++
        }
        flushBytes()
        return out.toString()
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun truncateToWordBoundary(norm: String, maxLen: Int): String {
        if (norm.length <= maxLen) return norm
        val cut = norm.lastIndexOf(' ', startIndex = maxLen)
        return if (cut >= maxLen / 2) norm.substring(0, cut) else norm.substring(0, maxLen)
    }

    private fun isIgnoredInWordChar(ch: Char): Boolean {
        if (ch == '\u00AD' || ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF' || ch == '\u0640') {
            return true
        }
        return Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
    }

    private fun findClosingAngleBracket(html: String, start: Int): Int {
        var i = start
        val len = html.length
        var quote: Char = '\u0000'
        while (i < len) {
            val c = html[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '>' -> return i
                }
            }
            i++
        }
        return -1
    }

    private fun findClosingTag(html: String, tagName: String, start: Int): Int {
        val len = html.length
        var i = start
        while (i < len) {
            val lt = html.indexOf("</", i)
            if (lt < 0) return -1
            var p = lt + 2
            while (p < len && html[p].isWhitespace()) p++
            val nameStart = p
            while (p < len && (html[p].isLetterOrDigit() || html[p] == ':' || html[p] == '-' || html[p] == '_')) {
                p++
            }
            val foundTag = html.substring(nameStart, p).lowercase(Locale.ROOT).substringAfter(':')
            val gt = html.indexOf('>', p)
            if (gt < 0) return -1
            if (foundTag == tagName) {
                return gt + 1
            }
            i = gt + 1
        }
        return -1
    }

    private fun findEntitySemicolon(html: String, start: Int, limit: Int): Int {
        var i = start
        while (i < limit) {
            val c = html[i]
            if (c == ';') return i
            if (c == '&' || c == '<' || c.isWhitespace()) return -1
            i++
        }
        return -1
    }

    private fun decodeHtmlEntity(entity: String): String? {
        if (entity.isEmpty()) return null
        if (entity[0] == '#') {
            val codePoint = if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull(10)
            } ?: return null
            if (codePoint <= 0 || codePoint > Character.MAX_CODE_POINT) return null
            return if (codePoint == 0xAD) "" else String(Character.toChars(codePoint))
        }
        return namedEntities[entity] ?: namedEntities[entity.lowercase(Locale.ROOT)]
    }

    private val skippedSubtreeTags = setOf("head", "style", "script", "noscript")

    private val blockSeparatorTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "tr", "td", "th", "br", "hr",
        "section", "article", "header", "footer", "blockquote",
        "pre", "dt", "dd", "title", "figcaption", "nav"
    )

    private val namedEntities = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
        "shy" to "",
        "lsquo" to "‘",
        "rsquo" to "’",
        "sbquo" to "‚",
        "ldquo" to "“",
        "rdquo" to "”",
        "bdquo" to "„",
        "laquo" to "«",
        "raquo" to "»",
        "mdash" to "—",
        "ndash" to "–",
        "minus" to "−",
        "hellip" to "…",
        "bull" to "•",
        "middot" to "·",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "sect" to "§",
        "para" to "¶",
        "dagger" to "†",
        "Dagger" to "‡",
        "eacute" to "é",
        "Eacute" to "É",
        "egrave" to "è",
        "Egrave" to "È",
        "ecirc" to "ê",
        "Ecirc" to "Ê",
        "euml" to "ë",
        "aacute" to "á",
        "Aacute" to "Á",
        "agrave" to "à",
        "Agrave" to "À",
        "acirc" to "â",
        "auml" to "ä",
        "Auml" to "Ä",
        "iacute" to "í",
        "igrave" to "ì",
        "icirc" to "î",
        "iuml" to "ï",
        "oacute" to "ó",
        "ograve" to "ò",
        "ocirc" to "ô",
        "ouml" to "ö",
        "Ouml" to "Ö",
        "uacute" to "ú",
        "ugrave" to "ù",
        "ucirc" to "û",
        "uuml" to "ü",
        "Uuml" to "Ü",
        "ntilde" to "ñ",
        "Ntilde" to "Ñ",
        "ccedil" to "ç",
        "Ccedil" to "Ç",
        "szlig" to "ß",
        "aelig" to "æ",
        "AElig" to "Æ",
        "oelig" to "œ",
        "OElig" to "Œ"
    )
}
