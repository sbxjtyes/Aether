package com.zhousl.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhousl.aether.BuildConfig
import com.zhousl.aether.R
import com.zhousl.aether.data.AetherPrivacyPolicyUrl
import com.zhousl.aether.data.AetherWebsiteUrl
import com.zhousl.aether.data.AgentModeAuthorizationIssue
import com.zhousl.aether.data.AgentModeAuthorizationMethod
import com.zhousl.aether.data.AgentModeAuthorizationState
import com.zhousl.aether.data.AgentModeDisplayState
import com.zhousl.aether.data.AutomaticModelPurpose
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AppThemeMode
import com.zhousl.aether.data.LlmProvider
import com.zhousl.aether.data.LlmProviderConfig
import com.zhousl.aether.data.ProviderModelOption
import com.zhousl.aether.data.RootSetupIssue
import com.zhousl.aether.data.RootSetupState
import com.zhousl.aether.data.availableModelOptions
import com.zhousl.aether.data.availableModels
import com.zhousl.aether.data.enabledModels
import com.zhousl.aether.data.findModelOption
import com.zhousl.aether.data.normalizeLlmInactivityReconnectTimeoutSeconds
import com.zhousl.aether.data.quickActionLabel
import com.zhousl.aether.data.resolveAutomaticModelKey
import com.zhousl.aether.termux.TermuxSetupState
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnPrimary
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherScrim
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Page enum - drives the local in-composable navigation
// -----------------------------------------------------------------------------

private enum class SettingsPage {
    Hub,
    General,
    Providers,
    DefaultModels,
    DefaultChatModel,
    DefaultTitleModel,
    DefaultNamingModel,
    AddProvider,
    EditProvider,
    Personalization,
    WebTools,
    Reliability,
    Skills,
    AddSkill,
    McpServers,
    AddMcpServer,
    EditMcpServer,
    Termux,
    AgentMode,
    RootSetupProgress,
    Developer,
    About,
}

private const val SettingsAutoRefreshIntervalMillis = 5_000L

private fun SettingsPage.depth(): Int = when (this) {
    SettingsPage.Hub -> 0
    SettingsPage.General,
    SettingsPage.Providers,
    SettingsPage.Personalization,
    SettingsPage.WebTools,
    SettingsPage.Reliability,
    SettingsPage.Skills,
    SettingsPage.McpServers,
    SettingsPage.Termux,
    SettingsPage.AgentMode,
    SettingsPage.Developer,
    SettingsPage.About -> 1
    SettingsPage.DefaultModels,
    SettingsPage.AddProvider,
    SettingsPage.EditProvider,
    SettingsPage.AddSkill,
    SettingsPage.AddMcpServer,
    SettingsPage.EditMcpServer,
    SettingsPage.RootSetupProgress -> 2
    SettingsPage.DefaultChatModel,
    SettingsPage.DefaultTitleModel,
    SettingsPage.DefaultNamingModel -> 3
}

private fun SettingsPage.toRootSetupProgressReturnPage(): RootSetupProgressReturnPage =
    when (this) {
        SettingsPage.AgentMode -> RootSetupProgressReturnPage.AgentMode
        else -> RootSetupProgressReturnPage.Termux
    }

private fun RootSetupProgressReturnPage.toSettingsPage(): SettingsPage =
    when (this) {
        RootSetupProgressReturnPage.Termux -> SettingsPage.Termux
        RootSetupProgressReturnPage.AgentMode -> SettingsPage.AgentMode
    }

// -----------------------------------------------------------------------------
// Animation constants
// -----------------------------------------------------------------------------

private const val PageTransitionDuration = 320
private val PageTransitionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)
private val SettingsTopFadeHeight = 40.dp

private fun tr(strings: AetherStrings, english: String, chinese: String): String =
    if (strings.appLanguage == AppLanguage.SimplifiedChinese) chinese else english

private fun settingsTopOverlayBodyGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherBackground.copy(alpha = 0.96f),
        0.18f to AetherBackground.copy(alpha = 0.86f),
        0.42f to AetherBackground.copy(alpha = 0.48f),
        0.72f to AetherBackground.copy(alpha = 0.22f),
        1.0f to AetherBackground.copy(alpha = 0.12f),
    )
)

private fun settingsTopOverlayTailGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherBackground.copy(alpha = 0.12f),
        0.42f to AetherBackground.copy(alpha = 0.05f),
        1.0f to Color.Transparent,
    )
)

// -----------------------------------------------------------------------------
// Root composable - drop-in replacement for the old SettingsScreen in AetherApp
// -----------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    provider: LlmProvider,
    apiKey: String,
    baseUrl: String,
    modelId: String,
    systemPrompt: String,
    tavilyApiKey: String,
    llmInactivityReconnectTimeoutSeconds: Int,
    keepTasksRunningInBackground: Boolean,
    notifyOnTaskCompletion: Boolean,
    agentModeAuthorizationEnabled: Boolean,
    agentModeAuthorizationMethod: AgentModeAuthorizationMethod,
    agentModeAuthorizationState: AgentModeAuthorizationState,
    rootSetupState: RootSetupState,
    rootSetupProgressReturnPage: RootSetupProgressReturnPage?,
    language: AppLanguage,
    themeMode: AppThemeMode,
    defaultChatModelKey: String,
    defaultTitleModelKey: String,
    defaultNamingModelKey: String,
    agentModeDisplayState: AgentModeDisplayState,
    providerConfigs: List<LlmProviderConfig>,
    termuxSetupState: TermuxSetupState,
    installedSkills: List<com.zhousl.aether.data.InstalledSkill>,
    mcpServers: List<com.zhousl.aether.data.McpServerConfig>,
    isFetchingModels: Boolean,
    appUpdate: AppUpdateUiState,
    onSave: (
        LlmProvider,
        String,
        String,
        String,
        String,
        String,
        Int,
        Boolean,
        Boolean,
        Boolean,
        AgentModeAuthorizationMethod,
        AppLanguage,
        AppThemeMode,
        String,
        String,
        String,
    ) -> Unit,
    onUpdateLanguage: (AppLanguage) -> Unit,
    onUpdateThemeMode: (AppThemeMode) -> Unit,
    onUpsertProviderConfig: (LlmProviderConfig) -> Unit,
    onRemoveProviderConfig: (String) -> Unit,
    onSetProviderEnabled: (String, Boolean) -> Unit,
    onFetchModels: (LlmProviderConfig, (List<String>) -> Unit) -> Unit,
    onImportSkillFolder: () -> Unit,
    onImportSkillZip: ((Boolean) -> Unit) -> Unit,
    onInstallSkillUrl: (String, (Boolean) -> Unit) -> Unit,
    onToggleSkillEnabled: (String, Boolean) -> Unit,
    onRemoveSkill: (String) -> Unit,
    onSaveHttpMcpServer: (String?, String, String, String) -> Unit,
    onSaveStdIoMcpServer: (String?, String, String, String, String) -> Unit,
    onValidateHttpMcpServer: (String?, String, String, String, (Boolean, String) -> Unit) -> Unit,
    onValidateStdIoMcpServer: (String?, String, String, String, String, (Boolean, String) -> Unit) -> Unit,
    onToggleMcpServerEnabled: (String, Boolean) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onImportAppData: () -> Unit,
    onExportAppData: () -> Unit,
    onExportLogs: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onRefreshRootSetup: () -> Unit,
    onStartRootSetupFromSettings: (RootSetupProgressReturnPage) -> Unit,
    onDismissRootSetupProgress: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshAgentModeAuthorization: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onReplayFollowUpOnboarding: () -> Unit,
    onStopAgentModeDisplay: () -> Unit,
    onRefreshAgentModeDisplays: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onForceUpdateCheckForTesting: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    // Mutable field values - survive recomposition & config changes
    var systemPromptValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(systemPrompt))
    }
    var tavilyApiKeyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(tavilyApiKey))
    }
    var llmInactivityReconnectTimeoutValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(llmInactivityReconnectTimeoutSeconds.toString()))
    }
    var keepTasksRunningInBackgroundValue by rememberSaveable {
        mutableStateOf(keepTasksRunningInBackground)
    }
    var notifyOnTaskCompletionValue by rememberSaveable {
        mutableStateOf(notifyOnTaskCompletion)
    }
    var agentModeAuthorizationEnabledValue by rememberSaveable {
        mutableStateOf(agentModeAuthorizationEnabled)
    }
    var agentModeAuthorizationMethodValue by rememberSaveable {
        mutableStateOf(agentModeAuthorizationMethod)
    }
    var languageValue by rememberSaveable {
        mutableStateOf(language)
    }
    var themeModeValue by rememberSaveable {
        mutableStateOf(themeMode)
    }
    var defaultChatModelKeyValue by rememberSaveable { mutableStateOf(defaultChatModelKey) }
    var defaultTitleModelKeyValue by rememberSaveable { mutableStateOf(defaultTitleModelKey) }
    var defaultNamingModelKeyValue by rememberSaveable { mutableStateOf(defaultNamingModelKey) }
    val strings = remember(languageValue) { aetherStringsFor(languageValue) }
    val enabledModelOptions = remember(providerConfigs) { providerConfigs.availableModelOptions() }

    // Track which provider is being edited
    var editingProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingMcpServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastObservedRootSetupIssue by rememberSaveable { mutableStateOf(rootSetupState.issue) }

    LaunchedEffect(rootSetupState.issue) {
        if (
            rootSetupState.issue == RootSetupIssue.Ready &&
            lastObservedRootSetupIssue != RootSetupIssue.Ready
        ) {
            agentModeAuthorizationEnabledValue = true
            agentModeAuthorizationMethodValue = AgentModeAuthorizationMethod.Root
        }
        lastObservedRootSetupIssue = rootSetupState.issue
    }

    fun persistAndExit() {
        val compatibilityOption = enabledModelOptions.firstOrNull()
        onSave(
            compatibilityOption?.providerType ?: provider,
            compatibilityOption?.apiKey ?: apiKey,
            compatibilityOption?.baseUrl ?: baseUrl,
            compatibilityOption?.modelId ?: modelId,
            systemPromptValue.text,
            tavilyApiKeyValue.text,
            normalizeLlmInactivityReconnectTimeoutSeconds(
                llmInactivityReconnectTimeoutValue.text.trim().toIntOrNull()
            ),
            keepTasksRunningInBackgroundValue,
            notifyOnTaskCompletionValue,
            agentModeAuthorizationEnabledValue,
            agentModeAuthorizationMethodValue,
            languageValue,
            themeModeValue,
            defaultChatModelKeyValue,
            defaultTitleModelKeyValue,
            defaultNamingModelKeyValue,
        )
        onBack()
    }

    fun persistAndReplayOnboarding() {
        val compatibilityOption = enabledModelOptions.firstOrNull()
        onSave(
            compatibilityOption?.providerType ?: provider,
            compatibilityOption?.apiKey ?: apiKey,
            compatibilityOption?.baseUrl ?: baseUrl,
            compatibilityOption?.modelId ?: modelId,
            systemPromptValue.text,
            tavilyApiKeyValue.text,
            normalizeLlmInactivityReconnectTimeoutSeconds(
                llmInactivityReconnectTimeoutValue.text.trim().toIntOrNull()
            ),
            keepTasksRunningInBackgroundValue,
            notifyOnTaskCompletionValue,
            agentModeAuthorizationEnabledValue,
            agentModeAuthorizationMethodValue,
            languageValue,
            themeModeValue,
            defaultChatModelKeyValue,
            defaultTitleModelKeyValue,
            defaultNamingModelKeyValue,
        )
        onReplayOnboarding()
    }

    fun persistAndReplayFollowUpOnboarding() {
        val compatibilityOption = enabledModelOptions.firstOrNull()
        onSave(
            compatibilityOption?.providerType ?: provider,
            compatibilityOption?.apiKey ?: apiKey,
            compatibilityOption?.baseUrl ?: baseUrl,
            compatibilityOption?.modelId ?: modelId,
            systemPromptValue.text,
            tavilyApiKeyValue.text,
            normalizeLlmInactivityReconnectTimeoutSeconds(
                llmInactivityReconnectTimeoutValue.text.trim().toIntOrNull()
            ),
            keepTasksRunningInBackgroundValue,
            notifyOnTaskCompletionValue,
            agentModeAuthorizationEnabledValue,
            agentModeAuthorizationMethodValue,
            languageValue,
            themeModeValue,
            defaultChatModelKeyValue,
            defaultTitleModelKeyValue,
            defaultNamingModelKeyValue,
        )
        onReplayFollowUpOnboarding()
    }

    // Local page navigation
    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.Hub.name) }
    val page = SettingsPage.valueOf(currentPage)
    var rootSetupReturnPage by rememberSaveable { mutableStateOf(SettingsPage.Termux.name) }

    fun rootSetupReturnPageValue(): SettingsPage =
        runCatching { SettingsPage.valueOf(rootSetupReturnPage) }
            .getOrDefault(SettingsPage.Termux)

    fun openRootSetupProgress(returnPage: SettingsPage) {
        rootSetupReturnPage = returnPage.name
        currentPage = SettingsPage.RootSetupProgress.name
        onStartRootSetupFromSettings(returnPage.toRootSetupProgressReturnPage())
    }

    fun closeRootSetupProgress() {
        onDismissRootSetupProgress()
        currentPage = rootSetupReturnPageValue().name
    }

    fun runRootSetupAgain() {
        onStartRootSetupFromSettings(rootSetupReturnPageValue().toRootSetupProgressReturnPage())
    }

    LaunchedEffect(rootSetupProgressReturnPage) {
        val returnPage = rootSetupProgressReturnPage ?: return@LaunchedEffect
        rootSetupReturnPage = returnPage.toSettingsPage().name
        currentPage = SettingsPage.RootSetupProgress.name
    }

    // Determine parent page for back navigation
    fun parentPage(): SettingsPage = when (page) {
        SettingsPage.DefaultModels -> SettingsPage.Providers
        SettingsPage.DefaultChatModel, SettingsPage.DefaultTitleModel, SettingsPage.DefaultNamingModel -> SettingsPage.DefaultModels
        SettingsPage.AddProvider, SettingsPage.EditProvider -> SettingsPage.Providers
        SettingsPage.AddSkill -> SettingsPage.Skills
        SettingsPage.AddMcpServer, SettingsPage.EditMcpServer -> SettingsPage.McpServers
        SettingsPage.RootSetupProgress -> rootSetupReturnPageValue()
        else -> SettingsPage.Hub
    }

    BackHandler {
        when (page) {
            SettingsPage.Hub -> persistAndExit()
            SettingsPage.RootSetupProgress -> closeRootSetupProgress()
            else -> currentPage = parentPage().name
        }
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val isForward = targetState.depth() > initialState.depth()
            val enterSlide = slideInHorizontally(
                animationSpec = tween(PageTransitionDuration, easing = PageTransitionEasing),
                initialOffsetX = { if (isForward) it / 3 else -it / 3 },
            ) + fadeIn(tween(PageTransitionDuration, easing = PageTransitionEasing))
            val exitSlide = slideOutHorizontally(
                animationSpec = tween(PageTransitionDuration, easing = PageTransitionEasing),
                targetOffsetX = { if (isForward) -it / 3 else it / 3 },
            ) + fadeOut(tween(PageTransitionDuration, easing = PageTransitionEasing))
            enterSlide togetherWith exitSlide
        },
        label = "settings_page_transition",
    ) { targetPage ->
        when (targetPage) {
            SettingsPage.Hub -> SettingsHub(
                strings = strings,
                generalSettingsSummary = strings.generalSettingsSummary(
                    language = languageValue,
                    themeMode = themeModeValue,
                ),
                activeProviderName = providerConfigs.count { it.isEnabled }.let { enabledCount ->
                    when {
                        enabledCount > 1 -> tr(strings, "$enabledCount providers enabled", "已启用 $enabledCount 个 Provider")
                        enabledCount == 1 -> providerConfigs.firstOrNull { it.isEnabled }?.name.orEmpty()
                        enabledModelOptions.isNotEmpty() -> enabledModelOptions.first().fullLabel
                        else -> provider.displayName
                    }
                },
                systemPromptSnippet = systemPromptValue.text.take(60),
                tavilyConfigured = tavilyApiKeyValue.text.isNotBlank(),
                reliabilitySummary = buildString {
                    append(
                        "Reconnect after ${
                            normalizeLlmInactivityReconnectTimeoutSeconds(
                                llmInactivityReconnectTimeoutValue.text.trim().toIntOrNull()
                            )
                        }s"
                    )
                    append(" · ")
                    append(
                        if (keepTasksRunningInBackgroundValue) {
                            "Background runs on"
                        } else {
                            "Background runs off"
                        }
                    )
                },
                termuxReady = termuxSetupState.isReady,
                skillCount = installedSkills.size,
                mcpServerCount = mcpServers.size,
                onReplayOnboarding = ::persistAndReplayOnboarding,
                onNavigate = { currentPage = it.name },
                onBack = ::persistAndExit,
            )

            SettingsPage.General -> GeneralSettingsPageV2(
                strings = strings,
                selectedLanguage = languageValue,
                onLanguageSelected = {
                    languageValue = it
                    onUpdateLanguage(it)
                },
                selectedThemeMode = themeModeValue,
                onThemeModeSelected = {
                    themeModeValue = it
                    onUpdateThemeMode(it)
                },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.Providers -> ProvidersListPage(
                providerConfigs = providerConfigs,
                onSetProviderEnabled = onSetProviderEnabled,
                onOpenDefaultModels = { currentPage = SettingsPage.DefaultModels.name },
                onEdit = { id ->
                    editingProviderId = id
                    currentPage = SettingsPage.EditProvider.name
                },
                onRemove = onRemoveProviderConfig,
                onAddNew = { currentPage = SettingsPage.AddProvider.name },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.DefaultModels -> DefaultModelsPage(
                modelOptions = enabledModelOptions,
                defaultChatModelKey = defaultChatModelKeyValue,
                defaultTitleModelKey = defaultTitleModelKeyValue,
                defaultNamingModelKey = defaultNamingModelKeyValue,
                onOpenDefaultChatModel = { currentPage = SettingsPage.DefaultChatModel.name },
                onOpenDefaultTitleModel = { currentPage = SettingsPage.DefaultTitleModel.name },
                onOpenDefaultNamingModel = { currentPage = SettingsPage.DefaultNamingModel.name },
                onBack = { currentPage = SettingsPage.Providers.name },
            )

            SettingsPage.DefaultChatModel -> ModelSelectionListPage(
                title = tr(strings, "Default Chat Model", "默认聊天模型"),
                subtitle = tr(strings, "Used for new chats and when a conversation has not selected a model yet.", "用于新建聊天，以及当前会话尚未单独选择模型时。"),
                selectedKey = defaultChatModelKeyValue,
                options = enabledModelOptions,
                automaticLabel = enabledModelOptions.findModelOption(
                    enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat)
                )?.fullLabel?.let { tr(strings, "Automatic · $it", "自动选择 · $it") }
                    ?: tr(strings, "Automatic", "自动选择"),
                automaticSubtitle = tr(strings, "Prioritize the SOTA models", "优先选择前沿模型"),
                onSelected = { defaultChatModelKeyValue = it },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultTitleModel -> ModelSelectionListPage(
                title = tr(strings, "Default Title Model", "默认标题模型"),
                subtitle = tr(strings, "Used when Aether automatically generates conversation titles.", "用于 Aether 自动生成会话标题。"),
                selectedKey = defaultTitleModelKeyValue,
                options = enabledModelOptions,
                automaticLabel = enabledModelOptions.findModelOption(
                    enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Title)
                        .ifBlank { enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
                )?.fullLabel?.let { tr(strings, "Automatic · $it", "自动选择 · $it") }
                    ?: tr(strings, "Automatic", "自动选择"),
                automaticSubtitle = tr(strings, "Prioritize the SOTA models", "优先选择前沿模型"),
                onSelected = { defaultTitleModelKeyValue = it },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultNamingModel -> ModelSelectionListPage(
                title = tr(strings, "Default Naming Model", "默认命名模型"),
                subtitle = tr(strings, "Used for automatic naming flows such as Agent Skills and MCP labels.", "用于 Agent Skills、MCP 等自动命名流程。"),
                selectedKey = defaultNamingModelKeyValue,
                options = enabledModelOptions,
                automaticLabel = enabledModelOptions.findModelOption(
                    enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Naming)
                        .ifBlank { enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
                )?.fullLabel?.let { tr(strings, "Automatic · $it", "自动选择 · $it") }
                    ?: tr(strings, "Automatic", "自动选择"),
                automaticSubtitle = tr(strings, "Prioritize the SOTA models", "优先选择前沿模型"),
                onSelected = { defaultNamingModelKeyValue = it },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.AddProvider -> ProviderEditPage(
                existingConfig = null,
                existingProviderIds = providerConfigs.map { it.providerId }.toSet(),
                isFetchingModels = isFetchingModels,
                onSave = { config ->
                    onUpsertProviderConfig(config)
                    currentPage = SettingsPage.Providers.name
                },
                onFetchModels = onFetchModels,
                onBack = { currentPage = SettingsPage.Providers.name },
            )

            SettingsPage.EditProvider -> {
                val configToEdit = providerConfigs.firstOrNull { it.id == editingProviderId }
                ProviderEditPage(
                    existingConfig = configToEdit,
                    existingProviderIds = providerConfigs.map { it.providerId }.toSet(),
                    isFetchingModels = isFetchingModels,
                    onSave = { config ->
                        onUpsertProviderConfig(config)
                        currentPage = SettingsPage.Providers.name
                    },
                    onFetchModels = onFetchModels,
                    onBack = { currentPage = SettingsPage.Providers.name },
                )
            }

            SettingsPage.Personalization -> PersonalizationPage(
                title = strings.personalization,
                systemPromptValue = systemPromptValue,
                onSystemPromptChanged = { systemPromptValue = it },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.WebTools -> WebToolsPage(
                title = strings.webTools,
                tavilyApiKeyValue = tavilyApiKeyValue,
                onTavilyApiKeyChanged = { tavilyApiKeyValue = it },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.Reliability -> ReliabilityPage(
                title = strings.reliability,
                llmInactivityReconnectTimeoutValue = llmInactivityReconnectTimeoutValue,
                onLlmInactivityReconnectTimeoutChanged = { llmInactivityReconnectTimeoutValue = it },
                keepTasksRunningInBackground = keepTasksRunningInBackgroundValue,
                onKeepTasksRunningInBackgroundChanged = { keepTasksRunningInBackgroundValue = it },
                notifyOnTaskCompletion = notifyOnTaskCompletionValue,
                onNotifyOnTaskCompletionChanged = { notifyOnTaskCompletionValue = it },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.Skills -> SkillsListPage(
                title = strings.agentSkills,
                installedSkills = installedSkills,
                onToggleSkillEnabled = onToggleSkillEnabled,
                onRemoveSkill = onRemoveSkill,
                onAddNew = { currentPage = SettingsPage.AddSkill.name },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.AddSkill -> AddSkillPage(
                title = strings.agentSkills,
                onImportSkillFolder = onImportSkillFolder,
                onImportSkillZip = { callback ->
                    onImportSkillZip { success ->
                        callback(success)
                        if (success) currentPage = SettingsPage.Skills.name
                    }
                },
                onInstallSkillUrl = { url, callback ->
                    onInstallSkillUrl(url) { success ->
                        callback(success)
                        if (success) currentPage = SettingsPage.Skills.name
                    }
                },
                onBack = { currentPage = SettingsPage.Skills.name },
            )

            SettingsPage.McpServers -> McpServersListPage(
                title = strings.mcpServers,
                mcpServers = mcpServers,
                onToggleMcpServerEnabled = onToggleMcpServerEnabled,
                onRemoveMcpServer = onRemoveMcpServer,
                onEdit = { serverId ->
                    editingMcpServerId = serverId
                    currentPage = SettingsPage.EditMcpServer.name
                },
                onAddNew = { currentPage = SettingsPage.AddMcpServer.name },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.AddMcpServer -> AddMcpServerPage(
                title = strings.mcpServers,
                existingServer = null,
                onSaveHttpMcpServer = { serverId, name, url, headers ->
                    onSaveHttpMcpServer(serverId, name, url, headers)
                    currentPage = SettingsPage.McpServers.name
                },
                onSaveStdIoMcpServer = { serverId, name, cmd, wd, env ->
                    onSaveStdIoMcpServer(serverId, name, cmd, wd, env)
                    currentPage = SettingsPage.McpServers.name
                },
                onValidateHttpMcpServer = onValidateHttpMcpServer,
                onValidateStdIoMcpServer = onValidateStdIoMcpServer,
                onBack = { currentPage = SettingsPage.McpServers.name },
            )

            SettingsPage.EditMcpServer -> AddMcpServerPage(
                title = strings.mcpServers,
                existingServer = mcpServers.firstOrNull { it.id == editingMcpServerId },
                onSaveHttpMcpServer = { serverId, name, url, headers ->
                    onSaveHttpMcpServer(serverId, name, url, headers)
                    currentPage = SettingsPage.McpServers.name
                },
                onSaveStdIoMcpServer = { serverId, name, cmd, wd, env ->
                    onSaveStdIoMcpServer(serverId, name, cmd, wd, env)
                    currentPage = SettingsPage.McpServers.name
                },
                onValidateHttpMcpServer = onValidateHttpMcpServer,
                onValidateStdIoMcpServer = onValidateStdIoMcpServer,
                onBack = { currentPage = SettingsPage.McpServers.name },
            )

            SettingsPage.Termux -> TermuxSettingsPage(
                title = strings.termux,
                termuxSetupState = termuxSetupState,
                rootSetupState = rootSetupState,
                onRequestTermuxPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefreshTermuxSetup = onRefreshTermuxSetup,
                onRefreshRootSetup = onRefreshRootSetup,
                onConfigureWithRoot = { openRootSetupProgress(SettingsPage.Termux) },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.AgentMode -> AgentModeSettingsPage(
                title = strings.agentMode,
                agentModeAuthorizationEnabled = agentModeAuthorizationEnabledValue,
                agentModeAuthorizationMethod = agentModeAuthorizationMethodValue,
                agentModeAuthorizationState = agentModeAuthorizationState,
                rootSetupState = rootSetupState,
                onAgentModeAuthorizationEnabledChanged = { agentModeAuthorizationEnabledValue = it },
                onAgentModeAuthorizationMethodChanged = { agentModeAuthorizationMethodValue = it },
                agentModeDisplayState = agentModeDisplayState,
                onRequestShizukuPermission = onRequestShizukuPermission,
                onRefreshAgentModeAuthorization = onRefreshAgentModeAuthorization,
                onOpenShizuku = onOpenShizuku,
                onInstallShizuku = onInstallShizuku,
                onRefreshRootSetup = onRefreshRootSetup,
                onConfigureWithRoot = { openRootSetupProgress(SettingsPage.AgentMode) },
                onStopAgentModeDisplay = onStopAgentModeDisplay,
                onRefreshAgentModeDisplays = onRefreshAgentModeDisplays,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.RootSetupProgress -> RootSetupProgressPage(
                rootSetupState = rootSetupState,
                termuxSetupState = termuxSetupState,
                agentModeAuthorizationEnabled = agentModeAuthorizationEnabledValue,
                agentModeAuthorizationMethod = agentModeAuthorizationMethodValue,
                agentModeAuthorizationState = agentModeAuthorizationState,
                onRunRootSetup = ::runRootSetupAgain,
                onBack = ::closeRootSetupProgress,
            )

            SettingsPage.Developer -> DeveloperSettingsPage(
                title = strings.developerSettings,
                onReplayFollowUpOnboarding = ::persistAndReplayFollowUpOnboarding,
                onImportAppData = onImportAppData,
                onExportAppData = onExportAppData,
                onExportLogs = onExportLogs,
                onForceUpdateCheckForTesting = onForceUpdateCheckForTesting,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.About -> AboutPage(
                title = strings.about,
                appUpdate = appUpdate,
                onOpenWebsite = onOpenWebsite,
                onOpenPrivacyPolicy = onOpenPrivacyPolicy,
                onCheckForUpdates = onCheckForUpdates,
                onDownloadAndInstallUpdate = onDownloadAndInstallUpdate,
                onBack = { currentPage = SettingsPage.Hub.name },
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Hub
// -----------------------------------------------------------------------------

@Composable
private fun SettingsHub(
    strings: AetherStrings,
    generalSettingsSummary: String,
    activeProviderName: String,
    systemPromptSnippet: String,
    tavilyConfigured: Boolean,
    reliabilitySummary: String,
    termuxReady: Boolean,
    skillCount: Int,
    mcpServerCount: Int,
    onReplayOnboarding: () -> Unit,
    onNavigate: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    var topBarBodyHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fallbackTopBarBodyHeight = with(density) {
        WindowInsets.statusBars.getTop(this).toDp() + 68.dp
    }
    val topBarBodyHeight = with(density) {
        if (topBarBodyHeightPx > 0) topBarBodyHeightPx.toDp() else fallbackTopBarBodyHeight
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = topBarBodyHeight)
                    .padding(horizontal = 20.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                Spacer(Modifier.height(6.dp))
                SettingsCardGroup {
                    SettingsNavRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = strings.generalSettings,
                        subtitle = generalSettingsSummary.ifBlank { strings.generalSettingsHubHint },
                        onClick = { onNavigate(SettingsPage.General) },
                    )
                }

                Spacer(Modifier.height(16.dp))

            // Configuration card
            SettingsCardGroup {
                SettingsNavRow(
                    icon = Icons.Rounded.Cloud,
                    title = strings.modelProviders,
                    subtitle = activeProviderName,
                    onClick = { onNavigate(SettingsPage.Providers) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Person,
                    title = strings.personalization,
                    subtitle = systemPromptSnippet.ifBlank { strings.customInstructions },
                    onClick = { onNavigate(SettingsPage.Personalization) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Link,
                    title = strings.webTools,
                    subtitle = if (tavilyConfigured) {
                        strings.tavilyConfigured
                    } else {
                        strings.tavilyNotConfigured
                    },
                    onClick = { onNavigate(SettingsPage.WebTools) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Refresh,
                    title = strings.reliability,
                    subtitle = reliabilitySummary,
                    onClick = { onNavigate(SettingsPage.Reliability) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // Extensions card
            SettingsCardGroup {
                SettingsNavRow(
                    icon = Icons.Rounded.Extension,
                    title = strings.agentSkills,
                    subtitle = strings.skillCountSummary(skillCount),
                    onClick = { onNavigate(SettingsPage.Skills) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Code,
                    title = strings.mcpServers,
                    subtitle = strings.serverCountSummary(mcpServerCount),
                    onClick = { onNavigate(SettingsPage.McpServers) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Terminal,
                    title = strings.termux,
                    subtitle = if (termuxReady) strings.connected else strings.setupRequired,
                    onClick = { onNavigate(SettingsPage.Termux) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = LucideIcons.MousePointer2,
                    title = strings.agentMode,
                    subtitle = strings.agentModeSubtitle,
                    onClick = { onNavigate(SettingsPage.AgentMode) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // About card
            SettingsCardGroup {
                SettingsNavRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = strings.getStartedTour,
                    subtitle = strings.getStartedTourSubtitle,
                    onClick = onReplayOnboarding,
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Code,
                    title = strings.developerSettings,
                    subtitle = strings.developerSettingsSubtitle,
                    onClick = { onNavigate(SettingsPage.Developer) },
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsCardGroup {
                SettingsNavRow(
                    icon = Icons.Rounded.Info,
                    title = strings.about,
                    subtitle = "Release ${BuildConfig.VERSION_NAME}",
                    onClick = { onNavigate(SettingsPage.About) },
                )
            }

            Spacer(Modifier.height(32.dp))
            }

            SettingsTopBarOverlay(
                modifier = Modifier.align(Alignment.TopCenter),
                title = strings.settings,
                onBack = onBack,
                onBodyHeightChanged = { topBarBodyHeightPx = it },
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Providers List Page (Multi-Provider)
// -----------------------------------------------------------------------------

@Composable
private fun GeneralSettingsPage(
    strings: AetherStrings,
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    selectedThemeMode: AppThemeMode,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(title = strings.generalSettings, onBack = onBack) {
        SettingsCardGroup {
            Text(
                text = strings.language,
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = strings.languageDescription,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppLanguage.entries.forEach { option ->
                    SettingsChoiceRow(
                        title = strings.languageDisplayName(option),
                        subtitle = if (option == AppLanguage.English) {
                            "English interface"
                        } else {
                            "Simplified Chinese interface"
                        },
                        selected = option == selectedLanguage,
                        onClick = { onLanguageSelected(option) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Text(
                text = strings.theme,
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = strings.themeDescription,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppThemeMode.entries.forEach { option ->
                    SettingsChoiceRow(
                        title = strings.themeDisplayName(option),
                        subtitle = if (option == AppThemeMode.Light) {
                            strings.lightThemeSubtitle
                        } else {
                            strings.darkThemeSubtitle
                        },
                        selected = option == selectedThemeMode,
                        onClick = { onThemeModeSelected(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsPageV2(
    strings: AetherStrings,
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    selectedThemeMode: AppThemeMode,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(title = strings.generalSettings, onBack = onBack) {
        SettingsCardGroup {
            SelectionDropdownField(
                label = strings.language,
                supportingText = strings.languageDescription,
                selectedLabel = strings.languageDisplayName(selectedLanguage),
                options = AppLanguage.entries.map { option ->
                    SelectionOption(
                        key = option.storageValue,
                        title = strings.languageDisplayName(option),
                        subtitle = if (option == AppLanguage.English) {
                            "English interface"
                        } else {
                            "Simplified Chinese interface"
                        },
                        selected = option == selectedLanguage,
                        onClick = { onLanguageSelected(option) },
                    )
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.theme,
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                )
                ThemeModeToggle(
                    isDark = selectedThemeMode == AppThemeMode.Dark,
                    onToggle = {
                        onThemeModeSelected(
                            if (selectedThemeMode == AppThemeMode.Dark) {
                                AppThemeMode.Light
                            } else {
                                AppThemeMode.Dark
                            }
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ProvidersListPage(
    providerConfigs: List<LlmProviderConfig>,
    onSetProviderEnabled: (String, Boolean) -> Unit,
    onOpenDefaultModels: () -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    SubPageScaffold(
        title = strings.modelProviders,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        onTrailingAction = onAddNew,
    ) {
        if (providerConfigs.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tr(strings, "No providers configured", "未配置模型提供方"),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr(strings, "Add a provider to connect to an LLM API.", "添加一个模型提供方以连接 LLM API。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = tr(strings, "Add Provider", "添加 Provider"),
                        onClick = onAddNew,
                    )
                }
            }
        } else {
            providerConfigs.forEach { config ->
                ProviderCard(
                    config = config,
                    onEnabledChange = { enabled -> onSetProviderEnabled(config.id, enabled) },
                    onEdit = { onEdit(config.id) },
                    onRemove = { onRemove(config.id) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        SettingsCardGroup {
            SettingsNavRow(
                icon = Icons.Rounded.AutoAwesome,
                title = tr(strings, "Default Models", "默认模型"),
                subtitle = tr(strings, "Choose dedicated defaults for chat, titles, and naming.", "为聊天、标题和命名分别设置默认模型。"),
                onClick = onOpenDefaultModels,
            )
        }
    }
}

@Composable
private fun DefaultModelsPage(
    modelOptions: List<ProviderModelOption>,
    defaultChatModelKey: String,
    defaultTitleModelKey: String,
    defaultNamingModelKey: String,
    onOpenDefaultChatModel: () -> Unit,
    onOpenDefaultTitleModel: () -> Unit,
    onOpenDefaultNamingModel: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val automaticChatLabel = modelOptions.findModelOption(
        modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat)
    )?.fullLabel
    val automaticTitleLabel = modelOptions.findModelOption(
        modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Title)
            .ifBlank { modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
    )?.fullLabel
    val automaticNamingLabel = modelOptions.findModelOption(
        modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Naming)
            .ifBlank { modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
    )?.fullLabel
    SubPageScaffold(
        title = tr(strings, "Default Models", "默认模型"),
        onBack = onBack,
    ) {
        SettingsCardGroup {
            SettingsNavRow(
                icon = Icons.Rounded.AutoAwesome,
                title = tr(strings, "Default Chat Model", "默认聊天模型"),
                subtitle = if (defaultChatModelKey.isBlank()) {
                    automaticChatLabel?.let { tr(strings, "Automatic · $it", "自动选择 · $it") }
                        ?: tr(strings, "Automatic", "自动选择")
                } else {
                    modelOptions.findModelOption(defaultChatModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultChatModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Edit,
                title = tr(strings, "Default Title Model", "默认标题模型"),
                subtitle = if (defaultTitleModelKey.isBlank()) {
                    automaticTitleLabel?.let { tr(strings, "Automatic · $it", "自动选择 · $it") }
                        ?: tr(strings, "Automatic", "自动选择")
                } else {
                    modelOptions.findModelOption(defaultTitleModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultTitleModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Person,
                title = tr(strings, "Default Naming Model", "默认命名模型"),
                subtitle = if (defaultNamingModelKey.isBlank()) {
                    automaticNamingLabel?.let { tr(strings, "Automatic · $it", "自动选择 · $it") }
                        ?: tr(strings, "Automatic", "自动选择")
                } else {
                    modelOptions.findModelOption(defaultNamingModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultNamingModel,
            )
        }
    }
}

@Composable
private fun ProviderCard(
    config: LlmProviderConfig,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val availableModels = config.availableModels()
    val enabledModels = config.enabledModels()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = config.isEnabled,
            onCheckedChange = { enabled -> onEnabledChange(enabled) },
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${config.providerType.displayName} · ${config.providerId}",
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tr(
                    strings,
                    "${enabledModels.size} of ${availableModels.size} models enabled",
                    "已启用 ${enabledModels.size}/${availableModels.size} 个模型",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onEdit) {
            Icon(
                Icons.Rounded.Edit,
                contentDescription = tr(strings, "Edit", "编辑"),
                tint = AetherOnSurfaceVariant,
            )
        }

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = tr(strings, "Remove", "移除"),
                tint = Color(0xFFD25757),
            )
        }
    }
}

@Composable
private fun ModelSelectionListPage(
    title: String,
    subtitle: String,
    selectedKey: String,
    options: List<ProviderModelOption>,
    automaticLabel: String,
    automaticSubtitle: String,
    onSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val selectedOption = options.findModelOption(selectedKey)

    SubPageScaffold(
        title = title,
        onBack = onBack,
    ) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (options.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = tr(strings, "No enabled models available", "没有可用的已启用模型"),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Text(
                        text = tr(strings, "Enable at least one provider model first, then return here to choose a default.", "请先启用至少一个 Provider 模型，然后再回来选择默认模型。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
            return@SubPageScaffold
        }

        SettingsCardGroup {
            ModelSelectionListRow(
                title = automaticLabel,
                subtitle = automaticSubtitle,
                selected = selectedOption == null,
                onClick = { onSelected("") },
            )
            options.forEach { option ->
                CardDivider()
                ModelSelectionListRow(
                    title = option.fullLabel,
                    subtitle = option.providerName,
                    selected = option.key == selectedOption?.key,
                    onClick = { onSelected(option.key) },
                )
            }
        }
    }
}

@Composable
private fun ModelSelectionListRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AetherBackground.copy(alpha = 0.9f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AetherOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(14.dp))
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = if (selected) AetherPrimary else Color.Transparent,
            modifier = Modifier.size(22.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Provider Edit Page (Add/Edit)
// -----------------------------------------------------------------------------

@Composable
private fun ProviderEditPage(
    existingConfig: LlmProviderConfig?,
    existingProviderIds: Set<String>,
    isFetchingModels: Boolean,
    onSave: (LlmProviderConfig) -> Unit,
    onFetchModels: (LlmProviderConfig, (List<String>) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val isNew = existingConfig == null
    val formState = rememberProviderFormState(existingConfig)

    SubPageScaffold(
        title = if (isNew) tr(strings, "Add Provider", "添加 Provider") else tr(strings, "Edit Provider", "编辑 Provider"),
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        trailingEnabled = formState.isValid(existingProviderIds),
        onTrailingAction = {
            onSave(formState.buildConfig())
            onBack()
        },
    ) {
        ProviderConfigurationForm(
            state = formState,
            existingProviderIds = existingProviderIds,
            isFetchingModels = isFetchingModels,
            onFetchModels = onFetchModels,
        )
    }
}
// -----------------------------------------------------------------------------
// Personalization sub-page
// -----------------------------------------------------------------------------

@Composable
private fun PersonalizationPage(
    title: String,
    systemPromptValue: TextFieldValue,
    onSystemPromptChanged: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        onTrailingAction = onBack,
    ) {
        SettingsCardGroup {
            ChatGptTextField(
                label = strings.customInstructions,
                value = systemPromptValue,
                onValueChange = onSystemPromptChanged,
                minLines = 8,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = tr(
                strings,
                "这是 Aether 在每次对话中都会使用的系统提示词，不会影响工具能力。",
                "This is the system prompt Aether uses in every conversation. It doesn't affect tool capabilities.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Skills List Page (Refactored)
// -----------------------------------------------------------------------------

@Composable
private fun ReliabilityPage(
    title: String,
    llmInactivityReconnectTimeoutValue: TextFieldValue,
    onLlmInactivityReconnectTimeoutChanged: (TextFieldValue) -> Unit,
    keepTasksRunningInBackground: Boolean,
    onKeepTasksRunningInBackgroundChanged: (Boolean) -> Unit,
    notifyOnTaskCompletion: Boolean,
    onNotifyOnTaskCompletionChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        onTrailingAction = onBack,
    ) {
        Text(
            text = tr(strings, "Multitasking", "后台运行"),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        SettingsCardGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsToggleRow(
                    title = tr(strings, "Keep tasks running in background", "保持任务在后台运行"),
                    subtitle = tr(strings, "Uses an Android foreground service so active chats can keep working after you leave Aether.", "使用 Android 前台服务，让活跃对话在离开 Aether 后继续工作。"),
                    checked = keepTasksRunningInBackground,
                    onCheckedChange = onKeepTasksRunningInBackgroundChanged,
                )
                Spacer(Modifier.height(4.dp))
                SettingsToggleRow(
                    title = tr(strings, "Notify when background tasks finish", "后台任务结束时通知"),
                    subtitle = tr(strings, "Shows a completion alert when a run ends while Aether is not on screen.", "当 Aether 不在前台且任务结束时显示完成提醒。"),
                    checked = notifyOnTaskCompletion,
                    onCheckedChange = onNotifyOnTaskCompletionChanged,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = tr(strings, "Reconnect", "重连"),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        SettingsCardGroup {
            ChatGptTextField(
                label = tr(strings, "Reconnect after idle seconds", "空闲多少秒后重连"),
                value = llmInactivityReconnectTimeoutValue,
                onValueChange = {
                    val digitsOnly = it.text.filter(Char::isDigit)
                    onLlmInactivityReconnectTimeoutChanged(
                        it.copy(
                            text = digitsOnly,
                            selection = androidx.compose.ui.text.TextRange(digitsOnly.length),
                        )
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = tr(strings, "If a request produces no response activity at all for this many seconds, Aether cancels that attempt and reconnects with backoff. Range: 30-3600 seconds.", "如果请求在这段时间内完全没有响应活动，Aether 会取消本次尝试并退避重连。范围：30-3600 秒。"),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun WebToolsPage(
    title: String,
    tavilyApiKeyValue: TextFieldValue,
    onTavilyApiKeyChanged: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        onTrailingAction = onBack,
    ) {
        SettingsCardGroup {
            ChatGptTextField(
                label = tr(strings, "Tavily API Key", "Tavily API 密钥"),
                value = tavilyApiKeyValue,
                onValueChange = onTavilyApiKeyChanged,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = tr(strings, "fetch_web_url works without extra setup and converts pages to Markdown on-device. tavily_search uses this API key for public web search.", "fetch_web_url 无需额外设置，会在设备上把网页转换为 Markdown。tavily_search 使用此 API 密钥进行公开网页搜索。"),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun SkillsListPage(
    title: String,
    installedSkills: List<com.zhousl.aether.data.InstalledSkill>,
    onToggleSkillEnabled: (String, Boolean) -> Unit,
    onRemoveSkill: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        onTrailingAction = onAddNew,
    ) {
        Text(
            text = tr(strings, "Manage installed skills and keep only the bundles you want Aether to use in chat.", "管理已安装技能，只保留希望 Aether 在对话中使用的技能包。"),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        if (installedSkills.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tr(strings, "No skills installed", "未安装技能"),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr(strings, "Import skills from a folder, zip, or remote URL.", "从文件夹、Zip 或远程 URL 导入技能。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = tr(strings, "Add Skill", "添加技能"),
                        onClick = onAddNew,
                    )
                }
            }
        } else {
            installedSkills.forEach { skill ->
                SkillCard(
                    skill = skill,
                    onToggleEnabled = { enabled -> onToggleSkillEnabled(skill.id, enabled) },
                    onRemove = { onRemoveSkill(skill.id) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: com.zhousl.aether.data.InstalledSkill,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val strings = rememberAetherStrings()
    var expanded by rememberSaveable(skill.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .animateContentSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                if (skill.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ActionPreviewPill(label = skill.quickActionLabel())
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (expanded) Icons.Rounded.ArrowDropDown else Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = if (expanded) tr(strings, "Collapse", "收起") else tr(strings, "Expand", "展开"),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = tr(strings, "Remove", "移除"),
                    tint = Color(0xFFD25757),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsToggleRow(
            title = "",
            subtitle = "",
            checked = skill.isEnabled,
            onCheckedChange = onToggleEnabled,
        )
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            Spacer(Modifier.height(14.dp))
            DetailLine(tr(strings, "Skill ID", "技能 ID"), skill.id)
            DetailLine(tr(strings, "Files", "文件"), "${skill.resourceEntries.size}")
            DetailLine(tr(strings, "Allowed tools", "允许的工具"), skill.allowedTools.ifEmpty { listOf(tr(strings, "Any", "任意")) }.joinToString(", "))
            if (skill.compatibility.isNotBlank()) {
                DetailLine(tr(strings, "Compatibility", "兼容性"), skill.compatibility)
            }
            if (skill.source.label.isNotBlank()) {
                DetailLine(tr(strings, "Source", "来源"), skill.source.label)
            }
            DetailLine(tr(strings, "Path", "路径"), skill.skillRootPath)
        }
    }
}

// -----------------------------------------------------------------------------
// Add Skill Page
// -----------------------------------------------------------------------------

@Composable
private fun AddSkillPage(
    title: String,
    onImportSkillFolder: () -> Unit,
    onImportSkillZip: ((Boolean) -> Unit) -> Unit,
    onInstallSkillUrl: (String, (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var skillUrlValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var isInstalling by remember { mutableStateOf(false) }
    val strings = rememberAetherStrings()

    val tabOptions = listOf(
        tr(strings, "Folder", "文件夹"),
        "Zip",
        "URL",
    )

    SubPageScaffold(title = title, onBack = onBack) {
        Text(
            text = tr(strings, "Import Agent Skills from a local folder, zip file, or remote URL.", "从本地文件夹、Zip 文件或远程 URL 导入 Agent 技能。"),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        // Segmented button row
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tabOptions.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabOptions.size),
                    onClick = { selectedTab = index },
                    selected = selectedTab == index,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AetherPrimary,
                        activeContentColor = Color.White,
                        inactiveContainerColor = AetherSurfaceHigh,
                        inactiveContentColor = AetherOnSurface,
                    ),
                ) {
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        when (selectedTab) {
            0 -> {
                // Folder import
                SettingsCardGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = AetherOnSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tr(strings, "Select a folder containing SKILL.md", "选择包含 SKILL.md 的文件夹"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        SettingsActionButton(
                            label = tr(strings, "Choose Folder", "选择文件夹"),
                            onClick = {
                                onImportSkillFolder()
                                // Will navigate back via callback on success
                            },
                        )
                    }
                }
            }

            1 -> {
                // Zip import
                SettingsCardGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = AetherOnSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tr(strings, "Select a .zip file containing SKILL.md", "选择包含 SKILL.md 的 .zip 文件"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        SettingsActionButton(
                            label = tr(strings, "Choose Zip", "选择 Zip"),
                            onClick = {
                                onImportSkillZip {}
                            },
                        )
                    }
                }
            }

            2 -> {
                // URL import
                SettingsCardGroup {
                    ChatGptTextField(
                        label = tr(strings, "Remote skill URL", "远程技能 URL"),
                        value = skillUrlValue,
                        onValueChange = { skillUrlValue = it },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = tr(strings, "GitHub repo/tree URLs and direct zip links are supported.", "支持 GitHub 仓库/目录 URL 和直接 Zip 链接。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Spacer(Modifier.height(16.dp))

                if (isInstalling) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = AetherPrimary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(tr(strings, "Installing...", "安装中..."), color = AetherOnSurfaceVariant)
                    }
                } else {
                    SettingsActionButton(
                        label = tr(strings, "Install from URL", "从 URL 安装"),
                        onClick = {
                            if (skillUrlValue.text.isNotBlank()) {
                                isInstalling = true
                                onInstallSkillUrl(skillUrlValue.text) { success ->
                                    isInstalling = false
                                    if (success) {
                                        skillUrlValue = TextFieldValue("")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// MCP Servers List Page (Refactored)
// -----------------------------------------------------------------------------

@Composable
private fun McpServersListPage(
    title: String,
    mcpServers: List<com.zhousl.aether.data.McpServerConfig>,
    onToggleMcpServerEnabled: (String, Boolean) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        onTrailingAction = onAddNew,
    ) {
        Text(
            text = tr(strings, "Manage MCP servers, inspect each transport config, and keep only the connections you want active.", "管理 MCP 服务器，检查传输配置，并只保留需要启用的连接。"),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        if (mcpServers.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tr(strings, "No MCP servers", "没有 MCP 服务器"),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr(strings, "Add HTTP or stdio servers to extend capabilities.", "添加 HTTP 或 stdio 服务器来扩展能力。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = tr(strings, "Add Server", "添加服务器"),
                        onClick = onAddNew,
                    )
                }
            }
        } else {
            mcpServers.forEach { server ->
                McpServerCard(
                    server = server,
                    onToggleEnabled = { enabled -> onToggleMcpServerEnabled(server.id, enabled) },
                    onEdit = { onEdit(server.id) },
                    onRemove = { onRemoveMcpServer(server.id) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun McpServerCard(
    server: com.zhousl.aether.data.McpServerConfig,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val strings = rememberAetherStrings()
    var expanded by rememberSaveable(server.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .animateContentSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = server.transport.transportType.storageValue.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ActionPreviewPill(label = server.quickActionLabel())
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (expanded) Icons.Rounded.ArrowDropDown else Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = if (expanded) tr(strings, "Collapse", "收起") else tr(strings, "Expand", "展开"),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = tr(strings, "Edit", "编辑"),
                    tint = AetherOnSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = tr(strings, "Remove", "移除"),
                    tint = Color(0xFFD25757),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsToggleRow(
            title = "",
            subtitle = "",
            checked = server.isEnabled,
            onCheckedChange = onToggleEnabled,
        )
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            Spacer(Modifier.height(14.dp))
            DetailLine(tr(strings, "Server ID", "服务器 ID"), server.id)
            DetailLine(tr(strings, "Quick action", "快捷操作"), server.quickActionLabel())
            DetailLine(tr(strings, "Transport", "传输方式"), server.transport.transportType.storageValue.uppercase())
            when (val transport = server.transport) {
                is com.zhousl.aether.data.McpTransportConfig.StreamableHttp -> {
                    DetailLine("URL", transport.url)
                    DetailLine(tr(strings, "Headers", "请求头"), transport.headers.size.toString())
                }

                is com.zhousl.aether.data.McpTransportConfig.StdIo -> {
                    DetailLine(tr(strings, "Command", "命令"), transport.command)
                    if (transport.workingDirectory.isNotBlank()) {
                        DetailLine(tr(strings, "Working dir", "工作目录"), transport.workingDirectory)
                    }
                    DetailLine(tr(strings, "Environment", "环境变量"), transport.environment.size.toString())
                }
            }
            DetailLine(tr(strings, "Connect timeout", "连接超时"), "${server.connectTimeoutMillis} ms")
            DetailLine(tr(strings, "Request timeout", "请求超时"), "${server.requestTimeoutMillis} ms")
        }
    }
}

// -----------------------------------------------------------------------------
// Add MCP Server Page
// -----------------------------------------------------------------------------

@Composable
private fun AddMcpServerPage(
    title: String,
    existingServer: com.zhousl.aether.data.McpServerConfig?,
    onSaveHttpMcpServer: (String?, String, String, String) -> Unit,
    onSaveStdIoMcpServer: (String?, String, String, String, String) -> Unit,
    onValidateHttpMcpServer: (String?, String, String, String, (Boolean, String) -> Unit) -> Unit,
    onValidateStdIoMcpServer: (String?, String, String, String, String, (Boolean, String) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val isEditing = existingServer != null
    val existingHttpTransport = existingServer?.transport as? com.zhousl.aether.data.McpTransportConfig.StreamableHttp
    val existingStdIoTransport = existingServer?.transport as? com.zhousl.aether.data.McpTransportConfig.StdIo
    var selectedTab by rememberSaveable(existingServer?.id) {
        mutableIntStateOf(if (existingStdIoTransport != null) 1 else 0)
    }

    var httpServerNameValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingServer?.displayName.orEmpty()))
    }
    var httpServerUrlValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingHttpTransport?.url.orEmpty()))
    }
    var httpHeadersValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                existingHttpTransport?.headers
                    ?.joinToString("\n") { header -> "${header.key}=${header.value}" }
                    .orEmpty()
            )
        )
    }

    var stdioServerNameValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingServer?.displayName.orEmpty()))
    }
    var stdioCommandValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingStdIoTransport?.command.orEmpty()))
    }
    var stdioWorkingDirectoryValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingStdIoTransport?.workingDirectory.orEmpty()))
    }
    var stdioEnvValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                existingStdIoTransport?.environment
                    ?.joinToString("\n") { env -> "${env.key}=${env.value}" }
                    .orEmpty()
            )
        )
    }

    var isValidating by rememberSaveable(existingServer?.id) { mutableStateOf(false) }
    var validationMessage by rememberSaveable(existingServer?.id) { mutableStateOf("") }
    var validationSucceeded by rememberSaveable(existingServer?.id) { mutableStateOf<Boolean?>(null) }
    val tabOptions = listOf("HTTP", tr(strings, "Stdio", "标准输入输出"))

    fun resetValidation() {
        validationMessage = ""
        validationSucceeded = null
    }

    fun finishValidation(
        success: Boolean,
        message: String,
    ) {
        isValidating = false
        validationSucceeded = success
        validationMessage = message
    }

    SubPageScaffold(title = title, onBack = onBack) {
        Text(
            text = if (isEditing) {
                tr(strings, "Update the transport config and quick action source for this MCP server.", "更新此 MCP 服务器的传输配置和快捷操作来源。")
            } else {
                tr(strings, "Add an HTTP server for remote APIs or a stdio server for local Termux processes.", "添加用于远程 API 的 HTTP 服务器，或用于本地 Termux 进程的 stdio 服务器。")
            },
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        // Segmented button row
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tabOptions.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabOptions.size),
                    onClick = {
                        selectedTab = index
                        resetValidation()
                    },
                    selected = selectedTab == index,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AetherPrimary,
                        activeContentColor = Color.White,
                        inactiveContainerColor = AetherSurfaceHigh,
                        inactiveContentColor = AetherOnSurface,
                    ),
                ) {
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        when (selectedTab) {
            0 -> {
                // HTTP server
                SettingsCardGroup {
                    ChatGptTextField(tr(strings, "Server name", "服务器名称"), httpServerNameValue) {
                        httpServerNameValue = it
                        resetValidation()
                    }
                    CardDivider()
                    ChatGptTextField(tr(strings, "Server URL", "服务器 URL"), httpServerUrlValue) {
                        httpServerUrlValue = it
                        resetValidation()
                    }
                    CardDivider()
                    ChatGptTextField(tr(strings, "Headers", "请求头"), httpHeadersValue, minLines = 2) {
                        httpHeadersValue = it
                        resetValidation()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(strings, "Optional headers, one KEY=VALUE per line.", "可选请求头，每行一个 KEY=VALUE。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                McpValidationStatus(
                    isValidating = isValidating,
                    message = validationMessage,
                    succeeded = validationSucceeded,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = if (isValidating) tr(strings, "Checking MCP config...", "正在检测 MCP 配置...") else tr(strings, "Validate configuration", "检测配置有效性"),
                    onClick = {
                        if (!isValidating) {
                            isValidating = true
                            validationMessage = ""
                            validationSucceeded = null
                            onValidateHttpMcpServer(
                                existingServer?.id,
                                httpServerNameValue.text,
                                httpServerUrlValue.text,
                                httpHeadersValue.text,
                                ::finishValidation,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating,
                )
                Spacer(Modifier.height(10.dp))
                SettingsActionButton(
                    label = if (isEditing) tr(strings, "Save HTTP Server", "保存 HTTP 服务器") else tr(strings, "Add HTTP Server", "添加 HTTP 服务器"),
                    onClick = {
                        if (httpServerNameValue.text.isNotBlank() && httpServerUrlValue.text.isNotBlank()) {
                            onSaveHttpMcpServer(
                                existingServer?.id,
                                httpServerNameValue.text,
                                httpServerUrlValue.text,
                                httpHeadersValue.text,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            1 -> {
                // Stdio server
                SettingsCardGroup {
                    ChatGptTextField(tr(strings, "Server name", "服务器名称"), stdioServerNameValue) {
                        stdioServerNameValue = it
                        resetValidation()
                    }
                    CardDivider()
                    ChatGptTextField(tr(strings, "Command", "命令"), stdioCommandValue, minLines = 2) {
                        stdioCommandValue = it
                        resetValidation()
                    }
                    CardDivider()
                    ChatGptTextField(tr(strings, "Working directory", "工作目录"), stdioWorkingDirectoryValue) {
                        stdioWorkingDirectoryValue = it
                        resetValidation()
                    }
                    CardDivider()
                    ChatGptTextField(tr(strings, "Environment", "环境变量"), stdioEnvValue, minLines = 2) {
                        stdioEnvValue = it
                        resetValidation()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(strings, "Optional environment variables, one KEY=VALUE per line.", "可选环境变量，每行一个 KEY=VALUE。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                McpValidationStatus(
                    isValidating = isValidating,
                    message = validationMessage,
                    succeeded = validationSucceeded,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = if (isValidating) tr(strings, "Checking MCP config...", "正在检测 MCP 配置...") else tr(strings, "Validate configuration", "检测配置有效性"),
                    onClick = {
                        if (!isValidating) {
                            isValidating = true
                            validationMessage = ""
                            validationSucceeded = null
                            onValidateStdIoMcpServer(
                                existingServer?.id,
                                stdioServerNameValue.text,
                                stdioCommandValue.text,
                                stdioWorkingDirectoryValue.text,
                                stdioEnvValue.text,
                                ::finishValidation,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating,
                )
                Spacer(Modifier.height(10.dp))
                SettingsActionButton(
                    label = if (isEditing) tr(strings, "Save Stdio Server", "保存 Stdio 服务器") else tr(strings, "Add Stdio Server", "添加 Stdio 服务器"),
                    onClick = {
                        if (stdioServerNameValue.text.isNotBlank() && stdioCommandValue.text.isNotBlank()) {
                            onSaveStdIoMcpServer(
                                existingServer?.id,
                                stdioServerNameValue.text,
                                stdioCommandValue.text,
                                stdioWorkingDirectoryValue.text,
                                stdioEnvValue.text,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun McpValidationStatus(
    isValidating: Boolean,
    message: String,
    succeeded: Boolean?,
) {
    AnimatedVisibility(visible = isValidating || message.isNotBlank()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when (succeeded) {
                        true -> Color(0xFF2E7D32).copy(alpha = 0.12f)
                        false -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                        null -> AetherSurfaceHigh
                    }
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isValidating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AetherPrimary,
                )
            } else {
                Icon(
                    imageVector = if (succeeded == true) Icons.Rounded.Check else Icons.Rounded.Info,
                    contentDescription = null,
                    tint = if (succeeded == true) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (isValidating) "正在解析并连接 MCP 服务..." else message,
                style = MaterialTheme.typography.bodySmall,
                color = when (succeeded) {
                    true -> Color(0xFF2E7D32)
                    false -> MaterialTheme.colorScheme.error
                    null -> AetherOnSurfaceVariant
                },
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Termux sub-page
// -----------------------------------------------------------------------------

@Composable
private fun TermuxSettingsPage(
    title: String,
    termuxSetupState: TermuxSetupState,
    rootSetupState: RootSetupState,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onRefreshRootSetup: () -> Unit,
    onConfigureWithRoot: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    var showAlreadyConfiguredDialog by rememberSaveable { mutableStateOf(false) }

    fun requestRootSetup() {
        if (termuxSetupState.isReady && !rootSetupState.isRunning) {
            showAlreadyConfiguredDialog = true
        } else {
            onConfigureWithRoot()
        }
    }

    if (showAlreadyConfiguredDialog) {
        RootSetupAlreadyConfiguredDialog(
            title = tr(strings, "Termux is already configured", "Termux 已配置完成"),
            body = tr(
                strings,
                "Termux command access is already working. You do not need to run Root automatic setup again.",
                "Termux 命令访问已经正常，不需要再次执行 Root 自动配置。",
            ),
            onDismiss = { showAlreadyConfiguredDialog = false },
            onContinue = {
                showAlreadyConfiguredDialog = false
                onConfigureWithRoot()
            },
        )
    }

    LaunchedEffect(Unit) {
        onRefreshRootSetup()
        onRefreshTermuxSetup()
        while (true) {
            delay(SettingsAutoRefreshIntervalMillis)
            onRefreshTermuxSetup()
        }
    }
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Refresh,
        onTrailingAction = onRefreshTermuxSetup,
    ) {
        Text(
            text = tr(strings, "Aether runs bash through Termux. Finish setup here so tool calls work for every user without manual adb steps.", "Aether 通过 Termux 运行 bash。在这里完成设置后，每个用户都能使用工具调用，无需手动 adb 步骤。"),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            RootSetupSettingsSection(
                title = tr(strings, "Root automatic setup", "Root 自动配置"),
                rootSetupState = rootSetupState,
                body = rootSetupSettingsBody(rootSetupState, strings),
                onConfigureWithRoot = ::requestRootSetup,
            )
        }

        Spacer(Modifier.height(16.dp))

        if (termuxSetupState.isReady) {
            SettingsCardGroup {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(tr(strings, "Termux is connected", "Termux 已连接"), style = MaterialTheme.typography.labelLarge, color = AetherOnSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr(strings, "Permission is granted and the setup probe succeeded.", "权限已授予，设置检测已通过。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
        } else {
            TermuxSetupNotice(
                setupState = termuxSetupState,
                onRequestPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefresh = onRefreshTermuxSetup,
                showRefreshAction = false,
            )
        }
    }
}

@Composable
private fun AgentModeSettingsPage(
    title: String,
    agentModeAuthorizationEnabled: Boolean,
    agentModeAuthorizationMethod: AgentModeAuthorizationMethod,
    agentModeAuthorizationState: AgentModeAuthorizationState,
    rootSetupState: RootSetupState,
    onAgentModeAuthorizationEnabledChanged: (Boolean) -> Unit,
    onAgentModeAuthorizationMethodChanged: (AgentModeAuthorizationMethod) -> Unit,
    agentModeDisplayState: AgentModeDisplayState,
    onRequestShizukuPermission: () -> Unit,
    onRefreshAgentModeAuthorization: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onRefreshRootSetup: () -> Unit,
    onConfigureWithRoot: () -> Unit,
    onStopAgentModeDisplay: () -> Unit,
    onRefreshAgentModeDisplays: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    var showAlreadyConfiguredDialog by rememberSaveable { mutableStateOf(false) }
    val agentModeConfigured = agentModeAuthorizationEnabled && agentModeAuthorizationState.isReady

    fun requestRootSetup() {
        if (agentModeConfigured && !rootSetupState.isRunning) {
            showAlreadyConfiguredDialog = true
        } else {
            onConfigureWithRoot()
        }
    }

    fun refreshAgentModeStatus() {
        onRefreshAgentModeAuthorization()
        onRefreshAgentModeDisplays()
    }
    if (showAlreadyConfiguredDialog) {
        RootSetupAlreadyConfiguredDialog(
            title = tr(strings, "Agent Mode is already configured", "Agent Mode 已配置完成"),
            body = if (agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root) {
                tr(
                    strings,
                    "Root Agent Mode authorization is already ready. You do not need to run Root automatic setup again.",
                    "Root Agent Mode 授权已经正常，不需要再次执行 Root 自动配置。",
                )
            } else {
                tr(
                    strings,
                    "Agent Mode authorization is already ready. Root automatic setup is not required; continuing will reconfigure Agent Mode to Root.",
                    "Agent Mode 授权已经正常，不需要执行 Root 自动配置；继续后会将 Agent Mode 重新配置为 Root。",
                )
            },
            onDismiss = { showAlreadyConfiguredDialog = false },
            onContinue = {
                showAlreadyConfiguredDialog = false
                onConfigureWithRoot()
            },
        )
    }

    LaunchedEffect(Unit) {
        onRefreshRootSetup()
        refreshAgentModeStatus()
        while (true) {
            delay(SettingsAutoRefreshIntervalMillis)
            refreshAgentModeStatus()
        }
    }
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Refresh,
        onTrailingAction = ::refreshAgentModeStatus,
    ) {
        Text(
            text = tr(strings, "Authorize isolated virtual-display tools with Shizuku or Root. Skip this on devices without either option.", "通过 Shizuku 或 Root 授权隔离的虚拟显示工具。没有这两种能力的设备可以跳过。"),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsToggleRow(
                    title = tr(strings, "Agent Mode authorization", "Agent 模式授权"),
                    subtitle = tr(strings, "Enables isolated virtual-display tools. Requires Shizuku or Root.", "启用隔离的虚拟显示工具，需要 Shizuku 或 Root。"),
                    checked = agentModeAuthorizationEnabled,
                    onCheckedChange = onAgentModeAuthorizationEnabledChanged,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = tr(strings, "Authorization method", "授权方式"),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentModeAuthorizationMethod.entries.forEach { method ->
                        SettingsChoiceRow(
                            title = method.displayName,
                            subtitle = when (method) {
                                AgentModeAuthorizationMethod.Shizuku -> tr(strings, "Uses an elevated Shizuku service.", "使用提权后的 Shizuku 服务。")
                                AgentModeAuthorizationMethod.Root -> tr(strings, "Uses root shell for privileged input.", "使用 root shell 执行特权输入。")
                            },
                            selected = agentModeAuthorizationMethod == method,
                            onClick = { onAgentModeAuthorizationMethodChanged(method) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tr(strings, "Shizuku mode creates the display from a Shizuku user service so apps can render off the main screen. Root mode remains experimental.", "Shizuku 模式会通过 Shizuku 用户服务创建显示，让应用在主屏幕之外渲染。Root 模式仍处于实验阶段。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                AgentModeAuthorizationNotice(
                    enabled = agentModeAuthorizationEnabled,
                    method = agentModeAuthorizationMethod,
                    state = agentModeAuthorizationState,
                    onRequestShizukuPermission = onRequestShizukuPermission,
                    onOpenShizuku = onOpenShizuku,
                    onInstallShizuku = onInstallShizuku,
                )
                Spacer(Modifier.height(12.dp))
                RootSetupSettingsSection(
                    title = tr(strings, "Root automatic setup", "Root 自动配置"),
                    rootSetupState = rootSetupState,
                    body = tr(
                        strings,
                        "Use su to select Root mode and prepare local device control automatically.",
                        "使用 su 自动选择 Root 模式，并准备本地设备控制。",
                    ),
                    onConfigureWithRoot = ::requestRootSetup,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = tr(strings, "Virtual Display", "虚拟显示"),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (agentModeDisplayState.isActive) {
                        tr(strings, "Display ${agentModeDisplayState.displayId ?: "-"} is active at ${agentModeDisplayState.width} x ${agentModeDisplayState.height}.", "显示 ${agentModeDisplayState.displayId ?: "-"} 正在运行，分辨率为 ${agentModeDisplayState.width} x ${agentModeDisplayState.height}。")
                    } else {
                        tr(strings, "No Agent Mode virtual display is active.", "当前没有活动的 Agent 模式虚拟显示。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                if (agentModeDisplayState.latestWorkspacePath.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = agentModeDisplayState.latestWorkspacePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = tr(strings, "Visible displays", "可见显示"),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(8.dp))
                if (agentModeDisplayState.displays.isEmpty()) {
                    Text(
                        text = tr(strings, "No displays are currently visible to Aether.", "Aether 当前看不到任何显示。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        agentModeDisplayState.displays.forEach { display ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                if (display.isAetherDisplay) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                                } else {
                                    AetherBackground
                                }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tr(strings, "Display ${display.displayId}", "显示 ${display.displayId}"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AetherOnSurface,
                                    )
                                    Text(
                                        text = listOf(
                                            display.name.ifBlank { tr(strings, "Unnamed", "未命名") },
                                            "${display.width} x ${display.height}",
                                            if (display.isAetherDisplay) "Aether" else "",
                                        ).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AetherOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SettingsActionButton(
                    label = tr(strings, "Stop virtual display", "停止虚拟显示"),
                    onClick = onStopAgentModeDisplay,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = agentModeDisplayState.isActive,
                )
            }
        }
    }
}

@Composable
private fun RootSetupAlreadyConfiguredDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(28.dp))
                .background(AetherSurfaceHigh)
                .padding(20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSubtleActionButton(
                    label = tr(strings, "Cancel", "取消"),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    label = tr(strings, "Continue", "继续"),
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class RootSetupProgressStepStatus {
    Pending,
    Active,
    Complete,
    Attention,
}

@Composable
private fun RootSetupProgressPage(
    rootSetupState: RootSetupState,
    termuxSetupState: TermuxSetupState,
    agentModeAuthorizationEnabled: Boolean,
    agentModeAuthorizationMethod: AgentModeAuthorizationMethod,
    agentModeAuthorizationState: AgentModeAuthorizationState,
    onRunRootSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val rootAgentModeReady = agentModeAuthorizationEnabled &&
        agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root &&
        agentModeAuthorizationState.isReady
    val rootStepStatus = when {
        rootSetupState.isRunning -> RootSetupProgressStepStatus.Active
        rootSetupState.isReady || rootSetupState.rootAvailable -> RootSetupProgressStepStatus.Complete
        rootSetupState.issue == RootSetupIssue.Unavailable -> RootSetupProgressStepStatus.Attention
        else -> RootSetupProgressStepStatus.Pending
    }
    val termuxStepStatus = when {
        rootSetupState.isReady || termuxSetupState.isReady -> RootSetupProgressStepStatus.Complete
        rootSetupState.isRunning -> RootSetupProgressStepStatus.Active
        rootSetupState.issue == RootSetupIssue.TermuxNotInstalled ||
            rootSetupState.issue == RootSetupIssue.Failed -> RootSetupProgressStepStatus.Attention
        else -> RootSetupProgressStepStatus.Pending
    }
    val agentModeStepStatus = when {
        rootSetupState.isReady || rootAgentModeReady -> RootSetupProgressStepStatus.Complete
        rootSetupState.isRunning -> RootSetupProgressStepStatus.Active
        rootSetupState.issue == RootSetupIssue.PermissionDenied ||
            rootSetupState.issue == RootSetupIssue.Failed -> RootSetupProgressStepStatus.Attention
        else -> RootSetupProgressStepStatus.Pending
    }

    SubPageScaffold(
        title = tr(strings, "Root automatic setup", "Root 自动配置"),
        onBack = onBack,
    ) {
        SettingsCardGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(rootSetupProgressAccent(rootSetupState.issue).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (rootSetupState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            strokeWidth = 3.dp,
                            color = AetherPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = if (rootSetupState.isReady) Icons.Rounded.Check else Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = rootSetupProgressAccent(rootSetupState.issue),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = rootSetupProgressTitle(rootSetupState.issue, strings),
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = rootSetupProgressBody(rootSetupState.issue, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                if (rootSetupState.detail.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = rootSetupState.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RootSetupProgressStep(
                    title = tr(strings, "Root access", "Root 访问"),
                    subtitle = tr(strings, "Detect su and wait for authorization.", "检测 su 并等待授权。"),
                    status = rootStepStatus,
                )
                RootSetupProgressStep(
                    title = tr(strings, "Termux command access", "Termux 命令访问"),
                    subtitle = tr(strings, "Enable external apps and grant command permission.", "启用外部应用并授予命令权限。"),
                    status = termuxStepStatus,
                )
                RootSetupProgressStep(
                    title = tr(strings, "Root Agent Mode", "Root Agent Mode"),
                    subtitle = tr(strings, "Select Root authorization for local device control.", "为本地设备控制选择 Root 授权。"),
                    status = agentModeStepStatus,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            rootSetupState.isRunning -> SettingsSubtleActionButton(
                label = tr(strings, "Back", "返回"),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )

            rootSetupState.isReady -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSubtleActionButton(
                    label = tr(strings, "Run again", "重新执行"),
                    onClick = onRunRootSetup,
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    label = tr(strings, "Done", "完成"),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSubtleActionButton(
                    label = tr(strings, "Back", "返回"),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    label = tr(strings, "Try again", "重试"),
                    onClick = onRunRootSetup,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RootSetupProgressStep(
    title: String,
    subtitle: String,
    status: RootSetupProgressStepStatus,
) {
    val (containerColor, contentColor) = when (status) {
        RootSetupProgressStepStatus.Complete -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) to AetherPrimary
        RootSetupProgressStepStatus.Active -> AetherPrimary.copy(alpha = 0.16f) to AetherPrimary
        RootSetupProgressStepStatus.Attention -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.64f) to MaterialTheme.colorScheme.error
        RootSetupProgressStepStatus.Pending -> AetherSurface to AetherOnSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AetherSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            if (status == RootSetupProgressStepStatus.Active) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
            } else {
                Icon(
                    imageVector = when (status) {
                        RootSetupProgressStepStatus.Complete -> Icons.Rounded.Check
                        RootSetupProgressStepStatus.Attention -> Icons.Rounded.Info
                        RootSetupProgressStepStatus.Pending,
                        RootSetupProgressStepStatus.Active -> Icons.Rounded.Refresh
                    },
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun rootSetupProgressAccent(issue: RootSetupIssue): Color = when (issue) {
    RootSetupIssue.Ready -> AetherPrimary
    RootSetupIssue.Running,
    RootSetupIssue.Available -> AetherPrimary
    RootSetupIssue.PermissionDenied,
    RootSetupIssue.TermuxNotInstalled,
    RootSetupIssue.Failed -> MaterialTheme.colorScheme.error
    RootSetupIssue.Unknown,
    RootSetupIssue.Unavailable -> AetherOnSurfaceVariant
}

private fun rootSetupProgressTitle(
    issue: RootSetupIssue,
    strings: AetherStrings,
): String = when (issue) {
    RootSetupIssue.Running -> tr(strings, "Configuring Root setup", "正在执行 Root 自动配置")
    RootSetupIssue.Ready -> tr(strings, "Root setup completed", "Root 自动配置已完成")
    RootSetupIssue.Available -> tr(strings, "Ready to start", "准备开始")
    RootSetupIssue.Unavailable -> tr(strings, "Root is unavailable", "Root 不可用")
    RootSetupIssue.PermissionDenied -> tr(strings, "Root authorization was not granted", "未获得 Root 授权")
    RootSetupIssue.TermuxNotInstalled -> tr(strings, "Termux is required", "需要安装 Termux")
    RootSetupIssue.Failed -> tr(strings, "Setup did not complete", "配置未完成")
    RootSetupIssue.Unknown -> tr(strings, "Preparing setup", "正在准备配置")
}

private fun rootSetupProgressBody(
    issue: RootSetupIssue,
    strings: AetherStrings,
): String = when (issue) {
    RootSetupIssue.Running -> tr(
        strings,
        "Aether is requesting su, enabling Termux command access, and preparing Root Agent Mode.",
        "Aether 正在请求 su、启用 Termux 命令访问，并准备 Root Agent Mode。",
    )

    RootSetupIssue.Ready -> tr(
        strings,
        "Termux command access and Root Agent Mode authorization are ready.",
        "Termux 命令访问和 Root Agent Mode 授权已经就绪。",
    )

    RootSetupIssue.Available,
    RootSetupIssue.Unknown -> tr(
        strings,
        "Start setup to grant su and finish the local access configuration.",
        "开始配置后会请求 su，并完成本地访问配置。",
    )

    RootSetupIssue.Unavailable -> tr(
        strings,
        "No su binary was detected on this device.",
        "当前设备未检测到 su。",
    )

    RootSetupIssue.PermissionDenied -> tr(
        strings,
        "Grant su to Aether, then try Root automatic setup again.",
        "请授予 Aether su 权限后重试 Root 自动配置。",
    )

    RootSetupIssue.TermuxNotInstalled -> tr(
        strings,
        "Install Termux first, then return to this setup.",
        "请先安装 Termux，然后返回继续配置。",
    )

    RootSetupIssue.Failed -> tr(
        strings,
        "The setup command finished with an error. Review the detail above and retry when ready.",
        "配置命令返回错误。请查看上方详情后重试。",
    )
}

@Composable
private fun RootSetupSettingsSection(
    title: String,
    rootSetupState: RootSetupState,
    body: String,
    onConfigureWithRoot: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
            )
            if (rootSetupState.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AetherPrimary,
                )
            }
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
        if (rootSetupState.detail.isNotBlank()) {
            Text(
                text = rootSetupState.detail,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsSubtleActionButton(
            label = when (rootSetupState.issue) {
                RootSetupIssue.Running -> tr(strings, "Configuring...", "正在配置...")
                RootSetupIssue.Ready -> tr(strings, "Run Root setup again", "重新执行 Root 配置")
                RootSetupIssue.Unavailable -> tr(strings, "Try Root setup", "尝试 Root 配置")
                else -> tr(strings, "Configure with Root", "使用 Root 配置")
            },
            onClick = onConfigureWithRoot,
            modifier = Modifier.fillMaxWidth(),
            enabled = !rootSetupState.isRunning,
        )
    }
}

private fun rootSetupSettingsBody(
    rootSetupState: RootSetupState,
    strings: AetherStrings,
): String = when (rootSetupState.issue) {
    RootSetupIssue.Ready -> tr(
        strings,
        "Root setup can repair Termux command access silently if Termux is killed later.",
        "如果之后 Termux 被杀掉，Root 配置可以静默修复命令访问。",
    )

    RootSetupIssue.Available,
    RootSetupIssue.Running -> tr(
        strings,
        "Aether can enable Termux external apps and grant the command permission with su.",
        "Aether 可以通过 su 启用 Termux 外部应用并授予命令权限。",
    )

    RootSetupIssue.Unavailable,
    RootSetupIssue.Unknown -> tr(
        strings,
        "On rooted devices, this avoids the manual Termux permission and settings flow.",
        "在已 Root 设备上，这可以避免手动配置 Termux 权限和设置。",
    )

    RootSetupIssue.PermissionDenied,
    RootSetupIssue.TermuxNotInstalled,
    RootSetupIssue.Failed -> tr(
        strings,
        "Retry after granting su to Aether, or continue with the manual setup actions below.",
        "授予 Aether su 后重试，或继续使用下方的手动配置操作。",
    )
}

@Composable
private fun AgentModeAuthorizationNotice(
    enabled: Boolean,
    method: AgentModeAuthorizationMethod,
    state: AgentModeAuthorizationState,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val statusText = when {
        enabled && method == AgentModeAuthorizationMethod.Root -> when (state.issue) {
            AgentModeAuthorizationIssue.Ready -> tr(strings, "Root authorization is ready.", "Root 授权已就绪。")
            AgentModeAuthorizationIssue.RootUnavailable -> tr(strings, "No su binary was detected on this device.", "此设备未检测到 su。")
            AgentModeAuthorizationIssue.RootPermissionMissing -> tr(strings, "Grant su to Aether, then refresh this status.", "请向 Aether 授予 su，然后刷新状态。")
            AgentModeAuthorizationIssue.RootPermissionDenied -> tr(strings, "Root authorization was denied. Grant su to Aether, then refresh this status.", "Root 授权被拒绝。请向 Aether 授予 su，然后刷新状态。")
            AgentModeAuthorizationIssue.Error -> state.detail.ifBlank {
                tr(strings, "Unable to inspect Root status.", "无法检查 Root 状态。")
            }
            else -> state.detail.ifBlank {
                tr(strings, "Refresh Root status before using Agent Mode.", "使用 Agent 模式前请刷新 Root 状态。")
            }
        }
        !enabled -> tr(strings, "Agent Mode authorization is off.", "Agent 模式授权已关闭。")
        method == AgentModeAuthorizationMethod.Root -> tr(strings, "Root mode will request su when Agent Mode starts.", "Root 模式会在启动 Agent 模式时请求 su。")
        else -> when (state.issue) {
            AgentModeAuthorizationIssue.Ready -> tr(strings, "Shizuku is running and Aether is authorized.", "Shizuku 正在运行，Aether 已获得授权。")
            AgentModeAuthorizationIssue.ShizukuNotInstalled -> tr(strings, "Install Shizuku before using Shizuku mode.", "使用 Shizuku 模式前，请先安装 Shizuku。")
            AgentModeAuthorizationIssue.ShizukuNotRunning -> tr(strings, "Start Shizuku, then refresh this status.", "先启动 Shizuku，然后刷新此状态。")
            AgentModeAuthorizationIssue.ShizukuPermissionMissing -> tr(strings, "Grant Aether permission in Shizuku before using Agent Mode.", "使用 Agent 模式前，请先在 Shizuku 中授予 Aether 权限。")
            AgentModeAuthorizationIssue.ShizukuPermissionDenied -> tr(strings, "Shizuku permission was denied. Request it again or enable Aether inside Shizuku.", "Shizuku 权限被拒绝。请重新请求授权，或在 Shizuku 中启用 Aether。")
            AgentModeAuthorizationIssue.Disabled -> tr(strings, "Save Agent Mode settings, then refresh Shizuku status.", "保存 Agent 模式设置后，再刷新 Shizuku 状态。")
            AgentModeAuthorizationIssue.RootUnavailable,
            AgentModeAuthorizationIssue.RootPermissionMissing,
            AgentModeAuthorizationIssue.RootPermissionDenied -> state.detail.ifBlank {
                tr(strings, "Switch to Root mode to inspect Root status.", "切换到 Root 模式以检查 Root 状态。")
            }
            AgentModeAuthorizationIssue.Error -> state.detail.ifBlank {
                tr(strings, "Unable to inspect Shizuku status.", "无法检查 Shizuku 状态。")
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = tr(strings, "Authorization status", "授权状态"),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
        )
        Text(
            text = if (state.issue == AgentModeAuthorizationIssue.Error && state.detail.isNotBlank()) {
                state.detail
            } else {
                statusText
            },
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )

        when {
            !enabled || method == AgentModeAuthorizationMethod.Root -> Unit

            state.issue == AgentModeAuthorizationIssue.ShizukuNotInstalled -> {
                SettingsSubtleActionButton(
                    label = tr(strings, "Install Shizuku", "安装 Shizuku"),
                    onClick = onInstallShizuku,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.issue == AgentModeAuthorizationIssue.ShizukuNotRunning -> {
                SettingsSubtleActionButton(
                    label = tr(strings, "Open Shizuku", "打开 Shizuku"),
                    onClick = onOpenShizuku,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.issue == AgentModeAuthorizationIssue.ShizukuPermissionMissing ||
                state.issue == AgentModeAuthorizationIssue.ShizukuPermissionDenied -> {
                SettingsSubtleActionButton(
                    label = tr(strings, "Grant access", "授予权限"),
                    onClick = onRequestShizukuPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun DeveloperSettingsPage(
    title: String,
    onReplayFollowUpOnboarding: () -> Unit,
    onImportAppData: () -> Unit,
    onExportAppData: () -> Unit,
    onExportLogs: () -> Unit,
    onForceUpdateCheckForTesting: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    SubPageScaffold(title = title, onBack = onBack) {
        Text(
            text = tr(strings, "Developer-only tools and replay controls.", "开发者专用工具和引导重播控制。"),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = tr(strings, "App Data", "应用数据"),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr(strings, "Import or export the complete local Aether data set as JSON.", "以 JSON 格式导入或导出完整的本地 Aether 数据。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = tr(strings, "Import app data", "导入应用数据"),
                    onClick = onImportAppData,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                SettingsSubtleActionButton(
                    label = tr(strings, "Export app data", "导出应用数据"),
                    onClick = onExportAppData,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = tr(strings, "Logs", "日志"),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr(strings, "Export recent Aether logcat output and diagnostic metadata.", "导出近期 Aether logcat 输出和诊断信息。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = tr(strings, "Export logs", "导出日志"),
                    onClick = onExportLogs,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = tr(strings, "Update testing", "更新测试"),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr(
                        strings,
                        "拉取最新 GitHub Release，即使当前已是最新版本也显示更新提示。",
                        "Fetch the latest GitHub Release and show the update prompt even when the installed version is current.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = tr(strings, "Force update prompt", "强制显示更新提示"),
                    onClick = onForceUpdateCheckForTesting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = tr(strings, "Replay Follow-up Tour", "重播后续引导"),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr(strings, "Starts from Termux, then goes through Agent Mode, Tavily, Skills, and MCP.", "从 Termux 开始，然后依次进入 Agent 模式、Tavily、技能和 MCP。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = tr(strings, "Replay second part", "重播第二部分"),
                    onClick = onReplayFollowUpOnboarding,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Shared building blocks
// -----------------------------------------------------------------------------

// Sub-page scaffold

@Composable
private fun AboutPage(
    title: String,
    appUpdate: AppUpdateUiState,
    onOpenWebsite: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val updateSubtitle = when {
        appUpdate.isDownloading -> appUpdate.downloadProgress?.let { progress ->
            tr(strings, "Downloading ${(progress * 100).toInt()}%", "正在下载 ${(progress * 100).toInt()}%")
        } ?: tr(strings, "Downloading update", "正在下载更新")
        appUpdate.isChecking -> tr(strings, "Checking GitHub Releases", "正在检查 GitHub Releases")
        appUpdate.availableRelease != null -> tr(
            strings,
            "Aether ${appUpdate.availableRelease.versionName} is available",
            "Aether ${appUpdate.availableRelease.versionName} 可用",
        )
        else -> tr(strings, "Check GitHub Releases for a newer APK", "检查 GitHub Releases 中是否有新版 APK")
    }
    val releaseLabel = tr(strings, "Release ${BuildConfig.VERSION_NAME}", "版本 ${BuildConfig.VERSION_NAME}")
    SubPageScaffold(title = title, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.aether_mark),
                contentDescription = tr(strings, "Aether logo", "Aether 标志"),
                modifier = Modifier.size(112.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Aether",
                style = MaterialTheme.typography.titleLarge,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = releaseLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))

        SettingsCardGroup {
            AboutInfoRow(label = tr(strings, "Author", "作者"), value = "sbxjtyes")
            CardDivider()
            AboutInfoRow(label = tr(strings, "Version", "版本"), value = releaseLabel)
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Refresh,
                title = tr(strings, "Check for updates", "检查更新"),
                subtitle = updateSubtitle,
                onClick = onCheckForUpdates,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Link,
                title = tr(strings, "Website", "网站"),
                subtitle = AetherWebsiteUrl.removePrefix("https://"),
                onClick = onOpenWebsite,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Link,
                title = tr(strings, "Privacy Policy", "隐私政策"),
                subtitle = AetherPrivacyPolicyUrl.removePrefix("https://"),
                onClick = onOpenPrivacyPolicy,
            )
        }

        if (appUpdate.availableRelease != null) {
            Spacer(Modifier.height(16.dp))
            SettingsActionButton(
                label = if (appUpdate.isDownloading) {
                    updateSubtitle
                } else {
                    tr(strings, "Download and install", "下载并安装")
                },
                onClick = onDownloadAndInstallUpdate,
                enabled = !appUpdate.isDownloading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    trailingIcon: ImageVector? = null,
    trailingEnabled: Boolean = true,
    onTrailingAction: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var topBarBodyHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fallbackTopBarBodyHeight = with(density) {
        WindowInsets.statusBars.getTop(this).toDp() + 68.dp
    }
    val topBarBodyHeight = with(density) {
        if (topBarBodyHeightPx > 0) topBarBodyHeightPx.toDp() else fallbackTopBarBodyHeight
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = topBarBodyHeight)
                    .padding(horizontal = 20.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                Spacer(Modifier.height(6.dp))
                content()
                Spacer(Modifier.height(32.dp))
            }

            SettingsTopBarOverlay(
                modifier = Modifier.align(Alignment.TopCenter),
                title = title,
                onBack = onBack,
                trailingIcon = trailingIcon,
                trailingEnabled = trailingEnabled,
                onTrailingAction = onTrailingAction,
                onBodyHeightChanged = { topBarBodyHeightPx = it },
            )
        }
    }
}

// Top bar

@Composable
private fun SettingsTopBarOverlay(
    modifier: Modifier = Modifier,
    title: String,
    onBack: () -> Unit,
    trailingIcon: ImageVector? = null,
    trailingEnabled: Boolean = true,
    onTrailingAction: (() -> Unit)? = null,
    onBodyHeightChanged: (Int) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(settingsTopOverlayBodyGradient())
                .onSizeChanged { onBodyHeightChanged(it.height) },
        ) {
            SettingsTopBar(
                title = title,
                onBack = onBack,
                trailingIcon = trailingIcon,
                trailingEnabled = trailingEnabled,
                onTrailingAction = onTrailingAction,
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingsTopFadeHeight)
                .background(settingsTopOverlayTailGradient())
        )
    }
}

@Composable
private fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    trailingIcon: ImageVector? = null,
    trailingEnabled: Boolean = true,
    onTrailingAction: (() -> Unit)? = null,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsCircleButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = strings.back,
            onClick = onBack,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AetherOnSurface,
        )
        if (trailingIcon != null && onTrailingAction != null) {
            SettingsCircleButton(
                icon = trailingIcon,
                contentDescription = title,
                enabled = trailingEnabled,
                onClick = onTrailingAction,
            )
        } else {
            Spacer(Modifier.size(44.dp))
        }
    }
}

@Composable
private fun SettingsCircleButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(10.dp, RoundedCornerShape(50), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) AetherSurface else AetherSurface.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) AetherOnSurface else AetherOnSurface.copy(alpha = 0.45f),
        )
    }
}
