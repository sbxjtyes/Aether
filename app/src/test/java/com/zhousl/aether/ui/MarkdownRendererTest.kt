package com.zhousl.aether.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test(timeout = 1_000L)
    fun parseMarkdownTreatsIncompleteTableLineAsParagraph() {
        val blocks = parseMarkdownBlocks("| Effort | Share |")

        assertEquals(1, blocks.size)
        assertEquals("Paragraph", blocks.single()?.javaClass?.simpleName)
    }

    @Test(timeout = 1_000L)
    fun parseMarkdownKeepsPipeTextInsideParagraphUntilTableIsComplete() {
        val blocks = parseMarkdownBlocks(
            """
            The stream may contain a partial table next.
            | Effort | Share |
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        assertEquals("Paragraph", blocks.single()?.javaClass?.simpleName)
    }

    @Test(timeout = 1_000L)
    fun parseMarkdownStillParsesCompleteTables() {
        val blocks = parseMarkdownBlocks(
            """
            | Effort | Share |
            |--------|-------|
            | high   | 80%   |
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        assertEquals("Table", blocks.single()?.javaClass?.simpleName)
    }

    @Test
    fun markdownTableColumnWidthsFitTwoColumnTablesIntoViewport() {
        val widths = markdownTableColumnWidths(columnCount = 2, viewportWidth = 320.dp)

        assertEquals(2, widths.size)
        assertEquals(320.dp, widths.reduce { total, width -> total + width })
        assertTrue(widths[1] > widths[0])
    }

    @Test
    fun markdownTableColumnWidthsKeepManyColumnTablesScrollable() {
        val widths = markdownTableColumnWidths(columnCount = 5, viewportWidth = 320.dp)

        assertEquals(5, widths.size)
        assertTrue(widths.reduce { total, width -> total + width } > 320.dp)
    }

    @Test
    fun parseMarkdownImageSupportsOptionalTitleAndAngleWrappedUrls() {
        val image = parseMarkdownImage(
            "![Example](<https://example.com/assets/diagram(v2).svg> \"A title\")"
        )

        requireNotNull(image)
        assertEquals("Example", image.altText)
        assertEquals("https://example.com/assets/diagram(v2).svg", image.url)
    }

    @Test
    fun normalizeMarkdownImageUrlDecodesCommonHtmlEscapes() {
        assertEquals(
            "https://example.com/image.png?foo=1&bar=2",
            normalizeMarkdownImageUrl("https://example.com/image.png?foo=1&amp;bar=2"),
        )
    }

    @Test
    fun extractMarkdownLinkDestinationStopsBeforeOptionalTitle() {
        assertEquals(
            "https://example.com/a(b)c.png",
            extractMarkdownLinkDestination("https://example.com/a(b)c.png 'preview'"),
        )
    }

    @Test
    fun inferMarkdownImageMimeTypeDetectsSvgFromBytes() {
        val mimeType = inferMarkdownImageMimeType(
            reportedMimeType = null,
            rawUrl = "https://example.com/logo",
            bytes = "<svg viewBox=\"0 0 10 10\"></svg>".toByteArray(),
        )

        assertEquals("image/svg+xml", mimeType)
    }

    @Test
    fun inferMarkdownImageMimeTypeNormalizesSvgMimeVariants() {
        val mimeType = inferMarkdownImageMimeType(
            reportedMimeType = "image/svg",
            rawUrl = "/tmp/preview",
            bytes = ByteArray(0),
        )

        assertEquals("image/svg+xml", mimeType)
    }

    @Test
    fun sanitizeInlineMarkdownSvgRemovesScriptAndJavascriptHandlers() {
        val sanitized = sanitizeInlineMarkdownSvg(
            """
            <?xml version="1.0"?>
            <svg onclick="alert(1)" viewBox="0 0 10 10">
                <script>alert(1)</script>
                <a href="javascript:alert(1)"><rect width="10" height="10" /></a>
            </svg>
            """.trimIndent(),
        )

        assertTrue(sanitized.startsWith("<svg"))
        assertFalse(sanitized.contains("<script", ignoreCase = true))
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun buildMarkdownInlineSvgHtmlEmbedsSvgWithoutPreviewImageAlt() {
        val html = buildMarkdownInlineSvgHtml("<svg viewBox=\"0 0 10 10\"></svg>")

        assertTrue(html.contains("<svg viewBox=\"0 0 10 10\"></svg>"))
        assertFalse(html.contains("alt=\"preview\""))
    }

    @Test
    fun parseMarkdownImageRejectsBlankDestinations() {
        assertNull(parseMarkdownImage("![Example]()"))
    }

    @Test
    fun parseMarkdownImageReadsMediaLayoutAttributes() {
        val image = parseMarkdownImage(
            "![Example](https://example.com/image.png){width=75% height=280 scroll=true show-all=false fit=cover}"
        )

        requireNotNull(image)
        assertEquals(MarkdownMediaWidth.Fraction(0.75f), image.layout.width)
        assertEquals(280, image.layout.heightDp)
        assertTrue(image.layout.scroll)
        assertFalse(image.layout.showAll)
        assertEquals(MarkdownMediaFit.Cover, image.layout.fit)
    }

    @Test
    fun parseMarkdownCodeFenceHeaderReadsMermaidAttributes() {
        val header = parseMarkdownCodeFenceHeader("```mermaid {height=420 show-all=true width=640}")
        val layout = parseMarkdownMediaLayout(
            attributes = header.attributes,
            defaults = MarkdownMediaLayout(maxHeightDp = 640),
        )

        assertEquals("mermaid", header.language)
        assertEquals(MarkdownMediaWidth.DpValue(640), layout.width)
        assertEquals(420, layout.heightDp)
        assertTrue(layout.showAll)
        assertFalse(layout.scroll)
        assertNull(layout.maxHeightDp)
    }

    @Test
    fun markdownImageSampleSizeKeepsLargeInlinePreviewBounded() {
        assertEquals(1, calculateMarkdownImageSampleSize(width = 1200, height = 900))
        assertEquals(4, calculateMarkdownImageSampleSize(width = 5000, height = 3000))
    }

    @Test
    fun normalizeMarkdownSourceRepairsCompactStockAnalysisMarkdown() {
        val blocks = parseNormalizedMarkdownBlocks(
            """
            ##科瑞技术（002957.SZ）分析![科瑞技术 K线图](https://img1.money.126.net/chart/h5/stock/kline/d/1000957.png){width=100% fit=contain}

            ---

            ###最新行情|指标 |数值 |
            |---|---|
            |最新价 | **60.15元** |
            |涨跌幅 | **+5.32%** ↑ |

            ###近期走势回顾（近一个月）
            -从41.83元一路暴涨至61.79元
            -5/26 -5/28连续三个交易日涨停
            -从61.79元回调至50.10元，跌幅约 **-18.9%**

            ###风险提示> ⚠️ **免责声明**：以上数据来源于公开行情，可能有延迟，不构成任何投资建议。
            """.trimIndent(),
        )
        val blockNames = blocks.map { requireNotNull(it).javaClass.simpleName }

        assertTrue(blockNames.contains("Heading"))
        assertTrue(blockNames.contains("Image"))
        assertTrue(blockNames.contains("Table"))
        assertTrue(blockNames.contains("UnorderedList"))
        assertTrue(blockNames.contains("Quote"))
    }

    @Test
    fun normalizeMarkdownSourceDoesNotTurnLeadingNegativeNumbersIntoLists() {
        val blocks = parseNormalizedMarkdownBlocks("-18.9% drawdown")

        assertEquals(1, blocks.size)
        assertEquals("Paragraph", requireNotNull(blocks.single()).javaClass.simpleName)
    }

    private fun parseMarkdownBlocks(markdown: String): List<*> {
        val method = Class.forName("com.zhousl.aether.ui.MarkdownRendererKt")
            .getDeclaredMethod("parseMarkdown", String::class.java)
        method.isAccessible = true
        return method.invoke(null, markdown) as List<*>
    }

    private fun parseNormalizedMarkdownBlocks(markdown: String): List<*> {
        val normalized = normalizeMarkdownSource(markdown)
        return parseMarkdownBlocks(normalized)
    }

    private fun normalizeMarkdownSource(markdown: String): String {
        val method = Class.forName("com.zhousl.aether.ui.MarkdownRendererKt")
            .getDeclaredMethod("normalizeMarkdownSource", String::class.java)
        method.isAccessible = true
        return method.invoke(null, markdown) as String
    }
}
