package com.zhousl.aether.data

import org.json.JSONObject

/**
 * 令牌用量（token usage）统计。
 *
 * 不同提供方字段命名不同（OpenAI 用 prompt/completion，Anthropic/Responses 用 input/output，
 * Vertex 用 promptTokenCount/candidatesTokenCount），这里统一为输入/输出/合计三个口径。
 */
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    val isEmpty: Boolean
        get() = promptTokens == 0 && completionTokens == 0 && totalTokens == 0

    /** 累加多次调用的用量（一个 turn 内可能发生多轮 LLM 请求）。 */
    operator fun plus(other: TokenUsage): TokenUsage {
        val prompt = promptTokens + other.promptTokens
        val completion = completionTokens + other.completionTokens
        val rawTotal = totalTokens + other.totalTokens
        return TokenUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = if (rawTotal > 0) rawTotal else prompt + completion,
        )
    }
}

data class ChatCompletionResult(
    val assistantText: String,
    val toolCalls: List<ChatCompletionToolCall>,
    val assistantMessage: JSONObject,
    val reasoningText: String = "",
    val reasoningSummaryText: String = "",
    val usage: TokenUsage? = null,
)

data class ChatCompletionToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ChatCompletionToolResult(
    val callId: String,
    val name: String,
    val output: String,
)

sealed interface LlmContentPart

data class LlmTextPart(
    val text: String,
) : LlmContentPart

data class LlmImagePart(
    val mimeType: String,
    val base64Data: String,
) : LlmContentPart

data class LlmMessage(
    val role: String,
    val contentParts: List<LlmContentPart>,
)
