package com.zhousl.aether.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.zhousl.aether.data.InstalledSkill
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AgentLoopState
import com.zhousl.aether.data.AgentModeDisplayState
import com.zhousl.aether.data.AgentTaskState
import com.zhousl.aether.data.AgentTaskStatus
import com.zhousl.aether.data.ChatToolGroups
import com.zhousl.aether.data.McpServerConfig
import com.zhousl.aether.data.McpTransportConfig
import com.zhousl.aether.data.PendingSessionInput
import com.zhousl.aether.data.ProviderModelOption
import com.zhousl.aether.data.SessionFollowUpMode
import com.zhousl.aether.data.normalizeChatToolGroups
import com.zhousl.aether.data.quickActionLabel
import com.zhousl.aether.termux.TermuxSetupState
import com.zhousl.aether.voice.VoicePlaybackState
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherBackgroundGradientTop
import com.zhousl.aether.ui.theme.AetherError
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnPrimary
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherScrim
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import com.zhousl.aether.ui.theme.AetherSurfaceHigher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import org.json.JSONObject

private sealed interface ConversationListItem {
    val key: String
    val contentType: String

    data class Message(
        val message: ChatMessage,
    ) : ConversationListItem {
        override val key: String = message.id
        override val contentType: String = when (message.author) {
            MessageAuthor.User -> "conversation-message-user"
            MessageAuthor.Agent -> "conversation-message-agent"
        }
    }

    data class AssistantGroup(
        val messages: List<ChatMessage>,
    ) : ConversationListItem {
        override val key: String = messages.firstOrNull()?.responseGroupId
            ?: messages.firstOrNull()?.id
            ?: "assistant-group"
        override val contentType: String = "conversation-assistant-group"
    }
}

private val ConversationTopFadeHeight = 42.dp
private val ComposerCardShape = RoundedCornerShape(26.dp)
private val ComposerFocusedCardShape = RoundedCornerShape(28.dp)
private val ChatGptPromptShape = RoundedCornerShape(30.dp)
private val ChatGptControlShadow = Color(0x14000000)
private val ChatGptComposerShadow = Color(0x18000000)
private val ChatGptMotionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)
private const val ComposerFocusTransitionMillis = 160
private const val ImeInsetStabilizationMillis = 24L
private const val ConversationMarkdownPrewarmWindow = 4
internal const val ConversationInitialMessageWindow = 40
internal const val ConversationMessagePageSize = 24
private const val ConversationLoadOlderScrollThreshold = 2
private const val ConversationOlderMessagesHintKey = "conversation-older-messages-hint"
private val ImeStabilizationMinVisibleHeight = 24.dp

internal data class ConversationScreenState(
    val conversationStateKey: String,
    val currentSession: ChatSession? = null,
    val currentTaskSnapshot: TaskWorkbenchSnapshot? = null,
    val currentSessionTitle: String,
    val currentSessionPinned: Boolean,
    val currentSessionArchived: Boolean,
    val messages: List<ChatMessage>,
    val workspaceDirectory: String,
    val pendingToolInvocations: List<ChatToolInvocation>,
    val pendingToolInvocationStateKey: String,
    val pendingResponseBlocks: List<AssistantResponseBlock>,
    val pendingAssistantText: String,
    val pendingStatusText: String,
    val pendingStatusDetail: String,
    val pendingInputs: List<PendingSessionInput>,
    val loopState: AgentLoopState = AgentLoopState(),
    val taskState: AgentTaskState,
    val inputValue: String,
    val draftAttachments: List<ChatAttachment>,
    val draftAttachmentRevision: Long,
    val modelOptions: List<ProviderModelOption>,
    val selectedModelKey: String,
    val availableSkills: List<InstalledSkill>,
    val availableMcpServers: List<McpServerConfig>,
    val selectedSkillIds: List<String>,
    val selectedMcpServerIds: List<String>,
    val agentModeAvailable: Boolean,
    val agentModeSelected: Boolean,
    val enabledToolGroups: List<String>,
    val planModeSelected: Boolean,
    val goalModeSelected: Boolean,
    val agentModeDisplayState: AgentModeDisplayState,
    val allowRootImageRead: Boolean,
    val isEditing: Boolean,
    val termuxSetupState: TermuxSetupState,
    val showResumeSetupBanner: Boolean,
    val showStarterPromptHint: Boolean,
    val showTermuxSetupNotice: Boolean,
    val isSending: Boolean,
    val voiceInputState: VoiceInputUiState = VoiceInputUiState(),
    val voicePlaybackEnabled: Boolean = false,
    val voicePlaybackState: VoicePlaybackState = VoicePlaybackState.Idle,
)

internal data class ConversationScreenActions(
    val onInputChanged: (String) -> Unit,
    val onRenameThread: (String) -> Unit,
    val onTogglePinned: () -> Unit,
    val onArchiveThread: () -> Unit,
    val onRestoreThread: () -> Unit,
    val onExportThread: () -> Unit,
    val onDeleteThread: () -> Unit,
    val onModelSelected: (String) -> Unit,
    val onRemoveDraftAttachment: (String) -> Unit,
    val onSetSkillSelected: (String, Boolean) -> Unit,
    val onSetMcpServerSelected: (String, Boolean) -> Unit,
    val onSetAgentModeSelected: (Boolean) -> Unit,
    val onSetToolGroupEnabled: (String, Boolean) -> Unit,
    val onSetPlanModeEnabled: (Boolean) -> Unit,
    val onSetGoalModeEnabled: (Boolean) -> Unit,
    val onSlashCommand: (ComposerSlashCommandId, String) -> Unit,
    val onCancelEdit: () -> Unit,
    val onSend: () -> Unit,
    val onVoiceInputPressed: () -> Unit,
    val onVoiceInputReleased: () -> Unit,
    val onCancelVoiceInput: () -> Unit,
    val onQueueFollowUp: () -> Unit,
    val onSteerFollowUp: () -> Unit,
    val onMenu: () -> Unit,
    val onOpenInbox: () -> Unit,
    val onNewChat: () -> Unit,
    val onPickImages: () -> Unit,
    val onPickFiles: () -> Unit,
    val onSaveAttachment: (ChatAttachment) -> Unit,
    val onOpenLink: (String) -> Unit,
    val onEditMessage: (String) -> Unit,
    val onDeleteMessage: (String) -> Unit,
    val onRedoAgentMessage: (String) -> Unit,
    val onRetryUserMessage: (String) -> Unit,
    val onSwitchUserMessageBranch: (String, Int) -> Unit,
    val onCopyMessage: (ChatMessage) -> Unit,
    val onRequestTermuxPermission: () -> Unit,
    val onOpenAppPermissions: () -> Unit,
    val onOpenTermuxSettings: () -> Unit,
    val onOpenTermux: () -> Unit,
    val onInstallTermux: () -> Unit,
    val onRefreshTermuxSetup: () -> Unit,
    val onPauseGeneration: () -> Unit,
    val onResumeOnboarding: () -> Unit,
    val onDismissStarterPromptHint: () -> Unit,
    val onReadMessage: (String, String) -> Unit = { _, _ -> },
    val onStopReading: () -> Unit = {},
)

/**
 * 将聚合后的会话状态和动作转发到内部渲染实现。
 */
@Composable
internal fun ConversationScreen(
    state: ConversationScreenState,
    actions: ConversationScreenActions,
) {
    ConversationScreen(
        conversationStateKey = state.conversationStateKey,
        currentSession = state.currentSession,
        currentTaskSnapshot = state.currentTaskSnapshot,
        currentSessionTitle = state.currentSessionTitle,
        currentSessionPinned = state.currentSessionPinned,
        currentSessionArchived = state.currentSessionArchived,
        messages = state.messages,
        workspaceDirectory = state.workspaceDirectory,
        pendingToolInvocations = state.pendingToolInvocations,
        pendingToolInvocationStateKey = state.pendingToolInvocationStateKey,
        pendingResponseBlocks = state.pendingResponseBlocks,
        pendingAssistantText = state.pendingAssistantText,
        pendingStatusText = state.pendingStatusText,
        pendingStatusDetail = state.pendingStatusDetail,
        pendingInputs = state.pendingInputs,
        loopState = state.loopState,
        taskState = state.taskState,
        inputValue = state.inputValue,
        draftAttachments = state.draftAttachments,
        draftAttachmentRevision = state.draftAttachmentRevision,
        modelOptions = state.modelOptions,
        selectedModelKey = state.selectedModelKey,
        availableSkills = state.availableSkills,
        availableMcpServers = state.availableMcpServers,
        selectedSkillIds = state.selectedSkillIds,
        selectedMcpServerIds = state.selectedMcpServerIds,
        agentModeAvailable = state.agentModeAvailable,
        agentModeSelected = state.agentModeSelected,
        enabledToolGroups = state.enabledToolGroups,
        planModeSelected = state.planModeSelected,
        goalModeSelected = state.goalModeSelected,
        agentModeDisplayState = state.agentModeDisplayState,
        allowRootImageRead = state.allowRootImageRead,
        isEditing = state.isEditing,
        termuxSetupState = state.termuxSetupState,
        showResumeSetupBanner = state.showResumeSetupBanner,
        showStarterPromptHint = state.showStarterPromptHint,
        showTermuxSetupNotice = state.showTermuxSetupNotice,
        onInputChanged = actions.onInputChanged,
        onRenameThread = actions.onRenameThread,
        onTogglePinned = actions.onTogglePinned,
        onArchiveThread = actions.onArchiveThread,
        onRestoreThread = actions.onRestoreThread,
        onExportThread = actions.onExportThread,
        onDeleteThread = actions.onDeleteThread,
        onModelSelected = actions.onModelSelected,
        onRemoveDraftAttachment = actions.onRemoveDraftAttachment,
        onSetSkillSelected = actions.onSetSkillSelected,
        onSetMcpServerSelected = actions.onSetMcpServerSelected,
        onSetAgentModeSelected = actions.onSetAgentModeSelected,
        onSetToolGroupEnabled = actions.onSetToolGroupEnabled,
        onSetPlanModeEnabled = actions.onSetPlanModeEnabled,
        onSetGoalModeEnabled = actions.onSetGoalModeEnabled,
        onSlashCommand = actions.onSlashCommand,
        onCancelEdit = actions.onCancelEdit,
        onSend = actions.onSend,
        onVoiceInputPressed = actions.onVoiceInputPressed,
        onVoiceInputReleased = actions.onVoiceInputReleased,
        onQueueFollowUp = actions.onQueueFollowUp,
        onSteerFollowUp = actions.onSteerFollowUp,
        onMenu = actions.onMenu,
        onOpenInbox = actions.onOpenInbox,
        onNewChat = actions.onNewChat,
        onPickImages = actions.onPickImages,
        onPickFiles = actions.onPickFiles,
        onSaveAttachment = actions.onSaveAttachment,
        onOpenLink = actions.onOpenLink,
        onEditMessage = actions.onEditMessage,
        onDeleteMessage = actions.onDeleteMessage,
        onRedoAgentMessage = actions.onRedoAgentMessage,
        onRetryUserMessage = actions.onRetryUserMessage,
        onSwitchUserMessageBranch = actions.onSwitchUserMessageBranch,
        onCopyMessage = actions.onCopyMessage,
        onRequestTermuxPermission = actions.onRequestTermuxPermission,
        onOpenAppPermissions = actions.onOpenAppPermissions,
        onOpenTermuxSettings = actions.onOpenTermuxSettings,
        onOpenTermux = actions.onOpenTermux,
        onInstallTermux = actions.onInstallTermux,
        onRefreshTermuxSetup = actions.onRefreshTermuxSetup,
        onPauseGeneration = actions.onPauseGeneration,
        onResumeOnboarding = actions.onResumeOnboarding,
        onDismissStarterPromptHint = actions.onDismissStarterPromptHint,
        isSending = state.isSending,
        voiceInputState = state.voiceInputState,
        onCancelVoiceInput = actions.onCancelVoiceInput,
        voicePlaybackEnabled = state.voicePlaybackEnabled,
        voicePlaybackState = state.voicePlaybackState,
        onReadMessage = actions.onReadMessage,
        onStopReading = actions.onStopReading,
    )
}

/**
 * 否则延迟 [ImeInsetStabilizationMillis] 毫秒确认输入法确实已收起，再回落到 0.dp。
 * 该延迟用于过滤弹层切换造成的瞬时 IME 抖动。
 */
@Composable
private fun rememberStableImeBottom(focused: Boolean): State<Dp> {
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val stable = remember { mutableStateOf(0.dp) }
    LaunchedEffect(imeBottom, focused) {
        if (focused && imeBottom > ImeStabilizationMinVisibleHeight) {
            stable.value = imeBottom
        } else {
            delay(ImeInsetStabilizationMillis)
            if (!focused || imeBottom <= ImeStabilizationMinVisibleHeight) {
                stable.value = 0.dp
            }
        }
    }
    return stable
}

internal enum class PendingGenerationIndicator {
    None,
    Thinking,
    Status,
}

internal fun pendingGenerationIndicator(
    isSending: Boolean,
    pendingAssistantText: String,
    pendingStatusText: String,
    hasVisiblePendingReasoning: Boolean = false,
): PendingGenerationIndicator = when {
    !isSending -> PendingGenerationIndicator.None
    pendingStatusText.isNotBlank() -> PendingGenerationIndicator.Status
    hasVisiblePendingReasoning -> PendingGenerationIndicator.None
    pendingAssistantText.isBlank() -> PendingGenerationIndicator.Thinking
    else -> PendingGenerationIndicator.None
}

internal fun hasVisibleReasoningStatus(trace: ReasoningTrace): Boolean =
    trace.latestStatusText.isNotBlank() ||
        trace.rawText.isNotBlank() ||
        trace.hasTimelineContent ||
        trace.completedAtMillis != null

private fun topOverlayBodyGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherBackground.copy(alpha = 0.98f),
        0.28f to AetherBackground.copy(alpha = 0.92f),
        0.58f to AetherBackground.copy(alpha = 0.52f),
        0.82f to AetherBackground.copy(alpha = 0.18f),
        1.0f to Color.Transparent,
    )
)

private fun topOverlayTailGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherBackground.copy(alpha = 0.10f),
        0.42f to AetherBackground.copy(alpha = 0.04f),
        1.0f to Color.Transparent,
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationScreen(
    conversationStateKey: String,
    currentSession: ChatSession?,
    currentTaskSnapshot: TaskWorkbenchSnapshot?,
    currentSessionTitle: String,
    currentSessionPinned: Boolean,
    currentSessionArchived: Boolean,
    messages: List<ChatMessage>,
    workspaceDirectory: String,
    pendingToolInvocations: List<ChatToolInvocation>,
    pendingToolInvocationStateKey: String,
    pendingResponseBlocks: List<AssistantResponseBlock>,
    pendingAssistantText: String,
    pendingStatusText: String,
    pendingStatusDetail: String,
    pendingInputs: List<PendingSessionInput>,
    loopState: AgentLoopState = AgentLoopState(),
    taskState: AgentTaskState,
    inputValue: String,
    draftAttachments: List<ChatAttachment>,
    draftAttachmentRevision: Long,
    modelOptions: List<ProviderModelOption>,
    selectedModelKey: String,
    availableSkills: List<InstalledSkill>,
    availableMcpServers: List<McpServerConfig>,
    selectedSkillIds: List<String>,
    selectedMcpServerIds: List<String>,
    agentModeAvailable: Boolean,
    agentModeSelected: Boolean,
    enabledToolGroups: List<String>,
    planModeSelected: Boolean,
    goalModeSelected: Boolean,
    agentModeDisplayState: AgentModeDisplayState,
    allowRootImageRead: Boolean = false,
    isEditing: Boolean,
    termuxSetupState: TermuxSetupState,
    showResumeSetupBanner: Boolean,
    showStarterPromptHint: Boolean,
    showTermuxSetupNotice: Boolean,
    onInputChanged: (String) -> Unit,
    onRenameThread: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onArchiveThread: () -> Unit,
    onRestoreThread: () -> Unit,
    onExportThread: () -> Unit,
    onDeleteThread: () -> Unit,
    onModelSelected: (String) -> Unit,
    onRemoveDraftAttachment: (String) -> Unit,
    onSetSkillSelected: (String, Boolean) -> Unit,
    onSetMcpServerSelected: (String, Boolean) -> Unit,
    onSetAgentModeSelected: (Boolean) -> Unit,
    onSetToolGroupEnabled: (String, Boolean) -> Unit,
    onSetPlanModeEnabled: (Boolean) -> Unit,
    onSetGoalModeEnabled: (Boolean) -> Unit,
    onSlashCommand: (ComposerSlashCommandId, String) -> Unit,
    onCancelEdit: () -> Unit,
    onSend: () -> Unit,
    onQueueFollowUp: () -> Unit,
    onSteerFollowUp: () -> Unit,
    onMenu: () -> Unit,
    onOpenInbox: () -> Unit,
    onNewChat: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onVoiceInputPressed: () -> Unit,
    onVoiceInputReleased: () -> Unit,
    onSaveAttachment: (ChatAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRedoAgentMessage: (String) -> Unit,
    onRetryUserMessage: (String) -> Unit,
    onSwitchUserMessageBranch: (String, Int) -> Unit,
    onCopyMessage: (ChatMessage) -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onPauseGeneration: () -> Unit,
    onResumeOnboarding: () -> Unit,
    onDismissStarterPromptHint: () -> Unit,
    isSending: Boolean,
    voiceInputState: VoiceInputUiState,
    onCancelVoiceInput: () -> Unit,
    voicePlaybackEnabled: Boolean,
    voicePlaybackState: VoicePlaybackState,
    onReadMessage: (String, String) -> Unit,
    onStopReading: () -> Unit,
) {
    val listState = remember(conversationStateKey) { LazyListState() }
    val coroutineScope = rememberCoroutineScope()
    val strings = rememberAetherStrings()
    var loadedFromMessageIndex by rememberSaveable(conversationStateKey) { mutableIntStateOf(-1) }
    var isLoadingOlderMessages by remember(conversationStateKey) { mutableStateOf(false) }
    LaunchedEffect(conversationStateKey, messages.size) {
        loadedFromMessageIndex = when {
            messages.isEmpty() -> 0
            loadedFromMessageIndex < 0 -> initialConversationMessageStartIndex(messages.size)
            loadedFromMessageIndex >= messages.size -> initialConversationMessageStartIndex(messages.size)
            else -> loadedFromMessageIndex
        }
    }
    val visibleMessages by remember(messages, loadedFromMessageIndex) {
        derivedStateOf {
            if (messages.isEmpty()) {
                emptyList()
            } else {
                val start = clampConversationMessageStartIndex(loadedFromMessageIndex, messages.size)
                messages.subList(start, messages.size)
            }
        }
    }
    val conversationItems = remember(visibleMessages) { buildConversationListItems(visibleMessages) }
    val hasOlderMessages = loadedFromMessageIndex > 0
    var previewAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    var topBarBodyHeightPx by remember { mutableIntStateOf(0) }
    var composerBodyHeightPx by remember { mutableIntStateOf(0) }
    var composerFocused by remember { mutableStateOf(false) }
    var didInitialScrollToBottom by remember(conversationStateKey) { mutableStateOf(false) }
    var hasUnseenLatestContent by rememberSaveable(conversationStateKey) { mutableStateOf(false) }
    // 生成期间是否自动跟随到最新内容：用户向上滚动时关闭，回到底部时恢复。
    var autoFollowEnabled by remember(conversationStateKey) { mutableStateOf(true) }
    val density = LocalDensity.current
    val fallbackTopBarBodyHeight = with(density) {
        WindowInsets.statusBars.getTop(this).toDp() + 68.dp
    }
    val topBarBodyHeight = with(density) {
        if (topBarBodyHeightPx > 0) topBarBodyHeightPx.toDp() else fallbackTopBarBodyHeight
    }
    val composerBodyHeight = with(density) {
        if (composerBodyHeightPx > 0) composerBodyHeightPx.toDp() else 112.dp
    }
    val conversationBottomClearance = composerBodyHeight + 72.dp
    val synchronizedImeOffsetPx = WindowInsets.ime.getBottom(density).coerceAtLeast(0)

    fun dispatchComposerDragToConversation(dragAmountPx: Float) {
        listState.dispatchRawDelta(-dragAmountPx)
    }

    suspend fun scrollToConversationBottom() {
        val lastItemIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastItemIndex >= 0) {
            listState.scrollToItem(lastItemIndex)
        }
    }

    fun jumpToConversationBottom() {
        hasUnseenLatestContent = false
        autoFollowEnabled = true
        coroutineScope.launch {
            scrollToConversationBottom()
        }
    }

    suspend fun loadOlderConversationMessages() {
        if (!hasOlderMessages || isLoadingOlderMessages) return
        isLoadingOlderMessages = true
        try {
            val anchorIndex = listState.firstVisibleItemIndex
            val anchorOffset = listState.firstVisibleItemScrollOffset
            val anchorKey = conversationItems.getOrNull(anchorIndex)?.let { item ->
                if (item.key == ConversationOlderMessagesHintKey) {
                    conversationItems.getOrNull(anchorIndex + 1)?.key
                } else {
                    item.key
                }
            } ?: return

            loadedFromMessageIndex = previousConversationMessageStartIndex(loadedFromMessageIndex)
            loadedFromMessageIndex = clampConversationMessageStartIndex(loadedFromMessageIndex, messages.size)

            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it > 0 }

            val updatedItems = buildConversationListItems(
                messages.subList(loadedFromMessageIndex, messages.size),
            )
            val newIndex = updatedItems.indexOfFirst { it.key == anchorKey }
            if (newIndex >= 0) {
                listState.scrollToItem(newIndex, anchorOffset)
            }
        } finally {
            isLoadingOlderMessages = false
        }
    }

    LaunchedEffect(conversationStateKey, conversationItems.size) {
        if (didInitialScrollToBottom || conversationItems.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        scrollToConversationBottom()
        hasUnseenLatestContent = false
        didInitialScrollToBottom = true
    }

    LaunchedEffect(listState, conversationStateKey, hasOlderMessages) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.isScrollInProgress,
                loadedFromMessageIndex,
            )
        }
            .distinctUntilChanged()
            .collect { (firstIndex, scrolling, startIndex) ->
                if (scrolling || startIndex <= 0 || isLoadingOlderMessages) return@collect
                val threshold = if (hasOlderMessages) {
                    ConversationLoadOlderScrollThreshold
                } else {
                    0
                }
                if (firstIndex > threshold) return@collect
                loadOlderConversationMessages()
            }
    }

    LaunchedEffect(conversationItems, listState) {
        snapshotFlow {
            val visibleMessageIndices = listState.layoutInfo.visibleItemsInfo.mapNotNull { itemInfo ->
                val itemIndex = itemInfo.index - if (hasOlderMessages) 1 else 0
                itemIndex.takeIf { it in conversationItems.indices }
            }
            if (visibleMessageIndices.isEmpty()) {
                null
            } else {
                visibleMessageIndices.minOrNull()!! to visibleMessageIndices.maxOrNull()!!
            }
        }
            .distinctUntilChanged()
            .collectLatest { visibleRange ->
                if (listState.isScrollInProgress) return@collectLatest
                val (firstVisible, lastVisible) = visibleRange ?: return@collectLatest
                val startIndex = (firstVisible - ConversationMarkdownPrewarmWindow).coerceAtLeast(0)
                val endIndex = (lastVisible + ConversationMarkdownPrewarmWindow)
                    .coerceAtMost(conversationItems.lastIndex)
                if (startIndex > endIndex) return@collectLatest

                val markdowns = conversationItems
                    .subList(startIndex, endIndex + 1)
                    .flatMap { it.markdownsForPrewarm() }
                if (markdowns.isEmpty()) return@collectLatest

                withContext(Dispatchers.Default) {
                    markdowns.forEach(::prewarmMarkdownContent)
                }
            }
    }

    // 用户主动向上滚动（查看历史）时关闭自动跟随，避免生成时强行把视图拉回底部。
    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val scrolledUp = index < lastIndex || (index == lastIndex && offset < lastOffset - 2)
                if (scrolledUp && !listState.isAtConversationBottom()) {
                    autoFollowEnabled = false
                }
                lastIndex = index
                lastOffset = offset
            }
    }

    // 仅在滚动停止且处于底部时恢复自动跟随，避免滑动过程中误判“在底部”而与手势拉锯。
    LaunchedEffect(listState) {
        snapshotFlow {
            !listState.isScrollInProgress && listState.isAtConversationBottom()
        }
            .distinctUntilChanged()
            .collect { idleAtBottom ->
                if (!idleAtBottom) return@collect
                autoFollowEnabled = true
                hasUnseenLatestContent = false
                if (listState.layoutInfo.totalItemsCount > 0) {
                    scrollToConversationBottom()
                }
            }
    }

    // 轻量内容版本号：流式更新时只统计长度/数量，避免在主线程拼接大字符串。
    val streamingContentRevision by remember {
        derivedStateOf {
            var revision = conversationItems.size
            revision = revision * 31 + conversationItems.lastOrNull()?.key.hashCode()
            revision = revision * 31 + pendingAssistantText.length
            revision = revision * 31 + pendingInputs.size
            revision = revision * 31 + loopState.hashCode()
            pendingResponseBlocks.forEach { block ->
                revision = when (block) {
                    is AssistantResponseBlock.Text -> revision * 31 + block.text.length
                    is AssistantResponseBlock.ToolGroup -> revision * 31 + block.toolInvocations.sumOf { it.outputJson.length }
                    is AssistantResponseBlock.Reasoning -> revision * 31 + block.trace.rawText.length +
                        block.trace.toolInvocations.sumOf { it.outputJson.length }
                }
            }
            revision = revision * 31 + pendingToolInvocations.sumOf { it.outputJson.length }
            revision = revision * 31 + if (isSending) 1 else 0
            revision
        }
    }

    LaunchedEffect(listState, didInitialScrollToBottom) {
        if (!didInitialScrollToBottom) return@LaunchedEffect
        snapshotFlow {
            Triple(streamingContentRevision, autoFollowEnabled, listState.isScrollInProgress)
        }
            .distinctUntilChanged()
            .collectLatest { (_, follow, scrolling) ->
                if (listState.layoutInfo.totalItemsCount == 0) {
                    hasUnseenLatestContent = false
                    return@collectLatest
                }
                if (!follow) {
                    hasUnseenLatestContent = true
                    return@collectLatest
                }
                if (scrolling) return@collectLatest
                // 合并同一帧内的多次流式更新，降低 scrollToItem 调用频率。
                delay(16)
                if (!autoFollowEnabled || listState.isScrollInProgress) return@collectLatest
                scrollToConversationBottom()
                hasUnseenLatestContent = false
            }
    }
    val showScrollToLatestButton by remember(messages, listState) {
        derivedStateOf {
            messages.isNotEmpty() &&
                listState.layoutInfo.totalItemsCount > 0 &&
                !listState.isAtConversationBottom()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(AetherBackgroundGradientTop, AetherBackground, AetherSurface)
                    )
                )
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = -synchronizedImeOffsetPx.toFloat()
                    },
            ) {
                if (messages.isEmpty()) {
                    ConversationEmptyState(
                        modifier = Modifier.padding(
                            top = topBarBodyHeight + 20.dp,
                            bottom = conversationBottomClearance,
                        ),
                        inputFocused = composerFocused,
                        showResumeSetupBanner = showResumeSetupBanner,
                        onResumeOnboarding = onResumeOnboarding,
                        onStarterPromptSelected = onInputChanged,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = topBarBodyHeight + 10.dp,
                            bottom = conversationBottomClearance,
                        ),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        if (hasOlderMessages) {
                            item(
                                key = ConversationOlderMessagesHintKey,
                                contentType = "older-messages-hint",
                            ) {
                                ConversationOlderMessagesHint(
                                    hiddenMessageCount = loadedFromMessageIndex,
                                    isLoading = isLoadingOlderMessages,
                                    onLoadMore = {
                                        coroutineScope.launch { loadOlderConversationMessages() }
                                    },
                                )
                            }
                        }
                        items(
                            items = conversationItems,
                            key = { it.key },
                            contentType = { it.contentType },
                        ) { item ->
                            // Defer the read of `isSending` so that its changes only
                            // invalidate the smallest subtree (the enabled state of action
                            // buttons) rather than recomposing every item in the list.
                            val actionsEnabled by remember { derivedStateOf { !isSending } }
                            when (item) {
                                is ConversationListItem.Message -> {
                                    val message = item.message
                                    ConversationMessageBubble(
                                        message = message,
                                        actionsEnabled = actionsEnabled,
                                        workspaceDirectory = workspaceDirectory,
                                        allowRootImageRead = allowRootImageRead,
                                        onOpenAttachment = { previewAttachment = it },
                                        onOpenLink = onOpenLink,
                                        onEdit = { onEditMessage(message.id) },
                                        onDelete = { onDeleteMessage(message.id) },
                                        onCopy = { onCopyMessage(message) },
                                        onRedo = { onRedoAgentMessage(message.id) },
                                        onRetry = { onRetryUserMessage(message.id) },
                                        onSwitchBranch = { delta -> onSwitchUserMessageBranch(message.id, delta) },
                                        voicePlaybackEnabled = voicePlaybackEnabled,
                                        voicePlaybackState = voicePlaybackState,
                                        onReadAloud = onReadMessage,
                                        onStopReading = onStopReading,
                                    )
                                }

                                is ConversationListItem.AssistantGroup -> {
                                    val lastMessage = item.messages.last()
                                    ConversationAssistantGroupBubble(
                                        messages = item.messages,
                                        actionsEnabled = actionsEnabled,
                                        workspaceDirectory = workspaceDirectory,
                                        allowRootImageRead = allowRootImageRead,
                                        onOpenAttachment = { previewAttachment = it },
                                        onOpenLink = onOpenLink,
                                        onCopy = {
                                            onCopyMessage(
                                                lastMessage.copy(
                                                    text = item.messages.joinToString("\n\n") { message -> message.text }
                                                        .trim(),
                                                )
                                            )
                                        },
                                        onRedo = { onRedoAgentMessage(lastMessage.id) },
                                        onDelete = { onDeleteMessage(lastMessage.id) },
                                        voicePlaybackEnabled = voicePlaybackEnabled,
                                        voicePlaybackState = voicePlaybackState,
                                        onReadAloud = onReadMessage,
                                        onStopReading = onStopReading,
                                    )
                                }
                            }
                        }
                        if (pendingResponseBlocks.isNotEmpty() || pendingToolInvocations.isNotEmpty() || isSending) {
                            item(
                                key = "pending-generation-block",
                                contentType = "pending-generation-block",
                            ) {
                                val indicator = pendingGenerationIndicator(
                                    isSending = isSending,
                                    pendingAssistantText = pendingAssistantText,
                                    pendingStatusText = pendingStatusText,
                                    hasVisiblePendingReasoning = pendingResponseBlocks.any {
                                        it is AssistantResponseBlock.Reasoning &&
                                            hasVisibleReasoningStatus(it.trace)
                                    },
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    PendingAssistantTimeline(
                                        blocks = pendingResponseBlocks,
                                        workspaceDirectory = workspaceDirectory,
                                        allowRootImageRead = allowRootImageRead,
                                        onOpenLink = onOpenLink,
                                        pendingToolInvocationStateKey = pendingToolInvocationStateKey,
                                        pendingToolInvocations = pendingToolInvocations,
                                        agentModeSelected = agentModeSelected,
                                        agentModeDisplayState = agentModeDisplayState,
                                    )
                                    when (indicator) {
                                        PendingGenerationIndicator.Thinking -> {
                                            ConversationThinkingIndicator()
                                        }

                                        PendingGenerationIndicator.Status -> {
                                            ReconnectingStatusCard(
                                                text = pendingStatusText,
                                                detail = pendingStatusDetail,
                                                modifier = Modifier.padding(top = 6.dp),
                                            )
                                        }

                                        PendingGenerationIndicator.None -> Unit
                                    }
                                    agentLoopStatusText(
                                        loopState = loopState,
                                        language = strings.appLanguage,
                                    )?.let { statusText ->
                                        AgentLoopStatusPill(statusText = statusText)
                                    }
                                }
                            }
                        }
                        items(
                            items = pendingInputs,
                            key = { it.id },
                            contentType = { "pending-input" },
                        ) { pendingInput ->
                            PendingSessionInputBubble(pendingInput = pendingInput)
                        }
                        item(
                            key = "conversation-bottom-anchor",
                            contentType = "conversation-bottom-anchor",
                        ) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                            )
                        }
                    }
                }

                ScrollToLatestButton(
                    visible = showScrollToLatestButton,
                    hasNewContent = hasUnseenLatestContent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = composerBodyHeight + 18.dp),
                    onClick = ::jumpToConversationBottom,
                )

                ConversationComposerOverlay(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onBodyHeightChanged = { composerBodyHeightPx = it },
                    onUserScrollStarted = {},
                    onUserScrollFinished = {},
                    onVerticalDrag = ::dispatchComposerDragToConversation,
                    value = inputValue,
                    attachments = draftAttachments,
                    attachmentRevision = draftAttachmentRevision,
                    availableSkills = availableSkills,
                    availableMcpServers = availableMcpServers,
                    selectedSkillIds = selectedSkillIds,
                    selectedMcpServerIds = selectedMcpServerIds,
                    agentModeAvailable = agentModeAvailable,
                    agentModeSelected = agentModeSelected,
                    enabledToolGroups = enabledToolGroups,
                    planModeSelected = planModeSelected,
                    goalModeSelected = goalModeSelected,
                    modelOptions = modelOptions,
                    selectedModelKey = selectedModelKey,
                    isEditing = isEditing,
                    termuxSetupState = termuxSetupState,
                    isSending = isSending,
                    voiceInputState = voiceInputState,
                    showStarterPromptHint = showStarterPromptHint,
                    showTermuxSetupNotice = showTermuxSetupNotice,
                    onValueChange = onInputChanged,
                    onRemoveAttachment = onRemoveDraftAttachment,
                    onSetSkillSelected = onSetSkillSelected,
                    onSetMcpServerSelected = onSetMcpServerSelected,
                    onSetAgentModeSelected = onSetAgentModeSelected,
                    onSetToolGroupEnabled = onSetToolGroupEnabled,
                    onSetPlanModeEnabled = onSetPlanModeEnabled,
                    onSetGoalModeEnabled = onSetGoalModeEnabled,
                    onSlashCommand = onSlashCommand,
                    onModelSelected = onModelSelected,
                    onCancelEdit = onCancelEdit,
                    onPickImages = onPickImages,
                    onPickFiles = onPickFiles,
                    onVoiceInputPressed = onVoiceInputPressed,
                    onVoiceInputReleased = onVoiceInputReleased,
                    onCancelVoiceInput = onCancelVoiceInput,
                    onRequestTermuxPermission = onRequestTermuxPermission,
                    onOpenAppPermissions = onOpenAppPermissions,
                    onOpenTermuxSettings = onOpenTermuxSettings,
                    onOpenTermux = onOpenTermux,
                    onInstallTermux = onInstallTermux,
                    onRefreshTermuxSetup = onRefreshTermuxSetup,
                    onPauseGeneration = onPauseGeneration,
                    onDismissStarterPromptHint = onDismissStarterPromptHint,
                    onFocusChanged = { composerFocused = it },
                    onSend = onSend,
                    onQueueFollowUp = onQueueFollowUp,
                    onSteerFollowUp = onSteerFollowUp,
                )
            }

            ConversationTopOverlay(
                modifier = Modifier.align(Alignment.TopCenter),
                onBodyHeightChanged = { topBarBodyHeightPx = it },
                currentSession = currentSession,
                currentTaskSnapshot = currentTaskSnapshot,
                currentSessionTitle = currentSessionTitle,
                currentSessionPinned = currentSessionPinned,
                currentSessionArchived = currentSessionArchived,
                modelOptions = modelOptions,
                selectedModelKey = selectedModelKey,
                onMenu = onMenu,
                onOpenInbox = onOpenInbox,
                onRenameThread = onRenameThread,
                onTogglePinned = onTogglePinned,
                onArchiveThread = onArchiveThread,
                onRestoreThread = onRestoreThread,
                onExportThread = onExportThread,
                onDeleteThread = onDeleteThread,
                onModelSelected = onModelSelected,
                onNewChat = onNewChat,
            )

            previewAttachment?.let { attachment ->
                AttachmentPreviewDialog(
                    attachment = attachment,
                    onDismiss = { previewAttachment = null },
                    onSave = { onSaveAttachment(attachment) },
                )
            }
        }
    }
}

private fun LazyListState.isAtConversationBottom(): Boolean {
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount == 0) return true
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    val isLastItemVisible = lastVisibleItem.index == layoutInfo.totalItemsCount - 1
    val distanceFromBottom = layoutInfo.viewportEndOffset - (lastVisibleItem.offset + lastVisibleItem.size)
    return isLastItemVisible && distanceFromBottom >= -32
}

@Composable
private fun ScrollToLatestButton(
    visible: Boolean,
    hasNewContent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val label = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "新消息" else "New"
    val contentDescription = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
        if (hasNewContent) "查看新消息" else "跳到最新消息"
    } else {
        if (hasNewContent) "View new messages" else "Jump to latest"
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = 160, easing = ChatGptMotionEasing)) +
            scaleIn(
                initialScale = 0.86f,
                animationSpec = tween(durationMillis = 180, easing = ChatGptMotionEasing),
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = 120, easing = ChatGptMotionEasing)) +
            scaleOut(
                targetScale = 0.92f,
                animationSpec = tween(durationMillis = 120, easing = ChatGptMotionEasing),
            ),
    ) {
        Row(
            modifier = Modifier
                .shadow(14.dp, CircleShape, ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(CircleShape)
                .background(AetherSurface.copy(alpha = 0.96f))
                .clickable(onClick = onClick)
                .animateContentSize(animationSpec = tween(durationMillis = 180, easing = ChatGptMotionEasing))
                .height(42.dp)
                .padding(start = if (hasNewContent) 12.dp else 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (hasNewContent) 6.dp else 0.dp),
        ) {
            if (hasNewContent) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherOnSurface,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = contentDescription,
                tint = AetherOnSurface,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun ConversationTopOverlay(
    modifier: Modifier = Modifier,
    onBodyHeightChanged: (Int) -> Unit,
    currentSession: ChatSession?,
    currentTaskSnapshot: TaskWorkbenchSnapshot?,
    currentSessionTitle: String,
    currentSessionPinned: Boolean,
    currentSessionArchived: Boolean,
    modelOptions: List<ProviderModelOption>,
    selectedModelKey: String,
    onMenu: () -> Unit,
    onOpenInbox: () -> Unit,
    onRenameThread: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onArchiveThread: () -> Unit,
    onRestoreThread: () -> Unit,
    onExportThread: () -> Unit,
    onDeleteThread: () -> Unit,
    onModelSelected: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(topOverlayBodyGradient())
                .onSizeChanged { onBodyHeightChanged(it.height) },
        ) {
            ConversationTopBar(
                currentSession = currentSession,
                currentTaskSnapshot = currentTaskSnapshot,
                currentSessionTitle = currentSessionTitle,
                currentSessionPinned = currentSessionPinned,
                currentSessionArchived = currentSessionArchived,
                modelOptions = modelOptions,
                selectedModelKey = selectedModelKey,
                onMenu = onMenu,
                onOpenInbox = onOpenInbox,
                onRenameThread = onRenameThread,
                onTogglePinned = onTogglePinned,
                onArchiveThread = onArchiveThread,
                onRestoreThread = onRestoreThread,
                onExportThread = onExportThread,
                onDeleteThread = onDeleteThread,
                onModelSelected = onModelSelected,
                onNewChat = onNewChat,
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(ConversationTopFadeHeight)
                .background(topOverlayTailGradient())
        )
    }
}

@Composable
private fun ConversationTopBar(
    modifier: Modifier = Modifier,
    currentSession: ChatSession?,
    currentTaskSnapshot: TaskWorkbenchSnapshot?,
    currentSessionTitle: String,
    currentSessionPinned: Boolean,
    currentSessionArchived: Boolean,
    modelOptions: List<ProviderModelOption>,
    selectedModelKey: String,
    onMenu: () -> Unit,
    onOpenInbox: () -> Unit,
    onRenameThread: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onArchiveThread: () -> Unit,
    onRestoreThread: () -> Unit,
    onExportThread: () -> Unit,
    onDeleteThread: () -> Unit,
    onModelSelected: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCircleButton(
                icon = Icons.Rounded.Menu,
                contentDescription = strings.menu,
                onClick = {
                    focusManager.clearFocus(force = true)
                    onMenu()
                },
                size = 44.dp,
                containerColor = AetherSurface.copy(alpha = 0.96f),
            )
            ConversationModelSelector(
                options = modelOptions,
                selectedModelKey = selectedModelKey,
                onSelected = onModelSelected,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            HeaderCircleButton(
                icon = LucideIcons.Cursor,
                contentDescription = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
                    "\u4efb\u52a1\u5de5\u4f5c\u53f0"
                } else {
                    "Task inbox"
                },
                onClick = onOpenInbox,
                size = 44.dp,
                containerColor = AetherSurface.copy(alpha = 0.96f),
            )
            HeaderCircleButton(
                icon = LucideIcons.SquarePen,
                contentDescription = strings.newChat,
                onClick = onNewChat,
                size = 44.dp,
                containerColor = AetherSurface.copy(alpha = 0.96f),
            )
        }

    }
}

@Composable
private fun ConversationThreadMetaBar(
    session: ChatSession?,
    taskSnapshot: TaskWorkbenchSnapshot?,
    title: String,
    isPinned: Boolean,
    isArchived: Boolean,
    onRenameThread: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onArchiveThread: () -> Unit,
    onRestoreThread: () -> Unit,
    onExportThread: () -> Unit,
    onDeleteThread: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val language = strings.appLanguage
    val taskStatusLabel = taskSnapshot?.let { snapshot ->
        taskWorkbenchStatusLabel(snapshot.effectiveStatus, language)
    }
    val taskStatusTint = taskSnapshot?.let { snapshot ->
        taskWorkbenchStatusAccent(snapshot.effectiveStatus)
    } ?: AetherOnSurfaceVariant
    val activityDetail = taskSnapshot?.activityDetail?.takeIf { it.isNotBlank() }
    var detailsVisible by rememberSaveable(session?.id) { mutableStateOf(false) }
    var deleteConfirmationVisible by rememberSaveable(session?.id) { mutableStateOf(false) }

    if (deleteConfirmationVisible) {
        DeleteThreadsConfirmationDialog(
            count = 1,
            language = language,
            onDismissRequest = { deleteConfirmationVisible = false },
            onConfirm = {
                deleteConfirmationVisible = false
                detailsVisible = false
                onDeleteThread()
            },
        )
    }

    session?.let { activeSession ->
        if (detailsVisible) {
            ThreadDetailSheet(
                state = ThreadDetailSheetState(
                    session = activeSession,
                    taskSnapshot = taskSnapshot,
                    isRunning = false,
                ),
                onDismissRequest = { detailsVisible = false },
                onOpenThread = { detailsVisible = false },
                onRename = onRenameThread,
                onTogglePinned = onTogglePinned,
                onToggleArchived = { if (isArchived) onRestoreThread() else onArchiveThread() },
                onExport = onExportThread,
                onDelete = {
                    deleteConfirmationVisible = true
                },
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(22.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(22.dp))
            .background(AetherSurface.copy(alpha = 0.90f))
            .clickable(enabled = session != null, onClick = { detailsVisible = true })
            .padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title.ifBlank {
                    if (language == AppLanguage.SimplifiedChinese) "\u672a\u547d\u540d\u7ebf\u7a0b" else "Untitled thread"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (taskStatusLabel != null || isPinned || isArchived) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    taskStatusLabel?.let { label ->
                        ConversationThreadStatusPill(
                            text = label,
                            tint = taskStatusTint,
                        )
                    }
                    if (isPinned) {
                        ConversationThreadStatusPill(
                            text = if (language == AppLanguage.SimplifiedChinese) "\u5df2\u7f6e\u9876" else "Pinned",
                            tint = AetherPrimary,
                        )
                    }
                    if (isArchived) {
                        ConversationThreadStatusPill(
                            text = if (language == AppLanguage.SimplifiedChinese) "\u5df2\u5f52\u6863" else "Archived",
                            tint = Color(0xFF8B5CF6),
                        )
                    }
                }
            } else if (!activityDetail.isNullOrBlank()) {
                Text(
                    text = activityDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = taskStatusTint.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ConversationThreadActionPill(
            label = if (language == AppLanguage.SimplifiedChinese) {
                "\u8be6\u60c5"
            } else {
                "Details"
            },
            tint = AetherOnSurfaceVariant,
            onClick = { detailsVisible = true },
        )
    }
}

@Composable
private fun ConversationThreadStatusPill(
    text: String,
    tint: Color,
) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ConversationThreadActionPill(
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = tint,
        maxLines = 1,
    )
}

@Composable
private fun ConversationModelSelector(
    options: List<ProviderModelOption>,
    selectedModelKey: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorBottomPx by remember { mutableIntStateOf(0) }
    val menuVisibility = remember { MutableTransitionState(false) }
    menuVisibility.targetState = expanded
    val strings = rememberAetherStrings()
    val density = LocalDensity.current
    val menuWidth = 340.dp
    val selectedOption = options.firstOrNull { it.key == selectedModelKey } ?: options.firstOrNull()
    val fallbackLabel = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
        "选择模型"
    } else {
        "Select model"
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    anchorBottomPx = coordinates.boundsInRoot().bottom.toInt()
                }
                .fillMaxWidth()
                .clickable(enabled = options.isNotEmpty()) { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = selectedOption?.providerName ?: fallbackLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = selectedOption?.modelId ?: fallbackLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherOnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }

        BackHandler(enabled = expanded) { expanded = false }
        if (menuVisibility.currentState || menuVisibility.targetState) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, anchorBottomPx - with(density) { 32.dp.roundToPx() }),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visibleState = menuVisibility,
                    enter = fadeIn() + scaleIn(
                        initialScale = 0.96f,
                        transformOrigin = TransformOrigin(0.5f, 0f),
                    ) + slideInVertically(initialOffsetY = { -it / 10 }),
                    exit = fadeOut() + scaleOut(
                        targetScale = 0.98f,
                        transformOrigin = TransformOrigin(0.5f, 0f),
                    ) + slideOutVertically(targetOffsetY = { -it / 12 }),
                ) {
                    Column(
                        modifier = Modifier
                            .width(menuWidth)
                            .shadow(20.dp, RoundedCornerShape(28.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                            .clip(RoundedCornerShape(28.dp))
                            .background(AetherSurface)
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            itemsIndexed(
                                items = options,
                                key = { _, option -> option.key },
                            ) { index, option ->
                                val isSelected = option.key == selectedOption?.key
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable {
                                            expanded = false
                                            onSelected(option.key)
                                        }
                                        .padding(horizontal = 18.dp, vertical = 13.dp),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .fillMaxWidth()
                                            .padding(end = 34.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = option.providerName,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = AetherOnSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = option.modelId,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                            ),
                                            color = AetherOnSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = AetherOnSurface,
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .size(22.dp),
                                        )
                                    }
                                }
                                if (index != options.lastIndex) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 18.dp)
                                            .height(1.dp)
                                            .background(AetherOnSurfaceVariant.copy(alpha = 0.12f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationEmptyState(
    modifier: Modifier = Modifier,
    inputFocused: Boolean,
    showResumeSetupBanner: Boolean,
    onResumeOnboarding: () -> Unit,
    onStarterPromptSelected: (String) -> Unit,
) {
    val strings = rememberAetherStrings()
    val density = LocalDensity.current
    val titleOffset by animateDpAsState(
        targetValue = if (inputFocused) (-34).dp else (-24).dp,
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "empty_state_title_offset",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp)
            .offset { IntOffset(0, with(density) { titleOffset.roundToPx() }) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showResumeSetupBanner) {
            ResumeSetupCard(onResumeOnboarding = onResumeOnboarding)
            Spacer(modifier = Modifier.height(22.dp))
        }
        Text(
            text = strings.whatCanIHelpWith,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Normal,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp,
            ),
            color = AetherOnSurface,
        )
        Spacer(modifier = Modifier.height(48.dp))
        StarterPromptChips(
            strings = strings,
            onStarterPromptSelected = onStarterPromptSelected,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StarterPromptChips(
    strings: AetherStrings,
    onStarterPromptSelected: (String) -> Unit,
) {
    val isChinese = strings.appLanguage == AppLanguage.SimplifiedChinese
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmptyStateChip(
            icon = Icons.Rounded.Image,
            label = strings.analyzeImageChip,
            iconTint = Color(0xFF38A961),
            onClick = {
                onStarterPromptSelected(
                    if (isChinese) "分析这张图片，并描述其中的重要细节。" else "Analyze this image and describe the important details."
                )
            },
        )
        EmptyStateChip(
            icon = Icons.Rounded.Terminal,
            label = strings.codeChip,
            iconTint = Color(0xFF5E76D8),
            onClick = {
                onStarterPromptSelected(
                    if (isChinese) "帮我编写或调试这段代码：" else "Help me write or debug this code: "
                )
            },
        )
        EmptyStateChip(
            icon = Icons.Rounded.AutoAwesome,
            label = strings.helpMeWriteChip,
            iconTint = Color(0xFFE48AAE),
            onClick = {
                onStarterPromptSelected(
                    if (isChinese) "帮我写一段清晰、得体的内容，主题是：" else "Help me write a clear, polished message about "
                )
            },
        )
        EmptyStateChip(
            icon = Icons.Rounded.AttachFile,
            label = strings.summarizeFileChip,
            iconTint = Color(0xFF38A6B8),
            onClick = {
                onStarterPromptSelected(
                    if (isChinese) "总结这个文件，并列出关键要点。" else "Summarize this file and list the key points."
                )
            },
        )
    }
}

@Composable
private fun EmptyStateChip(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .shadow(4.dp, RoundedCornerShape(999.dp), ambientColor = ChatGptControlShadow, spotColor = ChatGptControlShadow)
            .clip(RoundedCornerShape(999.dp))
            .background(AetherSurface.copy(alpha = 0.98f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun ResumeSetupCard(
    onResumeOnboarding: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurface.copy(alpha = 0.96f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "完成设置" else "Finish setup",
            style = MaterialTheme.typography.titleMedium,
            color = AetherOnSurface,
        )
        Text(
            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "连接模型后返回聊天。" else "Connect a model, then come back to chat.",
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
        Button(
            onClick = onResumeOnboarding,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AetherPrimary,
                contentColor = AetherOnPrimary,
            ),
        ) {
            Text(if (strings.appLanguage == AppLanguage.SimplifiedChinese) "继续设置" else "Resume setup")
        }
    }
}

@Composable
private fun ConversationThinkingIndicator() {
    val strings = rememberAetherStrings()
    ShimmerStatusText(
        text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "思考中" else "Thinking",
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun PendingAssistantTimeline(
    blocks: List<AssistantResponseBlock>,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    pendingToolInvocationStateKey: String,
    pendingToolInvocations: List<ChatToolInvocation>,
    agentModeSelected: Boolean,
    agentModeDisplayState: AgentModeDisplayState,
) {
    if (blocks.isEmpty()) {
        val agentModePreviewVisible =
            pendingToolInvocations.isNotEmpty() &&
                (agentModeSelected ||
                    agentModeDisplayState.isActive ||
                    agentModeDisplayState.latestPreviewPath.isNotBlank())
        if (agentModePreviewVisible) {
            AgentModePreviewPanel(
                displayState = agentModeDisplayState,
                toolInvocation = pendingToolInvocations.lastOrNull(),
            )
        } else if (pendingToolInvocations.isNotEmpty()) {
            ToolInvocationList(
                toolInvocations = pendingToolInvocations,
                stateKey = pendingToolInvocationStateKey,
                autoExpand = true,
            )
        }
        return
    }

    blocks.forEachIndexed { index, block ->
        when (block) {
            is AssistantResponseBlock.Text -> PendingAssistantResponseBlock(
                text = block.text,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
            )

            is AssistantResponseBlock.ToolGroup -> {
                val isLastBlock = index == blocks.lastIndex
                val shouldShowAgentModePreview =
                    isLastBlock &&
                        (agentModeSelected ||
                            agentModeDisplayState.isActive ||
                            agentModeDisplayState.latestPreviewPath.isNotBlank()) &&
                        block.toolInvocations.any { it.toolName.equals("agent_display", ignoreCase = true) }
                if (shouldShowAgentModePreview) {
                    AgentModePreviewPanel(
                        displayState = agentModeDisplayState,
                        toolInvocation = block.toolInvocations.lastOrNull(),
                    )
                } else {
                    ToolInvocationList(
                        toolInvocations = block.toolInvocations,
                        stateKey = "$pendingToolInvocationStateKey-${block.id}",
                        autoExpand = isLastBlock,
                    )
                }
            }

            is AssistantResponseBlock.Reasoning -> {
                if (hasVisibleReasoningStatus(block.trace)) {
                    ReasoningTraceStatus(
                        trace = block.trace,
                        onOpenLink = onOpenLink,
                    )
                }
            }
        }
    }
}

internal fun initialConversationMessageStartIndex(
    messageCount: Int,
    initialWindow: Int = ConversationInitialMessageWindow,
): Int = if (messageCount <= initialWindow) 0 else messageCount - initialWindow

internal fun previousConversationMessageStartIndex(
    currentStart: Int,
    pageSize: Int = ConversationMessagePageSize,
): Int = (currentStart - pageSize).coerceAtLeast(0)

internal fun clampConversationMessageStartIndex(
    startIndex: Int,
    messageCount: Int,
): Int = when {
    messageCount <= 0 -> 0
    startIndex <= 0 -> 0
    startIndex >= messageCount -> initialConversationMessageStartIndex(messageCount)
    else -> startIndex
}

@Composable
private fun ConversationOlderMessagesHint(
    hiddenMessageCount: Int,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val label = if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
        if (isLoading) "正在加载更早的消息…" else "向上滑动或点击加载更早的 $hiddenMessageCount 条消息"
    } else {
        if (isLoading) {
            "Loading earlier messages…"
        } else {
            "Swipe up or tap to load $hiddenMessageCount earlier messages"
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !isLoading, onClick = onLoadMore)
            .background(AetherSurfaceHigh.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
    }
}

private fun buildConversationListItems(
    messages: List<ChatMessage>,
): List<ConversationListItem> = buildList {
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        val responseGroupId = message.responseGroupId
        if (
            message.author == MessageAuthor.Agent &&
            (!responseGroupId.isNullOrBlank() || isLegacyAssistantGroupStart(messages, index))
        ) {
            val groupedMessages = buildList {
                var groupIndex = index
                while (groupIndex < messages.size) {
                    val candidate = messages[groupIndex]
                    if (candidate.author != MessageAuthor.Agent) {
                        break
                    }
                    val matchesGroup = if (!responseGroupId.isNullOrBlank()) {
                        candidate.responseGroupId == responseGroupId
                    } else {
                        val offset = groupIndex - index
                        candidate.responseGroupId.isNullOrBlank() &&
                            candidate.createdAtMillis == message.createdAtMillis + offset
                    }
                    if (!matchesGroup) {
                        break
                    }
                    add(candidate)
                    groupIndex += 1
                }
            }
            if (groupedMessages.isNotEmpty()) {
                add(ConversationListItem.AssistantGroup(groupedMessages))
                index += groupedMessages.size
                continue
            }
        }
        add(ConversationListItem.Message(message))
        index += 1
    }
}

private fun ConversationListItem.markdownsForPrewarm(): List<String> = when (this) {
    is ConversationListItem.Message -> message.markdownsForPrewarm()
    is ConversationListItem.AssistantGroup -> messages.flatMap { it.markdownsForPrewarm() }
}

private fun ChatMessage.markdownsForPrewarm(): List<String> =
    if (author == MessageAuthor.Agent && text.isNotBlank()) {
        listOf(text)
    } else {
        emptyList()
    }

private fun isLegacyAssistantGroupStart(
    messages: List<ChatMessage>,
    index: Int,
): Boolean {
    val message = messages.getOrNull(index) ?: return false
    if (
        message.author != MessageAuthor.Agent ||
        !message.responseGroupId.isNullOrBlank()
    ) {
        return false
    }
    val next = messages.getOrNull(index + 1) ?: return false
    return next.author == MessageAuthor.Agent &&
        next.responseGroupId.isNullOrBlank() &&
        next.createdAtMillis == message.createdAtMillis + 1
}

@Composable
private fun AgentLoopStatusPill(
    statusText: AgentLoopStatusText,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.82f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = statusText.title,
                style = MaterialTheme.typography.labelMedium,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (statusText.detail.isNotBlank()) {
                Text(
                    text = statusText.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PendingSessionInputBubble(
    pendingInput: PendingSessionInput,
) {
    val strings = rememberAetherStrings()
    val modeLabel = when (pendingInput.mode) {
        SessionFollowUpMode.Queue -> strings.pendingInputModeLabel(true)
        SessionFollowUpMode.Steer -> strings.pendingInputModeLabel(false)
    }
    val attachmentLabel = when (pendingInput.attachmentCount) {
        0 -> null
        else -> strings.attachmentCountLabel(pendingInput.attachmentCount)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherSurfaceHigh.copy(alpha = 0.96f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                attachmentLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
            Text(
                text = pendingInput.preview.ifBlank {
                    if (strings.appLanguage == AppLanguage.SimplifiedChinese) "补充上下文" else "Additional context"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = AetherOnSurface,
            )
        }
    }
}

@Composable
private fun ConversationComposerOverlay(
    modifier: Modifier = Modifier,
    onBodyHeightChanged: (Int) -> Unit,
    onUserScrollStarted: () -> Unit,
    onUserScrollFinished: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    value: String,
    attachments: List<ChatAttachment>,
    attachmentRevision: Long,
    availableSkills: List<InstalledSkill>,
    availableMcpServers: List<McpServerConfig>,
    selectedSkillIds: List<String>,
    selectedMcpServerIds: List<String>,
    agentModeAvailable: Boolean,
    agentModeSelected: Boolean,
    enabledToolGroups: List<String>,
    planModeSelected: Boolean,
    goalModeSelected: Boolean,
    modelOptions: List<ProviderModelOption>,
    selectedModelKey: String,
    isEditing: Boolean,
    termuxSetupState: TermuxSetupState,
    isSending: Boolean,
    voiceInputState: VoiceInputUiState,
    showStarterPromptHint: Boolean,
    showTermuxSetupNotice: Boolean,
    onValueChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSetSkillSelected: (String, Boolean) -> Unit,
    onSetMcpServerSelected: (String, Boolean) -> Unit,
    onSetAgentModeSelected: (Boolean) -> Unit,
    onSetToolGroupEnabled: (String, Boolean) -> Unit,
    onSetPlanModeEnabled: (Boolean) -> Unit,
    onSetGoalModeEnabled: (Boolean) -> Unit,
    onSlashCommand: (ComposerSlashCommandId, String) -> Unit,
    onModelSelected: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onVoiceInputPressed: () -> Unit,
    onVoiceInputReleased: () -> Unit,
    onCancelVoiceInput: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onPauseGeneration: () -> Unit,
    onDismissStarterPromptHint: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onQueueFollowUp: () -> Unit,
    onSteerFollowUp: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 18.dp)
            .pointerInput(onUserScrollStarted, onUserScrollFinished, onVerticalDrag) {
                detectVerticalDragGestures(
                    onDragStart = { onUserScrollStarted() },
                    onDragEnd = { onUserScrollFinished() },
                    onDragCancel = { onUserScrollFinished() },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        onVerticalDrag(dragAmount)
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onBodyHeightChanged(it.height) },
        ) {
            ChatGptPromptComposerBar(
                value = value,
                attachments = attachments,
                attachmentRevision = attachmentRevision,
                availableSkills = availableSkills,
                availableMcpServers = availableMcpServers,
                selectedSkillIds = selectedSkillIds,
                selectedMcpServerIds = selectedMcpServerIds,
                agentModeAvailable = agentModeAvailable,
                agentModeSelected = agentModeSelected,
                enabledToolGroups = enabledToolGroups,
                planModeSelected = planModeSelected,
                goalModeSelected = goalModeSelected,
                modelOptions = modelOptions,
                selectedModelKey = selectedModelKey,
                isEditing = isEditing,
                termuxSetupState = termuxSetupState,
                isSending = isSending,
                voiceInputState = voiceInputState,
                showStarterPromptHint = showStarterPromptHint,
                showTermuxSetupNotice = showTermuxSetupNotice,
                onValueChange = onValueChange,
                onRemoveAttachment = onRemoveAttachment,
                onSetSkillSelected = onSetSkillSelected,
                onSetMcpServerSelected = onSetMcpServerSelected,
                onSetAgentModeSelected = onSetAgentModeSelected,
                onSetToolGroupEnabled = onSetToolGroupEnabled,
                onSetPlanModeEnabled = onSetPlanModeEnabled,
                onSetGoalModeEnabled = onSetGoalModeEnabled,
                onSlashCommand = onSlashCommand,
                onModelSelected = onModelSelected,
                onCancelEdit = onCancelEdit,
                onPickImages = onPickImages,
                onPickFiles = onPickFiles,
                onVoiceInputPressed = onVoiceInputPressed,
                onVoiceInputReleased = onVoiceInputReleased,
                onCancelVoiceInput = onCancelVoiceInput,
                onRequestTermuxPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefreshTermuxSetup = onRefreshTermuxSetup,
                onPauseGeneration = onPauseGeneration,
                onDismissStarterPromptHint = onDismissStarterPromptHint,
                onFocusChanged = onFocusChanged,
                onSend = onSend,
                onQueueFollowUp = onQueueFollowUp,
                onSteerFollowUp = onSteerFollowUp,
            )
        }
    }
}

@Composable
private fun ChatGptPromptComposerBar(
    modifier: Modifier = Modifier,
    value: String,
    attachments: List<ChatAttachment>,
    attachmentRevision: Long,
    availableSkills: List<InstalledSkill>,
    availableMcpServers: List<McpServerConfig>,
    selectedSkillIds: List<String>,
    selectedMcpServerIds: List<String>,
    agentModeAvailable: Boolean,
    agentModeSelected: Boolean,
    enabledToolGroups: List<String>,
    planModeSelected: Boolean,
    goalModeSelected: Boolean,
    modelOptions: List<ProviderModelOption>,
    selectedModelKey: String,
    isEditing: Boolean,
    termuxSetupState: TermuxSetupState,
    isSending: Boolean,
    voiceInputState: VoiceInputUiState,
    showStarterPromptHint: Boolean,
    showTermuxSetupNotice: Boolean,
    onValueChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSetSkillSelected: (String, Boolean) -> Unit,
    onSetMcpServerSelected: (String, Boolean) -> Unit,
    onSetAgentModeSelected: (Boolean) -> Unit,
    onSetToolGroupEnabled: (String, Boolean) -> Unit,
    onSetPlanModeEnabled: (Boolean) -> Unit,
    onSetGoalModeEnabled: (Boolean) -> Unit,
    onSlashCommand: (ComposerSlashCommandId, String) -> Unit,
    onModelSelected: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onVoiceInputPressed: () -> Unit,
    onVoiceInputReleased: () -> Unit,
    onCancelVoiceInput: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onPauseGeneration: () -> Unit,
    onDismissStarterPromptHint: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onQueueFollowUp: () -> Unit,
    onSteerFollowUp: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val composerTapInteractionSource = remember { MutableInteractionSource() }
    var textFieldFocused by remember { mutableStateOf(false) }
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    val attachmentMenuVisibility = remember { MutableTransitionState(false) }
    attachmentMenuVisibility.targetState = attachmentMenuExpanded
    var toolsMenuExpanded by remember { mutableStateOf(false) }
    val toolsMenuVisibility = remember { MutableTransitionState(false) }
    toolsMenuVisibility.targetState = toolsMenuExpanded
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val modelMenuVisibility = remember { MutableTransitionState(false) }
    modelMenuVisibility.targetState = modelMenuExpanded
    var followUpMenuExpanded by remember { mutableStateOf(false) }
    val followUpMenuVisibility = remember { MutableTransitionState(false) }
    followUpMenuVisibility.targetState = followUpMenuExpanded
    var voiceCancelArmed by remember { mutableStateOf(false) }
    var voiceElapsedMs by remember { mutableLongStateOf(0L) }
    var measuredTextLineCount by remember { mutableIntStateOf(1) }
    var measuredTextHeight by remember { mutableStateOf(26.dp) }
    var composerFieldValue by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }

    BackHandler(enabled = attachmentMenuExpanded) { attachmentMenuExpanded = false }
    BackHandler(enabled = toolsMenuExpanded) { toolsMenuExpanded = false }
    BackHandler(enabled = modelMenuExpanded) { modelMenuExpanded = false }
    BackHandler(enabled = followUpMenuExpanded) { followUpMenuExpanded = false }
    LaunchedEffect(textFieldFocused) {
        onFocusChanged(textFieldFocused)
    }
    LaunchedEffect(value) {
        if (value != composerFieldValue.text) {
            val cursor = VoiceDraftSelectionState.consumePendingCursor(value.length)
            composerFieldValue = TextFieldValue(value, selection = TextRange(cursor))
            VoiceDraftSelectionState.update(cursor, cursor)
        } else {
            VoiceDraftSelectionState.update(composerFieldValue.selection.start, composerFieldValue.selection.end)
        }
    }
    LaunchedEffect(voiceInputState.status) {
        if (voiceInputState.status == VoiceInputStatus.Listening) {
            val startedAt = SystemClock.elapsedRealtime()
            voiceElapsedMs = 0L
            while (true) {
                voiceElapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                delay(100L)
            }
        } else {
            voiceElapsedMs = 0L
        }
    }

    val selectedSkillSet = remember(selectedSkillIds) { selectedSkillIds.toSet() }
    val selectedMcpServerSet = remember(selectedMcpServerIds) { selectedMcpServerIds.toSet() }
    val normalizedToolGroups = remember(enabledToolGroups) { normalizeChatToolGroups(enabledToolGroups) }
    val selectedToolGroupSet = remember(normalizedToolGroups) { normalizedToolGroups.toSet() }
    val extensionsEnabled = selectedToolGroupSet.contains(ChatToolGroups.Extensions)
    val selectedSkillActions = remember(availableSkills, selectedSkillSet, extensionsEnabled) {
        if (extensionsEnabled) {
            availableSkills.filter { selectedSkillSet.contains(it.id) }
        } else {
            emptyList()
        }
    }
    val selectedMcpActions = remember(availableMcpServers, selectedMcpServerSet, extensionsEnabled) {
        if (extensionsEnabled) {
            availableMcpServers.filter { selectedMcpServerSet.contains(it.id) }
        } else {
            emptyList()
        }
    }
    val allSkillsSelected = availableSkills.isNotEmpty() && availableSkills.all { selectedSkillSet.contains(it.id) }
    val allMcpServersSelected = availableMcpServers.isNotEmpty() && availableMcpServers.all { selectedMcpServerSet.contains(it.id) }
    val hasDraft = value.isNotBlank() || attachments.isNotEmpty()
    val canSendDraft = attachments.all { it.workspaceState == AttachmentWorkspaceState.Ready }
    val showPauseButton = isSending && !hasDraft
    val showSubmitButton = !isSending || hasDraft
    val slashQuery = remember(value) {
        val trimmed = value.trimStart()
        if (trimmed.startsWith("/") && trimmed.drop(1).none { it.isWhitespace() }) {
            slashCommandQuery(value)
        } else {
            null
        }
    }
    val slashCommands = remember(strings.appLanguage) { composerSlashCommandItems(strings.appLanguage) }
    val visibleSlashCommands = remember(slashCommands, slashQuery) {
        if (slashQuery == null) {
            emptyList()
        } else {
            slashCommands.filter { command ->
                command.name.contains(slashQuery, ignoreCase = true) ||
                    command.title.contains(slashQuery, ignoreCase = true)
            }
        }
    }
    val textLineCount = if (value.isBlank()) 1 else maxOf(value.count { it == '\n' } + 1, measuredTextLineCount).coerceIn(1, 5)
    val cardMinHeight by animateDpAsState(
        targetValue = maxOf(116.dp, measuredTextHeight + 74.dp),
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "chatgpt_prompt_min_height",
    )
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = AetherOnSurface,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )

    fun executeComposerSlashCommand(
        commandId: ComposerSlashCommandId,
        inlineText: String,
    ) {
        attachmentMenuExpanded = false
        toolsMenuExpanded = false
        modelMenuExpanded = false
        followUpMenuExpanded = false
        onSlashCommand(commandId, inlineText)
    }

    fun submitComposerDraft() {
        val parsedCommand = parseComposerSlashCommand(value)
        if (parsedCommand != null) {
            when (parsedCommand.id) {
                ComposerSlashCommandId.Model -> {
                    modelMenuExpanded = true
                    composerFieldValue = TextFieldValue("")
                    onValueChange("")
                }

                ComposerSlashCommandId.Tools,
                ComposerSlashCommandId.Permissions -> {
                    toolsMenuExpanded = true
                    composerFieldValue = TextFieldValue("")
                    onValueChange("")
                }

                else -> executeComposerSlashCommand(parsedCommand.id, parsedCommand.inlineText)
            }
        } else {
            onSend()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showTermuxSetupNotice) {
            TermuxSetupNotice(
                setupState = termuxSetupState,
                onRequestPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefresh = onRefreshTermuxSetup,
            )
        }
        if (showStarterPromptHint) {
            SurfaceNotice(
                title = strings.firstPromptReadyTitle,
                subtitle = strings.firstPromptReadySubtitle,
                actionLabel = strings.hide,
                onAction = onDismissStarterPromptHint,
                actionEnabled = true,
            )
        }
        if (isEditing) {
            SurfaceNotice(
                title = strings.editingEarlierMessageTitle,
                subtitle = strings.editingEarlierMessageSubtitle,
                actionLabel = strings.cancel,
                onAction = onCancelEdit,
                actionEnabled = true,
            )
        }
        ComposerSlashCommandPanel(
            visible = textFieldFocused && visibleSlashCommands.isNotEmpty(),
            commands = visibleSlashCommands,
            onCommandSelected = { command ->
                when (command.id) {
                    ComposerSlashCommandId.Plan,
                    ComposerSlashCommandId.Goal -> {
                        val inserted = "/${command.name} "
                        composerFieldValue = TextFieldValue(inserted, selection = TextRange(inserted.length))
                        onValueChange(inserted)
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }

                    ComposerSlashCommandId.Model -> {
                        modelMenuExpanded = true
                        composerFieldValue = TextFieldValue("")
                        onValueChange("")
                    }

                    ComposerSlashCommandId.Tools,
                    ComposerSlashCommandId.Permissions -> {
                        toolsMenuExpanded = true
                        composerFieldValue = TextFieldValue("")
                        onValueChange("")
                    }

                    ComposerSlashCommandId.Status,
                    ComposerSlashCommandId.Review -> executeComposerSlashCommand(command.id, "")
                }
            },
        )
        ComposerModelCommandPanel(
            visibleState = modelMenuVisibility,
            modelOptions = modelOptions,
            selectedModelKey = selectedModelKey,
            onDismiss = { modelMenuExpanded = false },
            onModelSelected = { modelKey ->
                modelMenuExpanded = false
                onModelSelected(modelKey)
            },
        )
        if (attachments.isNotEmpty()) {
            key(attachmentRevision) {
                ComposerAttachmentTray(
                    attachments = attachments,
                    onRemoveAttachment = onRemoveAttachment,
                )
            }
        }
        VoiceInputPanel(
            state = voiceInputState,
            elapsedMs = voiceElapsedMs,
            cancelArmed = voiceCancelArmed,
            onCancel = onCancelVoiceInput,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = 7.dp)
                    .blur(radius = 18.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .clip(ChatGptPromptShape)
                    .background(ChatGptComposerShadow),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = cardMinHeight)
                    .animateContentSize(animationSpec = tween(durationMillis = 300, easing = ChatGptMotionEasing))
                    .clip(ChatGptPromptShape)
                    .background(AetherSurface)
                    .clickable(
                        interactionSource = composerTapInteractionSource,
                        indication = null,
                    ) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                    .padding(start = 16.dp, end = 14.dp, top = 18.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = measuredTextHeight.coerceAtLeast(30.dp)),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (value.isBlank()) {
                        Text(
                            text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "输入消息..." else "Message...",
                            style = textStyle,
                            color = Color(0xFF767676),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = composerFieldValue,
                        onValueChange = { newValue ->
                            composerFieldValue = newValue
                            VoiceDraftSelectionState.update(newValue.selection.start, newValue.selection.end)
                            onValueChange(newValue.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                if (textFieldFocused != focusState.isFocused) {
                                    textFieldFocused = focusState.isFocused
                                }
                            },
                        textStyle = textStyle,
                        maxLines = 5,
                        onTextLayout = { textLayoutResult ->
                            val lineCount = textLayoutResult.lineCount.coerceIn(1, 5)
                            measuredTextLineCount = lineCount
                            val visibleLineBottom = textLayoutResult.getLineBottom(lineCount - 1)
                            val visibleLineTop = textLayoutResult.getLineTop(0)
                            measuredTextHeight = with(density) {
                                (visibleLineBottom - visibleLineTop).toDp()
                            }
                        },
                        cursorBrush = SolidColor(AetherOnSurface),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        ComposerIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = strings.addAttachmentOrTool,
                            iconSize = 30.dp,
                            onClick = {
                                toolsMenuExpanded = false
                                attachmentMenuExpanded = !attachmentMenuExpanded
                            },
                        )
                        ComposerAttachmentPopup(
                            visibleState = attachmentMenuVisibility,
                            onDismiss = { attachmentMenuExpanded = false },
                            onPickImages = {
                                attachmentMenuExpanded = false
                                onPickImages()
                            },
                            onPickFiles = {
                                attachmentMenuExpanded = false
                                onPickFiles()
                            },
                        )
                    }

                    Box {
                        ComposerToolsButton(
                            selected = toolsMenuExpanded || planModeSelected || goalModeSelected,
                            planModeSelected = planModeSelected,
                            goalModeSelected = goalModeSelected,
                            onClick = {
                                attachmentMenuExpanded = false
                                toolsMenuExpanded = !toolsMenuExpanded
                            },
                        )
                        ComposerToolsPopup(
                            visibleState = toolsMenuVisibility,
                            agentModeAvailable = agentModeAvailable,
                            agentModeSelected = agentModeSelected,
                            planModeSelected = planModeSelected,
                            goalModeSelected = goalModeSelected,
                            enabledToolGroups = normalizedToolGroups,
                            allSkillsSelected = allSkillsSelected,
                            allMcpServersSelected = allMcpServersSelected,
                            availableSkills = availableSkills,
                            availableMcpServers = availableMcpServers,
                            selectedSkillSet = selectedSkillSet,
                            selectedMcpServerSet = selectedMcpServerSet,
                            onDismiss = { toolsMenuExpanded = false },
                            onSetToolGroupEnabled = onSetToolGroupEnabled,
                            onSetAgentModeSelected = onSetAgentModeSelected,
                            onSetPlanModeEnabled = onSetPlanModeEnabled,
                            onSetGoalModeEnabled = onSetGoalModeEnabled,
                            onSetSkillSelected = onSetSkillSelected,
                            onSetMcpServerSelected = onSetMcpServerSelected,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    ComposerIconButton(
                        icon = Icons.Rounded.Mic,
                        contentDescription = strings.voice,
                        iconSize = 23.dp,
                        selected = voiceInputState.status == VoiceInputStatus.Listening ||
                            voiceInputState.status == VoiceInputStatus.Processing,
                        onPress = {
                            voiceCancelArmed = false
                            onVoiceInputPressed()
                        },
                        onRelease = {
                            val shouldCancel = voiceCancelArmed
                            voiceCancelArmed = false
                            if (shouldCancel) onCancelVoiceInput() else onVoiceInputReleased()
                        },
                        onCancel = {
                            voiceCancelArmed = false
                            onCancelVoiceInput()
                        },
                        onGestureStateChanged = { armed -> voiceCancelArmed = armed },
                    )
                    if (showPauseButton) {
                        ComposerPauseButton(onClick = onPauseGeneration)
                    }
                    if (showSubmitButton) {
                        Box {
                            ComposerSubmitButton(
                                hasDraft = hasDraft,
                                canSendDraft = canSendDraft,
                                isSending = isSending,
                                onClick = {
                                    if (!hasDraft || !canSendDraft) return@ComposerSubmitButton
                                    if (isSending) followUpMenuExpanded = true else submitComposerDraft()
                                },
                            )
                            ComposerFollowUpPopup(
                                visible = isSending && (followUpMenuVisibility.currentState || followUpMenuVisibility.targetState),
                                visibleState = followUpMenuVisibility,
                                onDismiss = { followUpMenuExpanded = false },
                                onSteerFollowUp = {
                                    followUpMenuExpanded = false
                                    onSteerFollowUp()
                                },
                                onQueueFollowUp = {
                                    followUpMenuExpanded = false
                                    onQueueFollowUp()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun goalModeTaskStatusLabel(
    status: AgentTaskStatus,
    language: AppLanguage,
): String {
    val isChinese = language == AppLanguage.SimplifiedChinese
    return when (status) {
        AgentTaskStatus.InProgress -> if (isChinese) "目标进行中" else "Goal in progress"
        AgentTaskStatus.WaitingForUser -> if (isChinese) "目标已暂停" else "Goal paused"
        AgentTaskStatus.Completed -> if (isChinese) "目标已完成" else "Goal complete"
        AgentTaskStatus.Blocked -> if (isChinese) "目标受阻" else "Goal blocked"
        AgentTaskStatus.Idle -> if (isChinese) "目标模式" else "Goal Mode"
    }
}

@Composable
private fun VoiceInputPanel(
    state: VoiceInputUiState,
    elapsedMs: Long = 0L,
    cancelArmed: Boolean = false,
    onCancel: () -> Unit,
) {
    if (!state.isActive) return
    val strings = rememberAetherStrings()
    val recognizedText = recoverVoiceTranscript(state, strings.appLanguage)
    val title = when (state.status) {
        VoiceInputStatus.RequestingPermission -> strings.grantAccess
        VoiceInputStatus.Listening -> strings.voiceListening
        VoiceInputStatus.Processing -> strings.voiceProcessing
        VoiceInputStatus.Error -> strings.voiceInputUnavailable
        VoiceInputStatus.Idle -> strings.voice
    }
    val bodyText = when {
        cancelArmed -> if (strings.appLanguage == AppLanguage.SimplifiedChinese) {
            "松开取消"
        } else {
            "Release to cancel"
        }
        state.errorMessage.isNotBlank() && state.errorMessage != "permission_denied" -> state.errorMessage
        recognizedText.isNotBlank() -> recognizedText
        state.finalText.isNotBlank() -> state.finalText
        state.status == VoiceInputStatus.Error && state.errorMessage == "permission_denied" -> strings.voiceInputPermissionDenied
        state.status == VoiceInputStatus.Listening -> strings.voiceReleaseToAddDraft
        else -> strings.voiceHoldToTalk
    }
    val durationLabel = formatVoiceRecordingDuration(elapsedMs)
    val accentColor = if (cancelArmed) AetherError else AetherPrimary
    val bars = remember(state.level) { voiceLevelBars(state.level, count = 7) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (cancelArmed) {
                            AetherError.copy(alpha = 0.18f + state.level * 0.18f)
                        } else if (state.status == VoiceInputStatus.Listening) {
                            AetherPrimary.copy(alpha = 0.18f + state.level * 0.18f)
                        } else {
                            AetherSurface.copy(alpha = 0.84f)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = if (state.status == VoiceInputStatus.Listening) accentColor else AetherOnSurface,
                    modifier = Modifier.size(21.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = bodyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.status == VoiceInputStatus.Listening) {
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (cancelArmed) AetherError else AetherOnSurfaceVariant,
                    )
                }
                VoiceLevelBars(
                    levels = bars,
                    active = state.status == VoiceInputStatus.Listening,
                    activeColor = accentColor,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoicePanelAction(
                label = strings.cancel,
                onClick = onCancel,
                emphasized = false,
            )
        }
    }
}

@Composable
private fun VoiceLevelBars(
    levels: List<Float>,
    active: Boolean,
    activeColor: Color = AetherPrimary,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .width(54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        levels.forEachIndexed { index, level ->
            val targetHeight by animateDpAsState(
                targetValue = if (active) (7.dp + 23.dp * level) else 7.dp,
                animationSpec = tween(durationMillis = 120, easing = ChatGptMotionEasing),
                label = "voice_level_bar_$index",
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(targetHeight)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) activeColor.copy(alpha = 0.74f) else AetherOnSurfaceVariant.copy(alpha = 0.28f))
            )
        }
    }
}

private fun formatVoiceRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L) / 1_000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun VoicePanelAction(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean,
    enabled: Boolean = true,
) {
    val background = when {
        !enabled -> AetherSurface.copy(alpha = 0.42f)
        emphasized -> AetherOnSurface
        else -> AetherSurface.copy(alpha = 0.82f)
    }
    val foreground = when {
        !enabled -> AetherOnSurfaceVariant.copy(alpha = 0.46f)
        emphasized -> AetherSurface
        else -> AetherOnSurface
    }
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = foreground,
            maxLines = 1,
        )
    }
}

private data class ComposerSlashCommandItem(
    val id: ComposerSlashCommandId,
    val name: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private fun composerSlashCommandItems(language: AppLanguage): List<ComposerSlashCommandItem> {
    val isChinese = language == AppLanguage.SimplifiedChinese
    return listOf(
        ComposerSlashCommandItem(
            id = ComposerSlashCommandId.Plan,
            name = "plan",
            title = if (isChinese) "Plan Mode" else "Plan Mode",
            subtitle = if (isChinese) "Read-only planning before implementation" else "Read-only planning before implementation",
            icon = Icons.Rounded.Lightbulb,
        ),
        ComposerSlashCommandItem(
            id = ComposerSlashCommandId.Goal,
            name = "goal",
            title = if (isChinese) "Goal" else "Goal",
            subtitle = if (isChinese) "Set, pause, resume, or clear the thread goal" else "Set, pause, resume, or clear the thread goal",
            icon = Icons.Rounded.AutoAwesome,
        ),
        ComposerSlashCommandItem(
            id = ComposerSlashCommandId.Status,
            name = "status",
            title = if (isChinese) "Status" else "Status",
            subtitle = if (isChinese) "Show model, tools, modes, and task state" else "Show model, tools, modes, and task state",
            icon = Icons.Rounded.Search,
        ),
        ComposerSlashCommandItem(
            id = ComposerSlashCommandId.Review,
            name = "review",
            title = if (isChinese) "Review" else "Review",
            subtitle = if (isChinese) "Ask Aether to review current workspace changes" else "Ask Aether to review current workspace changes",
            icon = Icons.Rounded.Check,
        ),
        ComposerSlashCommandItem(
            id = ComposerSlashCommandId.Model,
            name = "model",
            title = if (isChinese) "Model" else "Model",
            subtitle = if (isChinese) "Switch the current chat model" else "Switch the current chat model",
            icon = Icons.Rounded.ArrowDropDown,
        ),
        ComposerSlashCommandItem(
            id = ComposerSlashCommandId.Tools,
            name = "tools",
            title = if (isChinese) "Tools" else "Tools",
            subtitle = if (isChinese) "Open tools, Skills, and MCP controls" else "Open tools, Skills, and MCP controls",
            icon = Icons.Rounded.Tune,
        ),
        ComposerSlashCommandItem(
            id = ComposerSlashCommandId.Permissions,
            name = "permissions",
            title = if (isChinese) "Permissions" else "Permissions",
            subtitle = if (isChinese) "Review read-only and workspace tool access" else "Review read-only and workspace tool access",
            icon = Icons.Rounded.Terminal,
        ),
    )
}

@Composable
private fun ComposerSlashCommandPanel(
    visible: Boolean,
    commands: List<ComposerSlashCommandItem>,
    onCommandSelected: (ComposerSlashCommandItem) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 10 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 10 }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(22.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(22.dp))
                .background(AetherSurface)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            commands.take(7).forEach { command ->
                ChatGptToolMenuRow(
                    title = "/${command.name}  ${command.title}",
                    subtitle = command.subtitle,
                    icon = command.icon,
                    trailing = null,
                    selected = false,
                    onClick = { onCommandSelected(command) },
                )
            }
        }
    }
}

@Composable
private fun ComposerModelCommandPanel(
    visibleState: MutableTransitionState<Boolean>,
    modelOptions: List<ProviderModelOption>,
    selectedModelKey: String,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit,
) {
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn() +
            scaleIn(initialScale = 0.96f, transformOrigin = TransformOrigin(0f, 1f)) +
            slideInVertically(initialOffsetY = { it / 12 }),
        exit = fadeOut() +
            scaleOut(targetScale = 0.97f, transformOrigin = TransformOrigin(0f, 1f)) +
            slideOutVertically(targetOffsetY = { it / 14 }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(22.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(22.dp))
                .background(AetherSurface)
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ComposerToolsSectionLabel(text = "Model")
            if (modelOptions.isEmpty()) {
                ChatGptToolMenuRow(
                    title = "No models available",
                    icon = Icons.Rounded.Close,
                    trailing = null,
                    selected = false,
                    enabled = false,
                    onClick = onDismiss,
                )
            } else {
                modelOptions.forEach { option ->
                    ChatGptToolMenuRow(
                        title = option.chatLabel,
                        subtitle = option.providerName,
                        icon = Icons.Rounded.AutoAwesome,
                        trailing = if (option.key == selectedModelKey) "On" else null,
                        selected = option.key == selectedModelKey,
                        onClick = { onModelSelected(option.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerIconButton(
    icon: ImageVector,
    contentDescription: String?,
    iconSize: Dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onGestureStateChanged: (Boolean) -> Unit = {},
) {
    val inputModifier = if (onPress != null && onRelease != null) {
        val density = LocalDensity.current
        val cancelThresholdPx = with(density) { 64.dp.toPx() }
        Modifier.pointerInput(onPress, onRelease, onCancel, cancelThresholdPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                onPress()
                var cancelArmed = false
                var finished = false
                while (!finished) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        onCancel?.invoke()
                        finished = true
                    } else if (!change.pressed) {
                        if (cancelArmed) onCancel?.invoke() else onRelease?.invoke()
                        finished = true
                    } else {
                        val dragDistance = change.position.y - down.position.y
                        val nextCancelArmed = dragDistance <= -cancelThresholdPx
                        if (nextCancelArmed != cancelArmed) {
                            cancelArmed = nextCancelArmed
                            onGestureStateChanged(cancelArmed)
                        }
                        change.consume()
                    }
                }
                onGestureStateChanged(false)
            }
        }
    } else {
        Modifier.clickable { onClick?.invoke() }
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (selected) AetherPrimary.copy(alpha = 0.14f) else Color.Transparent)
            .semantics {
                this.role = Role.Button
                if (selected) this.stateDescription = "Active"
            }
            .then(inputModifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) AetherPrimary else AetherOnSurface,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun ComposerToolsButton(
    selected: Boolean,
    planModeSelected: Boolean,
    goalModeSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) AetherSurfaceHigh else AetherSurfaceHigh.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = null,
            tint = AetherOnSurface,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = composerToolsEntryLabel(planModeSelected, goalModeSelected),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = AetherOnSurface,
            maxLines = 1,
        )
    }
}

internal fun composerToolsEntryLabel(
    planModeSelected: Boolean,
    goalModeSelected: Boolean = false,
): String = when {
    planModeSelected -> "Plan"
    goalModeSelected -> "Goal"
    else -> "Auto"
}

@Composable
private fun ComposerAttachmentPopup(
    visibleState: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
) {
    if (!visibleState.currentState && !visibleState.targetState) return
    val strings = rememberAetherStrings()
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomStart,
        offset = with(density) { IntOffset(0, -48.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn() + scaleIn(initialScale = 0.94f, transformOrigin = TransformOrigin(0f, 1f)),
            exit = fadeOut() + scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(0f, 1f)),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 260.dp)
                    .shadow(16.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AetherSurface)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ComposerPlusMenuRow(
                    title = strings.photos,
                    icon = Icons.Rounded.Image,
                    iconTint = AetherOnSurface,
                    iconContainerColor = Color.Transparent,
                    onClick = onPickImages,
                )
                ComposerPlusMenuRow(
                    title = strings.files,
                    icon = Icons.Rounded.AttachFile,
                    iconTint = AetherOnSurface,
                    iconContainerColor = Color.Transparent,
                    onClick = onPickFiles,
                )
            }
        }
    }
}

@Composable
private fun ComposerToolsPopup(
    visibleState: MutableTransitionState<Boolean>,
    agentModeAvailable: Boolean,
    agentModeSelected: Boolean,
    planModeSelected: Boolean,
    goalModeSelected: Boolean,
    enabledToolGroups: List<String>,
    allSkillsSelected: Boolean,
    allMcpServersSelected: Boolean,
    availableSkills: List<InstalledSkill>,
    availableMcpServers: List<McpServerConfig>,
    selectedSkillSet: Set<String>,
    selectedMcpServerSet: Set<String>,
    onDismiss: () -> Unit,
    onSetToolGroupEnabled: (String, Boolean) -> Unit,
    onSetAgentModeSelected: (Boolean) -> Unit,
    onSetPlanModeEnabled: (Boolean) -> Unit,
    onSetGoalModeEnabled: (Boolean) -> Unit,
    onSetSkillSelected: (String, Boolean) -> Unit,
    onSetMcpServerSelected: (String, Boolean) -> Unit,
) {
    if (!visibleState.currentState && !visibleState.targetState) return
    val strings = rememberAetherStrings()
    val isChinese = strings.appLanguage == AppLanguage.SimplifiedChinese
    val normalizedToolGroups = remember(enabledToolGroups) { normalizeChatToolGroups(enabledToolGroups) }
    val enabledGroupSet = remember(normalizedToolGroups) { normalizedToolGroups.toSet() }
    val extensionsEnabled = enabledGroupSet.contains(ChatToolGroups.Extensions)
    val activeExtensionSelectionCount = if (extensionsEnabled) {
        selectedSkillSet.size + selectedMcpServerSet.size
    } else {
        0
    }
    val activeCapabilityCount = normalizedToolGroups.size +
        activeExtensionSelectionCount +
        (if (agentModeSelected) 1 else 0) +
        (if (planModeSelected) 1 else 0) +
        (if (goalModeSelected) 1 else 0)
    fun groupEnabled(groupId: String): Boolean = enabledGroupSet.contains(groupId)
    fun toggleGroup(groupId: String) {
        onSetToolGroupEnabled(groupId, !groupEnabled(groupId))
    }
    fun onOffLabel(enabled: Boolean): String = if (enabled) {
        if (isChinese) "开启" else "On"
    } else {
        if (isChinese) "关闭" else "Off"
    }
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomStart,
        offset = with(density) { IntOffset((-56).dp.roundToPx(), -48.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn() +
                scaleIn(initialScale = 0.94f, transformOrigin = TransformOrigin(0f, 1f)) +
                slideInVertically(initialOffsetY = { it / 12 }),
            exit = fadeOut() +
                scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(0f, 1f)) +
                slideOutVertically(targetOffsetY = { it / 14 }),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 292.dp, max = 332.dp)
                    .shadow(18.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AetherSurface)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ComposerToolsSummaryCard(
                    title = composerToolsEntryLabel(planModeSelected, goalModeSelected),
                    subtitle = if (isChinese) {
                        if (planModeSelected) "只读探索并输出计划" else "按需使用已启用能力"
                    } else {
                        when {
                            planModeSelected -> "Explore read-only and propose a plan"
                            goalModeSelected -> "Keep working toward the active goal"
                            else -> "Use enabled capabilities as needed"
                        }
                    },
                    trailing = if (isChinese) {
                        "$activeCapabilityCount 项"
                    } else {
                        "$activeCapabilityCount on"
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))
                ComposerToolsSectionLabel(
                    text = if (isChinese) "能力" else "Capabilities",
                )
                ChatGptToolMenuRow(
                    title = if (isChinese) "计划模式" else "Plan Mode",
                    icon = Icons.Rounded.Lightbulb,
                    trailing = onOffLabel(planModeSelected),
                    selected = planModeSelected,
                    onClick = { onSetPlanModeEnabled(!planModeSelected) },
                )
                ChatGptToolMenuRow(
                    title = if (isChinese) "目标模式" else "Goal Mode",
                    icon = Icons.Rounded.AutoAwesome,
                    trailing = onOffLabel(goalModeSelected),
                    selected = goalModeSelected,
                    onClick = { onSetGoalModeEnabled(!goalModeSelected) },
                )
                ChatGptToolMenuRow(
                    title = if (isChinese) "文件与图片" else "Files & Images",
                    icon = Icons.Rounded.AttachFile,
                    trailing = onOffLabel(groupEnabled(ChatToolGroups.FilesImages)),
                    selected = groupEnabled(ChatToolGroups.FilesImages),
                    onClick = { toggleGroup(ChatToolGroups.FilesImages) },
                )
                ChatGptToolMenuRow(
                    title = if (isChinese) "终端" else "Terminal",
                    icon = Icons.Rounded.Terminal,
                    trailing = onOffLabel(groupEnabled(ChatToolGroups.Terminal)),
                    selected = groupEnabled(ChatToolGroups.Terminal),
                    onClick = { toggleGroup(ChatToolGroups.Terminal) },
                )
                ChatGptToolMenuRow(
                    title = if (isChinese) "网页" else "Web",
                    icon = Icons.Rounded.Public,
                    trailing = onOffLabel(groupEnabled(ChatToolGroups.Web)),
                    selected = groupEnabled(ChatToolGroups.Web),
                    onClick = { toggleGroup(ChatToolGroups.Web) },
                )
                ChatGptToolMenuRow(
                    title = if (isChinese) "技能 / MCP" else "Skills / MCP",
                    icon = Icons.Rounded.Extension,
                    trailing = onOffLabel(groupEnabled(ChatToolGroups.Extensions)),
                    selected = groupEnabled(ChatToolGroups.Extensions),
                    onClick = { toggleGroup(ChatToolGroups.Extensions) },
                )
                ChatGptToolMenuRow(
                    title = strings.agentMode,
                    icon = LucideIcons.MousePointer2,
                    trailing = when {
                        !agentModeAvailable -> if (isChinese) "不可用" else "Unavailable"
                        agentModeSelected -> onOffLabel(true)
                        else -> onOffLabel(false)
                    },
                    selected = agentModeSelected,
                    enabled = agentModeAvailable,
                    onClick = { onSetAgentModeSelected(!agentModeSelected) },
                )
                if (availableSkills.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ComposerToolsSectionLabel(text = "Skills")
                    ChatGptToolMenuRow(
                        title = if (allSkillsSelected) {
                            if (isChinese) "清空技能选择" else "Clear selected skills"
                        } else {
                            if (isChinese) "全选技能" else "Select all skills"
                        },
                        icon = Icons.Rounded.Check,
                        trailing = if (!extensionsEnabled) {
                            if (isChinese) "已暂停" else "Paused"
                        } else {
                            null
                        },
                        selected = extensionsEnabled && allSkillsSelected,
                        enabled = extensionsEnabled,
                        onClick = {
                            availableSkills.forEach { skill -> onSetSkillSelected(skill.id, !allSkillsSelected) }
                        },
                    )
                }
                availableSkills.forEach { skill ->
                    val selected = selectedSkillSet.contains(skill.id)
                    ChatGptToolMenuRow(
                        title = skill.quickActionLabel(),
                        icon = Icons.Rounded.Extension,
                        selected = extensionsEnabled && selected,
                        enabled = extensionsEnabled,
                        onClick = {
                            onSetSkillSelected(skill.id, !selected)
                        },
                    )
                }
                if (availableMcpServers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ComposerToolsSectionLabel(text = "MCP")
                    ChatGptToolMenuRow(
                        title = if (allMcpServersSelected) {
                            if (isChinese) "清空 MCP 选择" else "Clear selected MCP"
                        } else {
                            if (isChinese) "全选 MCP" else "Select all MCP"
                        },
                        icon = Icons.Rounded.Check,
                        trailing = if (!extensionsEnabled) {
                            if (isChinese) "已暂停" else "Paused"
                        } else {
                            null
                        },
                        selected = extensionsEnabled && allMcpServersSelected,
                        enabled = extensionsEnabled,
                        onClick = {
                            availableMcpServers.forEach { server -> onSetMcpServerSelected(server.id, !allMcpServersSelected) }
                        },
                    )
                }
                availableMcpServers.forEach { server ->
                    val selected = selectedMcpServerSet.contains(server.id)
                    val isStdIo = server.transport is McpTransportConfig.StdIo
                    ChatGptToolMenuRow(
                        title = server.quickActionLabel(),
                        icon = if (isStdIo) Icons.Rounded.Terminal else Icons.Rounded.Cloud,
                        selected = extensionsEnabled && selected,
                        enabled = extensionsEnabled,
                        onClick = {
                            onSetMcpServerSelected(server.id, !selected)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerToolsSummaryCard(
    title: String,
    subtitle: String,
    trailing: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(AetherSurface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = AetherOnSurface,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = AetherOnSurface,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ComposerToolsSectionLabel(
    text: String,
) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = AetherOnSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun ChatGptToolMenuRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected && enabled) AetherSurfaceHigh else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AetherOnSurface.copy(alpha = contentAlpha),
            modifier = Modifier.size(21.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
                color = AetherOnSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = AetherOnSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 1,
            )
        }
        if (selected && enabled) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = AetherOnSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ComposerFollowUpPopup(
    visible: Boolean,
    visibleState: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
    onSteerFollowUp: () -> Unit,
    onQueueFollowUp: () -> Unit,
) {
    if (!visible) return
    val strings = rememberAetherStrings()
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomEnd,
        offset = with(density) { IntOffset(0, -12.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn() + scaleIn(initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f)),
            exit = fadeOut() + scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 1f)),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 252.dp, max = 284.dp)
                    .shadow(20.dp, RoundedCornerShape(30.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                    .clip(RoundedCornerShape(30.dp))
                    .background(AetherSurface)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ComposerPlusMenuRow(
                    title = strings.steerCurrentRun,
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = AetherOnSurface,
                    iconContainerColor = Color.Transparent,
                    onClick = onSteerFollowUp,
                )
                ComposerPlusMenuRow(
                    title = strings.queueNextTurn,
                    icon = Icons.Rounded.ArrowUpward,
                    iconTint = AetherOnSurface,
                    iconContainerColor = Color.Transparent,
                    onClick = onQueueFollowUp,
                )
            }
        }
    }
}

@Composable
private fun ConversationComposerBar(
    modifier: Modifier = Modifier,
    value: String,
    attachments: List<ChatAttachment>,
    attachmentRevision: Long,
    availableSkills: List<InstalledSkill>,
    availableMcpServers: List<McpServerConfig>,
    selectedSkillIds: List<String>,
    selectedMcpServerIds: List<String>,
    agentModeAvailable: Boolean,
    agentModeSelected: Boolean,
    isEditing: Boolean,
    termuxSetupState: TermuxSetupState,
    isSending: Boolean,
    showStarterPromptHint: Boolean,
    showTermuxSetupNotice: Boolean,
    onValueChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSetSkillSelected: (String, Boolean) -> Unit,
    onSetMcpServerSelected: (String, Boolean) -> Unit,
    onSetAgentModeSelected: (Boolean) -> Unit,
    onCancelEdit: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onPauseGeneration: () -> Unit,
    onDismissStarterPromptHint: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onQueueFollowUp: () -> Unit,
    onSteerFollowUp: () -> Unit,
) {
    val strings = rememberAetherStrings()
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    val attachmentMenuVisibility = remember { MutableTransitionState(false) }
    attachmentMenuVisibility.targetState = attachmentMenuExpanded
    var toolsMenuExpanded by remember { mutableStateOf(false) }
    val toolsMenuVisibility = remember { MutableTransitionState(false) }
    toolsMenuVisibility.targetState = toolsMenuExpanded
    var followUpMenuExpanded by remember { mutableStateOf(false) }
    val followUpMenuVisibility = remember { MutableTransitionState(false) }
    followUpMenuVisibility.targetState = followUpMenuExpanded
    BackHandler(enabled = attachmentMenuExpanded) { attachmentMenuExpanded = false }
    BackHandler(enabled = toolsMenuExpanded) { toolsMenuExpanded = false }
    BackHandler(enabled = followUpMenuExpanded) { followUpMenuExpanded = false }
    var textFieldFocused by remember { mutableStateOf(false) }
    var measuredTextLineCount by remember { mutableIntStateOf(1) }
    var measuredTextHeight by remember { mutableStateOf(22.dp) }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val composerTapInteractionSource = remember { MutableInteractionSource() }
    val stableImeBottom by rememberStableImeBottom(focused = textFieldFocused)
    val stableImeVisible = stableImeBottom > ImeStabilizationMinVisibleHeight
    val selectedSkillSet = remember(selectedSkillIds) { selectedSkillIds.toSet() }
    val selectedMcpServerSet = remember(selectedMcpServerIds) { selectedMcpServerIds.toSet() }
    val allSkillsSelected = availableSkills.isNotEmpty() && availableSkills.all { selectedSkillSet.contains(it.id) }
    val allMcpServersSelected = availableMcpServers.isNotEmpty() && availableMcpServers.all { selectedMcpServerSet.contains(it.id) }
    val selectedSkillActions = remember(availableSkills, selectedSkillSet) {
        availableSkills.filter { selectedSkillSet.contains(it.id) }
    }
    val selectedMcpActions = remember(availableMcpServers, selectedMcpServerSet) {
        availableMcpServers.filter { selectedMcpServerSet.contains(it.id) }
    }
    val hasSelectedActions = selectedSkillActions.isNotEmpty() || selectedMcpActions.isNotEmpty() || agentModeSelected
    val composerPlaceholder = when {
        value.isNotBlank() -> ""
        attachments.isNotEmpty() -> if (strings.appLanguage == AppLanguage.SimplifiedChinese) "添加备注" else "Add a note"
        agentModeSelected && selectedSkillActions.isEmpty() && selectedMcpActions.isEmpty() ->
            if (strings.appLanguage == AppLanguage.SimplifiedChinese) "询问 Agent 模式" else "Ask Agent Mode"
        selectedSkillActions.size + selectedMcpActions.size == 1 && !agentModeSelected -> {
            selectedSkillActions.firstOrNull()?.quickActionLabel()
                ?: selectedMcpActions.firstOrNull()?.quickActionLabel()
                ?: strings.replyToAether
        }
        hasSelectedActions -> if (strings.appLanguage == AppLanguage.SimplifiedChinese) "使用所选工具提问" else "Ask with selected tools"
        else -> strings.askAether
    }
    val hasDraft = value.isNotBlank() || attachments.isNotEmpty()
    val canSendDraft = attachments.all { it.workspaceState == AttachmentWorkspaceState.Ready }
    val showPauseButton = isSending && !hasDraft
    val showSubmitButton = !isSending || hasDraft
    val keepPlusSeparated = value.isNotBlank() || hasSelectedActions
    val plusSeparated = keepPlusSeparated || textFieldFocused || stableImeVisible
    val explicitTextLineCount = if (value.isBlank()) {
        1
    } else {
        value.count { it == '\n' } + 1
    }
    val composerTextLineCount = if (value.isBlank()) {
        1
    } else {
        maxOf(explicitTextLineCount, measuredTextLineCount).coerceIn(1, 5)
    }
    val isMultilineComposer = composerTextLineCount > 1
    val composerTextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = AetherOnSurface,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
    val fieldTopPadding = when {
        hasSelectedActions -> 12.dp
        isMultilineComposer -> 12.dp
        else -> 8.dp
    }
    val fieldBottomPadding = when {
        hasSelectedActions -> 12.dp
        isMultilineComposer -> 12.dp
        else -> 8.dp
    }
    LaunchedEffect(textFieldFocused) {
        onFocusChanged(textFieldFocused)
    }
    val fieldStartPadding by animateDpAsState(
        targetValue = if (plusSeparated) 50.dp else 0.dp,
        animationSpec = tween(durationMillis = ComposerFocusTransitionMillis, easing = ChatGptMotionEasing),
        label = "composer_field_start",
    )
    val fieldContentStartPadding by animateDpAsState(
        targetValue = if (plusSeparated) 18.dp else 52.dp,
        animationSpec = tween(durationMillis = ComposerFocusTransitionMillis, easing = ChatGptMotionEasing),
        label = "composer_field_content_start",
    )
    val fieldMinHeight by animateDpAsState(
        targetValue = maxOf(
            116.dp,
            measuredTextHeight + fieldTopPadding + fieldBottomPadding + 54.dp,
        ),
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "composer_field_min_height",
    )
    val plusShadowElevation by animateDpAsState(
        targetValue = if (plusSeparated) 10.dp else 0.dp,
        animationSpec = tween(durationMillis = ComposerFocusTransitionMillis, easing = ChatGptMotionEasing),
        label = "composer_plus_shadow",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showTermuxSetupNotice) {
            TermuxSetupNotice(
                setupState = termuxSetupState,
                onRequestPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefresh = onRefreshTermuxSetup,
            )
        }
        if (showStarterPromptHint) {
            SurfaceNotice(
                title = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "你的第一条提示已准备好" else "Your first prompt is ready",
                subtitle = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "点击发送来测试 Aether。" else "Tap send to test Aether.",
                actionLabel = strings.hide,
                onAction = onDismissStarterPromptHint,
                actionEnabled = true,
            )
        }
        if (isEditing) {
            SurfaceNotice(
                title = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "正在编辑较早的消息" else "Editing earlier message",
                subtitle = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "发送后会替换它之后的回复。" else "Sending will replace the replies that came after it.",
                actionLabel = strings.cancel,
                onAction = onCancelEdit,
                actionEnabled = true,
            )
        }
        if (attachments.isNotEmpty()) {
            key(attachmentRevision) {
                ComposerAttachmentTray(
                    attachments = attachments,
                    onRemoveAttachment = onRemoveAttachment,
                )
            }
        }

        val fieldShape = if (plusSeparated) ComposerFocusedCardShape else ComposerCardShape
        val fieldControlAlignment = if (isMultilineComposer) Alignment.Bottom else Alignment.CenterVertically
        val fieldTextAlignment = if (isMultilineComposer) Alignment.TopStart else Alignment.CenterStart
        val plusButtonAlignment = if (isMultilineComposer || hasSelectedActions) Alignment.BottomStart else Alignment.CenterStart
        Box(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = fieldStartPadding)
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(y = 8.dp)
                        .blur(
                            radius = 22.dp,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                        )
                        .clip(fieldShape)
                        .background(ChatGptComposerShadow),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = fieldMinHeight)
                        .animateContentSize(
                            animationSpec = tween(durationMillis = 320, easing = ChatGptMotionEasing),
                        )
                        .clip(fieldShape)
                        .background(AetherSurface)
                        .clickable(
                            interactionSource = composerTapInteractionSource,
                            indication = null,
                        ) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                        .padding(
                            start = fieldContentStartPadding,
                            end = 8.dp,
                            top = fieldTopPadding,
                            bottom = fieldBottomPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (hasSelectedActions) 10.dp else 0.dp),
                ) {
                    AnimatedVisibility(
                        visible = hasSelectedActions,
                        enter = fadeIn(
                            animationSpec = tween(durationMillis = 220, easing = ChatGptMotionEasing),
                        ) + slideInVertically(
                            animationSpec = tween(durationMillis = 280, easing = ChatGptMotionEasing),
                            initialOffsetY = { -it / 2 },
                        ),
                        exit = fadeOut(
                            animationSpec = tween(durationMillis = 160, easing = ChatGptMotionEasing),
                        ) + slideOutVertically(
                            animationSpec = tween(durationMillis = 220, easing = ChatGptMotionEasing),
                            targetOffsetY = { -it / 3 },
                        ),
                    ) {
                        ComposerActionTray(
                            modifier = Modifier.fillMaxWidth(),
                            skills = selectedSkillActions,
                            mcpServers = selectedMcpActions,
                            agentModeSelected = agentModeSelected,
                            onRemoveSkill = { skillId -> onSetSkillSelected(skillId, false) },
                            onRemoveMcpServer = { serverId -> onSetMcpServerSelected(serverId, false) },
                            onRemoveAgentMode = { onSetAgentModeSelected(false) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = fieldControlAlignment,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = measuredTextHeight.coerceAtLeast(22.dp)),
                            contentAlignment = fieldTextAlignment,
                        ) {
                            if (value.isBlank()) {
                                Text(
                                    text = composerPlaceholder,
                                    style = composerTextStyle,
                                    color = Color(0xFF8C8C8C),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = value,
                                onValueChange = onValueChange,
                                enabled = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        if (textFieldFocused != focusState.isFocused) {
                                            textFieldFocused = focusState.isFocused
                                        }
                                    },
                                textStyle = composerTextStyle,
                                maxLines = 5,
                                onTextLayout = { textLayoutResult ->
                                    val lineCount = textLayoutResult.lineCount.coerceIn(1, 5)
                                    if (measuredTextLineCount != lineCount) {
                                        measuredTextLineCount = lineCount
                                    }
                                    val visibleLineBottom = textLayoutResult.getLineBottom(lineCount - 1)
                                    val visibleLineTop = textLayoutResult.getLineTop(0)
                                    val textHeight = with(density) {
                                        (visibleLineBottom - visibleLineTop).toDp()
                                    }
                                    if (measuredTextHeight != textHeight) {
                                        measuredTextHeight = textHeight
                                    }
                                },
                                cursorBrush = SolidColor(AetherOnSurface),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (showPauseButton) {
                            ComposerPauseButton(
                                onClick = onPauseGeneration,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (showSubmitButton) {
                            Box {
                                ComposerSubmitButton(
                                    hasDraft = hasDraft,
                                    canSendDraft = canSendDraft,
                                    isSending = isSending,
                                    onClick = {
                                        if (!hasDraft || !canSendDraft) return@ComposerSubmitButton
                                        if (isSending) {
                                            followUpMenuExpanded = true
                                        } else {
                                            onSend()
                                        }
                                    },
                                )
                                if (isSending && (followUpMenuVisibility.currentState || followUpMenuVisibility.targetState)) {
                                    Popup(
                                        alignment = Alignment.BottomEnd,
                                        offset = with(density) {
                                            IntOffset(0, -12.dp.roundToPx())
                                        },
                                        onDismissRequest = { followUpMenuExpanded = false },
                                        properties = PopupProperties(
                                            focusable = false,
                                            dismissOnBackPress = true,
                                            dismissOnClickOutside = true,
                                        ),
                                    ) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visibleState = followUpMenuVisibility,
                                            enter = fadeIn() +
                                                scaleIn(
                                                    initialScale = 0.92f,
                                                    transformOrigin = TransformOrigin(1f, 1f),
                                                ) +
                                                slideInVertically(initialOffsetY = { it / 10 }),
                                            exit = fadeOut() +
                                                scaleOut(
                                                    targetScale = 0.96f,
                                                    transformOrigin = TransformOrigin(1f, 1f),
                                                ) +
                                                slideOutVertically(targetOffsetY = { it / 12 }),
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .widthIn(min = 252.dp, max = 284.dp)
                                                    .shadow(20.dp, RoundedCornerShape(30.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                                                    .clip(RoundedCornerShape(30.dp))
                                                    .background(AetherSurface)
                                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                ComposerPlusMenuRow(
                                                    title = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "引导当前运行" else "Steer current run",
                                                    icon = Icons.Rounded.AutoAwesome,
                                                    iconTint = Color(0xFF8D6C2F),
                                                    iconContainerColor = Color(0xFFFFF3DE),
                                                    onClick = {
                                                        followUpMenuExpanded = false
                                                        onSteerFollowUp()
                                                    },
                                                )
                                                ComposerPlusMenuRow(
                                                    title = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "排队下一轮" else "Queue next turn",
                                                    icon = Icons.Rounded.ArrowUpward,
                                                    iconTint = Color(0xFF2F6DA3),
                                                    iconContainerColor = Color(0xFFEAF2FF),
                                                    onClick = {
                                                        followUpMenuExpanded = false
                                                        onQueueFollowUp()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(plusButtonAlignment)
                    .size(48.dp)
                    .shadow(plusShadowElevation, CircleShape, ambientColor = ChatGptControlShadow, spotColor = ChatGptControlShadow)
                    .clip(CircleShape)
                    .background(if (plusSeparated) AetherSurface else Color.Transparent)
                    .clickable(onClick = { attachmentMenuExpanded = !attachmentMenuExpanded }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "添加附件或工具" else "Add attachment or tool",
                    tint = AetherOnSurface,
                    modifier = Modifier.size(27.dp),
                )
                if (attachmentMenuVisibility.currentState || attachmentMenuVisibility.targetState) {
                    Popup(
                        alignment = Alignment.BottomStart,
                        offset = with(density) {
                            IntOffset(0, -42.dp.roundToPx())
                        },
                        onDismissRequest = { attachmentMenuExpanded = false },
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                        ),
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = attachmentMenuVisibility,
                            enter = fadeIn() +
                                scaleIn(
                                    initialScale = 0.92f,
                                    transformOrigin = TransformOrigin(0f, 1f),
                                ) +
                                slideInVertically(initialOffsetY = { it / 10 }),
                            exit = fadeOut() +
                                scaleOut(
                                    targetScale = 0.96f,
                                    transformOrigin = TransformOrigin(0f, 1f),
                                ) +
                                slideOutVertically(targetOffsetY = { it / 12 }),
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(min = 284.dp, max = 304.dp)
                                    .shadow(20.dp, RoundedCornerShape(30.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                                    .clip(RoundedCornerShape(30.dp))
                                    .background(AetherSurface)
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                ComposerPlusMenuRow(
                                    title = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "照片" else "Photos",
                                    icon = Icons.Rounded.Image,
                                    iconTint = Color(0xFF4E8D5A),
                                    iconContainerColor = AetherSurfaceHigh,
                                    onClick = {
                                        attachmentMenuExpanded = false
                                        onPickImages()
                                    },
                                )
                                ComposerPlusMenuRow(
                                    title = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "文件" else "Files",
                                    icon = Icons.Rounded.AttachFile,
                                    iconTint = AetherOnSurface,
                                    iconContainerColor = AetherSurfaceHigh,
                                    onClick = {
                                        attachmentMenuExpanded = false
                                        onPickFiles()
                                    },
                                )
                                if (agentModeAvailable || availableSkills.isNotEmpty() || availableMcpServers.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                                if (agentModeAvailable) {
                                    ComposerPlusMenuRow(
                                        title = strings.agentMode,
                                        icon = LucideIcons.MousePointer2,
                                        selected = agentModeSelected,
                                        iconTint = Color(0xFF6D5CFF),
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            attachmentMenuExpanded = false
                                            onSetAgentModeSelected(!agentModeSelected)
                                        },
                                    )
                                }
                                if (availableSkills.isNotEmpty()) {
                                    ComposerPlusMenuRow(
                                        title = if (allSkillsSelected) {
                                            if (strings.appLanguage == AppLanguage.SimplifiedChinese) "清空技能选择" else "Clear selected skills"
                                        } else {
                                            if (strings.appLanguage == AppLanguage.SimplifiedChinese) "全选技能" else "Select all skills"
                                        },
                                        icon = Icons.Rounded.Check,
                                        selected = allSkillsSelected,
                                        iconTint = Color(0xFF9C6B2F),
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            attachmentMenuExpanded = false
                                            availableSkills.forEach { skill ->
                                                onSetSkillSelected(skill.id, !allSkillsSelected)
                                            }
                                        },
                                    )
                                }
                                availableSkills.forEach { skill ->
                                    val selected = selectedSkillSet.contains(skill.id)
                                    ComposerPlusMenuRow(
                                        title = skill.quickActionLabel(),
                                        icon = Icons.Rounded.Extension,
                                        selected = selected,
                                        iconTint = Color(0xFF9C6B2F),
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            attachmentMenuExpanded = false
                                            onSetSkillSelected(skill.id, !selected)
                                        },
                                    )
                                }
                                if (availableMcpServers.isNotEmpty()) {
                                    ComposerPlusMenuRow(
                                        title = if (allMcpServersSelected) {
                                            if (strings.appLanguage == AppLanguage.SimplifiedChinese) "清空 MCP 选择" else "Clear selected MCP"
                                        } else {
                                            if (strings.appLanguage == AppLanguage.SimplifiedChinese) "全选 MCP" else "Select all MCP"
                                        },
                                        icon = Icons.Rounded.Check,
                                        selected = allMcpServersSelected,
                                        iconTint = Color(0xFF2A9C9A),
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            attachmentMenuExpanded = false
                                            availableMcpServers.forEach { server ->
                                                onSetMcpServerSelected(server.id, !allMcpServersSelected)
                                            }
                                        },
                                    )
                                }
                                availableMcpServers.forEach { server ->
                                    val selected = selectedMcpServerSet.contains(server.id)
                                    val isStdIo = server.transport is McpTransportConfig.StdIo
                                    ComposerPlusMenuRow(
                                        title = server.quickActionLabel(),
                                        icon = if (isStdIo) Icons.Rounded.Terminal else Icons.Rounded.Cloud,
                                        selected = selected,
                                        iconTint = if (isStdIo) Color(0xFF2F6DA3) else Color(0xFF2A9C9A),
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            attachmentMenuExpanded = false
                                            onSetMcpServerSelected(server.id, !selected)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerPauseButton(
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(AetherOnSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .offset(x = 0.5.dp)
                .size(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AetherSurface)
        )
    }
}

@Composable
private fun ComposerSubmitButton(
    hasDraft: Boolean,
    canSendDraft: Boolean,
    isSending: Boolean,
    onClick: () -> Unit,
) {
    val strings = rememberAetherStrings()
    val enabled = hasDraft && canSendDraft
    val buttonColor = if (enabled) AetherOnSurface else AetherSurfaceHigher
    val iconColor = if (enabled) AetherSurface else AetherOnSurfaceVariant.copy(alpha = 0.48f)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(buttonColor)
            .clickable(
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.ArrowUpward,
            contentDescription = if (isSending) {
                if (strings.appLanguage == AppLanguage.SimplifiedChinese) "发送跟进" else "Send follow-up"
            } else {
                strings.send
            },
            tint = iconColor,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun ComposerActionTray(
    modifier: Modifier = Modifier,
    enabledToolGroups: List<String> = emptyList(),
    skills: List<InstalledSkill>,
    mcpServers: List<McpServerConfig>,
    agentModeSelected: Boolean,
    onRemoveToolGroup: (String) -> Unit = {},
    onRemoveSkill: (String) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onRemoveAgentMode: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        enabledToolGroups.forEach { groupId ->
            ComposerActionChip(
                label = toolGroupLabel(groupId, strings.appLanguage),
                icon = toolGroupIcon(groupId),
                onRemove = { onRemoveToolGroup(groupId) },
            )
        }
        if (agentModeSelected) {
            ComposerActionChip(
                label = strings.agentMode,
                icon = LucideIcons.MousePointer2,
                onRemove = onRemoveAgentMode,
            )
        }
        skills.forEach { skill ->
            ComposerActionChip(
                label = skill.quickActionLabel(),
                icon = Icons.Rounded.Extension,
                onRemove = { onRemoveSkill(skill.id) },
            )
        }
        mcpServers.forEach { server ->
            ComposerActionChip(
                label = server.quickActionLabel(),
                icon = if (server.transport is McpTransportConfig.StdIo) {
                    Icons.Rounded.Terminal
                } else {
                    Icons.Rounded.Cloud
                },
                onRemove = { onRemoveMcpServer(server.id) },
            )
        }
    }
}

@Composable
private fun AgentModePreviewPanel(
    displayState: AgentModeDisplayState,
    toolInvocation: ChatToolInvocation?,
) {
    val strings = rememberAetherStrings()
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        displayState.latestPreviewPath,
        displayState.lastUpdatedMillis,
    ) {
        value = withContext(Dispatchers.IO) {
            displayState.latestPreviewPath
                .takeIf { it.isNotBlank() }
                ?.let { BitmapFactory.decodeFile(it) }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (toolInvocation != null) {
            AgentModePreviewToolStatus(toolInvocation = toolInvocation)
        } else {
            AgentModePreviewHeader(displayState = displayState)
        }
        if (displayState.userMessage.isNotBlank() || displayState.suggestion.isNotBlank()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (displayState.userMessage.isNotBlank()) {
                    Text(
                        text = displayState.userMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurface,
                    )
                }
                if (displayState.suggestion.isNotBlank()) {
                    Text(
                        text = displayState.suggestion,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
        }
        val previewBitmap = bitmap
        if (previewBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(agentModePreviewBackdropBrush()),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "Agent 模式虚拟显示" else "Agent Mode virtual display",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            Text(
                text = if (strings.appLanguage == AppLanguage.SimplifiedChinese) "代理启动后会显示虚拟屏幕预览。" else "Virtual display preview will appear after the agent starts.",
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentModePreviewHeader(
    displayState: AgentModeDisplayState,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = LucideIcons.MousePointer2,
            contentDescription = null,
            tint = Color(0xFF6D5CFF),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = strings.agentMode,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = displayState.displayId?.let {
                if (strings.appLanguage == AppLanguage.SimplifiedChinese) "显示 $it" else "display $it"
            } ?: if (strings.appLanguage == AppLanguage.SimplifiedChinese) "待机" else "standby",
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun AgentModePreviewToolStatus(
    toolInvocation: ChatToolInvocation,
) {
    val label = formatPendingAgentModeToolLabel(toolInvocation)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (toolInvocation.toolName == "agent_display") {
                LucideIcons.MousePointer2
            } else {
                Icons.Rounded.AutoAwesome
            },
            contentDescription = null,
            tint = Color(0xFF5D7CFF),
            modifier = Modifier.size(16.dp),
        )
        if (toolInvocation.isRunning) {
            ShimmerStatusText(
                text = label,
                modifier = Modifier.weight(1f),
                travelDurationMillis = 2600,
                pauseDurationMillis = 900,
            )
        } else {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun formatPendingAgentModeToolLabel(toolInvocation: ChatToolInvocation): String {
    val strings = rememberAetherStrings()
    val arguments = parseJsonObject(toolInvocation.argumentsJson)
    return strings.toolInvocationTitleLabel(
        toolName = toolInvocation.toolName,
        isRunning = toolInvocation.isRunning,
        arguments = arguments,
    )
}

private fun parseJsonObject(rawValue: String): JSONObject? =
    if (rawValue.isBlank()) null else runCatching { JSONObject(rawValue) }.getOrNull()

private fun toolGroupLabel(
    groupId: String,
    language: AppLanguage,
): String = when (groupId) {
    ChatToolGroups.FilesImages -> if (language == AppLanguage.SimplifiedChinese) "\u6587\u4ef6\u4e0e\u56fe\u7247" else "Files & images"
    ChatToolGroups.Terminal -> if (language == AppLanguage.SimplifiedChinese) "\u7ec8\u7aef" else "Terminal"
    ChatToolGroups.Web -> if (language == AppLanguage.SimplifiedChinese) "\u7f51\u9875" else "Web"
    ChatToolGroups.Extensions -> if (language == AppLanguage.SimplifiedChinese) "\u6280\u80fd / MCP" else "Skills / MCP"
    else -> groupId
}

private fun toolGroupIcon(groupId: String): ImageVector = when (groupId) {
    ChatToolGroups.FilesImages -> Icons.Rounded.AttachFile
    ChatToolGroups.Terminal -> Icons.Rounded.Terminal
    ChatToolGroups.Web -> Icons.Rounded.Public
    ChatToolGroups.Extensions -> Icons.Rounded.Extension
    else -> Icons.Rounded.AutoAwesome
}

private fun agentModePreviewBackdropBrush(): Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFFBEEBFF),
        0.22f to Color(0xFF75C7FF),
        0.44f to Color(0xFFD5E9FF),
        0.68f to Color(0xFF83B5FF),
        1.00f to Color(0xFF4E86F7),
    ),
    start = Offset.Zero,
    end = Offset(900f, 620f),
)

@Composable
private fun ComposerActionChip(
    label: String,
    icon: ImageVector,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFE8F1FF))
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF4F8CFF),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = Color(0xFF2E6FD5),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove",
                tint = Color(0xFF4F8CFF),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ComposerPlusMenuRow(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    iconContainerColor: Color,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconContainerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnSurface,
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(AetherPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = AetherPrimary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
