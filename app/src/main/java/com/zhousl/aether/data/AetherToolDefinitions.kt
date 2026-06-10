package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 内置工具的 JSON Schema 定义。
 *
 * 这些定义此前内联在体量庞大的 [AetherAgent] 中，约 480 行且与实例状态无关，
 * 现拆分为同包顶层 internal 函数，显著减小 AetherAgent 文件体量、提升可维护性。
 * AetherAgent 仍以无限定名（unqualified）调用它们，行为完全不变。
 */

internal fun buildReadToolDefinition(): JSONObject = buildToolDefinition(
    name = "read",
    description = "Read a text file from Termux with optional line-based offset and limit. path accepts ~ or ~/... for the Termux home directory.",
    properties = JSONObject().apply {
        put("path", stringProperty("The file path to read."))
        put("offset", integerProperty("Optional zero-based line offset to start reading from."))
        put("limit", integerProperty("Optional maximum number of lines to return."))
        put(
            "showLineNumbers",
            booleanProperty("Whether stdout should prefix each returned line with its original 1-based line number."),
        )
        put(
            "show_line_numbers",
            booleanProperty("Alias of showLineNumbers."),
        )
        put(
            "workingDirectory",
            stringProperty("Optional working directory used to resolve relative paths."),
        )
        put(
            "working_directory",
            stringProperty("Alias of workingDirectory."),
        )
    },
    required = listOf("path"),
)

internal fun buildConversationStatusToolDefinition(): JSONObject = buildToolDefinition(
    name = "set_conversation_status",
    description = "Set whether this Aether conversation is completed, waiting for the user, blocked, or should continue autonomously with another hidden turn.",
    properties = JSONObject().apply {
        put(
            "status",
            stringProperty("One of: continue, waiting_for_user, completed, blocked."),
        )
        put(
            "reason",
            stringProperty("Brief reason for the status. Required in spirit for waiting_for_user or blocked, useful but concise for continue."),
        )
        put(
            "next_prompt",
            stringProperty("For status=continue only, a concise hidden instruction for the next autonomous turn."),
        )
        put(
            "nextPrompt",
            stringProperty("Alias of next_prompt."),
        )
    },
    required = listOf("status"),
)

internal fun buildTaskStateToolDefinition(): JSONObject = buildToolDefinition(
    name = "update_task_state",
    description = "Update the hidden persistent task board for this chat with the full current goal, status, todos, completion criteria, and progress summary.",
    properties = JSONObject().apply {
        put("goal", stringProperty("Current user goal for this task. Empty only when no task is active."))
        put("status", stringProperty("One of: idle, in_progress, waiting_for_user, completed, blocked."))
        put("summary", stringProperty("Short progress summary for the next turn."))
        put(
            "todos",
            JSONObject().apply {
                put("type", "array")
                put("description", "Current todo items for this task.")
                put(
                    "items",
                    JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties",
                            JSONObject().apply {
                                put("id", stringProperty("Stable short id, such as t1."))
                                put("text", stringProperty("Todo item text."))
                                put("done", booleanProperty("Whether this todo is complete."))
                            },
                        )
                        put("required", JSONArray().put("id").put("text").put("done"))
                        put("additionalProperties", false)
                    },
                )
            },
        )
        put(
            "completion_criteria",
            stringArrayProperty("Criteria that define when this task is complete."),
        )
        put(
            "completionCriteria",
            stringArrayProperty("Alias of completion_criteria."),
        )
    },
    required = listOf("goal", "status", "summary", "todos", "completion_criteria"),
)

internal fun buildEditToolDefinition(): JSONObject = buildToolDefinition(
    name = "edit",
    description = "Precisely edit a text file using exact oldText/newText replacements. For one edit use only oldText/newText. For multiple edits use only edits[]. path accepts ~ or ~/... for the Termux home directory.",
    properties = JSONObject().apply {
        put("path", stringProperty("The file path to edit."))
        put("oldText", stringProperty("For a single edit only, the exact text to replace. Omit this when using edits[]."))
        put("newText", stringProperty("For a single edit only, the replacement text. Omit this when using edits[]."))
        put(
            "edits",
            JSONObject().apply {
                put("type", "array")
                put(
                    "description",
                    "For multiple edits only, a list of non-overlapping precise replacements. Omit top-level oldText/newText when using this.",
                )
                put(
                    "items",
                    JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties",
                            JSONObject().apply {
                                put("oldText", stringProperty("The exact text to replace."))
                                put("newText", stringProperty("The replacement text."))
                            }
                        )
                        put("required", JSONArray().put("oldText").put("newText"))
                        put("additionalProperties", false)
                    }
                )
            }
        )
        put(
            "workingDirectory",
            stringProperty("Optional working directory used to resolve relative paths."),
        )
        put(
            "working_directory",
            stringProperty("Alias of workingDirectory."),
        )
    },
    required = listOf("path"),
)

internal fun buildWriteToolDefinition(): JSONObject = buildToolDefinition(
    name = "write",
    description = "Create a new text file or completely overwrite an existing text file. path accepts ~ or ~/... for the Termux home directory.",
    properties = JSONObject().apply {
        put("path", stringProperty("The file path to create or overwrite."))
        put("content", stringProperty("The full file contents to write."))
        put(
            "workingDirectory",
            stringProperty("Optional working directory used to resolve relative paths."),
        )
        put(
            "working_directory",
            stringProperty("Alias of workingDirectory."),
        )
    },
    required = listOf("path", "content"),
)

internal fun buildGrepToolDefinition(): JSONObject = buildToolDefinition(
    name = "grep",
    description = "Search for text or a regex pattern inside a file or directory tree. path accepts ~ or ~/... for the Termux home directory.",
    properties = JSONObject().apply {
        put("path", stringProperty("The file or directory path to search."))
        put("pattern", stringProperty("The text or regex pattern to search for."))
        put("isRegex", booleanProperty("Whether pattern should be treated as a regex."))
        put("caseSensitive", booleanProperty("Whether the search should be case-sensitive."))
        put("maxResults", integerProperty("Optional maximum number of matches to return."))
        put(
            "workingDirectory",
            stringProperty("Optional working directory used to resolve relative paths."),
        )
        put(
            "working_directory",
            stringProperty("Alias of workingDirectory."),
        )
    },
    required = listOf("path", "pattern"),
)

internal fun buildFindToolDefinition(): JSONObject = buildToolDefinition(
    name = "find",
    description = "Find files or directories by glob pattern. path accepts ~ or ~/... for the Termux home directory.",
    properties = JSONObject().apply {
        put("path", stringProperty("The directory path to search in."))
        put("pattern", stringProperty("The glob pattern to match, such as *.kt."))
        put(
            "type",
            stringProperty("Optional match type: any, file, or directory."),
        )
        put("caseSensitive", booleanProperty("Whether the glob match should be case-sensitive."))
        put("maxDepth", integerProperty("Optional maximum search depth."))
        put("maxResults", integerProperty("Optional maximum number of results to return."))
        put(
            "workingDirectory",
            stringProperty("Optional working directory used to resolve relative paths."),
        )
        put(
            "working_directory",
            stringProperty("Alias of workingDirectory."),
        )
    },
    required = listOf("path", "pattern"),
)

internal fun buildLsToolDefinition(): JSONObject = buildToolDefinition(
    name = "ls",
    description = "List the contents of a directory or inspect a file path. path accepts ~ or ~/... for the Termux home directory.",
    properties = JSONObject().apply {
        put("path", stringProperty("The file or directory path to list."))
        put("recursive", booleanProperty("Whether to list recursively."))
        put("includeHidden", booleanProperty("Whether to include hidden files and directories."))
        put("maxDepth", integerProperty("Optional maximum recursion depth."))
        put("maxEntries", integerProperty("Optional maximum number of entries to return."))
        put(
            "workingDirectory",
            stringProperty("Optional working directory used to resolve relative paths."),
        )
        put(
            "working_directory",
            stringProperty("Alias of workingDirectory."),
        )
    },
    required = listOf("path"),
)

internal fun buildBashToolDefinition(): JSONObject = JSONObject().apply {
    put("type", "function")
    put(
        "function",
        JSONObject().apply {
            put("name", "bash")
            put(
                "description",
                "Execute a bash command inside Termux on the user's Android device. The tool watches the command for up to 45 seconds. If the command is still running after that, it returns status=running, a run_id, and the latest stdout/stderr snapshot without interrupting the command. working_directory accepts ~ or ~/... for the Termux home directory."
            )
            put(
                "parameters",
                buildStrictToolParameters(
                    properties = JSONObject().apply {
                        put(
                            "command",
                            JSONObject().apply {
                                put("type", "string")
                                put("description", "The bash command or script to execute.")
                            }
                        )
                        put(
                            "working_directory",
                            JSONObject().apply {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional working directory inside Termux, for example ~/.aether/workspaces/<session-id>."
                                )
                            }
                        )
                        put(
                            "workingDirectory",
                            JSONObject().apply {
                                put("type", "string")
                                put(
                                    "description",
                                    "Alias of working_directory."
                                )
                            }
                        )
                        put(
                            "tail_bytes",
                            JSONObject().apply {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Optional maximum number of bytes to return from the end of stdout and stderr, up to 262144."
                                )
                            }
                        )
                        put(
                            "tailBytes",
                            JSONObject().apply {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Alias of tail_bytes."
                                )
                            }
                        )
                    },
                    required = listOf("command"),
                )
            )
            put("strict", true)
        }
    )
}

internal fun buildFetchBashOutputToolDefinition(): JSONObject = buildToolDefinition(
    name = "fetch_bash_output",
    description = "Fetch the latest stdout/stderr snapshot and status for a previously started long-running bash command by run_id.",
    properties = JSONObject().apply {
        put("run_id", stringProperty("The run_id returned by bash when it reported status=running."))
        put("runId", stringProperty("Alias of run_id."))
        put("tail_bytes", integerProperty("Optional maximum number of bytes to return from the end of stdout and stderr, up to 262144."))
        put("tailBytes", integerProperty("Alias of tail_bytes."))
    },
    required = listOf("run_id"),
)

internal fun buildKillBashToolDefinition(): JSONObject = buildToolDefinition(
    name = "kill_bash",
    description = "Stop a previously started long-running bash command by run_id and return its latest logs.",
    properties = JSONObject().apply {
        put("run_id", stringProperty("The run_id returned by bash when it reported status=running."))
        put("runId", stringProperty("Alias of run_id."))
        put("tail_bytes", integerProperty("Optional maximum number of bytes to return from the end of stdout and stderr, up to 262144."))
        put("tailBytes", integerProperty("Alias of tail_bytes."))
    },
    required = listOf("run_id"),
)

internal fun buildSleepToolDefinition(): JSONObject = buildToolDefinition(
    name = "sleep",
    description = "Pause the agent for a fixed duration so a long-running bash command can continue before you fetch logs again.",
    properties = JSONObject().apply {
        put("duration_ms", integerProperty("How long to sleep in milliseconds. Use this before polling a running bash command again."))
        put("durationMs", integerProperty("Alias of duration_ms."))
    },
    required = listOf("duration_ms"),
)

internal fun buildAnalyzeImageToolDefinition(): JSONObject = buildToolDefinition(
    name = "analyze_image",
    description = "Analyze an image file from the current workspace with model vision. Use this instead of assuming what an uploaded image contains.",
    properties = JSONObject().apply {
        put("path", stringProperty("The image file path to inspect. Relative paths resolve from the current workspace."))
        put("prompt", stringProperty("Optional question or instruction for what to inspect in the image."))
        put(
            "workingDirectory",
            stringProperty("Optional working directory used to resolve relative paths."),
        )
        put(
            "working_directory",
            stringProperty("Alias of workingDirectory."),
        )
    },
    required = listOf("path"),
)

internal fun buildFetchWebUrlToolDefinition(): JSONObject = buildToolDefinition(
    name = "fetch_web_url",
    description = "Fetch a specific HTTP or HTTPS URL and return the page content converted to Markdown. Use this when the user gives you a URL or you need the contents of one page.",
    properties = JSONObject().apply {
        put("url", stringProperty("The HTTP or HTTPS URL to fetch."))
        put("max_chars", integerProperty("Optional maximum number of Markdown characters to return."))
        put("maxChars", integerProperty("Alias of max_chars."))
    },
    required = listOf("url"),
)

internal fun buildTavilySearchToolDefinition(): JSONObject = buildToolDefinition(
    name = "tavily_search",
    description = "Search the public web with Tavily. Requires a Tavily API key in Settings > Web Tools. Use this for web discovery or current online information.",
    properties = JSONObject().apply {
        put("query", stringProperty("The search query to execute."))
        put("topic", stringProperty("Optional search topic: general, news, or finance."))
        put("search_depth", stringProperty("Optional search depth: basic, advanced, fast, or ultra-fast."))
        put("max_results", integerProperty("Optional maximum number of results to return, between 1 and 20."))
        put("time_range", stringProperty("Optional recency filter, such as day, week, month, or year. Do not combine this with start_date or end_date."))
        put("include_answer", booleanProperty("Whether Tavily should include a synthesized answer."))
        put("include_raw_content", booleanProperty("Whether each result should include raw page content in Markdown."))
        put("include_domains", stringArrayProperty("Optional list of domains to include."))
        put("exclude_domains", stringArrayProperty("Optional list of domains to exclude."))
        put("country", stringProperty("Optional lowercase Tavily country value for localized general search, such as united states or china. Leave null when unsure."))
        put("start_date", stringProperty("Optional start date in YYYY-MM-DD format. Do not combine this with time_range."))
        put("end_date", stringProperty("Optional end date in YYYY-MM-DD format. Do not combine this with time_range."))
    },
    required = listOf("query"),
)

internal fun buildStockMarketDataToolDefinition(): JSONObject = buildToolDefinition(
    name = "stock_market_data",
    description = "Search stock symbols or fetch current quote and historical OHLCV chart data from Eastmoney public market data endpoints. Supports A-shares and many HK/US symbols. Data may be delayed and is not financial advice.",
    properties = JSONObject().apply {
        put("action", stringProperty("One of: search, quote, chart. Defaults to search when query is provided without symbol, otherwise quote."))
        put("query", stringProperty("Search text for action=search, such as Apple, 贵州茅台, or BTC."))
        put("symbol", stringProperty("Stock symbol or Eastmoney QuoteID/secid for quote/chart, such as 001896.SZ, 600519.SH, 0.001896, 105.AAPL, AAPL, or 00700.HK."))
        put("range", stringProperty("Optional chart range, such as 1d, 5d, 1mo, 6mo, 1y, 5y, max. Defaults to 1d for quote and 1mo for chart."))
        put("interval", stringProperty("Optional chart interval, such as 1m, 5m, 15m, 1h, 1d, 1wk, 1mo. Defaults to 1m for quote and 1d for chart."))
        put("include_pre_post", booleanProperty("Whether to include pre-market and post-market data when available."))
        put("includePrePost", booleanProperty("Alias of include_pre_post."))
        put("max_results", integerProperty("For action=search, maximum number of symbol matches to return, between 1 and 25."))
        put("maxResults", integerProperty("Alias of max_results."))
    },
    required = emptyList(),
)

internal fun buildRunToolBatchToolDefinition(): JSONObject = buildToolDefinition(
    name = "run_tool_batch",
    description = "Submit multiple Aether tool calls in one top-level tool call. mode=parallel runs them at the same time; mode=sequential starts the next call only after the previous call finishes. If native parallel top-level tool calls are available, prefer direct multiple tool calls for simultaneous work and use this tool for ordered sequential batches or explicit fallback batching.",
    properties = JSONObject().apply {
        put(
            "mode",
            stringProperty("Execution mode: parallel for simultaneous execution, or sequential for one-after-another runtime execution."),
        )
        put(
            "calls",
            JSONObject().apply {
                put("type", "array")
                put("description", "Tool calls to execute.")
                put(
                    "items",
                    JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties",
                            JSONObject().apply {
                                put("tool_name", stringProperty("The Aether tool name to call, such as read, bash, grep, edit, or mcp_call_tool."))
                                put(
                                    "arguments_json",
                                    stringProperty("JSON object string containing the arguments for that tool, for example {\"path\":\"README.md\"}. Use {} when the tool has no arguments."),
                                )
                            },
                        )
                        put("required", JSONArray().put("tool_name").put("arguments_json"))
                        put("additionalProperties", false)
                    },
                )
            },
        )
    },
    required = listOf("mode", "calls"),
)

internal fun buildAgentModeToolDefinition(): JSONObject = buildToolDefinition(
    name = "agent_display",
    description = "Operate Aether Agent Mode on an isolated Android virtual display. Use this only when Agent Mode is selected in the chat composer.",
    properties = JSONObject().apply {
        put("action", stringProperty("One of: start, status, launch, tap, swipe, key, text, sequence, screenshot, stop."))
        put("target", stringProperty("For launch: package name or exact app label."))
        put("x", integerProperty("For tap: normalized X coordinate from 0 to 1000."))
        put("y", integerProperty("For tap: normalized Y coordinate from 0 to 1000."))
        put("x1", integerProperty("For swipe: normalized start X coordinate from 0 to 1000."))
        put("y1", integerProperty("For swipe: normalized start Y coordinate from 0 to 1000."))
        put("x2", integerProperty("For swipe: normalized end X coordinate from 0 to 1000."))
        put("y2", integerProperty("For swipe: normalized end Y coordinate from 0 to 1000."))
        put("duration_ms", integerProperty("For swipe: gesture duration in milliseconds."))
        put("durationMs", integerProperty("Alias of duration_ms."))
        put("key", stringProperty("For key: Android key code name or number, such as BACK, HOME, ENTER, or 4."))
        put("text", stringProperty("For text: text to type into the focused field."))
        put("steps", JSONObject().apply {
            put("type", "array")
            put("description", "For sequence: array of step objects. Each step has action (tap/swipe/key/text/wait/launch) plus the same params as the corresponding action. Optional wait_ms (0-5000) between steps.")
            put("items", JSONObject().apply {
                put("type", "object")
                put("additionalProperties", true)
            })
        })
    },
    required = listOf("action"),
)

internal fun buildActivateSkillToolDefinition(): JSONObject = buildToolDefinition(
    name = "activate_skill",
    description = "Load an installed Agent Skill into the current chat session. Use this when an installed skill matches the task or the user explicitly requests one.",
    properties = JSONObject().apply {
        put("name", stringProperty("The installed skill name or id to activate."))
    },
    required = listOf("name"),
)

internal fun buildReadSkillResourceToolDefinition(): JSONObject = buildToolDefinition(
    name = "read_skill_resource",
    description = "Read a bundled file from an already active Agent Skill by relative path. Use this for progressive disclosure when a skill's SKILL.md tells you to inspect references, scripts, assets, or agents metadata.",
    properties = JSONObject().apply {
        put("skill", stringProperty("The active skill name or id."))
        put("relative_path", stringProperty("The resource path relative to the skill root, such as references/guide.md or scripts/run.py."))
        put("path", stringProperty("Alias of relative_path."))
        put("max_chars", integerProperty("Optional maximum number of UTF-8 text characters to return."))
    },
    required = listOf("skill"),
)

internal fun buildMcpGenericToolDefinitions(): List<JSONObject> = listOf(
    buildToolDefinition(
        name = "mcp_list_tools",
        description = "List callable MCP tools across all connected servers or for one server.",
        properties = JSONObject().apply {
            put("server_id", stringProperty("Optional MCP server id to filter by."))
            put("serverId", stringProperty("Alias of server_id."))
        },
        required = emptyList(),
    ),
    buildToolDefinition(
        name = "mcp_call_tool",
        description = "Call an MCP tool by server id and tool name.",
        properties = JSONObject().apply {
            put("server_id", stringProperty("The MCP server id to call."))
            put("serverId", stringProperty("Alias of server_id."))
            put("tool_name", stringProperty("The MCP tool name to invoke."))
            put("toolName", stringProperty("Alias of tool_name."))
            put(
                "arguments",
                JSONObject().apply {
                    put("type", "object")
                    put("description", "Arguments to pass to the MCP tool.")
                    put("additionalProperties", true)
                },
            )
        },
        required = listOf("server_id", "tool_name"),
    ),
    buildToolDefinition(
        name = "mcp_list_resources",
        description = "List available MCP resources across all connected servers or for one server.",
        properties = JSONObject().apply {
            put("server_id", stringProperty("Optional MCP server id to filter by."))
            put("serverId", stringProperty("Alias of server_id."))
        },
        required = emptyList(),
    ),
    buildToolDefinition(
        name = "mcp_read_resource",
        description = "Read a specific MCP resource from a connected server.",
        properties = JSONObject().apply {
            put("server_id", stringProperty("The MCP server id to read from."))
            put("serverId", stringProperty("Alias of server_id."))
            put("uri", stringProperty("The MCP resource URI."))
        },
        required = listOf("server_id", "uri"),
    ),
    buildToolDefinition(
        name = "mcp_list_prompts",
        description = "List available MCP prompts across all connected servers or for one server.",
        properties = JSONObject().apply {
            put("server_id", stringProperty("Optional MCP server id to filter by."))
            put("serverId", stringProperty("Alias of server_id."))
        },
        required = emptyList(),
    ),
    buildToolDefinition(
        name = "mcp_get_prompt",
        description = "Fetch a rendered MCP prompt from a connected server.",
        properties = JSONObject().apply {
            put("server_id", stringProperty("The MCP server id to query."))
            put("serverId", stringProperty("Alias of server_id."))
            put("name", stringProperty("The MCP prompt name."))
            put(
                "arguments",
                JSONObject().apply {
                    put("type", "object")
                    put("description", "Optional prompt arguments.")
                    put("additionalProperties", true)
                },
            )
        },
        required = listOf("server_id", "name"),
    ),
)

internal fun buildMcpToolDefinition(binding: McpToolBinding): JSONObject = JSONObject().apply {
    put("type", "function")
    put(
        "function",
        JSONObject().apply {
            put("name", binding.namespacedToolName)
            put(
                "description",
                buildString {
                    append("Call MCP tool ")
                    append(binding.serverName)
                    append("/")
                    append(binding.toolName)
                    if (binding.description.isNotBlank()) {
                        append(": ")
                        append(binding.description)
                    }
                },
            )
            put(
                "parameters",
                JSONObject(binding.inputSchema.toString()).apply {
                    if (!has("type")) put("type", "object")
                },
            )
            put("strict", false)
        },
    )
}
