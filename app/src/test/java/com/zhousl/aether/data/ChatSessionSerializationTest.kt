package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.ChatSession
import com.zhousl.aether.ui.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChatSessionSerializationTest {
    @Test
    fun sessionRoundTripsThroughPerSessionJson() {
        val session = ChatSession(
            id = "session-1",
            title = "测试会话",
            preview = "你好",
            hasCustomTitle = true,
            messages = listOf(
                ChatMessage(
                    id = "u1",
                    author = MessageAuthor.User,
                    text = "你好",
                    createdAtMillis = 1000L,
                ),
                ChatMessage(
                    id = "a1",
                    author = MessageAuthor.Agent,
                    text = "你好，我能帮你什么？",
                    createdAtMillis = 1001L,
                    tokenUsage = TokenUsage(promptTokens = 12, completionTokens = 8, totalTokens = 20),
                ),
            ),
            selectedModelKey = "openai:gpt",
        )

        // 序列化为单会话 JSON，再用新的对象解析器读回。
        val json = session.toJson()
        val restored = parseChatSessionObject(json)

        assertEquals(session.id, restored.id)
        assertEquals(session.title, restored.title)
        assertEquals(true, restored.hasCustomTitle)
        assertEquals(2, restored.messages.size)
        assertEquals("openai:gpt", restored.selectedModelKey)

        val restoredUsage = restored.messages.last().tokenUsage
        assertNotNull(restoredUsage)
        assertEquals(12, restoredUsage!!.promptTokens)
        assertEquals(8, restoredUsage.completionTokens)
        assertEquals(20, restoredUsage.totalTokens)
    }

    @Test
    fun shortToolOutputIsNotTruncated() {
        val short = "ok"
        assertEquals(short, truncatePersistedToolOutput(short))
    }

    @Test
    fun oversizedToolOutputIsTruncatedWithMarker() {
        val big = "x".repeat(30_000)
        val truncated = truncatePersistedToolOutput(big)
        assertEquals(true, truncated.length < big.length)
        assertEquals(true, truncated.contains("已截断"))
    }

    @Test
    fun legacyArrayParsingStillWorks() {
        val raw = parseChatSessions(
            "[" + ChatSession(
                id = "s1",
                title = "t",
                preview = "p",
                messages = emptyList(),
            ).toJson().toString() + "]"
        )
        assertEquals(1, raw.size)
        assertEquals("s1", raw.first().id)
    }
}
