package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageParsingTest {
    @Test
    fun parsesOpenAiChatCompletionUsage() {
        val json = JSONObject(
            """{"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}"""
        )
        val usage = parseTokenUsage(json)!!
        assertEquals(120, usage.promptTokens)
        assertEquals(30, usage.completionTokens)
        assertEquals(150, usage.totalTokens)
    }

    @Test
    fun parsesAnthropicUsageAndDerivesTotal() {
        val json = JSONObject("""{"usage":{"input_tokens":200,"output_tokens":45}}""")
        val usage = parseTokenUsage(json)!!
        assertEquals(200, usage.promptTokens)
        assertEquals(45, usage.completionTokens)
        // Anthropic 不返回 total，应由输入 + 输出推导。
        assertEquals(245, usage.totalTokens)
    }

    @Test
    fun parsesVertexUsageMetadata() {
        val json = JSONObject(
            """{"usageMetadata":{"promptTokenCount":80,"candidatesTokenCount":20,"totalTokenCount":100}}"""
        )
        val usage = parseTokenUsage(json)!!
        assertEquals(80, usage.promptTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(100, usage.totalTokens)
    }

    @Test
    fun parsesNestedResponseUsage() {
        val json = JSONObject(
            """{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}"""
        )
        val usage = parseTokenUsage(json)!!
        assertEquals(15, usage.totalTokens)
    }

    @Test
    fun returnsNullWhenNoUsagePresent() {
        assertNull(parseTokenUsage(JSONObject("""{"choices":[]}""")))
        assertNull(parseTokenUsage(JSONObject("""{"usage":{}}""")))
    }

    @Test
    fun usageTrackerKeepsMaxAcrossStreamingChunks() {
        val tracker = UsageTracker()
        // 模拟 Anthropic：message_start 带 input，message_delta 带最终 output。
        tracker.capture(JSONObject("""{"type":"message_start","message":{"usage":{"input_tokens":300}}}"""))
        tracker.capture(JSONObject("""{"type":"message_delta","usage":{"output_tokens":10}}"""))
        tracker.capture(JSONObject("""{"type":"message_delta","usage":{"output_tokens":42}}"""))
        val usage = tracker.resolve()!!
        assertEquals(300, usage.promptTokens)
        assertEquals(42, usage.completionTokens)
        assertEquals(342, usage.totalTokens)
    }

    @Test
    fun tokenUsagePlusAggregatesTurns() {
        val combined = TokenUsage(100, 20, 120) + TokenUsage(50, 10, 60)
        assertEquals(150, combined.promptTokens)
        assertEquals(30, combined.completionTokens)
        assertEquals(180, combined.totalTokens)
    }
}
