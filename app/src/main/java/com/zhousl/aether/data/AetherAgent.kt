package com.zhousl.aether.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.zhousl.aether.termux.TermuxBashTool
import com.zhousl.aether.termux.TermuxFilesystemTool
import com.zhousl.aether.util.AetherLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val MaxAnalyzeImageBytes = 5 * 1024 * 1024
private const val MaxSkillResourceBytes = 1024 * 1024
private const val DefaultSkillResourceMaxChars = 20_000
private const val SkillMetadataContextBudgetChars = 8_000
private const val MaxSleepDurationMillis = 10 * 60 * 1000L
private const val AetherAgentLogTag = "AetherAgent"
private const val MaxStockCandleOutputCount = 200
// quote + include_chart 时返回足够覆盖约一个月交易日的 K 线，避免模型只看到 5 根就误判走势。
private const val QuoteStockCandleOutputCount = 22
private const val MaxConversationDecisionTextChars = 2_000
private const val MaxTaskStateTextChars = 2_000
private const val MaxTaskStateItemTextChars = 240
private const val MaxTaskStateItemCount = 24
private const val MaxForcedMarketSynthesisContinuations = 1

private val MarketDataToolNames = setOf(
    "stock_market_data",
    "market_overview",
    "sector_heat",
)

/**
 * 判断模型是否只输出了“马上给结论”一类过渡语，却没有真正完成行情分析。
 *
 * 必须与本回合实际调用行情工具联合使用，避免把普通短回复误判为未完成。
 */
internal fun shouldForceMarketSynthesisContinuation(
    assistantText: String,
    marketDataToolCalled: Boolean,
    conversationDecision: AgentConversationDecision,
    planModeEnabled: Boolean,
): Boolean {
    if (!marketDataToolCalled || planModeEnabled) return false
    if (conversationDecision.status == AgentConversationStatus.WaitingForUser) return false

    val text = assistantText.trim()
    if (text.isBlank()) return true
    if (text.length >= 180) return false

    val hasExplicitConclusion = listOf(
        Regex("""(?:结论|判断|建议)[：:]\s*.{0,16}(?:合理|不合理|正确|不正确|偏对|偏错|卖出|持有|观望|止损|减仓|加仓)"""),
        Regex("""(?:我认为|我的判断是|综合来看).{0,20}(?:合理|不合理|正确|偏对|偏错|建议|卖出|持有|观望)"""),
        Regex("""(?:卖出|持有|观望|止损|减仓|加仓).{0,12}(?:合理|不合理|正确|偏对|偏错|更稳妥|更合适)"""),
    ).any { it.containsMatchIn(text) }
    if (hasExplicitConclusion) return false

    val hasTransitionPromise = listOf(
        "数据拿到了",
        "已拿到数据",
        "已经拿到数据",
        "已获取行情",
        "已经获取行情",
        "接下来分析",
        "接着分析",
        "接下来判断",
        "接着判断",
        "下面给你结论",
        "现在给你结论",
        "给你做判断",
        "给你结论",
    ).any(text::contains)

    return hasTransitionPromise
}

private fun buildForcedMarketSynthesisMessage(): LlmMessage = LlmMessage(
    role = "user",
    contentParts = listOf(
        LlmTextPart(
            "这是隐藏的执行完整性检查，不是新的用户请求。你刚才已经取得行情工具结果，" +
                "但可见回复只有准备分析或准备给结论的过渡语。请立即使用已有工具结果写出完整、" +
                "直接的用户可见结论和关键依据；不要重复查询，不要再用过渡语收尾。写完结论后才能标记完成。",
        ),
    ),
)

// 单个 turn 内工具调用轮次的硬上限，防止模型陷入无限工具调用循环耗尽资源。
private const val MaxToolRoundsPerTurn = 64
// 工具执行遇到疑似瞬时错误（网络/超时等）时的额外重试次数。
private const val MaxTransientToolRetries = 1

internal fun parseAgentConversationDecisionOutput(output: String): AgentConversationDecision {
    val parsed = runCatching { JSONObject(output) }.getOrNull()
        ?: return AgentConversationDecision.completed()
    if (!parsed.optBoolean("ok", true)) {
        return AgentConversationDecision(
            status = AgentConversationStatus.Blocked,
            reason = parsed.optString("errmsg").trim(),
        )
    }
    return AgentConversationDecision(
        status = AgentConversationStatus.fromStorageValue(parsed.optString("status")),
        reason = parsed.cleanAgentDecisionString("reason").take(MaxConversationDecisionTextChars),
        nextPrompt = parsed.cleanAgentDecisionString("next_prompt")
            .ifBlank { parsed.cleanAgentDecisionString("nextPrompt") }
            .take(MaxConversationDecisionTextChars),
    )
}

internal fun deriveAgentConversationDecision(
    explicitDecision: AgentConversationDecision,
    taskState: AgentTaskState?,
    goalModeEnabled: Boolean = true,
): AgentConversationDecision {
    if (explicitDecision.status == AgentConversationStatus.Continue) {
        return if (explicitDecision.nextPrompt.isNotBlank()) {
            explicitDecision
        } else {
            explicitDecision.copy(
                nextPrompt = buildAgentTaskContinuationPrompt(taskState)
                    .ifBlank { "Continue the current task and decide the next status after making progress." },
            )
        }
    }
    if (explicitDecision != AgentConversationDecision.completed()) {
        return explicitDecision
    }
    if (!goalModeEnabled || taskState?.status != AgentTaskStatus.InProgress) {
        return explicitDecision
    }
    return AgentConversationDecision(
        status = AgentConversationStatus.Continue,
        reason = taskState.summary.ifBlank { "Task state is still in progress." },
        nextPrompt = buildAgentTaskContinuationPrompt(taskState),
    )
}

internal fun buildAgentTaskContinuationPrompt(taskState: AgentTaskState?): String {
    if (taskState == null) return ""
    val nextTodo = taskState.todos.firstOrNull { !it.done }?.text.orEmpty()
    return buildString {
        append("Continue the current task.")
        if (taskState.goal.isNotBlank()) {
            append("\nGoal: ")
            append(taskState.goal)
        }
        if (nextTodo.isNotBlank()) {
            append("\nNext todo: ")
            append(nextTodo)
        }
        if (taskState.summary.isNotBlank()) {
            append("\nProgress summary: ")
            append(taskState.summary)
        }
        append("\nUse the necessary tools now. Update task state after making progress, then set conversation status again.")
    }.take(MaxConversationDecisionTextChars)
}

private fun JSONObject.cleanAgentDecisionString(key: String): String {
    if (!has(key) || isNull(key)) return ""
    val value = optString(key).trim()
    return value.takeUnless {
        it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true)
    }.orEmpty()
}

internal fun buildPlanModeInstructions(): String = """
    PLAN MODE is enabled for this chat turn. You are a read-only planning agent.
    Treat any user task/request as something to plan, not answer or perform directly.
    Implementation requests produce an implementation plan; analysis, research, writing, debugging, and finance/stock questions produce an analysis or execution plan.
    Do not provide the final answer, investment analysis conclusion, finished report, implementation result, or deliverable content while Plan Mode is enabled.
    You may inspect files, search, fetch web pages, analyze images, read skill resources, and inspect MCP catalogs/resources/prompts.
    You must not modify files, write files, run terminal commands, operate devices, call MCP tools, use Agent Mode, wait/sleep, or make changes to external systems.

    Work in three phases:
    Phase 1 - Ground in the environment: first gather facts from available read-only tools before asking the user. Resolve discoverable facts such as files, schemas, entrypoints, configs, current behavior, and public documentation through inspection instead of questions.
    Phase 2 - Clarify intent: ask concise questions only when goal, success criteria, audience, in/out of scope, constraints, or a key tradeoff remains materially ambiguous after inspection.
    Phase 3 - Produce the implementation plan: make the plan decision-complete, including approach, affected interfaces or data flow, edge cases/failure modes, testing and acceptance criteria, install/verification needs, and explicit assumptions/defaults.

    When asking the user to choose between branches, present 2-3 short mutually exclusive text options, put the recommended option first, and mark it as "(recommended)".
    Ask only questions that materially change the plan, confirm an important assumption, or choose between meaningful tradeoffs.
    If user input is required before a reliable plan can be produced, ask the focused question directly. The execution framework decides whether the turn is complete.
    If a low-risk preference is unanswered, choose the recommended default and record it in the final plan's Assumptions.

    When ready, output exactly one proposed implementation, analysis, or execution plan inside a single <proposed_plan>...</proposed_plan> block.
    Use Markdown inside the block. Prefer concise sections named Summary, Implementation Changes, Test Plan, and Assumptions unless the task needs different headings.
    Match the user's language; for Chinese user requests, write the plan in Chinese by default.
    Do not ask "should I proceed" in the final plan. Do not claim that implementation has been completed while Plan Mode is enabled.
""".trimIndent()

internal fun buildGoalModeInstructions(): String = """
    GOAL MODE is enabled for this chat. Treat the current task_state goal as the objective to pursue across turns.
    Use available tools to make concrete progress, observe tool results, repair failures when possible, and provide the complete result in the current turn.
    Ask the user directly when essential input, confirmation, files, permissions, or choices are missing.
    Do not attempt to set conversation or task completion status. The execution framework exclusively decides when work stops or continues.
""".trimIndent()

private fun looksLikeMcpToolCallName(toolName: String): Boolean =
    toolName.startsWith("mcp__") || toolName.contains(':')

private fun isPlanModeToolNameAllowed(toolName: String): Boolean = when (toolName) {
    "run_tool_batch",
    "read",
    "grep",
    "find",
    "ls",
    "analyze_image",
    "fetch_web_url",
    "tavily_search",
    "stock_market_data",
    "market_overview",
    "sector_heat",
    "activate_skill",
    "read_skill_resource",
    "mcp_list_tools",
    "mcp_list_resources",
    "mcp_read_resource",
    "mcp_list_prompts",
    "mcp_get_prompt" -> true

    // 命名空间 MCP 工具（mcp__serverId__toolName）在 plan 模式下不允许执行
    else -> false
}

internal fun buildAetherBaseToolDefinitions(
    enabledToolGroups: List<String> = ChatToolGroups.DefaultEnabled,
    agentModeEnabled: Boolean = false,
    planModeEnabled: Boolean = false,
): List<JSONObject> {
    val normalizedGroups = normalizeChatToolGroups(enabledToolGroups)
    return buildList {
        if (isChatToolGroupEnabled(normalizedGroups, ChatToolGroups.FilesImages)) {
            add(buildReadToolDefinition())
            if (!planModeEnabled) {
                add(buildEditToolDefinition())
                add(buildWriteToolDefinition())
            }
            add(buildGrepToolDefinition())
            add(buildFindToolDefinition())
            add(buildLsToolDefinition())
            add(buildAnalyzeImageToolDefinition())
        }
        if (!planModeEnabled && isChatToolGroupEnabled(normalizedGroups, ChatToolGroups.Terminal)) {
            add(buildBashToolDefinition())
            add(buildFetchBashOutputToolDefinition())
            add(buildKillBashToolDefinition())
            add(buildSleepToolDefinition())
        }
        if (isChatToolGroupEnabled(normalizedGroups, ChatToolGroups.Web)) {
            add(buildFetchWebUrlToolDefinition())
            add(buildTavilySearchToolDefinition())
            add(buildStockMarketDataToolDefinition())
            add(buildMarketOverviewToolDefinition())
            add(buildSectorHeatToolDefinition())
        }
        if (!planModeEnabled && agentModeEnabled) {
            add(buildAgentModeToolDefinition())
        }
    }
}

internal fun isAetherToolAvailableForGroups(
    toolName: String,
    enabledToolGroups: List<String> = ChatToolGroups.DefaultEnabled,
    agentModeEnabled: Boolean = false,
    planModeEnabled: Boolean = false,
): Boolean {
    if (planModeEnabled && !isPlanModeToolNameAllowed(toolName)) {
        return false
    }
    val normalizedGroups = normalizeChatToolGroups(enabledToolGroups)
    return when (toolName) {
        "set_conversation_status",
        "update_task_state" -> false

        "run_tool_batch" -> true

        "read",
        "edit",
        "write",
        "grep",
        "find",
        "ls",
        "analyze_image" -> isChatToolGroupEnabled(normalizedGroups, ChatToolGroups.FilesImages)

        "bash",
        "fetch_bash_output",
        "kill_bash",
        "sleep" -> isChatToolGroupEnabled(normalizedGroups, ChatToolGroups.Terminal)

        "fetch_web_url",
        "tavily_search",
        "stock_market_data",
        "market_overview",
        "sector_heat" -> isChatToolGroupEnabled(normalizedGroups, ChatToolGroups.Web)

        "activate_skill",
        "read_skill_resource",
        "mcp_list_tools",
        "mcp_call_tool",
        "mcp_list_resources",
        "mcp_read_resource",
        "mcp_list_prompts",
        "mcp_get_prompt" -> isChatToolGroupEnabled(normalizedGroups, ChatToolGroups.Extensions)

        "agent_display" -> agentModeEnabled

        else -> isChatToolGroupEnabled(normalizedGroups, ChatToolGroups.Extensions) &&
            looksLikeMcpToolCallName(toolName)
    }
}

internal fun shouldForceToolUseForLatestUserText(latestUserText: String): Boolean {
    val normalized = latestUserText.lowercase()
    if (normalized.isBlank()) return false

    if (listOf(
        "bash",
        "shell",
        "termux",
        "terminal",
        "run pwd",
        "run ls",
        "run cat",
        "execute ",
        "command ",
        "read ",
        "open file",
        "edit file",
        "modify file",
        "write file",
        "create file",
        "overwrite file",
        "grep ",
        "search files",
        "find file",
        "list directory",
        "analyze image",
        "inspect image",
        "search the web",
        "search web",
        "browse the web",
        "browse web",
        "look up online",
        "fetch url",
        "web url",
        "tavily",
        "https://",
        "http://",
    ).any(normalized::contains)) {
        return true
    }

    val asksForDataRefresh =
        ("refresh" in normalized && "data" in normalized) ||
            ("update" in normalized && "data" in normalized) ||
            ("latest" in normalized && "data" in normalized) ||
            ("current" in normalized && "price" in normalized) ||
            ("latest" in normalized && "quote" in normalized) ||
            ("latest" in normalized && "market" in normalized) ||
            ("刷新" in normalized && "数据" in normalized) ||
            ("更新" in normalized && "数据" in normalized) ||
            ("最新" in normalized && "行情" in normalized) ||
            ("最新" in normalized && "股" in normalized) ||
            ("当前" in normalized && "股价" in normalized) ||
            ("现价" in normalized)
    if (asksForDataRefresh) {
        return true
    }

    val marketTerms = listOf(
        "stock",
        "stocks",
        "market",
        "quote",
        "ticker",
        "symbol",
        "etf",
        "index",
        "crypto",
        "share",
        "shares",
        "行情",
        "股票",
        "股价",
        "个股",
        "证券",
        "基金",
        "指数",
        "板块",
        "大盘",
        "港股",
        "美股",
        "a股",
        "加密货币",
        "币价",
        "当前价",
        "涨跌",
        "换手",
        "量比",
        "成交量",
        "分时",
        "k线",
    )
    val freshDataTerms = listOf(
        "refresh",
        "update",
        "latest",
        "current",
        "real-time",
        "realtime",
        "today",
        "now",
        "刷新",
        "更新",
        "最新",
        "当前",
        "实时",
        "现在",
        "今日",
        "今天",
        "盘中",
    )
    return marketTerms.any(normalized::contains) && freshDataTerms.any(normalized::contains)
}

class AetherAgent(
    private val client: OpenAiCompatibleClient,
    private val bashTool: TermuxBashTool,
    private val workspaceFileBridge: WorkspaceFileBridge,
    private val agentModeController: AgentModeController,
    private val skillManager: AgentSkillManager,
    private val mcpClientManager: McpClientManager,
    private val webToolsClient: WebToolsClient,
    private val marketMonitorRepository: MarketMonitorRepository? = null,
    private val onParallelToolCallsUnsupported: suspend (String) -> Unit = {},
) {
    private val filesystemTool = TermuxFilesystemTool(bashTool)

    suspend fun runTurn(
        settings: AppSettings,
        messages: List<LlmMessage>,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill> = emptyList(),
        activeSkills: List<ActiveSkillContext> = emptyList(),
        mcpToolBindings: List<McpToolBinding> = emptyList(),
        agentModeEnabled: Boolean = false,
        enabledToolGroups: List<String> = ChatToolGroups.DefaultEnabled,
        planModeEnabled: Boolean = false,
        goalModeEnabled: Boolean = false,
        taskState: AgentTaskState = AgentTaskState(),
        onToolEvent: suspend (AgentToolEvent) -> Unit = {},
        onAssistantTextDelta: suspend (String) -> Unit = {},
        onAssistantReasoningDelta: suspend (String) -> Unit = {},
        onAssistantReasoningSummaryDelta: suspend (String) -> Unit = {},
        onAssistantTextReset: suspend () -> Unit = {},
        onStreamingStatus: suspend (StreamingStatus?) -> Unit = {},
        onSkillActivated: suspend (ActiveSkillContext) -> Unit = {},
        onTaskStateUpdated: suspend (AgentTaskState) -> Unit = {},
        pollInjectedUserMessages: suspend () -> List<LlmMessage> = { emptyList() },
    ): Result<AgentTurnResult> {
        return try {
        val conversation = client.buildConversation(
            settings = settings,
            messages = messages,
        ).toMutableList()
        val normalizedToolGroups = normalizeChatToolGroups(enabledToolGroups)
        val extensionsEnabled = isChatToolGroupEnabled(normalizedToolGroups, ChatToolGroups.Extensions)
        val resolvedAvailableSkills = if (extensionsEnabled) {
            availableSkills
                .filter { it.isEnabled }
                .sortedBy { it.name.lowercase() }
        } else {
            emptyList()
        }
        val resolvedActiveSkills = if (extensionsEnabled) {
            activeSkills.toMutableList()
        } else {
            mutableListOf()
        }
        val effectiveMcpToolBindings = if (extensionsEnabled) mcpToolBindings else emptyList()
        val baseTools = buildAetherBaseToolDefinitions(
            enabledToolGroups = normalizedToolGroups,
            agentModeEnabled = agentModeEnabled,
            planModeEnabled = planModeEnabled,
        )
        val hasMcpCatalog = extensionsEnabled && (
            effectiveMcpToolBindings.isNotEmpty() ||
                mcpClientManager.snapshots().any { it.resources.isNotEmpty() || it.prompts.isNotEmpty() }
            )
        val useBasicToolCompatibility = settings.basicFunctionCallingCompatibilityMode
        val exposeNamespacedMcpTools =
            !planModeEnabled &&
            !useBasicToolCompatibility &&
            effectiveMcpToolBindings.isNotEmpty()
        var lastAssistantText = ""
        var conversationDecision = AgentConversationDecision.completed()
        var latestTaskState = taskState
        var updatedTaskState: AgentTaskState? = null
        var lastAgentModeScreenshotMessageIndex: Int? = null
        var internalOnlyContinuationCount = 0
        var marketDataToolCalled = false
        var forcedMarketSynthesisCount = 0
        val latestUserText = messages.lastOrNull { it.role == "user" }
            ?.contentParts
            ?.filterIsInstance<LlmTextPart>()
            ?.joinToString("\n") { it.text }
            .orEmpty()
        val initialToolChoice = if (shouldForceToolUse(latestUserText)) "required" else "auto"
        val parallelToolCallSupportKey = settings.parallelToolCallSupportKey()
        var parallelToolCallsEnabled = !useBasicToolCompatibility && settings.supportsParallelToolCalls()

        var turnUsage = TokenUsage()
        var round = 0
        while (true) {
            if (round >= MaxToolRoundsPerTurn) {
                // 触达单轮工具调用硬上限：停止以避免失控的无限循环。
                if (lastAssistantText.isBlank()) {
                    lastAssistantText = "Reached the maximum number of tool-call rounds for a single turn and stopped to avoid an endless loop. Send another message to continue."
                }
                break
            }
            val injectedMessages = pollInjectedUserMessages()
            if (injectedMessages.isNotEmpty()) {
                lastAssistantText = ""
                conversation += client.buildConversation(
                    settings = settings,
                    messages = injectedMessages,
                )
            }
            val systemPrompt = buildAgentInstructions(
                systemPrompt = settings.systemPrompt,
                workspaceDirectory = workspaceDirectory,
                availableSkills = resolvedAvailableSkills,
                activeSkills = resolvedActiveSkills,
                mcpToolBindings = effectiveMcpToolBindings,
                exposeNamespacedMcpTools = exposeNamespacedMcpTools,
                agentModeEnabled = agentModeEnabled,
                enabledToolGroups = normalizedToolGroups,
                planModeEnabled = planModeEnabled,
                goalModeEnabled = goalModeEnabled,
                taskState = latestTaskState,
                parallelToolCallsEnabled = parallelToolCallsEnabled,
                basicToolCompatibilityMode = useBasicToolCompatibility,
            )
            val tools = buildList {
                addAll(baseTools)
                if (!parallelToolCallsEnabled && !useBasicToolCompatibility) {
                    add(buildRunToolBatchToolDefinition())
                }
                if (resolvedAvailableSkills.isNotEmpty()) {
                    add(buildActivateSkillToolDefinition())
                }
                if (resolvedAvailableSkills.isNotEmpty() || resolvedActiveSkills.isNotEmpty()) {
                    add(buildReadSkillResourceToolDefinition())
                }
            }
            val effectiveTools = if (hasMcpCatalog) {
                // 命名空间工具直接注入时，跳过多余的 mcp_list_tools / mcp_call_tool 包装器
                val skipWhenNamespaced = if (exposeNamespacedMcpTools) {
                    setOf("mcp_list_tools", "mcp_call_tool")
                } else {
                    emptySet()
                }
                tools +
                    buildMcpGenericToolDefinitions().filter { definition ->
                        val name = definition.getJSONObject("function").getString("name")
                        name !in skipWhenNamespaced && isAetherToolAvailableForGroups(
                            toolName = name,
                            enabledToolGroups = normalizedToolGroups,
                            agentModeEnabled = agentModeEnabled,
                            planModeEnabled = planModeEnabled,
                        )
                    } +
                    if (exposeNamespacedMcpTools) {
                        effectiveMcpToolBindings.map(::buildMcpToolDefinition)
                    } else {
                        emptyList()
                    }
            } else {
                tools
            }
            val response = try {
                streamChatCompletionWithReconnect(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    conversation = conversation,
                    tools = effectiveTools,
                    toolChoice = if (useBasicToolCompatibility) {
                        "auto"
                    } else if (round == 0) {
                        initialToolChoice
                    } else {
                        "auto"
                    },
                    parallelToolCallsEnabled = parallelToolCallsEnabled,
                    onParallelToolCallsUnsupported = {
                        parallelToolCallsEnabled = false
                        onParallelToolCallsUnsupported(parallelToolCallSupportKey)
                    },
                    onTextDelta = onAssistantTextDelta,
                    onReasoningDelta = onAssistantReasoningDelta,
                    onReasoningSummaryDelta = onAssistantReasoningSummaryDelta,
                    onTextReset = onAssistantTextReset,
                    onStreamingStatus = onStreamingStatus,
                )
            } catch (_: ParallelToolCallsUnsupportedRestart) {
                continue
            }

            response.usage?.let { turnUsage += it }
            conversation += response.assistantMessage

            if (response.assistantText.isNotBlank()) {
                lastAssistantText = response.assistantText
            }

            if (response.toolCalls.isEmpty()) {
                val trailingInjectedMessages = pollInjectedUserMessages()
                if (trailingInjectedMessages.isNotEmpty()) {
                    lastAssistantText = ""
                    conversation += client.buildConversation(
                        settings = settings,
                        messages = trailingInjectedMessages,
                    )
                    round += 1
                    continue
                }
                if (
                    forcedMarketSynthesisCount < MaxForcedMarketSynthesisContinuations &&
                    shouldForceMarketSynthesisContinuation(
                        assistantText = lastAssistantText,
                        marketDataToolCalled = marketDataToolCalled,
                        conversationDecision = conversationDecision,
                        planModeEnabled = planModeEnabled,
                    )
                ) {
                    forcedMarketSynthesisCount += 1
                    lastAssistantText = ""
                    conversationDecision = AgentConversationDecision.completed()
                    onAssistantTextReset()
                    conversation += client.buildConversation(
                        settings = settings,
                        messages = listOf(buildForcedMarketSynthesisMessage()),
                    )
                    round += 1
                    continue
                }
                break
            }

            onAssistantTextReset()
            var pendingAgentDisplayScreenshotMessage: LlmMessage? = null
            val toolResults = executeToolCalls(
                toolCalls = response.toolCalls,
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                availableSkills = resolvedAvailableSkills,
                activeSkills = resolvedActiveSkills,
                enabledToolGroups = normalizedToolGroups,
                agentModeEnabled = agentModeEnabled,
                planModeEnabled = planModeEnabled,
                round = round,
                parallelToolCallsEnabled = parallelToolCallsEnabled,
                onToolEvent = onToolEvent,
                onSkillActivated = onSkillActivated,
            )
            if (toolResults.any { it.name in MarketDataToolNames }) {
                marketDataToolCalled = true
            }
            toolResults.forEach { result ->
                if (result.name == "set_conversation_status") {
                    conversationDecision = parseAgentConversationDecisionOutput(result.rawOutput)
                }
                if (result.name == "update_task_state") {
                    parseTaskStateToolOutput(result.rawOutput)?.let { parsedTaskState ->
                        latestTaskState = parsedTaskState
                        updatedTaskState = parsedTaskState
                        onTaskStateUpdated(parsedTaskState)
                    }
                }
                buildAgentDisplayScreenshotMessage(result.rawOutput)?.let { screenshotMessage ->
                    pendingAgentDisplayScreenshotMessage = screenshotMessage
                }
            }
            if (toolResults.all { isInternalToolCall(it.name) }) {
                if (lastAssistantText.isNotBlank()) {
                    if (
                        forcedMarketSynthesisCount < MaxForcedMarketSynthesisContinuations &&
                        shouldForceMarketSynthesisContinuation(
                            assistantText = lastAssistantText,
                            marketDataToolCalled = marketDataToolCalled,
                            conversationDecision = conversationDecision,
                            planModeEnabled = planModeEnabled,
                        )
                    ) {
                        forcedMarketSynthesisCount += 1
                        conversation += client.buildToolResultMessages(
                            settings = settings,
                            results = toolResults.map { result ->
                                ChatCompletionToolResult(
                                    callId = result.id,
                                    name = result.name,
                                    output = result.visibleOutput,
                                )
                            },
                        )
                        lastAssistantText = ""
                        conversationDecision = AgentConversationDecision.completed()
                        onAssistantTextReset()
                        conversation += client.buildConversation(
                            settings = settings,
                            messages = listOf(buildForcedMarketSynthesisMessage()),
                        )
                        round += 1
                        continue
                    }
                    break
                }
                if (internalOnlyContinuationCount >= 2) break
                internalOnlyContinuationCount += 1
                conversation += client.buildToolResultMessages(
                    settings = settings,
                    results = toolResults.map { result ->
                        ChatCompletionToolResult(
                            callId = result.id,
                            name = result.name,
                            output = result.visibleOutput,
                        )
                    },
                )
                round += 1
                continue
            }
            internalOnlyContinuationCount = 0
            conversation += client.buildToolResultMessages(
                settings = settings,
                results = toolResults.map { result ->
                    ChatCompletionToolResult(
                        callId = result.id,
                        name = result.name,
                        output = result.visibleOutput,
                    )
                },
            )
            pendingAgentDisplayScreenshotMessage?.let { screenshotMessage ->
                lastAgentModeScreenshotMessageIndex?.let { index ->
                    if (index in conversation.indices) {
                        conversation.removeAt(index)
                    }
                }
                lastAgentModeScreenshotMessageIndex = null
                conversation += client.buildConversation(
                    settings = settings,
                    messages = listOf(screenshotMessage),
                )
                lastAgentModeScreenshotMessageIndex = conversation.lastIndex
            }
            round += 1
        }
        val finalTaskState = updatedTaskState ?: latestTaskState
        val finalConversationDecision = deriveAgentConversationDecision(
            explicitDecision = conversationDecision,
            taskState = finalTaskState,
            goalModeEnabled = goalModeEnabled,
        )
        Result.success(
            AgentTurnResult(
                assistantText = lastAssistantText.ifBlank { fallbackAssistantText(finalConversationDecision, finalTaskState) },
                conversationDecision = finalConversationDecision,
                taskState = updatedTaskState,
                usage = turnUsage,
            )
        )
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private suspend fun executeToolCalls(
        toolCalls: List<ChatCompletionToolCall>,
        settings: AppSettings,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill>,
        activeSkills: MutableList<ActiveSkillContext>,
        enabledToolGroups: List<String>,
        agentModeEnabled: Boolean,
        planModeEnabled: Boolean,
        round: Int,
        parallelToolCallsEnabled: Boolean,
        onToolEvent: suspend (AgentToolEvent) -> Unit,
        onSkillActivated: suspend (ActiveSkillContext) -> Unit,
    ): List<ExecutedToolCallResult> {
        val results = mutableListOf<ExecutedToolCallResult>()
        var index = 0
        while (index < toolCalls.size) {
            val current = IndexedToolCall(
                toolCall = toolCalls[index],
                id = toolCalls[index].id.ifBlank { "tool-$round-$index" },
            )
            if (!parallelToolCallsEnabled || !isParallelSafeToolCall(current.toolCall.name)) {
                if (!isInternalToolCall(current.toolCall.name)) {
                    onToolStarted(current, onToolEvent)
                }
                val result = executeToolCall(
                    indexedToolCall = current,
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                    availableSkills = availableSkills,
                    activeSkills = activeSkills,
                    enabledToolGroups = enabledToolGroups,
                    agentModeEnabled = agentModeEnabled,
                    planModeEnabled = planModeEnabled,
                    onSkillActivated = onSkillActivated,
                )
                if (!isInternalToolCall(result.name)) {
                    onToolCompleted(result, onToolEvent)
                }
                results += result
                index += 1
                continue
            }

            val batch = mutableListOf(current)
            index += 1
            while (
                index < toolCalls.size &&
                isParallelSafeToolCall(toolCalls[index].name)
            ) {
                batch += IndexedToolCall(
                    toolCall = toolCalls[index],
                    id = toolCalls[index].id.ifBlank { "tool-$round-$index" },
                )
                index += 1
            }

            batch.forEach {
                if (!isInternalToolCall(it.toolCall.name)) {
                    onToolStarted(it, onToolEvent)
                }
            }
            val indexedBatchResults = coroutineScope {
                batch.mapIndexed { batchIndex, item ->
                    async {
                        val result = executeToolCall(
                            indexedToolCall = item,
                            settings = settings,
                            workspaceDirectory = workspaceDirectory,
                            availableSkills = availableSkills,
                            activeSkills = activeSkills,
                            enabledToolGroups = enabledToolGroups,
                            agentModeEnabled = agentModeEnabled,
                            planModeEnabled = planModeEnabled,
                            onSkillActivated = onSkillActivated,
                        )
                        if (!isInternalToolCall(result.name)) {
                            onToolCompleted(result, onToolEvent)
                        }
                        batchIndex to result
                    }
                }.map { it.await() }
            }
            results += indexedBatchResults
                .sortedBy { it.first }
                .map { it.second }
        }
        return results
    }

    private suspend fun onToolStarted(
        indexedToolCall: IndexedToolCall,
        onToolEvent: suspend (AgentToolEvent) -> Unit,
    ) {
        onToolEvent(
            AgentToolEvent(
                id = indexedToolCall.id,
                name = indexedToolCall.toolCall.name,
                argumentsJson = indexedToolCall.toolCall.arguments,
            )
        )
    }

    private suspend fun onToolCompleted(
        result: ExecutedToolCallResult,
        onToolEvent: suspend (AgentToolEvent) -> Unit,
    ) {
        onToolEvent(
            AgentToolEvent(
                id = result.id,
                name = result.name,
                argumentsJson = result.argumentsJson,
                outputJson = result.visibleOutput,
            )
        )
    }

    private suspend fun executeToolCall(
        indexedToolCall: IndexedToolCall,
        settings: AppSettings,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill>,
        activeSkills: MutableList<ActiveSkillContext>,
        enabledToolGroups: List<String>,
        agentModeEnabled: Boolean,
        planModeEnabled: Boolean,
        onSkillActivated: suspend (ActiveSkillContext) -> Unit,
    ): ExecutedToolCallResult {
        val toolCall = indexedToolCall.toolCall
        var attempt = 0
        var rawOutput: String
        while (true) {
            rawOutput = try {
                executeFunctionCall(
                    toolCall = toolCall,
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                    availableSkills = availableSkills,
                    activeSkills = activeSkills,
                    enabledToolGroups = enabledToolGroups,
                    agentModeEnabled = agentModeEnabled,
                    planModeEnabled = planModeEnabled,
                    onSkillActivated = onSkillActivated,
                )
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (throwable: Throwable) {
                // 仅对疑似瞬时错误（网络/超时等）做有限次自动重试；确定性错误（如参数错误）直接回传模型。
                if (attempt < MaxTransientToolRetries && shouldReconnectLlmRequest(throwable)) {
                    attempt += 1
                    delay(800L * attempt)
                    continue
                }
                JSONObject().apply {
                    put("ok", false)
                    put("errmsg", throwable.message ?: "Tool execution failed.")
                }.toString()
            }
            break
        }
        val visibleOutput = sanitizeToolOutputForConversation(toolCall.name, rawOutput)
        return ExecutedToolCallResult(
            id = indexedToolCall.id,
            name = toolCall.name,
            argumentsJson = toolCall.arguments,
            rawOutput = rawOutput,
            visibleOutput = visibleOutput,
        )
    }

    private fun isParallelSafeToolCall(toolName: String): Boolean = when (toolName) {
        "run_tool_batch",
        "activate_skill",
        "agent_display",
        "set_conversation_status",
        "update_task_state" -> false

        else -> true
    }

    private fun isInternalToolCall(toolName: String): Boolean =
        toolName == "set_conversation_status" || toolName == "update_task_state"

    private suspend fun executeFunctionCall(
        toolCall: ChatCompletionToolCall,
        settings: AppSettings,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill>,
        activeSkills: MutableList<ActiveSkillContext>,
        enabledToolGroups: List<String>,
        agentModeEnabled: Boolean,
        planModeEnabled: Boolean,
        onSkillActivated: suspend (ActiveSkillContext) -> Unit,
    ): String {
        if (!isAetherToolAvailableForGroups(toolCall.name, enabledToolGroups, agentModeEnabled, planModeEnabled)) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Tool '${toolCall.name}' is disabled for this chat turn.")
            }.toString()
        }
        return when (toolCall.name) {
            "read" -> filesystemTool.executeRead(
                injectDefaultWorkingDirectory(toolCall.arguments, workspaceDirectory)
            )
            "edit" -> filesystemTool.executeEdit(
                injectDefaultWorkingDirectory(toolCall.arguments, workspaceDirectory)
            )
            "write" -> filesystemTool.executeWrite(
                injectDefaultWorkingDirectory(toolCall.arguments, workspaceDirectory)
            )
            "grep" -> filesystemTool.executeGrep(
                injectDefaultWorkingDirectory(toolCall.arguments, workspaceDirectory)
            )
            "find" -> filesystemTool.executeFind(
                injectDefaultWorkingDirectory(toolCall.arguments, workspaceDirectory)
            )
            "ls" -> filesystemTool.executeLs(
                injectDefaultWorkingDirectory(toolCall.arguments, workspaceDirectory)
            )
            "bash" -> bashTool.execute(
                injectDefaultWorkingDirectory(toolCall.arguments, workspaceDirectory)
            )
            "fetch_bash_output" -> bashTool.fetchExecution(toolCall.arguments)
            "kill_bash" -> bashTool.killExecution(toolCall.arguments)
            "sleep" -> executeSleep(toolCall.arguments)
            "set_conversation_status" -> executeSetConversationStatus(toolCall.arguments)
            "update_task_state" -> executeUpdateTaskState(toolCall.arguments)
            "activate_skill" -> executeActivateSkill(
                argumentsJson = toolCall.arguments,
                availableSkills = availableSkills,
                activeSkills = activeSkills,
                onSkillActivated = onSkillActivated,
            )
            "read_skill_resource" -> executeReadSkillResource(
                argumentsJson = toolCall.arguments,
                activeSkills = activeSkills,
            )
            "fetch_web_url" -> executeFetchWebUrl(toolCall.arguments)
            "tavily_search" -> executeTavilySearch(
                settings = settings,
                argumentsJson = toolCall.arguments,
            )
            "stock_market_data" -> executeStockMarketData(toolCall.arguments)
            "market_overview" -> executeMarketOverview(toolCall.arguments)
            "sector_heat" -> executeSectorHeat(toolCall.arguments)
            "run_tool_batch" -> executeRunToolBatch(
                argumentsJson = toolCall.arguments,
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                availableSkills = availableSkills,
                activeSkills = activeSkills,
                enabledToolGroups = enabledToolGroups,
                agentModeEnabled = agentModeEnabled,
                planModeEnabled = planModeEnabled,
                onSkillActivated = onSkillActivated,
            )
            "mcp_list_tools" -> executeMcpListTools(toolCall.arguments)
            "mcp_call_tool" -> executeMcpCallTool(toolCall.arguments)
            "mcp_list_resources" -> executeMcpListResources(toolCall.arguments)
            "mcp_read_resource" -> executeMcpReadResource(toolCall.arguments)
            "mcp_list_prompts" -> executeMcpListPrompts(toolCall.arguments)
            "mcp_get_prompt" -> executeMcpGetPrompt(toolCall.arguments)
            "analyze_image" -> executeAnalyzeImage(
                settings = settings,
                argumentsJson = injectDefaultWorkingDirectory(toolCall.arguments, workspaceDirectory),
            )
            "agent_display" -> agentModeController.execute(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                argumentsJson = toolCall.arguments,
            )
            else -> if (looksLikeMcpToolCallName(toolCall.name)) {
                mcpClientManager.callToolByName(toolCall.name, toolCall.arguments)
                    .getOrElse { throwable -> toolFailureOutput(throwable, "MCP tool call failed.") }
            } else {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "Unknown tool '${toolCall.name}'.")
                }.toString()
            }
        }
    }

    private fun executeSetConversationStatus(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
                put("status", AgentConversationStatus.Completed.storageValue)
            }.toString()
        val status = AgentConversationStatus.fromStorageValue(arguments.optString("status"))
        val reason = arguments.cleanOptionalString("reason")
            .take(MaxConversationDecisionTextChars)
        val nextPrompt = arguments.cleanOptionalString("next_prompt")
            .ifBlank { arguments.cleanOptionalString("nextPrompt") }
            .take(MaxConversationDecisionTextChars)
        return JSONObject().apply {
            put("ok", true)
            put("status", status.storageValue)
            put("reason", reason)
            put("next_prompt", nextPrompt)
        }.toString()
    }

    private fun fallbackAssistantText(
        conversationDecision: AgentConversationDecision,
        taskState: AgentTaskState?,
    ): String = when (conversationDecision.status) {
        AgentConversationStatus.WaitingForUser -> conversationDecision.reason.ifBlank {
            "I need your input before I can continue."
        }
        AgentConversationStatus.Blocked -> conversationDecision.reason.ifBlank {
            "I am blocked and cannot make meaningful progress yet."
        }
        AgentConversationStatus.Continue -> conversationDecision.reason.ifBlank {
            "I will continue with the next step."
        }
        AgentConversationStatus.Completed -> taskState?.summary
            ?.takeIf { it.isNotBlank() }
            ?: "Done."
    }

    private fun executeUpdateTaskState(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()
        val taskState = AgentTaskState(
            goal = arguments.cleanOptionalString("goal").take(MaxTaskStateTextChars),
            status = AgentTaskStatus.fromStorageValue(arguments.cleanOptionalString("status")),
            todos = parseTaskStateItems(arguments.optJSONArray("todos")),
            completionCriteria = arguments.stringArrayValue("completion_criteria", "completionCriteria")
                .map { it.take(MaxTaskStateItemTextChars) }
                .take(MaxTaskStateItemCount),
            summary = arguments.cleanOptionalString("summary").take(MaxTaskStateTextChars),
            updatedAtMillis = System.currentTimeMillis(),
        )
        return JSONObject().apply {
            put("ok", true)
            put("task_state", taskState.toJsonObject())
        }.toString()
    }

    private fun parseTaskStateToolOutput(output: String): AgentTaskState? {
        val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return null
        if (!parsed.optBoolean("ok", true)) return null
        val stateJson = parsed.optJSONObject("task_state") ?: parsed
        return parseTaskStateObject(stateJson)
    }

    private fun parseTaskStateObject(json: JSONObject): AgentTaskState = AgentTaskState(
        goal = json.cleanOptionalString("goal").take(MaxTaskStateTextChars),
        status = AgentTaskStatus.fromStorageValue(json.cleanOptionalString("status")),
        todos = parseTaskStateItems(json.optJSONArray("todos")),
        completionCriteria = json.stringArrayValue("completion_criteria", "completionCriteria")
            .map { it.take(MaxTaskStateItemTextChars) }
            .take(MaxTaskStateItemCount),
        summary = json.cleanOptionalString("summary").take(MaxTaskStateTextChars),
        updatedAtMillis = json.optLong("updated_at_millis").takeIf { it > 0L }
            ?: json.optLong("updatedAtMillis").takeIf { it > 0L }
            ?: System.currentTimeMillis(),
    )

    private fun parseTaskStateItems(items: JSONArray?): List<AgentTaskItem> {
        if (items == null) return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                if (size >= MaxTaskStateItemCount) break
                val item = items.optJSONObject(index) ?: continue
                val text = item.cleanOptionalString("text").take(MaxTaskStateItemTextChars)
                if (text.isBlank()) continue
                add(
                    AgentTaskItem(
                        id = item.cleanOptionalString("id")
                            .take(80)
                            .ifBlank { "task-${index + 1}" },
                        text = text,
                        done = item.optBoolean("done", item.optBoolean("completed", false)),
                    )
                )
            }
        }
    }

    private fun AgentTaskState.toJsonObject(): JSONObject = JSONObject().apply {
        put("goal", goal)
        put("status", status.storageValue)
        put("summary", summary)
        put("updated_at_millis", updatedAtMillis)
        put("todos", JSONArray().apply {
            todos.forEach { item ->
                put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("text", item.text)
                        put("done", item.done)
                    }
                )
            }
        })
        put("completion_criteria", JSONArray().apply { completionCriteria.forEach(::put) })
    }

    private fun sanitizeToolOutputForConversation(
        toolName: String,
        output: String,
    ): String {
        if (toolName != "agent_display") return output
        val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return output
        if (!parsed.has("screenshot_base64")) return output
        parsed.remove("screenshot_base64")
        parsed.put("screenshot_injected_into_next_model_request", true)
        return parsed.toString()
    }

    private fun buildAgentDisplayScreenshotMessage(output: String): LlmMessage? {
        val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return null
        if (!parsed.optBoolean("ok")) return null
        val base64Data = parsed.optString("screenshot_base64").takeIf { it.isNotBlank() }
            ?: return null
        val mimeType = parsed.optString("screenshot_mime_type").ifBlank { "image/png" }
        val displayId = parsed.opt("display_id")?.toString().orEmpty()
        return LlmMessage(
            role = "user",
            contentParts = listOf(
                LlmTextPart(
                    "Latest Agent Mode virtual display screenshot" +
                        if (displayId.isNotBlank() && displayId != "null") " for display $displayId." else "."
                ),
                LlmImagePart(
                    mimeType = mimeType,
                    base64Data = base64Data,
                ),
            ),
        )
    }

    private fun toolFailureOutput(
        throwable: Throwable,
        fallbackMessage: String,
        configure: JSONObject.() -> Unit = {},
    ): String {
        if (throwable is CancellationException) throw throwable
        return JSONObject().apply {
            put("ok", false)
            configure()
            put("errmsg", throwable.message ?: fallbackMessage)
        }.toString()
    }

    private suspend fun executeRunToolBatch(
        argumentsJson: String,
        settings: AppSettings,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill>,
        activeSkills: MutableList<ActiveSkillContext>,
        enabledToolGroups: List<String>,
        agentModeEnabled: Boolean,
        planModeEnabled: Boolean,
        onSkillActivated: suspend (ActiveSkillContext) -> Unit,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()
        val mode = arguments.optString("mode").trim().lowercase()
        val runInParallel = when (mode) {
            "parallel", "concurrent", "simultaneous" -> true
            "sequential", "serial", "ordered" -> false
            else -> return JSONObject().apply {
                put("ok", false)
                put("errmsg", "mode must be either 'parallel' or 'sequential'.")
            }.toString()
        }
        val calls = parseRunToolBatchCalls(arguments.optJSONArray("calls"))
        if (calls.isEmpty()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "calls must contain at least one tool call.")
            }.toString()
        }
        val nestedCall = calls.firstOrNull { it.toolName == "run_tool_batch" }
        if (nestedCall != null) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "run_tool_batch cannot call itself.")
            }.toString()
        }
        val internalCall = calls.firstOrNull { isInternalToolCall(it.toolName) }
        if (internalCall != null) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "${internalCall.toolName} must be called as a top-level tool, not inside run_tool_batch.")
            }.toString()
        }
        if (runInParallel) {
            val blockedCall = calls.firstOrNull { !canRunInsideExplicitParallelBatch(it.toolName) }
            if (blockedCall != null) {
                return JSONObject().apply {
                    put("ok", false)
                    put("errmsg", "${blockedCall.toolName} must be run sequentially, not inside a parallel batch.")
                }.toString()
            }
        }

        val results = if (runInParallel) {
            coroutineScope {
                calls.mapIndexed { index, batchCall ->
                    async {
                        index to executeBatchToolCall(
                            batchCall = batchCall,
                            settings = settings,
                            workspaceDirectory = workspaceDirectory,
                            availableSkills = availableSkills,
                            activeSkills = activeSkills,
                            enabledToolGroups = enabledToolGroups,
                            agentModeEnabled = agentModeEnabled,
                            planModeEnabled = planModeEnabled,
                            onSkillActivated = onSkillActivated,
                        )
                    }
                }.map { it.await() }
                    .sortedBy { it.first }
                    .map { it.second }
            }
        } else {
            calls.map { batchCall ->
                executeBatchToolCall(
                    batchCall = batchCall,
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                    availableSkills = availableSkills,
                    activeSkills = activeSkills,
                    enabledToolGroups = enabledToolGroups,
                    agentModeEnabled = agentModeEnabled,
                    planModeEnabled = planModeEnabled,
                    onSkillActivated = onSkillActivated,
                )
            }
        }

        return JSONObject().apply {
            put("ok", results.all { it.optBoolean("ok", true) })
            put("mode", if (runInParallel) "parallel" else "sequential")
            put("results", JSONArray().apply { results.forEach(::put) })
        }.toString()
    }

    private fun parseRunToolBatchCalls(calls: JSONArray?): List<BatchToolCall> {
        if (calls == null) return emptyList()
        return buildList {
            for (index in 0 until calls.length()) {
                val call = calls.optJSONObject(index) ?: continue
                val toolName = call.optString("tool_name").trim().ifBlank {
                    call.optString("toolName").trim()
                }
                if (toolName.isBlank()) continue
                val argumentsJson = call.optString("arguments_json").trim().ifBlank {
                    call.optString("argumentsJson").trim()
                }
                val rawArguments = call.opt("arguments")
                add(
                    BatchToolCall(
                        toolName = toolName,
                        argumentsJson = argumentsJson.ifBlank {
                            when (rawArguments) {
                            null,
                            JSONObject.NULL -> "{}"
                            is JSONObject -> rawArguments.toString()
                            is String -> rawArguments.ifBlank { "{}" }
                            else -> JSONObject.wrap(rawArguments)?.toString() ?: "{}"
                            }
                        },
                    )
                )
            }
        }
    }

    private suspend fun executeBatchToolCall(
        batchCall: BatchToolCall,
        settings: AppSettings,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill>,
        activeSkills: MutableList<ActiveSkillContext>,
        enabledToolGroups: List<String>,
        agentModeEnabled: Boolean,
        planModeEnabled: Boolean,
        onSkillActivated: suspend (ActiveSkillContext) -> Unit,
    ): JSONObject {
        val rawOutput = try {
            executeFunctionCall(
                toolCall = ChatCompletionToolCall(
                    id = "",
                    name = batchCall.toolName,
                    arguments = batchCall.argumentsJson,
                ),
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                availableSkills = availableSkills,
                activeSkills = activeSkills,
                enabledToolGroups = enabledToolGroups,
                agentModeEnabled = agentModeEnabled,
                planModeEnabled = planModeEnabled,
                onSkillActivated = onSkillActivated,
            )
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (throwable: Throwable) {
            toolFailureOutput(throwable, "Tool execution failed.")
        }
        val visibleOutput = sanitizeToolOutputForConversation(batchCall.toolName, rawOutput)
        return JSONObject().apply {
            put("ok", inferToolOutputOk(visibleOutput))
            put("tool_name", batchCall.toolName)
            put("arguments", runCatching { JSONObject(batchCall.argumentsJson) }.getOrDefault(JSONObject()))
            put("output", visibleOutput)
        }
    }

    private fun inferToolOutputOk(output: String): Boolean {
        val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return true
        return parsed.optBoolean("ok", !parsed.optBoolean("err", false))
    }

    private fun canRunInsideExplicitParallelBatch(toolName: String): Boolean = when (toolName) {
        "run_tool_batch",
        "activate_skill",
        "set_conversation_status",
        "update_task_state",
        "agent_display" -> false

        else -> true
    }

    private suspend fun streamChatCompletionWithReconnect(
        settings: AppSettings,
        systemPrompt: String,
        conversation: List<JSONObject>,
        tools: List<JSONObject>,
        toolChoice: String?,
        parallelToolCallsEnabled: Boolean,
        onParallelToolCallsUnsupported: suspend () -> Unit,
        onTextDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
        onReasoningSummaryDelta: suspend (String) -> Unit,
        onTextReset: suspend () -> Unit,
        onStreamingStatus: suspend (StreamingStatus?) -> Unit,
    ): ChatCompletionResult {
        var reconnectFailures = 0
        var reconnectStatusVisible = false
        var currentParallelToolCallsEnabled = parallelToolCallsEnabled
        val reconnectFields = buildStreamReconnectFields(
            settings = settings,
            conversation = conversation,
            tools = tools,
            toolChoice = toolChoice,
            parallelToolCallsEnabled = parallelToolCallsEnabled,
        )

        while (true) {
            var receivedTextThisAttempt = false
            val result = streamChatCompletionAttempt(
                settings = settings,
                systemPrompt = systemPrompt,
                conversation = conversation,
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCallsEnabled = currentParallelToolCallsEnabled,
                onTextDelta = { delta ->
                    if (delta.isNotEmpty()) {
                        receivedTextThisAttempt = true
                    }
                    onTextDelta(delta)
                },
                onReasoningDelta = onReasoningDelta,
                onReasoningSummaryDelta = onReasoningSummaryDelta,
                onStreamActivity = {
                    if (reconnectStatusVisible) {
                        reconnectStatusVisible = false
                        onStreamingStatus(null)
                    }
                },
            )

            result.onSuccess {
                if (reconnectStatusVisible) {
                    reconnectStatusVisible = false
                    onStreamingStatus(null)
                }
                return it
            }

            val failure = result.exceptionOrNull() ?: error("Streaming request failed without an exception.")
            if (currentParallelToolCallsEnabled && isParallelToolCallsUnsupportedFailure(failure)) {
                AetherLog.event(
                    AetherAgentLogTag,
                    event = "agent_stream_parallel_tool_calls_restart",
                    fields = reconnectFields + buildReconnectFailureFields(
                        failure = failure,
                        attemptNumber = reconnectFailures + 1,
                        delayMillis = 0L,
                        receivedText = receivedTextThisAttempt,
                    ),
                    level = AetherLog.Level.Warn,
                )
                if (receivedTextThisAttempt) {
                    onTextReset()
                }
                onParallelToolCallsUnsupported()
                throw ParallelToolCallsUnsupportedRestart()
            }
            if (!shouldReconnectLlmRequest(failure) || reconnectFailures >= LlmReconnectDelayScheduleMillis.size) {
                AetherLog.event(
                    AetherAgentLogTag,
                    event = "agent_stream_reconnect_give_up",
                    fields = reconnectFields + buildReconnectFailureFields(
                        failure = failure,
                        attemptNumber = reconnectFailures + 1,
                        delayMillis = 0L,
                        receivedText = receivedTextThisAttempt,
                    ),
                    level = AetherLog.Level.Warn,
                )
                if (reconnectStatusVisible) {
                    reconnectStatusVisible = false
                    onStreamingStatus(null)
                }
                throw failure
            }

            if (receivedTextThisAttempt) {
                onTextReset()
            }
            val attemptNumber = reconnectFailures + 1
            val reconnectDelayMillis = resolveReconnectDelayMillis(failure, reconnectFailures)
            AetherLog.event(
                AetherAgentLogTag,
                event = "agent_stream_reconnect_scheduled",
                fields = reconnectFields + buildReconnectFailureFields(
                    failure = failure,
                    attemptNumber = attemptNumber,
                    delayMillis = reconnectDelayMillis,
                    receivedText = receivedTextThisAttempt,
                ),
                level = AetherLog.Level.Warn,
            )
            reconnectStatusVisible = true
            onStreamingStatus(
                StreamingStatus(
                    text = "Reconnecting... $attemptNumber/${LlmReconnectDelayScheduleMillis.size}",
                    detail = formatReconnectFailureDetail(
                        throwable = failure,
                        attemptNumber = attemptNumber,
                        maxAttempts = LlmReconnectDelayScheduleMillis.size,
                        delayMillis = reconnectDelayMillis,
                    ),
                )
            )
            delay(reconnectDelayMillis)
            reconnectFailures += 1
        }
    }

    /**
     * 构建流式重连日志字段，不包含对话正文或系统提示词。
     */
    private fun buildStreamReconnectFields(
        settings: AppSettings,
        conversation: List<JSONObject>,
        tools: List<JSONObject>,
        toolChoice: String?,
        parallelToolCallsEnabled: Boolean,
    ): Map<String, Any?> = mapOf(
        "conversation_count" to conversation.size,
        "max_attempts" to LlmReconnectDelayScheduleMillis.size,
        "model" to settings.modelId,
        "parallel_tool_calls" to parallelToolCallsEnabled,
        "provider" to settings.provider.storageValue,
        "tool_choice" to toolChoice.orEmpty(),
        "tool_count" to tools.size,
    )

    /**
     * 构建重连失败日志字段，仅记录异常类型与状态码，不记录错误正文。
     */
    private fun buildReconnectFailureFields(
        failure: Throwable,
        attemptNumber: Int,
        delayMillis: Long,
        receivedText: Boolean,
    ): Map<String, Any?> = mapOf(
        "attempt" to attemptNumber,
        "delay_ms" to delayMillis,
        "error_type" to failure.javaClass.simpleName.ifBlank { "Throwable" },
        "http_status" to ((failure as? LlmHttpException)?.statusCode ?: -1),
        "received_text" to receivedText,
        "retry_after_ms" to (preferredRetryDelayMillis(failure) ?: -1),
    )

    private fun formatReconnectFailureDetail(
        throwable: Throwable,
        attemptNumber: Int,
        maxAttempts: Int,
        delayMillis: Long,
    ): String = buildString {
        appendLine("Attempt $attemptNumber/$maxAttempts failed.")
        appendLine("Retry delay: ${formatReconnectDelay(delayMillis)}")
        append("Error: ")
        append(formatThrowableSummary(throwable))

        var cause = throwable.cause
        var depth = 0
        while (cause != null && cause !== throwable && depth < 3) {
            appendLine()
            append("Cause: ")
            append(formatThrowableSummary(cause))
            cause = cause.cause
            depth += 1
        }
    }

    private fun formatReconnectDelay(delayMillis: Long): String =
        if (delayMillis % 1000L == 0L) {
            "${delayMillis / 1000L}s"
        } else {
            "${delayMillis}ms"
        }

    private fun formatThrowableSummary(throwable: Throwable): String {
        val type = throwable.javaClass.simpleName.ifBlank { "Throwable" }
        val statusPrefix = (throwable as? LlmHttpException)?.let { "HTTP ${it.statusCode}: " }.orEmpty()
        val message = throwable.message?.trim().orEmpty()
        return if (message.isBlank()) {
            "$statusPrefix$type"
        } else {
            "$statusPrefix$type: $message"
        }
    }

    private suspend fun streamChatCompletionAttempt(
        settings: AppSettings,
        systemPrompt: String,
        conversation: List<JSONObject>,
        tools: List<JSONObject>,
        toolChoice: String?,
        parallelToolCallsEnabled: Boolean,
        onTextDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
        onReasoningSummaryDelta: suspend (String) -> Unit,
        onStreamActivity: suspend () -> Unit,
    ): Result<ChatCompletionResult> = coroutineScope {
        val timeoutMillis = settings.llmInactivityReconnectTimeoutSeconds * 1000L
        val lastActivityAt = AtomicReference(SystemClock.elapsedRealtime())
        val inactivityFailure = AtomicReference<LlmInactivityTimeoutException?>(null)
        val responseJob = async {
            client.streamChatCompletion(
                settings = settings,
                systemPrompt = systemPrompt,
                conversation = conversation,
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCalls = if (parallelToolCallsEnabled && settings.provider.supportsParallelToolCallParameter) {
                    true
                } else {
                    null
                },
                onTextDelta = onTextDelta,
                onReasoningDelta = onReasoningDelta,
                onReasoningSummaryDelta = onReasoningSummaryDelta,
                onStreamActivity = {
                    lastActivityAt.set(SystemClock.elapsedRealtime())
                    onStreamActivity()
                },
            )
        }
        val watchdogJob = launch {
            val checkIntervalMillis = timeoutMillis.coerceAtMost(5_000L).coerceAtLeast(1_000L)
            while (responseJob.isActive) {
                delay(checkIntervalMillis)
                if (!responseJob.isActive) break
                if (SystemClock.elapsedRealtime() - lastActivityAt.get() >= timeoutMillis) {
                    val failure = LlmInactivityTimeoutException(timeoutMillis)
                    inactivityFailure.compareAndSet(null, failure)
                    responseJob.cancel(
                        LlmInactivityTimeoutCancellationException(
                            failure.message ?: "LLM inactivity timeout."
                        )
                    )
                    break
                }
            }
        }

        try {
            responseJob.await()
        } catch (cancellationException: CancellationException) {
            inactivityFailure.get()?.let { return@coroutineScope Result.failure(it) }
            throw cancellationException
        } finally {
            watchdogJob.cancelAndJoin()
        }
    }

    private suspend fun executeMcpListTools(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
        return mcpClientManager.listTools(
            serverId = extractMcpServerId(arguments),
        ).getOrElse { throwable -> toolFailureOutput(throwable, "Couldn't list MCP tools.") }
    }

    private suspend fun executeMcpCallTool(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()
        val serverId = extractMcpServerId(arguments)
        val toolName = arguments.optString("tool_name").trim().ifBlank {
            arguments.optString("toolName").trim()
        }
        val toolArguments = parseToolArgumentsObject(arguments.opt("arguments")).getOrElse { throwable ->
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", throwable.message ?: "'arguments' must be a JSON object string.")
            }.toString()
        }
        if (serverId.isBlank() || toolName.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Both 'server_id' and 'tool_name' are required.")
            }.toString()
        }
        return mcpClientManager.callTool(serverId, toolName, toolArguments)
            .getOrElse { throwable -> toolFailureOutput(throwable, "Couldn't call the MCP tool.") }
    }

    private suspend fun executeMcpListResources(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
        return mcpClientManager.listResources(
            serverId = extractMcpServerId(arguments),
        ).getOrElse { throwable -> toolFailureOutput(throwable, "Couldn't list MCP resources.") }
    }

    private suspend fun executeMcpReadResource(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()
        val serverId = extractMcpServerId(arguments)
        val uri = arguments.optString("uri").trim()
        if (serverId.isBlank() || uri.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Both 'server_id' and 'uri' are required.")
            }.toString()
        }
        return mcpClientManager.readResource(serverId, uri)
            .getOrElse { throwable -> toolFailureOutput(throwable, "Couldn't read MCP resource.") }
    }

    private suspend fun executeMcpListPrompts(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
        return mcpClientManager.listPrompts(
            serverId = extractMcpServerId(arguments),
        ).getOrElse { throwable -> toolFailureOutput(throwable, "Couldn't list MCP prompts.") }
    }

    private suspend fun executeMcpGetPrompt(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()
        val serverId = extractMcpServerId(arguments)
        val promptName = arguments.optString("name").trim()
        val promptArguments = parseToolArgumentsObject(arguments.opt("arguments")).getOrElse { throwable ->
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", throwable.message ?: "'arguments' must be a JSON object string.")
            }.toString()
        }
        if (serverId.isBlank() || promptName.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Both 'server_id' and 'name' are required.")
            }.toString()
        }
        return mcpClientManager.getPrompt(serverId, promptName, promptArguments)
            .getOrElse { throwable -> toolFailureOutput(throwable, "Couldn't fetch the MCP prompt.") }
    }

    private suspend fun executeActivateSkill(
        argumentsJson: String,
        availableSkills: List<InstalledSkill>,
        activeSkills: MutableList<ActiveSkillContext>,
        onSkillActivated: suspend (ActiveSkillContext) -> Unit,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()
        val requestedName = arguments.optString("name").trim()
        if (requestedName.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'name' argument.")
            }.toString()
        }
        val skill = availableSkills.firstOrNull {
            it.name.equals(requestedName, ignoreCase = true) ||
                it.id.equals(requestedName, ignoreCase = true)
        } ?: return JSONObject().apply {
            put("ok", false)
                put("errmsg", "No installed enabled skill matched '$requestedName'.")
            put(
                "available_skills",
                JSONArray().apply { availableSkills.take(32).forEach { put(it.name) } },
            )
        }.toString()

        val activeSkill = skillManager.buildActiveSkillContext(skill).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Couldn't activate ${skill.name}.")
        }

        val existingIndex = activeSkills.indexOfFirst { it.skillId == activeSkill.skillId }
        if (existingIndex >= 0) {
            activeSkills[existingIndex] = activeSkill
        } else {
            activeSkills += activeSkill
        }
        onSkillActivated(activeSkill)

        return JSONObject().apply {
            put("ok", true)
            put("name", activeSkill.name)
            put("skill_id", activeSkill.skillId)
            put("description", activeSkill.description)
            put("compatibility", activeSkill.compatibility)
            put("skill_root_path", activeSkill.skillRootPath)
            put("body_markdown", activeSkill.bodyMarkdown)
            put("allowed_tools", JSONArray().apply { activeSkill.allowedTools.forEach(::put) })
            put(
                "resources",
                JSONArray().apply {
                    activeSkill.resourceEntries.forEach { resource ->
                        put(
                            JSONObject().apply {
                                put("path", resource.relativePath)
                                put("kind", resource.kind.storageValue)
                            }
                        )
                    }
                },
            )
            put(
                "stdout",
                "Activated Agent Skill ${activeSkill.name} with ${activeSkill.resourceEntries.size} bundled files.",
            )
        }.toString()
    }

    private fun executeReadSkillResource(
        argumentsJson: String,
        activeSkills: List<ActiveSkillContext>,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()
        val requestedSkill = arguments.optString("skill").trim().ifBlank {
            arguments.optString("name").trim().ifBlank {
                arguments.optString("skill_id").trim()
            }
        }
        val requestedPath = arguments.optString("relative_path").trim().ifBlank {
            arguments.optString("path").trim()
        }
        if (requestedSkill.isBlank() || requestedPath.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Both 'skill' and 'relative_path' are required.")
            }.toString()
        }
        val skill = activeSkills.firstOrNull {
            it.name.equals(requestedSkill, ignoreCase = true) ||
                it.skillId.equals(requestedSkill, ignoreCase = true)
        } ?: return JSONObject().apply {
            put("ok", false)
            put("errmsg", "No active skill matched '$requestedSkill'. Call activate_skill first.")
            put("active_skills", JSONArray().apply { activeSkills.forEach { put(it.name) } })
        }.toString()

        val normalizedPath = requestedPath.replace('\\', '/').trim('/')
        if (
            normalizedPath.isBlank() ||
            normalizedPath.startsWith("../") ||
            normalizedPath.contains("/../") ||
            File(normalizedPath).isAbsolute
        ) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "relative_path must stay inside the active skill directory.")
            }.toString()
        }

        val root = runCatching { File(skill.skillRootPath).canonicalFile }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Skill root is not readable.")
            }.toString()
        val file = runCatching { File(root, normalizedPath).canonicalFile }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Skill resource path could not be resolved.")
            }.toString()
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Skill resource was not found inside the active skill directory.")
            }.toString()
        }
        if (file.length() > MaxSkillResourceBytes) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Skill resource is too large to read in one tool call.")
                put("size_bytes", file.length())
                put("max_bytes", MaxSkillResourceBytes)
            }.toString()
        }

        val bytes = runCatching { file.readBytes() }.getOrElse { throwable ->
            return toolFailureOutput(throwable, "Couldn't read skill resource.")
        }
        val maxChars = arguments.optInt("max_chars", DefaultSkillResourceMaxChars)
            .coerceIn(1, 100_000)
        val isText = isLikelyTextResource(file.name, bytes)
        return JSONObject().apply {
            put("ok", true)
            put("skill", skill.name)
            put("skill_id", skill.skillId)
            put("relative_path", normalizedPath)
            put("size_bytes", bytes.size)
            if (isText) {
                val text = bytes.toString(Charsets.UTF_8)
                val truncated = text.length > maxChars
                put("content", if (truncated) text.take(maxChars) else text)
                put("truncated", truncated)
                put("encoding", "utf-8")
            } else {
                put("base64", Base64.getEncoder().encodeToString(bytes))
                put("encoding", "base64")
            }
        }.toString()
    }

    private suspend fun executeAnalyzeImage(
        settings: AppSettings,
        argumentsJson: String,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()

        val path = arguments.cleanOptionalString("path")
        val workingDirectory = arguments.stringValue("working_directory", "workingDirectory")
        val prompt = arguments.optString("prompt").trim().ifBlank {
            "Describe the image and answer any relevant details needed for the task."
        }

        if (path.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'path' argument.")
            }.toString()
        }

        // Try to read within the size limit. If the file is too large, fall back to
        // an oversized read (up to 32 MB) and then compress the image on-device before
        // passing it to the model.
        val oversizedReadLimit = 32 * 1024 * 1024
        val rawPayloadResult = workspaceFileBridge.readWorkspaceFile(
            path = path,
            workingDirectory = workingDirectory,
            byteLimit = MaxAnalyzeImageBytes,
        )

        val (imageBytes, usedMimeType, wasCompressed) = if (rawPayloadResult.isSuccess) {
            val p = rawPayloadResult.getOrThrow()
            val mime = guessImageMimeType(p.absolutePath)
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("path", p.absolutePath)
                    put("errmsg", "The selected file does not look like a supported image.")
                }.toString()
            if (p.bytes.isEmpty()) {
                return JSONObject().apply {
                    put("ok", false)
                    put("path", p.absolutePath)
                    put("errmsg", "The selected image is empty.")
                }.toString()
            }
            Triple(p.bytes, mime, false)
        } else {
            val err = rawPayloadResult.exceptionOrNull()
            val isTooBig = err?.message?.contains("larger than", ignoreCase = true) == true
            if (!isTooBig) {
                return toolFailureOutput(
                    err ?: Exception("Couldn't read the image from the workspace."),
                    "Couldn't read the image from the workspace.",
                ) {
                    put("path", workspaceFileBridge.resolveTermuxPath(path, workingDirectory))
                }
            }
            // Image is larger than MaxAnalyzeImageBytes — try oversized read then compress.
            val oversizedPayload = workspaceFileBridge.readWorkspaceFile(
                path = path,
                workingDirectory = workingDirectory,
                byteLimit = oversizedReadLimit,
            ).getOrElse { throwable ->
                return toolFailureOutput(throwable, "Couldn't read the image from the workspace.") {
                    put("path", workspaceFileBridge.resolveTermuxPath(path, workingDirectory))
                }
            }
            val mime = guessImageMimeType(oversizedPayload.absolutePath)
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("path", oversizedPayload.absolutePath)
                    put("errmsg", "The selected file does not look like a supported image.")
                }.toString()
            if (oversizedPayload.bytes.isEmpty()) {
                return JSONObject().apply {
                    put("ok", false)
                    put("path", oversizedPayload.absolutePath)
                    put("errmsg", "The selected image is empty.")
                }.toString()
            }
            val compressed = compressImageBytes(oversizedPayload.bytes, MaxAnalyzeImageBytes)
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("path", oversizedPayload.absolutePath)
                    put("errmsg", "Image is too large to analyze (over ${oversizedReadLimit / 1024 / 1024} MB) and could not be compressed.")
                    put("size_bytes", oversizedPayload.sizeBytes)
                }.toString()
            Triple(compressed, "image/jpeg", true)
        }

        val absolutePath = workspaceFileBridge.resolveTermuxPath(path, workingDirectory)

        val response = client.createChatCompletion(
            settings = settings,
            systemPrompt = "You are an image analysis helper for an Android coding agent. Answer only with observations and conclusions grounded in the image and the prompt.",
            conversation = client.buildConversation(
                settings = settings,
                messages = listOf(
                    LlmMessage(
                        role = "user",
                        contentParts = listOf(
                            LlmTextPart(prompt),
                            LlmImagePart(
                                mimeType = usedMimeType,
                                base64Data = Base64.getEncoder().encodeToString(imageBytes),
                            ),
                        ),
                    ),
                ),
            ),
        ).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Image analysis request failed.") {
                put("path", absolutePath)
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("path", absolutePath)
            put("prompt", prompt)
            put("analysis", response.assistantText)
            put("stdout", response.assistantText)
            if (wasCompressed) {
                put("note", "Image was automatically compressed before analysis because it exceeded the 5 MB size limit.")
            }
        }.toString()
    }

    /**
     * Compress [bytes] (any image format) to JPEG so that the output is at most [targetBytes].
     * Returns null only if the image cannot be decoded at all.
     * Progressively reduces resolution and quality until the target is met or a minimum
     * quality / dimension floor is hit.
     */
    private fun compressImageBytes(bytes: ByteArray, targetBytes: Int): ByteArray? {
        val original = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return null

        var bitmap = original
        var quality = 85

        // First pass: try compressing at original size with decreasing quality
        while (quality >= 40) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val compressed = out.toByteArray()
            if (compressed.size <= targetBytes) {
                if (bitmap !== original) bitmap.recycle()
                return compressed
            }
            quality -= 15
        }

        // Second pass: progressively scale down the image
        var scale = 0.75f
        while (scale >= 0.15f) {
            val newWidth = (original.width * scale).toInt().coerceAtLeast(64)
            val newHeight = (original.height * scale).toInt().coerceAtLeast(64)
            val scaled = runCatching {
                Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
            }.getOrNull() ?: break

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
            val compressed = out.toByteArray()
            scaled.recycle()
            if (compressed.size <= targetBytes) {
                if (bitmap !== original) bitmap.recycle()
                original.recycle()
                return compressed
            }
            scale -= 0.15f
        }

        if (bitmap !== original) bitmap.recycle()
        original.recycle()
        return null
    }

    private suspend fun executeFetchWebUrl(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()

        val url = arguments.optString("url").trim()
        if (url.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'url' argument.")
            }.toString()
        }

        val maxChars = when {
            arguments.has("max_chars") -> arguments.optInt("max_chars")
            arguments.has("maxChars") -> arguments.optInt("maxChars")
            else -> 20_000
        }

        val page = webToolsClient.fetchUrlAsMarkdown(
            url = url,
            maxChars = maxChars,
        ).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Couldn't fetch the URL.") {
                put("url", url)
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("request_url", page.requestUrl)
            put("final_url", page.finalUrl)
            put("title", page.title)
            put("content_type", page.contentType)
            put("markdown", page.markdown)
            put("truncated", page.wasTruncated)
            put(
                "stdout",
                buildString {
                    append("Fetched ")
                    append(page.title.ifBlank { page.finalUrl })
                    if (page.wasTruncated) {
                        append(" (truncated)")
                    }
                },
            )
        }.toString()
    }

    private suspend fun executeTavilySearch(
        settings: AppSettings,
        argumentsJson: String,
    ): String {
        if (settings.tavilyApiKey.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put(
                    "errmsg",
                    "Tavily API key is not configured. Add it in Settings > Web Tools before using tavily_search.",
                )
            }.toString()
        }

        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()

        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'query' argument.")
            }.toString()
        }

        val response = webToolsClient.searchTavily(
            apiKey = settings.tavilyApiKey,
            request = TavilySearchRequest(
                query = query,
                topic = arguments.stringValue("topic").ifBlank { "general" },
                searchDepth = arguments.stringValue("search_depth", "searchDepth").ifBlank { "basic" },
                maxResults = arguments.intValue("max_results", "maxResults") ?: 5,
                timeRange = arguments.stringValue("time_range", "timeRange").ifBlank { null },
                includeAnswer = arguments.booleanValue("include_answer", "includeAnswer") ?: true,
                includeRawContent = arguments.booleanValue("include_raw_content", "includeRawContent") ?: false,
                includeDomains = arguments.stringArrayValue("include_domains", "includeDomains"),
                excludeDomains = arguments.stringArrayValue("exclude_domains", "excludeDomains"),
                country = arguments.stringValue("country").ifBlank { null },
                startDate = arguments.stringValue("start_date", "startDate").ifBlank { null },
                endDate = arguments.stringValue("end_date", "endDate").ifBlank { null },
            ),
        ).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Tavily search failed.") {
                put("query", query)
            }
        }

        response.put("ok", true)
        response.put("stdout", buildTavilySearchSummary(response))
        return response.toString()
    }

    private suspend fun executeMarketOverview(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: JSONObject()
        val includeSectors = args.optBoolean("include_sectors", false)
            || args.optBoolean("includeSectors", false)
        val sectorLimit = args.optInt("sector_limit", 20).coerceIn(5, 50)

        val cached = marketMonitorRepository?.cachedSnapshot()
        val snapshot = cached?.takeIf { it.indices.isNotEmpty() && (!includeSectors || it.industrySectors.isNotEmpty()) }
            ?: marketMonitorRepository?.refreshOnce()
        val indices = snapshot?.indices?.takeIf { it.isNotEmpty() }
            ?: webToolsClient.fetchMarketIndices().getOrElse { t ->
                return toolFailureOutput(t, "市场指数获取失败。") {}
            }
        val breadth = snapshot?.breadth ?: webToolsClient.fetchMarketBreadth().getOrDefault(MarketBreadth())

        val summaryLines = mutableListOf<String>()
        summaryLines.add("【大盘指数】is_market_open=${MarketTradingCalendar.isTrading()}")
        // 统一用数值类型输出，避免模型对字符串与数值字段解析不一致。
        val indicesArray = JSONArray().apply {
            indices.forEach { idx ->
                val sign = if (idx.changePercent >= 0) "+" else ""
                summaryLines.add("${idx.name}: ${String.format("%.2f", idx.price)}  $sign${String.format("%.2f", idx.changePercent)}%")
                put(JSONObject().apply {
                    put("code", idx.code)
                    put("name", idx.name)
                    put("price", idx.price)               // Double
                    put("change", idx.change)             // Double
                    put("change_percent", idx.changePercent) // Double
                })
            }
        }

        val output = JSONObject().apply {
            put("ok", true)
            put("source", "Eastmoney")
            put("is_market_open", MarketTradingCalendar.isTrading())
            put("updated_at", System.currentTimeMillis())
            put("indices", indicesArray)
            put("breadth", buildBreadthObject(breadth))
        }
        summaryLines.add("【市场宽度】上涨${breadth.risingCount} 下跌${breadth.fallingCount} 平盘${breadth.flatCount} 涨停${breadth.limitUpCount} 跌停${breadth.limitDownCount}${if (breadth.isEstimatedLimitStats) "（涨跌停为估算）" else ""}")

        if (includeSectors) {
            val industry = snapshot?.industrySectors?.take(sectorLimit)?.takeIf { it.isNotEmpty() }
                ?: webToolsClient.fetchSectorRank(SectorType.Industry, sectorLimit).getOrDefault(emptyList())
            val concept = snapshot?.conceptSectors?.take(sectorLimit)?.takeIf { it.isNotEmpty() }
                ?: webToolsClient.fetchSectorRank(SectorType.Concept, sectorLimit).getOrDefault(emptyList())
            output.put("industry_sectors", buildSectorArray(industry))
            output.put("concept_sectors", buildSectorArray(concept))
            // 板块摘要追加到 stdout，方便模型在文本层快速读取。
            if (industry.isNotEmpty()) {
                summaryLines.add("【行业板块 Top5】")
                industry.take(5).forEach { s ->
                    val sign = if (s.changePercent >= 0) "+" else ""
                    summaryLines.add("${s.name} $sign${String.format("%.2f", s.changePercent)}%")
                }
            }
            if (concept.isNotEmpty()) {
                summaryLines.add("【概念板块 Top5】")
                concept.take(5).forEach { s ->
                    val sign = if (s.changePercent >= 0) "+" else ""
                    summaryLines.add("${s.name} $sign${String.format("%.2f", s.changePercent)}%")
                }
            }
        }

        summaryLines.add("东方财富公开数据可能延迟，数据仅供参考，不构成投资建议。")
        output.put("stdout", summaryLines.joinToString("\n"))
        return output.toString()
    }

    private suspend fun executeSectorHeat(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: JSONObject()
        val typeStr = args.optString("type", "industry").lowercase(Locale.US)
        val type = if (typeStr == "concept") SectorType.Concept else SectorType.Industry
        val limit = args.optInt("limit", 50).coerceIn(5, 100)
        val sortArg = args.optString("sort", "change_desc").lowercase(Locale.US)
        val (sort, ascending, sortLabel) = parseSectorHeatSort(sortArg)

        val cached = marketMonitorRepository?.cachedSnapshot()?.takeIf {
            sort == SectorSort.ChangePercent && !ascending
        }
        val cachedSectors = when (type) {
            SectorType.Industry -> cached?.industrySectors
            SectorType.Concept -> cached?.conceptSectors
        }?.take(limit)?.takeIf { it.isNotEmpty() }
        val sectors = cachedSectors ?: webToolsClient.fetchSectorRank(type, limit, sort, ascending).getOrElse { t ->
            return toolFailureOutput(t, "板块数据获取失败。") {}
        }

        val summaryLines = mutableListOf<String>()
        summaryLines.add("【${if (type == SectorType.Industry) "行业板块" else "概念板块"}排行（${sortLabel}优先，共${sectors.size}条）】")
        sectors.take(10).forEachIndexed { idx, s ->
            val sign = if (s.changePercent >= 0) "+" else ""
            val leading = if (s.leadingStock.isNotBlank()) "  领涨:${s.leadingStock}" else ""
            summaryLines.add("${idx + 1}. ${s.name} $sign${String.format("%.2f", s.changePercent)}%$leading")
        }

        return JSONObject().apply {
            put("ok", true)
            put("source", "Eastmoney")
            put("type", typeStr)
            put("sort", sortArg)
            put("updated_at", System.currentTimeMillis())
            put("sectors", buildSectorArray(sectors))
            summaryLines.add("东方财富公开数据可能延迟，数据仅供参考，不构成投资建议。")
            put("stdout", summaryLines.joinToString("\n"))
        }.toString()
    }

    private fun parseSectorHeatSort(sortArg: String): Triple<SectorSort, Boolean, String> =
        when (sortArg) {
            "asc",
            "change_asc" -> Triple(SectorSort.ChangePercent, true, "跌幅")
            "turnover",
            "turnover_desc" -> Triple(SectorSort.TurnoverRate, false, "换手率")
            else -> Triple(SectorSort.ChangePercent, false, "涨幅")
        }

    private fun buildBreadthObject(breadth: MarketBreadth): JSONObject = JSONObject().apply {
        put("rising_count", breadth.risingCount)
        put("falling_count", breadth.fallingCount)
        put("flat_count", breadth.flatCount)
        put("limit_up_count", breadth.limitUpCount)
        put("limit_down_count", breadth.limitDownCount)
        put("is_estimated_limit_stats", breadth.isEstimatedLimitStats)
        put("updated_at", breadth.updatedAtMillis)
    }

    private fun buildSectorArray(sectors: List<SectorItem>): JSONArray = JSONArray().apply {
        sectors.forEach { s ->
            put(JSONObject().apply {
                put("code", s.code)
                put("name", s.name)
                put("change_percent", s.changePercent)  // Double，与 index 字段类型一致
                put("turnover_rate", s.turnoverRate)
                put("amount", s.amount)
                if (s.leadingStock.isNotBlank()) {
                    put("leading_stock", s.leadingStock)
                    put("leading_stock_change", s.leadingStockChange) // Double
                }
            })
        }
    }

    private suspend fun executeStockMarketData(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()

        val action = arguments.stringValue("action").lowercase(Locale.US).ifBlank {
            if (arguments.stringValue("query").isNotBlank() && arguments.stringValue("symbol").isBlank()) {
                "search"
            } else {
                "quote"
            }
        }

        val normalizedAction = when (action) {
            "batch",
            "batch_quote" -> "quotes"
            else -> action
        }

        return when (normalizedAction) {
            "search" -> executeStockSearch(arguments)
            "quotes" -> executeStockQuotes(arguments)
            "quote" -> executeStockQuote(arguments)
            "chart" -> executeStockChart(arguments, action)
            else -> JSONObject().apply {
                put("ok", false)
                put("errmsg", "Unsupported stock action '$action'. Use search, quote, quotes, or chart.")
            }.toString()
        }
    }

    private suspend fun executeStockSearch(arguments: JSONObject): String {
        val query = arguments.stringValue("query").ifBlank { arguments.stringValue("symbol") }
        if (query.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'query' argument for stock search.")
            }.toString()
        }

        val maxResults = arguments.intValue("max_results", "maxResults") ?: 10
        val response = webToolsClient.searchStocks(
            StockSearchRequest(
                query = query,
                maxResults = maxResults,
            ),
        ).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Stock search failed.") {
                put("query", query)
            }
        }

        return buildStockSearchOutput(
            query = query,
            maxResults = maxResults,
            response = response,
        ).toString()
    }

    private suspend fun executeStockQuote(arguments: JSONObject): String {
        val symbol = arguments.stringValue("symbol").uppercase(Locale.US)
        if (symbol.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'symbol' argument for stock quote.")
            }.toString()
        }

        val includeChart = arguments.booleanValue("include_chart", "includeChart") ?: false
        if (includeChart) {
            return executeStockChart(arguments, "quote")
        }

        val response = webToolsClient.fetchStockQuote(
            StockQuoteRequest(symbol = symbol),
        ).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Stock quote request failed.") {
                put("symbol", symbol)
            }
        }

        return buildStockQuoteOutput(
            requestedSymbols = listOf(symbol),
            response = response,
        ).toString()
    }

    private suspend fun executeStockQuotes(arguments: JSONObject): String {
        val symbols = arguments.stockSymbolsValue()
        if (symbols.isEmpty()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'symbols' argument for stock quotes.")
            }.toString()
        }

        val response = webToolsClient.fetchStockQuotes(
            StockQuotesRequest(symbols = symbols),
        ).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Stock quotes request failed.") {
                put("symbols", JSONArray(symbols))
            }
        }

        return buildStockQuotesOutput(
            action = "quotes",
            requestedSymbols = symbols,
            response = response,
        ).toString()
    }

    private suspend fun executeStockChart(
        arguments: JSONObject,
        action: String,
    ): String {
        val symbol = arguments.stringValue("symbol").uppercase(Locale.US)
        if (symbol.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'symbol' argument for stock $action.")
            }.toString()
        }

        val range = arguments.stringValue("range").ifBlank {
            if (action == "quote") "1d" else "1mo"
        }
        val interval = arguments.stringValue("interval").ifBlank {
            if (action == "quote") "1m" else "1d"
        }
        val includePrePost = arguments.booleanValue("include_pre_post", "includePrePost") ?: false
        val response = webToolsClient.fetchStockChart(
            StockChartRequest(
                symbol = symbol,
                range = range,
                interval = interval,
                includePrePost = includePrePost,
            ),
        ).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Stock data request failed.") {
                put("symbol", symbol)
            }
        }

        return buildStockChartOutput(
            action = action,
            symbol = symbol,
            range = range,
            interval = interval,
            includePrePost = includePrePost,
            response = response,
        ).toString()
    }

    private fun buildStockSearchOutput(
        query: String,
        maxResults: Int,
        response: JSONObject,
    ): JSONObject {
        val quotes = response
            .optJSONObject("QuotationCodeTable")
            ?.optJSONArray("Data")
            ?: JSONArray()
        val normalizedQuotes = JSONArray()
        val limit = minOf(maxResults.coerceIn(1, 25), quotes.length())
        for (index in 0 until limit) {
            val quote = quotes.optJSONObject(index) ?: continue
            normalizedQuotes.put(
                JSONObject().apply {
                    put("symbol", quote.optString("Code"))
                    put("name", quote.optString("Name"))
                    put("quote_id", quote.optString("QuoteID"))
                    put("exchange", quote.optString("JYS"))
                    put("market", quote.optString("MktNum"))
                    put("market_type", quote.optString("MarketType"))
                    put("security_type", quote.optString("SecurityTypeName"))
                    put("classify", quote.optString("Classify"))
                    put("pinyin", quote.optString("PinYin"))
                }
            )
        }

        return JSONObject().apply {
            put("ok", true)
            put("source", "Eastmoney")
            put("action", "search")
            put("query", query)
            put("quotes", normalizedQuotes)
            put(
                "stdout",
                if (normalizedQuotes.length() == 0) {
                    "No stock symbols found for $query."
                } else {
                    "Found ${normalizedQuotes.length()} stock symbol(s) for $query."
                },
            )
        }
    }

    private fun buildStockChartOutput(
        action: String,
        symbol: String,
        range: String,
        interval: String,
        includePrePost: Boolean,
        response: JSONObject,
    ): JSONObject {
        val quoteData = response
            .getJSONObject("quote")
            .getJSONObject("data")
        val market = response.optString("market").ifBlank {
            quoteData.optString("f107")
        }

        val metaOutput = normalizeEastmoneyQuote(quoteData, market, response.optString("secid")).apply {
            if (optString("symbol").isBlank()) {
                put("symbol", response.optString("resolved_symbol"))
            }
            optNumberAsDouble("high")?.let { put("regularMarketDayHigh", it) }
            optNumberAsDouble("low")?.let { put("regularMarketDayLow", it) }
            optNumberAsDouble("open")?.let { put("regularMarketOpen", it) }
            optNumberAsDouble("volume")?.let { put("regularMarketVolume", it) }
            optNumberAsDouble("amount")?.let { put("regularMarketAmount", it) }
            optNumberAsDouble("change")?.let { put("regularMarketChange", it) }
            optNumberAsDouble("changePercent")?.let { put("regularMarketChangePercent", it) }
        }

        val candles = JSONArray()
        val rawKlines = response
            .optJSONObject("kline")
            ?.optJSONObject("data")
            ?.optJSONArray("klines")
            ?: JSONArray()
        val candleOutputLimit = if (action == "quote") {
            QuoteStockCandleOutputCount
        } else {
            response
                .optInt("kline_limit", defaultStockCandleOutputLimit(range, interval))
                .coerceIn(1, MaxStockCandleOutputCount)
        }
        val startIndex = maxOf(0, rawKlines.length() - candleOutputLimit)
        for (index in startIndex until rawKlines.length()) {
            parseEastmoneyKline(rawKlines.optString(index))?.let(candles::put)
        }

        return JSONObject().apply {
            put("ok", true)
            put("source", "Eastmoney")
            put("action", action)
            put("symbol", symbol)
            put("secid", response.optString("secid"))
            if (response.optBoolean("resolved_from_query", false)) {
                put("resolved_from_query", true)
                put("resolved_symbol", response.optString("resolved_symbol"))
            }
            put("range", range)
            put("interval", interval)
            put("include_pre_post", includePrePost)
            put("kline_begin_date", response.optString("kline_begin_date"))
            put("kline_end_date", response.optString("kline_end_date"))
            put("meta", metaOutput)
            put("candles", candles)
            put("candle_output_limit", candleOutputLimit)
            put("truncated", rawKlines.length() > candleOutputLimit)
            response.optString("kline_error").takeIf(String::isNotBlank)?.let { put("kline_error", it) }
            put("stdout", buildStockChartSummary(symbol, metaOutput, candles.length(), range, interval))
        }
    }

    private fun buildStockQuotesOutput(
        action: String,
        requestedSymbols: List<String>,
        response: JSONObject,
    ): JSONObject {
        val resolvedBySecid = mutableMapOf<String, JSONObject>()
        val resolvedSymbols = response.optJSONArray("resolved_symbols") ?: JSONArray()
        for (index in 0 until resolvedSymbols.length()) {
            val resolved = resolvedSymbols.optJSONObject(index) ?: continue
            resolvedBySecid[resolved.optString("secid")] = resolved
        }

        val normalizedQuotes = JSONArray()
        val rawQuotes = response
            .optJSONObject("quotes")
            ?.optJSONObject("data")
            ?.optJSONArray("diff")
            ?: JSONArray()
        for (index in 0 until rawQuotes.length()) {
            val rawQuote = rawQuotes.optJSONObject(index) ?: continue
            val normalizedQuote = normalizeEastmoneyQuote(rawQuote)
            val resolved = resolvedBySecid[normalizedQuote.optString("secid")]
            normalizedQuotes.put(
                normalizedQuote.apply {
                    resolved?.optString("input")?.takeIf(String::isNotBlank)?.let { put("input", it) }
                },
            )
        }

        val errors = JSONArray()
        response.optJSONArray("errors")?.let { sourceErrors ->
            for (index in 0 until sourceErrors.length()) {
                sourceErrors.optJSONObject(index)?.let(errors::put)
            }
        }
        val returnedSecids = buildSet {
            for (index in 0 until normalizedQuotes.length()) {
                val secid = normalizedQuotes.optJSONObject(index)?.optString("secid").orEmpty()
                if (secid.isNotBlank()) add(secid)
            }
        }
        for (index in 0 until resolvedSymbols.length()) {
            val resolved = resolvedSymbols.optJSONObject(index) ?: continue
            val secid = resolved.optString("secid")
            if (secid.isNotBlank() && secid !in returnedSecids) {
                errors.put(
                    JSONObject().apply {
                        put("symbol", resolved.optString("input").ifBlank { resolved.optString("symbol") })
                        put("secid", secid)
                        put("errmsg", "Stock quotes response did not include this symbol.")
                    },
                )
            }
        }
        val ok = normalizedQuotes.length() > 0
        return JSONObject().apply {
            put("ok", ok)
            put("source", "Eastmoney")
            put("action", action)
            put("requested_symbols", JSONArray(requestedSymbols))
            put("quotes", normalizedQuotes)
            if (action == "quote" && normalizedQuotes.length() > 0) {
                put("quote", normalizedQuotes.getJSONObject(0))
            }
            if (errors.length() > 0) {
                put("errors", errors)
            }
            if (!ok) {
                put("errmsg", "No stock quotes were returned.")
            }
            put("stdout", buildStockQuotesSummary(normalizedQuotes, errors.length()))
        }
    }

    private fun buildStockQuoteOutput(
        requestedSymbols: List<String>,
        response: JSONObject,
    ): JSONObject {
        val quoteData = response
            .getJSONObject("quote")
            .getJSONObject("data")
        val market = response.optString("market").ifBlank {
            quoteData.optString("f107")
        }
        val normalizedQuote = normalizeEastmoneyQuote(quoteData, market, response.optString("secid")).apply {
            if (optString("symbol").isBlank()) {
                put("symbol", response.optString("resolved_symbol"))
            }
        }
        val quotes = JSONArray().put(normalizedQuote)

        return JSONObject().apply {
            put("ok", true)
            put("source", "Eastmoney")
            put("action", "quote")
            put("requested_symbols", JSONArray(requestedSymbols))
            put("secid", response.optString("secid"))
            if (response.optBoolean("resolved_from_query", false)) {
                put("resolved_from_query", true)
                put("resolved_symbol", response.optString("resolved_symbol"))
            }
            put("quotes", quotes)
            put("quote", normalizedQuote)
            put("stdout", buildStockQuotesSummary(quotes, errorCount = 0))
        }
    }

    private fun defaultStockCandleOutputLimit(
        range: String,
        interval: String,
    ): Int {
        val normalizedInterval = interval.trim().lowercase(Locale.US).ifBlank { "1d" }
        val normalizedRange = range.trim().lowercase(Locale.US).ifBlank { "1mo" }
        if (normalizedInterval in setOf("1m", "1min", "5m", "5min", "15m", "15min", "30m", "30min", "60m", "60min", "1h")) {
            return MaxStockCandleOutputCount
        }
        return when (normalizedRange) {
            "1d" -> 1
            "5d" -> 5
            "1mo" -> 31
            "3mo" -> 93
            "6mo" -> 186
            else -> MaxStockCandleOutputCount
        }
    }

    private fun parseEastmoneyKline(rawValue: String): JSONObject? {
        val parts = rawValue.split(',')
        if (parts.size < 7) return null
        return JSONObject().apply {
            put("date", parts[0])
            parts.getOrNull(1)?.toDoubleOrNull()?.let { put("open", it) }
            parts.getOrNull(2)?.toDoubleOrNull()?.let { put("close", it) }
            parts.getOrNull(3)?.toDoubleOrNull()?.let { put("high", it) }
            parts.getOrNull(4)?.toDoubleOrNull()?.let { put("low", it) }
            parts.getOrNull(5)?.toDoubleOrNull()?.let { put("volume", it) }
            parts.getOrNull(6)?.toDoubleOrNull()?.let { put("amount", it) }
            parts.getOrNull(7)?.toDoubleOrNull()?.let { put("amplitude_percent", it) }
            parts.getOrNull(8)?.toDoubleOrNull()?.let { put("change_percent", it) }
            parts.getOrNull(9)?.toDoubleOrNull()?.let { put("change", it) }
            parts.getOrNull(10)?.toDoubleOrNull()?.let { put("turnover_percent", it) }
        }
    }

    private fun normalizeEastmoneyQuote(
        quoteData: JSONObject,
        marketOverride: String = "",
        secidOverride: String = "",
    ): JSONObject {
        val isBatchQuote = quoteData.hasUsableValue("f12") || quoteData.hasUsableValue("f2")
        val market = marketOverride.ifBlank {
            if (isBatchQuote) quoteData.optString("f13") else quoteData.optString("f107")
        }
        val symbol = if (isBatchQuote) {
            quoteData.optString("f12")
        } else {
            quoteData.optString("f57")
        }
        val secid = secidOverride.ifBlank {
            if (market.isNotBlank() && symbol.isNotBlank()) "$market.$symbol" else ""
        }

        return JSONObject().apply {
            put("symbol", symbol)
            put("name", if (isBatchQuote) quoteData.optString("f14") else quoteData.optString("f58"))
            put("market", market)
            put("secid", secid)
            put("currency", eastmoneyCurrency(market))
            quoteData.optNumberAsDouble("f152")?.let { put("pricePrecision", it.toInt()) }

            if (isBatchQuote) {
                quoteData.optEastmoneyPrice("f2", market)?.let { put("regularMarketPrice", it) }
                quoteData.optEastmoneyPrice("f18", market)?.let { put("previousClose", it) }
                quoteData.optEastmoneyPrice("f17", market)?.let { put("open", it) }
                quoteData.optEastmoneyPrice("f15", market)?.let { put("high", it) }
                quoteData.optEastmoneyPrice("f16", market)?.let { put("low", it) }
                quoteData.optEastmoneyPriceDelta("f4", market)?.let { put("change", it) }
                quoteData.optEastmoneyPercent("f3")?.let { put("changePercent", it) }
                quoteData.optEastmoneyPercent("f7")?.let { put("amplitudePercent", it) }
                quoteData.optNumberAsDouble("f5")?.let { put("volume", it) }
                quoteData.optNumberAsDouble("f6")?.let { put("amount", it) }
                quoteData.optEastmoneyPercent("f8")?.let { put("turnoverPercent", it) }
                quoteData.optEastmoneyRatio("f10")?.let { put("volumeRatio", it) }
                quoteData.optEastmoneyRatio("f9")?.let { put("peDynamic", it) }
                quoteData.optEastmoneyRatio("f115")?.let { put("peTtm", it) }
                quoteData.optEastmoneyRatio("f23")?.let { put("pbRatio", it) }
                quoteData.optNumberAsDouble("f20")?.let { put("marketCap", it) }
                quoteData.optNumberAsDouble("f21")?.let { put("floatMarketCap", it) }
                quoteData.optEastmoneyPercent("f24")?.let { put("changePercent60d", it) }
                quoteData.optEastmoneyPercent("f25")?.let { put("changePercentYtd", it) }
            } else {
                quoteData.optNumberAsDouble("f86")?.let { put("regularMarketTime", it.toLong()) }
                quoteData.optEastmoneyPrice("f43", market)?.let { put("regularMarketPrice", it) }
                quoteData.optEastmoneyPrice("f60", market)?.let { put("previousClose", it) }
                quoteData.optEastmoneyPrice("f46", market)?.let { put("open", it) }
                quoteData.optEastmoneyPrice("f44", market)?.let { put("high", it) }
                quoteData.optEastmoneyPrice("f45", market)?.let { put("low", it) }
                quoteData.optEastmoneyPrice("f71", market)?.let { put("averagePrice", it) }
                quoteData.optNumberAsDouble("f47")?.let { put("volume", it) }
                quoteData.optNumberAsDouble("f48")?.let { put("amount", it) }
                quoteData.optEastmoneyPriceDelta("f169", market)?.let { put("change", it) }
                quoteData.optEastmoneyPercent("f170")?.let { put("changePercent", it) }
                quoteData.optEastmoneyPercent("f171")?.let { put("amplitudePercent", it) }
                quoteData.optEastmoneyPercent("f168")?.let { put("turnoverPercent", it) }
                quoteData.optEastmoneyRatio("f50")?.let { put("volumeRatio", it) }
                quoteData.optEastmoneyRatio("f162")?.let { put("peDynamic", it) }
                quoteData.optEastmoneyRatio("f164")?.let { put("peTtm", it) }
                quoteData.optEastmoneyRatio("f167")?.let { put("pbRatio", it) }
                quoteData.optNumberAsDouble("f116")?.let { put("marketCap", it) }
                quoteData.optNumberAsDouble("f117")?.let { put("floatMarketCap", it) }
                val price = optNumberAsDouble("regularMarketPrice")
                val previousClose = optNumberAsDouble("previousClose")
                if (!has("change") && price != null && previousClose != null) {
                    put("change", price - previousClose)
                }
                if (!has("changePercent") && price != null && previousClose != null && previousClose != 0.0) {
                    put("changePercent", (price - previousClose) / previousClose * 100.0)
                }
            }
        }
    }

    private fun eastmoneyCurrency(market: String): String = when (market) {
        "0", "1" -> "CNY"
        "105" -> "USD"
        "116" -> "HKD"
        else -> ""
    }

    private fun buildStockChartSummary(
        requestedSymbol: String,
        meta: JSONObject,
        candleCount: Int,
        range: String,
        interval: String,
    ): String {
        val symbol = meta.optString("symbol").ifBlank { requestedSymbol }
        val currency = meta.optString("currency")
        val price = meta.optNumberAsDouble("regularMarketPrice")
        val change = meta.optNumberAsDouble("regularMarketChange")
        val changePercent = meta.optNumberAsDouble("regularMarketChangePercent")
        return buildString {
            append(symbol)
            if (price != null) {
                append(" ")
                append(formatStockNumber(price))
                if (currency.isNotBlank()) {
                    append(" ")
                    append(currency)
                }
            }
            if (change != null && changePercent != null) {
                append(" (")
                append(if (change >= 0) "+" else "")
                append(formatStockNumber(change))
                append(", ")
                append(if (changePercent >= 0) "+" else "")
                append(formatStockNumber(changePercent))
                append("%)")
            }
            append("; candles=")
            append(candleCount)
            append(", range=")
            append(range)
            append(", interval=")
            append(interval)
            append(". Data may be delayed.")
        }
    }

    private fun buildStockQuotesSummary(
        quotes: JSONArray,
        errorCount: Int,
    ): String = buildString {
        if (quotes.length() == 0) {
            append("No stock quotes returned")
        } else {
            append("Quotes: ")
            for (index in 0 until minOf(quotes.length(), 5)) {
                if (index > 0) append("; ")
                val quote = quotes.optJSONObject(index) ?: continue
                append(quote.optString("symbol"))
                val price = quote.optNumberAsDouble("regularMarketPrice")
                if (price != null) {
                    append(" ")
                    append(formatStockNumber(price))
                    val currency = quote.optString("currency")
                    if (currency.isNotBlank()) {
                        append(" ")
                        append(currency)
                    }
                }
                val change = quote.optNumberAsDouble("change")
                val changePercent = quote.optNumberAsDouble("changePercent")
                if (change != null && changePercent != null) {
                    append(" (")
                    append(if (change >= 0) "+" else "")
                    append(formatStockNumber(change))
                    append(", ")
                    append(if (changePercent >= 0) "+" else "")
                    append(formatStockNumber(changePercent))
                    append("%)")
                }
            }
            if (quotes.length() > 5) {
                append("; +")
                append(quotes.length() - 5)
                append(" more")
            }
        }
        if (errorCount > 0) {
            append("; errors=")
            append(errorCount)
        }
        append(". Data may be delayed.")
    }

    private suspend fun executeSleep(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()

        val durationMillis = arguments.takeIf { it.has("duration_ms") }?.optLong("duration_ms")
            ?: arguments.takeIf { it.has("durationMs") }?.optLong("durationMs")
            ?: -1L

        if (durationMillis < 0L) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'duration_ms' argument.")
            }.toString()
        }
        if (durationMillis > MaxSleepDurationMillis) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "'duration_ms' must be between 0 and $MaxSleepDurationMillis.")
            }.toString()
        }

        delay(durationMillis)
        return JSONObject().apply {
            put("ok", true)
            put("duration_ms", durationMillis)
            put("stdout", "Slept for ${durationMillis}ms.")
        }.toString()
    }

    private fun injectDefaultWorkingDirectory(
        argumentsJson: String,
        workspaceDirectory: String,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return argumentsJson
        if (!arguments.has("workingDirectory") && !arguments.has("working_directory")) {
            arguments.put("working_directory", workspaceDirectory)
        }
        return arguments.toString()
    }

    private fun guessImageMimeType(path: String): String? {
        val guessedMimeType = workspaceFileBridge.guessMimeType(path)
        if (guessedMimeType.startsWith("image/")) return guessedMimeType

        return when (path.substringAfterLast('.', "").lowercase()) {
            "jpg",
            "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> null
        }
    }

    private fun isLikelyTextResource(fileName: String, bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in TextSkillResourceExtensions) return true
        val sample = bytes.take(512)
        if (sample.isEmpty()) return true
        return sample.count { byte ->
            val value = byte.toInt() and 0xff
            value == 9 || value == 10 || value == 13 || value in 32..126
        } >= sample.size * 9 / 10
    }

    private fun renderAvailableSkillLines(
        skills: List<InstalledSkill>,
    ): Pair<List<String>, Int> {
        val lines = mutableListOf<String>()
        var usedChars = 0
        var omitted = 0
        skills.sortedBy { it.name.lowercase() }.forEach { skill ->
            val minimumLine = renderAvailableSkillLine(skill, description = "")
            if (usedChars + minimumLine.length + 1 > SkillMetadataContextBudgetChars) {
                omitted += 1
                return@forEach
            }

            val fullLine = renderAvailableSkillLine(skill, skill.description)
            val remaining = SkillMetadataContextBudgetChars - usedChars - 1
            val line = if (fullLine.length <= remaining) {
                fullLine
            } else {
                val prefix = "- ${skill.name}: "
                val suffix = " (file: ${skill.skillMdPath.replace('\\', '/')})"
                val descriptionBudget = (remaining - prefix.length - suffix.length)
                    .coerceAtLeast(0)
                if (descriptionBudget > 0) {
                    prefix + skill.description.take(descriptionBudget) + suffix
                } else {
                    minimumLine
                }
            }
            usedChars += line.length + 1
            lines += line
        }
        return lines to omitted
    }

    private fun renderAvailableSkillLine(
        skill: InstalledSkill,
        description: String,
    ): String {
        val path = skill.skillMdPath.replace('\\', '/')
        return if (description.isBlank()) {
            "- ${skill.name}: (file: $path)"
        } else {
            "- ${skill.name}: $description (file: $path)"
        }
    }

    private fun buildAgentInstructions(
        systemPrompt: String,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill>,
        activeSkills: List<ActiveSkillContext>,
        mcpToolBindings: List<McpToolBinding>,
        exposeNamespacedMcpTools: Boolean,
        agentModeEnabled: Boolean,
        enabledToolGroups: List<String>,
        planModeEnabled: Boolean,
        goalModeEnabled: Boolean,
        taskState: AgentTaskState,
        parallelToolCallsEnabled: Boolean,
        basicToolCompatibilityMode: Boolean,
    ): String = buildString {
        val normalizedToolGroups = normalizeChatToolGroups(enabledToolGroups)
        val extensionsEnabled = isChatToolGroupEnabled(normalizedToolGroups, ChatToolGroups.Extensions)
        val enabledToolGroupSummary = normalizedToolGroups
            .joinToString(", ") { group ->
                when (group) {
                    ChatToolGroups.FilesImages -> "Files & Images"
                    ChatToolGroups.Terminal -> "Terminal"
                    ChatToolGroups.Web -> "Web"
                    ChatToolGroups.Extensions -> "Skills/MCP"
                    else -> group
                }
            }
            .ifBlank { "none" }
        val trimmedPrompt = systemPrompt.trim()
        if (trimmedPrompt.isNotBlank()) {
            append(trimmedPrompt)
            append("\n\n")
        }
        append(
            "You are running inside Aether agent mode on Android. " +
                "Use available tools instead of guessing about the local device state. " +
                "The current session workspace is $workspaceDirectory, which corresponds to ~/.aether/workspaces for this chat. " +
                "All user-uploaded files are copied into that workspace, usually under uploads/. " +
                "User-uploaded workspace images are not inserted into model vision automatically; call analyze_image on a workspace path when you need to inspect one. " +
                "You can show inline media in replies with Markdown images and Mermaid. For images, use ![alt](url){width=75% height=280 scroll=true show-all=false fit=contain}; width accepts percentages or dp, height/min-height/max-height accept dp, scroll/show-all accept true or false, and fit accepts contain or cover. " +
                "For Mermaid, use fenced blocks like ```mermaid {height=360 scroll=true show-all=false}\\ngraph TD\\nA-->B\\n``` and the same width/height/min-height/max-height/scroll/show-all attributes apply. Users can tap rendered images to enlarge them. " +
                "Use fetch_web_url when you need the contents of a specific webpage or the user gives you a URL. " +
                "Use tavily_search for public-web discovery and fresh online information. " +
                "Use stock_market_data for individual stock, ETF, or crypto symbol lookup, lightweight single quotes, batch quotes, and chart/OHLCV data before falling back to web search. " +
                "For action=quote/quotes/chart, you may pass a Chinese company name such as 法拉电子 or 贵州茅台 directly as symbol; Aether will auto-resolve it. Prefer that over a separate search when the name is unambiguous. " +
                "For multiple symbols or comparisons, use stock_market_data action=quotes; for one current snapshot with price, volume, valuation, turnover, and period-performance metrics, use action=quote; use action=chart only when historical candles/OHLCV are needed. " +
                "Use market_overview (not stock_market_data) when the user asks about today's broad A-share market conditions, major index performance (上证/深证/创业板 etc.), or wants a market-wide summary; set include_sectors=true to also fetch sector rankings in the same call. " +
                "Use sector_heat when the user asks which industry or concept sectors are hot/cold today, or wants to understand sector rotation; type=industry for 行业板块, type=concept for 概念板块; sort=change_desc returns strongest sectors, change_asc returns weakest sectors, turnover_desc sorts by turnover. " +
                "Never invent or extrapolate market prices, volume, turnover, valuation, or intraday data. " +
                "When the user's latest request asks to refresh, update, or get latest/current market or stock data, call the appropriate tool before analysis; if no symbol can be inferred from the conversation, ask for it. " +
                "After market or stock tool results arrive, deliver the complete user-facing conclusion in the SAME turn. Do not stop after only announcing that you will look up, analyze, judge, or continue. Never end a turn with a teaser such as '接下来分析', '接着判断', or '数据拿到了，下面给你结论' without the actual conclusion. " +
                "If a data tool fails or is unavailable, say so instead of fabricating numbers. " +
                "Stock market data can be delayed; do not present it as financial advice. " +
                "For tavily_search, prefer a simple query plus include_domains or max_results when useful. " +
                "Use either time_range or start_date/end_date, never both. " +
                "Only set country when you know Tavily supports that lowercase country value, such as china or united states; otherwise leave it null. " +
                "Use snake_case tavily_search keys only; do not invent duplicate camelCase aliases. "
        )
        if (planModeEnabled) {
            append(
                "Prefer read, grep, find, ls, and analyze_image for read-only filesystem and image inspection. " +
                    "If a tool call omits working_directory, Aether will run it in the current session workspace by default. " +
                    "The read tool reads file contents with optional line offset and limit. " +
                    "The grep tool searches file contents. The find tool matches file paths by glob pattern. " +
                    "The ls tool lists directory contents. " +
                    "All filesystem tools accept ~ or ~/... to mean the Termux home directory. "
            )
        } else {
            append(
                "Prefer read, edit, write, grep, find, and ls for filesystem work. " +
                    "If a tool call omits working_directory, Aether will run it in the current session workspace by default. " +
                    "The read tool reads file contents with optional line offset and limit. " +
                    "The edit tool applies exact text replacements and should be used for precise file edits. " +
                    "For exactly one edit, call edit with {path, oldText, newText} and omit edits. " +
                    "For multiple edits, call edit with {path, edits:[{oldText, newText}, ...]} and omit top-level oldText/newText. " +
                    "Never send both edit formats together unless you intentionally want the edits array to be used. " +
                    "The write tool creates or overwrites a file with full contents. " +
                    "The grep tool searches file contents. The find tool matches file paths by glob pattern. " +
                    "The ls tool lists directory contents. " +
                    "All filesystem tools accept ~ or ~/... to mean the Termux home directory. " +
                    "The bash tool runs inside Termux on the user's phone. It watches the command for up to 45 seconds. " +
                    "If the command finishes quickly, bash returns the final structured JSON with stdout, stderr, exit_code, err, errmsg, duration_ms, command, and working_directory. " +
                    "If the command is still running after 45 seconds, bash returns status=running plus run_id and the latest stdout/stderr snapshot without stopping the command. " +
                    "bash and fetch_bash_output return up to 65536 bytes from each of stdout and stderr by default; pass tail_bytes up to 262144 when you expect long command output or when stdout_truncated/stderr_truncated is true. " +
                    "When bash returns status=running, use sleep to wait, then call fetch_bash_output with the same run_id to poll for more logs or completion. " +
                    "If a long-running command is stuck or no longer needed, call kill_bash with the run_id. " +
                    "Use sleep instead of busy waiting. " +
                    "Use bash for shell commands, scripts, and tasks that are not covered by the specialized filesystem tools. " +
                    "If the user asks you to run a bash, shell, terminal, or Termux command, you must call the bash tool instead of describing what they should run manually. " +
                    "When you create or modify a file that the user should download, include a Markdown link that uses file:// with the absolute workspace path, for example [report.txt](file://$workspaceDirectory/report.txt). " +
                    "Only claim you executed shell commands if you actually called bash. "
            )
        }
        append(
            "For non-trivial or multi-step work, interleave concise assistant updates with tool use: briefly say what you are about to inspect or do, call the relevant tool or independent tool batch for that step, then continue with another short update before the next distinct tool step. " +
                "Keep these updates short and skip them for obvious single-tool lookups, purely mechanical polling, or when the user asks for no narration. " +
                "After using tools, summarize the result clearly for the user."
        )
        if (planModeEnabled) {
            append("\n\n")
            append(buildPlanModeInstructions())
        } else if (goalModeEnabled) {
            append("\n\n")
            append(buildGoalModeInstructions())
        }
        append("\nEnabled tool groups for this chat: $enabledToolGroupSummary. Disabled groups are intentionally unavailable for this turn.")
        append("\n\n")
        append(
            "The following task state is read-only context maintained by the execution framework. " +
                "Do not attempt to update its status or decide whether the conversation is complete."
        )
        append("\n<task_state>\n")
        append(renderTaskStateForPrompt(taskState))
        append("\n</task_state>")
        append(
            "\n\nCompletion control belongs exclusively to the execution framework. " +
                "You cannot mark work completed, blocked, waiting, or continuing. " +
                "Use tools as needed, then provide the complete user-facing result in the current response."
        )
        if (agentModeEnabled) {
            append("\n\n")
            append(
                "Agent Mode is enabled for this chat. Use agent_display to operate an isolated Android virtual display (900×1600 px), not the user's main screen. " +
                    "Coordinates for tap and swipe are normalized from 0 to 1000 on both axes (top-left = 0,0; bottom-right = 1000,1000). " +
                    "The screenshot has red ruler tick-marks along all four edges at every 50-unit interval (minor ticks) and every 100-unit interval (major ticks with numeric labels 0, 100, 200, ... 1000). " +
                    "Use these rulers as visual anchors to estimate coordinates precisely. " +
                    "IMPORTANT coordinate tips: " +
                    "(1) Always aim for the exact CENTER of the target UI element—horizontally and vertically. " +
                    "(2) Cross-reference the element's position against the nearest ruler tick marks on BOTH the horizontal (top/bottom) and vertical (left/right) edges. " +
                    "(3) For text buttons or list items, the tap point should be at the middle of the text label, not at the edge. " +
                    "(4) For input fields, tap the center of the field area, not the hint text edge. " +
                    "(5) After tapping, always take a screenshot to verify the result; if the tap missed, re-examine the coordinates and retry. " +
                    "(6) When elements are small (icons, checkboxes), be extra careful—estimate each axis independently using the nearest two ruler labels. " +
                    "Call agent_display with action=start before operating apps, action=launch to open an app by package name or exact label, and action=screenshot after visible changes. " +
                    "After each agent_display action that captures the display, the latest screenshot is automatically inserted into the next model request as an image, following the Ruto-GLM workflow. Use that image directly instead of calling analyze_image for Agent Mode screenshots. " +
                    "SPEED OPTIMIZATION: Use action=sequence with a steps array to batch multiple actions in one tool call. " +
                    "This executes all steps server-side without intermediate screenshots, only capturing once at the end. " +
                    "Example: {\"action\":\"sequence\",\"steps\":[{\"action\":\"tap\",\"x\":500,\"y\":200},{\"action\":\"wait\",\"wait_ms\":500},{\"action\":\"text\",\"text\":\"hello\"},{\"action\":\"key\",\"key\":\"ENTER\"}]} " +
                    "Use sequence when you are confident about the next 2-5 steps (e.g. tap a known field, type text, press enter). " +
                    "Fall back to single actions when you need to see the screen before deciding the next step. " +
                    "RECOVERY FROM MISTAKES: After every tap, always screenshot to verify you reached the expected screen. " +
                    "If the screenshot shows an unexpected page, popup, ad, or wrong app screen, IMMEDIATELY use action=key with key=BACK to go back, then screenshot again. " +
                    "Repeat BACK presses until you return to the intended screen. " +
                    "If BACK does not help after 2-3 attempts, use action=key with key=HOME to return to the launcher, then re-launch the target app. " +
                    "Never continue operating on a wrong screen—always correct your position first. " +
                    "Do not use Agent Mode tools when the user only wants a normal chat answer."
            )
        }
        append("\n\n")
        append(
            if (basicToolCompatibilityMode) {
                "Basic tool compatibility mode is enabled for this model endpoint. " +
                    "Call at most one normal top-level function tool per assistant turn. " +
                    "Do not request native parallel tool calls, batch tool calls, or provider-specific tool call modes."
            } else if (parallelToolCallsEnabled) {
                "Parallel tool calls are available for this model endpoint. " +
                    "When independent operations belong to the same explained step and should start at the same time, issue multiple normal top-level tool calls directly in one assistant turn. " +
                    "When order matters, call only the next required tool and wait for its result before choosing the following tool. " +
                    "The fallback batch tool is not available while native parallel tool calls are supported."
            } else {
                "This model endpoint rejected Aether's native parallel tool call request shape earlier. " +
                    "Call at most one normal top-level tool per assistant turn. " +
                    "When multiple independent operations belong to the same explained step, use run_tool_batch as the single top-level tool call and choose mode=parallel for simultaneous execution or mode=sequential when order matters."
            }
        )
        if (availableSkills.isNotEmpty()) {
            val (availableSkillLines, omittedSkillCount) = renderAvailableSkillLines(availableSkills)
            append("\n\n")
            append(
                "Installed Agent Skills are available in this app. " +
                    "Decide autonomously whether a task matches a skill, or whether the user explicitly named one. " +
                    "When it does, call activate_skill before following that skill's instructions. " +
                    "The visible list is metadata only and may be budget-truncated; activate_skill is the framework-level disclosure step that reads SKILL.md into context. " +
                    "Do not use dollar-sign syntax for skills in Aether; the user-facing manual path remains the plus button. " +
                    "Do not pretend a skill is active until you have called activate_skill."
            )
            append("\n<available_skills>")
            availableSkillLines.forEach { line ->
                append("\n")
                append(line)
            }
            if (omittedSkillCount > 0) {
                append("\n- ")
                append(omittedSkillCount)
                append(" additional skills omitted from this model-visible metadata list because of the context budget.")
            }
            append("\n</available_skills>")
        }
        if (activeSkills.isNotEmpty()) {
            append("\n\n")
            append("The following Agent Skills are already active for this chat session and must remain in effect:")
            activeSkills.forEach { skill ->
                append("\n<active_skill name=\"")
                append(skill.name.replace("\"", "'"))
                append("\">")
                if (skill.description.isNotBlank()) {
                    append("\n<description>")
                    append(skill.description)
                    append("</description>")
                }
                if (skill.compatibility.isNotBlank()) {
                    append("\n<compatibility>")
                    append(skill.compatibility)
                    append("</compatibility>")
                }
                if (skill.allowedTools.isNotEmpty()) {
                    append("\n<allowed_tools>")
                    skill.allowedTools.forEach { tool ->
                        append("\n- ")
                        append(tool)
                    }
                    append("\n</allowed_tools>")
                }
                append("\n<skill_root>")
                append(skill.skillRootPath)
                append("</skill_root>")
                if (skill.resourceEntries.isNotEmpty()) {
                    append("\n<resources>")
                    skill.resourceEntries.forEach { resource ->
                        append("\n- ")
                        append(resource.relativePath)
                        append(" (")
                        append(resource.kind.storageValue)
                        append(')')
                    }
                    append("\n</resources>")
                    append("\nUse read_skill_resource to read only the specific bundled files needed for the task.")
                }
                append("\n<instructions>\n")
                append(skill.bodyMarkdown)
                append("\n</instructions>")
                append("\n</active_skill>")
            }
        }
        val mcpSnapshots = if (extensionsEnabled) mcpClientManager.snapshots() else emptyList()
        if (mcpSnapshots.isNotEmpty()) {
            append("\n\n")
            if (exposeNamespacedMcpTools) {
                // 命名空间工具已直接注入 LLM tools 列表，告知模型直接调用即可
                append(
                    "MCP server tools are available as first-class functions in your tool list. " +
                        "Call them directly by their function name (e.g. mcp__serverId__toolName) — " +
                        "do NOT use mcp_call_tool or mcp_list_tools. " +
                        "Use mcp_list_resources and mcp_read_resource for resources. " +
                        "Use mcp_list_prompts and mcp_get_prompt for prompts."
                )
            } else {
                append(
                    "Connected MCP servers are also available in this session. " +
                        "Use mcp_list_tools to inspect callable MCP tools. " +
                        "Use mcp_list_resources and mcp_read_resource for resources. " +
                        "Use mcp_list_prompts and mcp_get_prompt for prompts. " +
                        "Never invent tool names such as server:tool."
                )
                if (planModeEnabled) {
                    append(" Plan Mode is read-only, so MCP tool invocation is unavailable.")
                } else {
                    append(" Use mcp_call_tool to invoke an MCP tool with server_id, tool_name, and arguments.")
                }
            }
            append("\n<mcp_servers>")
            mcpSnapshots.forEach { snapshot ->
                append("\n- ")
                append(snapshot.config.displayName)
                append(" [")
                append(snapshot.config.id)
                append("]")
                if (snapshot.tools.isNotEmpty()) {
                    append(" tools=")
                    append(snapshot.tools.joinToString { it.toolName })
                }
                if (snapshot.resources.isNotEmpty()) {
                    append(" resources=")
                    append(snapshot.resources.size)
                }
                if (snapshot.prompts.isNotEmpty()) {
                    append(" prompts=")
                    append(snapshot.prompts.size)
                }
            }
            append("\n</mcp_servers>")
            if (mcpToolBindings.isNotEmpty() && !exposeNamespacedMcpTools) {
                // 仅在非命名空间模式下列出工具清单（命名空间模式下 LLM 已能从 tools 里看到）
                append("\n<mcp_tools>")
                mcpToolBindings.forEach { binding ->
                    append("\n- ")
                    append(binding.serverId)
                    append("/")
                    append(binding.toolName)
                    if (binding.description.isNotBlank()) {
                        append(": ")
                        append(binding.description)
                    }
                }
                append("\n</mcp_tools>")
            }
        }
    }

    private fun renderTaskStateForPrompt(taskState: AgentTaskState): String {
        if (taskState.isEmpty) {
            return "No active task state yet."
        }
        return buildString {
            append("status: ")
            append(taskState.status.storageValue)
            if (taskState.goal.isNotBlank()) {
                append("\ngoal: ")
                append(taskState.goal)
            }
            if (taskState.summary.isNotBlank()) {
                append("\nsummary: ")
                append(taskState.summary)
            }
            if (taskState.todos.isNotEmpty()) {
                append("\ntodos:")
                taskState.todos.forEach { item ->
                    append("\n- [")
                    append(if (item.done) "x" else " ")
                    append("] ")
                    append(item.id)
                    append(": ")
                    append(item.text)
                }
            }
            if (taskState.completionCriteria.isNotEmpty()) {
                append("\ncompletion_criteria:")
                taskState.completionCriteria.forEach { criterion ->
                    append("\n- ")
                    append(criterion)
                }
            }
        }
    }

    private fun shouldForceToolUse(latestUserText: String): Boolean =
        shouldForceToolUseForLatestUserText(latestUserText)

    private fun buildTavilySearchSummary(response: JSONObject): String = buildString {
        val answer = response.optString("answer").trim()
        if (answer.isNotBlank()) {
            append(answer)
        }

        val results = response.optJSONArray("results") ?: JSONArray()
        if (results.length() > 0) {
            if (isNotEmpty()) append("\n\n")
            append("Top results:")
            for (index in 0 until minOf(results.length(), 5)) {
                val result = results.optJSONObject(index) ?: continue
                append("\n")
                append(index + 1)
                append(". ")
                append(result.optString("title").ifBlank { result.optString("url") })
                val url = result.optString("url").trim()
                if (url.isNotBlank()) {
                    append(" - ")
                    append(url)
                }
                val snippet = result.optString("content").trim()
                if (snippet.isNotBlank()) {
                    append("\n")
                    append(snippet.take(280))
                }
            }
        }
    }

    private fun JSONObject.optNumberAsDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.optEastmoneyPrice(
        key: String,
        market: String,
    ): Double? {
        val rawValue = optNumberAsDouble(key) ?: return null
        if (rawValue <= 0.0) return null
        val divisor = when {
            market == "105" && rawValue >= 10_000.0 -> 1_000.0
            else -> 100.0
        }
        return rawValue / divisor
    }

    private fun JSONObject.optEastmoneyPriceDelta(
        key: String,
        market: String,
    ): Double? {
        val rawValue = optNumberAsDouble(key) ?: return null
        val divisor = when {
            market == "105" && kotlin.math.abs(rawValue) >= 10_000.0 -> 1_000.0
            else -> 100.0
        }
        return rawValue / divisor
    }

    private fun JSONObject.optEastmoneyPercent(key: String): Double? {
        val rawValue = optNumberAsDouble(key) ?: return null
        return rawValue / 100.0
    }

    private fun JSONObject.optEastmoneyRatio(key: String): Double? {
        val rawValue = optNumberAsDouble(key) ?: return null
        if (rawValue == 0.0) return null
        return rawValue / 100.0
    }

    private fun formatStockNumber(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    private fun JSONObject.stringValue(
        primaryKey: String,
        aliasKey: String? = null,
    ): String {
        val primary = cleanOptionalString(primaryKey)
        if (primary.isNotBlank()) return primary
        return aliasKey?.let { cleanOptionalString(it) }.orEmpty()
    }

    private fun JSONObject.intValue(
        primaryKey: String,
        aliasKey: String? = null,
    ): Int? = when {
        hasUsableValue(primaryKey) -> optInt(primaryKey)
        aliasKey != null && hasUsableValue(aliasKey) -> optInt(aliasKey)
        else -> null
    }

    private fun JSONObject.booleanValue(
        primaryKey: String,
        aliasKey: String? = null,
    ): Boolean? = when {
        hasUsableValue(primaryKey) -> optBoolean(primaryKey)
        aliasKey != null && hasUsableValue(aliasKey) -> optBoolean(aliasKey)
        else -> null
    }

    private fun JSONObject.stringArrayValue(
        primaryKey: String,
        aliasKey: String? = null,
    ): List<String> {
        val array = when {
            hasUsableValue(primaryKey) -> optJSONArray(primaryKey)
            aliasKey != null && hasUsableValue(aliasKey) -> optJSONArray(aliasKey)
            else -> null
        } ?: return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }
    }

    private fun JSONObject.stockSymbolsValue(): List<String> {
        val fromArray = stringArrayValue("symbols")
        if (fromArray.isNotEmpty()) {
            return fromArray.distinct()
        }
        val fromSymbol = stringValue("symbol")
        return fromSymbol
            .split(Regex("[,;\\s]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun JSONObject.hasUsableValue(key: String): Boolean =
        has(key) && !isNull(key) && !cleanOptionalString(key).equals("null", ignoreCase = true)

    private fun JSONObject.cleanOptionalString(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val value = optString(key).trim()
        return value.takeUnless { it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true) }
            .orEmpty()
    }

    private fun extractMcpServerId(arguments: JSONObject): String =
        arguments.optString("server_id").trim().ifBlank {
            arguments.optString("serverId").trim()
        }
}
