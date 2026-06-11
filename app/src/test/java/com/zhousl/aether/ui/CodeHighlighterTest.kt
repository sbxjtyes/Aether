package com.zhousl.aether.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlighterTest {
    @Test
    fun mapsCommonLanguageAliasesToDisplayNames() {
        assertEquals("Kotlin", codeFenceLanguageLabel("kt"))
        assertEquals("JavaScript", codeFenceLanguageLabel("js"))
        assertEquals("Python", codeFenceLanguageLabel("PYTHON"))
        assertEquals("C++", codeFenceLanguageLabel("cpp"))
        assertEquals("Shell", codeFenceLanguageLabel("bash"))
    }

    @Test
    fun emptyLanguageProducesEmptyLabel() {
        assertEquals("", codeFenceLanguageLabel("   "))
    }

    @Test
    fun unknownLanguageIsCapitalized() {
        assertEquals("Zig", codeFenceLanguageLabel("zig"))
    }

    @Test
    fun highlightPreservesSourceText() {
        val colors = CodeHighlightColors(
            keyword = androidx.compose.ui.graphics.Color.Red,
            string = androidx.compose.ui.graphics.Color.Green,
            comment = androidx.compose.ui.graphics.Color.Gray,
            number = androidx.compose.ui.graphics.Color.Blue,
            punctuation = androidx.compose.ui.graphics.Color.DarkGray,
            plain = androidx.compose.ui.graphics.Color.Black,
        )
        val code = "fun main() {\n  val x = 42 // 注释\n}"
        val highlighted = highlightCode(code, "kotlin", colors)
        // 着色不应改变原始字符内容，仅添加样式。
        assertEquals(code, highlighted.text)
        assertTrue(highlighted.spanStyles.isNotEmpty())
    }

    @Test
    fun veryLargeCodeSkipsHighlighting() {
        val code = buildString {
            repeat(MaxHighlightedCodeChars + 1) {
                append('a')
            }
        }

        assertTrue(!shouldHighlightCode(code))
    }
}
