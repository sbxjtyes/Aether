package com.zhousl.aether.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhousl.aether.BuildConfig
import com.zhousl.aether.aetherRuntime
import com.zhousl.aether.data.ActiveSkillContext
import com.zhousl.aether.data.AetherAnalytics
import com.zhousl.aether.data.AppUpdateManager
import com.zhousl.aether.data.AutomaticModelPurpose
import com.zhousl.aether.data.AgentModeAuthorizationMethod
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.AppThemeMode
import com.zhousl.aether.data.CurrentOnboardingVersion
import com.zhousl.aether.data.InstalledSkill
import com.zhousl.aether.data.LlmApiClient
import com.zhousl.aether.data.LlmProvider
import com.zhousl.aether.data.LlmProviderConfig
import com.zhousl.aether.data.ProviderModelOption
import com.zhousl.aether.data.availableModelOptions
import com.zhousl.aether.data.McpClientManager
import com.zhousl.aether.data.McpServerConfig
import com.zhousl.aether.data.McpTransportConfig
import com.zhousl.aether.data.McpValidationSummary
import com.zhousl.aether.data.generateQuickActionLabel
import com.zhousl.aether.data.normalizeSelectableModelKey
import com.zhousl.aether.data.normalizeLlmInactivityReconnectTimeoutSeconds
import com.zhousl.aether.data.OnboardingStarterPrompt
import com.zhousl.aether.data.OpenAiCompatibleClient
import com.zhousl.aether.data.RootSetupIssue
import com.zhousl.aether.data.RootSetupState
import com.zhousl.aether.data.SessionFollowUpMode
import com.zhousl.aether.data.SessionTurnEvent
import com.zhousl.aether.data.SessionTurnOutcome
import com.zhousl.aether.data.SessionTurnRequest
import com.zhousl.aether.data.parseChatSessions
import com.zhousl.aether.data.parseMcpServerConfigs
import com.zhousl.aether.data.parseProviderConfigs
import com.zhousl.aether.data.serializeChatSessions
import com.zhousl.aether.data.serializeMcpServerConfigs
import com.zhousl.aether.data.serializeProviderConfigs
import com.zhousl.aether.data.toJson
import com.zhousl.aether.data.isProviderSetupValid
import com.zhousl.aether.data.isVersionNewer
import com.zhousl.aether.data.isOnboardingComplete
import com.zhousl.aether.data.shouldMarkOnboardingCompleted
import com.zhousl.aether.data.shouldLaunchOnboarding
import com.zhousl.aether.data.shouldRevealFollowUpTourCard
import com.zhousl.aether.data.resolveDefaultChatModelKey
import com.zhousl.aether.data.resolveDefaultTitleModelKey
import com.zhousl.aether.data.resolveAutomaticModelKey
import com.zhousl.aether.data.resolveModelSettings
import com.zhousl.aether.data.resolveStoredOrAutomaticModelKey
import com.zhousl.aether.termux.TermuxSetupIssue
import com.zhousl.aether.termux.TermuxSetupState
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val FollowUpTourAutoOpenDelayMillis = 2_500L
private const val AppUpdateCheckIntervalMillis = 3L * 24L * 60L * 60L * 1000L
private const val LogcatReadTimeoutSeconds = 4L
private const val AetherViewModelLogTag = "AetherViewModel"
private const val SessionTitleSystemPrompt =
    "Generate a concise chat title for this conversation. Return only the title, in the user's language when possible, with no quotes, no emoji, and at most 6 words."

class AetherViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val runtime = application.aetherRuntime
    private val settingsRepository = runtime.settingsRepository
    private val chatStateStore = runtime.chatStateStore
    private val extensionsRepository = runtime.extensionsRepository
    private val sessionExecutionManager = runtime.sessionExecutionManager
    private val client = OpenAiCompatibleClient()
    private val bashTool = runtime.bashTool
    private val rootSetupController = runtime.rootSetupController
    private val workspaceFileBridge = runtime.workspaceFileBridge
    private val agentModeController = runtime.agentModeController
    private val skillManager = runtime.skillManager
    private val mcpClientManager = McpClientManager(bashTool = bashTool)
    private val appUpdateManager = AppUpdateManager(application.applicationContext)
    private var didEvaluateStartupUpdateCheck = false
    private var lastTrackedTermuxDetectedIssue: TermuxSetupIssue? = null
    private var pendingTermuxSetupSource: String? = null
    @Volatile
    private var lastTermuxCommandSuccessAtMillis: Long = 0L
    private val _uiState = MutableStateFlow(AetherUiState())
    private val _transientMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val uiState: StateFlow<AetherUiState> = _uiState.asStateFlow()
    val transientMessages = _transientMessages.asSharedFlow()

    init {
        refreshTermuxSetup()
        refreshRootSetup()

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                if (settings.privacyPolicyAccepted) {
                    runtime.initializePostHog()
                }
                _uiState.update { current ->
                    if (!current.isStartupRouteResolved) {
                        current.copy(
                            settings = settings,
                            currentScreen = if (settings.shouldLaunchOnboarding()) {
                                AppScreen.Onboarding
                            } else {
                                AppScreen.Chat
                            },
                            isStartupRouteResolved = true,
                            isOnboardingReplay = false,
                            onboardingStep = OnboardingStep.Landing,
                            onboardingReturnScreen = AppScreen.Chat,
                        )
                    } else {
                        current.copy(settings = settings)
                    }
                }
                if (!didEvaluateStartupUpdateCheck && settings.privacyPolicyAccepted) {
                    didEvaluateStartupUpdateCheck = true
                    maybeCheckForUpdates(settings)
                }
                agentModeController.refreshAuthorization(settings)
            }
        }

        viewModelScope.launch {
            chatStateStore.state.collect { persisted ->
                _uiState.update { current ->
                    val currentExecution = current.sessionExecutionStates[persisted.currentSessionId.ifBlank { DraftSessionId }]
                    current.copy(
                        sessions = persisted.sessions,
                        currentSessionId = persisted.currentSessionId.ifBlank { DraftSessionId },
                        isSending = currentExecution?.isRunning == true,
                        pendingResponseSessionId = currentExecution?.sessionId,
                        pendingToolInvocations = currentExecution?.pendingToolInvocations.orEmpty(),
                        pendingResponseBlocks = currentExecution?.pendingResponseBlocks.orEmpty(),
                        pendingAssistantText = currentExecution?.pendingAssistantText.orEmpty(),
                        pendingStatusText = currentExecution?.pendingStatusText.orEmpty(),
                        pendingStatusDetail = currentExecution?.pendingStatusDetail.orEmpty(),
                    )
                }
            }
        }

        viewModelScope.launch {
            sessionExecutionManager.executionStates.collect { executionStates ->
                _uiState.update { current ->
                    val currentExecution = executionStates[current.currentSessionId]
                    current.copy(
                        sessionExecutionStates = executionStates,
                        isSending = currentExecution?.isRunning == true,
                        pendingResponseSessionId = currentExecution?.sessionId,
                        pendingToolInvocations = currentExecution?.pendingToolInvocations.orEmpty(),
                        pendingResponseBlocks = currentExecution?.pendingResponseBlocks.orEmpty(),
                        pendingAssistantText = currentExecution?.pendingAssistantText.orEmpty(),
                        pendingStatusText = currentExecution?.pendingStatusText.orEmpty(),
                        pendingStatusDetail = currentExecution?.pendingStatusDetail.orEmpty(),
                    )
                }
            }
        }

        viewModelScope.launch {
            sessionExecutionManager.turnEvents.collect { event ->
                handleTurnEvent(event)
            }
        }

        viewModelScope.launch {
            extensionsRepository.extensionState.collect { extensionState ->
                var didPruneSelections = false
                val enabledSkillIds = extensionState.installedSkills
                    .filter { it.isEnabled }
                    .map { it.id }
                    .toSet()
                val enabledMcpServerIds = extensionState.mcpServers
                    .filter { it.isEnabled }
                    .map { it.id }
                    .toSet()
                _uiState.update { current ->
                    val updatedSessions = current.sessions.map { session ->
                        val updatedSelectedSkillIds = session.selectedSkillIds.filter(enabledSkillIds::contains)
                        val updatedActiveSkills = session.activeSkills.filter { activeSkill ->
                            updatedSelectedSkillIds.contains(activeSkill.skillId)
                        }
                        val updatedActiveMcpServerIds = session.activeMcpServerIds.filter(enabledMcpServerIds::contains)
                        if (
                            updatedSelectedSkillIds != session.selectedSkillIds ||
                            updatedActiveSkills != session.activeSkills ||
                            updatedActiveMcpServerIds != session.activeMcpServerIds
                        ) {
                            didPruneSelections = true
                            session.copy(
                                selectedSkillIds = updatedSelectedSkillIds,
                                activeSkills = updatedActiveSkills,
                                activeMcpServerIds = updatedActiveMcpServerIds,
                            )
                        } else {
                            session
                        }
                    }
                    val updatedDraftSelectedSkillIds = current.draftSelectedSkillIds.filter(enabledSkillIds::contains)
                    val updatedDraftSelectedMcpServerIds = current.draftSelectedMcpServerIds.filter(enabledMcpServerIds::contains)
                    if (
                        updatedDraftSelectedSkillIds != current.draftSelectedSkillIds ||
                        updatedDraftSelectedMcpServerIds != current.draftSelectedMcpServerIds
                    ) {
                        didPruneSelections = true
                    }
                    current.copy(
                        sessions = updatedSessions,
                        draftSelectedSkillIds = updatedDraftSelectedSkillIds,
                        draftSelectedMcpServerIds = updatedDraftSelectedMcpServerIds,
                        installedSkills = extensionState.installedSkills,
                        mcpServers = extensionState.mcpServers,
                    )
                }
                if (didPruneSelections) {
                    persistPrunedSessionSelections(
                        enabledSkillIds = enabledSkillIds,
                        enabledMcpServerIds = enabledMcpServerIds,
                    )
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.providerConfigs.collect { configs ->
                _uiState.update { current -> current.copy(providerConfigs = configs) }
            }
        }
        viewModelScope.launch {
            agentModeController.displayState.collect { displayState ->
                _uiState.update { current -> current.copy(agentModeDisplayState = displayState) }
            }
        }
        viewModelScope.launch {
            agentModeController.authorizationState.collect { authorizationState ->
                _uiState.update { current -> current.copy(agentModeAuthorizationState = authorizationState) }
            }
        }
    }

    fun acceptPrivacyPolicy() {
        viewModelScope.launch {
            settingsRepository.updatePrivacyPolicyAccepted(true)
            runtime.initializePostHog()
        }
    }

    fun refreshTermuxSetup() {
        viewModelScope.launch {
            val refreshStartedAtMillis = System.currentTimeMillis()
            val setupState = withContext(Dispatchers.IO) {
                inspectTermuxSetupWithRootRepair()
            }
            if (setupState.isReady) {
                lastTermuxCommandSuccessAtMillis = System.currentTimeMillis()
            }
            trackTermuxSetupState(setupState, source = "refresh")
            _uiState.update { current ->
                val staleFailureAfterKnownSuccess = !setupState.isReady &&
                    current.termuxSetupState.isReady &&
                    lastTermuxCommandSuccessAtMillis >= refreshStartedAtMillis
                if (staleFailureAfterKnownSuccess) {
                    current
                } else {
                    current.copy(termuxSetupState = setupState)
                }
            }
        }
    }

    fun refreshRootSetup() {
        viewModelScope.launch {
            val inspectedRootState = rootSetupController.inspect()
            _uiState.update { current ->
                val rootState = if (current.rootSetupState.isReady && inspectedRootState.rootAvailable) {
                    current.rootSetupState.copy(
                        rootAvailable = true,
                        suPath = inspectedRootState.suPath,
                        lastUpdatedMillis = inspectedRootState.lastUpdatedMillis,
                    )
                } else {
                    inspectedRootState
                }
                current.copy(rootSetupState = rootState)
            }
        }
    }

    fun configureLocalAccessWithRoot() {
        val currentRootState = _uiState.value.rootSetupState
        if (currentRootState.isRunning) return
        trackTermuxSetupStarted(source = "root_setup")
        trackPermissionRequested(
            permission = "root_su",
            source = "root_setup",
        )
        _uiState.update { current ->
            current.copy(
                rootSetupState = RootSetupState(
                    issue = RootSetupIssue.Running,
                    detail = "Requesting root access...",
                    rootAvailable = currentRootState.rootAvailable,
                    suPath = currentRootState.suPath,
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            )
        }
        viewModelScope.launch {
            val rootState = rootSetupController.configureLocalAccess()
            trackPermissionResult(
                permission = "root_su",
                granted = rootState.isReady,
                source = "root_setup",
                result = rootState.issue.name.lowercase(),
            )
            if (rootState.isReady) {
                val settings = _uiState.value.settings
                settingsRepository.updateSettings(
                    settings.copy(
                        agentModeAuthorizationEnabled = true,
                        agentModeAuthorizationMethod = AgentModeAuthorizationMethod.Root,
                    )
                )
                agentModeController.refreshAuthorization(
                    settings.copy(
                        agentModeAuthorizationEnabled = true,
                        agentModeAuthorizationMethod = AgentModeAuthorizationMethod.Root,
                    )
                )
            }
            val setupState = withContext(Dispatchers.IO) { bashTool.inspectSetup() }
            trackTermuxSetupState(setupState, source = "root_setup")
            _uiState.update { current ->
                current.copy(
                    rootSetupState = rootState,
                    termuxSetupState = setupState,
                )
            }
            if (rootState.didLaunchTermuxForBackground) {
                emitTransientMessage(termuxBackgroundLaunchMessage())
            }
            emitTransientMessage(
                if (rootState.isReady) {
                    "Root setup completed"
                } else {
                    "Root setup failed: ${rootState.detail.ifBlank { rootState.issue.name }}"
                }
            )
        }
    }

    fun startRootSetupFromSettings(returnPage: RootSetupProgressReturnPage) {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Settings,
                rootSetupProgressReturnPage = returnPage,
            )
        }
        configureLocalAccessWithRoot()
    }

    fun dismissRootSetupProgress() {
        _uiState.update { current ->
            current.copy(rootSetupProgressReturnPage = null)
        }
    }

    private suspend fun inspectTermuxSetupWithRootRepair(): TermuxSetupState {
        val setupState = bashTool.inspectSetup()
        if (setupState.isReady) return setupState

        val rootState = rootStateForAutomaticTermuxRepair()
        if (
            rootState.isRunning ||
            (!rootState.isReady && rootState.issue != RootSetupIssue.Available)
        ) {
            return setupState
        }

        trackTermuxSetupStarted(source = "automatic_root_repair")
        trackPermissionRequested(
            permission = "root_su",
            source = "automatic_root_repair",
        )
        val repairedRootState = rootSetupController.configureLocalAccess()
        trackPermissionResult(
            permission = "root_su",
            granted = repairedRootState.isReady,
            source = "automatic_root_repair",
            result = repairedRootState.issue.name.lowercase(),
        )
        _uiState.update { current -> current.copy(rootSetupState = repairedRootState) }
        if (repairedRootState.didLaunchTermuxForBackground) {
            emitTransientMessage(termuxBackgroundLaunchMessage())
        }
        return if (repairedRootState.isReady) {
            bashTool.inspectSetup()
        } else {
            setupState
        }
    }

    private suspend fun rootStateForAutomaticTermuxRepair(): RootSetupState {
        val rootState = _uiState.value.rootSetupState
        if (
            rootState.isReady ||
            rootState.issue == RootSetupIssue.Available ||
            rootState.isRunning
        ) {
            return rootState
        }
        if (rootState.issue != RootSetupIssue.Unknown) return rootState

        val inspectedRootState = rootSetupController.inspect()
        _uiState.update { current -> current.copy(rootSetupState = inspectedRootState) }
        return inspectedRootState
    }

    fun refreshAgentModeAuthorization() {
        viewModelScope.launch {
            agentModeController.refreshAuthorization(_uiState.value.settings)
        }
    }

    fun requestShizukuPermission() {
        _uiState.update { current ->
            current.copy(agentModeAuthorizationState = agentModeController.requestShizukuPermission())
        }
    }

    fun trackTermuxSetupStarted(source: String) {
        pendingTermuxSetupSource = source
        captureAnalyticsEvent(
            event = "termux setup started",
            properties = mapOf(
                "source" to source,
                "current_issue" to _uiState.value.termuxSetupState.issue.name.lowercase(),
                "is_ready" to _uiState.value.termuxSetupState.isReady,
            ),
        )
    }

    fun trackPermissionRequested(
        permission: String,
        source: String,
    ) {
        captureAnalyticsEvent(
            event = "permission requested",
            properties = mapOf(
                "permission" to permission,
                "source" to source,
            ),
        )
    }

    fun trackPermissionResult(
        permission: String,
        granted: Boolean,
        source: String,
        result: String = if (granted) "granted" else "denied",
    ) {
        captureAnalyticsEvent(
            event = "permission result",
            properties = mapOf(
                "permission" to permission,
                "source" to source,
                "granted" to granted,
                "result" to result,
            ),
        )
    }

    fun checkForUpdates() {
        checkForUpdates(manual = true, forceAvailable = false)
    }

    fun forceUpdateCheckForTesting() {
        checkForUpdates(manual = true, forceAvailable = true)
    }

    fun dismissUpdateAvailableDialog() {
        _uiState.update { current ->
            current.copy(
                appUpdate = current.appUpdate.copy(showAvailableDialog = false)
            )
        }
    }

    fun downloadAndInstallUpdate() {
        val release = _uiState.value.appUpdate.availableRelease ?: return
        if (_uiState.value.appUpdate.isDownloading) return

        _uiState.update { current ->
            current.copy(
                appUpdate = current.appUpdate.copy(
                    isDownloading = true,
                    downloadProgress = null,
                )
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appUpdateManager.downloadApk(release) { progress ->
                        _uiState.update { current ->
                            current.copy(
                                appUpdate = current.appUpdate.copy(downloadProgress = progress)
                            )
                        }
                    }
                }
            }
            result
                .onSuccess { installUri ->
                    _uiState.update { current ->
                        current.copy(
                            appUpdate = current.appUpdate.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                pendingInstallUri = installUri.toString(),
                                showAvailableDialog = false,
                            )
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { current ->
                        current.copy(
                            appUpdate = current.appUpdate.copy(
                                isDownloading = false,
                                downloadProgress = null,
                            )
                        )
                    }
                    emitTransientMessage("Couldn't download update: ${throwable.userFacingMessage()}")
                }
        }
    }

    fun consumePendingUpdateInstallUri() {
        _uiState.update { current ->
            current.copy(
                appUpdate = current.appUpdate.copy(pendingInstallUri = "")
            )
        }
    }

    fun stopAgentModeDisplay() {
        agentModeController.stopDisplay()
    }

    fun refreshAgentModeDisplays() {
        viewModelScope.launch {
            agentModeController.refreshDisplays(_uiState.value.settings)
        }
    }

    fun updateDraftInput(value: String) {
        _uiState.update { current ->
            current.copy(
                draftInput = value,
                showStarterPromptHint = if (
                    current.showStarterPromptHint && value != current.draftInput
                ) {
                    false
                } else {
                    current.showStarterPromptHint
                },
            )
        }
    }

    fun skipOnboarding() {
        viewModelScope.launch {
            settingsRepository.updateOnboardingSeenVersion(CurrentOnboardingVersion)
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    isStartupRouteResolved = true,
                    isOnboardingReplay = false,
                    onboardingStep = OnboardingStep.Landing,
                    onboardingReturnScreen = AppScreen.Chat,
                )
            }
        }
    }

    fun openOnboardingFromSettings() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.Landing,
                onboardingReturnScreen = AppScreen.Settings,
            )
        }
    }

    fun openFollowUpOnboardingFromSettings() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.TermuxSetup,
                onboardingReturnScreen = AppScreen.Settings,
                awaitingFollowUpTour = false,
                showFollowUpTourCard = false,
            )
        }
    }

    fun resumeOnboarding() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.ProviderSetup,
                onboardingReturnScreen = AppScreen.Chat,
            )
        }
    }

    fun closeOnboarding() {
        _uiState.update { current ->
            current.copy(
                currentScreen = current.onboardingReturnScreen,
                isOnboardingReplay = false,
                onboardingStep = OnboardingStep.Landing,
                onboardingReturnScreen = AppScreen.Chat,
            )
        }
    }

    fun completeFollowUpOnboarding() {
        captureAnalyticsEvent(
            event = "onboarding follow up completed",
            properties = mapOf(
                "section" to "follow_up",
                "source" to if (_uiState.value.isOnboardingReplay) "replay" else "auto",
                "termux_ready" to _uiState.value.termuxSetupState.isReady,
                "agent_mode_authorized" to _uiState.value.agentModeAuthorizationState.isReady,
                "tavily_configured" to _uiState.value.settings.tavilyApiKey.isNotBlank(),
                "skill_count" to _uiState.value.installedSkills.size,
                "mcp_server_count" to _uiState.value.mcpServers.size,
            ),
        )
        closeOnboarding()
    }

    fun completeOnboardingProviderSetup(config: LlmProviderConfig) {
        viewModelScope.launch {
            val enabledConfig = config.copy(isEnabled = true)
            settingsRepository.upsertProviderConfig(enabledConfig)
            settingsRepository.setProviderEnabled(enabledConfig.id, true)
            val defaultModelKey = listOf(enabledConfig)
                .availableModelOptions()
                .resolveAutomaticModelKey(AutomaticModelPurpose.Chat)
            settingsRepository.updateOnboardingSeenVersion(CurrentOnboardingVersion)
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    isStartupRouteResolved = true,
                    isOnboardingReplay = false,
                    onboardingStep = OnboardingStep.Landing,
                    onboardingReturnScreen = AppScreen.Chat,
                    currentSessionId = DraftSessionId,
                    draftInput = OnboardingStarterPrompt,
                    draftAttachments = emptyList(),
                    draftSelectedModelKey = defaultModelKey,
                    draftSelectedSkillIds = emptyList(),
                    draftSelectedMcpServerIds = emptyList(),
                    draftAgentModeEnabled = false,
                    draftWorkspaceId = null,
                    editingSessionId = null,
                    editingMessageId = null,
                    showStarterPromptHint = true,
                    awaitingFollowUpTour = true,
                    showFollowUpTourCard = false,
                )
            }
            persistCurrentSessionId(DraftSessionId)
            captureAnalyticsEvent(
                event = "onboarding completed",
                properties = mapOf(
                    "section" to "initial",
                    "provider" to enabledConfig.providerType.displayName,
                    "provider_id" to enabledConfig.id,
                ),
            )
            captureAnalyticsEvent(
                event = "onboarding initial completed",
                properties = mapOf(
                    "section" to "initial",
                    "provider" to enabledConfig.providerType.displayName,
                    "provider_id" to enabledConfig.id,
                ),
            )
        }
    }

    fun dismissStarterPromptHint() {
        _uiState.update { current -> current.copy(showStarterPromptHint = false) }
    }

    fun openFollowUpTour() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.TermuxSetup,
                onboardingReturnScreen = AppScreen.Chat,
                awaitingFollowUpTour = false,
                showFollowUpTourCard = false,
            )
        }
    }

    fun saveOnboardingTavilyApiKey(value: String) {
        viewModelScope.launch {
            settingsRepository.updateTavilyApiKey(value.trim())
        }
    }

    fun saveOnboardingAgentModeAuthorization(
        enabled: Boolean,
        method: AgentModeAuthorizationMethod,
    ) {
        viewModelScope.launch {
            settingsRepository.updateSettings(
                _uiState.value.settings.copy(
                    agentModeAuthorizationEnabled = enabled,
                    agentModeAuthorizationMethod = method,
                )
            )
        }
    }

    fun exploreSettingsFromOnboardingTour() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Settings,
                isOnboardingReplay = false,
                onboardingStep = OnboardingStep.Landing,
                onboardingReturnScreen = AppScreen.Chat,
                awaitingFollowUpTour = false,
                showFollowUpTourCard = false,
            )
        }
    }

    fun appendDraftAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val targetSessionId = ensureDraftWorkspaceId()

        viewModelScope.launch {
            val pendingAttachments = withContext(Dispatchers.IO) {
                uris
                    .mapNotNull { uri -> buildPendingDraftAttachment(uri) }
                    .distinctBy { it.uri }
                    .toList()
            }
            if (pendingAttachments.isEmpty()) return@launch

            var attachmentsToImport = emptyList<ChatAttachment>()
            _uiState.update { current ->
                val newAttachments = pendingAttachments.filterNot { candidate ->
                    current.draftAttachments.any { existing -> existing.uri == candidate.uri }
                }
                attachmentsToImport = newAttachments
                if (newAttachments.isEmpty()) {
                    current
                } else {
                    current.copy(
                        draftAttachments = current.draftAttachments + newAttachments,
                        draftAttachmentRevision = current.draftAttachmentRevision + 1,
                    )
                }
            }

            withContext(Dispatchers.IO) {
                // Termux RUN_COMMAND requests are serviced out-of-process and can be queued during
                // cold start. Import attachments one by one so each upload's init/chunk/finalize
                // command sequence can finish before the next file starts dispatching commands.
                attachmentsToImport.forEach { attachment ->
                    importDraftAttachmentToWorkspace(
                        attachment = attachment,
                        sessionId = targetSessionId,
                    )
                }
            }
        }
    }

    fun removeDraftAttachment(attachmentId: String) {
        _uiState.update { current ->
            val updatedAttachments = current.draftAttachments.filterNot { it.id == attachmentId }
            if (updatedAttachments == current.draftAttachments) {
                current
            } else {
                current.copy(
                    draftAttachments = updatedAttachments,
                    draftAttachmentRevision = current.draftAttachmentRevision + 1,
                )
            }
        }
    }

    fun pauseGeneration() {
        val snapshot = _uiState.value
        val sessionId = sequenceOf(
            snapshot.currentSessionId,
            snapshot.pendingResponseSessionId,
        )
            .filterNotNull()
            .firstOrNull(sessionExecutionManager::isSessionRunning)
            ?: return
        sessionExecutionManager.pauseSession(sessionId)
    }

    fun openSettings() {
        _uiState.update { it.copy(currentScreen = AppScreen.Settings) }
    }

    fun closeSettings() {
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Chat,
                rootSetupProgressReturnPage = null,
            )
        }
    }

    fun startNewChat() {
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Chat,
                currentSessionId = DraftSessionId,
                draftInput = "",
                draftAttachments = emptyList(),
                draftSelectedModelKey = resolveDefaultChatModelKey(it.settings, it.providerConfigs),
                draftSelectedSkillIds = emptyList(),
                draftSelectedMcpServerIds = emptyList(),
                draftAgentModeEnabled = false,
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
                unviewedCompletedSessionIds = it.unviewedCompletedSessionIds - DraftSessionId,
                showStarterPromptHint = false,
            )
        }
        persistCurrentSessionId(DraftSessionId)
        captureAnalyticsEvent(event = "conversation started")
    }

    fun selectSession(sessionId: String) {
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Chat,
                currentSessionId = sessionId,
                draftInput = "",
                draftAttachments = emptyList(),
                draftSelectedModelKey = "",
                draftSelectedSkillIds = emptyList(),
                draftSelectedMcpServerIds = emptyList(),
                draftAgentModeEnabled = false,
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
                unviewedCompletedSessionIds = it.unviewedCompletedSessionIds - sessionId,
                showStarterPromptHint = false,
            )
        }
        persistCurrentSessionId(sessionId)
    }

    fun renameSession(
        sessionId: String,
        title: String,
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        updateSession(sessionId) { session ->
            if (session.title == trimmedTitle && session.hasCustomTitle) {
                null
            } else {
                session.copy(
                    title = trimmedTitle.take(80),
                    hasCustomTitle = true,
                )
            }
        }
    }

    fun deleteSession(sessionId: String) {
        deleteSessions(setOf(sessionId))
    }

    fun deleteSessions(sessionIds: Set<String>) {
        val requestedIds = sessionIds.filterTo(mutableSetOf()) { it != DraftSessionId }
        if (requestedIds.isEmpty()) return
        val runningIds = requestedIds.filter(sessionExecutionManager::isSessionRunning).toSet()
        if (runningIds.isNotEmpty()) {
            emitTransientMessage("Pause this session before deleting it.")
            requestedIds.removeAll(runningIds)
            if (requestedIds.isEmpty()) return
        }

        var didUpdate = false
        var deletedIds = emptySet<String>()
        _uiState.update { current ->
            val existingIds = current.sessions.mapTo(mutableSetOf()) { it.id }
            deletedIds = requestedIds.intersect(existingIds)
            if (deletedIds.isEmpty()) return@update current
            val updatedSessions = current.sessions.filterNot { it.id in deletedIds }
            if (updatedSessions.size == current.sessions.size) return@update current
            didUpdate = true
            current.copy(
                sessions = updatedSessions,
                currentSessionId = if (current.currentSessionId in deletedIds) DraftSessionId else current.currentSessionId,
                draftInput = if (current.editingSessionId in deletedIds) "" else current.draftInput,
                draftAttachments = if (current.editingSessionId in deletedIds) emptyList() else current.draftAttachments,
                draftWorkspaceId = if (current.editingSessionId in deletedIds) null else current.draftWorkspaceId,
                editingSessionId = if (current.editingSessionId in deletedIds) null else current.editingSessionId,
                editingMessageId = if (current.editingSessionId in deletedIds) null else current.editingMessageId,
                unviewedCompletedSessionIds = current.unviewedCompletedSessionIds - deletedIds,
                showStarterPromptHint = false,
            )
        }
        if (didUpdate) {
            persistDeleteSessions(deletedIds)
            cleanupDeletedSessionWorkspaces(deletedIds)
            captureAnalyticsEvent(event = if (deletedIds.size == 1) "conversation deleted" else "conversations deleted")
        }
    }

    fun exportSessionToUri(
        sessionId: String,
        destinationUri: Uri,
    ) {
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        viewModelScope.launch {
            val didExport = withContext(Dispatchers.IO) {
                writeTextToUri(
                    uri = destinationUri,
                    text = JSONObject().apply {
                        put("schemaVersion", 1)
                        put("exportType", "session")
                        put("exportedAtMillis", System.currentTimeMillis())
                        put("session", session.copy(messages = syncActiveBranches(session.messages)).toJson())
                    }.toString(2),
                )
            }
            emitTransientMessage(if (didExport) "Session exported" else "Couldn't export session")
        }
    }

    fun exportAllDataToUri(destinationUri: Uri) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            val didExport = withContext(Dispatchers.IO) {
                writeTextToUri(
                    uri = destinationUri,
                    text = buildFullAppExportJson(snapshot).toString(2),
                )
            }
            emitTransientMessage(if (didExport) "App data exported" else "Couldn't export app data")
        }
    }

    fun exportLogsToUri(destinationUri: Uri) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            val didExport = withContext(Dispatchers.IO) {
                writeTextToUri(
                    uri = destinationUri,
                    text = buildDiagnosticLogText(snapshot),
                )
            }
            emitTransientMessage(if (didExport) "Logs exported" else "Couldn't export logs")
        }
    }

    fun importAllDataFromUri(sourceUri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rawValue = readTextFromUri(sourceUri)
                    val json = JSONObject(rawValue)
                    val importedSkills = skillManager.importSkillBundles(json.optJSONArray("skillBundles"))
                    parseFullAppImport(json, importedSkills)
                }
            }
            result
                .onSuccess { imported ->
                    settingsRepository.replaceImportedSettings(
                        settings = imported.settings,
                        providerConfigs = imported.providerConfigs,
                    )
                    extensionsRepository.updateInstalledSkills(imported.installedSkills)
                    extensionsRepository.updateMcpServers(imported.mcpServers)
                    chatStateStore.updateAndFlush {
                        it.copy(
                            sessions = imported.sessions,
                            currentSessionId = imported.currentSessionId,
                        )
                    }
                    _uiState.update { current ->
                        current.copy(
                            sessions = imported.sessions,
                            currentSessionId = imported.currentSessionId,
                            draftInput = "",
                            draftAttachments = emptyList(),
                            draftSelectedModelKey = "",
                            draftSelectedSkillIds = emptyList(),
                            draftSelectedMcpServerIds = emptyList(),
                            draftAgentModeEnabled = false,
                            draftWorkspaceId = null,
                            editingSessionId = null,
                            editingMessageId = null,
                            unviewedCompletedSessionIds = emptySet(),
                        )
                    }
                    emitTransientMessage("App data imported")
                }
                .onFailure { throwable ->
                    emitTransientMessage("Couldn't import app data: ${throwable.userFacingMessage()}")
                }
        }
    }

    fun startEditingUserMessage(
        sessionId: String,
        messageId: String,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return

        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val message = session.messages.firstOrNull {
            it.id == messageId && it.author == MessageAuthor.User
        } ?: return

        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Chat,
                currentSessionId = sessionId,
                draftInput = message.text,
                draftAttachments = message.attachments.map(::normalizeDraftAttachmentForEditing),
                draftSelectedModelKey = if (sessionId == DraftSessionId) {
                    it.draftSelectedModelKey.ifBlank {
                        resolveDefaultChatModelKey(it.settings, it.providerConfigs)
                    }
                } else {
                    it.draftSelectedModelKey
                },
                draftWorkspaceId = sessionId,
                editingSessionId = sessionId,
                editingMessageId = messageId,
                showStarterPromptHint = false,
            )
        }
        persistCurrentSessionId(sessionId)
    }

    fun cancelMessageEdit() {
        _uiState.update {
            it.copy(
                draftInput = "",
                draftAttachments = emptyList(),
                draftSelectedModelKey = if (it.currentSessionId == DraftSessionId) {
                    it.draftSelectedModelKey.ifBlank {
                        resolveDefaultChatModelKey(it.settings, it.providerConfigs)
                    }
                } else {
                    it.draftSelectedModelKey
                },
                draftSelectedSkillIds = emptyList(),
                draftSelectedMcpServerIds = emptyList(),
                draftAgentModeEnabled = false,
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
                showStarterPromptHint = false,
            )
        }
    }

    fun deleteMessage(
        sessionId: String,
        messageId: String,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return

        var didUpdate = false
        var updatedSessionForPersistence: ChatSession? = null
        var removedSessionForPersistence = false

        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current

            val session = current.sessions[sessionIndex]
            val messageIndex = session.messages.indexOfFirst { it.id == messageId }
            if (messageIndex < 0) return@update current

            val trimFromIndex = session.messages.resolveConversationTrimIndex(messageIndex)
            val trimmedMessages = session.messages.take(trimFromIndex)
            val updatedSessions = current.sessions.toMutableList().apply {
                removeAt(sessionIndex)
                if (trimmedMessages.isNotEmpty()) {
                    val updatedSession = session.withMessages(trimmedMessages)
                    updatedSessionForPersistence = updatedSession
                    add(sessionIndex.coerceAtMost(size), updatedSession)
                } else {
                    removedSessionForPersistence = true
                }
            }

            didUpdate = true
                current.copy(
                    sessions = updatedSessions,
                    currentSessionId = when {
                        trimmedMessages.isEmpty() && current.currentSessionId == sessionId -> DraftSessionId
                        else -> current.currentSessionId
                    },
                    draftSelectedSkillIds = if (
                        trimmedMessages.isEmpty() && current.currentSessionId == sessionId
                    ) {
                        emptyList()
                    } else {
                        current.draftSelectedSkillIds
                    },
                    draftSelectedMcpServerIds = if (
                        trimmedMessages.isEmpty() && current.currentSessionId == sessionId
                    ) {
                        emptyList()
                    } else {
                        current.draftSelectedMcpServerIds
                    },
                    draftInput = if (current.editingSessionId == sessionId) "" else current.draftInput,
                    draftAttachments = if (current.editingSessionId == sessionId) {
                        emptyList()
                } else {
                    current.draftAttachments
                },
                draftWorkspaceId = if (current.editingSessionId == sessionId) null else current.draftWorkspaceId,
                editingSessionId = if (current.editingSessionId == sessionId) null else current.editingSessionId,
                editingMessageId = if (current.editingSessionId == sessionId) null else current.editingMessageId,
                pendingResponseSessionId = if (current.pendingResponseSessionId == sessionId) null else current.pendingResponseSessionId,
                pendingToolInvocations = if (current.pendingResponseSessionId == sessionId) {
                    emptyList()
                } else {
                    current.pendingToolInvocations
                },
            )
        }

        if (didUpdate) {
            if (removedSessionForPersistence) {
                persistDeleteSession(sessionId)
            } else {
                updatedSessionForPersistence?.let(::persistSessionSnapshot)
            }
        }
    }

    fun redoAgentMessage(
        sessionId: String,
        messageId: String,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return

        val snapshot = _uiState.value
        var request: SessionTurnRequest? = null
        var updatedSessionForPersistence: ChatSession? = null

        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current

            val session = current.sessions[sessionIndex]
            val messageIndex = session.messages.indexOfFirst {
                it.id == messageId && it.author == MessageAuthor.Agent
            }
            if (messageIndex < 0) return@update current

            val trimFromIndex = session.messages.resolveConversationTrimIndex(messageIndex)
            val trimmedMessages = session.messages.take(trimFromIndex)
            if (trimmedMessages.lastOrNull()?.author != MessageAuthor.User) {
                return@update current
            }

            request = SessionTurnRequest(
                sessionId = sessionId,
                settings = resolveModelSettings(
                    baseSettings = snapshot.settings,
                    providerConfigs = snapshot.providerConfigs,
                    preferredModelKey = session.selectedModelKey,
                    fallbackModelKey = resolveDefaultChatModelKey(snapshot.settings, snapshot.providerConfigs),
                ),
                requestMessages = trimmedMessages,
                selectedSkillIds = session.selectedSkillIds,
                activeSkills = session.activeSkills,
                activeMcpServerIds = session.activeMcpServerIds,
                agentModeEnabled = session.agentModeEnabled,
            )
            val updatedSessions = current.sessions.toMutableList().apply {
                removeAt(sessionIndex)
                val updatedSession = session.withMessages(trimmedMessages)
                updatedSessionForPersistence = updatedSession
                add(0, updatedSession)
            }

            current.copy(
                sessions = updatedSessions,
                currentSessionId = sessionId,
                currentScreen = AppScreen.Chat,
                draftInput = "",
                draftAttachments = emptyList(),
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
            )
        }

        val turnRequest = request ?: return
        updatedSessionForPersistence?.let { session ->
            persistSessionSnapshot(
                session = session,
                currentSessionId = sessionId,
                moveToFront = true,
            )
        }
        sessionExecutionManager.startTurn(turnRequest)
    }

    fun retryUserMessage(
        sessionId: String,
        messageId: String,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return

        val snapshot = _uiState.value
        var request: SessionTurnRequest? = null
        var updatedSessionForPersistence: ChatSession? = null

        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current

            val session = current.sessions[sessionIndex]
            val userMessage = session.messages.firstOrNull {
                it.id == messageId && it.author == MessageAuthor.User
            } ?: return@update current

            val retryMessage = userMessage.copy(
                id = "user-${System.currentTimeMillis()}",
                createdAtMillis = System.currentTimeMillis(),
                branchGroup = null,
            )
            val branchedMessages = createEditedMessageBranch(
                messages = session.messages,
                messageId = messageId,
                replacement = retryMessage,
            ) ?: return@update current
            val updatedSession = session.withMessages(branchedMessages)
            updatedSessionForPersistence = updatedSession
            val updatedSessions = current.sessions.toMutableList().apply {
                removeAt(sessionIndex)
                add(0, updatedSession)
            }

            request = SessionTurnRequest(
                sessionId = sessionId,
                settings = resolveModelSettings(
                    baseSettings = snapshot.settings,
                    providerConfigs = snapshot.providerConfigs,
                    preferredModelKey = updatedSession.selectedModelKey,
                    fallbackModelKey = resolveDefaultChatModelKey(snapshot.settings, snapshot.providerConfigs),
                ),
                requestMessages = updatedSession.messages,
                selectedSkillIds = updatedSession.selectedSkillIds,
                activeSkills = updatedSession.activeSkills,
                activeMcpServerIds = updatedSession.activeMcpServerIds,
                agentModeEnabled = updatedSession.agentModeEnabled,
            )

            current.copy(
                sessions = updatedSessions,
                currentSessionId = sessionId,
                currentScreen = AppScreen.Chat,
                draftInput = "",
                draftAttachments = emptyList(),
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
            )
        }

        val turnRequest = request ?: return
        updatedSessionForPersistence?.let { session ->
            persistSessionSnapshot(
                session = session,
                currentSessionId = sessionId,
                moveToFront = true,
            )
        }
        sessionExecutionManager.startTurn(turnRequest)
    }

    fun switchUserMessageBranch(
        sessionId: String,
        messageId: String,
        delta: Int,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return
        var didUpdate = false
        var updatedSessionForPersistence: ChatSession? = null

        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current
            val session = current.sessions[sessionIndex]
            val updatedMessages = switchMessageBranch(
                messages = session.messages,
                messageId = messageId,
                delta = delta,
            ) ?: return@update current
            didUpdate = true
            val updatedSession = session.withMessages(updatedMessages)
            updatedSessionForPersistence = updatedSession
            val updatedSessions = current.sessions.toMutableList().apply {
                set(sessionIndex, updatedSession)
            }
            current.copy(sessions = updatedSessions)
        }

        if (didUpdate) {
            updatedSessionForPersistence?.let(::persistSessionSnapshot)
        }
    }

    fun saveSettings(
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
        language: AppLanguage,
        themeMode: AppThemeMode,
        defaultChatModelKey: String,
        defaultTitleModelKey: String,
        defaultNamingModelKey: String,
    ) {
        viewModelScope.launch {
            val currentState = _uiState.value
            val modelOptions = currentState.providerConfigs.availableModelOptions()
            val resolvedDefaultChatModelKey = resolveStoredOrAutomaticModelKey(
                modelKey = defaultChatModelKey,
                options = modelOptions,
                purpose = AutomaticModelPurpose.Chat,
            )
            val compatibilitySettings = resolveModelSettings(
                baseSettings = currentState.settings.copy(
                    provider = provider,
                    apiKey = apiKey.trim(),
                    baseUrl = baseUrl.trim(),
                    modelId = modelId.trim(),
                ),
                providerConfigs = currentState.providerConfigs,
                preferredModelKey = resolvedDefaultChatModelKey,
                fallbackModelKey = resolvedDefaultChatModelKey,
            )
            settingsRepository.updateSettings(
                currentState.settings.copy(
                    provider = compatibilitySettings.provider,
                    apiKey = compatibilitySettings.apiKey,
                    baseUrl = compatibilitySettings.baseUrl,
                    modelId = compatibilitySettings.modelId,
                    basicFunctionCallingCompatibilityMode =
                        compatibilitySettings.basicFunctionCallingCompatibilityMode,
                    systemPrompt = systemPrompt,
                    tavilyApiKey = tavilyApiKey.trim(),
                    llmInactivityReconnectTimeoutSeconds =
                        normalizeLlmInactivityReconnectTimeoutSeconds(
                            llmInactivityReconnectTimeoutSeconds
                        ),
                    keepTasksRunningInBackground = keepTasksRunningInBackground,
                    notifyOnTaskCompletion = notifyOnTaskCompletion,
                    agentModeAuthorizationEnabled = agentModeAuthorizationEnabled,
                    agentModeAuthorizationMethod = agentModeAuthorizationMethod,
                    language = language,
                    themeMode = themeMode,
                    defaultChatModelKey = normalizeSelectableModelKey(defaultChatModelKey, modelOptions),
                    defaultTitleModelKey = normalizeSelectableModelKey(defaultTitleModelKey, modelOptions),
                    defaultNamingModelKey = normalizeSelectableModelKey(defaultNamingModelKey, modelOptions),
                )
            )
        }
    }

    // 鈹€鈹€ Multi-Provider methods 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    fun updateAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.updateLanguage(language)
        }
    }

    fun updateAppThemeMode(themeMode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.updateThemeMode(themeMode)
        }
    }

    fun upsertProviderConfig(config: LlmProviderConfig) {
        viewModelScope.launch {
            settingsRepository.upsertProviderConfig(normalizeProviderConfig(config))
            captureAnalyticsEvent(
                event = "provider added",
                properties = mapOf(
                    "provider" to config.providerType.displayName,
                    "provider_id" to config.id,
                ),
            )
        }
    }

    fun removeProviderConfig(id: String) {
        viewModelScope.launch {
            settingsRepository.removeProviderConfig(id)
            captureAnalyticsEvent(event = "provider removed")
        }
    }

    fun setProviderEnabled(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            settingsRepository.setProviderEnabled(id, enabled)
        }
    }

    fun setCurrentChatModelSelection(modelKey: String) {
        var didUpdate = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            if (current.currentSessionId == DraftSessionId) {
                if (current.draftSelectedModelKey == modelKey) return@update current
                didUpdate = true
                current.copy(draftSelectedModelKey = modelKey)
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val session = current.sessions[sessionIndex]
                if (session.selectedModelKey == modelKey) return@update current
                val updatedSession = session.copy(selectedModelKey = modelKey)
                val updatedSessions = current.sessions.toMutableList().apply {
                    set(sessionIndex, updatedSession)
                }
                sessionIdForPersistence = current.currentSessionId
                didUpdate = true
                current.copy(sessions = updatedSessions)
            }
        }
        val persistedSessionId = sessionIdForPersistence
        if (didUpdate && persistedSessionId != null) {
            persistSessionMutation(persistedSessionId) { session ->
                if (session.selectedModelKey == modelKey) {
                    null
                } else {
                    session.copy(selectedModelKey = modelKey)
                }
            }
        }
    }

    fun fetchModels(
        config: LlmProviderConfig,
        onComplete: (List<String>) -> Unit,
    ) {
        _uiState.update { it.copy(isFetchingModels = true) }
        viewModelScope.launch {
            val result = LlmApiClient.fetchModels(config)
            _uiState.update { it.copy(isFetchingModels = false) }
            onComplete(result.models)
            if (result.error != null) {
                _transientMessages.emit("Failed to fetch models: ${result.error}")
            }
        }
    }

    private fun mergeFetchedModels(
        current: LlmProviderConfig,
        fetchedModels: List<String>,
    ): LlmProviderConfig {
        val normalizedCurrent = normalizeProviderConfig(current)
        val previousModels = normalizedCurrent.cachedModels.toSet()
        val normalizedFetched = fetchedModels
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val enabledModels = normalizedFetched.filter { modelId ->
            normalizedCurrent.enabledModelIds.contains(modelId) || !previousModels.contains(modelId)
        }
        return normalizeProviderConfig(
            normalizedCurrent.copy(
                modelId = when {
                    normalizedCurrent.modelId in normalizedFetched -> normalizedCurrent.modelId
                    normalizedFetched.isNotEmpty() -> normalizedFetched.first()
                    else -> normalizedCurrent.modelId
                },
                cachedModels = normalizedFetched,
                enabledModelIds = enabledModels,
            )
        )
    }

    fun installSkillFromDirectory(treeUri: Uri) {
        performSkillInstall {
            skillManager.installSkillFromDirectory(treeUri)
        }
    }

    fun installSkillFromZip(
        zipUri: Uri,
        onComplete: (Boolean) -> Unit = {},
    ) {
        performSkillInstall(onComplete = onComplete) {
            skillManager.installSkillFromZipUri(zipUri)
        }
    }

    fun installSkillFromRemote(
        url: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            onComplete(false)
            return
        }
        performSkillInstall(onComplete = onComplete) {
            skillManager.installSkillFromRemote(trimmedUrl)
        }
    }

    fun removeSkill(skillId: String) {
        viewModelScope.launch {
            skillManager.uninstallSkill(skillId)
            captureAnalyticsEvent(
                event = "skill removed",
                properties = mapOf("skill_id" to skillId),
            )
        }
    }

    fun setSkillEnabled(
        skillId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            extensionsRepository.setSkillEnabled(skillId, enabled)
        }
    }

    fun setComposerSkillSelected(
        skillId: String,
        selected: Boolean,
    ) {
        var didUpdate = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            if (current.currentSessionId == DraftSessionId) {
                val updatedDraftSelection = updateOrderedSelection(
                    current.draftSelectedSkillIds,
                    skillId,
                    selected,
                )
                if (updatedDraftSelection == current.draftSelectedSkillIds) {
                    current
                } else {
                    didUpdate = true
                    current.copy(draftSelectedSkillIds = updatedDraftSelection)
                }
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val updatedSessions = current.sessions.toMutableList()
                val session = updatedSessions.removeAt(sessionIndex)
                val updatedSelectedSkillIds = updateOrderedSelection(
                    session.selectedSkillIds,
                    skillId,
                    selected,
                )
                val updatedActiveSkills = session.activeSkills.filter { activeSkill ->
                    updatedSelectedSkillIds.contains(activeSkill.skillId)
                }
                if (
                    updatedSelectedSkillIds == session.selectedSkillIds &&
                    updatedActiveSkills == session.activeSkills
                ) {
                    updatedSessions.add(sessionIndex, session)
                    current
                } else {
                    didUpdate = true
                    val updatedSession = session.copy(
                        selectedSkillIds = updatedSelectedSkillIds,
                        activeSkills = updatedActiveSkills,
                    )
                    sessionIdForPersistence = current.currentSessionId
                    updatedSessions.add(
                        sessionIndex.coerceAtMost(updatedSessions.size),
                        updatedSession,
                    )
                    current.copy(sessions = updatedSessions)
                }
            }
        }
        val persistedSessionId = sessionIdForPersistence
        if (didUpdate && persistedSessionId != null) {
            persistSessionMutation(persistedSessionId) { session ->
                val selectedSkillIds = updateOrderedSelection(
                    session.selectedSkillIds,
                    skillId,
                    selected,
                )
                val activeSkills = session.activeSkills.filter { activeSkill ->
                    selectedSkillIds.contains(activeSkill.skillId)
                }
                if (
                    selectedSkillIds == session.selectedSkillIds &&
                    activeSkills == session.activeSkills
                ) {
                    null
                } else {
                    session.copy(
                        selectedSkillIds = selectedSkillIds,
                        activeSkills = activeSkills,
                    )
                }
            }
        }
    }

    fun saveStreamableHttpMcpServer(
        serverId: String?,
        displayName: String,
        url: String,
        headersRaw: String,
    ) {
        val existingServer = serverId?.let(::findMcpServerById)
        val server = buildStreamableHttpMcpServerConfig(serverId, displayName, url, headersRaw, existingServer) ?: return
        viewModelScope.launch {
            extensionsRepository.upsertMcpServer(server)
            if (existingServer != null) {
                mcpClientManager.disconnect(existingServer.id)
            }
            captureAnalyticsEvent(
                event = "mcp server added",
                properties = mapOf("transport" to "streamable_http"),
            )
        }
    }

    fun saveStdIoMcpServer(
        serverId: String?,
        displayName: String,
        command: String,
        workingDirectory: String,
        environmentRaw: String,
    ) {
        val existingServer = serverId?.let(::findMcpServerById)
        val server = buildStdIoMcpServerConfig(serverId, displayName, command, workingDirectory, environmentRaw, existingServer) ?: return
        viewModelScope.launch {
            extensionsRepository.upsertMcpServer(server)
            if (existingServer != null) {
                mcpClientManager.disconnect(existingServer.id)
            }
            captureAnalyticsEvent(
                event = "mcp server added",
                properties = mapOf("transport" to "stdio"),
            )
        }
    }

    fun validateStreamableHttpMcpServer(
        serverId: String?,
        displayName: String,
        url: String,
        headersRaw: String,
        onComplete: (Boolean, String) -> Unit,
    ) {
        val existingServer = serverId?.let(::findMcpServerById)
        val server = buildStreamableHttpMcpServerConfig(serverId, displayName, url, headersRaw, existingServer)
        validateMcpServerConfig(server, "streamable_http", onComplete)
    }

    fun validateStdIoMcpServer(
        serverId: String?,
        displayName: String,
        command: String,
        workingDirectory: String,
        environmentRaw: String,
        onComplete: (Boolean, String) -> Unit,
    ) {
        val existingServer = serverId?.let(::findMcpServerById)
        val server = buildStdIoMcpServerConfig(serverId, displayName, command, workingDirectory, environmentRaw, existingServer)
        validateMcpServerConfig(server, "stdio", onComplete)
    }

    private fun validateMcpServerConfig(
        server: McpServerConfig?,
        transport: String,
        onComplete: (Boolean, String) -> Unit,
    ) {
        if (server == null) {
            onComplete(false, "字段缺失：请填写服务器名称以及必填连接信息。")
            return
        }
        val localFormatIssue = localMcpConfigIssue(server)
        if (localFormatIssue != null) {
            onComplete(false, localFormatIssue)
            return
        }
        viewModelScope.launch {
            val workspaceDirectory = workspaceFileBridge.workspaceDirectory(server.id)
            val result = mcpClientManager.validateServer(server, workspaceDirectory)
            result.fold(
                onSuccess = { summary ->
                    onComplete(true, buildMcpValidationSuccessMessage(summary))
                    captureAnalyticsEvent(
                        event = "mcp server validated",
                        properties = mapOf("transport" to transport, "success" to true),
                    )
                },
                onFailure = { throwable ->
                    onComplete(false, formatMcpValidationFailure(throwable))
                    captureAnalyticsEvent(
                        event = "mcp server validated",
                        properties = mapOf("transport" to transport, "success" to false),
                    )
                },
            )
        }
    }

    private fun buildStreamableHttpMcpServerConfig(
        serverId: String?,
        displayName: String,
        url: String,
        headersRaw: String,
        existingServer: McpServerConfig?,
    ): McpServerConfig? {
        val trimmedName = displayName.trim()
        val trimmedUrl = url.trim()
        if (trimmedName.isBlank() || trimmedUrl.isBlank()) return null
        val now = System.currentTimeMillis()
        return McpServerConfig(
            id = existingServer?.id ?: serverId ?: "mcp-$now",
            displayName = trimmedName,
            actionLabel = generateQuickActionLabel(trimmedName, trimmedUrl),
            transport = McpTransportConfig.StreamableHttp(
                url = trimmedUrl,
                headers = parseKeyValueLines(headersRaw),
            ),
            isEnabled = existingServer?.isEnabled ?: true,
            connectTimeoutMillis = existingServer?.connectTimeoutMillis ?: 15_000L,
            requestTimeoutMillis = existingServer?.requestTimeoutMillis ?: 60_000L,
            createdAtMillis = existingServer?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
    }

    private fun buildStdIoMcpServerConfig(
        serverId: String?,
        displayName: String,
        command: String,
        workingDirectory: String,
        environmentRaw: String,
        existingServer: McpServerConfig?,
    ): McpServerConfig? {
        val trimmedName = displayName.trim()
        val trimmedCommand = command.trim()
        if (trimmedName.isBlank() || trimmedCommand.isBlank()) return null
        val now = System.currentTimeMillis()
        return McpServerConfig(
            id = existingServer?.id ?: serverId ?: "mcp-$now",
            displayName = trimmedName,
            actionLabel = generateQuickActionLabel(trimmedName, trimmedCommand),
            transport = McpTransportConfig.StdIo(
                command = trimmedCommand,
                workingDirectory = workingDirectory.trim(),
                environment = parseKeyValueLines(environmentRaw),
            ),
            isEnabled = existingServer?.isEnabled ?: true,
            connectTimeoutMillis = existingServer?.connectTimeoutMillis ?: 15_000L,
            requestTimeoutMillis = existingServer?.requestTimeoutMillis ?: 60_000L,
            createdAtMillis = existingServer?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
    }

    private fun localMcpConfigIssue(server: McpServerConfig): String? {
        if (server.displayName.isBlank()) return "字段缺失：服务器名称不能为空。"
        return when (val transport = server.transport) {
            is McpTransportConfig.StreamableHttp -> {
                val url = transport.url.trim()
                when {
                    url.isBlank() -> "字段缺失：服务器 URL 不能为空。"
                    runCatching { URI(url) }.getOrNull()?.scheme !in listOf("http", "https") ->
                        "格式错误：服务器 URL 必须是 http:// 或 https:// 地址。"
                    else -> null
                }
            }

            is McpTransportConfig.StdIo -> {
                if (transport.command.trim().isBlank()) "字段缺失：启动命令不能为空。" else null
            }
        }
    }

    private fun buildMcpValidationSuccessMessage(summary: McpValidationSummary): String = buildString {
        append("配置有效：MCP 服务已正常响应")
        val serverInfo = summary.serverInfo.trim()
        if (serverInfo.isNotBlank()) {
            append("（")
            append(serverInfo)
            append("）")
        }
        append("。工具 ")
        append(summary.toolCount)
        append(" 个，资源 ")
        append(summary.resourceCount)
        append(" 个，提示词 ")
        append(summary.promptCount)
        append(" 个。")
    }

    private fun formatMcpValidationFailure(throwable: Throwable): String {
        val message = throwable.message.orEmpty().ifBlank { "MCP 服务无响应。" }
        return when {
            message.contains("Missing required field", ignoreCase = true) -> "字段缺失：${message.substringAfter(':').trim()}"
            message.contains("Format error", ignoreCase = true) -> "格式错误：${message.substringAfter(':').trim()}"
            message.contains("timed out", ignoreCase = true) || message.contains("timeout", ignoreCase = true) -> "连接超时：$message"
            message.contains("launch", ignoreCase = true) || message.contains("command", ignoreCase = true) -> "命令执行失败：$message"
            message.contains("HTTP", ignoreCase = true) -> "服务地址不可访问：$message"
            else -> "服务无响应：$message"
        }
    }

    fun removeMcpServer(serverId: String) {
        viewModelScope.launch {
            extensionsRepository.removeMcpServer(serverId)
            mcpClientManager.disconnect(serverId)
        }
    }

    fun setMcpServerEnabled(
        serverId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            extensionsRepository.setMcpServerEnabled(serverId, enabled)
            if (!enabled) {
                mcpClientManager.disconnect(serverId)
            }
        }
    }

    fun setComposerMcpServerSelected(
        serverId: String,
        selected: Boolean,
    ) {
        var didUpdate = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            if (current.currentSessionId == DraftSessionId) {
                val updatedDraftSelection = updateOrderedSelection(
                    current.draftSelectedMcpServerIds,
                    serverId,
                    selected,
                )
                if (updatedDraftSelection == current.draftSelectedMcpServerIds) {
                    current
                } else {
                    didUpdate = true
                    current.copy(draftSelectedMcpServerIds = updatedDraftSelection)
                }
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val updatedSessions = current.sessions.toMutableList()
                val session = updatedSessions.removeAt(sessionIndex)
                val updatedActiveIds = updateOrderedSelection(
                    session.activeMcpServerIds,
                    serverId,
                    selected,
                )
                if (updatedActiveIds == session.activeMcpServerIds) {
                    updatedSessions.add(sessionIndex, session)
                    current
                } else {
                    didUpdate = true
                    val updatedSession = session.copy(activeMcpServerIds = updatedActiveIds)
                    sessionIdForPersistence = current.currentSessionId
                    updatedSessions.add(
                        sessionIndex.coerceAtMost(updatedSessions.size),
                        updatedSession,
                    )
                    current.copy(sessions = updatedSessions)
                }
            }
        }
        val persistedSessionId = sessionIdForPersistence
        if (didUpdate && persistedSessionId != null) {
            persistSessionMutation(persistedSessionId) { session ->
                val activeMcpServerIds = updateOrderedSelection(
                    session.activeMcpServerIds,
                    serverId,
                    selected,
                )
                if (activeMcpServerIds == session.activeMcpServerIds) {
                    null
                } else {
                    session.copy(activeMcpServerIds = activeMcpServerIds)
                }
            }
        }
    }

    fun setComposerAgentModeSelected(selected: Boolean) {
        var didUpdate = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            if (current.currentSessionId == DraftSessionId) {
                if (current.draftAgentModeEnabled == selected) {
                    current
                } else {
                    didUpdate = true
                    current.copy(draftAgentModeEnabled = selected)
                }
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val updatedSessions = current.sessions.toMutableList()
                val session = updatedSessions.removeAt(sessionIndex)
                if (session.agentModeEnabled == selected) {
                    updatedSessions.add(sessionIndex, session)
                    current
                } else {
                    didUpdate = true
                    val updatedSession = session.copy(agentModeEnabled = selected)
                    sessionIdForPersistence = current.currentSessionId
                    updatedSessions.add(
                        sessionIndex.coerceAtMost(updatedSessions.size),
                        updatedSession,
                    )
                    current.copy(sessions = updatedSessions)
                }
            }
        }
        val persistedSessionId = sessionIdForPersistence
        if (didUpdate && persistedSessionId != null) {
            persistSessionMutation(persistedSessionId) { session ->
                if (session.agentModeEnabled == selected) {
                    null
                } else {
                    session.copy(agentModeEnabled = selected)
                }
            }
        }
        if (didUpdate) {
            captureAnalyticsEvent(
                event = "agent mode toggled",
                properties = mapOf("enabled" to selected),
            )
        }
    }

    fun sendCurrentMessage() {
        submitCurrentMessage(SessionFollowUpMode.Queue)
    }

    fun queueCurrentMessage() {
        submitCurrentMessage(SessionFollowUpMode.Queue)
    }

    fun steerCurrentMessage() {
        submitCurrentMessage(SessionFollowUpMode.Steer)
    }

    private fun submitCurrentMessage(
        runningFollowUpMode: SessionFollowUpMode,
    ) {
        val snapshot = _uiState.value
        val content = snapshot.draftInput.trim()
        val attachments = snapshot.draftAttachments

        if (content.isEmpty() && attachments.isEmpty()) return
        if (attachments.any { it.workspaceState != AttachmentWorkspaceState.Ready }) return

        val targetSessionId = snapshot.editingSessionId ?: when {
            snapshot.currentSessionId != DraftSessionId -> snapshot.currentSessionId
            !snapshot.draftWorkspaceId.isNullOrBlank() -> snapshot.draftWorkspaceId.orEmpty()
            else -> "session-${System.currentTimeMillis()}"
        }

        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            id = "user-$now",
            author = MessageAuthor.User,
            text = content,
            createdAtMillis = now,
            attachments = attachments,
        )

        if (snapshot.editingSessionId != null && sessionExecutionManager.isSessionRunning(targetSessionId)) {
            emitTransientMessage("Pause this session before editing an earlier message.")
            return
        }

        if (sessionExecutionManager.isSessionRunning(targetSessionId)) {
            if (!sessionExecutionManager.submitFollowUp(targetSessionId, userMessage, runningFollowUpMode)) {
                emitTransientMessage("This session is no longer running. Try sending again.")
                return
            }
            buildAnalyticsTurnRequest(
                snapshot = snapshot,
                sessionId = targetSessionId,
                userMessage = userMessage,
            )?.let { turnRequest ->
                captureMessageSent(
                    request = turnRequest,
                    attachments = attachments,
                    isEdit = false,
                    submissionType = runningFollowUpMode.name.lowercase(),
                )
            }
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    draftInput = "",
                    draftAttachments = emptyList(),
                    draftWorkspaceId = null,
                    editingSessionId = null,
                    editingMessageId = null,
                    showStarterPromptHint = false,
                )
            }
            return
        }

        var request: SessionTurnRequest? = null
        var requestMessages: List<ChatMessage> = emptyList()
        var requestSelectedSkillIds: List<String> = emptyList()
        var requestActiveSkills: List<ActiveSkillContext> = emptyList()
        var requestActiveMcpServerIds: List<String> = emptyList()
        var requestAgentModeEnabled = false
        var requestModelKey = ""
        var shouldGenerateSessionTitle = false
        var sessionForPersistence: ChatSession? = null

        _uiState.update { current ->
            val updatedSessions = current.sessions.toMutableList()

            if (
                current.editingSessionId != null &&
                current.editingMessageId != null
            ) {
                val editingSessionIndex = updatedSessions.indexOfFirst {
                    it.id == current.editingSessionId
                }
                if (editingSessionIndex >= 0) {
                    val editingSession = updatedSessions.removeAt(editingSessionIndex)
                    val editingMessageIndex = editingSession.messages.indexOfFirst {
                        it.id == current.editingMessageId && it.author == MessageAuthor.User
                    }
                    if (editingMessageIndex >= 0) {
                        val branchedMessages = createEditedMessageBranch(
                            messages = editingSession.messages,
                            messageId = current.editingMessageId,
                            replacement = userMessage,
                        ) ?: (editingSession.messages.take(editingMessageIndex) + userMessage)
                        val updated = editingSession.withMessages(branchedMessages)
                        sessionForPersistence = updated
                        updatedSessions.add(0, updated)
                        requestMessages = updated.messages
                        requestSelectedSkillIds = updated.selectedSkillIds
                        requestActiveSkills = updated.activeSkills
                        requestActiveMcpServerIds = updated.activeMcpServerIds
                        requestAgentModeEnabled = updated.agentModeEnabled
                        requestModelKey = updated.selectedModelKey
                    } else {
                        updatedSessions.add(editingSessionIndex, editingSession)
                    }
                }
            }

            if (requestMessages.isEmpty()) {
                val existingIndex = updatedSessions.indexOfFirst { it.id == targetSessionId }
                if (existingIndex >= 0) {
                    val existing = updatedSessions.removeAt(existingIndex)
                    val updated = existing.withMessages(existing.messages + userMessage)
                    sessionForPersistence = updated
                    updatedSessions.add(0, updated)
                    requestMessages = updated.messages
                    requestSelectedSkillIds = updated.selectedSkillIds
                    requestActiveSkills = updated.activeSkills
                    requestActiveMcpServerIds = updated.activeMcpServerIds
                    requestAgentModeEnabled = updated.agentModeEnabled
                    requestModelKey = updated.selectedModelKey
                } else {
                    val newSession = createSession(
                        id = targetSessionId,
                        messages = listOf(userMessage),
                        title = "New chat",
                        hasCustomTitle = true,
                        selectedModelKey = current.draftSelectedModelKey.ifBlank {
                            resolveDefaultChatModelKey(current.settings, current.providerConfigs)
                        },
                        selectedSkillIds = current.draftSelectedSkillIds,
                        activeMcpServerIds = current.draftSelectedMcpServerIds,
                        agentModeEnabled = current.draftAgentModeEnabled,
                    )
                    shouldGenerateSessionTitle = true
                    sessionForPersistence = newSession
                    updatedSessions.add(0, newSession)
                    requestMessages = newSession.messages
                    requestSelectedSkillIds = newSession.selectedSkillIds
                    requestActiveSkills = newSession.activeSkills
                    requestActiveMcpServerIds = newSession.activeMcpServerIds
                    requestAgentModeEnabled = newSession.agentModeEnabled
                    requestModelKey = newSession.selectedModelKey
                }
            }

            request = SessionTurnRequest(
                sessionId = targetSessionId,
                settings = resolveModelSettings(
                    baseSettings = current.settings,
                    providerConfigs = current.providerConfigs,
                    preferredModelKey = requestModelKey,
                    fallbackModelKey = resolveDefaultChatModelKey(current.settings, current.providerConfigs),
                ),
                requestMessages = requestMessages,
                selectedSkillIds = requestSelectedSkillIds,
                activeSkills = requestActiveSkills,
                activeMcpServerIds = requestActiveMcpServerIds,
                agentModeEnabled = requestAgentModeEnabled,
            )

            current.copy(
                sessions = updatedSessions,
                currentSessionId = targetSessionId,
                draftInput = "",
                draftAttachments = emptyList(),
                draftSelectedModelKey = "",
                draftSelectedSkillIds = emptyList(),
                draftSelectedMcpServerIds = emptyList(),
                draftAgentModeEnabled = false,
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
                currentScreen = AppScreen.Chat,
                showStarterPromptHint = false,
            )
        }

        val turnRequest = request ?: return
        sessionForPersistence?.let { session ->
            persistSessionSnapshot(
                session = session,
                currentSessionId = targetSessionId,
                moveToFront = true,
            )
        }
        captureMessageSent(
            request = turnRequest,
            attachments = attachments,
            isEdit = snapshot.editingSessionId != null,
            submissionType = "new_turn",
        )
        if (shouldGenerateSessionTitle) {
            generateSessionTitle(
                sessionId = targetSessionId,
                seedMessage = userMessage,
                settings = turnRequest.settings,
            )
        }
        sessionExecutionManager.startTurn(turnRequest)
    }

    private fun handleTurnEvent(
        event: SessionTurnEvent,
    ) {
        captureTurnCompleted(event)
        val isSuccessfulAssistantReply = event.outcome == SessionTurnOutcome.Success
        if (
            shouldMarkOnboardingCompleted(
                settings = _uiState.value.settings,
                isSuccessfulAssistantReply = isSuccessfulAssistantReply,
            )
        ) {
            viewModelScope.launch {
                settingsRepository.updateOnboardingCompletedVersion(CurrentOnboardingVersion)
            }
        }
        if (
            shouldRevealFollowUpTourCard(
                isAwaitingFollowUpTour = _uiState.value.awaitingFollowUpTour,
                isSuccessfulAssistantReply = isSuccessfulAssistantReply,
            )
        ) {
            scheduleFollowUpTourAfterFirstReply()
        }
        _uiState.update { current ->
            val unviewedCompletedSessionIds = when {
                event.sessionId == current.currentSessionId -> current.unviewedCompletedSessionIds - event.sessionId
                event.outcome != SessionTurnOutcome.Neutral -> current.unviewedCompletedSessionIds + event.sessionId
                else -> current.unviewedCompletedSessionIds
            }
            current.copy(unviewedCompletedSessionIds = unviewedCompletedSessionIds)
        }
    }

    private suspend fun buildPendingDraftAttachment(
        uri: Uri,
    ): ChatAttachment? {
        val metadata = readAttachmentMetadata(uri) ?: return null
        return ChatAttachment(
            id = "attachment-${System.currentTimeMillis()}-${uri.hashCode()}",
            uri = uri.toString(),
            name = metadata.displayName,
            mimeType = metadata.mimeType,
            sizeBytes = metadata.sizeBytes,
            kind = metadata.kind,
            workspaceState = AttachmentWorkspaceState.Pending,
        )
    }

    private suspend fun importDraftAttachmentToWorkspace(
        attachment: ChatAttachment,
        sessionId: String,
    ) {
        val startedAtMillis = System.currentTimeMillis()
        Log.i(
            AetherViewModelLogTag,
            "Importing attachment '${attachment.name}' (${attachment.uri}) into workspace session=$sessionId " +
                "attachment_id=${attachment.id} source_size=${attachment.sizeBytes ?: -1}",
        )
        val importResult = workspaceFileBridge.importAttachmentToWorkspace(
            sourceUri = attachment.uri.toUri(),
            sessionId = sessionId,
            attachmentId = attachment.id,
            displayName = attachment.name,
        )

        importResult.onSuccess { importedFile ->
            Log.i(
                AetherViewModelLogTag,
                "Imported attachment '${attachment.name}' into workspace path=${importedFile.absolutePath} " +
                    "bytes=${importedFile.bytesCopied} elapsed_ms=${System.currentTimeMillis() - startedAtMillis}",
            )
        }

        importResult.exceptionOrNull()?.let { throwable ->
            val message = throwable.userFacingMessage()
            Log.w(
                AetherViewModelLogTag,
                "Failed to import attachment '${attachment.name}' (${attachment.uri}) into workspace session=$sessionId " +
                    "source_size=${attachment.sizeBytes ?: -1} elapsed_ms=${System.currentTimeMillis() - startedAtMillis}: $message",
                throwable,
            )
            emitTransientMessage(
                if (_uiState.value.settings.language == AppLanguage.SimplifiedChinese) {
                    "文件复制到工作区失败：$message"
                } else {
                    "Couldn't copy attachment into workspace: $message"
                }
            )
        }

        var didUpdateDraftAttachment = false
        _uiState.update { current ->
            val attachmentIndex = current.draftAttachments.indexOfFirst { draftAttachment ->
                draftAttachment.id == attachment.id ||
                    (draftAttachment.uri == attachment.uri && draftAttachment.workspaceState == AttachmentWorkspaceState.Pending)
            }
            if (attachmentIndex < 0) return@update current

            didUpdateDraftAttachment = true
            val existingAttachment = current.draftAttachments[attachmentIndex]
            val updatedAttachment = importResult.fold(
                onSuccess = { importedFile ->
                    val resolvedMimeType = existingAttachment.mimeType.ifBlank {
                        workspaceFileBridge.guessMimeType(importedFile.absolutePath)
                    }
                    existingAttachment.copy(
                        mimeType = resolvedMimeType,
                        sizeBytes = existingAttachment.sizeBytes ?: importedFile.bytesCopied,
                        kind = if (resolvedMimeType.startsWith("image/")) {
                            AttachmentKind.Image
                        } else {
                            AttachmentKind.File
                        },
                        workspacePath = importedFile.absolutePath,
                        workspaceState = AttachmentWorkspaceState.Ready,
                        workspaceError = "",
                    )
                },
                onFailure = { throwable ->
                    existingAttachment.copy(
                        workspaceState = AttachmentWorkspaceState.Failed,
                        workspaceError = throwable.message
                            .orEmpty()
                            .ifBlank { "Couldn't copy this attachment into the workspace." },
                    )
                },
            )

            val updatedAttachments = current.draftAttachments.toMutableList().apply {
                set(attachmentIndex, updatedAttachment)
            }
            Log.i(
                AetherViewModelLogTag,
                "Attachment import UI state update session=$sessionId attachment_id=${attachment.id} " +
                    "index=$attachmentIndex success=${importResult.isSuccess} " +
                    "state=${updatedAttachment.workspaceState} workspace_path=${updatedAttachment.workspacePath} " +
                    "current_session=${current.currentSessionId} draft_workspace=${current.draftWorkspaceId} " +
                    "editing_session=${current.editingSessionId}",
            )
            if (importResult.isSuccess) {
                lastTermuxCommandSuccessAtMillis = System.currentTimeMillis()
            }
            val updatedTermuxSetupState = if (importResult.isSuccess && !current.termuxSetupState.isReady) {
                TermuxSetupState(TermuxSetupIssue.Ready)
            } else {
                current.termuxSetupState
            }
            current.copy(
                draftAttachments = updatedAttachments,
                draftAttachmentRevision = current.draftAttachmentRevision + 1,
                termuxSetupState = updatedTermuxSetupState,
            )
        }

        if (!didUpdateDraftAttachment) {
            val snapshot = _uiState.value
            Log.w(
                AetherViewModelLogTag,
                "Attachment import finished but draft attachment was not present for UI update " +
                    "session=$sessionId attachment_id=${attachment.id} uri=${attachment.uri} success=${importResult.isSuccess} " +
                    "current_session=${snapshot.currentSessionId} draft_workspace=${snapshot.draftWorkspaceId} " +
                    "editing_session=${snapshot.editingSessionId} draft_count=${snapshot.draftAttachments.size}",
            )
        } else if (importResult.isSuccess) {
            emitTransientMessage(
                if (_uiState.value.settings.language == AppLanguage.SimplifiedChinese) {
                    "附件已复制到工作区"
                } else {
                    "Attachment copied to workspace"
                }
            )
        }
    }

    private fun normalizeDraftAttachmentForEditing(
        attachment: ChatAttachment,
    ): ChatAttachment = if (attachment.workspacePath.isNotBlank()) {
        attachment.copy(
            workspaceState = AttachmentWorkspaceState.Ready,
            workspaceError = "",
        )
    } else {
        attachment.copy(
            workspaceState = AttachmentWorkspaceState.Failed,
            workspaceError = "This attachment is missing its workspace copy. Re-upload it before sending.",
        )
    }

    private fun readAttachmentMetadata(
        uri: Uri,
    ): AttachmentMetadata? {
        val resolver = getApplication<Application>().contentResolver
        var mimeType = resolver.getType(uri).orEmpty()
        var displayName = uri.lastPathSegment ?: "Attachment"
        var sizeBytes: Long? = null

        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }

                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        if (mimeType.isBlank()) {
            mimeType = workspaceFileBridge.guessMimeType(displayName)
        }

        return AttachmentMetadata(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            kind = if (mimeType.startsWith("image/")) AttachmentKind.Image else AttachmentKind.File,
        )
    }

    private fun scheduleFollowUpTourAfterFirstReply() {
        _uiState.update { current ->
            current.copy(
                awaitingFollowUpTour = false,
                showFollowUpTourCard = true,
            )
        }
        viewModelScope.launch {
            delay(FollowUpTourAutoOpenDelayMillis)
            _uiState.update { current ->
                if (current.currentScreen != AppScreen.Chat) {
                    current
                } else {
                    current.copy(
                        currentScreen = AppScreen.Onboarding,
                        isOnboardingReplay = true,
                        onboardingStep = OnboardingStep.TermuxSetup,
                        onboardingReturnScreen = AppScreen.Chat,
                        awaitingFollowUpTour = false,
                        showFollowUpTourCard = false,
                    )
                }
            }
        }
    }

    private fun persistCurrentSessionId(sessionId: String) {
        chatStateStore.update { persisted ->
            persisted.copy(currentSessionId = sessionId)
        }
    }

    private fun replacePersistedChats(
        sessions: List<ChatSession>,
        currentSessionId: String,
    ) {
        chatStateStore.update { persisted ->
            persisted.copy(
                sessions = sessions,
                currentSessionId = currentSessionId,
            )
        }
    }

    private fun persistSessionSnapshot(
        session: ChatSession,
        currentSessionId: String? = null,
        moveToFront: Boolean = false,
    ) {
        chatStateStore.update { persisted ->
            val currentIndex = persisted.sessions.indexOfFirst { it.id == session.id }
            val updatedSessions = persisted.sessions.toMutableList().apply {
                if (currentIndex >= 0) {
                    removeAt(currentIndex)
                }
                val insertIndex = when {
                    moveToFront -> 0
                    currentIndex >= 0 -> currentIndex.coerceAtMost(size)
                    else -> 0
                }
                add(insertIndex, session)
            }
            persisted.copy(
                sessions = updatedSessions,
                currentSessionId = currentSessionId ?: persisted.currentSessionId,
            )
        }
    }

    private fun persistSessionMutation(
        sessionId: String,
        transform: (ChatSession) -> ChatSession?,
    ) {
        chatStateStore.update { persisted ->
            val sessionIndex = persisted.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update persisted
            val updatedSession = transform(persisted.sessions[sessionIndex]) ?: return@update persisted
            val updatedSessions = persisted.sessions.toMutableList().apply {
                set(sessionIndex, updatedSession)
            }
            persisted.copy(sessions = updatedSessions)
        }
    }

    private fun persistDeleteSession(sessionId: String) {
        persistDeleteSessions(setOf(sessionId))
    }

    private fun persistDeleteSessions(sessionIds: Set<String>) {
        if (sessionIds.isEmpty()) return
        chatStateStore.update { persisted ->
            val updatedSessions = persisted.sessions.filterNot { it.id in sessionIds }
            persisted.copy(
                sessions = updatedSessions,
                currentSessionId = if (persisted.currentSessionId in sessionIds) {
                    DraftSessionId
                } else {
                    persisted.currentSessionId
                },
            )
        }
    }

    private fun cleanupDeletedSessionWorkspaces(sessionIds: Set<String>) {
        sessionIds.forEach { sessionId ->
            viewModelScope.launch(Dispatchers.IO) {
                workspaceFileBridge.deleteWorkspace(sessionId)
                    .onFailure { throwable ->
                        Log.w(
                            AetherViewModelLogTag,
                            "Failed to delete Termux workspace for session=$sessionId: ${throwable.message.orEmpty()}",
                            throwable,
                        )
                    }
            }
        }
    }

    private fun persistPrunedSessionSelections(
        enabledSkillIds: Set<String>,
        enabledMcpServerIds: Set<String>,
    ) {
        chatStateStore.update { persisted ->
            persisted.copy(
                sessions = persisted.sessions.map { session ->
                    val selectedSkillIds = session.selectedSkillIds.filter(enabledSkillIds::contains)
                    val activeSkills = session.activeSkills.filter { activeSkill ->
                        selectedSkillIds.contains(activeSkill.skillId)
                    }
                    val activeMcpServerIds = session.activeMcpServerIds.filter(enabledMcpServerIds::contains)
                    if (
                        selectedSkillIds == session.selectedSkillIds &&
                        activeSkills == session.activeSkills &&
                        activeMcpServerIds == session.activeMcpServerIds
                    ) {
                        session
                    } else {
                        session.copy(
                            selectedSkillIds = selectedSkillIds,
                            activeSkills = activeSkills,
                            activeMcpServerIds = activeMcpServerIds,
                        )
                    }
                }
            )
        }
    }

    private fun buildAnalyticsTurnRequest(
        snapshot: AetherUiState,
        sessionId: String,
        userMessage: ChatMessage,
    ): SessionTurnRequest? {
        val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return null
        return SessionTurnRequest(
            sessionId = sessionId,
            settings = resolveModelSettings(
                baseSettings = snapshot.settings,
                providerConfigs = snapshot.providerConfigs,
                preferredModelKey = session.selectedModelKey,
                fallbackModelKey = resolveDefaultChatModelKey(snapshot.settings, snapshot.providerConfigs),
            ),
            requestMessages = session.messages + userMessage,
            selectedSkillIds = session.selectedSkillIds,
            activeSkills = session.activeSkills,
            activeMcpServerIds = session.activeMcpServerIds,
            agentModeEnabled = session.agentModeEnabled,
        )
    }

    private fun captureMessageSent(
        request: SessionTurnRequest,
        attachments: List<ChatAttachment>,
        isEdit: Boolean,
        submissionType: String,
    ) {
        val modelProperties = modelUsageProperties(request, source = "message")
        captureAnalyticsEvent(
            event = "message sent",
            properties = mapOf(
                "has_attachments" to attachments.isNotEmpty(),
                "attachment_count" to attachments.size,
                "agent_mode_enabled" to request.agentModeEnabled,
                "skill_count" to request.selectedSkillIds.size,
                "mcp_server_count" to request.activeMcpServerIds.size,
                "is_edit" to isEdit,
                "submission_type" to submissionType,
            ) + modelProperties,
        )
        captureAnalyticsEvent(
            event = "model used",
            properties = modelProperties + mapOf(
                "has_attachments" to attachments.isNotEmpty(),
                "attachment_count" to attachments.size,
                "is_edit" to isEdit,
                "submission_type" to submissionType,
            ),
        )
        captureAgentModeStarted(
            request = request,
            isEdit = isEdit,
            submissionType = submissionType,
        )
    }

    private fun captureTurnCompleted(event: SessionTurnEvent) {
        captureAnalyticsEvent(
            event = "conversation turn completed",
            properties = mapOf(
                "outcome" to event.outcome.name.lowercase(),
                "tool_call_count" to event.toolCallCount,
                "distinct_tool_count" to event.distinctToolCount,
                "tool_names" to event.toolNames,
                "has_tool_calls" to (event.toolCallCount > 0),
                "duration_millis" to (event.durationMillis ?: 0L),
            ),
        )
    }

    private fun trackTermuxSetupState(
        setupState: TermuxSetupState,
        source: String,
    ) {
        if (!_uiState.value.settings.privacyPolicyAccepted) return
        if (setupState.issue != TermuxSetupIssue.NotInstalled &&
            lastTrackedTermuxDetectedIssue != setupState.issue
        ) {
            lastTrackedTermuxDetectedIssue = setupState.issue
            captureAnalyticsEvent(
                event = "termux detected",
                properties = mapOf(
                    "source" to source,
                    "issue" to setupState.issue.name.lowercase(),
                    "is_ready" to setupState.isReady,
                ),
            )
        }

        val setupSource = pendingTermuxSetupSource ?: return
        if (!setupState.isReady) return
        pendingTermuxSetupSource = null
        captureAnalyticsEvent(
            event = "termux setup completed",
            properties = mapOf(
                "source" to setupSource,
                "detected_source" to source,
                "issue" to setupState.issue.name.lowercase(),
            ),
        )
    }

    private fun captureAgentModeStarted(
        request: SessionTurnRequest,
        isEdit: Boolean,
        submissionType: String,
    ) {
        if (!request.agentModeEnabled) return
        val properties = modelUsageProperties(request, source = "agent_mode") + mapOf(
            "authorization_enabled" to request.settings.agentModeAuthorizationEnabled,
            "authorization_method" to request.settings.agentModeAuthorizationMethod.storageValue,
            "is_edit" to isEdit,
            "submission_type" to submissionType,
        )
        if (request.settings.agentModeAuthorizationEnabled) {
            captureAnalyticsEvent(
                event = "agent mode started",
                properties = properties,
            )
        } else {
            captureAnalyticsEvent(
                event = "agent mode failed",
                properties = properties + mapOf("reason" to "authorization_disabled"),
            )
        }
    }

    private fun modelUsageProperties(
        request: SessionTurnRequest,
        source: String,
    ): Map<String, Any> = mapOf(
        "model" to request.settings.modelId.trim(),
        "provider" to request.settings.provider.displayName,
        "provider_type" to request.settings.provider.storageValue,
        "source" to source,
        "agent_mode_enabled" to request.agentModeEnabled,
        "skill_count" to request.selectedSkillIds.size,
        "mcp_server_count" to request.activeMcpServerIds.size,
    )

    private fun captureAnalyticsEvent(
        event: String,
        properties: Map<String, Any> = emptyMap(),
    ) {
        AetherAnalytics.capture(event = event, properties = properties)
    }

    private fun normalizeProviderConfig(
        config: LlmProviderConfig,
    ): LlmProviderConfig {
        val models = (config.cachedModels + config.enabledModelIds + config.modelId)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val normalizedModelId = config.modelId.trim().ifBlank {
            models.firstOrNull() ?: config.providerType.defaultModelId
        }
        val normalizedModels = (models + normalizedModelId)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val normalizedEnabledModels = config.enabledModelIds
            .map(String::trim)
            .filter { it.isNotEmpty() && normalizedModels.contains(it) }
            .distinct()
        return config.copy(
            providerId = config.providerId.trim(),
            baseUrl = config.baseUrl.trim(),
            modelId = normalizedModelId,
            cachedModels = normalizedModels,
            enabledModelIds = normalizedEnabledModels,
        )
    }

    private fun createSession(
        id: String,
        messages: List<ChatMessage>,
        title: String? = null,
        hasCustomTitle: Boolean = false,
        selectedModelKey: String = "",
        selectedSkillIds: List<String> = emptyList(),
        activeSkills: List<ActiveSkillContext> = emptyList(),
        activeMcpServerIds: List<String> = emptyList(),
        agentModeEnabled: Boolean = false,
    ): ChatSession {
        val metadata = deriveSessionMetadata(messages)
        return ChatSession(
            id = id,
            title = title ?: metadata.title,
            preview = metadata.preview,
            hasCustomTitle = hasCustomTitle,
            messages = messages,
            selectedModelKey = selectedModelKey,
            selectedSkillIds = selectedSkillIds,
            activeSkills = activeSkills,
            activeMcpServerIds = activeMcpServerIds,
            agentModeEnabled = agentModeEnabled,
        )
    }

    private fun ChatSession.withMessages(messages: List<ChatMessage>): ChatSession {
        val syncedMessages = syncActiveBranches(messages)
        val metadata = deriveSessionMetadata(syncedMessages)
        return copy(
            title = if (hasCustomTitle) title else metadata.title,
            preview = metadata.preview,
            messages = syncedMessages,
        )
    }

    private suspend fun resolveSelectedActiveSkills(
        selectedSkillIds: List<String>,
        existingActiveSkills: List<ActiveSkillContext>,
    ): List<ActiveSkillContext> {
        if (selectedSkillIds.isEmpty()) return emptyList()
        val installedSkillsById = _uiState.value.installedSkills
            .filter { it.isEnabled }
            .associateBy { it.id }
        return buildList {
            selectedSkillIds.distinct().forEach { skillId ->
                val installedSkill = installedSkillsById[skillId] ?: return@forEach
                val refreshedSkill = skillManager.buildActiveSkillContext(installedSkill)
                    .getOrElse { return@forEach }
                add(refreshedSkill)
            }
        }
    }

    private fun upsertActiveSkillContext(
        activeSkills: List<ActiveSkillContext>,
        activeSkill: ActiveSkillContext,
    ): List<ActiveSkillContext> {
        val existingIndex = activeSkills.indexOfFirst { it.skillId == activeSkill.skillId }
        if (existingIndex < 0) return activeSkills + activeSkill
        return activeSkills.toMutableList().apply {
            set(existingIndex, activeSkill)
        }
    }

    private fun resolveSelectedMcpServers(
        selectedServerIds: List<String>,
    ): List<McpServerConfig> {
        if (selectedServerIds.isEmpty()) return emptyList()
        val enabledServersById = _uiState.value.mcpServers
            .filter { it.isEnabled }
            .associateBy { it.id }
        return selectedServerIds.distinct().mapNotNull(enabledServersById::get)
    }

    private fun setSessionSelectedSkillIds(
        sessionId: String,
        selectedSkillIds: List<String>,
    ) {
        updateSession(sessionId) { session ->
            if (session.selectedSkillIds == selectedSkillIds) {
                null
            } else {
                session.copy(selectedSkillIds = selectedSkillIds)
            }
        }
    }

    private fun setSessionActiveSkills(
        sessionId: String,
        activeSkills: List<ActiveSkillContext>,
    ) {
        updateSession(sessionId) { session ->
            if (session.activeSkills == activeSkills) {
                null
            } else {
                session.copy(activeSkills = activeSkills)
            }
        }
    }

    private fun setSessionActiveMcpServerIds(
        sessionId: String,
        activeMcpServerIds: List<String>,
    ) {
        updateSession(sessionId) { session ->
            if (session.activeMcpServerIds == activeMcpServerIds) {
                null
            } else {
                session.copy(activeMcpServerIds = activeMcpServerIds)
            }
        }
    }

    private fun updateSession(
        sessionId: String,
        transform: (ChatSession) -> ChatSession?,
    ) {
        var didUpdate = false
        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current
            val updatedSessions = current.sessions.toMutableList()
            val session = updatedSessions.removeAt(sessionIndex)
            val updatedSession = transform(session)
            if (updatedSession == null) {
                updatedSessions.add(sessionIndex, session)
                current
            } else {
                didUpdate = true
                updatedSessions.add(
                    sessionIndex.coerceAtMost(updatedSessions.size),
                    updatedSession,
                )
                current.copy(sessions = updatedSessions)
            }
        }
        if (didUpdate) {
            persistSessionMutation(sessionId, transform)
        }
    }

    private fun findMcpServerById(serverId: String): McpServerConfig? =
        _uiState.value.mcpServers.firstOrNull { it.id == serverId }

    private fun updateOrderedSelection(
        currentSelection: List<String>,
        id: String,
        selected: Boolean,
    ): List<String> = when {
        selected && currentSelection.contains(id) -> currentSelection
        selected -> currentSelection + id
        else -> currentSelection.filterNot { it == id }
    }

    private fun deriveSessionMetadata(messages: List<ChatMessage>): SessionMetadata {
        val title = messages
            .firstOrNull { it.author == MessageAuthor.User }
            ?.summaryText()
            .orEmpty()
            .ifBlank { "New chat" }
            .take(36)

        val preview = messages
            .lastOrNull()
            ?.summaryText()
            .orEmpty()
            .ifBlank { "No messages yet." }
            .take(96)

        return SessionMetadata(title = title, preview = preview)
    }

    private fun generateSessionTitle(
        sessionId: String,
        seedMessage: ChatMessage,
        settings: AppSettings,
    ) {
        val titleInput = buildTitleGenerationInput(seedMessage)
        if (titleInput.isBlank()) return

        viewModelScope.launch {
            val providerConfigs = _uiState.value.providerConfigs
            val titleSettings = resolveModelSettings(
                baseSettings = settings,
                providerConfigs = providerConfigs,
                preferredModelKey = resolveDefaultTitleModelKey(settings, providerConfigs),
                fallbackModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
            )
            if (!isProviderSetupValid(titleSettings.provider, titleSettings.apiKey, titleSettings.baseUrl, titleSettings.modelId)) {
                return@launch
            }
            val titleResult = client.createChatCompletion(
                settings = titleSettings,
                systemPrompt = SessionTitleSystemPrompt,
                conversation = listOf(buildProviderUserMessage(titleSettings, titleInput)),
            )
            val title = titleResult.getOrNull()
                ?.assistantText
                ?.sanitizeGeneratedSessionTitle()
                .orEmpty()

            if (title.isBlank()) return@launch

            updateSession(sessionId) { session ->
                val firstUserMessage = session.messages.firstOrNull { it.author == MessageAuthor.User }
                if (firstUserMessage?.id != seedMessage.id) {
                    null
                } else {
                    session.copy(
                        title = title,
                        hasCustomTitle = true,
                    )
                }
            }
        }
    }

    private fun buildTitleGenerationInput(
        message: ChatMessage,
    ): String = buildString {
        val text = message.text.trim()
        if (text.isNotBlank()) {
            appendLine("First user message:")
            appendLine(text)
        }
        if (message.attachments.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("Attachments:")
            message.attachments.forEach { attachment ->
                appendLine("- ${attachment.name}")
            }
        }
    }.trim()

    private fun buildProviderUserMessage(
        settings: AppSettings,
        text: String,
    ): JSONObject = when (settings.provider) {
        LlmProvider.OpenAiResponses -> JSONObject().apply {
            put("role", "user")
            put(
                "content",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "input_text")
                        put("text", text)
                    }
                ),
            )
        }

        LlmProvider.OpenAiCompatible -> JSONObject().apply {
            put("role", "user")
            put("content", text)
        }

        LlmProvider.VertexExpress -> JSONObject().apply {
            put("role", "user")
            put(
                "parts",
                JSONArray().put(
                    JSONObject().apply {
                        put("text", text)
                    }
                ),
            )
        }

        LlmProvider.AnthropicMessages -> JSONObject().apply {
            put("role", "user")
            put(
                "content",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    }
                ),
            )
        }
    }

    private fun String.sanitizeGeneratedSessionTitle(): String =
        lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("Title:")
                    .removePrefix("title:")
                    .trim()
                    .trim('"', '\'', '`')
            }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trimEnd('.', '!', '?')
            .take(36)

    private fun ensureDraftWorkspaceId(): String {
        val snapshot = _uiState.value
        return snapshot.editingSessionId ?: when {
            snapshot.currentSessionId != DraftSessionId -> snapshot.currentSessionId
            !snapshot.draftWorkspaceId.isNullOrBlank() -> snapshot.draftWorkspaceId.orEmpty()
            else -> {
                val generatedId = "session-${System.currentTimeMillis()}"
                _uiState.update { current ->
                    if (current.currentSessionId == DraftSessionId && current.draftWorkspaceId.isNullOrBlank()) {
                        current.copy(draftWorkspaceId = generatedId)
                    } else {
                        current
                    }
                }
                _uiState.value.draftWorkspaceId ?: generatedId
            }
        }
    }

    private fun ChatMessage.summaryText(): String {
        val textSummary = text.trim()
        if (textSummary.isNotBlank()) return textSummary
        reasoningTrace?.let { trace ->
            trace.chunks.lastOrNull { it.detail.isNotBlank() || it.title.isNotBlank() }?.let { chunk ->
                return chunk.detail.ifBlank { chunk.title }
            }
            return if (trace.toolInvocations.isNotEmpty()) {
                "Thought and used ${trace.toolInvocations.size} tools"
            } else {
                "Thought"
            }
        }
        if (toolInvocations.isNotEmpty()) {
            return if (toolInvocations.size == 1) {
                when (toolInvocations.first().toolName.lowercase()) {
                    "bash" -> "Ran bash command"
                    "fetch_bash_output" -> "Fetched bash output"
                    "kill_bash" -> "Stopped bash command"
                    "sleep" -> "Waited"
                    else -> "Used ${toolInvocations.first().toolName}"
                }
            } else {
                "Used ${toolInvocations.size} tools"
            }
        }
        if (attachments.isEmpty()) return "Empty message"
        if (attachments.size == 1) return attachments.first().name
        return "${attachments.size} attachments"
    }

    private fun List<ChatMessage>.resolveConversationTrimIndex(
        targetIndex: Int,
    ): Int {
        val targetMessage = getOrNull(targetIndex) ?: return targetIndex
        val responseGroupId = targetMessage.responseGroupId
        if (
            targetMessage.author != MessageAuthor.Agent ||
            responseGroupId.isNullOrBlank()
        ) {
            return targetIndex
        }
        if (responseGroupId.isNullOrBlank()) {
            return resolveLegacyAssistantGroupStartIndex(targetIndex)
        }
        val groupStartIndex = indexOfFirst { message ->
            message.author == MessageAuthor.Agent && message.responseGroupId == responseGroupId
        }
        return if (groupStartIndex >= 0) groupStartIndex else targetIndex
    }

    private fun List<ChatMessage>.resolveLegacyAssistantGroupStartIndex(
        targetIndex: Int,
    ): Int {
        val targetMessage = getOrNull(targetIndex) ?: return targetIndex
        if (targetMessage.author != MessageAuthor.Agent) return targetIndex
        var groupStartIndex = targetIndex
        var expectedCreatedAtMillis = targetMessage.createdAtMillis
        while (groupStartIndex > 0) {
            val previous = this[groupStartIndex - 1]
            if (
                previous.author != MessageAuthor.Agent ||
                !previous.responseGroupId.isNullOrBlank() ||
                previous.createdAtMillis != expectedCreatedAtMillis - 1
            ) {
                break
            }
            groupStartIndex -= 1
            expectedCreatedAtMillis = previous.createdAtMillis
        }
        return groupStartIndex
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    private fun parseKeyValueLines(rawValue: String): List<com.zhousl.aether.data.McpKeyValue> =
        rawValue.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val separatorIndex = trimmed.indexOf('=')
                if (separatorIndex <= 0) return@mapNotNull null
                com.zhousl.aether.data.McpKeyValue(
                    key = trimmed.substring(0, separatorIndex).trim(),
                    value = trimmed.substring(separatorIndex + 1).trim(),
                )
            }
            .toList()

    private fun performSkillInstall(
        onComplete: (Boolean) -> Unit = {},
        installBlock: suspend () -> Result<InstalledSkill>,
    ) {
        viewModelScope.launch {
            val result = installBlock()
            result
                .onSuccess { installedSkill ->
                    emitTransientMessage("Installed skill: ${installedSkill.name}")
                    captureAnalyticsEvent(
                        event = "skill installed",
                        properties = mapOf(
                            "skill_id" to installedSkill.id,
                            "skill_name" to installedSkill.name,
                        ),
                    )
                }
                .onFailure { throwable ->
                    emitTransientMessage(
                        "Couldn't install skill: ${throwable.userFacingMessage()}"
                    )
                }
            onComplete(result.isSuccess)
        }
    }

    private fun maybeCheckForUpdates(settings: AppSettings) {
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheckAtMillis < AppUpdateCheckIntervalMillis) {
            return
        }
        checkForUpdates(manual = false, forceAvailable = false)
    }

    private fun checkForUpdates(
        manual: Boolean,
        forceAvailable: Boolean,
    ) {
        if (_uiState.value.appUpdate.isChecking) return

        _uiState.update { current ->
            current.copy(
                appUpdate = current.appUpdate.copy(
                    isChecking = true,
                    showAvailableDialog = if (manual) false else current.appUpdate.showAvailableDialog,
                )
            )
        }
        viewModelScope.launch {
            val checkedAtMillis = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching { appUpdateManager.fetchLatestRelease() }
            }
            settingsRepository.updateLastUpdateCheckAtMillis(checkedAtMillis)

            result
                .onSuccess { release ->
                    val hasUpdate = forceAvailable || isVersionNewer(
                        remoteVersion = release.versionName,
                        currentVersion = BuildConfig.VERSION_NAME,
                    )
                    _uiState.update { current ->
                        current.copy(
                            appUpdate = current.appUpdate.copy(
                                isChecking = false,
                                availableRelease = if (hasUpdate) release else current.appUpdate.availableRelease,
                                showAvailableDialog = hasUpdate,
                            )
                        )
                    }
                    if (!hasUpdate && manual) {
                        emitTransientMessage("Aether is up to date.")
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { current ->
                        current.copy(
                            appUpdate = current.appUpdate.copy(isChecking = false)
                        )
                    }
                    if (manual) {
                        emitTransientMessage("Couldn't check for updates: ${throwable.userFacingMessage()}")
                    }
                }
        }
    }

    private fun writeTextToUri(
        uri: Uri,
        text: String,
    ): Boolean = runCatching {
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: return false
        true
    }.getOrDefault(false)

    private fun readTextFromUri(uri: Uri): String =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("Unable to read the selected file.")

    private fun buildDiagnosticLogText(snapshot: AetherUiState): String = buildString {
        appendLine("Aether diagnostic log")
        appendLine("generatedAtMillis=${System.currentTimeMillis()}")
        appendLine("versionName=${BuildConfig.VERSION_NAME}")
        appendLine("versionCode=${BuildConfig.VERSION_CODE}")
        appendLine("debug=${BuildConfig.DEBUG}")
        appendLine("screen=${snapshot.currentScreen}")
        appendLine("currentSessionId=${snapshot.currentSessionId}")
        appendLine("sessionCount=${snapshot.sessions.size}")
        appendLine("runningSessionCount=${snapshot.sessionExecutionStates.values.count { it.isRunning }}")
        appendLine("provider=${snapshot.settings.provider.storageValue}")
        appendLine("providerConfigCount=${snapshot.providerConfigs.size}")
        appendLine("skillCount=${snapshot.installedSkills.size}")
        appendLine("mcpServerCount=${snapshot.mcpServers.size}")
        appendLine("termuxReady=${snapshot.termuxSetupState.isReady}")
        appendLine("rootReady=${snapshot.rootSetupState.isReady}")
        appendLine("agentModeAuthorized=${snapshot.agentModeAuthorizationState.isReady}")
        appendLine()
        appendLine("logcat:")
        append(readLogcatDump())
    }

    private fun readLogcatDump(): String {
        val pid = android.os.Process.myPid().toString()
        val commands = listOf(
            listOf("logcat", "-d", "-v", "threadtime", "-t", "2000", "--pid", pid),
            listOf("logcat", "-d", "-v", "threadtime", "-t", "2000"),
        )

        commands.forEach { command ->
            val output = runCatching { runLogcatCommand(command) }.getOrNull()
            if (!output.isNullOrBlank()) {
                return output
            }
        }

        return "Unable to read logcat output."
    }

    private fun runLogcatCommand(command: List<String>): String {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
        if (!process.waitFor(LogcatReadTimeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            return "Logcat command timed out: ${command.joinToString(" ")}"
        }
        return output.ifBlank {
            "Logcat command returned no output: ${command.joinToString(" ")}"
        }
    }

    private fun buildFullAppExportJson(snapshot: AetherUiState): JSONObject =
        JSONObject().apply {
            put("schemaVersion", 2)
            put("exportType", "app")
            put("exportedAtMillis", System.currentTimeMillis())
            put("settings", snapshot.settings.toJson())
            put("providerConfigs", JSONArray(serializeProviderConfigs(snapshot.providerConfigs)))
            put("sessions", JSONArray(serializeChatSessions(snapshot.sessions.map { it.copy(activeSkills = emptyList()) })))
            put("currentSessionId", snapshot.currentSessionId)
            put("skillBundles", skillManager.exportSkillBundles(snapshot.installedSkills))
            put("mcpServers", JSONArray(serializeMcpServerConfigs(snapshot.mcpServers)))
        }

    private fun parseFullAppImport(
        json: JSONObject,
        installedSkills: List<InstalledSkill>,
    ): ImportedAppData {
        val mcpServers = parseMcpServerConfigs(json.optJSONArray("mcpServers")?.toString().orEmpty())
        val sessions = sanitizeImportedSessions(
            sessions = parseChatSessions(json.optJSONArray("sessions")?.toString().orEmpty()),
            installedSkillIds = installedSkills.map { it.id }.toSet(),
            mcpServerIds = mcpServers.map { it.id }.toSet(),
        )
        return ImportedAppData(
            settings = parseImportedSettings(json.optJSONObject("settings")),
            providerConfigs = parseProviderConfigs(json.optJSONArray("providerConfigs")?.toString().orEmpty()),
            sessions = sessions,
            currentSessionId = json.optString("currentSessionId")
                .takeIf { id -> id == DraftSessionId || sessions.any { it.id == id } }
                ?: DraftSessionId,
            installedSkills = installedSkills,
            mcpServers = mcpServers,
        )
    }

    private fun sanitizeImportedSessions(
        sessions: List<ChatSession>,
        installedSkillIds: Set<String>,
        mcpServerIds: Set<String>,
    ): List<ChatSession> =
        sessions.map { session ->
            session.copy(
                selectedSkillIds = session.selectedSkillIds.filter(installedSkillIds::contains),
                activeSkills = emptyList(),
                activeMcpServerIds = session.activeMcpServerIds.filter(mcpServerIds::contains),
            )
        }

    private fun AppSettings.toJson(): JSONObject = JSONObject().apply {
        put("provider", provider.storageValue)
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("modelId", modelId)
        put("systemPrompt", systemPrompt)
        put("tavilyApiKey", tavilyApiKey)
        put("llmInactivityReconnectTimeoutSeconds", llmInactivityReconnectTimeoutSeconds)
        put("keepTasksRunningInBackground", keepTasksRunningInBackground)
        put("notifyOnTaskCompletion", notifyOnTaskCompletion)
        put("agentModeAuthorizationEnabled", agentModeAuthorizationEnabled)
        put("agentModeAuthorizationMethod", agentModeAuthorizationMethod.storageValue)
        put("language", language.storageValue)
        put("themeMode", themeMode.storageValue)
        put("defaultChatModelKey", defaultChatModelKey)
        put("defaultTitleModelKey", defaultTitleModelKey)
        put("defaultNamingModelKey", defaultNamingModelKey)
        put(
            "unsupportedParallelToolCallProviderKeys",
            JSONArray().apply { unsupportedParallelToolCallProviderKeys.forEach(::put) },
        )
        put("basicFunctionCallingCompatibilityMode", basicFunctionCallingCompatibilityMode)
        put("onboardingSeenVersion", onboardingSeenVersion)
        put("onboardingCompletedVersion", onboardingCompletedVersion)
        put("privacyPolicyAccepted", privacyPolicyAccepted)
        put("lastUpdateCheckAtMillis", lastUpdateCheckAtMillis)
    }

    private fun parseImportedSettings(json: JSONObject?): AppSettings {
        if (json == null) return AppSettings()
        val defaults = AppSettings()
        return AppSettings(
            provider = LlmProvider.fromStorage(json.optString("provider")),
            apiKey = json.optString("apiKey", defaults.apiKey),
            baseUrl = json.optString("baseUrl", defaults.baseUrl),
            modelId = json.optString("modelId", defaults.modelId),
            systemPrompt = json.optString("systemPrompt", defaults.systemPrompt),
            tavilyApiKey = json.optString("tavilyApiKey", defaults.tavilyApiKey),
            llmInactivityReconnectTimeoutSeconds = normalizeLlmInactivityReconnectTimeoutSeconds(
                json.optInt(
                    "llmInactivityReconnectTimeoutSeconds",
                    defaults.llmInactivityReconnectTimeoutSeconds,
                )
            ),
            keepTasksRunningInBackground = json.optBoolean(
                "keepTasksRunningInBackground",
                defaults.keepTasksRunningInBackground,
            ),
            notifyOnTaskCompletion = json.optBoolean(
                "notifyOnTaskCompletion",
                defaults.notifyOnTaskCompletion,
            ),
            agentModeAuthorizationEnabled = json.optBoolean(
                "agentModeAuthorizationEnabled",
                defaults.agentModeAuthorizationEnabled,
            ),
            agentModeAuthorizationMethod = AgentModeAuthorizationMethod.fromStorage(
                json.optString("agentModeAuthorizationMethod"),
                defaults.agentModeAuthorizationMethod,
            ),
            language = AppLanguage.fromStorage(
                json.optString("language"),
                defaults.language,
            ),
            themeMode = AppThemeMode.fromStorage(json.optString("themeMode")),
            defaultChatModelKey = json.optString("defaultChatModelKey", defaults.defaultChatModelKey),
            defaultTitleModelKey = json.optString("defaultTitleModelKey", defaults.defaultTitleModelKey),
            defaultNamingModelKey = json.optString("defaultNamingModelKey", defaults.defaultNamingModelKey),
            unsupportedParallelToolCallProviderKeys = parseImportedStringArray(
                json.optJSONArray("unsupportedParallelToolCallProviderKeys")
            ),
            basicFunctionCallingCompatibilityMode = json.optBoolean(
                "basicFunctionCallingCompatibilityMode",
                defaults.basicFunctionCallingCompatibilityMode,
            ),
            onboardingSeenVersion = json.optInt("onboardingSeenVersion", defaults.onboardingSeenVersion),
            onboardingCompletedVersion = json.optInt(
                "onboardingCompletedVersion",
                defaults.onboardingCompletedVersion,
            ),
            privacyPolicyAccepted = json.optBoolean(
                "privacyPolicyAccepted",
                defaults.privacyPolicyAccepted,
            ),
            lastUpdateCheckAtMillis = json.optLong(
                "lastUpdateCheckAtMillis",
                defaults.lastUpdateCheckAtMillis,
            ),
        )
    }

    private fun parseImportedStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }.distinct()
    }

    private fun emitTransientMessage(message: String) {
        _transientMessages.tryEmit(message)
    }

    private fun termuxBackgroundLaunchMessage(): String =
        if (_uiState.value.settings.language == AppLanguage.SimplifiedChinese) {
            "Aether 打开 Termux 是为了让它在后台运行。"
        } else {
            "Aether opened Termux so it can keep running in the background."
        }

    private fun Throwable.userFacingMessage(): String =
        message?.trim().takeUnless { it.isNullOrBlank() } ?: javaClass.simpleName

    private data class SessionMetadata(
        val title: String,
        val preview: String,
    )

    private data class AttachmentMetadata(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val kind: AttachmentKind,
    )

    private data class ImportedAppData(
        val settings: AppSettings,
        val providerConfigs: List<LlmProviderConfig>,
        val sessions: List<ChatSession>,
        val currentSessionId: String,
        val installedSkills: List<InstalledSkill>,
        val mcpServers: List<McpServerConfig>,
    )
}
