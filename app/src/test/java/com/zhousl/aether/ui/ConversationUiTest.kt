package com.zhousl.aether.ui

import com.zhousl.aether.data.AgentTaskState
import com.zhousl.aether.data.AgentTaskStatus
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.PendingSessionInput
import com.zhousl.aether.data.SessionExecutionState
import com.zhousl.aether.data.SessionFollowUpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUiTest {
    @Test
    fun composerToolsEntryLabelSwitchesForPlanMode() {
        assertEquals("Auto", composerToolsEntryLabel(planModeSelected = false))
        assertEquals("Plan", composerToolsEntryLabel(planModeSelected = true))
    }

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
    fun taskWorkbenchSnapshotSkipsPlainConversationThreads() {
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
            language = AppLanguage.English,
        )

        assertNull(snapshot)
    }

    @Test
    fun taskWorkbenchSnapshotIncludesTaskStateThreads() {
        val session = ChatSession(
            id = "thread-task",
            title = "Task thread",
            preview = "A tracked task",
            messages = emptyList(),
            taskState = AgentTaskState(goal = "Ship the inbox cleanup"),
            lastOpenedAtMillis = 1500L,
        )

        val snapshot = session.toTaskWorkbenchSnapshot(
            executionState = null,
            isUnviewedComplete = false,
            language = AppLanguage.English,
        )

        assertEquals("Task thread", snapshot!!.conversationLabel)
        assertEquals(AgentTaskStatus.Idle, snapshot.effectiveStatus)
    }

    @Test
    fun taskWorkbenchSnapshotIncludesRuntimeSignalsWithoutTaskState() {
        val waiting = chatSession(id = "waiting-runtime", preview = "Waiting preview")
        val waitingSnapshot = waiting.toTaskWorkbenchSnapshot(
            executionState = SessionExecutionState(
                sessionId = waiting.id,
                pendingInputs = listOf(
                    PendingSessionInput(
                        id = "input-1",
                        mode = SessionFollowUpMode.Steer,
                        preview = "Need a choice",
                        attachmentCount = 0,
                    )
                ),
            ),
            isUnviewedComplete = false,
            language = AppLanguage.English,
        )
        val completed = chatSession(id = "completed-runtime", preview = "Done preview")
        val completedSnapshot = completed.toTaskWorkbenchSnapshot(
            executionState = null,
            isUnviewedComplete = true,
            language = AppLanguage.English,
        )

        assertEquals(AgentTaskStatus.WaitingForUser, waitingSnapshot!!.effectiveStatus)
        assertEquals(AgentTaskStatus.Completed, completedSnapshot!!.effectiveStatus)
    }

    @Test
    fun taskWorkbenchSortsPinnedAndWaitingThreadsFirst() {
        val pinnedDone = taskSnapshot(
            id = "pinned",
            status = AgentTaskStatus.Completed,
            pinned = true,
            activityAt = 100L,
        )
        val waiting = taskSnapshot(
            id = "waiting",
            status = AgentTaskStatus.WaitingForUser,
            activityAt = 90L,
        )
        val active = taskSnapshot(
            id = "active",
            status = AgentTaskStatus.InProgress,
            activityAt = 200L,
        )

        val sorted = sortTaskWorkbenchSnapshots(
            listOf(active, waiting, pinnedDone),
            TaskWorkbenchSortMode.NeedsAttention,
        )

        assertEquals(listOf("pinned", "waiting", "active"), sorted.map { it.session.id })
    }

    @Test
    fun taskWorkbenchSortModesRespectActivityAndExposeTwoModes() {
        val olderOpened = taskSnapshot(id = "older-opened", activityAt = 300L, openedAt = 100L)
        val recentActivity = taskSnapshot(id = "recent-activity", activityAt = 400L, openedAt = 50L)
        val recentOpened = taskSnapshot(id = "recent-opened", activityAt = 100L, openedAt = 500L)

        assertEquals(
            listOf("NeedsAttention", "RecentActivity"),
            TaskWorkbenchSortMode.entries.map { it.name },
        )
        assertEquals(
            "recent-activity",
            sortTaskWorkbenchSnapshots(
                listOf(olderOpened, recentOpened, recentActivity),
                TaskWorkbenchSortMode.RecentActivity,
            ).first().session.id,
        )
    }

    @Test
    fun taskWorkbenchScopeFiltersArchivedAndActiveThreads() {
        val active = chatSession(id = "active")
        val archived = chatSession(id = "archived", archived = true)

        assertEquals(
            listOf("active"),
            filterSessionsByScope(listOf(active, archived), TaskCollectionScope.Active).map { it.id },
        )
        assertEquals(
            listOf("archived"),
            filterSessionsByScope(listOf(active, archived), TaskCollectionScope.Archived).map { it.id },
        )
    }

    @Test
    fun taskWorkbenchActiveFilterExcludesIdleThreads() {
        val idle = taskSnapshot(id = "idle", status = AgentTaskStatus.Idle)
        val running = taskSnapshot(id = "running", status = AgentTaskStatus.InProgress)

        assertEquals(
            listOf("idle", "running"),
            filterTaskWorkbenchSnapshots(
                tasks = listOf(idle, running),
                selectedFilter = TaskWorkbenchFilter.All,
                query = "",
                searchMatches = emptyMap(),
            ).map { it.session.id },
        )
        assertEquals(
            listOf("running"),
            filterTaskWorkbenchSnapshots(
                tasks = listOf(idle, running),
                selectedFilter = TaskWorkbenchFilter.Active,
                query = "",
                searchMatches = emptyMap(),
            ).map { it.session.id },
        )
    }

    @Test
    fun taskWorkbenchStatusFiltersReturnOnlyMatchingStatuses() {
        val idle = taskSnapshot(id = "idle", status = AgentTaskStatus.Idle)
        val waiting = taskSnapshot(id = "waiting", status = AgentTaskStatus.WaitingForUser)
        val blocked = taskSnapshot(id = "blocked", status = AgentTaskStatus.Blocked)
        val running = taskSnapshot(id = "running", status = AgentTaskStatus.InProgress)
        val completed = taskSnapshot(id = "completed", status = AgentTaskStatus.Completed)
        val tasks = listOf(idle, waiting, blocked, running, completed)

        assertEquals(
            listOf("idle", "waiting", "blocked", "running", "completed"),
            filterTaskWorkbenchSnapshots(tasks, TaskWorkbenchFilter.All, "", emptyMap()).map { it.session.id },
        )
        assertEquals(
            listOf("waiting", "blocked"),
            filterTaskWorkbenchSnapshots(tasks, TaskWorkbenchFilter.NeedsYou, "", emptyMap()).map { it.session.id },
        )
        assertEquals(
            listOf("running"),
            filterTaskWorkbenchSnapshots(tasks, TaskWorkbenchFilter.Active, "", emptyMap()).map { it.session.id },
        )
        assertEquals(
            listOf("completed"),
            filterTaskWorkbenchSnapshots(tasks, TaskWorkbenchFilter.Done, "", emptyMap()).map { it.session.id },
        )
    }

    @Test
    fun taskWorkbenchFilteredStateFeedsPanelAndMainListFromSameTaskIds() {
        val waiting = taskSnapshot(id = "waiting", status = AgentTaskStatus.WaitingForUser)
        val running = taskSnapshot(id = "running", status = AgentTaskStatus.InProgress)
        val completed = taskSnapshot(id = "completed", status = AgentTaskStatus.Completed)
        val state = buildTaskWorkbenchFilteredState(
            tasks = listOf(waiting, running, completed),
            selectedFilter = TaskWorkbenchFilter.Active,
            query = "",
            searchMatches = emptyMap(),
        )

        assertEquals(listOf("running"), state.filteredTasks.map { it.session.id })
        assertEquals(1, state.attentionCount)
        assertEquals(1, state.activeCount)
        assertEquals(1, state.completedCount)
    }

    @Test
    fun taskWorkbenchSearchNarrowsAfterStatusFilter() {
        val waiting = taskSnapshot(id = "waiting", status = AgentTaskStatus.WaitingForUser)
        val running = taskSnapshot(id = "running", status = AgentTaskStatus.InProgress)
        val completed = taskSnapshot(id = "completed", status = AgentTaskStatus.Completed)
        val searchMatches = mapOf(
            waiting.session.id to TaskSearchMatch(waiting.session),
            running.session.id to TaskSearchMatch(running.session),
            completed.session.id to TaskSearchMatch(completed.session),
        )

        val activeState = buildTaskWorkbenchFilteredState(
            tasks = listOf(waiting, running, completed),
            selectedFilter = TaskWorkbenchFilter.Active,
            query = "match",
            searchMatches = searchMatches,
        )
        val doneState = buildTaskWorkbenchFilteredState(
            tasks = listOf(waiting, running, completed),
            selectedFilter = TaskWorkbenchFilter.Done,
            query = "match",
            searchMatches = searchMatches - completed.session.id,
        )

        assertEquals(listOf("running"), activeState.filteredTasks.map { it.session.id })
        assertEquals(emptyList<String>(), doneState.filteredTasks.map { it.session.id })
    }

    @Test
    fun taskWorkbenchArchiveChangeDefaultNavigationReturnsToActiveAll() {
        val defaultState = taskWorkbenchDefaultNavigationStateAfterArchiveChange()

        assertEquals(TaskCollectionScope.Active, defaultState.scope)
        assertEquals(TaskWorkbenchFilter.All, defaultState.filter)
    }

    @Test
    fun taskWorkbenchSortingDoesNotChangeFilteredMembership() {
        val waiting = taskSnapshot(id = "waiting", status = AgentTaskStatus.WaitingForUser, activityAt = 10L)
        val running = taskSnapshot(id = "running", status = AgentTaskStatus.InProgress, activityAt = 20L)
        val completed = taskSnapshot(id = "completed", status = AgentTaskStatus.Completed, activityAt = 30L)
        val tasks = listOf(completed, running, waiting)

        val focusSorted = sortTaskWorkbenchSnapshots(tasks, TaskWorkbenchSortMode.NeedsAttention)
        val recentSorted = sortTaskWorkbenchSnapshots(tasks, TaskWorkbenchSortMode.RecentActivity)

        assertEquals(
            setOf("waiting"),
            filterTaskWorkbenchSnapshots(focusSorted, TaskWorkbenchFilter.NeedsYou, "", emptyMap())
                .map { it.session.id }
                .toSet(),
        )
        assertEquals(
            setOf("waiting"),
            filterTaskWorkbenchSnapshots(recentSorted, TaskWorkbenchFilter.NeedsYou, "", emptyMap())
                .map { it.session.id }
                .toSet(),
        )
    }

    @Test
    fun taskWorkbenchSearchMatchesTaskAndMessageContent() {
        val session = chatSession(
            id = "search",
            title = "Build",
            preview = "Preview",
            taskState = AgentTaskState(
                status = AgentTaskStatus.InProgress,
                goal = "Ship compact inbox controls",
                summary = "Reduce noisy actions",
            ),
            messages = listOf(
                ChatMessage(
                    id = "agent-1",
                    author = MessageAuthor.Agent,
                    text = "The drawer now keeps details in the menu.",
                )
            ),
        )

        assertTrue(session.findTaskSearchMatch("compact inbox") != null)
        assertTrue(session.findTaskSearchMatch("details in the menu") != null)
        assertFalse(session.findTaskSearchMatch("missing phrase") != null)
    }

    @Test
    fun runningTaskSnapshotReportsInProgressForArchiveGuard() {
        val session = chatSession(id = "running")
        val snapshot = session.toTaskWorkbenchSnapshot(
            executionState = SessionExecutionState(sessionId = session.id, isRunning = true),
            isUnviewedComplete = false,
            language = AppLanguage.English,
        )

        assertEquals(AgentTaskStatus.InProgress, snapshot!!.effectiveStatus)
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

    private fun chatSession(
        id: String,
        title: String = id,
        preview: String = "",
        taskState: AgentTaskState = AgentTaskState(),
        messages: List<ChatMessage> = emptyList(),
        pinned: Boolean = false,
        archived: Boolean = false,
        openedAt: Long = 0L,
        activityAt: Long = 0L,
    ): ChatSession = ChatSession(
        id = id,
        title = title,
        preview = preview,
        messages = messages,
        taskState = taskState,
        isPinned = pinned,
        isArchived = archived,
        lastOpenedAtMillis = openedAt,
        lastActivityAtMillis = activityAt,
    )

    private fun taskSnapshot(
        id: String,
        status: AgentTaskStatus = AgentTaskStatus.Idle,
        pinned: Boolean = false,
        openedAt: Long = 0L,
        activityAt: Long = 0L,
    ): TaskWorkbenchSnapshot = chatSession(
        id = id,
        taskState = AgentTaskState(status = status, goal = id),
        pinned = pinned,
        openedAt = openedAt,
        activityAt = activityAt,
    ).toTaskWorkbenchSnapshot(
        executionState = null,
        isUnviewedComplete = false,
        language = AppLanguage.English,
    )!!
}
