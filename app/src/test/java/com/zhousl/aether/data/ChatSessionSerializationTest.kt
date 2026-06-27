package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.ChatSession
import com.zhousl.aether.ui.MessageAuthor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChatSessionSerializationTest {
    @Test
    fun sessionRoundTripsThroughPerSessionJson() {
        val session = ChatSession(
            id = "session-1",
            title = "test session",
            preview = "hello",
            hasCustomTitle = true,
            messages = listOf(
                ChatMessage(
                    id = "u1",
                    author = MessageAuthor.User,
                    text = "hello",
                    createdAtMillis = 1000L,
                ),
                ChatMessage(
                    id = "a1",
                    author = MessageAuthor.Agent,
                    text = "how can I help?",
                    createdAtMillis = 1001L,
                    tokenUsage = TokenUsage(promptTokens = 12, completionTokens = 8, totalTokens = 20),
                ),
            ),
            selectedModelKey = "openai:gpt",
            lastOpenedAtMillis = 1234L,
            lastActivityAtMillis = 5678L,
        )

        val json = session.toJson()
        val restored = parseChatSessionObject(json)

        assertEquals(session.id, restored.id)
        assertEquals(session.title, restored.title)
        assertEquals(true, restored.hasCustomTitle)
        assertEquals(2, restored.messages.size)
        assertEquals("openai:gpt", restored.selectedModelKey)
        assertEquals(1234L, restored.lastOpenedAtMillis)
        assertEquals(5678L, restored.lastActivityAtMillis)

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

    @Test
    fun missingRecencyFieldsDefaultToZero() {
        val restored = parseChatSessionObject(
            JSONObject(
                "{\"id\":\"s2\",\"title\":\"t\",\"preview\":\"p\",\"messages\":[]}"
            )
        )
        assertEquals(0L, restored.lastOpenedAtMillis)
        assertEquals(0L, restored.lastActivityAtMillis)
    }

    @Test
    fun missingEnabledToolGroupsDefaultToAllGroups() {
        val restored = parseChatSessionObject(
            JSONObject(
                "{\"id\":\"s3\",\"title\":\"t\",\"preview\":\"p\",\"messages\":[]}"
            )
        )

        assertEquals(ChatToolGroups.DefaultEnabled, restored.enabledToolGroups)
    }

    @Test
    fun missingPlanModeDefaultsToFalse() {
        val restored = parseChatSessionObject(
            JSONObject(
                "{\"id\":\"plan-missing\",\"title\":\"t\",\"preview\":\"p\",\"messages\":[]}"
            )
        )

        assertEquals(false, restored.planModeEnabled)
    }

    @Test
    fun explicitPlanModeRoundTrips() {
        val enabledSession = ChatSession(
            id = "plan-enabled",
            title = "plan",
            preview = "",
            messages = emptyList(),
            planModeEnabled = true,
        )
        val disabledSession = ChatSession(
            id = "plan-disabled",
            title = "plan",
            preview = "",
            messages = emptyList(),
            planModeEnabled = false,
        )

        assertEquals(true, parseChatSessionObject(enabledSession.toJson()).planModeEnabled)
        assertEquals(false, parseChatSessionObject(disabledSession.toJson()).planModeEnabled)
    }

    @Test
    fun taskGoalRoundTripsForSlashGoal() {
        val session = ChatSession(
            id = "goal-session",
            title = "goal",
            preview = "",
            messages = emptyList(),
            taskState = AgentTaskState(
                goal = "Finish slash commands",
                status = AgentTaskStatus.InProgress,
                summary = "Goal set from /goal.",
                updatedAtMillis = 99L,
            ),
        )

        val restored = parseChatSessionObject(session.toJson())

        assertEquals("Finish slash commands", restored.taskState.goal)
        assertEquals(AgentTaskStatus.InProgress, restored.taskState.status)
        assertEquals("Goal set from /goal.", restored.taskState.summary)
        assertEquals(99L, restored.taskState.updatedAtMillis)
    }

    @Test
    fun explicitEnabledToolGroupsRoundTrip() {
        val session = ChatSession(
            id = "session-tools",
            title = "tools",
            preview = "",
            messages = emptyList(),
            enabledToolGroups = listOf(ChatToolGroups.FilesImages, ChatToolGroups.Web),
        )

        val restored = parseChatSessionObject(session.toJson())

        assertEquals(
            listOf(ChatToolGroups.FilesImages, ChatToolGroups.Web),
            restored.enabledToolGroups,
        )
    }

    @Test
    fun explicitEmptyEnabledToolGroupsArePreserved() {
        val restored = parseChatSessionObject(
            JSONObject(
                "{\"id\":\"s4\",\"title\":\"t\",\"preview\":\"p\",\"messages\":[],\"enabledToolGroups\":[]}"
            )
        )

        assertEquals(emptyList<String>(), restored.enabledToolGroups)
    }
}
