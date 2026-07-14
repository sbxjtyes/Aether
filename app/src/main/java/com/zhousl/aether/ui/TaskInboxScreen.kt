package com.zhousl.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhousl.aether.data.AgentTaskState
import com.zhousl.aether.data.AgentTaskStatus
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.PendingSessionInput
import com.zhousl.aether.data.SessionExecutionState
import com.zhousl.aether.data.SessionFollowUpMode
import com.zhousl.aether.data.summaryText
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherBackgroundGradientTop
import com.zhousl.aether.ui.theme.AetherOnPrimary
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherScrim
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.delay
import org.json.JSONObject

internal enum class TaskWorkbenchFilter {
    All,
    NeedsYou,
    Active,
    Done,
}

internal enum class TaskCollectionScope {
    Active,
    Archived,
}

internal enum class TaskWorkbenchSortMode {
    NeedsAttention,
    RecentActivity,
}

internal data class TaskSearchMatch(
    val session: ChatSession,
    val snippet: String? = null,
)

internal data class TaskWorkbenchSnapshot(
    val session: ChatSession,
    val isPinned: Boolean,
    val goalModeEnabled: Boolean,
    val effectiveStatus: AgentTaskStatus,
    val headline: String,
    val body: String,
    val conversationLabel: String,
    val activityLabel: String,
    val activityDetail: String,
    val pendingSummary: String?,
    val progressLabel: String,
    val updatedAtMillis: Long,
    val lastOpenedAtMillis: Long,
    val lastActivityAtMillis: Long,
) {
    val priority: Int
        get() = when (effectiveStatus) {
            AgentTaskStatus.WaitingForUser -> 0
            AgentTaskStatus.Blocked -> 1
            AgentTaskStatus.InProgress -> 2
            AgentTaskStatus.Idle -> 3
            AgentTaskStatus.Completed -> 4
        }
}

private fun Set<String>.toggleSelectedTaskId(id: String): Set<String> =
    if (contains(id)) this - id else this + id

private data class TaskWorkbenchSection(
    val key: String,
    val filter: TaskWorkbenchFilter?,
    val title: String,
    val subtitle: String,
    val tasks: List<TaskWorkbenchSnapshot>,
    val overflowCount: Int = 0,
)

private val TaskWorkbenchChipHeight = 36.dp

private fun taskWorkbenchSortComparator(
    sortMode: TaskWorkbenchSortMode,
): Comparator<TaskWorkbenchSnapshot> = when (sortMode) {
    TaskWorkbenchSortMode.NeedsAttention -> compareBy<TaskWorkbenchSnapshot> { !it.isPinned }
        .thenBy { it.priority }
        .thenByDescending { it.lastActivityAtMillis }
        .thenByDescending { it.lastOpenedAtMillis }
        .thenBy { it.session.title.lowercase() }

    TaskWorkbenchSortMode.RecentActivity -> compareBy<TaskWorkbenchSnapshot> { !it.isPinned }
        .thenByDescending { it.lastActivityAtMillis }
        .thenBy { it.priority }
        .thenByDescending { it.lastOpenedAtMillis }
        .thenBy { it.session.title.lowercase() }

}

internal fun sortTaskWorkbenchSnapshots(
    tasks: List<TaskWorkbenchSnapshot>,
    sortMode: TaskWorkbenchSortMode,
): List<TaskWorkbenchSnapshot> = tasks.sortedWith(taskWorkbenchSortComparator(sortMode))

internal fun sortSessionsForWorkbench(
    sessions: List<ChatSession>,
    taskSnapshotsBySessionId: Map<String, TaskWorkbenchSnapshot>,
    sortMode: TaskWorkbenchSortMode,
): List<ChatSession> = sessions.sortedWith(
    compareBy<ChatSession> { !it.isPinned }
        .thenComparator { left, right ->
            fun activityOf(session: ChatSession): Long =
                taskSnapshotsBySessionId[session.id]?.lastActivityAtMillis ?: session.lastActivityAtMillis

            fun openedOf(session: ChatSession): Long =
                taskSnapshotsBySessionId[session.id]?.lastOpenedAtMillis ?: session.lastOpenedAtMillis

            fun priorityOf(session: ChatSession): Int =
                taskSnapshotsBySessionId[session.id]?.priority ?: Int.MAX_VALUE

            when (sortMode) {
                TaskWorkbenchSortMode.NeedsAttention -> when {
                    priorityOf(left) != priorityOf(right) -> priorityOf(left).compareTo(priorityOf(right))
                    activityOf(left) != activityOf(right) -> activityOf(right).compareTo(activityOf(left))
                    openedOf(left) != openedOf(right) -> openedOf(right).compareTo(openedOf(left))
                    else -> left.title.lowercase().compareTo(right.title.lowercase())
                }

                TaskWorkbenchSortMode.RecentActivity -> when {
                    activityOf(left) != activityOf(right) -> activityOf(right).compareTo(activityOf(left))
                    priorityOf(left) != priorityOf(right) -> priorityOf(left).compareTo(priorityOf(right))
                    openedOf(left) != openedOf(right) -> openedOf(right).compareTo(openedOf(left))
                    else -> left.title.lowercase().compareTo(right.title.lowercase())
                }

            }
        }
)

internal fun buildTaskWorkbenchSnapshots(
    sessions: List<ChatSession>,
    sessionExecutionStates: Map<String, SessionExecutionState>,
    unviewedCompletedSessionIds: Set<String>,
    language: AppLanguage,
    sortMode: TaskWorkbenchSortMode = TaskWorkbenchSortMode.NeedsAttention,
): List<TaskWorkbenchSnapshot> = sessions.mapNotNull { session ->
    session.toTaskWorkbenchSnapshot(
        executionState = sessionExecutionStates[session.id],
        isUnviewedComplete = unviewedCompletedSessionIds.contains(session.id),
        language = language,
    )
}.let { tasks ->
    sortTaskWorkbenchSnapshots(tasks, sortMode)
}

internal fun TaskWorkbenchSnapshot.matches(filter: TaskWorkbenchFilter): Boolean = when (filter) {
    TaskWorkbenchFilter.All -> true
    TaskWorkbenchFilter.NeedsYou -> effectiveStatus == AgentTaskStatus.WaitingForUser ||
        effectiveStatus == AgentTaskStatus.Blocked
    TaskWorkbenchFilter.Active -> effectiveStatus == AgentTaskStatus.InProgress
    TaskWorkbenchFilter.Done -> effectiveStatus == AgentTaskStatus.Completed
}

internal fun filterTaskWorkbenchSnapshots(
    tasks: List<TaskWorkbenchSnapshot>,
    selectedFilter: TaskWorkbenchFilter,
    query: String,
    searchMatches: Map<String, TaskSearchMatch>,
): List<TaskWorkbenchSnapshot> = tasks.filter { task ->
    task.matches(selectedFilter) &&
        (query.isBlank() || searchMatches.containsKey(task.session.id))
}

internal data class TaskWorkbenchFilteredState(
    val allTasks: List<TaskWorkbenchSnapshot>,
    val selectedFilter: TaskWorkbenchFilter,
    val query: String,
    val searchMatches: Map<String, TaskSearchMatch>,
    val filteredTasks: List<TaskWorkbenchSnapshot>,
) {
    val attentionCount: Int
        get() = allTasks.count {
            it.effectiveStatus == AgentTaskStatus.WaitingForUser ||
                it.effectiveStatus == AgentTaskStatus.Blocked
        }
    val activeCount: Int
        get() = allTasks.count { it.effectiveStatus == AgentTaskStatus.InProgress }
    val completedCount: Int
        get() = allTasks.count { it.effectiveStatus == AgentTaskStatus.Completed }
}

internal fun buildTaskWorkbenchFilteredState(
    tasks: List<TaskWorkbenchSnapshot>,
    selectedFilter: TaskWorkbenchFilter,
    query: String,
    searchMatches: Map<String, TaskSearchMatch>,
): TaskWorkbenchFilteredState = TaskWorkbenchFilteredState(
    allTasks = tasks,
    selectedFilter = selectedFilter,
    query = query,
    searchMatches = searchMatches,
    filteredTasks = filterTaskWorkbenchSnapshots(
        tasks = tasks,
        selectedFilter = selectedFilter,
        query = query,
        searchMatches = searchMatches,
    ),
)

internal fun filterSessionsByScope(
    sessions: List<ChatSession>,
    scope: TaskCollectionScope,
): List<ChatSession> = sessions.filter { session ->
    when (scope) {
        TaskCollectionScope.Active -> !session.isArchived
        TaskCollectionScope.Archived -> session.isArchived
    }
}

internal data class TaskWorkbenchNavigationState(
    val scope: TaskCollectionScope,
    val filter: TaskWorkbenchFilter,
)

internal fun taskWorkbenchDefaultNavigationStateAfterArchiveChange(): TaskWorkbenchNavigationState =
    TaskWorkbenchNavigationState(
        scope = TaskCollectionScope.Active,
        filter = TaskWorkbenchFilter.All,
    )

private fun buildTaskWorkbenchSections(
    tasks: List<TaskWorkbenchSnapshot>,
    selectedFilter: TaskWorkbenchFilter,
    query: String,
    searchMatches: Map<String, TaskSearchMatch>,
    language: AppLanguage,
): List<TaskWorkbenchSection> {
    val filteredTasks = filterTaskWorkbenchSnapshots(
        tasks = tasks,
        selectedFilter = selectedFilter,
        query = query,
        searchMatches = searchMatches,
    )
    if (filteredTasks.isEmpty()) return emptyList()

    fun titleFor(filter: TaskWorkbenchFilter): String = when (filter) {
        TaskWorkbenchFilter.All -> if (language == AppLanguage.SimplifiedChinese) "\u6240\u6709\u7ebf\u7a0b" else "All threads"
        TaskWorkbenchFilter.NeedsYou -> if (language == AppLanguage.SimplifiedChinese) "\u7b49\u4f60\u5904\u7406" else "Needs you"
        TaskWorkbenchFilter.Active -> if (language == AppLanguage.SimplifiedChinese) "\u6b63\u5728\u63a8\u8fdb" else "Active now"
        TaskWorkbenchFilter.Done -> if (language == AppLanguage.SimplifiedChinese) "\u521a\u5b8c\u6210" else "Recently done"
    }

    fun subtitleFor(filter: TaskWorkbenchFilter, count: Int): String = when (filter) {
        TaskWorkbenchFilter.All -> if (language == AppLanguage.SimplifiedChinese) {
            "$count \u4e2a\u53ef\u8ddf\u8fdb\u7ebf\u7a0b"
        } else {
            "$count tracked threads"
        }
        TaskWorkbenchFilter.NeedsYou -> if (language == AppLanguage.SimplifiedChinese) {
            "$count \u4e2a\u7ebf\u7a0b\u6b63\u5728\u7b49\u4f60\u56de\u5e94"
        } else {
            "$count threads are waiting on you"
        }
        TaskWorkbenchFilter.Active -> if (language == AppLanguage.SimplifiedChinese) {
            "$count \u4e2a\u7ebf\u7a0b\u8fd8\u5728\u63a8\u8fdb"
        } else {
            "$count threads are still moving"
        }
        TaskWorkbenchFilter.Done -> if (language == AppLanguage.SimplifiedChinese) {
            "$count \u4e2a\u7ebf\u7a0b\u6700\u8fd1\u5df2\u5b8c\u6210"
        } else {
            "$count threads recently finished"
        }
    }

    val sections = mutableListOf<TaskWorkbenchSection>()

    if (selectedFilter == TaskWorkbenchFilter.All) {
        val pinnedTasks = filteredTasks.filter { it.isPinned }
        if (pinnedTasks.isNotEmpty()) {
            sections += TaskWorkbenchSection(
                key = "Pinned",
                filter = null,
                title = if (language == AppLanguage.SimplifiedChinese) "\u5df2\u7f6e\u9876" else "Pinned",
                subtitle = if (language == AppLanguage.SimplifiedChinese) {
                    "${pinnedTasks.size} \u4e2a\u7f6e\u9876\u7ebf\u7a0b"
                } else {
                    "${pinnedTasks.size} pinned threads"
                },
                tasks = pinnedTasks.take(3),
                overflowCount = (pinnedTasks.size - 3).coerceAtLeast(0),
            )
        }
    }

    val orderedFilters = when (selectedFilter) {
        TaskWorkbenchFilter.All -> listOf(
            TaskWorkbenchFilter.NeedsYou,
            TaskWorkbenchFilter.Active,
            TaskWorkbenchFilter.Done,
        )
        else -> listOf(selectedFilter)
    }

    orderedFilters.forEach { filter ->
        val sectionTasks = filteredTasks.filter { it.matches(filter) }
        if (sectionTasks.isEmpty()) return@forEach
        val visibleTasks = when {
            selectedFilter == TaskWorkbenchFilter.All && filter == TaskWorkbenchFilter.NeedsYou -> sectionTasks.take(4)
            selectedFilter == TaskWorkbenchFilter.All -> sectionTasks.take(3)
            else -> sectionTasks
        }
        sections += TaskWorkbenchSection(
            key = filter.name,
            filter = filter,
            title = titleFor(filter),
            subtitle = subtitleFor(filter, sectionTasks.size),
            tasks = visibleTasks,
            overflowCount = (sectionTasks.size - visibleTasks.size).coerceAtLeast(0),
        )
    }
    return sections
}

internal fun ChatSession.findTaskSearchMatch(query: String): TaskSearchMatch? {
    if (title.contains(query, ignoreCase = true)) return TaskSearchMatch(this)
    if (preview.contains(query, ignoreCase = true)) return TaskSearchMatch(this, preview.toSearchSnippet(query))
    val taskText = buildString {
        append(taskState.goal)
        append('\n')
        append(taskState.summary)
        taskState.todos.forEach { item ->
            append('\n')
            append(item.text)
        }
        taskState.completionCriteria.forEach { criterion ->
            append('\n')
            append(criterion)
        }
    }
    if (taskText.contains(query, ignoreCase = true)) {
        return TaskSearchMatch(this, taskText.toSearchSnippet(query))
    }
    messages.forEach { message ->
        val text = message.text
        if (text.contains(query, ignoreCase = true)) {
            return TaskSearchMatch(this, text.toSearchSnippet(query))
        }
        message.attachments.firstOrNull { attachment ->
            attachment.name.contains(query, ignoreCase = true) ||
                attachment.workspacePath.contains(query, ignoreCase = true)
        }?.let { attachment ->
            return TaskSearchMatch(this, attachment.name.toSearchSnippet(query))
        }
        message.toolInvocations.firstOrNull { tool ->
            tool.argumentsJson.contains(query, ignoreCase = true) ||
                tool.outputJson.contains(query, ignoreCase = true)
        }?.let { tool ->
            return TaskSearchMatch(this, tool.toolName.toSearchSnippet(query))
        }
        message.reasoningTrace?.let { trace ->
            val reasoningText = buildString {
                append(trace.rawText)
                trace.chunks.forEach { chunk ->
                    append('\n')
                    append(chunk.title)
                    append('\n')
                    append(chunk.detail)
                    append('\n')
                    append(chunk.rawText)
                }
            }
            if (reasoningText.contains(query, ignoreCase = true)) {
                return TaskSearchMatch(this, reasoningText.toSearchSnippet(query))
            }
        }
    }
    return null
}

internal fun taskWorkbenchEmptyStateText(
    language: AppLanguage,
    filter: TaskWorkbenchFilter,
    query: String,
    hasAnySessions: Boolean,
    scope: TaskCollectionScope = TaskCollectionScope.Active,
): String {
    if (query.isNotBlank()) {
        return if (hasAnySessions) {
            if (language == AppLanguage.SimplifiedChinese) {
                "\u6ca1\u6709\u5339\u914d\u201c$query\u201d\u7684\u4efb\u52a1"
            } else {
                "No tasks match \"$query\"."
            }
        } else {
            if (language == AppLanguage.SimplifiedChinese) "\u8fd8\u6ca1\u6709\u4efb\u52a1" else "No tasks yet."
        }
    }

    if (scope == TaskCollectionScope.Archived) {
        return if (language == AppLanguage.SimplifiedChinese) {
            "\u5f52\u6863\u4e2d\u8fd8\u6ca1\u6709\u7ebf\u7a0b"
        } else {
            "No archived threads yet."
        }
    }

    return when (filter) {
        TaskWorkbenchFilter.All -> if (language == AppLanguage.SimplifiedChinese) {
            "\u8fd8\u6ca1\u6709\u53ef\u8ddf\u8fdb\u7684\u4efb\u52a1"
        } else {
            "No tracked tasks yet."
        }

        TaskWorkbenchFilter.NeedsYou -> if (language == AppLanguage.SimplifiedChinese) {
            "\u73b0\u5728\u6ca1\u6709\u7b49\u4f60\u5904\u7406\u7684\u4efb\u52a1"
        } else {
            "Nothing needs your attention right now."
        }

        TaskWorkbenchFilter.Active -> if (language == AppLanguage.SimplifiedChinese) {
            "\u73b0\u5728\u6ca1\u6709\u8fdb\u884c\u4e2d\u7684\u4efb\u52a1"
        } else {
            "No active tasks right now."
        }

        TaskWorkbenchFilter.Done -> if (language == AppLanguage.SimplifiedChinese) {
            "\u8fd8\u6ca1\u6709\u65b0\u5b8c\u6210\u7684\u4efb\u52a1"
        } else {
            "No recently completed tasks yet."
        }
    }
}

@Composable
internal fun TaskWorkbenchPanel(
    filteredState: TaskWorkbenchFilteredState,
    selectedScope: TaskCollectionScope,
    selectedSortMode: TaskWorkbenchSortMode,
    selectedSessionId: String?,
    onFilterSelected: (TaskWorkbenchFilter) -> Unit,
    onScopeSelected: (TaskCollectionScope) -> Unit,
    onSortModeSelected: (TaskWorkbenchSortMode) -> Unit,
    onTaskClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxVisibleTasks: Int? = null,
    showCompactTaskPreview: Boolean = false,
    hasAnySessions: Boolean = filteredState.allTasks.isNotEmpty(),
) {
    val language = rememberAetherStrings().appLanguage
    val tasks = filteredState.allTasks
    val selectedFilter = filteredState.selectedFilter
    val query = filteredState.query
    val searchMatches = filteredState.searchMatches
    val filteredTasks = filteredState.filteredTasks
    val visibleTasks = remember(filteredTasks, maxVisibleTasks) {
        if (maxVisibleTasks == null) filteredTasks else filteredTasks.take(maxVisibleTasks)
    }
    val attentionCount = filteredState.attentionCount
    val activeCount = filteredState.activeCount
    val completedCount = filteredState.completedCount

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (language == AppLanguage.SimplifiedChinese) "\u4efb\u52a1" else "Tasks",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AetherOnSurface,
            )
            Text(
                text = if (query.isBlank()) {
                    if (language == AppLanguage.SimplifiedChinese) {
                        "${tasks.size} \u4e2a\u4efb\u52a1"
                    } else {
                        "${tasks.size} tasks"
                    }
                } else {
                    if (language == AppLanguage.SimplifiedChinese) {
                        "${filteredTasks.size} \u4e2a\u7ed3\u679c"
                    } else {
                        "${filteredTasks.size} matches"
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = AetherOnSurfaceVariant,
            )
        }

        TaskWorkbenchControlRow(
            label = if (language == AppLanguage.SimplifiedChinese) "\u8303\u56f4" else "Scope",
        ) {
            TaskCollectionScopeSwitcher(
                selectedScope = selectedScope,
                language = language,
                onScopeSelected = onScopeSelected,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskWorkbenchControlLabel(
                text = if (language == AppLanguage.SimplifiedChinese) "\u72b6\u6001" else "Status",
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TaskWorkbenchFilter.entries, key = { it.name }) { filter ->
                    TaskWorkbenchFilterChip(
                        filter = filter,
                        selected = selectedFilter == filter,
                        count = when (filter) {
                            TaskWorkbenchFilter.All -> tasks.size
                            TaskWorkbenchFilter.NeedsYou -> attentionCount
                            TaskWorkbenchFilter.Active -> activeCount
                            TaskWorkbenchFilter.Done -> completedCount
                        },
                        language = language,
                        onClick = { onFilterSelected(filter) },
                    )
                }
            }
        }

        TaskWorkbenchControlRow(
            label = if (language == AppLanguage.SimplifiedChinese) "\u6392\u5e8f" else "Sort",
        ) {
            TaskWorkbenchSortSwitcher(
                selectedSortMode = selectedSortMode,
                language = language,
                onSortModeSelected = onSortModeSelected,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (attentionCount > 0) {
                TaskWorkbenchMetricChip(
                    label = if (language == AppLanguage.SimplifiedChinese) "\u5f85\u5904\u7406" else "Needs you",
                    count = attentionCount,
                    tint = Color(0xFFB26A00),
                )
            }
            if (activeCount > 0) {
                TaskWorkbenchMetricChip(
                    label = if (language == AppLanguage.SimplifiedChinese) "\u8fdb\u884c\u4e2d" else "Active",
                    count = activeCount,
                    tint = AetherPrimary,
                )
            }
            if (completedCount > 0) {
                TaskWorkbenchMetricChip(
                    label = if (language == AppLanguage.SimplifiedChinese) "\u5df2\u5b8c\u6210" else "Done",
                    count = completedCount,
                    tint = Color(0xFF2563EB),
                )
            }
        }

        if (!showCompactTaskPreview) {
            Text(
                text = if (query.isBlank()) {
                    if (language == AppLanguage.SimplifiedChinese) {
                        "${filteredTasks.size} \u4e2a\u7b5b\u9009\u7ed3\u679c"
                    } else {
                        "${filteredTasks.size} filtered tasks"
                    }
                } else {
                    if (language == AppLanguage.SimplifiedChinese) {
                        "${filteredTasks.size} \u4e2a\u641c\u7d22\u7ed3\u679c"
                    } else {
                        "${filteredTasks.size} search results"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        } else if (visibleTasks.isEmpty()) {
            Text(
                text = taskWorkbenchEmptyStateText(
                    language = language,
                    filter = selectedFilter,
                    query = query,
                    hasAnySessions = hasAnySessions,
                    scope = selectedScope,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        } else {
            visibleTasks.forEach { task ->
                TaskWorkbenchCard(
                    task = task,
                    selected = task.session.id == selectedSessionId,
                    supportingOverride = searchMatches[task.session.id]?.snippet,
                    onClick = { onTaskClick(task.session.id) },
                )
            }
        }

        if (showCompactTaskPreview && maxVisibleTasks != null && filteredTasks.size > visibleTasks.size) {
            Text(
                text = if (language == AppLanguage.SimplifiedChinese) {
                    "+${filteredTasks.size - visibleTasks.size} \u4e2a\u66f4\u591a\u4efb\u52a1"
                } else {
                    "+${filteredTasks.size - visibleTasks.size} more tasks"
                },
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskWorkbenchControlRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskWorkbenchControlLabel(text = label)
        content()
    }
}

@Composable
private fun TaskWorkbenchControlLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.widthIn(min = 34.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = AetherOnSurfaceVariant,
        maxLines = 1,
    )
}
@Composable
private fun TaskCollectionScopeSwitcher(
    selectedScope: TaskCollectionScope,
    language: AppLanguage,
    onScopeSelected: (TaskCollectionScope) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AetherSurface.copy(alpha = 0.94f)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskCollectionScope.entries.forEach { scope ->
            val selected = selectedScope == scope
            Text(
                text = when (scope) {
                    TaskCollectionScope.Active -> if (language == AppLanguage.SimplifiedChinese) "\u6d3b\u8dc3" else "Active"
                    TaskCollectionScope.Archived -> if (language == AppLanguage.SimplifiedChinese) "\u5f52\u6863" else "Archived"
                },
                modifier = Modifier
                    .height(TaskWorkbenchChipHeight)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) AetherPrimary.copy(alpha = 0.14f) else Color.Transparent
                    )
                    .clickable(onClick = { onScopeSelected(scope) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = if (selected) AetherPrimary else AetherOnSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TaskWorkbenchSortSwitcher(
    selectedSortMode: TaskWorkbenchSortMode,
    language: AppLanguage,
    onSortModeSelected: (TaskWorkbenchSortMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AetherSurface.copy(alpha = 0.94f)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskWorkbenchSortMode.entries.forEach { sortMode ->
            val selected = selectedSortMode == sortMode
            Text(
                text = when (sortMode) {
                    TaskWorkbenchSortMode.NeedsAttention ->
                        if (language == AppLanguage.SimplifiedChinese) "\u4f18\u5148" else "Focus"
                    TaskWorkbenchSortMode.RecentActivity ->
                        if (language == AppLanguage.SimplifiedChinese) "\u6700\u65b0\u52a8\u6001" else "Recent"

                },
                modifier = Modifier
                    .height(TaskWorkbenchChipHeight)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) AetherPrimary.copy(alpha = 0.14f) else Color.Transparent
                    )
                    .clickable(onClick = { onSortModeSelected(sortMode) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = if (selected) AetherPrimary else AetherOnSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun TaskWorkbenchSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    requestFocusOnStart: Boolean = false,
) {
    val strings = rememberAetherStrings()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(requestFocusOnStart) {
        if (requestFocusOnStart) {
            delay(120)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Row(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = LucideIcons.Search,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isBlank()) {
                Text(
                    text = strings.searchChats,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurface),
                cursorBrush = SolidColor(AetherOnSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        if (value.isNotBlank()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AetherSurfaceHigh)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = LucideIcons.X,
                    contentDescription = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                        "\u6e05\u9664\u641c\u7d22"
                    } else {
                        "Clear search"
                    },
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
internal fun TaskInboxScreen(
    sessions: List<ChatSession>,
    selectedSessionId: String,
    sessionExecutionStates: Map<String, SessionExecutionState>,
    unviewedCompletedSessionIds: Set<String>,
    onBack: () -> Unit,
    onTaskSelected: (String) -> Unit,
    onPinTasks: (Set<String>) -> Unit,
    onUnpinTasks: (Set<String>) -> Unit,
    onArchiveTasks: (Set<String>) -> Unit,
    onRestoreTasks: (Set<String>) -> Unit,
    onDeleteTasks: (Set<String>) -> Unit,
    onRenameTask: (String, String) -> Unit,
    onExportTask: (ChatSession) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val strings = rememberAetherStrings()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilterName by rememberSaveable { mutableStateOf(TaskWorkbenchFilter.All.name) }
    var selectedScopeName by rememberSaveable { mutableStateOf(TaskCollectionScope.Active.name) }
    var selectedSortModeName by rememberSaveable { mutableStateOf(TaskWorkbenchSortMode.NeedsAttention.name) }
    var selectedTaskIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var detailSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteTaskIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val selectedFilter = remember(selectedFilterName) {
        TaskWorkbenchFilter.entries.firstOrNull { it.name == selectedFilterName } ?: TaskWorkbenchFilter.All
    }
    val selectedScope = remember(selectedScopeName) {
        TaskCollectionScope.entries.firstOrNull { it.name == selectedScopeName } ?: TaskCollectionScope.Active
    }
    val selectedSortMode = remember(selectedSortModeName) {
        TaskWorkbenchSortMode.entries.firstOrNull { it.name == selectedSortModeName } ?: TaskWorkbenchSortMode.NeedsAttention
    }
    val visibleSessions = remember(sessions, selectedScope) {
        filterSessionsByScope(
            sessions = sessions,
            scope = selectedScope,
        )
    }
    val taskSnapshots = remember(
        visibleSessions,
        sessionExecutionStates,
        unviewedCompletedSessionIds,
        strings.appLanguage,
        selectedSortMode,
    ) {
        buildTaskWorkbenchSnapshots(
            sessions = visibleSessions,
            sessionExecutionStates = sessionExecutionStates,
            unviewedCompletedSessionIds = unviewedCompletedSessionIds,
            language = strings.appLanguage,
            sortMode = selectedSortMode,
        )
    }
    val searchMatches = remember(taskSnapshots, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            emptyMap()
        } else {
            taskSnapshots.mapNotNull { snapshot ->
                snapshot.session.findTaskSearchMatch(query)
            }.associateBy { it.session.id }
        }
    }
    val filteredState = remember(taskSnapshots, selectedFilter, searchQuery, searchMatches) {
        buildTaskWorkbenchFilteredState(
            tasks = taskSnapshots,
            selectedFilter = selectedFilter,
            query = searchQuery,
            searchMatches = searchMatches,
        )
    }
    val filteredTasks = filteredState.filteredTasks
    val detailTask = remember(detailSessionId, taskSnapshots) {
        taskSnapshots.firstOrNull { it.session.id == detailSessionId }
    }
    val detailSession = remember(detailSessionId, sessions) {
        sessions.firstOrNull { it.id == detailSessionId }
    }
    val selectableTaskIds = remember(visibleSessions) { visibleSessions.map { it.id }.toSet() }
    LaunchedEffect(selectableTaskIds) {
        selectedTaskIds = selectedTaskIds.filterTo(mutableSetOf()) { it in selectableTaskIds }
    }
    val isSelectionMode = selectedTaskIds.isNotEmpty()
    val shouldPinSelection = remember(selectedTaskIds, visibleSessions, selectedScope) {
        selectedScope == TaskCollectionScope.Active &&
            selectedTaskIds.any { id -> visibleSessions.firstOrNull { it.id == id }?.isPinned != true }
    }

    fun resetTaskWorkbenchNavigationAfterArchiveChange() {
        val defaultState = taskWorkbenchDefaultNavigationStateAfterArchiveChange()
        selectedScopeName = defaultState.scope.name
        selectedFilterName = defaultState.filter.name
        selectedTaskIds = emptySet()
        searchQuery = ""
    }

    BackHandler { onBack() }

    if (pendingDeleteTaskIds.isNotEmpty()) {
        DeleteThreadsConfirmationDialog(
            count = pendingDeleteTaskIds.size,
            language = strings.appLanguage,
            onDismissRequest = { pendingDeleteTaskIds = emptySet() },
            onConfirm = {
                val ids = pendingDeleteTaskIds
                pendingDeleteTaskIds = emptySet()
                selectedTaskIds = emptySet()
                onDeleteTasks(ids)
            },
        )
    }

    detailSession?.let { session ->
        ThreadDetailSheet(
            state = ThreadDetailSheetState(
                session = session,
                taskSnapshot = detailTask,
                isRunning = sessionExecutionStates[session.id]?.isRunning == true,
            ),
            onDismissRequest = { detailSessionId = null },
            onOpenThread = {
                detailSessionId = null
                onTaskSelected(session.id)
            },
            onRename = { title -> onRenameTask(session.id, title) },
            onTogglePinned = {
                if (session.isPinned) onUnpinTasks(setOf(session.id)) else onPinTasks(setOf(session.id))
            },
            onToggleArchived = {
                detailSessionId = null
                if (session.isArchived) onRestoreTasks(setOf(session.id)) else onArchiveTasks(setOf(session.id))
                resetTaskWorkbenchNavigationAfterArchiveChange()
            },
            onExport = {
                detailSessionId = null
                onExportTask(session)
            },
            onDelete = {
                detailSessionId = null
                pendingDeleteTaskIds = setOf(session.id)
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(AetherBackgroundGradientTop, AetherBackground, AetherSurface)
                    )
                )
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp)
                    .statusBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HeaderCircleButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = strings.back,
                        onClick = onBack,
                        size = 44.dp,
                        containerColor = AetherSurface.copy(alpha = 0.96f),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                                "\u4efb\u52a1\u5de5\u4f5c\u53f0"
                            } else {
                                "Task inbox"
                            },
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = AetherOnSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                                "\u67e5\u770b\u8fd0\u884c\u4e2d\u3001\u7b49\u4f60\u5904\u7406\u548c\u521a\u5b8c\u6210\u7684\u7ebf\u7a0b"
                            } else {
                                "Track running work, follow-ups, and recent completions."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherOnSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (isSelectionMode) {
                            InboxSelectionActionPill(
                                label = if (strings.appLanguage == AppLanguage.SimplifiedChinese) strings.cancel else "Cancel",
                                tint = AetherOnSurface,
                                onClick = { selectedTaskIds = emptySet() },
                            )
                            InboxSelectionActionPill(
                                label = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                                    if (shouldPinSelection) "\u7f6e\u9876 ${selectedTaskIds.size}" else "\u53d6\u6d88\u7f6e\u9876 ${selectedTaskIds.size}"
                                } else {
                                    if (shouldPinSelection) "Pin ${selectedTaskIds.size}" else "Unpin ${selectedTaskIds.size}"
                                },
                                tint = Color(0xFF2563EB),
                                onClick = {
                                    val ids = selectedTaskIds
                                    selectedTaskIds = emptySet()
                                    if (shouldPinSelection) {
                                        onPinTasks(ids)
                                    } else {
                                        onUnpinTasks(ids)
                                    }
                                },
                            )
                            InboxSelectionActionPill(
                                label = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                                    if (selectedScope == TaskCollectionScope.Active) "\u5f52\u6863 ${selectedTaskIds.size}" else "\u6062\u590d ${selectedTaskIds.size}"
                                } else {
                                    if (selectedScope == TaskCollectionScope.Active) "Archive ${selectedTaskIds.size}" else "Restore ${selectedTaskIds.size}"
                                },
                                tint = if (selectedScope == TaskCollectionScope.Active) Color(0xFF6D28D9) else AetherPrimary,
                                onClick = {
                                    val ids = selectedTaskIds
                                    if (selectedScope == TaskCollectionScope.Active) {
                                        onArchiveTasks(ids)
                                    } else {
                                        onRestoreTasks(ids)
                                    }
                                    resetTaskWorkbenchNavigationAfterArchiveChange()
                                },
                            )
                            InboxSelectionActionPill(
                                label = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u5220\u9664 ${selectedTaskIds.size}" else "Delete ${selectedTaskIds.size}",
                                tint = Color(0xFFB42318),
                                onClick = {
                                    pendingDeleteTaskIds = selectedTaskIds
                                },
                            )
                        }
                        HeaderCircleButton(
                            icon = LucideIcons.Settings,
                            contentDescription = strings.settings,
                            onClick = onOpenSettings,
                            size = 44.dp,
                            containerColor = AetherSurface.copy(alpha = 0.96f),
                        )
                        HeaderCircleButton(
                            icon = LucideIcons.SquarePen,
                            contentDescription = strings.newChat,
                            onClick = onNewChat,
                            size = 44.dp,
                            containerColor = AetherSurface.copy(alpha = 0.96f),
                        )
                    }
                }

                TaskWorkbenchSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    onClear = { searchQuery = "" },
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TaskWorkbenchPanel(
                        filteredState = filteredState,
                        selectedScope = selectedScope,
                        selectedSortMode = selectedSortMode,
                        selectedSessionId = if (isSelectionMode) null else selectedSessionId,
                        onFilterSelected = { filter ->
                            selectedFilterName = filter.name
                        },
                        onScopeSelected = { scope -> selectedScopeName = scope.name },
                        onSortModeSelected = { sortMode -> selectedSortModeName = sortMode.name },
                        onTaskClick = onTaskSelected,
                        hasAnySessions = visibleSessions.isNotEmpty(),
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (filteredTasks.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = taskWorkbenchEmptyStateText(
                                        language = strings.appLanguage,
                                        filter = selectedFilter,
                                        query = searchQuery,
                                        hasAnySessions = visibleSessions.isNotEmpty(),
                                        scope = selectedScope,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AetherOnSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                )
                            }
                        }
                        items(filteredTasks, key = { it.session.id }) { task ->
                            TaskWorkbenchCard(
                                task = task,
                                selected = !isSelectionMode && task.session.id == selectedSessionId,
                                selectionMode = isSelectionMode,
                                checked = selectedTaskIds.contains(task.session.id),
                                supportingOverride = searchMatches[task.session.id]?.snippet,
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedTaskIds = selectedTaskIds.toggleSelectedTaskId(task.session.id)
                                    } else {
                                        onTaskSelected(task.session.id)
                                    }
                                },
                                onLongClick = {
                                    if (isSelectionMode) {
                                        selectedTaskIds = selectedTaskIds + task.session.id
                                    } else {
                                        detailSessionId = task.session.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxSelectionActionPill(
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .height(TaskWorkbenchChipHeight)
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun DeleteThreadsConfirmationDialog(
    count: Int,
    language: AppLanguage,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = AetherSurface,
        title = {
            Text(
                text = if (language == AppLanguage.SimplifiedChinese) "\u5220\u9664\u7ebf\u7a0b\uff1f" else "Delete threads?",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = AetherOnSurface,
            )
        },
        text = {
            Text(
                text = if (language == AppLanguage.SimplifiedChinese) {
                    if (count == 1) {
                        "\u8fd9\u4e2a\u7ebf\u7a0b\u548c\u5bf9\u5e94\u7684\u5de5\u4f5c\u533a\u4f1a\u88ab\u5220\u9664\uff0c\u6b64\u64cd\u4f5c\u65e0\u6cd5\u64a4\u9500\u3002"
                    } else {
                        "\u5c06\u5220\u9664 $count \u4e2a\u7ebf\u7a0b\u548c\u5bf9\u5e94\u7684\u5de5\u4f5c\u533a\uff0c\u6b64\u64cd\u4f5c\u65e0\u6cd5\u64a4\u9500\u3002"
                    }
                } else {
                    if (count == 1) {
                        "This thread and its workspace will be deleted. This cannot be undone."
                    } else {
                        "$count threads and their workspaces will be deleted. This cannot be undone."
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB42318),
                    contentColor = Color.White,
                ),
            ) {
                Text(if (language == AppLanguage.SimplifiedChinese) "\u5220\u9664" else "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(
                    text = if (language == AppLanguage.SimplifiedChinese) "\u53d6\u6d88" else "Cancel",
                    color = AetherOnSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun TaskWorkbenchFilterChip(
    filter: TaskWorkbenchFilter,
    selected: Boolean,
    count: Int,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    val tint = when (filter) {
        TaskWorkbenchFilter.All -> AetherOnSurface
        TaskWorkbenchFilter.NeedsYou -> Color(0xFFB26A00)
        TaskWorkbenchFilter.Active -> AetherPrimary
        TaskWorkbenchFilter.Done -> Color(0xFF2563EB)
    }
    Row(
        modifier = Modifier
            .height(TaskWorkbenchChipHeight)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) tint.copy(alpha = 0.16f) else AetherSurface.copy(alpha = 0.94f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (filter) {
                TaskWorkbenchFilter.All -> if (language == AppLanguage.SimplifiedChinese) "\u5168\u90e8" else "All"
                TaskWorkbenchFilter.NeedsYou -> if (language == AppLanguage.SimplifiedChinese) "\u5f85\u5904\u7406" else "Needs you"
                TaskWorkbenchFilter.Active -> if (language == AppLanguage.SimplifiedChinese) "\u8fdb\u884c\u4e2d" else "Active"
                TaskWorkbenchFilter.Done -> if (language == AppLanguage.SimplifiedChinese) "\u5df2\u5b8c\u6210" else "Done"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) tint else AetherOnSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) tint else AetherOnSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun TaskWorkbenchMetricChip(
    label: String,
    count: Int,
    tint: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun TaskWorkbenchMetaPill(
    text: String,
    tint: Color,
) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskWorkbenchSpotlightCard(
    task: TaskWorkbenchSnapshot,
    language: AppLanguage,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val accent = taskWorkbenchStatusAccent(task.effectiveStatus)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(accent.copy(alpha = 0.10f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (language == AppLanguage.SimplifiedChinese) "\u5f53\u524d\u805a\u7126" else "In focus",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
            Text(
                text = taskWorkbenchStatusLabel(task.effectiveStatus, language),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = accent,
            )
        }
        Text(
            text = task.headline,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AetherOnSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (task.body.isNotBlank() && task.body != task.headline) {
            Text(
                text = task.body,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskWorkbenchMetaPill(
                text = task.activityLabel.ifBlank {
                    if (language == AppLanguage.SimplifiedChinese) "\u6d3b\u52a8" else "Activity"
                },
                tint = accent,
            )
            if (task.progressLabel.isNotBlank()) {
                TaskWorkbenchMetaPill(
                    text = if (language == AppLanguage.SimplifiedChinese) {
                        "\u8fdb\u5ea6 ${task.progressLabel}"
                    } else {
                        "Progress ${task.progressLabel}"
                    },
                    tint = accent.copy(alpha = 0.9f),
                )
            }
        }
        if (task.activityDetail.isNotBlank() && task.activityDetail != task.body) {
            Text(
                text = task.activityDetail,
                style = MaterialTheme.typography.bodySmall,
                color = accent.copy(alpha = 0.95f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TaskWorkbenchSectionCard(
    section: TaskWorkbenchSection,
    selectedSessionId: String?,
    selectionMode: Boolean,
    selectedTaskIds: Set<String>,
    searchMatches: Map<String, TaskSearchMatch>,
    onSectionSelected: (TaskWorkbenchFilter) -> Unit,
    onTaskSelected: (String) -> Unit,
    onTaskLongPress: (String) -> Unit,
    onTaskDetailClick: ((String) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.62f))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherOnSurface,
                    maxLines = 1,
                )
                Text(
                    text = section.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            section.filter?.let { filter ->
                HeaderCircleButton(
                    icon = LucideIcons.Cursor,
                    contentDescription = section.title,
                    onClick = { onSectionSelected(filter) },
                    size = 36.dp,
                    containerColor = AetherSurfaceHigh,
                    iconTint = AetherOnSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            section.tasks.forEach { task ->
                TaskWorkbenchCard(
                    task = task,
                    selected = task.session.id == selectedSessionId || selectedTaskIds.contains(task.session.id),
                    selectionMode = selectionMode,
                    checked = selectedTaskIds.contains(task.session.id),
                    supportingOverride = searchMatches[task.session.id]?.snippet,
                    onClick = { onTaskSelected(task.session.id) },
                    onLongClick = { onTaskLongPress(task.session.id) },
                )
            }
        }

        if (section.overflowCount > 0) {
            Text(
                text = "+${section.overflowCount} more",
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TaskWorkbenchCard(
    task: TaskWorkbenchSnapshot,
    selected: Boolean,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    supportingOverride: String? = null,
) {
    val language = rememberAetherStrings().appLanguage
    val accent = taskWorkbenchStatusAccent(task.effectiveStatus)
    val primarySupporting = supportingOverride?.takeIf { it.isNotBlank() }
        ?: task.body.takeIf { it.isNotBlank() && it != task.headline }
    val secondarySupporting = if (supportingOverride.isNullOrBlank()) {
        null
    } else {
        task.activityDetail.takeIf {
            it.isNotBlank() &&
                it != supportingOverride &&
                it != task.headline
        }
    }
    val metaParts = remember(task, language) {
        buildList {
            if (task.activityLabel.isNotBlank()) {
                add(task.activityLabel)
            }
            if (task.conversationLabel.isNotBlank() && task.conversationLabel != task.headline) {
                add(task.conversationLabel)
            }
            task.pendingSummary?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AetherSurfaceHigh else AetherSurface.copy(alpha = 0.72f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (checked) AetherPrimary else AetherSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = AetherOnPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = taskWorkbenchStatusIcon(task.effectiveStatus),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = taskWorkbenchStatusLabel(task.effectiveStatus, language),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    maxLines = 1,
                )
        if (task.progressLabel.isNotBlank()) {
            Text(
                text = task.progressLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = accent,
                maxLines = 1,
            )
        }
        if (task.isPinned) {
            TaskWorkbenchMetaPill(
                text = if (language == AppLanguage.SimplifiedChinese) "\u5df2\u7f6e\u9876" else "Pinned",
                tint = accent.copy(alpha = 0.92f),
            )
        }
    }

            Text(
                text = task.headline,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = AetherOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (!primarySupporting.isNullOrBlank()) {
                Text(
                    text = primarySupporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (supportingOverride.isNullOrBlank()) {
                        AetherOnSurfaceVariant
                    } else {
                        accent.copy(alpha = 0.90f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!secondarySupporting.isNullOrBlank()) {
                Text(
                    text = secondarySupporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent.copy(alpha = 0.90f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun ChatSession.toTaskWorkbenchSnapshot(
    executionState: SessionExecutionState?,
    isUnviewedComplete: Boolean,
    language: AppLanguage,
): TaskWorkbenchSnapshot? {
    val hasPendingInputs = executionState?.pendingInputs?.isNotEmpty() == true
    val isRunning = executionState?.isRunning == true
    val hasLoopSignal = executionState?.loopState
        ?.let { agentLoopStatusText(it, language) } != null
    val hasTaskState = !taskState.isEmpty
    // The workbench is also the recovery surface for completed and archived conversations.
    // Keep ordinary conversations after their one-shot completion notification is cleared.
    val hasConversationContent = messages.isNotEmpty()
    val hasTaskSignal = hasTaskState || isRunning || hasPendingInputs || hasLoopSignal ||
        isUnviewedComplete || hasConversationContent
    if (!hasTaskSignal) return null

    val effectiveStatus = when {
        isRunning -> AgentTaskStatus.InProgress
        taskState.status != AgentTaskStatus.Idle -> taskState.status
        hasPendingInputs -> AgentTaskStatus.WaitingForUser
        hasLoopSignal -> AgentTaskStatus.WaitingForUser
        isUnviewedComplete || hasConversationContent -> AgentTaskStatus.Completed
        else -> AgentTaskStatus.Idle
    }
    val conversationLabel = title.ifBlank {
        if (language == AppLanguage.SimplifiedChinese) "\u672a\u547d\u540d\u5bf9\u8bdd" else "Untitled chat"
    }
    val headline = taskState.goal.ifBlank {
        preview.ifBlank { conversationLabel }
    }
    val body = taskWorkbenchBodyText(taskState).ifBlank {
        preview.takeIf { it.isNotBlank() && it != headline }.orEmpty()
    }
    val recentActivity = taskWorkbenchRecentActivitySummary(
        session = this,
        executionState = executionState,
        language = language,
    )
    val progressLabel = if (taskState.todos.isNotEmpty()) {
        "${taskState.todos.count { it.done }}/${taskState.todos.size}"
    } else {
        ""
    }
    val updatedAtMillis = maxOf(
        taskState.updatedAtMillis,
        messages.lastOrNull()?.createdAtMillis ?: 0L,
        lastActivityAtMillis,
    )
    return TaskWorkbenchSnapshot(
        session = this,
        isPinned = isPinned,
        goalModeEnabled = goalModeEnabled,
        effectiveStatus = effectiveStatus,
        headline = headline,
        body = body,
        conversationLabel = conversationLabel,
        activityLabel = recentActivity.first,
        activityDetail = recentActivity.second,
        pendingSummary = taskWorkbenchPendingInputSummary(
            pendingInputs = executionState?.pendingInputs.orEmpty(),
            language = language,
        ),
        progressLabel = progressLabel,
        updatedAtMillis = updatedAtMillis,
        lastOpenedAtMillis = lastOpenedAtMillis,
        lastActivityAtMillis = updatedAtMillis,
    )
}

private fun taskWorkbenchBodyText(taskState: AgentTaskState): String =
    taskState.summary.ifBlank {
        taskState.todos.firstOrNull { !it.done }?.text.orEmpty()
    }.ifBlank {
        taskState.completionCriteria.firstOrNull().orEmpty()
    }

private fun taskWorkbenchRecentActivitySummary(
    session: ChatSession,
    executionState: SessionExecutionState?,
    language: AppLanguage,
): Pair<String, String> {
    executionState?.loopState
        ?.let { loopState -> agentLoopStatusText(loopState, language) }
        ?.let { statusText ->
            return statusText.title to statusText.detail
        }

    executionState?.pendingStatusText
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { statusText ->
            val detail = executionState.pendingStatusDetail.trim()
            return statusText to detail
        }

    val runningTool = executionState?.pendingToolInvocations
        ?.lastOrNull { it.isRunning }
        ?: executionState?.pendingToolInvocations?.lastOrNull()
    if (runningTool != null) {
        val title = taskWorkbenchToolActivityLabel(
            toolInvocation = runningTool,
            language = language,
        )
        val detail = parseTaskWorkbenchSubject(runningTool.argumentsJson)
        return title to detail
    }

    if (session.goalModeEnabled && session.taskState.goal.isNotBlank()) {
        return goalModeTaskStatusLabel(
            status = session.taskState.status,
            language = language,
        ) to session.taskState.summary
    }

    executionState?.pendingAssistantText
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { pendingText ->
            return (
                if (language == AppLanguage.SimplifiedChinese) "\u6b63\u5728\u8d77\u8349\u56de\u590d" else "Drafting reply"
            ) to pendingText.take(88)
        }

    session.messages.lastOrNull { it.author == MessageAuthor.Agent }?.let { lastAgentMessage ->
        val summary = lastAgentMessage.summaryText().trim()
        if (summary.isNotBlank()) {
            val label = if (language == AppLanguage.SimplifiedChinese) "\u6700\u8fd1\u4ea7\u51fa" else "Latest output"
            return label to summary.take(88)
        }
    }

    val fallback = session.preview.trim()
    return (
        if (language == AppLanguage.SimplifiedChinese) "\u5bf9\u8bdd" else "Conversation"
    ) to fallback
}

private fun taskWorkbenchToolActivityLabel(
    toolInvocation: ChatToolInvocation,
    language: AppLanguage,
): String {
    val toolName = toolInvocation.toolName.lowercase()
    val isRunning = toolInvocation.isRunning
    return when (toolName) {
        "bash" -> if (language == AppLanguage.SimplifiedChinese) {
            if (isRunning) "\u6b63\u5728\u8fd0\u884c bash" else "\u6700\u8fd1\u8fd0\u884c bash"
        } else {
            if (isRunning) "Running bash" else "Ran bash"
        }

        "fetch_web_url" -> if (language == AppLanguage.SimplifiedChinese) {
            if (isRunning) "\u6b63\u5728\u6293\u53d6\u7f51\u9875" else "\u6700\u8fd1\u6293\u53d6\u7f51\u9875"
        } else {
            if (isRunning) "Fetching page" else "Fetched page"
        }

        "tavily_search" -> if (language == AppLanguage.SimplifiedChinese) {
            if (isRunning) "\u6b63\u5728\u641c\u7d22" else "\u6700\u8fd1\u641c\u7d22"
        } else {
            if (isRunning) "Searching" else "Searched"
        }

        "agent_display" -> if (language == AppLanguage.SimplifiedChinese) {
            if (isRunning) "\u6b63\u5728\u64cd\u4f5c Agent \u6a21\u5f0f" else "\u6700\u8fd1\u64cd\u4f5c Agent \u6a21\u5f0f"
        } else {
            if (isRunning) "Using Agent Mode" else "Used Agent Mode"
        }

        else -> if (language == AppLanguage.SimplifiedChinese) {
            if (isRunning) "\u6b63\u5728\u4f7f\u7528 ${toolInvocation.toolName}" else "\u6700\u8fd1\u4f7f\u7528 ${toolInvocation.toolName}"
        } else {
            if (isRunning) "Using ${toolInvocation.toolName}" else "Used ${toolInvocation.toolName}"
        }
    }
}

private fun parseTaskWorkbenchSubject(argumentsJson: String): String {
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return ""
    return listOf("path", "url", "query", "command", "target", "symbol", "key")
        .firstNotNullOfOrNull { key ->
            arguments.optString(key).trim().takeIf { it.isNotBlank() }
        }
        .orEmpty()
        .take(88)
}

internal fun taskWorkbenchPendingInputSummary(
    pendingInputs: List<PendingSessionInput>,
    language: AppLanguage,
): String? {
    if (pendingInputs.isEmpty()) return null
    val queuedCount = pendingInputs.count { it.mode == SessionFollowUpMode.Queue }
    val steerCount = pendingInputs.count { it.mode == SessionFollowUpMode.Steer }
    return buildList {
        if (queuedCount > 0) {
            add(
                if (language == AppLanguage.SimplifiedChinese) {
                    "$queuedCount \u4e2a\u5df2\u6392\u961f"
                } else {
                    "$queuedCount queued"
                }
            )
        }
        if (steerCount > 0) {
            add(
                if (language == AppLanguage.SimplifiedChinese) {
                    "$steerCount \u4e2a\u5f15\u5bfc"
                } else {
                    "$steerCount steering"
                }
            )
        }
    }.joinToString(" / ")
}

private fun String.toSearchSnippet(query: String, radius: Int = 42): String {
    val normalized = replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return ""
    val index = normalized.lowercase().indexOf(query.lowercase())
    if (index < 0) return normalized.take(96)
    val start = (index - radius).coerceAtLeast(0)
    val end = (index + query.length + radius).coerceAtMost(normalized.length)
    return buildString {
        if (start > 0) append("...")
        append(normalized.substring(start, end))
        if (end < normalized.length) append("...")
    }
}

internal fun taskWorkbenchStatusLabel(
    status: AgentTaskStatus,
    language: AppLanguage,
): String = when (status) {
    AgentTaskStatus.Idle -> if (language == AppLanguage.SimplifiedChinese) "\u5df2\u51c6\u5907" else "Ready"
    AgentTaskStatus.InProgress -> if (language == AppLanguage.SimplifiedChinese) "\u8fdb\u884c\u4e2d" else "Working"
    AgentTaskStatus.WaitingForUser -> if (language == AppLanguage.SimplifiedChinese) "\u7b49\u4f60\u5904\u7406" else "Waiting for you"
    AgentTaskStatus.Completed -> if (language == AppLanguage.SimplifiedChinese) "\u5df2\u5b8c\u6210" else "Completed"
    AgentTaskStatus.Blocked -> if (language == AppLanguage.SimplifiedChinese) "\u5df2\u963b\u585e" else "Blocked"
}

@Composable
internal fun taskWorkbenchStatusAccent(status: AgentTaskStatus): Color = when (status) {
    AgentTaskStatus.Completed -> Color(0xFF2563EB)
    AgentTaskStatus.Blocked -> MaterialTheme.colorScheme.error
    AgentTaskStatus.WaitingForUser -> Color(0xFFB26A00)
    AgentTaskStatus.InProgress -> AetherPrimary
    AgentTaskStatus.Idle -> AetherOnSurfaceVariant
}

internal fun taskWorkbenchStatusIcon(status: AgentTaskStatus): ImageVector = when (status) {
    AgentTaskStatus.Completed -> Icons.Rounded.Check
    AgentTaskStatus.Blocked -> Icons.Rounded.Close
    AgentTaskStatus.WaitingForUser -> Icons.Rounded.Lightbulb
    AgentTaskStatus.InProgress -> Icons.Rounded.AutoAwesome
    AgentTaskStatus.Idle -> Icons.Rounded.AutoAwesome
}
