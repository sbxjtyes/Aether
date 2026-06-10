package com.zhousl.aether.data

import org.json.JSONObject

/**
 * LLM 工具（function calling，函数调用）的 JSON Schema 构造辅助。
 *
 * 这些是与 Agent 实例状态无关的纯函数，从体量庞大的 [AetherAgent] 中拆分出来，
 * 作为超大文件解耦的一步：所有 build*ToolDefinition 仍在同包内以无限定名调用它们。
 */

/** 构造一个标准的 OpenAI 风格函数工具定义（strict 模式）。 */
internal fun buildToolDefinition(
    name: String,
    description: String,
    properties: JSONObject,
    required: List<String>,
): JSONObject = JSONObject().apply {
    put("type", "function")
    put(
        "function",
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put(
                "parameters",
                buildStrictToolParameters(
                    properties = properties,
                    required = required,
                ),
            )
            put("strict", true)
        },
    )
}

internal fun stringProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "string")
    put("description", description)
}

internal fun integerProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "integer")
    put("description", description)
}

internal fun booleanProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "boolean")
    put("description", description)
}

internal fun stringArrayProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "array")
    put("description", description)
    put(
        "items",
        JSONObject().apply {
            put("type", "string")
        },
    )
}
