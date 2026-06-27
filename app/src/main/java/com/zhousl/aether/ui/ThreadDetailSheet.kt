package com.zhousl.aether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhousl.aether.data.AgentTaskStatus
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ThreadDetailSheetState(
    val session: ChatSession,
    val taskSnapshot: TaskWorkbenchSnapshot? = null,
    val isRunning: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadDetailSheet(
    state: ThreadDetailSheetState,
    onDismissRequest: () -> Unit,
    onOpenThread: () -> Unit,
    onRename: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onToggleArchived: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val language = strings.appLanguage
    val hasTaskContext = state.taskSnapshot != null || state.isRunning
    val isRunning = hasTaskContext &&
        (state.isRunning || state.taskSnapshot?.effectiveStatus == AgentTaskStatus.InProgress)
    val canArchive = state.session.isArchived || !isRunning
    val effectiveStatus = when {
        state.taskSnapshot != null -> state.taskSnapshot.effectiveStatus
        state.isRunning -> AgentTaskStatus.InProgress
        else -> null
    }
    val latestActivityLabel = if (hasTaskContext) {
        state.taskSnapshot?.activityLabel?.takeIf { it.isNotBlank() }
    } else {
        null
    } ?: localizedThreadDetailText(
            language = language,
            english = "Conversation",
            chinese = "\u5bf9\u8bdd",
        )
    val latestActivityDetail = state.taskSnapshot?.activityDetail
        ?.takeIf { it.isNotBlank() }
        ?: state.session.preview.takeIf { it.isNotBlank() }
        ?: "-"
    val focusText = state.taskSnapshot?.headline
        ?.takeIf { it.isNotBlank() && it != state.session.title }
    val summaryText = state.taskSnapshot?.body
        ?.takeIf { it.isNotBlank() && it != state.taskSnapshot.headline }
        ?: state.session.preview.takeIf { it.isNotBlank() && it != focusText }

    var titleDraft by rememberSaveable(state.session.id, state.session.title) {
        mutableStateOf(state.session.title.ifBlank { strings.newChatFallback })
    }

    fun commitRename() {
        val trimmed = titleDraft.trim()
        if (trimmed.isNotBlank() && trimmed != state.session.title) {
            onRename(trimmed)
        }
    }

    LaunchedEffect(state.session.id, state.session.title) {
        titleDraft = state.session.title.ifBlank { strings.newChatFallback }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = AetherSurface,
        contentColor = AetherOnSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .width(56.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(AetherOnSurfaceVariant.copy(alpha = 0.16f))
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, top = 4.dp, end = 22.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = localizedThreadDetailText(
                    language = language,
                    english = "Thread details",
                    chinese = "\u7ebf\u7a0b\u8be6\u60c5",
                ),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = AetherOnSurface,
            )

            if (state.session.isPinned || state.session.isArchived || isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.session.isPinned) {
                        ThreadDetailBadge(
                            label = localizedThreadDetailText(
                                language = language,
                                english = "Pinned",
                                chinese = "\u5df2\u7f6e\u9876",
                            ),
                            tint = AetherPrimary,
                        )
                    }
                    if (state.session.isArchived) {
                        ThreadDetailBadge(
                            label = localizedThreadDetailText(
                                language = language,
                                english = "Archived",
                                chinese = "\u5df2\u5f52\u6863",
                            ),
                            tint = Color(0xFF8B5CF6),
                        )
                    }
                    if (isRunning) {
                        ThreadDetailBadge(
                            label = localizedThreadDetailText(
                                language = language,
                                english = "Running",
                                chinese = "\u8fd0\u884c\u4e2d",
                            ),
                            tint = Color(0xFF22C55E),
                        )
                    }
                }
            }

            ThreadDetailSectionCard(
                title = localizedThreadDetailText(
                    language = language,
                    english = "Title",
                    chinese = "\u540d\u79f0",
                ),
            ) {
                BasicTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = AetherOnSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(AetherOnSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
                ThreadDetailActionPillRow(
                    primaryLabel = localizedThreadDetailText(
                        language = language,
                        english = "Save title",
                        chinese = "\u4fdd\u5b58\u540d\u79f0",
                    ),
                    primaryTint = AetherPrimary,
                    primaryEnabled = titleDraft.trim().isNotBlank() && titleDraft.trim() != state.session.title,
                    onPrimaryClick = ::commitRename,
                    secondaryLabel = localizedThreadDetailText(
                        language = language,
                        english = "Open thread",
                        chinese = "\u6253\u5f00\u7ebf\u7a0b",
                    ),
                    secondaryTint = AetherOnSurface,
                    onSecondaryClick = {
                        commitRename()
                        onOpenThread()
                    },
                )
            }

            ThreadDetailSectionCard(
                title = localizedThreadDetailText(
                    language = language,
                    english = "Overview",
                    chinese = "\u6982\u89c8",
                ),
            ) {
                effectiveStatus?.let { status ->
                    ThreadDetailStatusRow(
                        label = localizedThreadDetailText(
                            language = language,
                            english = "Status",
                            chinese = "\u72b6\u6001",
                        ),
                        value = taskWorkbenchStatusLabel(status, language),
                    )
                }
                ThreadDetailStatusRow(
                    label = localizedThreadDetailText(
                        language = language,
                        english = "Activity",
                        chinese = "\u6d3b\u52a8",
                    ),
                    value = latestActivityLabel,
                )
                ThreadDetailStatusRow(
                    label = localizedThreadDetailText(
                        language = language,
                        english = "Detail",
                        chinese = "\u8be6\u60c5",
                    ),
                    value = latestActivityDetail,
                    multiline = true,
                )
                ThreadDetailStatusRow(
                    label = localizedThreadDetailText(
                        language = language,
                        english = "Last activity",
                        chinese = "\u6700\u8fd1\u6d3b\u52a8",
                    ),
                    value = formatThreadDetailTimestamp(state.session.lastActivityAtMillis),
                )
                ThreadDetailStatusRow(
                    label = localizedThreadDetailText(
                        language = language,
                        english = "Last opened",
                        chinese = "\u6700\u8fd1\u6253\u5f00",
                    ),
                    value = formatThreadDetailTimestamp(state.session.lastOpenedAtMillis),
                )
                ThreadDetailStatusRow(
                    label = localizedThreadDetailText(
                        language = language,
                        english = "Messages",
                        chinese = "\u6d88\u606f\u6570",
                    ),
                    value = state.session.messages.size.toString(),
                )
                focusText?.let { focus ->
                    ThreadDetailStatusRow(
                        label = localizedThreadDetailText(
                            language = language,
                            english = "Focus",
                            chinese = "\u805a\u7126\u70b9",
                        ),
                        value = focus,
                        multiline = true,
                    )
                }
                summaryText?.let { summary ->
                    ThreadDetailStatusRow(
                        label = localizedThreadDetailText(
                            language = language,
                            english = "Summary",
                            chinese = "\u6458\u8981",
                        ),
                        value = summary,
                        multiline = true,
                    )
                }
                if (!canArchive) {
                    Text(
                        text = localizedThreadDetailText(
                            language = language,
                            english = "Finish the current run before archiving this thread.",
                            chinese = "\u8bf7\u5148\u7b49\u5f53\u524d\u8fd0\u884c\u5b8c\u6210\uff0c\u518d\u5f52\u6863\u8fd9\u4e2a\u7ebf\u7a0b\u3002",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }

            ThreadDetailSectionCard(
                title = localizedThreadDetailText(
                    language = language,
                    english = "Actions",
                    chinese = "\u64cd\u4f5c",
                ),
            ) {
                ThreadDetailActionRow(
                    icon = if (state.session.isPinned) Icons.Rounded.Close else Icons.Rounded.Check,
                    label = localizedThreadDetailText(
                        language = language,
                        english = if (state.session.isPinned) "Unpin thread" else "Pin thread",
                        chinese = if (state.session.isPinned) {
                            "\u53d6\u6d88\u7f6e\u9876"
                        } else {
                            "\u7f6e\u9876\u7ebf\u7a0b"
                        },
                    ),
                    tint = AetherPrimary,
                    onClick = onTogglePinned,
                )
                ThreadDetailActionRow(
                    icon = if (state.session.isArchived) Icons.Rounded.Check else Icons.Rounded.Close,
                    label = localizedThreadDetailText(
                        language = language,
                        english = if (state.session.isArchived) "Restore thread" else "Archive thread",
                        chinese = if (state.session.isArchived) {
                            "\u79fb\u51fa\u5f52\u6863"
                        } else {
                            "\u5f52\u6863\u7ebf\u7a0b"
                        },
                    ),
                    tint = if (state.session.isArchived) Color(0xFF8B5CF6) else AetherOnSurface,
                    enabled = canArchive,
                    supportingText = if (canArchive) {
                        null
                    } else {
                        localizedThreadDetailText(
                            language = language,
                            english = "Temporarily unavailable while the thread is running.",
                            chinese = "\u7ebf\u7a0b\u8fd0\u884c\u671f\u95f4\u6682\u65f6\u4e0d\u53ef\u7528\u3002",
                        )
                    },
                    onClick = onToggleArchived,
                )
                ThreadDetailActionRow(
                    icon = Icons.Rounded.Download,
                    label = strings.export,
                    tint = Color(0xFF2563EB),
                    onClick = onExport,
                )
                ThreadDetailActionRow(
                    icon = LucideIcons.Trash2,
                    label = strings.delete,
                    tint = Color(0xFFB42318),
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun ThreadDetailSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun ThreadDetailBadge(
    label: String,
    tint: Color,
) {
    Text(
        text = label,
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

@Composable
private fun ThreadDetailStatusRow(
    label: String,
    value: String,
    multiline: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
        Text(
            text = value.ifBlank { "-" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
            maxLines = if (multiline) 4 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThreadDetailActionPillRow(
    primaryLabel: String,
    primaryTint: Color,
    primaryEnabled: Boolean,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String,
    secondaryTint: Color,
    onSecondaryClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThreadDetailPill(
            label = primaryLabel,
            tint = primaryTint,
            modifier = Modifier.weight(1f),
            enabled = primaryEnabled,
            onClick = onPrimaryClick,
        )
        ThreadDetailPill(
            label = secondaryLabel,
            tint = secondaryTint,
            modifier = Modifier.weight(1f),
            onClick = onSecondaryClick,
        )
    }
}

@Composable
private fun ThreadDetailActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(tint.copy(alpha = if (enabled) 0.08f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = if (enabled) 0.10f else 0.06f))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else tint.copy(alpha = 0.45f),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) tint else tint.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ThreadDetailPill(
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = if (enabled) 0.10f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        color = if (enabled) tint else tint.copy(alpha = 0.45f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun localizedThreadDetailText(
    language: AppLanguage,
    english: String,
    chinese: String,
): String = if (language == AppLanguage.SimplifiedChinese) chinese else english

private fun formatThreadDetailTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return runCatching {
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault("-")
}
