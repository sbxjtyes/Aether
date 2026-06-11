package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.MessageAuthor

// 对话历史上下文预算（防止超长对话直接撞上模型 context 上限）。
// 超过后按从新到旧保留最近消息，更早的消息折叠为一条摘要提示。
internal const val MaxHistoryTokenBudget = 48_000
internal const val ApproxCharsPerToken = 4
internal const val OmittedHistoryPreviewChars = 160

/**
 * 在发送给模型前对对话历史做上下文预算控制：
 * - 估算每条消息的 token 量（约 4 字符 / token）。
 * - 从最新消息向前累加，保留预算内的最近消息（至少保留最后一条用户消息）。
 * - 被裁掉的更早消息折叠为一条 user 角色的摘要提示。
 */
internal fun applyHistoryContextBudget(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.size <= 2) return messages

    val estimatedTokens = IntArray(messages.size) { index ->
        estimateMessageTokens(messages[index])
    }
    val totalTokens = estimatedTokens.sum()
    if (totalTokens <= MaxHistoryTokenBudget) return messages

    var budget = MaxHistoryTokenBudget
    var keepFromIndex = messages.size
    for (index in messages.indices.reversed()) {
        val cost = estimatedTokens[index]
        if (budget - cost < 0 && keepFromIndex != messages.size) {
            break
        }
        budget -= cost
        keepFromIndex = index
    }
    if (keepFromIndex >= messages.size) {
        keepFromIndex = messages.size - 1
    }
    if (keepFromIndex <= 0) return messages

    val omitted = messages.subList(0, keepFromIndex)
    val kept = messages.subList(keepFromIndex, messages.size)
    val summaryMessage = buildOmittedHistorySummaryMessage(omitted)
    return listOf(summaryMessage) + kept
}

internal fun estimateMessageTokens(message: ChatMessage): Int {
    var chars = message.text.length
    chars += message.attachments.size * (ApproxCharsPerToken * 256)
    return (chars / ApproxCharsPerToken) + 1
}

internal fun buildOmittedHistorySummaryMessage(omitted: List<ChatMessage>): ChatMessage {
    val userCount = omitted.count { it.author == MessageAuthor.User }
    val agentCount = omitted.size - userCount
    val previews = omitted
        .asSequence()
        .filter { it.text.isNotBlank() }
        .map { message ->
            val role = if (message.author == MessageAuthor.User) "用户" else "助手"
            val preview = message.text.replace("\n", " ").take(OmittedHistoryPreviewChars)
            "- $role：$preview"
        }
        .toList()
        .takeLast(8)
    val summaryText = buildString {
        append("[对话历史摘要] 为控制上下文长度，已省略较早的 ")
        append(omitted.size)
        append(" 条消息（用户 ")
        append(userCount)
        append(" 条，助手 ")
        append(agentCount)
        append(" 条）。以下是被省略内容的简要预览，若需要其中细节请向用户确认：\n")
        if (previews.isEmpty()) {
            append("（无可预览的文本内容）")
        } else {
            append(previews.joinToString("\n"))
        }
    }
    return ChatMessage(
        id = "history-summary",
        author = MessageAuthor.User,
        text = summaryText,
    )
}
