package com.zhousl.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.SessionExecutionState
import com.zhousl.aether.ui.theme.AetherOnPrimary
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherScrim
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.delay

private val DrawerOverlayFadeHeight = 18.dp

private fun drawerOverlayBodyGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherSurface.copy(alpha = 0.94f),
        0.20f to AetherSurface.copy(alpha = 0.86f),
        0.48f to AetherSurface.copy(alpha = 0.54f),
        0.78f to AetherSurface.copy(alpha = 0.18f),
        1.0f to Color.Transparent,
    )
)

private fun drawerOverlayTailGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherSurface.copy(alpha = 0.18f),
        0.46f to AetherSurface.copy(alpha = 0.06f),
        1.0f to Color.Transparent,
    )
)

@Composable
fun ConversationDrawer(
    sessions: List<ChatSession>,
    selectedSessionId: String,
    sessionExecutionStates: Map<String, SessionExecutionState>,
    unviewedCompletedSessionIds: Set<String>,
    onNewChat: () -> Unit,
    onInboxSelected: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onToggleSessionPinned: (String) -> Unit,
    onPinSessions: (Set<String>) -> Unit,
    onUnpinSessions: (Set<String>) -> Unit,
    onArchiveSession: (String) -> Unit,
    onArchiveSessions: (Set<String>) -> Unit,
    onUnarchiveSession: (String) -> Unit,
    onUnarchiveSessions: (Set<String>) -> Unit,
    onExportSession: (ChatSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteSessions: (Set<String>) -> Unit = { ids -> ids.forEach(onDeleteSession) },
    onSettingsSelected: () -> Unit,
) {
    val strings = rememberAetherStrings()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTaskFilterName by rememberSaveable { mutableStateOf(TaskWorkbenchFilter.All.name) }
    var selectedScopeName by rememberSaveable { mutableStateOf(TaskCollectionScope.Active.name) }
    var selectedSortModeName by rememberSaveable { mutableStateOf(TaskWorkbenchSortMode.NeedsAttention.name) }
    var selectedSessionIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var detailSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteSessionIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val overlayHeight = with(density) {
        if (overlayHeightPx > 0) overlayHeightPx.toDp() else 132.dp
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
    val selectedTaskFilter = remember(selectedTaskFilterName) {
        TaskWorkbenchFilter.entries.firstOrNull { it.name == selectedTaskFilterName } ?: TaskWorkbenchFilter.All
    }
    val taskSnapshotsBySessionId = remember(taskSnapshots) {
        taskSnapshots.associateBy { it.session.id }
    }
    val filteredTaskSnapshots = remember(taskSnapshots, selectedTaskFilter) {
        taskSnapshots.filter { it.matches(selectedTaskFilter) }
    }
    val browseSessions = remember(
        visibleSessions,
        filteredTaskSnapshots,
        selectedTaskFilter,
        taskSnapshotsBySessionId,
        selectedSortMode,
    ) {
        if (selectedTaskFilter == TaskWorkbenchFilter.All) {
            sortSessionsForWorkbench(
                sessions = visibleSessions,
                taskSnapshotsBySessionId = taskSnapshotsBySessionId,
                sortMode = selectedSortMode,
            )
        } else {
            filteredTaskSnapshots.map { it.session }
        }
    }
    val searchMatches = remember(browseSessions, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            browseSessions.map { TaskSearchMatch(it) }
        } else {
            browseSessions.mapNotNull { session -> session.findTaskSearchMatch(query) }
        }
    }
    val detailSession = remember(detailSessionId, sessions) {
        sessions.firstOrNull { it.id == detailSessionId }
    }
    val detailTask = remember(detailSessionId, taskSnapshots) {
        taskSnapshots.firstOrNull { it.session.id == detailSessionId }
    }
    val selectableSessionIds = remember(visibleSessions) { visibleSessions.map { it.id }.toSet() }
    LaunchedEffect(selectableSessionIds) {
        selectedSessionIds = selectedSessionIds.filterTo(mutableSetOf()) { it in selectableSessionIds }
    }
    val isSelectionMode = selectedSessionIds.isNotEmpty()
    val showTaskDashboard = !isSelectionMode &&
        !searchExpanded &&
        searchQuery.isBlank() &&
        taskSnapshots.isNotEmpty()

    if (pendingDeleteSessionIds.isNotEmpty()) {
        DeleteThreadsConfirmationDialog(
            count = pendingDeleteSessionIds.size,
            language = strings.appLanguage,
            onDismissRequest = { pendingDeleteSessionIds = emptySet() },
            onConfirm = {
                val ids = pendingDeleteSessionIds
                pendingDeleteSessionIds = emptySet()
                selectedSessionIds = emptySet()
                onDeleteSessions(ids)
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
                searchExpanded = false
                searchQuery = ""
                onSessionSelected(session.id)
            },
            onRename = { title -> onRenameSession(session.id, title) },
            onTogglePinned = {
                if (session.isPinned) onUnpinSessions(setOf(session.id)) else onPinSessions(setOf(session.id))
            },
            onToggleArchived = {
                detailSessionId = null
                if (session.isArchived) onUnarchiveSession(session.id) else onArchiveSession(session.id)
            },
            onExport = { onExportSession(session) },
            onDelete = {
                detailSessionId = null
                pendingDeleteSessionIds = setOf(session.id)
            },
        )
    }

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 304.dp, max = 328.dp),
        drawerContainerColor = AetherSurface,
        drawerShape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 18.dp)
        ) {
            if (searchMatches.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = overlayHeight - DrawerOverlayFadeHeight,
                            bottom = 96.dp,
                        )
                ) {
                    Text(
                        text = taskWorkbenchEmptyStateText(
                            language = strings.appLanguage,
                            filter = selectedTaskFilter,
                            query = searchQuery,
                            hasAnySessions = visibleSessions.isNotEmpty(),
                            scope = selectedScope,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = overlayHeight - DrawerOverlayFadeHeight,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(searchMatches, key = { it.session.id }) { match ->
                        val session = match.session
                        DrawerSessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            selectionMode = isSelectionMode,
                            checked = selectedSessionIds.contains(session.id),
                            searchSnippet = match.snippet,
                            taskSnapshot = taskSnapshotsBySessionId[session.id],
                            indicator = when {
                                sessionExecutionStates[session.id]?.isRunning == true -> DrawerSessionIndicator.Working
                                unviewedCompletedSessionIds.contains(session.id) -> DrawerSessionIndicator.UnviewedComplete
                                else -> DrawerSessionIndicator.None
                            },
                            onClick = {
                                if (isSelectionMode) {
                                    selectedSessionIds = selectedSessionIds.toggle(session.id)
                                } else {
                                    searchExpanded = false
                                    searchQuery = ""
                                    onSessionSelected(session.id)
                                }
                            },
                            onLongClick = {
                                if (isSelectionMode) {
                                    selectedSessionIds = selectedSessionIds.toggle(session.id)
                                } else {
                                    detailSessionId = session.id
                                }
                            },
                            onShowDetails = { detailSessionId = session.id },
                            onSelect = { selectedSessionIds = selectedSessionIds + session.id },
                            onRename = { title -> onRenameSession(session.id, title) },
                            onTogglePinned = { onToggleSessionPinned(session.id) },
                            onToggleArchived = {
                                if (session.isArchived) onUnarchiveSession(session.id) else onArchiveSession(session.id)
                            },
                            onExport = { onExportSession(session) },
                            onDelete = { pendingDeleteSessionIds = setOf(session.id) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(drawerOverlayBodyGradient())
                    .onSizeChanged { overlayHeightPx = it.height }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 18.dp)
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Aether",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = AetherOnSurface,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (isSelectionMode) {
                                HeaderCircleButton(
                                    icon = LucideIcons.X,
                                    contentDescription = strings.cancel,
                                    onClick = { selectedSessionIds = emptySet() },
                                    size = 46.dp,
                                    containerColor = AetherSurface.copy(alpha = 0.90f),
                                )
                                DrawerSelectionArchiveButton(
                                    count = selectedSessionIds.size,
                                    archiveMode = selectedScope == TaskCollectionScope.Active,
                                    onClick = {
                                        val idsToUpdate = selectedSessionIds
                                        selectedSessionIds = emptySet()
                                        if (selectedScope == TaskCollectionScope.Active) {
                                            onArchiveSessions(idsToUpdate)
                                        } else {
                                            onUnarchiveSessions(idsToUpdate)
                                        }
                                    },
                                )
                                DrawerSelectionPinButton(
                                    count = selectedSessionIds.size,
                                    pinMode = selectedScope == TaskCollectionScope.Active &&
                                        selectedSessionIds.any { id ->
                                            visibleSessions.firstOrNull { it.id == id }?.isPinned != true
                                        },
                                    onClick = {
                                        val idsToUpdate = selectedSessionIds
                                        selectedSessionIds = emptySet()
                                        val shouldPin = selectedScope == TaskCollectionScope.Active &&
                                            idsToUpdate.any { id -> visibleSessions.firstOrNull { it.id == id }?.isPinned != true }
                                        if (shouldPin) {
                                            onPinSessions(idsToUpdate)
                                        } else {
                                            onUnpinSessions(idsToUpdate)
                                        }
                                    },
                                )
                                DrawerSelectionDeleteButton(
                                    count = selectedSessionIds.size,
                                    onClick = {
                                        pendingDeleteSessionIds = selectedSessionIds
                                    },
                                )
                            }
                            HeaderCircleButton(
                                icon = LucideIcons.Cursor,
                                contentDescription = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                                    "\u4efb\u52a1\u5de5\u4f5c\u53f0"
                                } else {
                                    "Task inbox"
                                },
                                onClick = {
                                    selectedSessionIds = emptySet()
                                    searchExpanded = false
                                    searchQuery = ""
                                    onInboxSelected()
                                },
                                size = 46.dp,
                                containerColor = AetherSurface.copy(alpha = 0.90f),
                            )
                            HeaderCircleButton(
                                icon = LucideIcons.Search,
                                contentDescription = strings.search,
                                onClick = {
                                    selectedSessionIds = emptySet()
                                    if (searchExpanded || searchQuery.isNotBlank()) {
                                        searchExpanded = false
                                        searchQuery = ""
                                    } else {
                                        searchExpanded = true
                                    }
                                },
                                size = 46.dp,
                                containerColor = AetherSurface.copy(alpha = 0.90f),
                            )
                            HeaderCircleButton(
                                icon = LucideIcons.Settings,
                                contentDescription = strings.settings,
                                onClick = {
                                    selectedSessionIds = emptySet()
                                    searchExpanded = false
                                    searchQuery = ""
                                    onSettingsSelected()
                                },
                                size = 46.dp,
                                containerColor = AetherSurface.copy(alpha = 0.90f),
                            )
                        }
                    }

                    AnimatedVisibility(visible = searchExpanded || searchQuery.isNotBlank()) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            TaskWorkbenchSearchField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                onClear = { searchQuery = "" },
                                requestFocusOnStart = searchExpanded,
                            )
                        }
                    }

                    AnimatedVisibility(visible = showTaskDashboard) {
                        Column {
                            Spacer(modifier = Modifier.height(14.dp))
                            TaskWorkbenchPanel(
                                tasks = taskSnapshots,
                                selectedFilter = selectedTaskFilter,
                                selectedScope = selectedScope,
                                selectedSortMode = selectedSortMode,
                                selectedSessionId = selectedSessionId,
                                onFilterSelected = { filter ->
                                    selectedTaskFilterName =
                                        if (selectedTaskFilter == filter) TaskWorkbenchFilter.All.name else filter.name
                                },
                                onScopeSelected = { scope -> selectedScopeName = scope.name },
                                onSortModeSelected = { sortMode -> selectedSortModeName = sortMode.name },
                                onTaskClick = { sessionId ->
                                    searchExpanded = false
                                    searchQuery = ""
                                    onSessionSelected(sessionId)
                                },
                                onTaskDetailClick = { sessionId -> detailSessionId = sessionId },
                                maxVisibleTasks = 3,
                                hasAnySessions = visibleSessions.isNotEmpty(),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(if (searchExpanded || searchQuery.isNotBlank()) 10.dp else 12.dp))
                }

                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DrawerOverlayFadeHeight)
                        .background(drawerOverlayTailGradient())
                )
            }

            DrawerFloatingChatButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 18.dp),
                onClick = {
                    selectedSessionIds = emptySet()
                    searchExpanded = false
                    searchQuery = ""
                    onNewChat()
                },
            )
        }
    }
}

private enum class DrawerSessionIndicator {
    None,
    Working,
    UnviewedComplete,
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (contains(id)) this - id else this + id

@Composable
private fun DrawerSelectionPinButton(
    count: Int,
    pinMode: Boolean,
    onClick: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val tint = Color(0xFF2563EB)
    Row(
        modifier = Modifier
            .height(46.dp)
            .shadow(12.dp, RoundedCornerShape(999.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                if (pinMode) "\u7f6e\u9876 $count" else "\u53d6\u6d88\u7f6e\u9876 $count"
            } else {
                if (pinMode) "Pin $count" else "Unpin $count"
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun DrawerSelectionArchiveButton(
    count: Int,
    archiveMode: Boolean,
    onClick: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val tint = if (archiveMode) Color(0xFF6D28D9) else AetherPrimary
    Row(
        modifier = Modifier
            .height(46.dp)
            .shadow(12.dp, RoundedCornerShape(999.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                if (archiveMode) "\u5f52\u6863 $count" else "\u6062\u590d $count"
            } else {
                if (archiveMode) "Archive $count" else "Restore $count"
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun DrawerSelectionDeleteButton(
    count: Int,
    onClick: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = Modifier
            .height(46.dp)
            .shadow(12.dp, RoundedCornerShape(999.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFFFEDEA))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = LucideIcons.Trash2,
            contentDescription = null,
            tint = Color(0xFFB42318),
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u5220\u9664 $count" else "Delete $count",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = Color(0xFFB42318),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    session: ChatSession,
    selected: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    searchSnippet: String?,
    taskSnapshot: TaskWorkbenchSnapshot?,
    indicator: DrawerSessionIndicator,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShowDetails: () -> Unit,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onToggleArchived: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val detailTint = taskSnapshot?.let { snapshot ->
        taskWorkbenchStatusAccent(snapshot.effectiveStatus)
    } ?: AetherOnSurfaceVariant
    var menuExpanded by remember { mutableStateOf(false) }
    var isRenaming by remember { mutableStateOf(false) }
    var renameFieldHadFocus by remember { mutableStateOf(false) }
    var renameFocusRequest by remember { mutableIntStateOf(0) }
    var titleValue by remember(session.id, session.title) { mutableStateOf(session.title.ifBlank { strings.newChat }) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun commitRename() {
        val trimmed = titleValue.trim()
        if (trimmed.isNotBlank() && trimmed != session.title) {
            onRename(trimmed)
        }
        isRenaming = false
        keyboardController?.hide()
    }

    LaunchedEffect(renameFocusRequest, isRenaming) {
        if (renameFocusRequest > 0 && isRenaming) {
            delay(260)
            if (isRenaming) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(if (selected) AetherSurfaceHigh else Color.Transparent)
                .then(
                    if (isRenaming) {
                        Modifier
                    } else {
                        Modifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = {
                                if (selectionMode) {
                                    onLongClick()
                                } else {
                                    menuExpanded = true
                                }
                            },
                        )
                    }
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(20.dp)
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
            if (isRenaming) {
                BasicTextField(
                    value = titleValue,
                    onValueChange = { titleValue = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = AetherOnSurface,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(AetherOnSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitRename() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                renameFieldHadFocus = true
                            } else if (renameFieldHadFocus && isRenaming) {
                                commitRename()
                            }
                        },
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title.ifBlank { strings.newChat },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = AetherOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (session.isPinned) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u5df2\u7f6e\u9876" else "Pinned",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = AetherPrimary,
                            maxLines = 1,
                        )
                    }
                    val supportingLine = searchSnippet
                        ?: taskSnapshot?.activityDetail?.takeIf { it.isNotBlank() }
                        ?: taskSnapshot?.headline?.takeIf { it.isNotBlank() && it != session.title }
                        ?: taskSnapshot?.body
                    if (!supportingLine.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = supportingLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (searchSnippet.isNullOrBlank() && taskSnapshot != null) {
                                taskWorkbenchStatusAccent(taskSnapshot.effectiveStatus).copy(alpha = 0.88f)
                            } else {
                                AetherOnSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!selectionMode) {
                        Spacer(modifier = Modifier.height(6.dp))
                        DrawerSessionQuickActionPill(
                            label = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u8be6\u60c5" else "Details",
                            tint = detailTint,
                            onClick = onShowDetails,
                        )
                    }
                }
            }
            if (indicator != DrawerSessionIndicator.None) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (indicator) {
                                DrawerSessionIndicator.Working -> Color(0xFF22C55E)
                                DrawerSessionIndicator.UnviewedComplete -> Color(0xFF3B82F6)
                                DrawerSessionIndicator.None -> Color.Transparent
                            }
                        )
                )
            }
        }

        DrawerSessionActionMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            onSelect = {
                menuExpanded = false
                onSelect()
            },
            onShowDetails = {
                menuExpanded = false
                onShowDetails()
            },
            onRename = {
                menuExpanded = false
                titleValue = session.title.ifBlank { strings.newChat }
                renameFieldHadFocus = false
                isRenaming = true
                renameFocusRequest += 1
            },
            onTogglePinned = {
                menuExpanded = false
                onTogglePinned()
            },
            isPinned = session.isPinned,
            onToggleArchived = {
                menuExpanded = false
                onToggleArchived()
            },
            isArchived = session.isArchived,
            onExport = {
                menuExpanded = false
                onExport()
            },
            onDelete = {
                menuExpanded = false
                onDelete()
            },
        )
    }
}

@Composable
private fun DrawerSessionActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onSelect: () -> Unit,
    onShowDetails: () -> Unit,
    onRename: () -> Unit,
    onTogglePinned: () -> Unit,
    isPinned: Boolean,
    onToggleArchived: () -> Unit,
    isArchived: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val menuVisibility = remember { MutableTransitionState(false) }
    menuVisibility.targetState = expanded
    if (!menuVisibility.currentState && !menuVisibility.targetState) return

    BackHandler(enabled = expanded) { onDismissRequest() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 34),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visibleState = menuVisibility,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 188.dp, max = 220.dp)
                    .shadow(18.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AetherSurface)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DrawerSessionActionRow(
                    if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u9009\u62e9" else "Select",
                    onSelect,
                )
                DrawerSessionActionRow(
                    if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u8be6\u60c5" else "Details",
                    onShowDetails,
                )
                DrawerSessionActionRow(strings.rename, onRename)
                DrawerSessionActionRow(
                    if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                        if (isPinned) "\u53d6\u6d88\u7f6e\u9876" else "\u7f6e\u9876"
                    } else {
                        if (isPinned) "Unpin" else "Pin"
                    },
                    onTogglePinned,
                )
                DrawerSessionActionRow(
                    if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                        if (isArchived) "\u79fb\u51fa\u5f52\u6863" else "\u5f52\u6863"
                    } else {
                        if (isArchived) "Restore" else "Archive"
                    },
                    onToggleArchived,
                )
                DrawerSessionActionRow(strings.export, onExport)
                DrawerSessionActionRow(strings.delete, onDelete, destructive = true)
            }
        }
    }
}

@Composable
private fun DrawerSessionQuickActionPill(
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DrawerSessionActionRow(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (destructive) Color(0xFFB42318) else AetherOnSurface,
        )
    }
}

@Composable
private fun DrawerFloatingChatButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = modifier
            .shadow(18.dp, RoundedCornerShape(999.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF7A4DFF), Color(0xFF925BFF)),
                )
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = LucideIcons.SquarePen,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = strings.chat,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
        )
    }
}

@Composable
internal fun HeaderCircleButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    containerColor: Color = Color.White,
    iconTint: Color = AetherOnSurface,
) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(12.dp, CircleShape, ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(CircleShape)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            iconPainter != null -> Icon(
                painter = iconPainter,
                contentDescription = contentDescription,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
            )

            icon != null -> Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
            )
        }
    }
}
