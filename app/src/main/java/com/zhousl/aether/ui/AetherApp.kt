package com.zhousl.aether.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.net.toUri
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.zhousl.aether.data.AetherPrivacyPolicyUrl
import com.zhousl.aether.data.AetherWebsiteUrl
import com.zhousl.aether.data.AgentModeAuthorizationMethod
import com.zhousl.aether.data.AgentTaskState
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.AgentLoopState
import com.zhousl.aether.data.SessionExecutionState
import com.zhousl.aether.data.AutomaticModelPurpose
import com.zhousl.aether.data.LlmProviderConfig
import com.zhousl.aether.data.ProviderModelOption
import com.zhousl.aether.data.WorkspaceFileBridge
import com.zhousl.aether.data.availableModelOptions
import com.zhousl.aether.data.buildModelOptionKey
import com.zhousl.aether.data.isOnboardingComplete
import com.zhousl.aether.data.resolveAutomaticModelKey
import com.zhousl.aether.data.shouldShowResumeSetupBanner
import com.zhousl.aether.termux.TermuxContract
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherScrim
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import com.zhousl.aether.ui.theme.AetherSurfaceHigher
import com.zhousl.aether.ui.theme.AetherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


private data class SuggestionAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
)

private sealed interface PendingSaveTarget {
    data class Attachment(val attachment: ChatAttachment) : PendingSaveTarget
    data class WorkspaceFile(val rawLink: String) : PendingSaveTarget
}

private const val ScreenTransitionDuration = 320
private val ScreenTransitionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)
private const val AssistantLocalFileOpenByteLimit = 32 * 1024 * 1024
private const val AssistantLocalFileCacheDirectory = "assistant-local-open"
private const val PrivacyPolicyAnnotationTag = "privacy_policy"

data class MarketAlertLaunchRequest(
    val id: Long = System.currentTimeMillis(),
    val symbol: String,
    val ruleId: String,
    val autoAnalyze: Boolean,
)

private fun tr(strings: AetherStrings, english: String, chinese: String): String =
    if (strings.appLanguage == com.zhousl.aether.data.AppLanguage.SimplifiedChinese) chinese else english

private fun AppScreen.depth(): Int = when (this) {
    AppScreen.Onboarding -> 0
    AppScreen.Inbox -> 1
    AppScreen.Chat -> 2
    AppScreen.MarketMonitor -> 2
    AppScreen.Settings -> 3
}

@Composable
fun AetherApp(
    viewModel: AetherViewModel = viewModel(),
    onPrivacyPolicyAccepted: () -> Unit = {},
    marketAlertRequest: MarketAlertLaunchRequest? = null,
    onMarketAlertRequestConsumed: () -> Unit = {},
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    // 流式执行状态单独订阅，逐 token 更新只影响依赖它的子树，不会重组整包 uiState。
    val executionStates = viewModel.executionStates.collectAsStateWithLifecycle().value
    val strings = remember(uiState.settings.language) { aetherStringsFor(uiState.settings.language) }

    LaunchedEffect(marketAlertRequest?.id) {
        val request = marketAlertRequest ?: return@LaunchedEffect
        if (request.autoAnalyze) {
            viewModel.analyzeMarketAlert(request.symbol, request.ruleId)
        } else {
            viewModel.openMarketMonitor()
        }
        onMarketAlertRequestConsumed()
    }

    AetherLocalization(uiState.settings.language) {
        AetherTheme(themeMode = uiState.settings.themeMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                AetherAppContent(
                    viewModel = viewModel,
                    uiState = uiState,
                    executionStates = executionStates,
                    strings = strings,
                    onPrivacyPolicyAccepted = onPrivacyPolicyAccepted,
                )
            }
        }
    }
}

@Composable
private fun AetherAppContent(
    viewModel: AetherViewModel,
    uiState: AetherUiState,
    executionStates: Map<String, SessionExecutionState>,
    strings: AetherStrings,
    onPrivacyPolicyAccepted: () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    val workspaceFileBridge = remember(context) { WorkspaceFileBridge(context) }
    val activeSession = uiState.sessions.firstOrNull { it.id == uiState.currentSessionId }
    val activeProviderConfig = uiState.providerConfigs.firstOrNull { it.isEnabled }
        ?: uiState.providerConfigs.firstOrNull()
    val currentSessionExecution = executionStates[uiState.currentSessionId]
    val currentMessages = activeSession?.messages.orEmpty()
    val selectedSkillIds = activeSession?.selectedSkillIds ?: uiState.draftSelectedSkillIds
    val selectedMcpServerIds = activeSession?.activeMcpServerIds ?: uiState.draftSelectedMcpServerIds
    val agentModeSelected = activeSession?.agentModeEnabled ?: uiState.draftAgentModeEnabled
    val enabledToolGroups = activeSession?.enabledToolGroups ?: uiState.draftEnabledToolGroups
    val planModeSelected = activeSession?.planModeEnabled ?: uiState.draftPlanModeEnabled
    val goalModeSelected = activeSession?.goalModeEnabled ?: uiState.draftGoalModeEnabled
    val currentTaskSnapshot = remember(
        activeSession,
        currentSessionExecution,
        uiState.unviewedCompletedSessionIds,
        uiState.settings.language,
    ) {
        activeSession?.toTaskWorkbenchSnapshot(
            executionState = currentSessionExecution,
            isUnviewedComplete = uiState.unviewedCompletedSessionIds.contains(activeSession.id),
            language = uiState.settings.language,
        )
    }
    val conversationModelOptions = remember(uiState.providerConfigs, uiState.settings) {
        buildConversationModelOptions(
            settings = uiState.settings,
            providerConfigs = uiState.providerConfigs,
        )
    }
    val selectedConversationModelKey = remember(
        activeSession?.selectedModelKey,
        uiState.draftSelectedModelKey,
        uiState.settings.defaultChatModelKey,
        conversationModelOptions,
    ) {
        resolveConversationModelKey(
            session = activeSession,
            draftSelectedModelKey = uiState.draftSelectedModelKey,
            defaultChatModelKey = uiState.settings.defaultChatModelKey,
            options = conversationModelOptions,
        )
    }
    val pendingToolInvocations = currentSessionExecution?.pendingToolInvocations.orEmpty()
    val pendingResponseBlocks = currentSessionExecution?.pendingResponseBlocks.orEmpty()
    val pendingAssistantText = currentSessionExecution?.pendingAssistantText.orEmpty()
    val pendingInputs = currentSessionExecution?.pendingInputs.orEmpty()
    val isCurrentSessionRunning = currentSessionExecution?.isRunning == true
    val currentWorkspaceSessionId = uiState.editingSessionId
        ?: uiState.draftWorkspaceId
        ?: uiState.currentSessionId
    val currentWorkspaceDirectory = workspaceFileBridge.workspaceDirectory(currentWorkspaceSessionId)

    LaunchedEffect(viewModel, context) {
        viewModel.transientMessages.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(uiState.appUpdate.pendingInstallUri) {
        val installUri = uiState.appUpdate.pendingInstallUri
        if (installUri.isNotBlank()) {
            requestApkInstall(context, installUri.toUri())
            viewModel.consumePendingUpdateInstallUri()
        }
    }
    LaunchedEffect(uiState.settings.privacyPolicyAccepted) {
        if (uiState.settings.privacyPolicyAccepted) {
            onPrivacyPolicyAccepted()
        }
    }

    var pendingSaveTarget by remember { mutableStateOf<PendingSaveTarget?>(null) }
    var pendingSessionExportId by remember { mutableStateOf<String?>(null) }
    var pendingSkillZipCompletion by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var pendingTermuxPermissionSource by remember { mutableStateOf("unknown") }
    val onPickedDocuments: (List<Uri>) -> Unit = { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        viewModel.appendDraftAttachments(uris)
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = onPickedDocuments,
    )
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = onPickedDocuments,
    )
    var voiceInputState by remember { mutableStateOf(VoiceInputUiState()) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var voiceInputCanceled by remember { mutableStateOf(false) }
    var voiceInputHeld by remember { mutableStateOf(false) }
    var voiceInputRecoveryRestartCount by remember { mutableStateOf(0) }

    fun destroyVoiceRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun voiceTranscriptFromBundle(results: Bundle?): String =
        results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

    fun currentRecoveredVoiceTranscript(): String =
        recoverVoiceTranscript(
            state = voiceInputState,
            language = uiState.settings.language,
        )

    fun finishVoiceInputWithTranscript(
        transcript: String,
        retainedAfterInterruption: Boolean,
    ) {
        val normalizedTranscript = transcript.trim()
        if (normalizedTranscript.isBlank()) {
            Toast.makeText(context, strings.voiceInputEmpty, Toast.LENGTH_SHORT).show()
            voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Cancel)
            return
        }
        voiceInputHeld = false
        voiceInputState = reduceVoiceInputState(
            voiceInputState,
            VoiceInputEvent.FinalText(normalizedTranscript),
        )
        viewModel.appendVoiceTranscript(normalizedTranscript)
        Toast.makeText(
            context,
            if (retainedAfterInterruption) strings.voiceInputInterruptedRetained else strings.voiceInputAdded,
            Toast.LENGTH_SHORT,
        ).show()
        voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Cancel)
    }

    fun finishVoiceInputFromRecoveredText(retainedAfterInterruption: Boolean) {
        finishVoiceInputWithTranscript(
            transcript = currentRecoveredVoiceTranscript(),
            retainedAfterInterruption = retainedAfterInterruption,
        )
    }

    fun cancelVoiceInput() {
        voiceInputHeld = false
        voiceInputCanceled = true
        voiceInputRecoveryRestartCount = 0
        speechRecognizer?.cancel()
        destroyVoiceRecognizer()
        voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Cancel)
    }

    fun isEmptyVoiceResultError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    fun isFatalVoiceInputError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

    fun startVoiceInputListening(continuing: Boolean = false) {
        fun scheduleVoiceInputRestart(delayMillis: Long = 240L) {
            scope.launch {
                delay(delayMillis)
                if (voiceInputHeld && !voiceInputCanceled) {
                    startVoiceInputListening(continuing = true)
                }
            }
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            voiceInputHeld = false
            voiceInputState = reduceVoiceInputState(
                voiceInputState,
                VoiceInputEvent.Failed(strings.voiceInputUnavailable),
            )
            Toast.makeText(context, strings.voiceInputUnavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val languageTag = if (uiState.settings.language == AppLanguage.SimplifiedChinese) "zh-CN" else "en-US"
        voiceInputCanceled = false
        destroyVoiceRecognizer()
        voiceInputState = reduceVoiceInputState(
            voiceInputState,
            if (continuing) VoiceInputEvent.ContinueListening else VoiceInputEvent.StartListening,
        )
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!voiceInputCanceled) {
                    voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.ContinueListening)
                }
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (!voiceInputCanceled) {
                    voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.LevelChanged(rmsdB))
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!voiceInputCanceled) {
                    voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Processing)
                }
            }

            override fun onError(error: Int) {
                destroyVoiceRecognizer()
                if (voiceInputCanceled) {
                    voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Cancel)
                    return
                }
                val recoveredTranscript = currentRecoveredVoiceTranscript()
                if (shouldContinueVoiceInputAfterError(
                        isHolding = voiceInputHeld,
                        isFatalError = isFatalVoiceInputError(error),
                        hasRecoverableTranscript = recoveredTranscript.isNotBlank(),
                        emptyRestartCount = voiceInputRecoveryRestartCount,
                    )
                ) {
                    val pendingSegment = pendingVoiceTranscriptSegment(voiceInputState)
                    if (pendingSegment.isNotBlank()) {
                        voiceInputRecoveryRestartCount = 0
                        voiceInputState = reduceVoiceInputState(
                            voiceInputState,
                            VoiceInputEvent.CommitSegment(
                                text = pendingSegment,
                                language = uiState.settings.language,
                            ),
                        )
                        scheduleVoiceInputRestart()
                    } else {
                        voiceInputRecoveryRestartCount += 1
                        voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.ContinueListening)
                        scheduleVoiceInputRestart(delayMillis = 420L + voiceInputRecoveryRestartCount * 160L)
                    }
                    return
                }
                val resolution = resolveVoiceInputError(
                    hasRecoverableTranscript = currentRecoveredVoiceTranscript().isNotBlank(),
                    isEmptyResultError = isEmptyVoiceResultError(error),
                )
                voiceInputHeld = false
                voiceInputRecoveryRestartCount = 0
                when (resolution) {
                    VoiceInputErrorResolution.CommitRecoveredTranscript -> {
                        finishVoiceInputFromRecoveredText(retainedAfterInterruption = true)
                    }
                    VoiceInputErrorResolution.ShowEmptyMessage -> {
                        Toast.makeText(context, strings.voiceInputEmpty, Toast.LENGTH_SHORT).show()
                        voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Cancel)
                    }
                    VoiceInputErrorResolution.ShowInterruptedMessage -> {
                        Toast.makeText(context, strings.voiceInputInterruptedRetry, Toast.LENGTH_SHORT).show()
                        voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Cancel)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                destroyVoiceRecognizer()
                if (voiceInputCanceled) {
                    voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Cancel)
                    return
                }
                val voiceResult = resolveVoiceInputResult(
                    isOk = true,
                    rawTranscript = voiceTranscriptFromBundle(results),
                )
                if (voiceResult.showEmptyMessage) {
                    if (voiceInputHeld) {
                        if (voiceInputRecoveryRestartCount < 3) {
                            voiceInputRecoveryRestartCount += 1
                            scheduleVoiceInputRestart(delayMillis = 420L + voiceInputRecoveryRestartCount * 160L)
                        } else {
                            voiceInputHeld = false
                            voiceInputRecoveryRestartCount = 0
                            finishVoiceInputFromRecoveredText(retainedAfterInterruption = false)
                        }
                    } else {
                        finishVoiceInputFromRecoveredText(retainedAfterInterruption = false)
                    }
                } else {
                    if (voiceInputHeld) {
                        voiceInputRecoveryRestartCount = 0
                        voiceInputState = reduceVoiceInputState(
                            voiceInputState,
                            VoiceInputEvent.CommitSegment(
                                text = voiceResult.transcript,
                                language = uiState.settings.language,
                            ),
                        )
                        scheduleVoiceInputRestart()
                    } else {
                        voiceInputRecoveryRestartCount = 0
                        finishVoiceInputWithTranscript(
                            transcript = mergeVoiceTranscriptSegment(
                                committedTranscript = voiceInputState.committedText,
                                segment = voiceResult.transcript,
                                language = uiState.settings.language,
                            ),
                            retainedAfterInterruption = false,
                        )
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!voiceInputCanceled) {
                    voiceInputState = reduceVoiceInputState(
                        voiceInputState,
                        VoiceInputEvent.PartialText(voiceTranscriptFromBundle(partialResults)),
                    )
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, strings.voice)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30_000L)
        }
        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            destroyVoiceRecognizer()
            voiceInputHeld = false
            voiceInputRecoveryRestartCount = 0
            voiceInputState = reduceVoiceInputState(
                voiceInputState,
                VoiceInputEvent.Failed(strings.voiceInputUnavailable),
            )
            Toast.makeText(context, strings.voiceInputUnavailable, Toast.LENGTH_SHORT).show()
        }
    }

    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                voiceInputRecoveryRestartCount = 0
                voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Cancel)
                Toast.makeText(context, strings.voiceHoldToTalk, Toast.LENGTH_SHORT).show()
            } else {
                voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.PermissionDenied)
                Toast.makeText(context, strings.voiceInputPermissionDenied, Toast.LENGTH_SHORT).show()
            }
        },
    )

    fun startVoiceInputHold() {
        if (voiceInputState.isActive) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceInputHeld = true
            voiceInputRecoveryRestartCount = 0
            startVoiceInputListening()
        } else {
            voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.RequestPermission)
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun releaseVoiceInputHold() {
        if (!voiceInputState.isActive || voiceInputState.status == VoiceInputStatus.RequestingPermission) return
        voiceInputHeld = false
        voiceInputRecoveryRestartCount = 0
        if (speechRecognizer != null) {
            voiceInputState = reduceVoiceInputState(voiceInputState, VoiceInputEvent.Processing)
            speechRecognizer?.stopListening()
        } else {
            finishVoiceInputFromRecoveredText(retainedAfterInterruption = false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceInputHeld = false
            voiceInputRecoveryRestartCount = 0
            speechRecognizer?.cancel()
            destroyVoiceRecognizer()
        }
    }
    val skillFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { treeUri ->
            if (treeUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.installSkillFromDirectory(treeUri)
            }
        },
    )
    val skillZipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { zipUri ->
            val completion = pendingSkillZipCompletion
            pendingSkillZipCompletion = null
            if (zipUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        zipUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.installSkillFromZip(zipUri) { success ->
                    completion?.invoke(success)
                }
            } else {
                completion?.invoke(false)
            }
        },
    )
    val saveAttachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
        onResult = { destinationUri ->
            val saveTarget = pendingSaveTarget
            pendingSaveTarget = null

            if (saveTarget == null || destinationUri == null) {
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                val didSave = withContext(Dispatchers.IO) {
                    when (saveTarget) {
                        is PendingSaveTarget.Attachment -> saveAttachmentToDocument(
                            context = context,
                            attachment = saveTarget.attachment,
                            destinationUri = destinationUri,
                        )

                        is PendingSaveTarget.WorkspaceFile -> workspaceFileBridge.saveWorkspaceFileToDocument(
                            path = workspaceFileBridge.resolveLinkPath(saveTarget.rawLink),
                            destinationUri = destinationUri,
                        )
                    }
                }
                Toast.makeText(
                    context,
                    if (didSave) strings.fileSaved else strings.couldNotSaveFile,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )
    val sessionExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { destinationUri ->
            val sessionId = pendingSessionExportId
            pendingSessionExportId = null
            if (sessionId != null && destinationUri != null) {
                viewModel.exportSessionToUri(sessionId, destinationUri)
            }
        },
    )
    val appDataExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { destinationUri ->
            if (destinationUri != null) {
                viewModel.exportAllDataToUri(destinationUri)
            }
        },
    )
    val logExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { destinationUri ->
            if (destinationUri != null) {
                viewModel.exportLogsToUri(destinationUri)
            }
        },
    )
    val appDataImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { sourceUri ->
            if (sourceUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        sourceUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.importAllDataFromUri(sourceUri)
            }
        },
    )
    val termuxPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            val source = pendingTermuxPermissionSource
            pendingTermuxPermissionSource = "unknown"
            viewModel.trackPermissionResult(
                permission = "termux_run_command",
                granted = granted,
                source = source,
            )
            viewModel.refreshTermuxSetup()
            Toast.makeText(
                context,
                if (granted) strings.termuxAccessGranted else strings.termuxAccessNotGranted,
                Toast.LENGTH_SHORT,
            ).show()
        },
    )
    fun requestTermuxPermission(source: String) {
        viewModel.trackTermuxSetupStarted(source)
        viewModel.trackPermissionRequested(
            permission = "termux_run_command",
            source = source,
        )
        pendingTermuxPermissionSource = source
        termuxPermissionLauncher.launch(TermuxContract.RunCommandPermission)
    }

    fun startTermuxSetupAction(
        source: String,
        action: () -> Unit,
    ) {
        viewModel.trackTermuxSetupStarted(source)
        action()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshTermuxSetup()
                viewModel.refreshAgentModeAuthorization()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (drawerState.isOpen) {
        BackHandler {
            scope.launch { drawerState.close() }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = uiState.currentScreen != AppScreen.Onboarding,
        scrimColor = AetherScrim,
        drawerContent = {
            ConversationDrawer(
                sessions = uiState.sessions,
                selectedSessionId = uiState.currentSessionId,
                onNewChat = {
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onSessionSelected = { sessionId ->
                    viewModel.selectSession(sessionId)
                    scope.launch { drawerState.close() }
                },
                onRenameSession = viewModel::renameSession,
                onToggleSessionPinned = viewModel::toggleSessionPinned,
                onPinSessions = viewModel::pinSessions,
                onUnpinSessions = viewModel::unpinSessions,
                onArchiveSession = viewModel::archiveSession,
                onArchiveSessions = viewModel::archiveSessions,
                onExportSession = { session ->
                    pendingSessionExportId = session.id
                    sessionExportLauncher.launch("${session.title.ifBlank { "aether-session" }}.json")
                },
                onDeleteSession = viewModel::deleteSession,
                onDeleteSessions = viewModel::deleteSessions,
                onSettingsSelected = {
                    scope.launch {
                        drawerState.close()
                        viewModel.openSettings()
                    }
                },
                onMarketMonitorSelected = {
                    scope.launch {
                        drawerState.close()
                        viewModel.openMarketMonitor()
                    }
                },
            )
        },
    ) {
        if (!uiState.isStartupRouteResolved) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AetherBackground)
            )
        } else {
            AnimatedContent(
                targetState = uiState.currentScreen,
                transitionSpec = {
                    val isForward = targetState.depth() > initialState.depth()
                    val enterSlide = slideInHorizontally(
                        animationSpec = tween(ScreenTransitionDuration, easing = ScreenTransitionEasing),
                        initialOffsetX = { if (isForward) it / 3 else -it / 3 },
                    ) + fadeIn(tween(ScreenTransitionDuration, easing = ScreenTransitionEasing))
                    val exitSlide = slideOutHorizontally(
                        animationSpec = tween(ScreenTransitionDuration, easing = ScreenTransitionEasing),
                        targetOffsetX = { if (isForward) -it / 3 else it / 3 },
                    ) + fadeOut(tween(ScreenTransitionDuration, easing = ScreenTransitionEasing))
                    enterSlide togetherWith exitSlide
                },
                label = "app_screen_transition",
            ) { currentScreen ->
                when (currentScreen) {
                    AppScreen.Onboarding -> OnboardingScreen(
                        initialStep = uiState.onboardingStep,
                        replayMode = uiState.isOnboardingReplay,
                        existingProviderConfig = activeProviderConfig,
                        isFetchingModels = uiState.isFetchingModels,
                        termuxSetupState = uiState.termuxSetupState,
                        rootSetupState = uiState.rootSetupState,
                        agentModeAuthorizationMethod = uiState.settings.agentModeAuthorizationMethod,
                        tavilyApiKey = uiState.settings.tavilyApiKey,
                        installedSkillCount = uiState.installedSkills.size,
                        mcpServerCount = uiState.mcpServers.size,
                        onFetchModels = viewModel::fetchModels,
                        onSkip = viewModel::skipOnboarding,
                        onClose = viewModel::closeOnboarding,
                        onCompleteProviderSetup = viewModel::completeOnboardingProviderSetup,
                        onSaveTavilyApiKey = viewModel::saveOnboardingTavilyApiKey,
                        onRequestTermuxPermission = { requestTermuxPermission("onboarding_termux_permission") },
                        onOpenAppPermissions = {
                            startTermuxSetupAction("onboarding_app_permissions") { openAppPermissionSettings(context) }
                        },
                        onOpenTermuxSettings = {
                            startTermuxSetupAction("onboarding_termux_settings") { openTermuxSettings(context) }
                        },
                        onOpenTermux = {
                            startTermuxSetupAction("onboarding_open_termux") { openTermux(context) }
                        },
                        onInstallTermux = {
                            startTermuxSetupAction("onboarding_install_termux") { openTermuxInstallPage(context) }
                        },
                        onRefreshTermuxSetup = viewModel::refreshTermuxSetup,
                        onRefreshRootSetup = viewModel::refreshRootSetup,
                        onConfigureWithRoot = viewModel::configureLocalAccessWithRoot,
                        onSaveAgentModeAuthorization = { enabled, method ->
                            viewModel.saveOnboardingAgentModeAuthorization(enabled, method)
                            if (enabled && method == AgentModeAuthorizationMethod.Shizuku) {
                                viewModel.requestShizukuPermission()
                            }
                        },
                        onCompleteFollowUp = viewModel::completeFollowUpOnboarding,
                        onExploreSettings = viewModel::exploreSettingsFromOnboardingTour,
                    )

                    AppScreen.Inbox -> TaskInboxScreen(
                        sessions = uiState.sessions,
                        selectedSessionId = uiState.currentSessionId,
                        sessionExecutionStates = executionStates,
                        unviewedCompletedSessionIds = uiState.unviewedCompletedSessionIds,
                        onBack = viewModel::closeInbox,
                        onTaskSelected = viewModel::selectSession,
                        onPinTasks = viewModel::pinSessions,
                        onUnpinTasks = viewModel::unpinSessions,
                        onArchiveTasks = viewModel::archiveSessions,
                        onRestoreTasks = viewModel::unarchiveSessions,
                        onDeleteTasks = viewModel::deleteSessions,
                        onRenameTask = viewModel::renameSession,
                        onExportTask = { session ->
                            pendingSessionExportId = session.id
                            sessionExportLauncher.launch("${session.title.ifBlank { "aether-session" }}.json")
                        },
                        onNewChat = viewModel::startNewChat,
                        onOpenSettings = viewModel::openSettings,
                    )

                    AppScreen.Chat -> ConversationScreen(
                        state = ConversationScreenState(
                            conversationStateKey = uiState.currentSessionId,
                            currentSession = activeSession,
                            currentTaskSnapshot = currentTaskSnapshot,
                            currentSessionTitle = activeSession?.title ?: strings.newChat,
                            currentSessionPinned = activeSession?.isPinned == true,
                            currentSessionArchived = activeSession?.isArchived == true,
                            messages = currentMessages,
                            workspaceDirectory = currentWorkspaceDirectory,
                            pendingToolInvocations = pendingToolInvocations,
                            pendingToolInvocationStateKey = "pending-tools-${uiState.currentSessionId}",
                            pendingResponseBlocks = pendingResponseBlocks,
                            pendingAssistantText = pendingAssistantText,
                            pendingStatusText = currentSessionExecution?.pendingStatusText.orEmpty(),
                            pendingStatusDetail = currentSessionExecution?.pendingStatusDetail.orEmpty(),
                            pendingInputs = pendingInputs,
                            loopState = currentSessionExecution?.loopState ?: AgentLoopState(),
                            taskState = activeSession?.taskState ?: AgentTaskState(),
                            inputValue = uiState.draftInput,
                            draftAttachments = uiState.draftAttachments,
                            draftAttachmentRevision = uiState.draftAttachmentRevision,
                            modelOptions = conversationModelOptions,
                            selectedModelKey = selectedConversationModelKey,
                            availableSkills = uiState.installedSkills.filter { it.isEnabled },
                            availableMcpServers = uiState.mcpServers.filter { it.isEnabled },
                            selectedSkillIds = selectedSkillIds,
                            selectedMcpServerIds = selectedMcpServerIds,
                            agentModeAvailable = uiState.settings.agentModeAuthorizationEnabled,
                            agentModeSelected = agentModeSelected,
                            enabledToolGroups = enabledToolGroups,
                            planModeSelected = planModeSelected,
                            goalModeSelected = goalModeSelected,
                            agentModeDisplayState = uiState.agentModeDisplayState,
                            allowRootImageRead = uiState.rootSetupState.isReady ||
                                (
                                    uiState.settings.agentModeAuthorizationEnabled &&
                                        uiState.settings.agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root &&
                                        uiState.agentModeAuthorizationState.isReady
                                    ),
                            isEditing = uiState.editingMessageId != null,
                            termuxSetupState = uiState.termuxSetupState,
                            showResumeSetupBanner = shouldShowResumeSetupBanner(
                                settings = uiState.settings,
                                messageCount = currentMessages.size,
                                draftInput = uiState.draftInput,
                                hasDraftAttachments = uiState.draftAttachments.isNotEmpty(),
                            ),
                            showStarterPromptHint = uiState.showStarterPromptHint,
                            showTermuxSetupNotice = !uiState.awaitingFollowUpTour && !uiState.showFollowUpTourCard,
                            isSending = isCurrentSessionRunning,
                            voiceInputState = voiceInputState,
                        ),
                        actions = ConversationScreenActions(
                            onInputChanged = viewModel::updateDraftInput,
                            onRenameThread = { title ->
                                activeSession?.let { viewModel.renameSession(it.id, title) }
                            },
                            onTogglePinned = {
                                activeSession?.let { viewModel.toggleSessionPinned(it.id) }
                            },
                            onArchiveThread = {
                                activeSession?.let { viewModel.archiveSession(it.id) }
                            },
                            onRestoreThread = {
                                activeSession?.let { viewModel.unarchiveSession(it.id) }
                            },
                            onExportThread = {
                                activeSession?.let { session ->
                                    pendingSessionExportId = session.id
                                    sessionExportLauncher.launch("${session.title.ifBlank { "aether-session" }}.json")
                                }
                            },
                            onDeleteThread = {
                                activeSession?.let { viewModel.deleteSession(it.id) }
                            },
                            onModelSelected = viewModel::setCurrentChatModelSelection,
                            onRemoveDraftAttachment = viewModel::removeDraftAttachment,
                            onSetSkillSelected = viewModel::setComposerSkillSelected,
                            onSetMcpServerSelected = viewModel::setComposerMcpServerSelected,
                            onSetAgentModeSelected = viewModel::setComposerAgentModeSelected,
                            onSetToolGroupEnabled = viewModel::setComposerToolGroupEnabled,
                            onSetPlanModeEnabled = viewModel::setComposerPlanModeEnabled,
                            onSetGoalModeEnabled = viewModel::setComposerGoalModeEnabled,
                            onSlashCommand = viewModel::handleComposerSlashCommand,
                            onCancelEdit = viewModel::cancelMessageEdit,
                            onSend = viewModel::sendCurrentMessage,
                            onVoiceInputPressed = ::startVoiceInputHold,
                            onVoiceInputReleased = ::releaseVoiceInputHold,
                            onCancelVoiceInput = ::cancelVoiceInput,
                            onQueueFollowUp = viewModel::queueCurrentMessage,
                            onSteerFollowUp = viewModel::steerCurrentMessage,
                            onMenu = { scope.launch { drawerState.open() } },
                            onOpenInbox = viewModel::openInbox,
                            onNewChat = viewModel::startNewChat,
                            onPickImages = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                            onSaveAttachment = { attachment ->
                                pendingSaveTarget = PendingSaveTarget.Attachment(attachment)
                                saveAttachmentLauncher.launch(attachment.name)
                            },
                            onOpenLink = { rawLink ->
                                scope.launch {
                                    handleAssistantLink(
                                        context = context,
                                        workspaceFileBridge = workspaceFileBridge,
                                        rawLink = rawLink,
                                        onSaveWorkspaceFile = {
                                            pendingSaveTarget = PendingSaveTarget.WorkspaceFile(rawLink)
                                            saveAttachmentLauncher.launch(
                                                workspaceFileBridge.resolveWorkspaceDownloadName(rawLink)
                                            )
                                        },
                                    )
                                }
                            },
                            onEditMessage = { messageId ->
                                activeSession?.let { viewModel.startEditingUserMessage(it.id, messageId) }
                            },
                            onDeleteMessage = { messageId ->
                                activeSession?.let { viewModel.deleteMessage(it.id, messageId) }
                            },
                            onRedoAgentMessage = { messageId ->
                                activeSession?.let { viewModel.redoAgentMessage(it.id, messageId) }
                            },
                            onRetryUserMessage = { messageId ->
                                activeSession?.let { viewModel.retryUserMessage(it.id, messageId) }
                            },
                            onSwitchUserMessageBranch = { messageId, delta ->
                                activeSession?.let { viewModel.switchUserMessageBranch(it.id, messageId, delta) }
                            },
                            onCopyMessage = { message ->
                                clipboardManager.setText(AnnotatedString(message.text))
                                Toast.makeText(context, strings.replyCopied, Toast.LENGTH_SHORT).show()
                            },
                            onRequestTermuxPermission = { requestTermuxPermission("chat_termux_permission") },
                            onOpenAppPermissions = {
                                startTermuxSetupAction("chat_app_permissions") { openAppPermissionSettings(context) }
                            },
                            onOpenTermuxSettings = {
                                startTermuxSetupAction("chat_termux_settings") { openTermuxSettings(context) }
                            },
                            onOpenTermux = {
                                startTermuxSetupAction("chat_open_termux") { openTermux(context) }
                            },
                            onInstallTermux = {
                                startTermuxSetupAction("chat_install_termux") { openTermuxInstallPage(context) }
                            },
                            onRefreshTermuxSetup = viewModel::refreshTermuxSetup,
                            onPauseGeneration = viewModel::pauseGeneration,
                            onResumeOnboarding = viewModel::resumeOnboarding,
                            onDismissStarterPromptHint = viewModel::dismissStarterPromptHint,
                        ),
                    )

                    AppScreen.MarketMonitor -> MarketMonitorScreen(
                        onBack = viewModel::closeMarketMonitor,
                    )

                    AppScreen.Settings -> SettingsScreen(
                    provider = uiState.settings.provider,
                    apiKey = uiState.settings.apiKey,
                    baseUrl = uiState.settings.baseUrl,
                    modelId = uiState.settings.modelId,
                    systemPrompt = uiState.settings.systemPrompt,
                    tavilyApiKey = uiState.settings.tavilyApiKey,
                    llmInactivityReconnectTimeoutSeconds = uiState.settings.llmInactivityReconnectTimeoutSeconds,
                    keepTasksRunningInBackground = uiState.settings.keepTasksRunningInBackground,
                    notifyOnTaskCompletion = uiState.settings.notifyOnTaskCompletion,
                    agentLoopPolicy = uiState.settings.agentLoopPolicy,
                    agentModeAuthorizationEnabled = uiState.settings.agentModeAuthorizationEnabled,
                    agentModeAuthorizationMethod = uiState.settings.agentModeAuthorizationMethod,
                    agentModeAuthorizationState = uiState.agentModeAuthorizationState,
                    rootSetupState = uiState.rootSetupState,
                    rootSetupProgressReturnPage = uiState.rootSetupProgressReturnPage,
                    language = uiState.settings.language,
                    themeMode = uiState.settings.themeMode,
                    defaultChatModelKey = uiState.settings.defaultChatModelKey,
                    defaultTitleModelKey = uiState.settings.defaultTitleModelKey,
                    defaultNamingModelKey = uiState.settings.defaultNamingModelKey,
                    agentModeDisplayState = uiState.agentModeDisplayState,
                    providerConfigs = uiState.providerConfigs,
                    termuxSetupState = uiState.termuxSetupState,
                    installedSkills = uiState.installedSkills,
                    mcpServers = uiState.mcpServers,
                    isFetchingModels = uiState.isFetchingModels,
                    appUpdate = uiState.appUpdate,
                    onSave = viewModel::saveSettings,
                    onUpdateLanguage = viewModel::updateAppLanguage,
                    onUpdateThemeMode = viewModel::updateAppThemeMode,
                    onUpsertProviderConfig = viewModel::upsertProviderConfig,
                    onRemoveProviderConfig = viewModel::removeProviderConfig,
                    onSetProviderEnabled = viewModel::setProviderEnabled,
                    onFetchModels = viewModel::fetchModels,
                    onImportSkillFolder = { skillFolderPicker.launch(null) },
                    onImportSkillZip = { onComplete ->
                        pendingSkillZipCompletion = onComplete
                        skillZipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onInstallSkillUrl = { url, onComplete ->
                        viewModel.installSkillFromRemote(url, onComplete)
                    },
                    onToggleSkillEnabled = viewModel::setSkillEnabled,
                    onRemoveSkill = viewModel::removeSkill,
                    onSaveHttpMcpServer = viewModel::saveStreamableHttpMcpServer,
                    onSaveStdIoMcpServer = viewModel::saveStdIoMcpServer,
                    onValidateHttpMcpServer = viewModel::validateStreamableHttpMcpServer,
                    onValidateStdIoMcpServer = viewModel::validateStdIoMcpServer,
                    onToggleMcpServerEnabled = viewModel::setMcpServerEnabled,
                    onRemoveMcpServer = viewModel::removeMcpServer,
                    onRequestTermuxPermission = { requestTermuxPermission("settings_termux_permission") },
                    onImportAppData = {
                        appDataImportLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    onExportAppData = {
                        appDataExportLauncher.launch("aether-data.json")
                    },
                    onExportLogs = {
                        logExportLauncher.launch("aether-logs.txt")
                    },
                    onOpenAppPermissions = {
                        startTermuxSetupAction("settings_app_permissions") { openAppPermissionSettings(context) }
                    },
                    onOpenTermuxSettings = {
                        startTermuxSetupAction("settings_termux_settings") { openTermuxSettings(context) }
                    },
                    onOpenTermux = {
                        startTermuxSetupAction("settings_open_termux") { openTermux(context) }
                    },
                    onInstallTermux = {
                        startTermuxSetupAction("settings_install_termux") { openTermuxInstallPage(context) }
                    },
                    onRefreshTermuxSetup = viewModel::refreshTermuxSetup,
                    onRefreshRootSetup = viewModel::refreshRootSetup,
                    onStartRootSetupFromSettings = viewModel::startRootSetupFromSettings,
                    onDismissRootSetupProgress = viewModel::dismissRootSetupProgress,
                    onRequestShizukuPermission = viewModel::requestShizukuPermission,
                    onRefreshAgentModeAuthorization = viewModel::refreshAgentModeAuthorization,
                    onOpenShizuku = { openShizuku(context) },
                    onInstallShizuku = { openShizukuInstallPage(context) },
                    onReplayOnboarding = viewModel::openOnboardingFromSettings,
                    onReplayFollowUpOnboarding = viewModel::openFollowUpOnboardingFromSettings,
                    onStopAgentModeDisplay = viewModel::stopAgentModeDisplay,
                    onRefreshAgentModeDisplays = viewModel::refreshAgentModeDisplays,
                    onOpenWebsite = { openExternalUrl(context, AetherWebsiteUrl) },
                    onOpenPrivacyPolicy = { openExternalUrl(context, AetherPrivacyPolicyUrl) },
                    onCheckForUpdates = viewModel::checkForUpdates,
                    onForceUpdateCheckForTesting = viewModel::forceUpdateCheckForTesting,
                    onDownloadAndInstallUpdate = viewModel::downloadAndInstallUpdate,
                    onBack = viewModel::closeSettings,
                )
        }
            }
        }

        if (uiState.isStartupRouteResolved && !uiState.settings.privacyPolicyAccepted) {
            PrivacyPolicyConsentDialog(
                strings = strings,
                onOpenPolicy = { openPrivacyPolicy(context) },
                onAccept = viewModel::acceptPrivacyPolicy,
                onDecline = { (context as? Activity)?.finishAffinity() },
            )
        }
        val availableUpdate = uiState.appUpdate.availableRelease
        if (
            uiState.appUpdate.showAvailableDialog &&
            availableUpdate != null &&
            uiState.settings.privacyPolicyAccepted
        ) {
            AppUpdateAvailableDialog(
                strings = strings,
                updateState = uiState.appUpdate,
                onDismiss = viewModel::dismissUpdateAvailableDialog,
                onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
            )
        }
    }
}

@Composable
private fun PrivacyPolicyConsentDialog(
    strings: AetherStrings,
    onOpenPolicy: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = AetherSurface,
        titleContentColor = AetherOnSurface,
        textContentColor = AetherOnSurfaceVariant,
        title = {
            Text(
                text = tr(strings, "Privacy Policy", "\u9690\u79c1\u653f\u7b56"),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            val policyText = tr(strings, "Privacy Policy", "\u9690\u79c1\u653f\u7b56")
            val annotatedText = buildAnnotatedString {
                append(tr(strings, "Please review and agree to Aether's ", "\u4f7f\u7528 Aether \u524d\uff0c\u8bf7\u5148\u9605\u8bfb\u5e76\u540c\u610f\u6211\u4eec\u7684"))
                pushStringAnnotation(
                    tag = PrivacyPolicyAnnotationTag,
                    annotation = AetherPrivacyPolicyUrl,
                )
                withStyle(SpanStyle(color = Color(0xFF3B82F6))) {
                    append(policyText)
                }
                pop()
                append(tr(strings, " before using the app.", "\u3002"))
            }
            ClickableText(
                text = annotatedText,
                style = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurfaceVariant),
                onClick = { offset ->
                    annotatedText
                        .getStringAnnotations(PrivacyPolicyAnnotationTag, offset, offset)
                        .firstOrNull()
                        ?.let { onOpenPolicy() }
                },
            )
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AetherPrimary,
                    contentColor = Color.White,
                ),
            ) {
                Text(text = tr(strings, "Agree", "\u540c\u610f"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(
                    text = tr(strings, "Decline", "\u4e0d\u540c\u610f"),
                    color = AetherOnSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun AppUpdateAvailableDialog(
    strings: AetherStrings,
    updateState: AppUpdateUiState,
    onDismiss: () -> Unit,
    onDownloadAndInstall: () -> Unit,
) {
    val release = updateState.availableRelease ?: return
    val progress = updateState.downloadProgress
    AlertDialog(
        onDismissRequest = {
            if (!updateState.isDownloading) onDismiss()
        },
        containerColor = AetherSurface,
        titleContentColor = AetherOnSurface,
        textContentColor = AetherOnSurfaceVariant,
        title = {
            Text(
                text = tr(strings, "Update available", "\u53d1\u73b0\u65b0\u7248\u672c"),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    text = tr(
                        strings,
                        "Aether ${release.versionName} is available. You can download the APK and install it now.",
                        "Aether ${release.versionName} \u5df2\u53ef\u7528\uff0c\u53ef\u4ee5\u7acb\u5373\u4e0b\u8f7d APK \u5e76\u5b89\u88c5\u3002",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
                if (updateState.isDownloading) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AetherPrimary,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = if (progress != null) {
                                "${(progress * 100).toInt()}%"
                            } else {
                                tr(strings, "Downloading...", "\u6b63\u5728\u4e0b\u8f7d...")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownloadAndInstall,
                enabled = !updateState.isDownloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AetherPrimary,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = tr(strings, "Download and install", "\u4e0b\u8f7d\u5e76\u5b89\u88c5"),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !updateState.isDownloading,
            ) {
                Text(
                    text = tr(strings, "Later", "\u7a0d\u540e"),
                    color = AetherOnSurfaceVariant,
                )
            }
        },
    )
}

private fun saveAttachmentToDocument(
    context: android.content.Context,
    attachment: ChatAttachment,
    destinationUri: Uri,
): Boolean = runCatching {
    val resolver = context.contentResolver
    val sourceUri = attachment.uri.toUri()
    val expectedSize = resolver.openAssetFileDescriptor(sourceUri, "r")?.use { descriptor ->
        descriptor.length.takeIf { it >= 0L }
    }
    var copiedBytes = 0L
    resolver.openInputStream(sourceUri)?.use { input ->
        resolver.openOutputStream(destinationUri, "w")?.use { output ->
            copiedBytes = input.copyTo(output)
            output.flush()
        }
    } != null && (expectedSize == null || expectedSize == copiedBytes)
}.getOrDefault(false)

private suspend fun handleAssistantLink(
    context: android.content.Context,
    workspaceFileBridge: WorkspaceFileBridge,
    rawLink: String,
    onSaveWorkspaceFile: () -> Unit,
) {
    parseAssistantLocalFileLink(rawLink)?.let { localPath ->
        openAssistantLocalFile(
            context = context,
            workspaceFileBridge = workspaceFileBridge,
            absolutePath = localPath,
        )
        return
    }

    val normalizedLink = normalizeAssistantLink(rawLink)
    if (looksLikeWorkspaceFileLink(normalizedLink)) {
        onSaveWorkspaceFile()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW, normalizedLink.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent) {
        Toast.makeText(context, "Unable to open that link", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun openAssistantLocalFile(
    context: android.content.Context,
    workspaceFileBridge: WorkspaceFileBridge,
    absolutePath: String,
) {
    val resolvedFile = runCatching {
        withContext(Dispatchers.IO) {
            val directFile = File(absolutePath)
            if (directFile.exists() && directFile.isFile && directFile.canRead()) {
                return@withContext directFile
            }

            val payload = workspaceFileBridge.readWorkspaceFile(
                path = absolutePath,
                byteLimit = AssistantLocalFileOpenByteLimit,
            ).getOrThrow()
            val exportDirectory = File(context.cacheDir, AssistantLocalFileCacheDirectory).apply {
                mkdirs()
            }
            val fileName = payload.absolutePath.substringAfterLast('/').ifBlank { "file" }
            File(exportDirectory, fileName).apply {
                outputStream().use { output ->
                    output.write(payload.bytes)
                    output.flush()
                }
            }
        }
    }.getOrElse {
        Toast.makeText(context, "Unable to open that file", Toast.LENGTH_SHORT).show()
        return
    }
    val contentUri = runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            resolvedFile,
        )
    }.getOrElse {
        Toast.makeText(context, "Unable to open that file", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, "*/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    launchIntentSafely(context, intent) {
        Toast.makeText(context, "Unable to open that file", Toast.LENGTH_SHORT).show()
    }
}

private fun requestApkInstall(
    context: android.content.Context,
    apkUri: Uri,
) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    launchIntentSafely(context, intent) {
        Toast.makeText(context, "Unable to open the APK installer", Toast.LENGTH_SHORT).show()
    }
}

private fun normalizeAssistantLink(rawLink: String): String {
    val trimmed = rawLink.trim().removeSurrounding("<", ">")
    if (trimmed.isBlank()) return trimmed
    if (looksLikeWorkspaceFileLink(trimmed)) return trimmed
    if (trimmed.contains("://")) return trimmed
    if (trimmed.startsWith("www.", ignoreCase = true)) {
        return "https://$trimmed"
    }
    return if (Patterns.WEB_URL.matcher(trimmed).matches() && !trimmed.startsWith("/")) {
        "https://$trimmed"
    } else {
        trimmed
    }
}

private fun looksLikeWorkspaceFileLink(rawLink: String): Boolean {
    val trimmed = rawLink.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("file://", ignoreCase = true)) return true
    if (trimmed.startsWith("~/")) return true
    if (trimmed.startsWith("/")) return true
    return false
}

private fun buildConversationModelOptions(
    settings: AppSettings,
    providerConfigs: List<LlmProviderConfig>,
): List<ProviderModelOption> {
    val configuredOptions = providerConfigs.availableModelOptions()
    if (configuredOptions.isNotEmpty()) {
        return configuredOptions
    }
    return listOf(
        ProviderModelOption(
            key = buildModelOptionKey("legacy", settings.modelId),
            providerConfigId = "legacy",
            providerId = settings.provider.storageValue,
            providerName = settings.provider.displayName,
            providerType = settings.provider,
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl,
            modelId = settings.modelId,
            userAgent = settings.userAgent,
            basicFunctionCallingCompatibilityMode = settings.basicFunctionCallingCompatibilityMode,
            fullLabel = "${settings.provider.storageValue}/${settings.modelId}",
            chatLabel = settings.modelId,
        )
    ).filter { it.modelId.isNotBlank() && it.baseUrl.isNotBlank() }
}

private fun resolveConversationModelKey(
    session: ChatSession?,
    draftSelectedModelKey: String,
    defaultChatModelKey: String,
    options: List<ProviderModelOption>,
): String {
    val preferredKey = session?.selectedModelKey
        ?.takeIf { key -> options.any { it.key == key } }
        ?: draftSelectedModelKey.takeIf { key -> options.any { it.key == key } }
        ?: defaultChatModelKey.takeIf { key -> options.any { it.key == key } }
    return preferredKey ?: options.resolveAutomaticModelKey(AutomaticModelPurpose.Chat)
}

private fun openAppPermissionSettings(
    context: android.content.Context,
) {
    val managePermissionsIntent = Intent("android.intent.action.MANAGE_APP_PERMISSIONS").apply {
        putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallbackIntent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri(),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, managePermissionsIntent) {
        launchIntentSafely(context, fallbackIntent)
    }
}

private fun openTermuxSettings(
    context: android.content.Context,
) {
    val intent = Intent().apply {
        setClassName(TermuxContract.PackageName, TermuxContract.TermuxSettingsActivity)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent) {
        openTermux(context)
    }
}

private fun openTermux(
    context: android.content.Context,
) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(TermuxContract.PackageName)
        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (launchIntent != null) {
        launchIntentSafely(context, launchIntent)
    } else {
        openTermuxInstallPage(context)
    }
}

private fun openTermuxInstallPage(
    context: android.content.Context,
) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        "https://f-droid.org/en/packages/com.termux/".toUri(),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent)
}

private fun openShizuku(
    context: android.content.Context,
) {
    val launchIntent = listOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager",
    ).firstNotNullOfOrNull { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
    if (launchIntent != null) {
        launchIntentSafely(context, launchIntent)
    } else {
        openShizukuInstallPage(context)
    }
}

private fun openShizukuInstallPage(
    context: android.content.Context,
) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        "https://shizuku.rikka.app/download/".toUri(),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent)
}

private fun openPrivacyPolicy(
    context: android.content.Context,
) {
    openExternalUrl(context, AetherPrivacyPolicyUrl)
}

private fun openExternalUrl(
    context: android.content.Context,
    url: String,
) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent) {
        Toast.makeText(context, "Unable to open that link", Toast.LENGTH_SHORT).show()
    }
}

private fun launchIntentSafely(
    context: android.content.Context,
    intent: Intent,
    fallback: (() -> Unit)? = null,
) {
    val launchResult = runCatching {
        context.startActivity(intent)
    }
    if (launchResult.isSuccess) {
        return
    }

    val failure = launchResult.exceptionOrNull()
    if (failure is ActivityNotFoundException || failure is SecurityException) {
        if (fallback != null) {
            fallback()
        } else {
            Toast.makeText(context, "Unable to open that screen", Toast.LENGTH_SHORT).show()
        }
    } else if (fallback != null) {
        fallback()
    } else {
        Toast.makeText(context, "Unable to open that screen", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ChatScreen(
    messages: List<ChatMessage>,
    inputValue: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    isSending: Boolean,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isSending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherBackground,
        topBar = { ChatTopBar(onMenu, onNewChat) },
        bottomBar = { ComposerBar(inputValue, onInputChanged, onSend, messages.isNotEmpty(), isSending) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(AetherBackground, Color(0xFF0B0B0D), Color(0xFF070708))
                    )
                )
                .padding(innerPadding)
        ) {
            if (messages.isEmpty()) {
                EmptyChatState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                    if (isSending) {
                        item {
                            TypingIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SurfaceIconButton(Icons.Rounded.Menu, strings.menu, onMenu)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SurfaceIconButton(Icons.Rounded.Create, strings.newChat, onNewChat)
            SurfaceIconButton(Icons.Rounded.MoreHoriz, strings.more, {})
        }
    }
}

@Composable
private fun EmptyChatState() {
    val strings = rememberAetherStrings()
    val actions = listOf(
        SuggestionAction(Icons.Rounded.Image, strings.createImage, Color(0xFF22C55E)),
        SuggestionAction(Icons.Rounded.Lightbulb, strings.brainstorm, Color(0xFFFACC15)),
        SuggestionAction(Icons.Rounded.Visibility, strings.analyzeImages, AetherPrimary),
        SuggestionAction(Icons.Rounded.AutoAwesome, strings.more, AetherPrimary),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = strings.whatCanIHelpWith,
            style = MaterialTheme.typography.headlineMedium,
            color = AetherOnSurface,
        )
        Spacer(modifier = Modifier.height(28.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.take(2).forEach { action ->
                    SuggestionChip(Modifier.weight(1f), action)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.drop(2).forEach { action ->
                    SuggestionChip(Modifier.weight(1f), action)
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    modifier: Modifier = Modifier,
    action: SuggestionAction,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF0C0D10))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = action.tint,
        )
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.author == MessageAuthor.User) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = message.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF5B36D7), Color(0xFF6E48FF)))
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Aether",
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurfaceVariant,
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = AetherOnSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TypingIndicator() {
    val strings = rememberAetherStrings()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = AetherPrimary,
            strokeWidth = 2.dp,
        )
        Text(
            text = strings.aetherIsThinking,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    hasMessages: Boolean,
    isSending: Boolean,
) {
    val strings = rememberAetherStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(AetherSurfaceHigher)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleAction(Icons.Rounded.Add, {})
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = if (hasMessages) strings.replyToAether else strings.askAether,
                    color = AetherOnSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !isSending,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AetherOnSurface),
                cursorBrush = SolidColor(AetherOnSurface),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = {}, enabled = !isSending) {
            Icon(
                imageVector = Icons.Rounded.KeyboardVoice,
                contentDescription = strings.voice,
                tint = AetherOnSurfaceVariant,
            )
        }
        CircleAction(
            icon = if (value.isBlank()) Icons.Rounded.AutoAwesome else Icons.Rounded.ArrowUpward,
            onClick = onSend,
            enabled = !isSending,
            containerColor = AetherPrimary,
            contentColor = Color.White,
        )
    }
}

@Composable
private fun AppDrawer(
    sessions: List<ChatSession>,
    selectedSessionId: String,
    onNewChat: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onSettingsSelected: () -> Unit,
) {
    val strings = rememberAetherStrings()
    ModalDrawerSheet(
        modifier = Modifier.width(312.dp),
        drawerContainerColor = AetherSurface,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBarStub(modifier = Modifier.weight(1f))
                SurfaceIconButton(Icons.Rounded.Create, strings.newChat, onNewChat)
            }

            Spacer(modifier = Modifier.height(14.dp))
            DrawerPrimaryAction(Icons.Rounded.Create, strings.newChat, onNewChat)
            DrawerPrimaryAction(Icons.Rounded.Image, strings.images, {})
            DrawerPrimaryAction(Icons.Rounded.GridView, strings.apps, {})
            DrawerPrimaryAction(Icons.Rounded.AutoAwesome, strings.gpts, {})

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = strings.recent,
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (sessions.isEmpty()) {
                Text(
                    text = strings.noConversationsYet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            } else {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sessions.forEach { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            onClick = { onSessionSelected(session.id) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Spacer(modifier = Modifier.height(12.dp))
            DrawerPrimaryAction(Icons.Rounded.Settings, strings.settings, onSettingsSelected)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SearchBarStub(modifier: Modifier = Modifier) {
    val strings = rememberAetherStrings()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(AetherSurfaceHigher)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
        )
        Text(
            text = strings.search,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun DrawerPrimaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = AetherOnSurface)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val strings = rememberAetherStrings()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AetherSurfaceHigher else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = session.title,
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = session.preview.ifBlank { strings.emptyDraft },
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// SettingsScreen is now in SettingsScreen.kt

@Composable
private fun SurfaceIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(12.dp, CircleShape, ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(CircleShape)
            .background(AetherSurface.copy(alpha = 0.88f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AetherOnSurface,
        )
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    containerColor: Color = AetherSurfaceHigh,
    contentColor: Color = AetherOnSurface,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) contentColor else contentColor.copy(alpha = 0.45f),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AetherAppPreview() {
    AetherLocalization(language = com.zhousl.aether.data.AppLanguage.English) {
        AetherTheme {
            ChatScreen(
                messages = defaultPreviewMessages(),
                inputValue = "",
                onInputChanged = {},
                onSend = {},
                onMenu = {},
                onNewChat = {},
                isSending = false,
            )
        }
    }
}

private fun defaultPreviewMessages(): List<ChatMessage> = listOf(
    ChatMessage("preview-user", MessageAuthor.User, "How should I wire this app to an LLM API?"),
    ChatMessage(
        "preview-agent",
        MessageAuthor.Agent,
        "Start with persistent settings, then call a configurable OpenAI-compatible chat endpoint from the composer.",
    ),
)
