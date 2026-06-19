package com.zhousl.aether.ui

import com.zhousl.aether.data.SessionExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUiTest {
    @Test
    fun taskWorkbenchSnapshotKeepsOpenedAndActivityTimesSeparate() {
        val session = ChatSession(
            id = "thread-1",
            title = "Thread",
            preview = "Preview",
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    author = MessageAuthor.User,
                    text = "Hi",
                    createdAtMillis = 1000L,
                )
            ),
            lastOpenedAtMillis = 2000L,
            lastActivityAtMillis = 3000L,
        )

        val snapshot = session.toTaskWorkbenchSnapshot(
            executionState = SessionExecutionState(sessionId = session.id, isRunning = true),
            isUnviewedComplete = false,
            language = com.zhousl.aether.data.AppLanguage.English,
        )

        assertEquals(3000L, snapshot!!.lastActivityAtMillis)
        assertEquals(2000L, snapshot.lastOpenedAtMillis)
    }

    @Test
    fun taskWorkbenchSnapshotIncludesPlainConversationThreads() {
        val session = ChatSession(
            id = "thread-plain",
            title = "Plain thread",
            preview = "A normal conversation",
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    author = MessageAuthor.User,
                    text = "Hi",
                    createdAtMillis = 1000L,
                )
            ),
            lastOpenedAtMillis = 1500L,
        )

        val snapshot = session.toTaskWorkbenchSnapshot(
            executionState = null,
            isUnviewedComplete = false,
            language = com.zhousl.aether.data.AppLanguage.English,
        )

        assertEquals("Plain thread", snapshot!!.conversationLabel)
        assertEquals(com.zhousl.aether.data.AgentTaskStatus.Idle, snapshot.effectiveStatus)
    }

    @Test
    fun pendingIndicatorShowsThinkingAfterBodyTextResetsForToolCall() {
        val previousBlocks = listOf(
            AssistantResponseBlock.Text(
                id = "text-1",
                text = "I will inspect the file first.",
            ),
            AssistantResponseBlock.ToolGroup(
                id = "tools-1",
                toolInvocations = listOf(
                    ChatToolInvocation(
                        id = "call-1",
                        toolName = "read",
                        argumentsJson = """{"path":"README.md"}""",
                        isRunning = true,
                    )
                ),
            ),
        )

        assertTrue(previousBlocks.any { it is AssistantResponseBlock.Text && it.text.isNotBlank() })
        assertEquals(
            PendingGenerationIndicator.Thinking,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesThinkingWhileBodyTextIsActive() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "Streaming body text",
                pendingStatusText = "",
            ),
        )
    }

    @Test
    fun pendingIndicatorShowsThinkingForEmptyReasoningPlaceholder() {
        assertEquals(
            PendingGenerationIndicator.Thinking,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                hasVisiblePendingReasoning = hasVisibleReasoningStatus(ReasoningTrace(id = "empty")),
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesThinkingForVisibleReasoningStatus() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                hasVisiblePendingReasoning = hasVisibleReasoningStatus(
                    ReasoningTrace(id = "reasoning", latestStatusText = "Checking"),
                ),
            ),
        )
    }

    @Test
    fun pendingIndicatorShowsStatusWhenStatusTextExists() {
        assertEquals(
            PendingGenerationIndicator.Status,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "Streaming body text",
                pendingStatusText = "Reconnecting...",
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesAfterTurnEnds() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = false,
                pendingAssistantText = "",
                pendingStatusText = "",
            ),
        )
    }

    @Test
    fun initialMessageWindowStartsNearLatestMessages() {
        assertEquals(0, initialConversationMessageStartIndex(12))
        assertEquals(60, initialConversationMessageStartIndex(100))
    }

    @Test
    fun previousMessageWindowMovesBackwardByPageSize() {
        assertEquals(36, previousConversationMessageStartIndex(60))
        assertEquals(0, previousConversationMessageStartIndex(12))
    }

    @Test
    fun clampMessageWindowStartHandlesOutOfRangeValues() {
        assertEquals(0, clampConversationMessageStartIndex(-3, 80))
        assertEquals(40, clampConversationMessageStartIndex(999, 80))
    }

    @Test
    fun reasoningTimelineKeepsSummaryAndToolsInRecordedOrder() {
        val trace = ReasoningTrace(
            id = "reasoning-1",
            chunks = listOf(
                ReasoningSummaryChunk(
                    id = "summary-1",
                    title = "Planning",
                    detail = "I am checking the input first.",
                    timelineOrder = 1,
                ),
                ReasoningSummaryChunk(
                    id = "summary-2",
                    title = "Reviewing output",
                    detail = "I should inspect the command result.",
                    timelineOrder = 3,
                ),
            ),
            toolInvocations = listOf(
                ChatToolInvocation(
                    id = "tool-1",
                    toolName = "bash",
                    argumentsJson = """{"command":"pwd"}""",
                    timelineOrder = 2,
                ),
            ),
        )

        val items = reasoningTimelineItems(trace)

        assertEquals(
            listOf("summary-1", "tool-1", "summary-2"),
            items.map { item ->
                when (item) {
                    is ReasoningTimelineItem.Summary -> item.chunk.id
                    is ReasoningTimelineItem.Tool -> item.toolInvocation.id
                }
            },
        )
    }
}
