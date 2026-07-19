package com.zhousl.aether.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "aether_settings")

class SettingsRepository(
    private val context: Context,
) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            provider = LlmProvider.fromStorage(preferences[PROVIDER]),
            apiKey = preferences[API_KEY].orEmpty(),
            baseUrl = preferences[BASE_URL] ?: AppSettings().baseUrl,
            modelId = preferences[MODEL_ID] ?: AppSettings().modelId,
            systemPrompt = normalizeStoredSystemPrompt(preferences[SYSTEM_PROMPT]),
            tavilyApiKey = preferences[TAVILY_API_KEY].orEmpty(),
            mineruApiToken = preferences[MINERU_API_TOKEN].orEmpty(),
            llmInactivityReconnectTimeoutSeconds = normalizeLlmInactivityReconnectTimeoutSeconds(
                preferences[LLM_INACTIVITY_RECONNECT_TIMEOUT_SECONDS]
            ),
            keepTasksRunningInBackground = preferences[KEEP_TASKS_RUNNING_IN_BACKGROUND] ?: true,
            notifyOnTaskCompletion = preferences[NOTIFY_ON_TASK_COMPLETION] ?: true,
            voiceServerBaseUrl = preferences[VOICE_SERVER_BASE_URL].orEmpty(),
            voiceServerToken = preferences[VOICE_SERVER_TOKEN].orEmpty(),
            voiceId = preferences[VOICE_ID]?.trim().orEmpty().ifBlank { DefaultVoiceId },
            voiceEnabled = (preferences[VOICE_ENABLED] ?: false) &&
                (preferences[VOICE_AUTHORIZATION_CONFIRMED] ?: false),
            voiceAutoRead = preferences[VOICE_AUTO_READ]
                ?: preferences[LEGACY_OFFLINE_VOICE_AUTO_READ]
                ?: false,
            voiceAuthorizationConfirmed = preferences[VOICE_AUTHORIZATION_CONFIRMED]
                ?: preferences[LEGACY_OFFLINE_VOICE_AUTHORIZATION_CONFIRMED]
                ?: false,
            voiceSpeedPercent = normalizeVoiceSpeedPercent(
                preferences[VOICE_SPEED_PERCENT] ?: preferences[LEGACY_OFFLINE_VOICE_SPEED_PERCENT]
            ),
            agentLoopPolicy = normalizeAgentLoopPolicy(
                AgentLoopPolicy(
                    autonomousContinuationEnabled =
                        preferences[AUTONOMOUS_CONTINUATION_ENABLED] ?: true,
                    maxAutonomousContinuationTurns = normalizeAutonomousContinuationTurns(
                        preferences[MAX_AUTONOMOUS_CONTINUATION_TURNS]
                    ),
                )
            ),
            agentModeAuthorizationEnabled = preferences[AGENT_MODE_AUTHORIZATION_ENABLED] ?: false,
            agentModeAuthorizationMethod = AgentModeAuthorizationMethod.fromStorage(
                preferences[AGENT_MODE_AUTHORIZATION_METHOD],
                defaultValue = defaultAgentModeAuthorizationMethod(context),
            ),
            language = AppLanguage.fromStorage(preferences[LANGUAGE]),
            themeMode = AppThemeMode.fromStorage(preferences[THEME_MODE]),
            defaultChatModelKey = preferences[DEFAULT_CHAT_MODEL_KEY].orEmpty(),
            defaultTitleModelKey = preferences[DEFAULT_TITLE_MODEL_KEY].orEmpty(),
            defaultNamingModelKey = preferences[DEFAULT_NAMING_MODEL_KEY].orEmpty(),
            unsupportedParallelToolCallProviderKeys = parseStoredStringList(
                preferences[UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS].orEmpty()
            ),
            basicFunctionCallingCompatibilityMode =
                preferences[BASIC_FUNCTION_CALLING_COMPATIBILITY_MODE] ?: false,
            onboardingSeenVersion = preferences[ONBOARDING_SEEN_VERSION] ?: 0,
            onboardingCompletedVersion = preferences[ONBOARDING_COMPLETED_VERSION] ?: 0,
            privacyPolicyAccepted = preferences[PRIVACY_POLICY_ACCEPTED] ?: false,
            lastUpdateCheckAtMillis = preferences[LAST_UPDATE_CHECK_AT_MILLIS] ?: 0L,
        )
    }

    // ── Multi-Provider support ───────────────────────────────────────────────
    val providerConfigs: Flow<List<LlmProviderConfig>> = context.dataStore.data.map { preferences ->
        parseProviderConfigs(preferences[PROVIDER_CONFIGS].orEmpty())
    }

    suspend fun upsertProviderConfig(config: LlmProviderConfig) {
        context.dataStore.edit { prefs ->
            val current = parseProviderConfigs(prefs[PROVIDER_CONFIGS].orEmpty()).toMutableList()
            val existingIndex = current.indexOfFirst { it.id == config.id }
            val updatedConfig = config.copy(updatedAtMillis = System.currentTimeMillis())
            if (existingIndex >= 0) {
                current[existingIndex] = updatedConfig
            } else {
                current.add(updatedConfig)
            }
            prefs[PROVIDER_CONFIGS] = serializeProviderConfigs(current)
        }
    }

    suspend fun removeProviderConfig(id: String) {
        context.dataStore.edit { prefs ->
            val current = parseProviderConfigs(prefs[PROVIDER_CONFIGS].orEmpty())
            val updated = current.filter { it.id != id }
            prefs[PROVIDER_CONFIGS] = serializeProviderConfigs(updated)
        }
    }

    suspend fun setProviderEnabled(
        id: String,
        enabled: Boolean,
    ) {
        context.dataStore.edit { prefs ->
            val current = parseProviderConfigs(prefs[PROVIDER_CONFIGS].orEmpty())
            val updated = current.map { config ->
                if (config.id == id) config.copy(isEnabled = enabled) else config
            }
            prefs[PROVIDER_CONFIGS] = serializeProviderConfigs(updated)

            val fallbackOption = updated.availableModelOptions().firstOrNull()
            if (fallbackOption != null) {
                prefs[PROVIDER] = fallbackOption.providerType.storageValue
                prefs[API_KEY] = fallbackOption.apiKey
                prefs[BASE_URL] = fallbackOption.baseUrl
                prefs[MODEL_ID] = fallbackOption.modelId
            }
        }
    }

    // ── Legacy single-provider methods ───────────────────────────────────────

    suspend fun replaceImportedSettings(
        settings: AppSettings,
        providerConfigs: List<LlmProviderConfig>,
    ) {
        context.dataStore.edit {
            it[PROVIDER] = settings.provider.storageValue
            it[API_KEY] = settings.apiKey
            it[BASE_URL] = settings.baseUrl
            it[MODEL_ID] = settings.modelId
            it[SYSTEM_PROMPT] = settings.systemPrompt
            it[TAVILY_API_KEY] = settings.tavilyApiKey
            it[MINERU_API_TOKEN] = settings.mineruApiToken
            it[LLM_INACTIVITY_RECONNECT_TIMEOUT_SECONDS] =
                normalizeLlmInactivityReconnectTimeoutSeconds(
                    settings.llmInactivityReconnectTimeoutSeconds
                )
            it[KEEP_TASKS_RUNNING_IN_BACKGROUND] = settings.keepTasksRunningInBackground
            it[NOTIFY_ON_TASK_COMPLETION] = settings.notifyOnTaskCompletion
            // Remote voice credentials are device-local secrets. Full-app imports deliberately
            // leave all voice server settings untouched, especially the bearer token.
            val agentLoopPolicy = normalizeAgentLoopPolicy(settings.agentLoopPolicy)
            it[AUTONOMOUS_CONTINUATION_ENABLED] = agentLoopPolicy.autonomousContinuationEnabled
            it[MAX_AUTONOMOUS_CONTINUATION_TURNS] = agentLoopPolicy.maxAutonomousContinuationTurns
            it[AGENT_MODE_AUTHORIZATION_ENABLED] = settings.agentModeAuthorizationEnabled
            it[AGENT_MODE_AUTHORIZATION_METHOD] = settings.agentModeAuthorizationMethod.storageValue
            it[LANGUAGE] = settings.language.storageValue
            it[THEME_MODE] = settings.themeMode.storageValue
            it[DEFAULT_CHAT_MODEL_KEY] = settings.defaultChatModelKey
            it[DEFAULT_TITLE_MODEL_KEY] = settings.defaultTitleModelKey
            it[DEFAULT_NAMING_MODEL_KEY] = settings.defaultNamingModelKey
            it[UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS] =
                serializeStoredStringList(settings.unsupportedParallelToolCallProviderKeys)
            it[BASIC_FUNCTION_CALLING_COMPATIBILITY_MODE] = settings.basicFunctionCallingCompatibilityMode
            it[ONBOARDING_SEEN_VERSION] = settings.onboardingSeenVersion
            it[ONBOARDING_COMPLETED_VERSION] = settings.onboardingCompletedVersion
            it[PRIVACY_POLICY_ACCEPTED] = settings.privacyPolicyAccepted
            it[LAST_UPDATE_CHECK_AT_MILLIS] = settings.lastUpdateCheckAtMillis
            it[PROVIDER_CONFIGS] = serializeProviderConfigs(providerConfigs)
        }
    }

    suspend fun updateApiKey(value: String) {
        context.dataStore.edit { it[API_KEY] = value }
    }

    suspend fun updateBaseUrl(value: String) {
        context.dataStore.edit { it[BASE_URL] = value }
    }

    suspend fun updateModelId(value: String) {
        context.dataStore.edit { it[MODEL_ID] = value }
    }

    suspend fun updateSystemPrompt(value: String) {
        context.dataStore.edit { it[SYSTEM_PROMPT] = value }
    }

    suspend fun updateTavilyApiKey(value: String) {
        context.dataStore.edit { it[TAVILY_API_KEY] = value }
    }

    suspend fun updateLanguage(language: AppLanguage) {
        context.dataStore.edit { it[LANGUAGE] = language.storageValue }
    }

    suspend fun updateThemeMode(themeMode: AppThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = themeMode.storageValue }
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit {
            it[PROVIDER] = settings.provider.storageValue
            it[API_KEY] = settings.apiKey
            it[BASE_URL] = settings.baseUrl
            it[MODEL_ID] = settings.modelId
            it[SYSTEM_PROMPT] = settings.systemPrompt
            it[TAVILY_API_KEY] = settings.tavilyApiKey
            it[MINERU_API_TOKEN] = settings.mineruApiToken
            it[LLM_INACTIVITY_RECONNECT_TIMEOUT_SECONDS] =
                normalizeLlmInactivityReconnectTimeoutSeconds(
                    settings.llmInactivityReconnectTimeoutSeconds
                )
            it[KEEP_TASKS_RUNNING_IN_BACKGROUND] = settings.keepTasksRunningInBackground
            it[NOTIFY_ON_TASK_COMPLETION] = settings.notifyOnTaskCompletion
            it[VOICE_SERVER_BASE_URL] = settings.voiceServerBaseUrl.trim().trimEnd('/')
            it[VOICE_SERVER_TOKEN] = settings.voiceServerToken
            it[VOICE_ID] = settings.voiceId.trim()
            it[VOICE_ENABLED] = settings.voiceEnabled && settings.voiceAuthorizationConfirmed &&
                settings.hasConfiguredVoiceServer()
            it[VOICE_AUTO_READ] = settings.voiceAutoRead
            it[VOICE_AUTHORIZATION_CONFIRMED] = settings.voiceAuthorizationConfirmed
            it[VOICE_SPEED_PERCENT] = normalizeVoiceSpeedPercent(settings.voiceSpeedPercent)
            val agentLoopPolicy = normalizeAgentLoopPolicy(settings.agentLoopPolicy)
            it[AUTONOMOUS_CONTINUATION_ENABLED] = agentLoopPolicy.autonomousContinuationEnabled
            it[MAX_AUTONOMOUS_CONTINUATION_TURNS] = agentLoopPolicy.maxAutonomousContinuationTurns
            it[AGENT_MODE_AUTHORIZATION_ENABLED] = settings.agentModeAuthorizationEnabled
            it[AGENT_MODE_AUTHORIZATION_METHOD] = settings.agentModeAuthorizationMethod.storageValue
            it[LANGUAGE] = settings.language.storageValue
            it[THEME_MODE] = settings.themeMode.storageValue
            it[DEFAULT_CHAT_MODEL_KEY] = settings.defaultChatModelKey
            it[DEFAULT_TITLE_MODEL_KEY] = settings.defaultTitleModelKey
            it[DEFAULT_NAMING_MODEL_KEY] = settings.defaultNamingModelKey
            it[UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS] =
                serializeStoredStringList(settings.unsupportedParallelToolCallProviderKeys)
            it[BASIC_FUNCTION_CALLING_COMPATIBILITY_MODE] = settings.basicFunctionCallingCompatibilityMode
            it[PRIVACY_POLICY_ACCEPTED] = settings.privacyPolicyAccepted
            it[LAST_UPDATE_CHECK_AT_MILLIS] = settings.lastUpdateCheckAtMillis
        }
    }

    suspend fun markParallelToolCallsUnsupported(providerKey: String) {
        val normalizedProviderKey = providerKey.trim()
        if (normalizedProviderKey.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = parseStoredStringList(
                prefs[UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS].orEmpty()
            )
            if (normalizedProviderKey !in current) {
                prefs[UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS] =
                    serializeStoredStringList((current + normalizedProviderKey).takeLast(MaxUnsupportedParallelToolCallKeys))
            }
        }
    }

    suspend fun updatePrivacyPolicyAccepted(accepted: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PRIVACY_POLICY_ACCEPTED] = accepted
        }
    }

    suspend fun updateVoiceSettings(
        serverBaseUrl: String,
        serverToken: String,
        voiceId: String,
        enabled: Boolean,
        autoRead: Boolean,
        authorizationConfirmed: Boolean,
        speedPercent: Int,
    ) {
        val normalizedBaseUrl = serverBaseUrl.trim().trimEnd('/')
        val normalizedVoiceId = voiceId.trim()
        val configured = normalizedBaseUrl.isNotBlank() &&
            serverToken.isNotBlank() &&
            normalizedVoiceId.isNotBlank()
        context.dataStore.edit {
            it[VOICE_SERVER_BASE_URL] = normalizedBaseUrl
            it[VOICE_SERVER_TOKEN] = serverToken
            it[VOICE_ID] = normalizedVoiceId
            it[VOICE_ENABLED] = enabled && authorizationConfirmed && configured
            it[VOICE_AUTO_READ] = autoRead
            it[VOICE_AUTHORIZATION_CONFIRMED] = authorizationConfirmed
            it[VOICE_SPEED_PERCENT] = normalizeVoiceSpeedPercent(speedPercent)
        }
    }

    suspend fun migrateLegacyOfflineVoiceStorage() {
        val migrated = context.dataStore.data.first()[REMOTE_VOICE_MIGRATION_COMPLETE] ?: false
        if (migrated) return

        val legacyDirectories = listOf(
            File(context.filesDir, "offline_voice"),
            File(context.cacheDir, "offline_voice"),
            File(context.filesDir, "voice_models"),
        )
        val cleaned = legacyDirectories.all { directory ->
            !directory.exists() || directory.deleteRecursively()
        }
        if (!cleaned) return

        context.dataStore.edit { preferences ->
            preferences[REMOTE_VOICE_MIGRATION_COMPLETE] = true
            if (!preferences.contains(VOICE_AUTO_READ)) {
                preferences[VOICE_AUTO_READ] = preferences[LEGACY_OFFLINE_VOICE_AUTO_READ] ?: false
            }
            if (!preferences.contains(VOICE_AUTHORIZATION_CONFIRMED)) {
                preferences[VOICE_AUTHORIZATION_CONFIRMED] =
                    preferences[LEGACY_OFFLINE_VOICE_AUTHORIZATION_CONFIRMED] ?: false
            }
            if (!preferences.contains(VOICE_SPEED_PERCENT)) {
                preferences[VOICE_SPEED_PERCENT] = normalizeVoiceSpeedPercent(
                    preferences[LEGACY_OFFLINE_VOICE_SPEED_PERCENT]
                )
            }
        }
    }

    suspend fun updateOnboardingSeenVersion(version: Int) {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_SEEN_VERSION] = version
        }
    }

    suspend fun updateOnboardingCompletedVersion(version: Int) {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_COMPLETED_VERSION] = version
        }
    }

    suspend fun updateLastUpdateCheckAtMillis(value: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_UPDATE_CHECK_AT_MILLIS] = value
        }
    }

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL_ID = stringPreferencesKey("model_id")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TAVILY_API_KEY = stringPreferencesKey("tavily_api_key")
        val MINERU_API_TOKEN = stringPreferencesKey("mineru_api_token")
        val LLM_INACTIVITY_RECONNECT_TIMEOUT_SECONDS =
            intPreferencesKey("llm_inactivity_reconnect_timeout_seconds")
        val KEEP_TASKS_RUNNING_IN_BACKGROUND =
            booleanPreferencesKey("keep_tasks_running_in_background")
        val NOTIFY_ON_TASK_COMPLETION =
            booleanPreferencesKey("notify_on_task_completion")
        val VOICE_SERVER_BASE_URL = stringPreferencesKey("voice_server_base_url")
        val VOICE_SERVER_TOKEN = stringPreferencesKey("voice_server_token")
        val VOICE_ID = stringPreferencesKey("voice_id")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val VOICE_AUTO_READ = booleanPreferencesKey("voice_auto_read")
        val VOICE_AUTHORIZATION_CONFIRMED = booleanPreferencesKey("voice_authorization_confirmed")
        val VOICE_SPEED_PERCENT = intPreferencesKey("voice_speed_percent")
        val REMOTE_VOICE_MIGRATION_COMPLETE = booleanPreferencesKey("remote_voice_migration_complete_v1")
        val LEGACY_OFFLINE_VOICE_AUTO_READ = booleanPreferencesKey("offline_voice_auto_read")
        val LEGACY_OFFLINE_VOICE_AUTHORIZATION_CONFIRMED =
            booleanPreferencesKey("offline_voice_authorization_confirmed")
        val LEGACY_OFFLINE_VOICE_SPEED_PERCENT = intPreferencesKey("offline_voice_speed_percent")
        val AUTONOMOUS_CONTINUATION_ENABLED =
            booleanPreferencesKey("autonomous_continuation_enabled")
        val MAX_AUTONOMOUS_CONTINUATION_TURNS =
            intPreferencesKey("max_autonomous_continuation_turns")
        val AGENT_MODE_AUTHORIZATION_ENABLED =
            booleanPreferencesKey("agent_mode_authorization_enabled")
        val AGENT_MODE_AUTHORIZATION_METHOD =
            stringPreferencesKey("agent_mode_authorization_method")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_CHAT_MODEL_KEY = stringPreferencesKey("default_chat_model_key")
        val DEFAULT_TITLE_MODEL_KEY = stringPreferencesKey("default_title_model_key")
        val DEFAULT_NAMING_MODEL_KEY = stringPreferencesKey("default_naming_model_key")
        val UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS =
            stringPreferencesKey("unsupported_parallel_tool_call_provider_keys")
        val BASIC_FUNCTION_CALLING_COMPATIBILITY_MODE =
            booleanPreferencesKey("basic_function_calling_compatibility_mode")
        val PROVIDER_CONFIGS = stringPreferencesKey("provider_configs")
        val ONBOARDING_SEEN_VERSION = intPreferencesKey("onboarding_seen_version")
        val ONBOARDING_COMPLETED_VERSION = intPreferencesKey("onboarding_completed_version")
        val PRIVACY_POLICY_ACCEPTED = booleanPreferencesKey("privacy_policy_accepted")
        val LAST_UPDATE_CHECK_AT_MILLIS = longPreferencesKey("last_update_check_at_millis")
        const val MaxUnsupportedParallelToolCallKeys = 128
    }
}

internal fun normalizeStoredSystemPrompt(value: String?): String {
    val storedPrompt = value ?: return DefaultSystemPrompt
    return if (storedPrompt.trim() in LegacyDefaultSystemPrompts) {
        DefaultSystemPrompt
    } else {
        storedPrompt
    }
}

private val PreviousDefaultSystemPromptV2 = """
你是 Aether——运行在 Android 设备上的工程师级 AI 智能体，专注于真正完成任务，而非给出建议。默认使用简体中文，除非用户要求切换语言。

【角色与原则】
- 任务驱动：直接完成，不空谈计划、不重复复述问题。
- 诚实操作：不编造文件内容、路径、命令输出、版本号或设备状态；凡涉及本地/网络状态，先用工具确认。
- 精准修改：改动范围最小化，沿用项目已有架构；不重构无关代码，不覆盖用户改动。

【环境与能力】
- Shell：bash 在 Termux 中运行，可能受权限与后台限制影响。
- 工作区：当前会话独立工作区位于 ~/.aether/workspaces/<session-id>，上传文件在 uploads/ 下。
- 图片：附件不自动识别，需看图时对工作区路径调用 analyze_image（>5MB 自动压缩，无需手动处理）。
- 输出文件：用工作区绝对路径，回复中附 file:// 链接。
- MCP 工具：已连接服务器的工具直接出现在工具列表中，按工具名调用即可，无需先列举。
- Agent Skills：可在设置中加载自定义技能扩展能力。
- Agent Mode：需 Shizuku 或 Root 授权，支持虚拟显示与设备控制。

【工作节奏】
- 简单问题直接回答；涉及文件、代码、日志、网页或设备状态时——先检查，再结论。
- 多步骤任务：先一句话说明意图，立即执行，不要先写长计划。
- 报错时：保留原始错误信息，定位根因，给出最小可行修复。
- 危险操作（批量删除、清空目录、重置仓库、安装未知内容）：执行前向用户确认。
- 优先用 read/edit/write/grep/find/ls；只有需要 shell 能力时才用 bash。

【联网与资料】
- 用户给出 URL → 用 fetch_web_url 获取内容后再回答。
- 需要最新信息或不确定事实 → 用 tavily_search（需配置 API Key）。
- 引用外部资料时简要说明来源。

【沟通风格】
- 结论先行，细节按需展开；不空泛鼓励，不说废话。
- 多方案时：标出推荐项，说明取舍。
- 信息不足时：先调查可检查内容；确实无法判断再问一个明确问题。
""".trimIndent()

private val LegacyDefaultSystemPrompts = setOf(
    "You are Aether, a local-first Android agent that can call tools and complete tasks on-device. Use available tools instead of guessing local state.",
    // v1 中文默认提示词，迁移到新版
    "你是 Aether，一个运行在 Android 设备上的本地优先 AI 智能体。默认使用简体中文回答，除非用户明确要求其他语言。\n\n你的核心目标是把用户的任务真正完成，而不是只给建议。遇到本地文件、上传附件、设备状态、网页内容、命令执行结果时，不要凭空猜测；优先使用可用工具读取、搜索、执行或验证。\n\n运行环境：\n- 你在 Android 上工作，shell 命令通过 Termux 执行。\n- 当前会话有独立工作区，路径通常位于 ~/.aether/workspaces/<session-id>。\n- 用户上传的文件会复制到当前会话工作区，通常在 uploads/ 目录下。\n- 如果用户上传了文件，不要假设文件内容。需要查看时使用 read、grep、find、ls、bash 等工具读取。\n- 图片附件不会自动进入视觉模型。需要看图时，对工作区中的图片路径调用 analyze_image。超大图片（>5MB）会在读取前自动压缩缩放，无需手动处理。\n- 当你生成用户需要保存或下载的文件时，使用当前工作区中的绝对路径，并在回复里给出 file:// 链接。\n- 支持多模型提供方（OpenAI / Anthropic / Vertex AI / OpenAI Compatible），用户可在设置中配置。\n- 支持 MCP 服务器（HTTP / stdio）和 Agent Skills 扩展，可通过设置页面管理。\n- Agent Mode 需要 Shizuku 或 Root 授权，支持虚拟显示和设备控制。",
    PreviousDefaultSystemPromptV2,
)

private fun parseStoredStringList(rawValue: String): List<String> {
    if (rawValue.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(rawValue)
        buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }.distinct()
    }.getOrDefault(emptyList())
}

private fun serializeStoredStringList(values: List<String>): String =
    JSONArray().apply {
        values.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .forEach(::put)
    }.toString()

private val ShizukuManagerPackages = listOf(
    "moe.shizuku.privileged.api",
    "moe.shizuku.manager",
)

private fun defaultAgentModeAuthorizationMethod(
    context: Context,
): AgentModeAuthorizationMethod =
    if (isAnyPackageInstalled(context, ShizukuManagerPackages)) {
        AgentModeAuthorizationMethod.Shizuku
    } else {
        AgentModeAuthorizationMethod.Root
    }

private fun isAnyPackageInstalled(
    context: Context,
    packageNames: List<String>,
): Boolean {
    val packageManager = context.packageManager
    return packageNames.any { packageName ->
        runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }
}
