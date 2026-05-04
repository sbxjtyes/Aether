package com.zhousl.aether.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.Display
import android.util.Log
import androidx.core.content.getSystemService
import com.rosan.app_process.AppProcess
import com.zhousl.aether.agentmode.AetherAgentModeShizukuService
import com.zhousl.aether.agentmode.IAetherAgentModeService
import com.zhousl.aether.termux.TermuxBashTool
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

private const val FallbackAgentDisplayWidth = 720
private const val FallbackAgentDisplayHeight = 1280
private const val FallbackAgentDisplayDensityDpi = 320
private const val AgentDisplayName = "aether-agent-mode"
private const val ShizukuPermissionRequestCode = 4201
private const val RootAuthorizationProbeTimeoutMillis = 2_000L
private const val TAG = "AetherAgentMode"
private const val RootAgentModeServiceUid = "1000"

private val ShizukuManagerPackages = listOf(
    "moe.shizuku.privileged.api",
    "moe.shizuku.manager",
)

data class AgentModeDisplayState(
    val isActive: Boolean = false,
    val displayId: Int? = null,
    val width: Int = FallbackAgentDisplayWidth,
    val height: Int = FallbackAgentDisplayHeight,
    val displays: List<AgentModeDisplayInfo> = emptyList(),
    val latestPreviewPath: String = "",
    val latestWorkspacePath: String = "",
    val lastUpdatedMillis: Long = 0L,
    val status: String = "",
)

data class AgentModeDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isAetherDisplay: Boolean,
)

enum class AgentModeAuthorizationIssue {
    Disabled,
    Ready,
    ShizukuNotInstalled,
    ShizukuNotRunning,
    ShizukuPermissionMissing,
    ShizukuPermissionDenied,
    RootUnavailable,
    RootPermissionMissing,
    RootPermissionDenied,
    Error,
}

data class AgentModeAuthorizationState(
    val issue: AgentModeAuthorizationIssue = AgentModeAuthorizationIssue.Disabled,
    val detail: String = "",
) {
    val isReady: Boolean
        get() = issue == AgentModeAuthorizationIssue.Ready
}

class AgentModeController(
    private val context: Context,
    private val bashTool: TermuxBashTool,
    private val workspaceFileBridge: WorkspaceFileBridge,
) {
    private val displayManager = context.getSystemService<DisplayManager>()!!
    private val cacheDirectory = File(context.cacheDir, "agent-mode").apply { mkdirs() }
    private val _displayState = MutableStateFlow(AgentModeDisplayState())
    private val _authorizationState = MutableStateFlow(AgentModeAuthorizationState())
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuPermissionRequestCode) {
                AetherAnalytics.capture(
                    event = "permission result",
                    properties = mapOf(
                        "permission" to "shizuku",
                        "source" to "agent_mode_authorization",
                        "granted" to (grantResult == PackageManager.PERMISSION_GRANTED),
                        "result" to if (grantResult == PackageManager.PERMISSION_GRANTED) "granted" else "denied",
                    ),
                )
                _authorizationState.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.Ready,
                        detail = "Shizuku permission is granted.",
                    )
                } else {
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.ShizukuPermissionDenied,
                        detail = "Shizuku permission was denied. Grant Aether permission in Shizuku before using Agent Mode.",
                    )
                }
            }
        }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        if (_authorizationState.value.issue != AgentModeAuthorizationIssue.Disabled) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Shizuku stopped. Start Shizuku, then refresh Agent Mode status.",
            )
        }
        shizukuService = null
    }

    private var shizukuDisplayId: Int? = null
    private var shizukuService: IAetherAgentModeService? = null
    private var shizukuServiceArgs: Shizuku.UserServiceArgs? = null
    private var shizukuServiceConnection: ServiceConnection? = null
    private var rootService: IAetherAgentModeService? = null
    private var rootProcess: AppProcess.Terminal? = null

    val displayState: StateFlow<AgentModeDisplayState> = _displayState.asStateFlow()
    val authorizationState: StateFlow<AgentModeAuthorizationState> = _authorizationState.asStateFlow()

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        }
        runCatching {
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        }
    }

    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        argumentsJson: String,
    ): String = withContext(Dispatchers.IO) {
        if (!settings.agentModeAuthorizationEnabled) {
            captureAgentModeFailed(
                settings = settings,
                action = "unknown",
                reason = "authorization_disabled",
                message = "Agent Mode is not authorized.",
            )
            return@withContext JSONObject().apply {
                put("ok", false)
                put("errmsg", "Agent Mode is not authorized. Enable it in Settings > Agent Mode first.")
            }.toString()
        }

        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = "unknown",
                    reason = "invalid_arguments",
                    message = "Arguments were not valid JSON.",
                )
            }
        val action = arguments.optString("action").trim().lowercase()

        runCatching {
            when (action) {
            "start" -> {
                ensureDisplay(settings)
                captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
            }
            "status" -> statusResult(settings)
            "launch" -> {
                ensureDisplay(settings)
                val target = arguments.optString("target").trim()
                if (target.isBlank()) {
                    invalidArguments("Missing required 'target' argument.")
                } else {
                    launchTarget(settings, target)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 900)
                }
            }
            "tap" -> {
                val displayId = ensureDisplay(settings)
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("Both 'x' and 'y' are required, using 0..1000 screen coordinates.")
                } else {
                    requireAgentModeService(settings).tap(displayId, x, y)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
                }
            }
            "swipe" -> {
                val displayId = ensureDisplay(settings)
                val x1 = normalizedX(arguments.optDouble("x1", Double.NaN))
                val y1 = normalizedY(arguments.optDouble("y1", Double.NaN))
                val x2 = normalizedX(arguments.optDouble("x2", Double.NaN))
                val y2 = normalizedY(arguments.optDouble("y2", Double.NaN))
                val durationMs = arguments.optInt("duration_ms", arguments.optInt("durationMs", 500))
                    .coerceIn(50, 10_000)
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    invalidArguments("x1, y1, x2, and y2 are required, using 0..1000 screen coordinates.")
                } else {
                    requireAgentModeService(settings).swipe(displayId, x1, y1, x2, y2, durationMs)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = durationMs.toLong() + 250)
                }
            }
            "key" -> {
                val displayId = ensureDisplay(settings)
                val keyCode = arguments.optString("key").trim()
                if (keyCode.isBlank()) {
                    invalidArguments("Missing required 'key' argument.")
                } else {
                    requireAgentModeService(settings).key(displayId, keyCode)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 300)
                }
            }
            "text" -> {
                val displayId = ensureDisplay(settings)
                val text = arguments.optString("text")
                if (text.isBlank()) {
                    invalidArguments("Missing required 'text' argument.")
                } else {
                    requireAgentModeService(settings).text(displayId, text)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
                }
            }
            "screenshot" -> {
                ensureDisplay(settings)
                captureAfterDelay(settings, workspaceDirectory, delayMillis = 0)
            }
            "stop" -> {
                releaseDisplay()
                JSONObject().apply {
                    put("ok", true)
                    put("stdout", "Agent Mode virtual display stopped.")
                }.toString()
            }
            else -> invalidArguments("Unsupported action '$action'.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = action.ifBlank { "unknown" },
                    reason = "unsupported_action",
                    message = "Unsupported action '$action'.",
                )
            }
            }
        }.getOrElse { throwable ->
            captureAgentModeFailed(
                settings = settings,
                action = action.ifBlank { "unknown" },
                reason = "exception",
                message = throwable.message ?: throwable.javaClass.simpleName,
            )
            toolError(
                message = throwable.message ?: throwable.javaClass.simpleName,
                action = action,
            )
        }
    }

    suspend fun refreshAuthorization(settings: AppSettings) {
        _authorizationState.value = inspectAuthorization(settings)
    }

    fun requestShizukuPermission(): AgentModeAuthorizationState {
        val current = inspectShizukuAuthorization()
        if (current.issue == AgentModeAuthorizationIssue.Ready) {
            _authorizationState.value = current
            return current
        }
        if (
            current.issue != AgentModeAuthorizationIssue.ShizukuPermissionMissing &&
            current.issue != AgentModeAuthorizationIssue.ShizukuPermissionDenied
        ) {
            _authorizationState.value = current
            return current
        }

        return runCatching {
            AetherAnalytics.capture(
                event = "permission requested",
                properties = mapOf(
                    "permission" to "shizuku",
                    "source" to "agent_mode_authorization",
                    "current_issue" to current.issue.name.lowercase(),
                ),
            )
            Shizuku.requestPermission(ShizukuPermissionRequestCode)
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                detail = "Confirm the Shizuku permission prompt, then refresh Agent Mode status.",
            )
        }.getOrElse { throwable ->
            AetherAnalytics.capture(
                event = "permission result",
                properties = mapOf(
                    "permission" to "shizuku",
                    "source" to "agent_mode_authorization",
                    "granted" to false,
                    "result" to "request_failed",
                    "error" to (throwable.message ?: throwable.javaClass.simpleName),
                ),
            )
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = throwable.message ?: "Failed to request Shizuku permission.",
            )
        }.also { _authorizationState.value = it }
    }

    private suspend fun ensureDisplay(settings: AppSettings): Int {
        shizukuDisplayId?.let { return it }
        val displaySpec = currentDeviceDisplaySpec()
        val displayId = requireAgentModeService(settings).createOwnedDisplay(
            AgentDisplayName,
            displaySpec.width,
            displaySpec.height,
            displaySpec.densityDpi,
        )
        shizukuDisplayId = displayId
        _displayState.value = AgentModeDisplayState(
            isActive = true,
            displayId = displayId,
            width = displaySpec.width,
            height = displaySpec.height,
            displays = currentDisplays(settings, displayId),
            status = "${settings.agentModeAuthorizationMethod.displayName} virtual display ready",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        return displayId
    }

    fun stopDisplay() {
        releaseDisplay()
    }

    suspend fun refreshDisplays(settings: AppSettings) {
        val state = _displayState.value
        _displayState.value = state.copy(
            displays = currentDisplays(settings, state.displayId),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun launchTarget(
        settings: AppSettings,
        target: String,
    ) {
        val displayId = ensureDisplay(settings)
        val launchPackage = resolveLaunchPackage(target)
            ?: error("No launchable app matched '$target'. Try a package name such as com.android.chrome, or a shorter app label.")
        requireAgentModeService(settings).launchPackage(launchPackage, displayId)
    }

    private fun resolveLaunchPackage(target: String): String? {
        val normalizedTarget = target.trim().lowercase()
        if (normalizedTarget.isBlank()) return null
        val packageManager = context.packageManager
        packageManager.getLaunchIntentForPackage(target)?.let { return target }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { info ->
                val label = info.loadLabel(packageManager).toString()
                Triple(info.activityInfo.packageName, label, info.activityInfo.name)
            }
        val tokens = normalizedTarget.split(Regex("\\s+"))
            .filter { it.length > 2 && it !in setOf("app", "browser", "managed") }
        return launchables.firstOrNull { (packageName, label, _) ->
            packageName.equals(normalizedTarget, ignoreCase = true) ||
                label.equals(target, ignoreCase = true)
        }?.first ?: launchables.firstOrNull { (packageName, label, activityName) ->
            packageName.lowercase().contains(normalizedTarget) ||
                label.lowercase().contains(normalizedTarget) ||
                activityName.lowercase().contains(normalizedTarget) ||
                tokens.any { token ->
                    packageName.lowercase().contains(token) ||
                        label.lowercase().contains(token) ||
                        activityName.lowercase().contains(token)
                }
        }?.first
    }

    private suspend fun captureAfterDelay(
        settings: AppSettings,
        workspaceDirectory: String,
        delayMillis: Long,
    ): String {
        if (delayMillis > 0) delay(delayMillis)
        val bytes = capturePngBytes(settings)
        val captureId = "capture-${System.currentTimeMillis()}"
        val previewPath = File(cacheDirectory, "$captureId.png").absolutePath
        File(previewPath).writeBytes(bytes)
        File(cacheDirectory, "latest.png").writeBytes(bytes)
        val workspacePath = "$workspaceDirectory/agent-mode/$captureId.png"
        workspaceFileBridge.writeWorkspaceBytes(
            absolutePath = workspacePath,
            bytes = bytes,
        ).getOrThrow()
        val displayId = shizukuDisplayId
        val state = _displayState.value
        _displayState.value = AgentModeDisplayState(
            isActive = displayId != null,
            displayId = displayId,
            width = state.width,
            height = state.height,
            displays = currentDisplays(settings, displayId),
            latestPreviewPath = previewPath,
            latestWorkspacePath = workspacePath,
            lastUpdatedMillis = System.currentTimeMillis(),
            status = "Captured virtual display",
        )
        return JSONObject().apply {
            put("ok", true)
            put("display_id", displayId)
            put("width", state.width)
            put("height", state.height)
            put("screenshot_path", workspacePath)
            put("preview_path", previewPath)
            put("screenshot_mime_type", "image/png")
            put("screenshot_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("stdout", "Captured Agent Mode screenshot: $workspacePath")
        }.toString()
    }

    private suspend fun capturePngBytes(settings: AppSettings): ByteArray {
        val displayId = shizukuDisplayId ?: error("Agent Mode display is not running.")
        val service = requireAgentModeService(settings)
        return try {
            service.capturePngPipe(displayId).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.readBytes()
                }
            }
        } catch (deadObject: DeadObjectException) {
            clearDeadAgentModeService(settings)
            throw deadObject
        }
    }

    private fun clearDeadAgentModeService(settings: AppSettings) {
        when (settings.agentModeAuthorizationMethod) {
            AgentModeAuthorizationMethod.Shizuku -> {
                shizukuService = null
                unbindShizukuUserService()
            }
            AgentModeAuthorizationMethod.Root -> {
                rootService = null
                runCatching { rootProcess?.close() }
                rootProcess = null
            }
        }
        shizukuDisplayId = null
        _displayState.value = AgentModeDisplayState(
            isActive = false,
            displays = currentDisplaysLocal(null),
            status = "Agent Mode service disconnected",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun statusResult(settings: AppSettings): String {
        val state = _displayState.value
        val displays = currentDisplays(settings, state.displayId)
        _displayState.value = state.copy(
            displays = displays,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        return JSONObject().apply {
            put("ok", true)
            put("active", state.isActive)
            put("display_id", state.displayId)
            put("width", state.width)
            put("height", state.height)
            put(
                "displays",
                org.json.JSONArray().apply {
                    displays.forEach { display ->
                        put(
                            JSONObject().apply {
                                put("display_id", display.displayId)
                                put("name", display.name)
                                put("width", display.width)
                                put("height", display.height)
                                put("is_aether_display", display.isAetherDisplay)
                            }
                        )
                    }
                },
            )
            put("screenshot_path", state.latestWorkspacePath)
            put("stdout", if (state.isActive) "Agent Mode display is active." else "Agent Mode display is stopped.")
        }.toString()
    }

    private fun releaseDisplay() {
        shizukuDisplayId?.let { displayId ->
            runCatching { shizukuService?.releaseDisplay(displayId) }
            runCatching { rootService?.releaseDisplay(displayId) }
        }
        shizukuDisplayId = null
        _displayState.value = AgentModeDisplayState(
            isActive = false,
            displays = currentDisplaysLocal(null),
            status = "Virtual display stopped",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private fun captureAgentModeFailed(
        settings: AppSettings,
        action: String,
        reason: String,
        message: String,
    ) {
        AetherAnalytics.capture(
            event = "agent mode failed",
            properties = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message.take(280),
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
    }

    private suspend fun currentDisplays(
        settings: AppSettings,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val privilegedDisplays = runCatching {
            parseDisplays(requireAgentModeService(settings).listDisplaysJson(), aetherDisplayId)
        }.onFailure {
        }.getOrNull()
        if (privilegedDisplays != null) return privilegedDisplays
        return currentDisplaysLocal(aetherDisplayId)
    }

    private fun currentDisplaysLocal(aetherDisplayId: Int?): List<AgentModeDisplayInfo> =
        displayManager.displays.map { display ->
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            AgentModeDisplayInfo(
                displayId = display.displayId,
                name = display.name.orEmpty(),
                width = display.mode?.physicalWidth ?: size.x,
                height = display.mode?.physicalHeight ?: size.y,
                isAetherDisplay = display.displayId == aetherDisplayId ||
                    display.name.orEmpty().contains(AgentDisplayName, ignoreCase = true),
            )
        }.sortedBy { it.displayId }

    private fun parseDisplays(
        rawValue: String,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val array = org.json.JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val displayId = item.optInt("display_id")
                add(
                    AgentModeDisplayInfo(
                        displayId = displayId,
                        name = item.optString("name"),
                        width = item.optInt("width"),
                        height = item.optInt("height"),
                        isAetherDisplay = item.optBoolean("is_aether_display") ||
                            displayId == aetherDisplayId ||
                            item.optString("name").contains(AgentDisplayName, ignoreCase = true),
                    )
                )
            }
        }.sortedBy { it.displayId }
    }

    private suspend fun requireAgentModeService(settings: AppSettings): IAetherAgentModeService =
        when (settings.agentModeAuthorizationMethod) {
            AgentModeAuthorizationMethod.Shizuku -> requireShizukuService()
            AgentModeAuthorizationMethod.Root -> requireRootService()
        }

    private suspend fun requireRootService(): IAetherAgentModeService = withContext(Dispatchers.IO) {
        val existing = rootService
        if (existing != null) return@withContext existing
        val suPath = findSuPath()
        if (suPath.isBlank()) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "No su binary was detected on this device.",
            )
            error("No su binary was detected on this device.")
        }
        val process = object : AppProcess.Terminal() {
            override fun newTerminal(): List<String?> = listOf(suPath, RootAgentModeServiceUid)
        }
        if (!process.init(context)) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = "Root Agent Mode service failed to start. Check that su can be granted to Aether.",
            )
            error("Root Agent Mode service failed to start. Check that su can be granted to Aether.")
        }
        val binder = process.serviceBinder(
            ComponentName(context, AetherAgentModeShizukuService::class.java),
        )
        val service = IAetherAgentModeService.Stub.asInterface(binder)
            ?: error("Root Agent Mode service returned an invalid binder.")
        rootProcess = process
        rootService = service
        _authorizationState.value = AgentModeAuthorizationState(
            issue = AgentModeAuthorizationIssue.Ready,
            detail = "Root Agent Mode service is connected.",
        )
        service
    }

    private suspend fun requireShizukuService(): IAetherAgentModeService {
        val existing = shizukuService
        if (existing != null) return existing
        if (!Shizuku.pingBinder()) {
            error("Shizuku is not running.")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            error("Aether does not have Shizuku permission. Grant it in Shizuku first.")
        }
        val deferred = CompletableDeferred<IAetherAgentModeService>()
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, AetherAgentModeShizukuService::class.java),
        )
            .processNameSuffix("agentmode")
            .daemon(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val bound = IAetherAgentModeService.Stub.asInterface(service)
                shizukuService = bound
                if (!deferred.isCompleted) {
                    deferred.complete(bound)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                shizukuService = null
            }
        }
        shizukuServiceArgs = args
        shizukuServiceConnection = connection
        Shizuku.bindUserService(args, connection)
        return withTimeout(8_000) { deferred.await() }
    }

    private suspend fun inspectAuthorization(settings: AppSettings): AgentModeAuthorizationState =
        when {
            !settings.agentModeAuthorizationEnabled -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Disabled,
                detail = "Agent Mode authorization is disabled.",
            )

            settings.agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root -> inspectRootAuthorization()

            else -> inspectShizukuAuthorization()
        }

    private suspend fun inspectRootAuthorization(): AgentModeAuthorizationState = withContext(Dispatchers.IO) {
        if (rootService != null) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root Agent Mode service is already connected.",
            )
        }

        val suPath = findSuPath()
        if (suPath.isBlank()) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "No su binary was detected on this device.",
            )
        }

        val probe = runRootAuthorizationProbe(suPath)
        when {
            probe.launchError.isNotBlank() -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = probe.launchError,
            )

            probe.exitCode == 0 -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root authorization is granted.",
            )

            probe.timedOut -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionMissing,
                detail = "Root authorization timed out. Grant su to Aether, then refresh Agent Mode status.",
            )

            else -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = probe.combinedOutput().ifBlank {
                    "Root authorization was not granted. Grant su to Aether, then refresh Agent Mode status."
                }.take(280),
            )
        }
    }

    private fun inspectShizukuAuthorization(): AgentModeAuthorizationState {
        if (!isAnyPackageInstalled(ShizukuManagerPackages)) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotInstalled,
                detail = "Install Shizuku before using Shizuku Agent Mode.",
            )
        }
        val isRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!isRunning) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Start Shizuku, then refresh Agent Mode status.",
            )
        }
        return runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.Ready,
                    detail = "Shizuku permission is granted.",
                )
            } else {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                    detail = "Grant Aether permission in Shizuku before using Agent Mode.",
                )
            }
        }.getOrElse { throwable ->
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = throwable.message ?: "Unable to inspect Shizuku permission.",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun isAnyPackageInstalled(packageNames: List<String>): Boolean =
        packageNames.any { packageName ->
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }

    private fun findSuPath(): String {
        val commonPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
        )
        commonPaths.firstOrNull { path ->
            File(path).let { it.exists() && it.canExecute() }
        }?.let { return it }

        val result = runProcess(
            command = listOf("sh", "-c", "command -v su 2>/dev/null || true"),
            timeoutMillis = RootAuthorizationProbeTimeoutMillis,
        )
        return result.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
    }

    private fun runRootAuthorizationProbe(suPath: String): RootCommandResult =
        runProcess(
            command = listOf(suPath, "-c", "true"),
            timeoutMillis = RootAuthorizationProbeTimeoutMillis,
        )

    private fun runProcess(
        command: List<String>,
        timeoutMillis: Long,
    ): RootCommandResult {
        val process = runCatching {
            ProcessBuilder(command).start()
        }.getOrElse { throwable ->
            return RootCommandResult(
                exitCode = -1,
                launchError = throwable.message.orEmpty(),
            )
        }

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                runCatching { process.destroyForcibly() }
            }
        }

        val stdout = runCatching {
            process.inputStream.bufferedReader().readText()
        }.getOrDefault("")
        val stderr = runCatching {
            process.errorStream.bufferedReader().readText()
        }.getOrDefault("")
        return RootCommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout,
            stderr = stderr,
            timedOut = !finished,
        )
    }


    private fun normalizedX(value: Double): Int? =
        value.takeIf { !it.isNaN() }?.let { (it.coerceIn(0.0, 1000.0) * _displayState.value.width / 1000.0).toInt() }

    private fun normalizedY(value: Double): Int? =
        value.takeIf { !it.isNaN() }?.let { (it.coerceIn(0.0, 1000.0) * _displayState.value.height / 1000.0).toInt() }

    private fun invalidArguments(message: String): String =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", message)
        }.toString()

    private fun toolError(
        message: String,
        action: String,
    ): String = JSONObject().apply {
        put("ok", false)
        put("action", action)
        put("errmsg", message)
        put("stdout", "")
    }.toString()

    private fun currentDeviceDisplaySpec(): DisplaySpec {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        val metrics = context.resources.displayMetrics
        return DisplaySpec(
            width = (display?.mode?.physicalWidth ?: size.x).takeIf { it > 0 }
                ?: metrics.widthPixels.takeIf { it > 0 }
                ?: FallbackAgentDisplayWidth,
            height = (display?.mode?.physicalHeight ?: size.y).takeIf { it > 0 }
                ?: metrics.heightPixels.takeIf { it > 0 }
                ?: FallbackAgentDisplayHeight,
            densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: FallbackAgentDisplayDensityDpi,
        )
    }

    private data class DisplaySpec(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    private data class RootCommandResult(
        val exitCode: Int,
        val stdout: String = "",
        val stderr: String = "",
        val timedOut: Boolean = false,
        val launchError: String = "",
    ) {
        fun combinedOutput(): String = listOf(stdout, stderr, launchError)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
    }
}
