package com.zhousl.aether.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AppThemeMode
import org.json.JSONObject

@Stable
data class AetherStrings(
    val appLanguage: AppLanguage,
    val menu: String,
    val newChat: String,
    val more: String,
    val whatCanIHelpWith: String,
    val createImage: String,
    val brainstorm: String,
    val analyzeImages: String,
    val aetherIsThinking: String,
    val replyToAether: String,
    val askAether: String,
    val voice: String,
    val images: String,
    val apps: String,
    val gpts: String,
    val recent: String,
    val noConversationsYet: String,
    val settings: String,
    val search: String,
    val emptyDraft: String,
    val fileSaved: String,
    val couldNotSaveFile: String,
    val replyCopied: String,
    val termuxAccessGranted: String,
    val termuxAccessNotGranted: String,
    val generalSettings: String,
    val language: String,
    val languageDescription: String,
    val theme: String,
    val themeDescription: String,
    val modelProviders: String,
    val personalization: String,
    val webTools: String,
    val reliability: String,
    val agentSkills: String,
    val mcpServers: String,
    val termux: String,
    val agentMode: String,
    val developerSettings: String,
    val about: String,
    val getStartedTour: String,
    val generalSettingsHubHint: String,
    val customInstructions: String,
    val tavilyConfigured: String,
    val tavilyNotConfigured: String,
    val backgroundRunsOn: String,
    val backgroundRunsOff: String,
    val connected: String,
    val setupRequired: String,
    val agentModeSubtitle: String,
    val getStartedTourSubtitle: String,
    val developerSettingsSubtitle: String,
    val lightThemeSubtitle: String,
    val darkThemeSubtitle: String,
    val close: String,
    val skip: String,
    val back: String,
    val continueLabel: String,
    val done: String,
    val cancel: String,
    val next: String,
    val install: String,
    val refresh: String,
    val openSettings: String,
    val openTermux: String,
    val grantAccess: String,
    val loadModels: String,
    val startChat: String,
    val send: String,
    val sendFollowUp: String,
    val hide: String,
    val chat: String,
    val rename: String,
    val export: String,
    val delete: String,
    val copy: String,
    val selectText: String,
    val editMessage: String,
    val retry: String,
    val copyReply: String,
    val redoReply: String,
    val deleteReply: String,
    val save: String,
    val searchChats: String,
    val noChatsMatch: String,
    val newChatFallback: String,
    val addAttachmentOrTool: String,
    val photos: String,
    val files: String,
    val agentModeLabel: String,
    val virtualDisplayPreview: String,
    val displayLabel: String,
    val standby: String,
    val analyzeImageChip: String,
    val codeChip: String,
    val helpMeWriteChip: String,
    val summarizeFileChip: String,
    val finishSetupTitle: String,
    val finishSetupSubtitle: String,
    val resumeSetup: String,
    val thinking: String,
    val firstPromptReadyTitle: String,
    val firstPromptReadySubtitle: String,
    val editingEarlierMessageTitle: String,
    val editingEarlierMessageSubtitle: String,
    val steerCurrentRun: String,
    val queueNextTurn: String,
    val noOutput: String,
    val contentTruncated: String,
)

private val LocalAetherStrings = staticCompositionLocalOf {
    aetherStringsFor(AppLanguage.English)
}

@Composable
fun AetherLocalization(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val strings = remember(language) { aetherStringsFor(language) }
    CompositionLocalProvider(LocalAetherStrings provides strings, content = content)
}

@Composable
fun rememberAetherStrings(): AetherStrings = LocalAetherStrings.current

fun aetherStringsFor(language: AppLanguage): AetherStrings = when (language) {
    AppLanguage.English -> AetherStrings(
        appLanguage = AppLanguage.English,
        menu = "Menu",
        newChat = "New chat",
        more = "More",
        whatCanIHelpWith = "What can I help with?",
        createImage = "Create image",
        brainstorm = "Brainstorm",
        analyzeImages = "Analyze images",
        aetherIsThinking = "Aether is thinking...",
        replyToAether = "Reply to Aether",
        askAether = "Ask Aether",
        voice = "Voice",
        images = "Images",
        apps = "Apps",
        gpts = "GPTs",
        recent = "Recent",
        noConversationsYet = "No conversations yet.",
        settings = "Settings",
        search = "Search",
        emptyDraft = "Empty draft",
        fileSaved = "File saved",
        couldNotSaveFile = "Couldn't save file",
        replyCopied = "Reply copied",
        termuxAccessGranted = "Termux access granted",
        termuxAccessNotGranted = "Termux access not granted",
        generalSettings = "General Settings",
        language = "Language",
        languageDescription = "Choose the app language.",
        theme = "Theme",
        themeDescription = "Choose the app appearance.",
        modelProviders = "Model Providers",
        personalization = "Personalization",
        webTools = "Web Tools",
        reliability = "Reliability",
        agentSkills = "Agent Skills",
        mcpServers = "MCP Servers",
        termux = "Termux",
        agentMode = "Agent Mode",
        developerSettings = "Developer Settings",
        about = "About",
        getStartedTour = "Get Started Tour",
        generalSettingsHubHint = "App language and appearance",
        customInstructions = "Custom instructions",
        tavilyConfigured = "Tavily search configured",
        tavilyNotConfigured = "URL fetch ready, Tavily not configured",
        backgroundRunsOn = "Background runs on",
        backgroundRunsOff = "Background runs off",
        connected = "Connected",
        setupRequired = "Setup required",
        agentModeSubtitle = "Authorization and virtual display",
        getStartedTourSubtitle = "Replay the first-run landing and setup flow",
        developerSettingsSubtitle = "Replay the follow-up onboarding flow",
        lightThemeSubtitle = "Keep the current bright appearance.",
        darkThemeSubtitle = "Use a darker interface across the app.",
        close = "Close",
        skip = "Skip",
        back = "Back",
        continueLabel = "Continue",
        done = "Done",
        cancel = "Cancel",
        next = "Next",
        install = "Install",
        refresh = "Refresh",
        openSettings = "Open settings",
        openTermux = "Open Termux",
        grantAccess = "Grant access",
        loadModels = "Load models",
        startChat = "Start chat",
        send = "Send",
        sendFollowUp = "Send follow-up",
        hide = "Hide",
        chat = "Chat",
        rename = "Rename",
        export = "Export",
        delete = "Delete",
        copy = "Copy",
        selectText = "Select text",
        editMessage = "Edit message",
        retry = "Retry",
        copyReply = "Copy reply",
        redoReply = "Redo reply",
        deleteReply = "Delete reply",
        save = "Save",
        searchChats = "Search chats",
        noChatsMatch = "No chats match",
        newChatFallback = "New chat",
        addAttachmentOrTool = "Add attachment or tool",
        photos = "Photos",
        files = "Files",
        agentModeLabel = "Agent Mode",
        virtualDisplayPreview = "Virtual display preview will appear after the agent starts.",
        displayLabel = "display",
        standby = "standby",
        analyzeImageChip = "Analyze image",
        codeChip = "Code",
        helpMeWriteChip = "Help me write",
        summarizeFileChip = "Summarize file",
        finishSetupTitle = "Finish setup",
        finishSetupSubtitle = "Connect a model, then come back to chat.",
        resumeSetup = "Resume setup",
        thinking = "Thinking",
        firstPromptReadyTitle = "First prompt ready",
        firstPromptReadySubtitle = "Tap send to test Aether.",
        editingEarlierMessageTitle = "Editing earlier message",
        editingEarlierMessageSubtitle = "Sending will replace the replies that came after it.",
        steerCurrentRun = "Steer current run",
        queueNextTurn = "Queue next turn",
        noOutput = "No output",
        contentTruncated = "Content truncated for readability.",
    )

    AppLanguage.SimplifiedChinese -> AetherStrings(
        appLanguage = AppLanguage.SimplifiedChinese,
        menu = "菜单",
        newChat = "新建对话",
        more = "更多",
        whatCanIHelpWith = "想让我帮你做什么？",
        createImage = "生成图片",
        brainstorm = "头脑风暴",
        analyzeImages = "分析图片",
        aetherIsThinking = "Aether 正在思考...",
        replyToAether = "回复 Aether",
        askAether = "询问 Aether",
        voice = "语音",
        images = "图片",
        apps = "应用",
        gpts = "GPTs",
        recent = "最近",
        noConversationsYet = "还没有对话。",
        settings = "设置",
        search = "搜索",
        emptyDraft = "空白草稿",
        fileSaved = "文件已保存",
        couldNotSaveFile = "无法保存文件",
        replyCopied = "回复已复制",
        termuxAccessGranted = "已授予 Termux 访问权限",
        termuxAccessNotGranted = "未授予 Termux 访问权限",
        generalSettings = "通用设置",
        language = "语言",
        languageDescription = "选择应用界面语言。",
        theme = "主题",
        themeDescription = "选择应用界面外观。",
        modelProviders = "模型提供方",
        personalization = "个性化",
        webTools = "网页工具",
        reliability = "可靠性",
        agentSkills = "Agent 技能",
        mcpServers = "MCP 服务器",
        termux = "Termux",
        agentMode = "Agent 模式",
        developerSettings = "开发者设置",
        about = "关于",
        getStartedTour = "新手引导",
        generalSettingsHubHint = "应用语言与外观",
        customInstructions = "自定义指令",
        tavilyConfigured = "已配置 Tavily 搜索",
        tavilyNotConfigured = "URL 抓取可用，Tavily 未配置",
        backgroundRunsOn = "后台运行已开启",
        backgroundRunsOff = "后台运行已关闭",
        connected = "已连接",
        setupRequired = "需要设置",
        agentModeSubtitle = "授权与虚拟显示",
        getStartedTourSubtitle = "重新播放首次启动和设置流程",
        developerSettingsSubtitle = "重新播放后续引导流程",
        lightThemeSubtitle = "保持当前明亮外观。",
        darkThemeSubtitle = "在整个应用中使用深色界面。",
        close = "关闭",
        skip = "跳过",
        back = "返回",
        continueLabel = "继续",
        done = "完成",
        cancel = "取消",
        next = "下一步",
        install = "安装",
        refresh = "刷新",
        openSettings = "打开设置",
        openTermux = "打开 Termux",
        grantAccess = "授予权限",
        loadModels = "加载模型",
        startChat = "开始聊天",
        send = "发送",
        sendFollowUp = "发送跟进",
        hide = "隐藏",
        chat = "聊天",
        rename = "重命名",
        export = "导出",
        delete = "删除",
        copy = "复制",
        selectText = "选择文本",
        editMessage = "编辑消息",
        retry = "重试",
        copyReply = "复制回复",
        redoReply = "重新执行回复",
        deleteReply = "删除回复",
        save = "保存",
        searchChats = "搜索对话",
        noChatsMatch = "没有匹配的对话",
        newChatFallback = "新建对话",
        addAttachmentOrTool = "添加附件或工具",
        photos = "照片",
        files = "文件",
        agentModeLabel = "Agent 模式",
        virtualDisplayPreview = "代理启动后会显示虚拟屏幕预览。",
        displayLabel = "显示",
        standby = "待机",
        analyzeImageChip = "分析图片",
        codeChip = "代码",
        helpMeWriteChip = "帮我写",
        summarizeFileChip = "总结文件",
        finishSetupTitle = "完成设置",
        finishSetupSubtitle = "连接模型后再回到聊天。",
        resumeSetup = "继续设置",
        thinking = "思考中",
        firstPromptReadyTitle = "首条消息已就绪",
        firstPromptReadySubtitle = "点击发送来测试 Aether。",
        editingEarlierMessageTitle = "正在编辑较早的消息",
        editingEarlierMessageSubtitle = "发送后会替换它之后的回复。",
        steerCurrentRun = "引导当前运行",
        queueNextTurn = "排队下一轮",
        noOutput = "无输出",
        contentTruncated = "内容已截断，便于阅读。",
    )
}

private val AetherStrings.isChinese: Boolean
    get() = appLanguage == AppLanguage.SimplifiedChinese

fun AetherStrings.languageDisplayName(language: AppLanguage): String = when (language) {
    AppLanguage.English -> "English"
    AppLanguage.SimplifiedChinese -> "简体中文"
}

fun AetherStrings.themeDisplayName(themeMode: AppThemeMode): String = when (themeMode) {
    AppThemeMode.Light -> if (isChinese) "浅色" else "Light"
    AppThemeMode.Dark -> if (isChinese) "深色" else "Dark"
}

fun AetherStrings.generalSettingsSummary(
    language: AppLanguage,
    themeMode: AppThemeMode,
): String = "${languageDisplayName(language)} / ${themeDisplayName(themeMode)}"

fun AetherStrings.reliabilitySummary(
    reconnectAfterSeconds: Int,
    keepTasksRunningInBackground: Boolean,
): String {
    val status = if (keepTasksRunningInBackground) backgroundRunsOn else backgroundRunsOff
    return if (isChinese) {
        "${reconnectAfterSeconds} 秒后重连 / $status"
    } else {
        "Reconnect after ${reconnectAfterSeconds}s / $status"
    }
}

fun AetherStrings.skillCountSummary(skillCount: Int): String = when {
    isChinese && skillCount == 0 -> "未安装技能"
    isChinese -> "已安装 $skillCount 个"
    skillCount == 0 -> "No skills installed"
    else -> "$skillCount installed"
}

fun AetherStrings.serverCountSummary(serverCount: Int): String = when {
    isChinese && serverCount == 0 -> "没有服务器"
    isChinese -> "已配置 $serverCount 个"
    serverCount == 0 -> "No servers"
    else -> "$serverCount configured"
}

fun AetherStrings.noChatsMatchSummary(query: String): String = if (isChinese) {
    "没有匹配的对话：“$query”"
} else {
    "No chats match \"$query\"."
}

fun AetherStrings.attachmentTypeLabel(isImage: Boolean): String = when {
    isChinese && isImage -> "照片"
    isChinese -> "文件"
    isImage -> "Photo"
    else -> "File"
}

fun AetherStrings.attachmentCountLabel(count: Int): String = when {
    isChinese -> "$count 个附件"
    count == 1 -> "1 attachment"
    else -> "$count attachments"
}

fun AetherStrings.pendingInputModeLabel(isQueue: Boolean): String = when {
    isChinese && isQueue -> "已排队"
    isChinese -> "正在引导"
    isQueue -> "Queued"
    else -> "Steering"
}

fun AetherStrings.toolInvocationGroupTitle(count: Int, isRunning: Boolean): String = when {
    isChinese && isRunning -> "正在执行 $count 个工具"
    isChinese -> "已执行 $count 个工具"
    isRunning -> "Executing $count tools"
    else -> "Executed $count tools"
}
fun AetherStrings.toolInvocationTitleLabel(
    toolName: String,
    isRunning: Boolean,
    arguments: JSONObject? = null,
): String = when (toolName.lowercase()) {
    "bash" -> statusLabel(isRunning, "Executing bash command", "Executed bash command", "正在执行 bash 命令", "已执行 bash 命令")
    "fetch_bash_output" -> statusLabel(isRunning, "Fetching bash output", "Fetched bash output", "正在获取 bash 输出", "已获取 bash 输出")
    "kill_bash" -> statusLabel(isRunning, "Stopping bash command", "Stopped bash command", "正在停止 bash 命令", "已停止 bash 命令")
    "sleep" -> statusLabel(isRunning, "Waiting", "Waited", "正在等待", "已等待")
    "read" -> statusLabel(isRunning, "Reading file", "Read file", "正在读取文件", "已读取文件")
    "edit" -> statusLabel(isRunning, "Editing file", "Edited file", "正在编辑文件", "已编辑文件")
    "write" -> statusLabel(isRunning, "Writing file", "Wrote file", "正在写入文件", "已写入文件")
    "grep" -> statusLabel(isRunning, "Searching files", "Searched files", "正在搜索文件", "已搜索文件")
    "find" -> statusLabel(isRunning, "Finding files", "Found files", "正在查找文件", "已找到文件")
    "ls" -> statusLabel(isRunning, "Listing files", "Listed files", "正在列出文件", "已列出文件")
    "analyze_image" -> statusLabel(isRunning, "Analyzing image", "Analyzed image", "正在分析图片", "已分析图片")
    "tavily_search" -> formatArgumentDrivenTitle(
        isRunning = isRunning,
        progressiveVerb = if (isChinese) "正在搜索" else "Searching",
        completedVerb = if (isChinese) "已搜索" else "Searched",
        subject = arguments?.optString("query").orEmpty(),
        fallback = if (isChinese) "Tavily 搜索" else "Tavily search",
    )
    "fetch_web_url" -> formatArgumentDrivenTitle(
        isRunning = isRunning,
        progressiveVerb = if (isChinese) "正在抓取" else "Fetching",
        completedVerb = if (isChinese) "已抓取" else "Fetched",
        subject = arguments?.optString("url").orEmpty(),
        fallback = if (isChinese) "网页" else "web page",
    )
    "stock_market_data" -> {
        val action = arguments?.optString("action").orEmpty().lowercase()
        val symbol = arguments?.optString("symbol").orEmpty().trim()
        val query = arguments?.optString("query").orEmpty().trim()
        when (action) {
            "search" -> formatArgumentDrivenTitle(
                isRunning = isRunning,
                progressiveVerb = if (isChinese) "正在搜索股票" else "Searching stocks",
                completedVerb = if (isChinese) "已搜索股票" else "Searched stocks",
                subject = query,
                fallback = if (isChinese) "股票" else "stocks",
            )
            "chart" -> formatArgumentDrivenTitle(
                isRunning = isRunning,
                progressiveVerb = if (isChinese) "正在获取行情图" else "Fetching chart",
                completedVerb = if (isChinese) "已获取行情图" else "Fetched chart",
                subject = symbol,
                fallback = if (isChinese) "股票图表" else "stock chart",
            )
            else -> formatArgumentDrivenTitle(
                isRunning = isRunning,
                progressiveVerb = if (isChinese) "正在获取行情" else "Fetching quote",
                completedVerb = if (isChinese) "已获取行情" else "Fetched quote",
                subject = symbol.ifBlank { query },
                fallback = if (isChinese) "股票行情" else "stock quote",
            )
        }
    }
    "agent_display" -> formatAgentDisplayTitle(isRunning, arguments)
    else -> if (isChinese) {
        if (isRunning) "正在使用 $toolName" else "已使用 $toolName"
    } else {
        if (isRunning) "Using $toolName" else "Used $toolName"
    }
}

fun AetherStrings.toolInvocationCommandLabel(toolName: String, arguments: JSONObject?): String {
    if (arguments == null) return toolName
    return when (toolName.lowercase()) {
        "bash" -> arguments.optString("command").trim()
        "fetch_bash_output" -> buildRunIdCommand("fetch", "获取", arguments)
        "kill_bash" -> buildRunIdCommand("kill", "终止", arguments)
        "sleep" -> {
            val duration = arguments.optString("duration_ms").ifBlank { arguments.optString("durationMs") }.trim()
            if (isChinese) "等待 ${duration}ms" else "sleep ${duration}ms"
        }
        "read" -> buildReadCommand(arguments)
        "edit" -> buildEditCommand(arguments)
        "write" -> buildPathCommand(arguments, "path", "write", "写入")
        "grep" -> buildPatternCommand(arguments, "grep", "搜索")
        "find" -> buildPatternCommand(arguments, "find", "查找")
        "ls" -> buildPathCommand(arguments, "path", "ls", "列出")
        "analyze_image" -> buildAnalyzeImageCommand(arguments)
        "tavily_search" -> if (isChinese) {
            "搜索 ${arguments.optString("query").trim()}".trim()
        } else {
            "search ${arguments.optString("query").trim()}".trim()
        }
        "fetch_web_url" -> if (isChinese) {
            "抓取 ${arguments.optString("url").trim()}".trim()
        } else {
            "fetch ${arguments.optString("url").trim()}".trim()
        }
        "stock_market_data" -> {
            val action = arguments.optString("action").lowercase().ifBlank { "quote" }
            val symbol = arguments.optString("symbol").trim()
            val query = arguments.optString("query").trim()
            val range = arguments.optString("range").trim()
            val interval = arguments.optString("interval").trim()
            buildString {
                append("stock_market_data")
                if (symbol.isNotBlank()) { append(" "); append(symbol) }
                else if (query.isNotBlank()) { append(" "); append(query) }
                if (action.isNotBlank() && action != "quote") { append(" action=$action") }
                if (range.isNotBlank()) { append(" range=$range") }
                if (interval.isNotBlank()) { append(" interval=$interval") }
            }
        }
        "agent_display" -> summarizeAgentDisplayCommand(arguments)
        else -> toolName
    }.trim()
}

fun AetherStrings.formatArgumentDrivenTitle(
    isRunning: Boolean,
    progressiveVerb: String,
    completedVerb: String,
    subject: String,
    fallback: String,
): String {
    val normalizedSubject = subject.trim()
    val action = if (isRunning) progressiveVerb else completedVerb
    if (normalizedSubject.isBlank()) return "$action $fallback"
    val clipped = normalizedSubject.take(72)
    return if (normalizedSubject.length > 72) "$action $clipped..." else "$action $clipped"
}

fun AetherStrings.formatAgentDisplayTitle(
    isRunning: Boolean,
    arguments: JSONObject?,
): String {
    return when (arguments?.optString("action").orEmpty().lowercase()) {
        "start" -> statusLabel(isRunning, "Starting Agent Mode display", "Started Agent Mode display", "正在启动 Agent 模式显示", "已启动 Agent 模式显示")
        "status" -> statusLabel(isRunning, "Checking Agent Mode display", "Checked Agent Mode display", "正在检查 Agent 模式显示", "已检查 Agent 模式显示")
        "launch" -> formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = if (isChinese) "正在打开" else "Launching",
            completedVerb = if (isChinese) "已打开" else "Launched",
            subject = arguments?.optString("target").orEmpty(),
            fallback = if (isChinese) "Agent 模式中的应用" else "app in Agent Mode",
        )
        "tap" -> statusLabel(isRunning, "Tapping Agent Mode display", "Tapped Agent Mode display", "正在点击 Agent 模式屏幕", "已点击 Agent 模式屏幕")
        "swipe" -> statusLabel(isRunning, "Swiping Agent Mode display", "Swiped Agent Mode display", "正在滑动 Agent 模式屏幕", "已滑动 Agent 模式屏幕")
        "key" -> formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = if (isChinese) "正在按下" else "Pressing",
            completedVerb = if (isChinese) "已按下" else "Pressed",
            subject = arguments?.optString("key").orEmpty(),
            fallback = if (isChinese) "按键" else "key in Agent Mode",
        )
        "text" -> statusLabel(isRunning, "Typing in Agent Mode", "Typed in Agent Mode", "正在输入文本", "已输入文本")
        "screenshot" -> statusLabel(isRunning, "Capturing Agent Mode display", "Captured Agent Mode display", "正在截取 Agent 模式显示", "已截取 Agent 模式显示")
        "stop" -> statusLabel(isRunning, "Stopping Agent Mode display", "Stopped Agent Mode display", "正在停止 Agent 模式显示", "已停止 Agent 模式显示")
        else -> statusLabel(isRunning, "Using Agent Mode display", "Used Agent Mode display", "正在使用 Agent 模式显示", "已使用 Agent 模式显示")
    }
}

private fun AetherStrings.statusLabel(
    isRunning: Boolean,
    enProgress: String,
    enDone: String,
    zhProgress: String,
    zhDone: String,
): String = if (isChinese) {
    if (isRunning) zhProgress else zhDone
} else {
    if (isRunning) enProgress else enDone
}

private fun AetherStrings.buildRunIdCommand(
    enPrefix: String,
    zhPrefix: String,
    arguments: JSONObject,
): String {
    val runId = arguments.optString("run_id").ifBlank { arguments.optString("runId") }.trim()
    return if (isChinese) "$zhPrefix $runId".trim() else "$enPrefix $runId".trim()
}

private fun AetherStrings.buildPathCommand(
    arguments: JSONObject,
    pathKey: String,
    enPrefix: String,
    zhPrefix: String,
): String {
    val path = arguments.optString(pathKey).trim()
    return if (isChinese) "$zhPrefix $path".trim() else "$enPrefix $path".trim()
}

private fun AetherStrings.buildPatternCommand(
    arguments: JSONObject,
    enPrefix: String,
    zhPrefix: String,
): String {
    val pattern = arguments.optString("pattern").trim()
    val path = arguments.optString("path").trim()
    return if (isChinese) {
        "$zhPrefix $path 中的 $pattern".trim()
    } else {
        "$enPrefix $pattern in $path".trim()
    }
}

private fun AetherStrings.buildReadCommand(arguments: JSONObject): String {
    val path = arguments.optString("path").trim()
    val offset = if (arguments.has("offset")) arguments.optInt("offset") else 0
    val limit = if (arguments.has("limit")) arguments.optInt("limit") else null
    return buildString {
        append(if (isChinese) "读取 " else "read ")
        append(path)
        if (offset > 0 || limit != null) {
            append(if (isChinese) " (" else " (")
            if (isChinese) {
                append("偏移=")
            } else {
                append("offset=")
            }
            append(offset)
            if (limit != null) {
                append(if (isChinese) ", 限制=" else ", limit=")
                append(limit)
            }
            append(")")
        }
    }.trim()
}

private fun AetherStrings.buildEditCommand(arguments: JSONObject): String {
    val path = arguments.optString("path").trim()
    val editCount = arguments.optJSONArray("edits")?.length()
        ?: if (arguments.has("oldText") || arguments.has("newText")) 1 else 0
    return if (isChinese) {
        if (editCount > 0) "编辑 $path（$editCount 处）" else "编辑 $path"
    } else {
        if (editCount > 0) "edit $path ($editCount edit${if (editCount == 1) "" else "s"})" else "edit $path"
    }
}

private fun AetherStrings.buildAnalyzeImageCommand(arguments: JSONObject): String {
    val path = arguments.optString("path").trim()
    val prompt = arguments.optString("prompt").trim()
    return buildString {
        append(if (isChinese) "分析图片 " else "analyze_image ")
        append(path)
        if (prompt.isNotBlank()) {
            append(if (isChinese) "（" else " (")
            append(prompt.take(48))
            if (prompt.length > 48) append("...")
            append(if (isChinese) "）" else ")")
        }
    }.trim()
}

private fun AetherStrings.summarizeAgentDisplayCommand(arguments: JSONObject?): String {
    if (arguments == null) return "agent_display"
    val action = arguments.optString("action").trim().ifBlank { "unknown" }
    val target = arguments.optString("target").trim()
    return buildString {
        append(if (isChinese) "agent_display " else "agent_display ")
        append(action)
        if (target.isNotBlank()) {
            append(" ")
            append(target)
        }
    }.trim()
}
