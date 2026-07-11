package com.zhousl.aether.ui

import com.zhousl.aether.data.AgentLoopState
import com.zhousl.aether.data.AgentLoopStopReason
import com.zhousl.aether.data.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentLoopUiTextTest {
    @Test
    fun activeAutonomousContinuationFormatsProgress() {
        val status = agentLoopStatusText(
            loopState = AgentLoopState(
                isAutonomousContinuation = true,
                continuationIndex = 2,
                maxContinuationTurns = 8,
                nextStep = "Run tests",
            ),
            language = AppLanguage.English,
        )

        assertNotNull(status)
        assertEquals("Continuing autonomously 2/8", status!!.title)
        assertEquals("Run tests", status.detail)
    }

    @Test
    fun limitReachedFormatsChineseStatus() {
        val status = agentLoopStatusText(
            loopState = AgentLoopState(
                maxContinuationTurns = 8,
                reason = "Still active",
                stopReason = AgentLoopStopReason.LimitReached,
            ),
            language = AppLanguage.SimplifiedChinese,
        )

        assertNotNull(status)
        assertEquals("已达到自主续跑上限", status!!.title)
        assertEquals("Still active", status.detail)
    }
}
