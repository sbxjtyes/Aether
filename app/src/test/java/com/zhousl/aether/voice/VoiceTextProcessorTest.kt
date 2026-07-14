package com.zhousl.aether.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextProcessorTest {
    @Test fun removesNonSpeechMarkdown() {
        val result = VoiceTextProcessor.toSpeechText("# 标题\n正文 [链接](https://x.test)\n```kotlin\nprintln(1)\n```\nhttps://x.test/a")
        assertTrue(result.contains("标题"))
        assertTrue(result.contains("正文 链接"))
        assertFalse(result.contains("println"))
        assertFalse(result.contains("https"))
    }

    @Test fun splitsLongTextWithoutLosingContent() {
        val source = "第一句话。第二句话很长很长。第三句话。第四句话也很长很长。第五句话。"
        val chunks = VoiceTextProcessor.split(source, maxChars = 20)
        assertTrue(chunks.size >= 2)
        assertEquals(source, chunks.joinToString(""))
    }
}
