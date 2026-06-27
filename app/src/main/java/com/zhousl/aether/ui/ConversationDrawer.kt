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
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
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

private data class DrawerSearchMatch(
    val session: ChatSession,
    val snippet: String? = null,
)

private fun ChatSession.findDrawerSearchMatch(query: String): DrawerSearchMatch? {
    if (title.contains(query, ignoreCase = true)) return DrawerSearchMatch(this)
    if (preview.contains(query, ignoreCase = true)) return DrawerSearchMatch(this, preview.toDrawerSearchSnippet(query))
    messages.forEach { message ->
        val text = message.text
        if (text.contains(query, ignoreCase = true)) {
            return DrawerSearchMatch(this, text.toDrawerSearchSnippet(query))
        }
        message.attachments.firstOrNull { attachment ->
            attachment.name.contains(query, ignoreCase = true) ||
                attachment.workspacePath.contains(query, ignoreCase = true)
        }?.let { attachment ->
            val attachmentText = attachment.name.ifBlank { attachment.workspacePath }
            return DrawerSearchMatch(this, attachmentText.toDrawerSearchSnippet(query))
        }
        message.toolInvocations.firstOrNull { tool ->
            tool.toolName.contains(query, ignoreCase = true) ||
                tool.argumentsJson.contains(query, ignoreCase = true) ||
                tool.outputJson.contains(query, ignoreCase = true)
        }?.let { tool ->
            val toolText = buildString {
                append(tool.toolName)
                append('\n')
                append(tool.argumentsJson)
                append('\n')
                append(tool.outputJson)
            }
            return DrawerSearchMatch(this, toolText.toDrawerSearchSnippet(query))
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
                trace.toolInvocations.forEach { tool ->
                    append('\n')
                    append(tool.toolName)
                    append('\n')
                    append(tool.argumentsJson)
                    append('\n')
                    append(tool.outputJson)
                }
            }
            if (reasoningText.contains(query, ignoreCase = true)) {
                return DrawerSearchMatch(this, reasoningText.toDrawerSearchSnippet(query))
            }
        }
    }
    return null
}

private fun String.toDrawerSearchSnippet(query: String, radius: Int = 42): String {
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

private fun drawerEmptyStateText(
    language: AppLanguage,
    query: String,
    noConversationsText: String,
): String {
    if (query.isBlank()) return noConversationsText
    return if (language == AppLanguage.SimplifiedChinese) {
        "\u6ca1\u6709\u5339\u914d\u201c$query\u201d\u7684\u5bf9\u8bdd"
    } else {
        "No conversations match \"$query\"."
    }
}

@Composable
fun ConversationDrawer(
    sessions: List<ChatSession>,
    selectedSessionId: String,
    onNewChat: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onToggleSessionPinned: (String) -> Unit,
    onPinSessions: (Set<String>) -> Unit,
    onUnpinSessions: (Set<String>) -> Unit,
    onArchiveSession: (String) -> Unit,
    onArchiveSessions: (Set<String>) -> Unit,
    onExportSession: (ChatSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteSessions: (Set<String>) -> Unit = { ids -> ids.forEach(onDeleteSession) },
    onSettingsSelected: () -> Unit,
) {
    val strings = rememberAetherStrings()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSessionIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var detailSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteSessionIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val overlayHeight = with(density) {
        if (overlayHeightPx > 0) overlayHeightPx.toDp() else 132.dp
    }
    val visibleSessions = remember(sessions) {
        sessions.filterNot { it.isArchived }
    }
    val searchMatches = remember(visibleSessions, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            visibleSessions.map { DrawerSearchMatch(it) }
        } else {
            visibleSessions.mapNotNull { session -> session.findDrawerSearchMatch(query) }
        }
    }
    val detailSession = remember(detailSessionId, sessions) {
        sessions.firstOrNull { it.id == detailSessionId }
    }
    val selectableSessionIds = remember(visibleSessions) { visibleSessions.map { it.id }.toSet() }
    LaunchedEffect(selectableSessionIds) {
        selectedSessionIds = selectedSessionIds.filterTo(mutableSetOf()) { it in selectableSessionIds }
    }
    val isSelectionMode = selectedSessionIds.isNotEmpty()

    fun resetDrawerNavigationAfterArchiveChange() {
        selectedSessionIds = emptySet()
        searchExpanded = false
        searchQuery = ""
    }

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
                onArchiveSession(session.id)
                resetDrawerNavigationAfterArchiveChange()
            },
            onExport = {
                detailSessionId = null
                onExportSession(session)
            },
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
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = drawerEmptyStateText(
                                language = strings.appLanguage,
                                query = searchQuery,
                                noConversationsText = strings.noConversationsYet,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                    }
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
                                onArchiveSession(session.id)
                                resetDrawerNavigationAfterArchiveChange()
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
                                val selectedShouldPin = selectedSessionIds.any { id ->
                                    visibleSessions.firstOrNull { it.id == id }?.isPinned != true
                                }
                                HeaderCircleButton(
                                    icon = LucideIcons.X,
                                    contentDescription = strings.cancel,
                                    onClick = { selectedSessionIds = emptySet() },
                                    size = 46.dp,
                                    containerColor = AetherSurface.copy(alpha = 0.90f),
                                )
                                DrawerSelectionCircleButton(
                                    count = selectedSessionIds.size,
                                    icon = Icons.Rounded.Archive,
                                    contentDescription = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u5f52\u6863" else "Archive",
                                    tint = Color(0xFF6D28D9),
                                    onClick = {
                                        val idsToUpdate = selectedSessionIds
                                        onArchiveSessions(idsToUpdate)
                                        resetDrawerNavigationAfterArchiveChange()
                                    },
                                )
                                DrawerSelectionCircleButton(
                                    count = selectedSessionIds.size,
                                    icon = if (selectedShouldPin) Icons.Rounded.PushPin else Icons.Rounded.Close,
                                    contentDescription = if (selectedShouldPin) {
                                        if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u7f6e\u9876" else "Pin"
                                    } else {
                                        if (strings.appLanguage == AppLanguage.SimplifiedChinese) "\u53d6\u6d88\u7f6e\u9876" else "Unpin"
                                    },
                                    tint = Color(0xFF2563EB),
                                    onClick = {
                                        val idsToUpdate = selectedSessionIds
                                        selectedSessionIds = emptySet()
                                        if (selectedShouldPin) {
                                            onPinSessions(idsToUpdate)
                                        } else {
                                            onUnpinSessions(idsToUpdate)
                                        }
                                    },
                                )
                                DrawerSelectionCircleButton(
                                    count = selectedSessionIds.size,
                                    icon = LucideIcons.Trash2,
                                    contentDescription = strings.delete,
                                    tint = Color(0xFFB42318),
                                    onClick = {
                                        pendingDeleteSessionIds = selectedSessionIds
                                    },
                                )
                            }
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
                            DrawerSearchField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                onClear = { searchQuery = "" },
                                requestFocusOnStart = searchExpanded,
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

private fun Set<String>.toggle(id: String): Set<String> =
    if (contains(id)) this - id else this + id

@Composable
private fun DrawerSearchField(
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
private fun DrawerSelectionCircleButton(
    count: Int,
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
        if (count > 1) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = tint,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 7.dp, bottom = 6.dp),
                maxLines = 1,
            )
        }
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
                        ?: session.preview.takeIf { it.isNotBlank() }
                    if (!supportingLine.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = supportingLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherOnSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
