package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

enum class LlmProvider(
    val storageValue: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModelId: String,
) {
    OpenAiResponses(
        storageValue = "openai_responses",
        displayName = "OpenAI (Responses)",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModelId = "gpt-5.4",
    ),
    OpenAiCompatible(
        storageValue = "openai_compatible",
        displayName = "OpenAI (Chat Completions)",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModelId = "gpt-5.4",
    ),
    VertexExpress(
        storageValue = "vertex_express",
        displayName = "Vertex AI (Express Mode)",
        defaultBaseUrl = "https://aiplatform.googleapis.com/v1",
        defaultModelId = "gemini-2.5-flash",
    ),
    AnthropicMessages(
        storageValue = "anthropic_messages",
        displayName = "Anthropic Messages API",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModelId = "claude-sonnet-4-5",
    );

    companion object {
        fun fromStorage(value: String?): LlmProvider =
            entries.firstOrNull { it.storageValue == value } ?: OpenAiCompatible
    }
}

enum class AgentModeAuthorizationMethod(
    val storageValue: String,
    val displayName: String,
) {
    Root(
        storageValue = "root",
        displayName = "Root",
    ),
    Shizuku(
        storageValue = "shizuku",
        displayName = "Shizuku",
    );

    companion object {
        fun fromStorage(
            value: String?,
            defaultValue: AgentModeAuthorizationMethod = Shizuku,
        ): AgentModeAuthorizationMethod =
            entries.firstOrNull { it.storageValue == value } ?: defaultValue
    }
}

enum class AppLanguage(
    val storageValue: String,
    val languageTag: String,
) {
    English(
        storageValue = "en",
        languageTag = "en",
    ),
    SimplifiedChinese(
        storageValue = "zh-CN",
        languageTag = "zh-CN",
    );

    companion object {
        fun fromStorage(
            value: String?,
            defaultValue: AppLanguage = defaultAppLanguage(),
        ): AppLanguage = entries.firstOrNull { it.storageValue == value } ?: defaultValue
    }
}

enum class AppThemeMode(
    val storageValue: String,
) {
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStorage(value: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: Light
    }
}

val DefaultSystemPrompt: String = """
你是 Aether，一个运行在 Android 设备上的本地优先 AI 智能体。默认使用简体中文回答，除非用户明确要求其他语言。

你的核心目标是把用户的任务真正完成，而不是只给建议。遇到本地文件、上传附件、设备状态、网页内容、命令执行结果时，不要凭空猜测；优先使用可用工具读取、搜索、执行或验证。

运行环境：
- 你在 Android 上工作，shell 命令通过 Termux 执行。
- 当前会话有独立工作区，路径通常位于 ~/.aether/workspaces/<session-id>。
- 用户上传的文件会复制到当前会话工作区，通常在 uploads/ 目录下。
- 如果用户上传了文件，不要假设文件内容。需要查看时使用 read、grep、find、ls、bash 等工具读取。
- 图片附件不会自动进入视觉模型。需要看图时，对工作区中的图片路径调用 analyze_image。超大图片（>5MB）会在读取前自动压缩缩放，无需手动处理。
- 当你生成用户需要保存或下载的文件时，使用当前工作区中的绝对路径，并在回复里给出 file:// 链接。
- 支持多模型提供方（OpenAI / Anthropic / Vertex AI / OpenAI Compatible），用户可在设置中配置。
- 支持 MCP 服务器（HTTP / stdio）和 Agent Skills 扩展，可通过设置页面管理。
- Agent Mode 需要 Shizuku 或 Root 授权，支持虚拟显示和设备控制。

工作方式：
- 先理解用户真实目标，再选择最短可靠路径完成。
- 简单问题直接回答；涉及本地状态、文件、代码、日志、网页或设备环境时先检查再下结论。
- 多步骤任务中，先简短说明你要检查什么，再调用工具；不要长篇解释计划。
- 发现问题时直接指出根因、证据和修复方式。
- 不要声称已经执行命令、读取文件、修改文件或测试功能，除非你确实调用了相应工具。
- 不要编造路径、日志、文件内容、网页内容、版本号或设备状态。

代码和文件处理：
- 修改代码前先阅读相关文件和现有模式。
- 保持改动范围小，优先沿用项目已有架构和命名风格。
- 不要重构无关代码，不要覆盖用户已有改动。
- 对 Android 项目，修改后应尽量构建验证；如果用户需要安装，再安装 APK 到设备。
- 遇到失败时，保留关键错误信息，说明失败发生在哪一层。

Termux 和命令：
- bash 在手机 Termux 中运行，可能受权限、冷启动、后台限制影响。
- 长时间命令不要反复忙等；如果命令仍在运行，等待后再查询输出。
- 对危险操作，例如删除、覆盖大量文件、清空目录、重置仓库、安装未知内容，先向用户确认。
- 优先使用专用文件工具 read/edit/write/grep/find/ls；只有需要 shell 能力时才用 bash。

联网和资料：
- 用户给出 URL 时，使用网页读取工具（fetch_web_url）获取内容后再回答。
- 需要最新信息、公开资料检索或不确定事实时，使用搜索工具（tavily_search，需配置 API Key）。
- 回答基于外部资料时，简要说明来源或依据。

沟通风格：
- 简洁、直接、工程化。
- 先给结论，再给必要细节。
- 不要空泛鼓励，不要套话。
- 如果有多个方案，说明推荐方案和取舍。
- 如果信息不足，先基于可检查内容自行调查；确实无法判断时再问一个明确问题。
""".trimIndent()

data class AppSettings(
    val provider: LlmProvider = LlmProvider.OpenAiCompatible,
    val apiKey: String = "",
    val baseUrl: String = LlmProvider.OpenAiCompatible.defaultBaseUrl,
    val modelId: String = LlmProvider.OpenAiCompatible.defaultModelId,
    val systemPrompt: String = DefaultSystemPrompt,
    val tavilyApiKey: String = "",
    val userAgent: String = "",
    val llmInactivityReconnectTimeoutSeconds: Int = DefaultLlmInactivityReconnectTimeoutSeconds,
    val keepTasksRunningInBackground: Boolean = true,
    val notifyOnTaskCompletion: Boolean = true,
    val agentModeAuthorizationEnabled: Boolean = false,
    val agentModeAuthorizationMethod: AgentModeAuthorizationMethod = AgentModeAuthorizationMethod.Shizuku,
    val language: AppLanguage = defaultAppLanguage(),
    val themeMode: AppThemeMode = AppThemeMode.Light,
    val defaultChatModelKey: String = "",
    val defaultTitleModelKey: String = "",
    val defaultNamingModelKey: String = "",
    val unsupportedParallelToolCallProviderKeys: List<String> = emptyList(),
    val basicFunctionCallingCompatibilityMode: Boolean = false,
    val onboardingSeenVersion: Int = 0,
    val onboardingCompletedVersion: Int = 0,
    val privacyPolicyAccepted: Boolean = false,
    val lastUpdateCheckAtMillis: Long = 0L,
)

const val CurrentOnboardingVersion = 1
const val DefaultLlmInactivityReconnectTimeoutSeconds = 360
private const val MinLlmInactivityReconnectTimeoutSeconds = 30
private const val MaxLlmInactivityReconnectTimeoutSeconds = 3600
const val OnboardingStarterPrompt = "Hi"
const val AetherWebsiteUrl = "https://github.com/sbxjtyes/Aether"
const val AetherPrivacyPolicyUrl = "https://github.com/sbxjtyes/Aether/wiki/Privacy-Policy"

fun defaultAppLanguage(
    locale: Locale = Locale.getDefault(),
): AppLanguage = if (locale.language.equals("zh", ignoreCase = true)) {
    AppLanguage.SimplifiedChinese
} else {
    AppLanguage.English
}

fun normalizeLlmInactivityReconnectTimeoutSeconds(
    value: Int?,
): Int = when (value) {
    null -> DefaultLlmInactivityReconnectTimeoutSeconds
    else -> value.coerceIn(
        MinLlmInactivityReconnectTimeoutSeconds,
        MaxLlmInactivityReconnectTimeoutSeconds,
    )
}

fun AppSettings.shouldLaunchOnboarding(
    onboardingVersion: Int = CurrentOnboardingVersion,
): Boolean = onboardingSeenVersion < onboardingVersion

fun AppSettings.isOnboardingComplete(
    onboardingVersion: Int = CurrentOnboardingVersion,
): Boolean = onboardingCompletedVersion >= onboardingVersion

fun AppSettings.parallelToolCallSupportKey(): String {
    val normalizedBaseUrl = runCatching {
        val uri = URI(baseUrl.trim())
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.path.orEmpty().trimEnd('/')
        "$scheme://$host$port$path"
    }.getOrDefault(baseUrl.trim().trimEnd('/').lowercase(Locale.US))
    return listOf(
        provider.storageValue,
        normalizedBaseUrl,
        modelId.trim().lowercase(Locale.US),
    ).joinToString("|")
}

fun AppSettings.supportsParallelToolCalls(): Boolean =
    !basicFunctionCallingCompatibilityMode &&
        parallelToolCallSupportKey() !in unsupportedParallelToolCallProviderKeys

fun isProviderSetupValid(
    provider: LlmProvider,
    apiKey: String,
    baseUrl: String,
    modelId: String,
): Boolean {
    if (baseUrl.trim().isEmpty() || modelId.trim().isEmpty()) return false
    if (provider.requiresApiKey(baseUrl) && apiKey.trim().isEmpty()) return false
    return true
}

val LlmProvider.requiresApiKey: Boolean
    get() = this == LlmProvider.VertexExpress || this == LlmProvider.AnthropicMessages

fun LlmProvider.requiresApiKey(baseUrl: String): Boolean =
    requiresApiKey || usesOfficialOpenAiEndpoint(this, baseUrl)

fun usesOfficialOpenAiEndpoint(
    provider: LlmProvider,
    baseUrl: String,
): Boolean {
    if (provider != LlmProvider.OpenAiResponses && provider != LlmProvider.OpenAiCompatible) return false
    val host = runCatching {
        URI(baseUrl.trim()).host.orEmpty().lowercase(Locale.US)
    }.getOrDefault("")
    return host == "api.openai.com"
}

val OfficialVertexPreviewModels: List<String> = listOf(
    "gemini-3.1-pro-preview",
    "gemini-3-flash-preview",
    "gemini-3.1-flash-lite-preview",
)

fun usesOfficialVertexEndpoint(
    provider: LlmProvider,
    baseUrl: String,
): Boolean {
    if (provider != LlmProvider.VertexExpress) return false
    val host = runCatching {
        URI(baseUrl.trim()).host.orEmpty().lowercase(Locale.US)
    }.getOrDefault("")
    return host == "aiplatform.googleapis.com" ||
        host.endsWith("-aiplatform.googleapis.com")
}

fun shouldShowResumeSetupBanner(
    settings: AppSettings,
    messageCount: Int,
    draftInput: String,
    hasDraftAttachments: Boolean,
): Boolean = messageCount == 0 &&
    !settings.isOnboardingComplete() &&
    draftInput.isBlank() &&
    !hasDraftAttachments

fun shouldMarkOnboardingCompleted(
    settings: AppSettings,
    isSuccessfulAssistantReply: Boolean,
): Boolean = isSuccessfulAssistantReply && !settings.isOnboardingComplete()

fun shouldRevealFollowUpTourCard(
    isAwaitingFollowUpTour: Boolean,
    isSuccessfulAssistantReply: Boolean,
): Boolean = isAwaitingFollowUpTour && isSuccessfulAssistantReply

// ──────────────────────────────────────────────────────────────────────────────
// Multi-Provider Configuration
// ──────────────────────────────────────────────────────────────────────────────

data class LlmProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val name: String,
    val providerType: LlmProvider,
    val apiKey: String,
    val baseUrl: String,
    val modelId: String,
    val userAgent: String = "",
    val cachedModels: List<String> = listOf(modelId),
    val enabledModelIds: List<String> = cachedModels,
    val isEnabled: Boolean = true,
    val basicFunctionCallingCompatibilityMode: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
)

internal fun LlmProviderConfig.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("providerId", providerId)
    put("name", name)
    put("providerType", providerType.storageValue)
    put("apiKey", apiKey)
    put("baseUrl", baseUrl)
    put("modelId", modelId)
    put("userAgent", userAgent)
    put("cachedModels", JSONArray().apply { cachedModels.forEach(::put) })
    put("enabledModelIds", JSONArray().apply { enabledModelIds.forEach(::put) })
    put("isEnabled", isEnabled)
    put("basicFunctionCallingCompatibilityMode", basicFunctionCallingCompatibilityMode)
    put("createdAtMillis", createdAtMillis)
    put("updatedAtMillis", updatedAtMillis)
}

internal fun parseProviderConfigs(rawValue: String): List<LlmProviderConfig> {
    if (rawValue.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(rawValue)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val providerType = LlmProvider.fromStorage(json.optString("providerType"))
                val providerName = json.optString("name").trim()
                    .ifBlank { providerType.displayName }
                val baseUrl = json.optString("baseUrl").trim()
                    .ifBlank { providerType.defaultBaseUrl }
                val modelId = json.optString("modelId").trim()
                    .ifBlank { providerType.defaultModelId }
                val enabledModelIds = json.optJSONArray("enabledModelIds").toStringListSafe()
                val cachedModels = normalizeStringList(
                    buildList {
                        addAll(json.optJSONArray("cachedModels").toStringListSafe())
                        add(modelId)
                        addAll(enabledModelIds)
                    }
                )
                val inferredProviderId = providerName
                    .sanitizeProviderId()
                    .ifBlank { "${providerType.storageValue}_${index + 1}" }
                add(
                    LlmProviderConfig(
                        id = json.optString("id").trim().ifBlank { UUID.randomUUID().toString() },
                        providerId = json.optString("providerId").trim().ifBlank { inferredProviderId },
                        name = providerName,
                        providerType = providerType,
                        apiKey = json.optString("apiKey"),
                        baseUrl = baseUrl,
                        modelId = modelId,
                        userAgent = json.optString("userAgent"),
                        cachedModels = cachedModels,
                        enabledModelIds = if (json.has("enabledModelIds")) {
                            normalizeStringList(enabledModelIds.filter(cachedModels::contains))
                        } else {
                            cachedModels
                        },
                        isEnabled = if (json.has("isEnabled")) {
                            json.optBoolean("isEnabled", true)
                        } else {
                            true
                        },
                        basicFunctionCallingCompatibilityMode = json.optBoolean(
                            "basicFunctionCallingCompatibilityMode",
                            false,
                        ),
                        createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
                        updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

internal fun serializeProviderConfigs(configs: List<LlmProviderConfig>): String =
    JSONArray().apply { configs.forEach { put(it.toJson()) } }.toString()

private fun JSONArray?.toStringListSafe(): List<String> {
    if (this == null) return emptyList()
    return normalizeStringList(
        buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }
    )
}

private fun normalizeStringList(values: List<String>): List<String> =
    values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

private val ProviderIdPattern = Regex("^[a-z0-9_]+$")

fun isValidProviderId(value: String): Boolean = ProviderIdPattern.matches(value.trim())

fun String.sanitizeProviderId(): String =
    lowercase(Locale.US)
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')

fun buildModelOptionKey(
    providerConfigId: String,
    modelId: String,
): String = "$providerConfigId::$modelId"

fun LlmProviderConfig.availableModels(): List<String> = normalizeStringList(cachedModels + modelId)

fun LlmProviderConfig.enabledModels(): List<String> = normalizeStringList(
    enabledModelIds.filter { availableModels().contains(it) }
)

data class ProviderModelOption(
    val key: String,
    val providerConfigId: String,
    val providerId: String,
    val providerName: String,
    val providerType: LlmProvider,
    val apiKey: String,
    val baseUrl: String,
    val modelId: String,
    val userAgent: String,
    val basicFunctionCallingCompatibilityMode: Boolean,
    val fullLabel: String,
    val chatLabel: String,
)

enum class AutomaticModelPurpose {
    Chat,
    Title,
    Naming,
}

fun List<LlmProviderConfig>.availableModelOptions(
    includeDisabledProviders: Boolean = false,
    includeDisabledModels: Boolean = false,
): List<ProviderModelOption> {
    val scopedConfigs = (if (includeDisabledProviders) this else filter { it.isEnabled })
        .filter { it.baseUrl.trim().isNotEmpty() && it.providerId.trim().isNotEmpty() }
    val modelCounts = scopedConfigs
        .flatMap { config ->
            val models = if (includeDisabledModels) config.availableModels() else config.enabledModels()
            models.map { modelId -> modelId to config.id }
        }
        .groupingBy { it.first }
        .eachCount()

    return scopedConfigs.flatMap { config ->
        val models = if (includeDisabledModels) config.availableModels() else config.enabledModels()
        models.map { modelId ->
            val providerId = config.providerId.trim()
            val providerName = config.name.trim().ifBlank { providerId }
            val normalizedModelId = modelId.trim()
            val fullLabel = "$providerId/$normalizedModelId"
            ProviderModelOption(
                key = buildModelOptionKey(config.id, normalizedModelId),
                providerConfigId = config.id,
                providerId = providerId,
                providerName = providerName,
                providerType = config.providerType,
                apiKey = config.apiKey,
                baseUrl = config.baseUrl.trim(),
                modelId = normalizedModelId,
                userAgent = config.userAgent.trim(),
                basicFunctionCallingCompatibilityMode = config.basicFunctionCallingCompatibilityMode,
                fullLabel = fullLabel,
                chatLabel = if ((modelCounts[normalizedModelId] ?: 0) > 1) fullLabel else normalizedModelId,
            )
        }
    }.sortedWith(compareBy(ProviderModelOption::providerId, ProviderModelOption::modelId))
}

fun AppSettings.withModelOption(option: ProviderModelOption): AppSettings = copy(
    provider = option.providerType,
    apiKey = option.apiKey.trim(),
    baseUrl = option.baseUrl.trim(),
    modelId = option.modelId.trim(),
    userAgent = option.userAgent.trim(),
    basicFunctionCallingCompatibilityMode = option.basicFunctionCallingCompatibilityMode,
)

fun List<ProviderModelOption>.findModelOption(key: String?): ProviderModelOption? =
    firstOrNull { it.key == key }

fun List<ProviderModelOption>.resolveAutomaticModelKey(
    purpose: AutomaticModelPurpose,
): String {
    if (isEmpty()) return ""
    val rankedOption = mapNotNull { option ->
        automaticModelPriority(option.modelId, purpose)?.let { priority -> option to priority }
    }
        .minWithOrNull(compareBy<Pair<ProviderModelOption, Int>> { it.second }.thenBy { it.first.providerId }.thenBy { it.first.modelId })
        ?.first
    return rankedOption?.key ?: firstOrNull()?.key.orEmpty()
}

private fun automaticModelPriority(
    modelId: String,
    purpose: AutomaticModelPurpose,
): Int? {
    val normalized = modelId.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")
    val isGemini31Pro = normalized.contains("gemini31pro")
    val isGemini3Flash = Regex("gemini3(?:0)?flash").containsMatchIn(normalized) ||
        normalized.contains("gemini3flashpreview")
    val isGemini31FlashLite = normalized.contains("gemini31flashlite")

    return when (purpose) {
        AutomaticModelPurpose.Chat -> when {
            normalized.contains("gpt55") -> 0
            normalized.contains("gpt54") -> 1
            normalized.contains("claude") && normalized.contains("opus") && normalized.contains("47") -> 2
            normalized.contains("claude") && normalized.contains("sonnet") && normalized.contains("46") -> 3
            isGemini31Pro -> 4
            isGemini3Flash -> 5
            else -> null
        }

        AutomaticModelPurpose.Title,
        AutomaticModelPurpose.Naming -> when {
            isGemini3Flash -> 0
            isGemini31FlashLite -> 1
            normalized.contains("gpt54mini") -> 2
            normalized.contains("claude") && normalized.contains("haiku") && normalized.contains("46") -> 3
            else -> null
        }
    }
}
