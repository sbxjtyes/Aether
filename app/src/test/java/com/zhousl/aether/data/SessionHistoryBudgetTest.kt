package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryBudgetTest {
    @Test
    fun shortHistoryIsNotTrimmed() {
        val messages = listOf(
            ChatMessage(id = "u1", author = MessageAuthor.User, text = "hello"),
            ChatMessage(id = "a1", author = MessageAuthor.Agent, text = "hi"),
        )

        assertEquals(messages, applyHistoryContextBudget(messages))
    }

    @Test
    fun longHistoryIsTrimmedWithSummaryPrefix() {
        val messages = buildList {
            repeat(40) { index ->
                add(
                    ChatMessage(
                        id = "u$index",
                        author = MessageAuthor.User,
                        text = "x".repeat(6_000),
                    ),
                )
                add(
                    ChatMessage(
                        id = "a$index",
                        author = MessageAuthor.Agent,
                        text = "y".repeat(6_000),
                    ),
                )
            }
        }

        val trimmed = applyHistoryContextBudget(messages)

        assertTrue(trimmed.size < messages.size)
        assertEquals("history-summary", trimmed.first().id)
        assertEquals(MessageAuthor.User, trimmed.first().author)
        assertTrue(trimmed.first().text.contains("对话历史摘要"))
        assertEquals(messages.last(), trimmed.last())
    }
}
