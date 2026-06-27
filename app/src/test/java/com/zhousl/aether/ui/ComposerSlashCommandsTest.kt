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
        val updated = applyGoalSlashCommand(
            current = AgentTaskState(),
            inlineText = "Ship slash commands",
            nowMillis = 42L,
        )

        assertEquals("Ship slash commands", updated.goal)
        assertEquals(AgentTaskStatus.InProgress, updated.status)
        assertEquals(42L, updated.updatedAtMillis)
    }

    @Test
    fun goalPauseResumeAndClearUseExistingTaskState() {
        val current = AgentTaskState(
            goal = "Finish parity",
            status = AgentTaskStatus.InProgress,
            summary = "Working",
        )

        val paused = applyGoalSlashCommand(current, "pause", 10L)
        val resumed = applyGoalSlashCommand(paused, "resume", 11L)
        val cleared = applyGoalSlashCommand(resumed, "clear", 12L)

        assertEquals(AgentTaskStatus.WaitingForUser, paused.status)
        assertEquals(10L, paused.updatedAtMillis)
        assertEquals(AgentTaskStatus.InProgress, resumed.status)
        assertEquals(11L, resumed.updatedAtMillis)
        assertTrue(cleared.isEmpty)
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
