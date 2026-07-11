package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherToolSchemaTest {
    @Test
    fun buildsStrictFunctionToolDefinition() {
        val tool = buildToolDefinition(
            name = "read",
            description = "Read a file",
            properties = JSONObject().apply {
                put("path", stringProperty("File path"))
                put("limit", integerProperty("Max lines"))
            },
            required = listOf("path"),
        )

        assertEquals("function", tool.getString("type"))
        val function = tool.getJSONObject("function")
        assertEquals("read", function.getString("name"))
        assertTrue(function.getBoolean("strict"))

        val params = function.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        // strict 模式下 additionalProperties 必须为 false。
        assertFalse(params.getBoolean("additionalProperties"))
        // 所有属性都会被列入 required（非必填项以 nullable 类型表达）。
        val required = params.getJSONArray("required")
        val requiredNames = (0 until required.length()).map { required.getString(it) }.toSet()
        assertTrue(requiredNames.contains("path"))
        assertTrue(requiredNames.contains("limit"))
    }

    @Test
    fun propertyBuildersProduceExpectedTypes() {
        assertEquals("string", stringProperty("x").getString("type"))
        assertEquals("integer", integerProperty("x").getString("type"))
        assertEquals("boolean", booleanProperty("x").getString("type"))
        val arr = stringArrayProperty("x")
        assertEquals("array", arr.getString("type"))
        assertEquals("string", arr.getJSONObject("items").getString("type"))
        assertEquals("string", jsonObjectStringProperty("x").getString("type"))
    }

    @Test
    fun mcpCallToolUsesJsonStringArgumentsForStrictSchema() {
        val tool = buildMcpGenericToolDefinitions().first { definition ->
            definition.getJSONObject("function").getString("name") == "mcp_call_tool"
        }
        val properties = tool.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        val argumentsSchema = properties.getJSONObject("arguments")

        assertEquals("string", argumentsSchema.getString("type"))
        assertFalse(argumentsSchema.has("additionalProperties"))
    }

    @Test
    fun stockMarketDataSchemaExposesBatchQuotesAndIncludeChart() {
        val tool = buildStockMarketDataToolDefinition()
        val description = tool.getJSONObject("function").getString("description")
        val properties = tool.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")

        assertTrue(description.contains("valuation"))
        assertTrue(description.contains("turnover"))
        assertTrue(description.contains("period-performance"))
        assertTrue(properties.getJSONObject("query").getString("description").contains("贵州茅台"))
        assertTrue(properties.getJSONObject("action").getString("description").contains("quotes"))
        assertTrue(properties.getJSONObject("symbols").opt("type")?.toString().orEmpty().contains("array"))
        assertEquals("string", properties.getJSONObject("symbols").getJSONObject("items").getString("type"))
        assertTrue(properties.getJSONObject("include_chart").opt("type")?.toString().orEmpty().contains("boolean"))
        assertTrue(properties.getJSONObject("includeChart").opt("type")?.toString().orEmpty().contains("boolean"))
    }
}
