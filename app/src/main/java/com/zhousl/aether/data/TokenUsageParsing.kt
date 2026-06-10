package com.zhousl.aether.data

import org.json.JSONObject

/**
 * 令牌用量解析（token usage parsing）。
 *
 * 从这个文件从 [OpenAiCompatibleClient] 中拆分出来，承担所有 LLM 提供方的用量字段解析逻辑，
 * 既便于单元测试，也是大文件拆分的起点：与具体网络请求无关的纯解析逻辑应独立于客户端实现。
 */

/**
 * 从任意提供方的响应 JSON 中提取令牌用量，兼容多种字段命名：
 * - OpenAI Chat Completions：usage.{prompt_tokens, completion_tokens, total_tokens}
 * - OpenAI Responses：usage.{input_tokens, output_tokens, total_tokens}（completed 事件在 response.usage 下）
 * - Anthropic：usage.{input_tokens, output_tokens}（message_start 在 message.usage 下）
 * - Vertex：usageMetadata.{promptTokenCount, candidatesTokenCount, totalTokenCount}
 */
internal fun parseTokenUsage(json: JSONObject): TokenUsage? {
    val usage = json.optJSONObject("usage")
        ?: json.optJSONObject("usageMetadata")
        ?: json.optJSONObject("response")?.optJSONObject("usage")
        ?: json.optJSONObject("message")?.optJSONObject("usage")
        ?: return null

    fun firstInt(vararg keys: String): Int {
        for (key in keys) {
            val value = usage.optInt(key, -1)
            if (value >= 0) return value
        }
        return 0
    }

    val prompt = firstInt("prompt_tokens", "input_tokens", "promptTokenCount")
    val completion = firstInt("completion_tokens", "output_tokens", "candidatesTokenCount")
    var total = firstInt("total_tokens", "totalTokenCount")
    if (total == 0) total = prompt + completion
    if (prompt == 0 && completion == 0 && total == 0) return null
    return TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
}

/**
 * 流式响应里用量字段可能分散在多个事件（如 Anthropic 的 message_start / message_delta），
 * 这里按字段取最大值，保证拿到最终累计值。
 */
internal class UsageTracker {
    private var prompt = 0
    private var completion = 0
    private var total = 0

    fun capture(chunk: JSONObject) {
        val usage = parseTokenUsage(chunk) ?: return
        if (usage.promptTokens > prompt) prompt = usage.promptTokens
        if (usage.completionTokens > completion) completion = usage.completionTokens
        if (usage.totalTokens > total) total = usage.totalTokens
    }

    fun resolve(): TokenUsage? {
        // 输入与输出可能来自不同的流式事件，单个事件携带的 total 可能只反映其中一部分，
        // 因此最终 total 取「累计的最大 total」与「输入 + 输出之和」中的较大值。
        val resolvedTotal = maxOf(total, prompt + completion)
        if (prompt == 0 && completion == 0 && resolvedTotal == 0) return null
        return TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = resolvedTotal)
    }
}
