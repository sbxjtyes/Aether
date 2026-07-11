package com.zhousl.aether.ui

import com.zhousl.aether.data.AgentTaskStatus
import com.zhousl.aether.data.AgentTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSlashCommandsTest {
    @Test
    fun parsesKnownCommandsWithInlineText() {
        val parsed = parseComposerSlashCommand("/plan migrate settings")

        assertEquals(ComposerSlashCommandId.Plan, parsed?.id)
        assertEquals("migrate settings", parsed?.inlineText)
    }

    @Test
    fun ignoresUnknownCommandsAndNormalText() {
        assertNull(parseComposerSlashCommand("hello /plan"))
        assertNull(parseComposerSlashCommand("/unknown thing"))
        assertNull(parseComposerSlashCommand("/"))
    }

    @Test
    fun exposesFilterQueryForSlashPopup() {
        assertEquals("", slashCommandQuery("/"))
        assertEquals("pla", slashCommandQuery("/pla"))
        assertEquals("goal", slashCommandQuery("   /goal write tests"))
        assertNull(slashCommandQuery("not a command"))
    }

    @Test
    fun goalTextSetsInProgressGoal() {
        val result = applyGoalSlashCommand(
            current = AgentTaskState(),
            inlineText = "Ship slash commands",
            nowMillis = 42L,
        )
        val updated = result.taskState

        assertEquals("Ship slash commands", updated.goal)
        assertEquals(AgentTaskStatus.InProgress, updated.status)
        assertEquals(42L, updated.updatedAtMillis)
        assertEquals(true, result.goalModeEnabled)
    }

    @Test
    fun goalPauseResumeAndClearUseExistingTaskState() {
        val current = AgentTaskState(
            goal = "Finish parity",
            status = AgentTaskStatus.InProgress,
            summary = "Working",
        )

        val paused = applyGoalSlashCommand(current, "pause", 10L)
        val resumed = applyGoalSlashCommand(paused.taskState, "resume", 11L)
        val cleared = applyGoalSlashCommand(resumed.taskState, "clear", 12L)

        assertEquals(AgentTaskStatus.WaitingForUser, paused.taskState.status)
        assertEquals(10L, paused.taskState.updatedAtMillis)
        assertEquals(false, paused.goalModeEnabled)
        assertEquals(AgentTaskStatus.InProgress, resumed.taskState.status)
        assertEquals(11L, resumed.taskState.updatedAtMillis)
        assertEquals(true, resumed.goalModeEnabled)
        assertTrue(cleared.taskState.isEmpty)
        assertEquals(false, cleared.goalModeEnabled)
    }

    @Test
    fun reviewPromptMentionsTerminalWhenDisabled() {
        val disabledPrompt = buildReviewSlashPrompt(isTerminalEnabled = false)
        val enabledPrompt = buildReviewSlashPrompt(isTerminalEnabled = true)

        assertTrue(disabledPrompt.contains("Terminal tools are currently disabled"))
        assertTrue(enabledPrompt.contains("Review the current workspace changes"))
        assertEquals(false, enabledPrompt.contains("Terminal tools are currently disabled"))
    }
}
