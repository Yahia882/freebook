package com.example.freebook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubRichDocumentBuilderUnitTest {

    @Test
    fun expandsSelfClosingNonVoidXhtmlTagsWithoutBreakingVoidOrSvgTags() {
        val rawBody = """
            <a id="sec_3"/>
            <div class="spacer"/>
            <h2>Heading<br/>Second Line</h2>
            <p><img src="../Images/ornament.jpg" alt="ornament"/></p>
            <svg viewBox="0 0 600 900"><image width="600" height="900" xlink:href="../Images/cover.jpg"/></svg>
        """.trimIndent()

        val sanitized = EpubRichDocumentBuilder.sanitizeSpineBodyHtml(
            bodyInnerHtml = rawBody,
            spineBaseDir = "OEBPS/Text",
            currentSpinePath = "OEBPS/Text/ch01.xhtml"
        )

        assertTrue("Expected <a/> expanded to <a></a>: $sanitized", sanitized.contains("<a id=\"sec_3\" data-r2-id=\"sec_3\"></a>"))
        assertTrue("Expected <div/> expanded to <div></div>: $sanitized", sanitized.contains("<div class=\"spacer\"></div>"))
        assertTrue("Expected <br/> kept self-closing", sanitized.contains("<br/>"))
        assertTrue("Expected relative img src resolved", sanitized.contains("src=\"OEBPS/Images/ornament.jpg\""))
        assertTrue("Expected SVG image xlink:href resolved", sanitized.contains("xlink:href=\"OEBPS/Images/cover.jpg\""))
        assertTrue("Expected SVG image href resolved", sanitized.contains("href=\"OEBPS/Images/cover.jpg\""))
    }

    @Test
    fun rewritesInternalLinksStripsScriptsAndSanitizesPublisherCss() {
        val rawCss = """
            /* Publisher stylesheet */
            @font-face {
                font-family: "BookFont";
                src: url(../Fonts/BookSerif.otf);
            }
            body {
                color: #000000;
                background-color: #ffffff;
                font-size: 12pt;
                line-height: 1.4;
                text-align: justify;
            }
            h1.title {
                color: #111111;
                font-size: 24pt;
                text-align: center;
                page-break-before: always;
            }
            p.also-by {
                text-align: center;
                font-style: italic;
                margin-bottom: 0.2em;
            }
            @media print {
                h1 { display: none; }
            }
        """.trimIndent()

        val scoped = EpubRichDocumentBuilder.sanitizeAndScopePublisherCss(
            rawCss = rawCss,
            scopeSelector = ".r2-css-0",
            cssBaseDir = "OEBPS/Styles"
        )

        // Theme-breaking colors & @media print are stripped
        assertFalse(scoped.contains("#000000"))
        assertFalse(scoped.contains("#ffffff"))
        assertFalse(scoped.contains("#111111"))
        assertFalse(scoped.contains("@media print"))
        // Relative @font-face url resolved cleanly
        assertTrue(scoped.contains("url(\"OEBPS/Fonts/BookSerif.otf\")"))
        // Heading pt size converted to proportional em (24pt / 12pt = 2.000em)
        assertTrue(scoped.contains("font-size:2.000em;"))
        // Centered titles & italic lists preserved always (even when Publisher Styles is OFF)
        assertTrue(scoped.contains(".r2-css-0 h1.title{") && scoped.contains("text-align:center;"))
        assertTrue(scoped.contains(".r2-css-0 p.also-by{") && scoped.contains("font-style:italic;"))
        // page-break-before: always mapped to multi-column page break
        assertTrue(scoped.contains("break-before:column;"))
        // Body line-height & justify gated under body.publisher-styles-on
        assertTrue(scoped.contains("body.publisher-styles-on .r2-css-0 > .r2-body{"))
        assertTrue(scoped.contains("line-height:1.4;"))
        assertTrue(scoped.contains("text-align:justify;"))
    }

    @Test
    fun buildsRichBookWithCoverPreservationInternalLinksAndCanonicalBlockOffsets() {
        val coverHtml = """
            <html><head><link rel="stylesheet" href="../Styles/book.css"/></head>
            <body><div class="cover"><img src="../Images/cover.jpg"/></div></body></html>
        """.trimIndent()

        val alsoByHtml = """
            <html><head><link rel="stylesheet" href="../Styles/book.css"/></head>
            <body>
              <h1 class="title">Also by Esther and Jerry Hicks</h1>
              <p class="also-by"><i>Ask and It Is Given</i></p>
            </body></html>
        """.trimIndent()

        val tocHtml = """
            <html><head><link rel="stylesheet" href="../Styles/book.css"/></head>
            <body>
              <script>alert('xss')</script>
              <h1 class="title">Contents</h1>
              <p><a href="part1.xhtml#think_rich" onclick="bad()">Think and Grow Rich</a></p>
              <p><a href="#local_note">Local Note</a></p>
              <p><a href="https://example.com">Publisher Website</a></p>
              <p><a href="javascript:void(0)">Unsafe Link</a></p>
            </body></html>
        """.trimIndent()

        val part1Html = """
            <html><head><link rel="stylesheet" href="../Styles/book.css"/></head>
            <body>
              <div class="chapter-wrap">
                <a id="think_rich"/>
                <h2>Think and Grow Rich</h2>
                <p>One day I discovered <i>Napoleon Hill&rsquo;s</i> inspiring book.</p>
              </div>
            </body></html>
        """.trimIndent()

        val canonicalText = listOf(
            "Also by Esther and Jerry Hicks\n\nAsk and It Is Given",
            "Contents\n\nThink and Grow Rich\n\nLocal Note\n\nPublisher Website\n\nUnsafe Link",
            "Think and Grow Rich\n\nOne day I discovered Napoleon Hill’s inspiring book."
        ).joinToString("\n\n")

        val richDoc = EpubRichDocumentBuilder.buildFromSpineItems(
            spineItems = listOf(
                EpubRichDocumentBuilder.RawSpineItem("OEBPS/Text/cover.xhtml", listOf("OEBPS/Text/cover.xhtml"), coverHtml),
                EpubRichDocumentBuilder.RawSpineItem("OEBPS/Text/alsoby.xhtml", listOf("OEBPS/Text/alsoby.xhtml"), alsoByHtml),
                EpubRichDocumentBuilder.RawSpineItem("OEBPS/Text/toc.xhtml", listOf("OEBPS/Text/toc.xhtml"), tocHtml),
                EpubRichDocumentBuilder.RawSpineItem("OEBPS/Text/part1.xhtml", listOf("OEBPS/Text/part1.xhtml"), part1Html)
            ),
            canonicalBookText = canonicalText,
            readResourceText = { path ->
                if (path == "OEBPS/Styles/book.css") {
                    ".title { text-align: center; font-size: 20pt; } .also-by { text-align: center; }"
                } else {
                    null
                }
            }
        )

        assertTrue(richDoc.hasCoverFirstPage)
        assertEquals(4, richDoc.spineCount)
        // Cover page preserved with resolved image path
        assertTrue(richDoc.sectionsHtml.contains("r2-cover-page"))
        assertTrue(richDoc.sectionsHtml.contains("src=\"OEBPS/Images/cover.jpg\""))
        // Scripts & inline onclick & javascript: URL stripped
        assertFalse(richDoc.sectionsHtml.contains("alert('xss')"))
        assertFalse(richDoc.sectionsHtml.contains("onclick="))
        assertFalse(richDoc.sectionsHtml.contains("javascript:"))
        // Internal TOC links annotated with normalized target href + fragment
        assertTrue(
            richDoc.sectionsHtml.contains(
                "data-r2-link=\"internal\" data-r2-target-href=\"OEBPS/Text/part1.xhtml\" data-r2-target-frag=\"think_rich\""
            )
        )
        assertTrue(
            richDoc.sectionsHtml.contains(
                "data-r2-link=\"internal\" data-r2-target-href=\"OEBPS/Text/toc.xhtml\" data-r2-target-frag=\"local_note\""
            )
        )
        assertTrue(richDoc.sectionsHtml.contains("data-r2-link=\"external\""))
        // Leaf block offset annotation points accurately to canonicalText for "One day I discovered..."
        val expectedParaStart = canonicalText.indexOf("One day I discovered")
        assertTrue(
            "Expected data-r2-start=\"$expectedParaStart\" in sectionsHtml",
            richDoc.sectionsHtml.contains("data-r2-block=\"1\" data-r2-start=\"$expectedParaStart\"")
        )
        // Outer wrapper <div class="chapter-wrap"> is NOT marked as a leaf block (no nested block collisions)
        assertFalse(richDoc.sectionsHtml.contains("<div class=\"chapter-wrap\" data-r2-block=\"1\""))
    }

    @Test
    fun prependsSyntheticCoverWhenFirstSpineItemHasNoCover() {
        val ch1Html = "<html><body><h1>Chapter One</h1><p>This book opens directly with prose text.</p></body></html>"
        val canonical = "Chapter One\n\nThis book opens directly with prose text."

        val richDoc = EpubRichDocumentBuilder.buildFromSpineItems(
            spineItems = listOf(
                EpubRichDocumentBuilder.RawSpineItem("ch1.xhtml", listOf("ch1.xhtml"), ch1Html)
            ),
            canonicalBookText = canonical,
            coverFallbackSrc = "Images/cover.jpg",
            readResourceText = { null }
        )

        assertTrue(richDoc.hasCoverFirstPage)
        assertEquals(2, richDoc.spineCount)
        assertTrue(richDoc.sectionsHtml.startsWith("<section class=\"r2-spine-doc r2-cover-page\""))
        assertTrue(richDoc.sectionsHtml.contains("src=\"Images/cover.jpg\""))
    }
}
