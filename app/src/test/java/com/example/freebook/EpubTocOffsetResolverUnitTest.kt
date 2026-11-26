package com.example.freebook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTocOffsetResolverUnitTest {

    @Test
    fun resolvesFragmentOnOpeningHeadingTagAndSelfClosingAnchorsAccurately() {
        val coverHtml = """
            <html><head><title>Cover</title></head><body><img src="cover.jpg"/></body></html>
        """.trimIndent()

        val inlineTocHtml = """
            <html><body>
              <img src="ornament.png"/>
              <h1>Contents</h1>
              <p><a href="part1.xhtml#intro">Introduction, <i>by Jerry Hicks</i></a></p>
              <p><a href="part1.xhtml#think_rich">&bull; Think and Grow Rich</a></p>
              <p><a href="part1.xhtml#seth_speaks">&bull; Seth Speaks about Creating Your Reality</a></p>
              <p><a href="part2.xhtml#part2_start">PART II: The Universal Law of Attraction</a></p>
            </body></html>
        """.trimIndent()

        val part1DividerHtml = """
            <html><body><h1>PART I</h1></body></html>
        """.trimIndent()

        val part1BodyHtml = """
            <html><head><style>.h{text-align:center;}</style></head><body>
              <h2 class="ch" id="intro">Introduction<br/><i>by Jerry Hicks</i></h2>
              <p>Before we read Think and Grow Rich, we searched everywhere for practical answers.</p>
              <p>Many different teachers crossed our path over the years.</p>
              <a id="think_rich"/>
              <h3 class="sub">Think and Grow Rich</h3>
              <p>One afternoon I found Napoleon Hill&rsquo;s classic book waiting on a table in a small hotel lobby.</p>
              <a name="seth_speaks"></a>
              <h3 xml:id="seth_alt">Seth Speaks about &ldquo;Creating Your Reality&rdquo;</h3>
              <p>Soon afterward we discovered another transformative perspective on thoughts and emotions.</p>
            </body></html>
        """.trimIndent()

        val part2BodyHtml = """
            <html><body>
              <section id="part2_start">
                <div class="ornament"><img src="divider.png"/></div>
                <h1>PART II: The Universal Law of Attraction</h1>
                <p>That which is like unto itself is drawn in every corner of the universe.</p>
              </section>
            </body></html>
        """.trimIndent()

        // Mirrors what EpubParser import produces: cover & short "PART I" (<=15 chars) dropped,
        // <img> becomes \uFFFC ([OBJ]), entities decoded, chapters joined with "\n\n".
        val canonicalText = listOf(
            "\uFFFC\n\nContents\n\nIntroduction, by Jerry Hicks\n\n• Think and Grow Rich\n\n• Seth Speaks about Creating Your Reality\n\nPART II: The Universal Law of Attraction",
            "Introduction\nby Jerry Hicks\n\nBefore we read Think and Grow Rich, we searched everywhere for practical answers.\n\nMany different teachers crossed our path over the years.\n\nThink and Grow Rich\n\nOne afternoon I found Napoleon Hill’s classic book waiting on a table in a small hotel lobby.\n\nSeth Speaks about “Creating Your Reality”\n\nSoon afterward we discovered another transformative perspective on thoughts and emotions.",
            "\uFFFC\n\nPART II: The Universal Law of Attraction\n\nThat which is like unto itself is drawn in every corner of the universe."
        ).joinToString("\n\n")

        val index = EpubTocOffsetResolver.buildBookIndex(
            canonicalBookText = canonicalText,
            spineDocuments = listOf(
                EpubTocOffsetResolver.SpineDocument(listOf("OEBPS/Text/cover.xhtml"), coverHtml),
                EpubTocOffsetResolver.SpineDocument(listOf("OEBPS/Text/toc.xhtml"), inlineTocHtml),
                EpubTocOffsetResolver.SpineDocument(listOf("OEBPS/Text/part1_div.xhtml"), part1DividerHtml),
                EpubTocOffsetResolver.SpineDocument(listOf("OEBPS/Text/part1.xhtml"), part1BodyHtml),
                EpubTocOffsetResolver.SpineDocument(listOf("OEBPS/Text/part2.xhtml"), part2BodyHtml)
            )
        )

        // 1. Dropped "PART I" divider page jumps cleanly to the start of Part I body (NOT page 1 or inline TOC)
        val expectedIntroOffset = canonicalText.indexOf("Introduction\nby Jerry Hicks")
        val part1DividerOffset = index.resolveTocCharOffset(
            candidateHrefs = listOf("../Text/part1_div.xhtml"),
            fragmentId = null,
            tocTitle = "PART I"
        )
        assertEquals(expectedIntroOffset, part1DividerOffset)

        // 2. Fragment on <h2 id="intro"> resolves to actual heading (NOT inline Contents)
        val introOffset = index.resolveTocCharOffset(
            candidateHrefs = listOf("OEBPS/Text/part1.xhtml"),
            fragmentId = "intro",
            tocTitle = "Introduction, by Jerry Hicks"
        )
        assertEquals(expectedIntroOffset, introOffset)

        // 3. Self-closing <a id="think_rich"/> resolves to the actual heading, NOT the earlier body mention
        val expectedThinkHeading = canonicalText.lastIndexOf("Think and Grow Rich")
        val thinkOffset = index.resolveTocCharOffset(
            candidateHrefs = listOf("part1.xhtml"),
            fragmentId = "think_rich",
            tocTitle = "Think and Grow Rich"
        )
        assertEquals(expectedThinkHeading, thinkOffset)

        // 4. <a name="seth_speaks"></a> and xml:id="seth_alt" both resolve past curly quotes accurately
        val expectedSethHeading = canonicalText.lastIndexOf("Seth Speaks about")
        val sethByName = index.resolveTocCharOffset(
            candidateHrefs = listOf("/OEBPS/Text/part1.xhtml"),
            fragmentId = "seth_speaks",
            tocTitle = "Seth Speaks about Creating Your Reality"
        )
        val sethByXmlId = index.resolveTocCharOffset(
            candidateHrefs = listOf("OEBPS/Text/part1.xhtml"),
            fragmentId = "seth_alt",
            tocTitle = "Seth Speaks about Creating Your Reality"
        )
        assertEquals(expectedSethHeading, sethByName)
        assertEquals(expectedSethHeading, sethByXmlId)

        // 5. Wrapper <section id="part2_start"> with leading <img> (\uFFFC) resolves to PART II heading
        val expectedPart2 = canonicalText.lastIndexOf("PART II: The Universal Law of Attraction")
        val part2Offset = index.resolveTocCharOffset(
            candidateHrefs = listOf("OEBPS/Text/part2.xhtml"),
            fragmentId = "part2_start",
            tocTitle = "PART II: The Universal Law of Attraction"
        )
        assertEquals(expectedPart2, part2Offset)
    }

    @Test
    fun wordBoundaryMatchingNeverConfusesPartIWithPartIIOrChapter1WithChapter10() {
        val ch1Html = "<html><body><h1>Chapter 1</h1><p>This chapter previews Chapter 10 and Part II briefly.</p></body></html>"
        val ch10Html = "<html><body><h1>Chapter 10</h1><p>Tenth chapter body starts right here with unique details.</p></body></html>"
        val part2Html = "<html><body><h1>Part II</h1><p>Second major part begins cleanly here.</p></body></html>"

        val canonical = listOf(
            "Chapter 1\n\nThis chapter previews Chapter 10 and Part II briefly.",
            "Chapter 10\n\nTenth chapter body starts right here with unique details.",
            "Part II\n\nSecond major part begins cleanly here."
        ).joinToString("\n\n")

        val index = EpubTocOffsetResolver.buildBookIndex(
            canonicalBookText = canonical,
            spineDocuments = listOf(
                EpubTocOffsetResolver.SpineDocument(listOf("Text/Chapter%201.xhtml"), ch1Html),
                EpubTocOffsetResolver.SpineDocument(listOf("Text/Chapter%2010.xhtml"), ch10Html),
                EpubTocOffsetResolver.SpineDocument(listOf("Text/Part%202.xhtml"), part2Html)
            )
        )

        val ch1Offset = index.resolveTocCharOffset(listOf("Text/Chapter 1.xhtml"), null, "Chapter 1")
        val ch10Offset = index.resolveTocCharOffset(listOf("text/chapter%2010.xhtml"), null, "Chapter 10")
        val part2Offset = index.resolveTocCharOffset(listOf("Text/Part 2.xhtml"), null, "Part II")

        assertEquals(0, ch1Offset)
        assertEquals(canonical.lastIndexOf("Chapter 10"), ch10Offset)
        assertEquals(canonical.lastIndexOf("Part II"), part2Offset)
    }

    @Test
    fun resolves120NestedFragmentSubHeadingsAccuratelyAndFast() {
        val spineDocs = mutableListOf<EpubTocOffsetResolver.SpineDocument>()
        val canonicalParts = mutableListOf<String>()
        val expectedTargets = mutableListOf<Triple<String, String, String>>() // href, frag, expectedMarker

        for (ch in 1..12) {
            val htmlSb = StringBuilder()
            val textSb = StringBuilder()
            htmlSb.append("<html><body><h1>Chapter $ch Overview</h1><p>Opening notes for chapter $ch.</p>")
            textSb.append("Chapter $ch Overview\n\nOpening notes for chapter $ch.")

            for (sec in 1..10) {
                val fragId = "ch${ch}_sec${sec}"
                val marker = "Section $ch.$sec Unique Teaching"
                htmlSb.append("<h2 id=\"$fragId\">$marker</h2>")
                htmlSb.append("<p>Detailed explanation number $ch-$sec about deliberate focus and clarity. ")
                htmlSb.append("Adding realistic book paragraph body text to simulate full chapter length. ".repeat(14))
                htmlSb.append("</p>")

                textSb.append("\n\n$marker\n\n")
                textSb.append("Detailed explanation number $ch-$sec about deliberate focus and clarity. ")
                textSb.append("Adding realistic book paragraph body text to simulate full chapter length. ".repeat(14).trimEnd())

                expectedTargets.add(Triple("OEBPS/ch$ch.xhtml", fragId, marker))
            }
            htmlSb.append("</body></html>")
            spineDocs.add(EpubTocOffsetResolver.SpineDocument(listOf("OEBPS/ch$ch.xhtml"), htmlSb.toString()))
            canonicalParts.add(textSb.toString())
        }

        val canonicalText = canonicalParts.joinToString("\n\n")
        val startNs = System.nanoTime()
        val index = EpubTocOffsetResolver.buildBookIndex(canonicalText, spineDocs)

        for ((href, frag, marker) in expectedTargets) {
            val resolved = index.resolveTocCharOffset(
                candidateHrefs = listOf(href),
                fragmentId = frag,
                tocTitle = marker
            )
            val expected = canonicalText.indexOf(marker)
            assertEquals("Mismatch for $href#$frag", expected, resolved)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("Expected 120 nested fragments to resolve in < 1000ms, took ${elapsedMs}ms", elapsedMs < 1000L)
    }
}
