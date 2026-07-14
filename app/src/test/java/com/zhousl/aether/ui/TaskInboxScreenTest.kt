package com.zhousl.aether.ui

import com.zhousl.aether.data.AgentTaskStatus
import com.zhousl.aether.data.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskInboxScreenTest {
    @Test
    fun ordinaryFinishedConversationAppearsInCompletedTasks() {
        val session = conversation(isArchived = false)

        val tasks = buildTaskWorkbenchSnapshots(
            sessions = listOf(session),
            sessionExecutionStates = emptyMap(),
            unviewedCompletedSessionIds = emptySet(),
            language = AppLanguage.English,
        )

        assertEquals(1, tasks.size)
        assertEquals(AgentTaskStatus.Completed, tasks.single().effectiveStatus)
        assertTrue(tasks.single().matches(TaskWorkbenchFilter.Done))
    }

    @Test
    fun archivedConversationRemainsDiscoverableInArchivedScope() {
        val session = conversation(isArchived = true)

        assertTrue(filterSessionsByScope(listOf(session), TaskCollectionScope.Archived).contains(session))
        assertFalse(filterSessionsByScope(listOf(session), TaskCollectionScope.Active).contains(session))

        val tasks = buildTaskWorkbenchSnapshots(
            sessions = filterSessionsByScope(listOf(session), TaskCollectionScope.Archived),
            sessionExecutionStates = emptyMap(),
            unviewedCompletedSessionIds = emptySet(),
            language = AppLanguage.English,
        )
        assertEquals(session.id, tasks.single().session.id)
    }

    @Test
    fun emptyDraftDoesNotPolluteWorkbench() {
        val empty = ChatSession(
            id = "draft",
            title = "",
            preview = "",
            messages = emptyList(),
        )

        assertTrue(
            buildTaskWorkbenchSnapshots(
                sessions = listOf(empty),
                sessionExecutionStates = emptyMap(),
                unviewedCompletedSessionIds = emptySet(),
                language = AppLanguage.English,
            ).isEmpty()
        )
    }

    private fun conversation(isArchived: Boolean): ChatSession = ChatSession(
        id = "conversation-1",
        title = "Finished conversation",
        preview = "Done",
        messages = listOf(
            ChatMessage(
                id = "message-1",
                author = MessageAuthor.Agent,
                text = "Completed response",
                createdAtMillis = 100L,
            )
        ),
        lastActivityAtMillis = 100L,
        isArchived = isArchived,
    )
}
