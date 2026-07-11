package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopPolicyTest {
    @Test
    fun defaultPolicyMatchesExistingAutonomousContinuationLimit() {
        val policy = AppSettings().agentLoopPolicy

        assertTrue(policy.autonomousContinuationEnabled)
        assertEquals(8, policy.maxAutonomousContinuationTurns)
    }

    @Test
    fun maxTurnsAreClampedAndZeroDisablesContinuation() {
        val tooHigh = normalizeAgentLoopPolicy(
            AgentLoopPolicy(
                autonomousContinuationEnabled = true,
                maxAutonomousContinuationTurns = 99,
            )
        )
        val zero = normalizeAgentLoopPolicy(
            AgentLoopPolicy(
                autonomousContinuationEnabled = true,
                maxAutonomousContinuationTurns = 0,
            )
        )

        assertEquals(16, tooHigh.maxAutonomousContinuationTurns)
        assertTrue(tooHigh.autonomousContinuationEnabled)
        assertEquals(0, zero.maxAutonomousContinuationTurns)
        assertFalse(zero.autonomousContinuationEnabled)
    }
}
