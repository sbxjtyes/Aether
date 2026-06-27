package com.zhousl.aether.ui

import com.zhousl.aether.data.AgentTaskStatus
import com.zhousl.aether.data.AgentTaskState

internal enum class ComposerSlashCommandId(
    val storageValue: String,
) {
    Plan("plan"),
    Goal("goal"),
    Status("status"),
    Review("review"),
    Model("model"),
    Tools("tools"),
    Permissions("permissions");

    companion object {
        fun fromStorageValue(value: String): ComposerSlashCommandId? =
            entries.firstOrNull { it.storageValue == value.trim().lowercase() }
    }
}

internal data class ParsedComposerSlashCommand(
    val id: ComposerSlashCommandId,
    val inlineText: String,
)

internal fun parseComposerSlashCommand(input: String): ParsedComposerSlashCommand? {
    val trimmed = input.trimStart()
    if (!trimmed.startsWith("/") || trimmed == "/") return null
    val withoutSlash = trimmed.drop(1)
    val command = withoutSlash.takeWhile { !it.isWhitespace() }.lowercase()
    val id = ComposerSlashCommandId.fromStorageValue(command) ?: return null
    val inlineText = withoutSlash.drop(command.length).trim()
    return ParsedComposerSlashCommand(id = id, inlineText = inlineText)
}

internal fun slashCommandQuery(input: String): String? {
    val trimmed = input.trimStart()
    if (!trimmed.startsWith("/")) return null
    return trimmed.drop(1).takeWhile { !it.isWhitespace() }.lowercase()
}

internal fun applyGoalSlashCommand(
    current: AgentTaskState,
    inlineText: String,
    nowMillis: Long,
): AgentTaskState {
    val goalText = inlineText.trim()
    return when (goalText.lowercase()) {
        "clear" -> AgentTaskState()
        "pause" -> current.copy(
            status = AgentTaskStatus.WaitingForUser,
            summary = current.summary.ifBlank { "Goal paused." },
            updatedAtMillis = nowMillis,
        )
        "resume" -> current.copy(
            status = AgentTaskStatus.InProgress,
            summary = current.summary.ifBlank { "Goal resumed." },
            updatedAtMillis = nowMillis,
        )
        else -> if (goalText.isBlank()) {
            current
        } else {
            current.copy(
                goal = goalText,
                status = AgentTaskStatus.InProgress,
                summary = "Goal set from /goal.",
                updatedAtMillis = nowMillis,
            )
        }
    }
}

internal fun buildReviewSlashPrompt(isTerminalEnabled: Boolean): String =
    buildString {
        append("Review the current workspace changes. ")
        append("Prioritize bugs, behavioral regressions, security/privacy risks, and missing tests. ")
        append("Lead with findings grounded in file and line references, then list residual test gaps. ")
        if (!isTerminalEnabled) {
            append("Terminal tools are currently disabled, so use available read-only context and say if command output is needed. ")
        }
        append("Do not implement fixes unless I explicitly ask.")
    }
