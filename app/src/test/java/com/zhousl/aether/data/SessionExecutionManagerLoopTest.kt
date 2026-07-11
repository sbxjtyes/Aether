package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExecutionManagerLoopTest {
    @Test
    fun continueDecisionStartsAutonomousContinuation() {
        val (nextStep, state) = decideAgentLoopNextStep(
            planModeEnabled = false,
            queuedInputAvailable = false,
            conversationDecision = AgentConversationDecision(
                status = AgentConversationStatus.Continue,
                reason = "More work remains",
                nextPrompt = "Run the next check",
            ),
            autonomousContinuationCount = 1,
            policy = AgentLoopPolicy(
                autonomousContinuationEnabled = true,
                maxAutonomousContinuationTurns = 8,
            ),
        )

        assertTrue(nextStep is AgentLoopNextStep.ContinueAutonomously)
        assertEquals(2, state.continuationIndex)
        assertEquals(8, state.maxContinuationTurns)
        assertTrue(state.isAutonomousContinuation)
        assertEquals("Run the next check", state.nextStep)
    }

    @Test
    fun queuedInputWinsOverAutonomousContinuation() {
        val (nextStep, state) = decideAgentLoopNextStep(
            planModeEnabled = false,
            queuedInputAvailable = true,
            conversationDecision = AgentConversationDecision(
                status = AgentConversationStatus.Continue,
                nextPrompt = "Keep going",
            ),
            autonomousContinuationCount = 0,
            policy = AgentLoopPolicy(),
        )

        assertEquals(AgentLoopNextStep.RunQueuedInput, nextStep)
        assertEquals(AgentLoopStopReason.QueuedInput, state.stopReason)
        assertFalse(state.isAutonomousContinuation)
    }

    @Test
    fun planModeAndDisabledPolicyStopAutonomousContinuation() {
        val decision = AgentConversationDecision(
            status = AgentConversationStatus.Continue,
            nextPrompt = "Keep going",
        )

        val (planNextStep, planState) = decideAgentLoopNextStep(
            planModeEnabled = true,
            queuedInputAvailable = false,
            conversationDecision = decision,
            autonomousContinuationCount = 0,
            policy = AgentLoopPolicy(),
        )
        val (disabledNextStep, disabledState) = decideAgentLoopNextStep(
            planModeEnabled = false,
            queuedInputAvailable = false,
            conversationDecision = decision,
            autonomousContinuationCount = 0,
            policy = AgentLoopPolicy(autonomousContinuationEnabled = false),
        )

        assertEquals(AgentLoopNextStep.Stop, planNextStep)
        assertEquals(AgentLoopStopReason.PlanMode, planState.stopReason)
        assertEquals(AgentLoopNextStep.Stop, disabledNextStep)
        assertEquals(AgentLoopStopReason.Disabled, disabledState.stopReason)
    }

    @Test
    fun statusAndLimitStopAutonomousContinuation() {
        val waiting = decideAgentLoopNextStep(
            planModeEnabled = false,
            queuedInputAvailable = false,
            conversationDecision = AgentConversationDecision(
                status = AgentConversationStatus.WaitingForUser,
                reason = "Need a choice",
            ),
            autonomousContinuationCount = 0,
            policy = AgentLoopPolicy(),
        )
        val limit = decideAgentLoopNextStep(
            planModeEnabled = false,
            queuedInputAvailable = false,
            conversationDecision = AgentConversationDecision(
                status = AgentConversationStatus.Continue,
                nextPrompt = "Keep going",
            ),
            autonomousContinuationCount = 8,
            policy = AgentLoopPolicy(maxAutonomousContinuationTurns = 8),
        )

        assertEquals(AgentLoopNextStep.Stop, waiting.first)
        assertEquals(AgentLoopStopReason.WaitingForUser, waiting.second.stopReason)
        assertEquals(AgentLoopNextStep.Stop, limit.first)
        assertEquals(AgentLoopStopReason.LimitReached, limit.second.stopReason)
    }

    @Test
    fun goalModeUsesTaskStateFallbackWhenDecisionIsMissing() {
        val (nextStep, state) = decideAgentLoopNextStep(
            planModeEnabled = false,
            goalModeEnabled = true,
            queuedInputAvailable = false,
            conversationDecision = AgentConversationDecision.completed(),
            taskState = AgentTaskState(
                goal = "Ship Goal Mode",
                status = AgentTaskStatus.InProgress,
                todos = listOf(AgentTaskItem(id = "t1", text = "Run tests")),
                summary = "Implementation started",
            ),
            autonomousContinuationCount = 0,
            policy = AgentLoopPolicy(),
        )

        assertTrue(nextStep is AgentLoopNextStep.ContinueAutonomously)
        assertTrue(state.nextStep.contains("Ship Goal Mode"))
        assertTrue(state.nextStep.contains("Run tests"))
    }

    @Test
    fun nonGoalModeDoesNotContinueFromTaskStateFallback() {
        val (nextStep, state) = decideAgentLoopNextStep(
            planModeEnabled = false,
            goalModeEnabled = false,
            queuedInputAvailable = false,
            conversationDecision = AgentConversationDecision.completed(),
            taskState = AgentTaskState(
                goal = "Residual task",
                status = AgentTaskStatus.InProgress,
            ),
            autonomousContinuationCount = 0,
            policy = AgentLoopPolicy(),
        )

        assertEquals(AgentLoopNextStep.Stop, nextStep)
        assertEquals(AgentLoopStopReason.Completed, state.stopReason)
    }
}
