package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherAgentConversationDecisionTest {
    @Test
    fun parsesNextPromptAliases() {
        val snakeCase = parseAgentConversationDecisionOutput(
            """{"ok":true,"status":"continue","reason":"More","next_prompt":"Run tests"}"""
        )
        val camelCase = parseAgentConversationDecisionOutput(
            """{"ok":true,"status":"continue","reason":"More","nextPrompt":"Build release"}"""
        )

        assertEquals(AgentConversationStatus.Continue, snakeCase.status)
        assertEquals("Run tests", snakeCase.nextPrompt)
        assertEquals(AgentConversationStatus.Continue, camelCase.status)
        assertEquals("Build release", camelCase.nextPrompt)
    }

    @Test
    fun derivesFallbackPromptForFrameworkContinuation() {
        val derived = deriveAgentConversationDecision(
            explicitDecision = AgentConversationDecision(
                status = AgentConversationStatus.Continue,
                reason = "Task still active",
            ),
            taskState = AgentTaskState(
                goal = "Ship loop support",
                status = AgentTaskStatus.InProgress,
                todos = listOf(
                    AgentTaskItem(id = "t1", text = "Add tests", done = false),
                ),
                summary = "Runtime is wired",
            ),
        )

        assertEquals(AgentConversationStatus.Continue, derived.status)
        assertTrue(derived.nextPrompt.contains("Ship loop support"))
        assertTrue(derived.nextPrompt.contains("Add tests"))
        assertTrue(derived.nextPrompt.contains("Runtime is wired"))
    }

    @Test
    fun nonContinueStatusesDoNotAutonomouslyContinue() {
        val waiting = deriveAgentConversationDecision(
            explicitDecision = AgentConversationDecision(
                status = AgentConversationStatus.WaitingForUser,
                reason = "Need input",
            ),
            taskState = AgentTaskState(status = AgentTaskStatus.InProgress),
        )

        assertEquals(AgentConversationStatus.WaitingForUser, waiting.status)
        assertEquals("", waiting.nextPrompt)
    }

    @Test
    fun forcesContinuationForMarketDataTransitionWithoutConclusion() {
        val shouldContinue = shouldForceMarketSynthesisContinuation(
            assistantText = "数据拿到了，我基于今天收盘位置和近一个月结构给你结论。",
            marketDataToolCalled = true,
            conversationDecision = AgentConversationDecision.completed(),
            planModeEnabled = false,
        )

        assertTrue(shouldContinue)
    }

    @Test
    fun doesNotForceContinuationForActualMarketConclusion() {
        val shouldContinue = shouldForceMarketSynthesisContinuation(
            assistantText = "结论：今天收盘卖出偏合理，因为股价收于日内低点且当日下跌4.79%，短线风险仍高。",
            marketDataToolCalled = true,
            conversationDecision = AgentConversationDecision.completed(),
            planModeEnabled = false,
        )

        assertEquals(false, shouldContinue)
    }

    @Test
    fun doesNotForceContinuationWithoutMarketToolOrWhileWaiting() {
        val withoutTool = shouldForceMarketSynthesisContinuation(
            assistantText = "数据拿到了，下面给你结论。",
            marketDataToolCalled = false,
            conversationDecision = AgentConversationDecision.completed(),
            planModeEnabled = false,
        )
        val waiting = shouldForceMarketSynthesisContinuation(
            assistantText = "数据拿到了，下面给你结论。",
            marketDataToolCalled = true,
            conversationDecision = AgentConversationDecision(
                status = AgentConversationStatus.WaitingForUser,
            ),
            planModeEnabled = false,
        )

        assertEquals(false, withoutTool)
        assertEquals(false, waiting)
    }
}
