package com.zhousl.aether.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.DeadObjectException
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.Display
import androidx.core.content.getSystemService
import com.rosan.app_process.AppProcess
import com.zhousl.aether.agentmode.AetherAgentModeProcessContract
import com.zhousl.aether.agentmode.AetherAgentModeProcessMain
import com.zhousl.aether.agentmode.AetherAgentModeShizukuService
import com.zhousl.aether.agentmode.IAetherAgentModeService
import com.zhousl.aether.termux.TermuxBashTool
import com.zhousl.aether.util.AetherLog
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Agent Mode 虚拟显示固定分辨率。
 * 使用 900×1600 而非设备物理分辨率，确保截图尺寸可控，
 * 避免高分辨率图片被模型 API 内部缩放导致坐标精度损失。
 */
private const val AgentDisplayWidth = 900
private const val AgentDisplayHeight = 1600
private const val AgentDisplayDensityDpi = 440
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
    val isConnecting: Boolean = false,
    val displayId: Int? = null,
    val width: Int = AgentDisplayWidth,
    val height: Int = AgentDisplayHeight,
    val displays: List<AgentModeDisplayInfo> = emptyList(),
    val latestPreviewPath: String = "",
    val latestWorkspacePath: String = "",
    val lastUpdatedMillis: Long = 0L,
    val status: String = "",
    val errorCode: String = "",
    val userMessage: String = "",
    val suggestion: String = "",
)

data class AgentModeDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isAetherDisplay: Boolean,
)

data class AgentModeUserFacingError(
    val code: String,
    val message: String,
    val suggestion: String = "",
    val developerDetail: String = "",
)

private class AgentModeException(
    val userError: AgentModeUserFacingError,
    cause: Throwable? = null,
) : IllegalStateException(
    listOf(userError.message, userError.suggestion)
        .filter(String::isNotBlank)
        .joinToString(" "),
    cause,
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
    val suggestion: String = "",
    val errorCode: String = "",
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
                        detail = "Shizuku 授权已完成，Agent 模式可以使用。",
                        errorCode = "shizuku_ready",
                    )
                } else {
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.ShizukuPermissionDenied,
                        detail = "你拒绝了 Shizuku 授权，Agent 模式无法连接 Shizuku 服务。",
                        suggestion = "请在 Shizuku 应用中为 Aether 重新授权，或回到 Aether 再次发起授权请求。",
                        errorCode = "shizuku_permission_denied",
                    )
                }
            }
        }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        AetherLog.w(TAG, "Shizuku binder died while Agent Mode was active.")
        if (_authorizationState.value.issue != AgentModeAuthorizationIssue.Disabled) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Shizuku 已停止运行，Agent 模式服务连接已断开。",
                suggestion = "请重新启动 Shizuku，然后回到 Aether 刷新 Agent 模式授权状态。",
                errorCode = "shizuku_binder_dead",
            )
        }
        shizukuService = null
        shizukuDisplayId = null
        _displayState.value = _displayState.value.copy(
            isActive = false,
            isConnecting = false,
            displayId = null,
            displays = currentDisplaysLocal(null),
            status = "Shizuku 已停止，Agent 模式显示已断开。",
            errorCode = "shizuku_binder_dead",
            userMessage = "Shizuku 已停止运行，无法继续控制虚拟显示。",
            suggestion = "请启动 Shizuku 后重试；如仍失败，请在 Shizuku 中重新授权 Aether。",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private var shizukuDisplayId: Int? = null
    private var shizukuService: IAetherAgentModeService? = null
    private var shizukuProcess: Process? = null
    private var rootService: IAetherAgentModeService? = null
    private var rootProcess: AppProcess.Terminal? = null
    @Volatile private var isConnectingShizukuService = false
    @Volatile private var isStartingRootService = false
    @Volatile private var isCreatingDisplay = false

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
            val error = AgentModeUserFacingError(
                code = "authorization_disabled",
                message = "Agent 模式尚未授权。",
                suggestion = "请进入设置 > Agent 模式，选择 Shizuku 或 Root 并完成授权后再试。",
            )
            captureAgentModeFailed(
                settings = settings,
                action = "unknown",
                reason = error.code,
                message = error.message,
            )
            updateDisplayFailure(error)
            return@withContext toolError(error = error, action = "unknown")
        }

        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments(
                message = "参数不是有效的 JSON，Agent 模式无法解析本次操作。请重新发送工具调用。",
                code = "invalid_arguments",
            ).also {
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
                val displayId = ensureDisplay(settings)
                val state = _displayState.value
                JSONObject().apply {
                    put("ok", true)
                    put("display_id", displayId)
                    put("width", state.width)
                    put("height", state.height)
                    put("stdout", "Agent Mode virtual display started (${state.width}x${state.height}). No screenshot yet — use action=launch to open an app first, then action=screenshot.")
                }.toString()
            }
            "status" -> statusResult(settings)
            "launch" -> {
                ensureDisplay(settings)
                val target = arguments.optString("target").trim()
                if (target.isBlank()) {
                    invalidArguments("缺少要启动的应用名称或包名。请提供 target，例如 com.android.chrome。", "missing_target")
                } else {
                    launchTarget(settings, target)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 2500)
                }
            }
            "tap" -> {
                val displayId = ensureDisplay(settings)
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("点击坐标不完整：需要同时提供 x 和 y，范围为 0 到 1000。", "missing_tap_coordinates")
                } else {
                    runRemoteCall(settings, "tap", "tap_failed", "点击虚拟显示失败。", "请确认 Agent 模式显示仍处于活动状态；如果刚重启过 Shizuku 或 Root，请重新启动 Agent 模式。") {
                        requireAgentModeService(settings).tap(displayId, x, y)
                    }
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
                    invalidArguments("滑动坐标不完整：需要提供 x1、y1、x2、y2，范围为 0 到 1000。", "missing_swipe_coordinates")
                } else {
                    runRemoteCall(settings, "swipe", "swipe_failed", "滑动虚拟显示失败。", "请确认虚拟显示未被释放，并检查 Shizuku/Root 服务是否仍可用。") {
                        requireAgentModeService(settings).swipe(displayId, x1, y1, x2, y2, durationMs)
                    }
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = durationMs.toLong() + 250)
                }
            }
            "key" -> {
                val displayId = ensureDisplay(settings)
                val keyCode = arguments.optString("key").trim()
                if (keyCode.isBlank()) {
                    invalidArguments("缺少按键名称。请提供 key，例如 BACK、HOME 或 ENTER。", "missing_key")
                } else {
                    runRemoteCall(settings, "key", "key_failed", "发送按键失败。", "请确认按键名称有效，并检查 Agent 模式服务连接是否正常。") {
                        requireAgentModeService(settings).key(displayId, keyCode)
                    }
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 300)
                }
            }
            "text" -> {
                val displayId = ensureDisplay(settings)
                val text = arguments.optString("text")
                if (text.isBlank()) {
                    invalidArguments("缺少要输入的文本。请提供 text 后重试。", "missing_text")
                } else {
                    runRemoteCall(settings, "text", "text_failed", "输入文本失败。", "请确认目标应用处于可输入状态；如果服务已断开，请重新启动 Agent 模式后重试。") {
                        requireAgentModeService(settings).text(displayId, text)
                    }
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
                }
            }
            "sequence" -> {
                val displayId = ensureDisplay(settings)
                val steps = arguments.optJSONArray("steps")
                if (steps == null || steps.length() == 0) {
                    invalidArguments("sequence 需要提供 steps 数组。", "missing_steps")
                } else {
                    executeSequence(settings, displayId, steps)
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
            else -> invalidArguments("不支持的 Agent 模式操作：$action。请使用 start、status、launch、tap、swipe、key、text、sequence、screenshot 或 stop。", "unsupported_action").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = action.ifBlank { "unknown" },
                    reason = "unsupported_action",
                    message = "Unsupported action '$action'.",
                )
            }
            }
        }.getOrElse { throwable ->
            val userError = throwable.toAgentModeUserError(settings, action.ifBlank { "unknown" })
            AetherLog.e(TAG, "Agent Mode action '${action.ifBlank { "unknown" }}' failed: ${userError.developerDetail.ifBlank { throwable.message }}", throwable)
            captureAgentModeFailed(
                settings = settings,
                action = action.ifBlank { "unknown" },
                reason = userError.code,
                message = userError.message,
            )
            updateDisplayFailure(userError)
            toolError(
                error = userError,
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
                detail = "请在 Shizuku 弹窗中允许 Aether 使用 Shizuku。",
                suggestion = "授权后返回 Aether，点击刷新 Agent 模式状态。",
                errorCode = "shizuku_permission_prompt_pending",
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
            AetherLog.e(TAG, "Failed to request Shizuku permission.", throwable)
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = "无法拉起 Shizuku 授权请求。",
                suggestion = "请确认 Shizuku 已安装并正在运行，然后在 Shizuku 应用内手动授权 Aether。",
                errorCode = "shizuku_permission_request_failed",
            )
        }.also { _authorizationState.value = it }
    }

    private suspend fun ensureDisplay(settings: AppSettings): Int {
        shizukuDisplayId?.let { return it }
        if (isCreatingDisplay || _displayState.value.isConnecting) {
            throwAgentModeError(
                code = "display_connecting",
                message = "Agent 模式正在创建虚拟显示，请稍候。",
                suggestion = "等待当前连接完成后再执行新的 Agent 模式操作。",
            )
        }
        isCreatingDisplay = true
        _displayState.value = _displayState.value.copy(
            isConnecting = true,
            status = "正在创建 Agent 模式虚拟显示…",
            errorCode = "",
            userMessage = "",
            suggestion = "",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        try {
            val displaySpec = currentDeviceDisplaySpec()
            val service = requireAgentModeService(settings)
            val displayId = runRemoteCall(
                settings = settings,
                action = "create_display",
                code = "display_create_failed",
                message = "创建 Agent 模式虚拟显示失败。",
                suggestion = "请确认设备支持虚拟显示/投屏能力；若使用 Shizuku，请检查 Shizuku 正在运行；若使用 Root，请确认已授权。",
            ) {
                service.createOwnedDisplay(
                    AgentDisplayName,
                    displaySpec.width,
                    displaySpec.height,
                    displaySpec.densityDpi,
                )
            }
            if (displayId < 0) {
                throwAgentModeError(
                    code = "display_invalid_id",
                    message = "系统返回了无效的虚拟显示编号，Agent 模式未能启动。",
                    suggestion = "请重试；如果仍然失败，请重启 Shizuku/Root 服务或检查设备系统是否支持虚拟显示。",
                    developerDetail = "createOwnedDisplay returned $displayId",
                )
            }
            shizukuDisplayId = displayId
            _displayState.value = AgentModeDisplayState(
                isActive = true,
                isConnecting = false,
                displayId = displayId,
                width = displaySpec.width,
                height = displaySpec.height,
                displays = currentDisplays(settings, displayId),
                status = "${settings.agentModeAuthorizationMethod.displayName} 虚拟显示已就绪。",
                lastUpdatedMillis = System.currentTimeMillis(),
            )
            return displayId
        } catch (throwable: Throwable) {
            val userError = throwable.toAgentModeUserError(settings, "start")
            updateDisplayFailure(userError)
            throw throwable
        } finally {
            isCreatingDisplay = false
        }
    }

    fun stopDisplay() {
        releaseDisplay()
    }

    suspend fun refreshDisplays(settings: AppSettings) {
        val state = _displayState.value
        val displays = runCatching { currentDisplays(settings, state.displayId) }
            .onFailure { throwable ->
                val userError = throwable.toAgentModeUserError(settings, "status")
                AetherLog.w(TAG, "Failed to refresh Agent Mode displays: ${userError.developerDetail}", throwable)
                updateDisplayFailure(userError)
            }
            .getOrElse { currentDisplaysLocal(state.displayId) }
        _displayState.value = _displayState.value.copy(
            displays = displays,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun launchTarget(
        settings: AppSettings,
        target: String,
    ) {
        val displayId = ensureDisplay(settings)
        val launchPackage = resolveLaunchPackage(target)
            ?: throwAgentModeError(
                code = "launch_target_not_found",
                message = "未找到可启动的应用：$target。",
                suggestion = "请检查应用是否已安装，或改用准确包名，例如 com.android.chrome。",
            )
        val service = requireAgentModeService(settings)
        runRemoteCall(
            settings = settings,
            action = "launch",
            code = "launch_failed",
            message = "无法在 Agent 模式虚拟显示中启动应用：$target。",
            suggestion = "请确认目标应用已安装且允许启动；如果是系统限制，请尝试使用包名或先手动打开一次应用。",
        ) {
            service.launchPackage(launchPackage, displayId)
        }
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
        val bytes = runCatching { capturePngBytes(settings) }
            .getOrElse { throwable ->
                val userError = throwable.toAgentModeUserError(settings, "screenshot")
                updateDisplayFailure(userError)
                throw throwable
            }
        val captureId = "capture-${System.currentTimeMillis()}"
        val previewPath = File(cacheDirectory, "$captureId.jpg").absolutePath
        runCatching { File(previewPath).writeBytes(bytes) }
            .onFailure { throwable ->
                AetherLog.e(TAG, "Failed to write Agent Mode preview image to ${AetherLog.summarizePath(previewPath)}", throwable)
                throwAgentModeError(
                    code = "preview_write_failed",
                    message = "截图已生成，但无法保存本地预览文件。",
                    suggestion = "请检查设备存储空间是否充足，然后重试。",
                    developerDetail = throwable.message.orEmpty(),
                    cause = throwable,
                )
            }
        runCatching { File(cacheDirectory, "latest.jpg").writeBytes(bytes) }
            .onFailure { throwable -> AetherLog.w(TAG, "Failed to update latest Agent Mode preview cache.", throwable) }
        val workspacePath = "$workspaceDirectory/agent-mode/$captureId.jpg"
        // 异步写入工作区文件，不阻塞截图响应返回给模型
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            workspaceFileBridge.writeWorkspaceBytes(
                absolutePath = workspacePath,
                bytes = bytes,
            ).onFailure { throwable ->
                AetherLog.e(TAG, "Failed to write Agent Mode screenshot to workspace: ${AetherLog.summarizePath(workspacePath)}", throwable)
            }
        }
        val displayId = shizukuDisplayId
        val state = _displayState.value
        _displayState.value = AgentModeDisplayState(
            isActive = displayId != null,
            isConnecting = false,
            displayId = displayId,
            width = state.width,
            height = state.height,
            displays = currentDisplays(settings, displayId),
            latestPreviewPath = previewPath,
            latestWorkspacePath = workspacePath,
            lastUpdatedMillis = System.currentTimeMillis(),
            status = "已捕获 Agent 模式截图。",
        )
        // 空白占位图检测：如果截图 < 15KB，说明虚拟显示尚无实际内容渲染
        val isBlankPlaceholder = bytes.size < 15_000
        return JSONObject().apply {
            put("ok", true)
            put("display_id", displayId)
            put("width", state.width)
            put("height", state.height)
            if (isBlankPlaceholder) {
                put("blank", true)
                put("stdout", "The virtual display has no rendered content yet — the app is still loading. " +
                    "Wait 2-3 seconds and then use action=screenshot to check again. Do NOT repeat the launch action.")
            } else {
                put("screenshot_path", workspacePath)
                put("preview_path", previewPath)
                put("screenshot_mime_type", "image/jpeg")
                val annotatedBytes = annotateScreenshotWithCoordinateRulers(bytes, state.width, state.height)
                put("screenshot_base64", Base64.encodeToString(annotatedBytes, Base64.NO_WRAP))
                put("coordinate_space", "0-1000 normalized on both axes; top-left=(0,0) bottom-right=(1000,1000); minor ticks every 50, major ticks with labels every 100")
                put("stdout", "Captured Agent Mode screenshot (${state.width}x${state.height}). Use the red ruler marks on edges to estimate tap coordinates in the 0-1000 space.")
            }
        }.toString()
    }

    private suspend fun capturePngBytes(settings: AppSettings): ByteArray {
        val displayId = shizukuDisplayId ?: throwAgentModeError(
            code = "display_not_active",
            message = "Agent 模式虚拟显示尚未启动，无法截图。",
            suggestion = "请先执行 start 操作创建虚拟显示，再进行截图或控制操作。",
        )
        val service = requireAgentModeService(settings)
        return try {
            runRemoteCall(
                settings = settings,
                action = "screenshot",
                code = "screenshot_failed",
                message = "捕获 Agent 模式截图失败。",
                suggestion = "请确认虚拟显示仍在运行；如果 Shizuku/Root 服务刚重启，请重新启动 Agent 模式。",
            ) {
                service.capturePngPipe(displayId).use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        input.readBytes().takeIf { it.isNotEmpty() }
                            ?: error("Agent Mode screenshot returned 0 bytes.")
                    }
                }
            }
        } catch (deadObject: DeadObjectException) {
            clearDeadAgentModeService(settings, deadObject)
            throw deadObject
        }
    }

    private fun clearDeadAgentModeService(settings: AppSettings, cause: Throwable? = null) {
        AetherLog.w(TAG, "Agent Mode service connection is no longer usable.", cause)
        when (settings.agentModeAuthorizationMethod) {
            AgentModeAuthorizationMethod.Shizuku -> {
                shizukuService = null
                unbindShizukuUserService()
            }
            AgentModeAuthorizationMethod.Root -> {
                rootService = null
                runCatching { rootProcess?.close() }
                    .onFailure { throwable -> AetherLog.w(TAG, "Failed to close Root Agent Mode process after service death.", throwable) }
                rootProcess = null
            }
        }
        shizukuDisplayId = null
        _displayState.value = AgentModeDisplayState(
            isActive = false,
            isConnecting = false,
            displays = currentDisplaysLocal(null),
            status = "Agent 模式服务已断开。",
            errorCode = "service_disconnected",
            userMessage = "Agent 模式服务连接已断开，当前虚拟显示不可再使用。",
            suggestion = "请重新启动 Agent 模式；如果反复断开，请检查 Shizuku 是否运行或 Root 权限是否稳定。",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun statusResult(settings: AppSettings): String {
        val state = _displayState.value
        val displays = runCatching { currentDisplays(settings, state.displayId) }
            .onFailure { throwable ->
                val userError = throwable.toAgentModeUserError(settings, "status")
                AetherLog.w(TAG, "Failed to query Agent Mode display status: ${userError.developerDetail}", throwable)
                updateDisplayFailure(userError)
            }
            .getOrElse { currentDisplaysLocal(state.displayId) }
        val updatedState = _displayState.value.copy(
            displays = displays,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        _displayState.value = updatedState
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
            put("screenshot_path", updatedState.latestWorkspacePath)
            put("status", updatedState.status)
            put("error_code", updatedState.errorCode)
            put("user_message", updatedState.userMessage)
            put("suggestion", updatedState.suggestion)
            put("stdout", if (updatedState.isActive) "Agent 模式虚拟显示正在运行。" else "Agent 模式虚拟显示未启动。")
        }.toString()
    }

    private fun releaseDisplay() {
        val displayId = shizukuDisplayId
        if (displayId == null) {
            AetherLog.i(TAG, "releaseDisplay ignored because no Agent Mode display is active.")
            _displayState.value = _displayState.value.copy(
                isActive = false,
                isConnecting = false,
                displays = currentDisplaysLocal(null),
                status = "Agent 模式虚拟显示未启动，无需停止。",
                errorCode = "display_not_active",
                userMessage = "当前没有正在运行的 Agent 模式虚拟显示。",
                suggestion = "如需使用 Agent 模式，请先执行 start 操作。",
                lastUpdatedMillis = System.currentTimeMillis(),
            )
            return
        }
        val shizukuReleaseFailure = runCatching { shizukuService?.releaseDisplay(displayId) }.exceptionOrNull()
        val rootReleaseFailure = runCatching { rootService?.releaseDisplay(displayId) }.exceptionOrNull()
        shizukuReleaseFailure?.let { AetherLog.w(TAG, "Failed to release Shizuku Agent Mode display $displayId.", it) }
        rootReleaseFailure?.let { AetherLog.w(TAG, "Failed to release Root Agent Mode display $displayId.", it) }
        shizukuDisplayId = null
        val releaseError = shizukuReleaseFailure ?: rootReleaseFailure
        _displayState.value = AgentModeDisplayState(
            isActive = false,
            isConnecting = false,
            displays = currentDisplaysLocal(null),
            status = if (releaseError == null) "Agent 模式虚拟显示已停止。" else "Agent 模式显示已标记为停止，但释放过程中出现异常。",
            errorCode = if (releaseError == null) "" else "display_release_failed",
            userMessage = if (releaseError == null) "" else "虚拟显示释放时出现异常，已清理本地状态。",
            suggestion = if (releaseError == null) "" else "如果后续无法重新启动，请重启 Shizuku/Root 服务后再试。",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private fun unbindShizukuUserService() {
        val process = shizukuProcess
        if (process == null) {
            AetherLog.d(TAG, "Shizuku app_process stop skipped: process was not started.")
            return
        }
        runCatching { process.destroy() }
            .onFailure { throwable ->
                AetherLog.w(TAG, "Failed to stop Shizuku Agent Mode app_process.", throwable)
                _displayState.value = _displayState.value.copy(
                    errorCode = "shizuku_unbind_failed",
                    userMessage = "Shizuku Agent 模式进程停止时出现异常，可能仍有残留连接。",
                    suggestion = "如再次启动失败，请重启 Shizuku 后重试。",
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            }
        shizukuProcess = null
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
        return try {
            val service = requireAgentModeService(settings)
            runRemoteCall(
                settings = settings,
                action = "list_displays",
                code = "display_list_failed",
                message = "无法从 Agent 模式服务读取显示列表。",
                suggestion = "将使用系统本地显示列表作为备用；如果列表不准确，请刷新状态或重启 Agent 模式服务。",
            ) {
                parseDisplays(service.listDisplaysJson(), aetherDisplayId)
            }
        } catch (throwable: Throwable) {
            AetherLog.w(TAG, "Falling back to local display list because privileged display query failed.", throwable)
            currentDisplaysLocal(aetherDisplayId)
        }
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
        if (isStartingRootService) {
            throwAgentModeError(
                code = "root_service_starting",
                message = "Root Agent 模式服务正在启动，请稍候。",
                suggestion = "等待当前启动流程完成后再重试。",
            )
        }
        isStartingRootService = true
        try {
            val suPath = findSuPath()
            if (suPath.isBlank()) {
                _authorizationState.value = AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.RootUnavailable,
                    detail = "当前设备未检测到可用的 su。",
                    suggestion = "请确认设备已获取 Root，且 su 命令可执行；否则请改用 Shizuku 模式。",
                    errorCode = "root_su_missing",
                )
                throwAgentModeError(
                    code = "root_su_missing",
                    message = "当前设备未检测到可用的 Root 环境。",
                    suggestion = "请确认设备已 Root 并授予 Aether 权限，或切换到 Shizuku 模式。",
                )
            }
            val process = object : AppProcess.Terminal() {
                override fun newTerminal(): List<String?> = listOf(suPath, RootAgentModeServiceUid)
            }
            if (!process.init(context)) {
                _authorizationState.value = AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                    detail = "Root Agent 模式服务启动失败。",
                    suggestion = "请在 Root 管理器中允许 Aether 获取 su 权限，然后刷新 Agent 模式状态。",
                    errorCode = "root_service_start_failed",
                )
                throwAgentModeError(
                    code = "root_service_start_failed",
                    message = "Root Agent 模式服务启动失败。",
                    suggestion = "请确认 Root 管理器已授权 Aether；如果刚拒绝过权限，请重新授权后再试。",
                )
            }
            val binder = runCatching {
                process.serviceBinder(ComponentName(context, AetherAgentModeShizukuService::class.java))
            }.getOrElse { throwable ->
                runCatching { process.close() }
                throwAgentModeError(
                    code = "root_service_binder_failed",
                    message = "Root 服务已启动，但无法取得 Agent 模式服务连接。",
                    suggestion = "请重试；如果持续失败，请检查系统是否限制 app_process 或重启设备后再试。",
                    developerDetail = throwable.message.orEmpty(),
                    cause = throwable,
                )
            }
            val service = IAetherAgentModeService.Stub.asInterface(binder)
                ?: run {
                    runCatching { process.close() }
                    throwAgentModeError(
                        code = "root_service_invalid_binder",
                        message = "Root 服务返回了无效连接，Agent 模式无法使用。",
                        suggestion = "请重启 Agent 模式；如果仍失败，请重启设备或改用 Shizuku 模式。",
                    )
                }
            rootProcess = process
            rootService = service
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root Agent 模式服务已连接。",
                errorCode = "root_ready",
            )
            service
        } finally {
            isStartingRootService = false
        }
    }

    private suspend fun requireShizukuService(): IAetherAgentModeService = withContext(Dispatchers.IO) {
        val existing = shizukuService
        if (existing != null) return@withContext existing
        if (isConnectingShizukuService) {
            throwAgentModeError(
                code = "shizuku_service_connecting",
                message = "Shizuku Agent 模式服务正在连接，请稍候。",
                suggestion = "等待当前连接完成后再重试，避免重复启动服务。",
            )
        }
        if (!isAnyPackageInstalled(ShizukuManagerPackages)) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotInstalled,
                detail = "未检测到 Shizuku 应用。",
                suggestion = "请先安装 Shizuku，并按 Shizuku 指引启动服务。",
                errorCode = "shizuku_not_installed",
            )
            throwAgentModeError("shizuku_not_installed", "未安装 Shizuku，无法使用 Shizuku Agent 模式。", "请安装并启动 Shizuku，或切换到 Root 模式。")
        }
        val binderRunning = runCatching { Shizuku.pingBinder() }.getOrElse { throwable ->
            AetherLog.w(TAG, "Shizuku pingBinder failed.", throwable)
            false
        }
        if (!binderRunning) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Shizuku 当前未运行或 Binder 不可用。",
                suggestion = "请打开 Shizuku 并启动服务，然后回到 Aether 刷新授权状态。",
                errorCode = "shizuku_not_running",
            )
            throwAgentModeError("shizuku_not_running", "Shizuku 未运行，Agent 模式无法连接服务。", "请启动 Shizuku 后重试；如果已启动，请刷新状态或重启 Shizuku。")
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                detail = "Aether 尚未获得 Shizuku 授权。",
                suggestion = "请在 Shizuku 中允许 Aether 使用权限，然后刷新 Agent 模式状态。",
                errorCode = "shizuku_permission_missing",
            )
            throwAgentModeError("shizuku_permission_missing", "Aether 尚未获得 Shizuku 授权。", "请在 Shizuku 中授权 Aether，或点击授权按钮重新发起请求。")
        }
        isConnectingShizukuService = true
        try {
            val binder = startShizukuAgentModeProcess()
            val service = IAetherAgentModeService.Stub.asInterface(binder)
                ?: throwAgentModeError(
                    code = "shizuku_service_null_binder",
                    message = "Shizuku Agent 模式进程已启动，但返回的服务对象为空。",
                    suggestion = "请重启 Shizuku 后重试；如果仍失败，请切换到 Root 授权模式。",
                )
            runCatching {
                service.asBinder().linkToDeath({ handleShizukuServiceDeath() }, 0)
            }.onFailure { throwable ->
                AetherLog.w(TAG, "Unable to observe Shizuku Agent Mode app_process binder death.", throwable)
            }
            shizukuService = service
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Shizuku Agent 模式服务已连接。",
                errorCode = "shizuku_ready",
            )
            return@withContext service
        } catch (timeout: TimeoutException) {
            val isMiui = isMiuiDevice()
            val suggestion = if (isMiui) {
                "当前设备为 MIUI/HyperOS 系统，已绕过 Shizuku UserService 启动器但 app_process 仍未返回服务连接。" +
                    "请重启 Shizuku 后重试；若设备已 Root，切换到 Root 授权模式可继续绕过系统兼容性问题。"
            } else {
                "请检查 Shizuku 是否正在运行，重启 Shizuku 后再试。"
            }
            runCatching { shizukuProcess?.destroy() }
            shizukuProcess = null
            throwAgentModeError(
                code = if (isMiui) "shizuku_app_process_timeout_miui" else "shizuku_app_process_timeout",
                message = "启动 Shizuku Agent 模式进程超时。",
                suggestion = suggestion,
                cause = timeout,
            )
        } catch (throwable: Throwable) {
            if (throwable is AgentModeException) throw throwable
            runCatching { shizukuProcess?.destroy() }
            shizukuProcess = null
            throwAgentModeError(
                code = "shizuku_app_process_start_failed",
                message = "启动 Shizuku Agent 模式进程失败。",
                suggestion = "请确认 Shizuku 正在运行且已授权 Aether；如果刚更新过应用，请重启 Shizuku 后重试。",
                developerDetail = throwable.message.orEmpty(),
                cause = throwable,
            )
        } finally {
            isConnectingShizukuService = false
        }
    }

    /**
     * 通过 Shizuku 的远程 app_process 能力启动 Agent Mode 进程并接收服务 Binder。
     */
    private fun startShizukuAgentModeProcess(): IBinder {
        val token = UUID.randomUUID().toString()
        val binderQueue = java.util.concurrent.ArrayBlockingQueue<IBinder>(1)
        val workerThread = HandlerThread("aether-shizuku-agentmode-bind").apply { start() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != AetherAgentModeProcessContract.ActionServiceStarted) return
                if (intent.getStringExtra(AetherAgentModeProcessContract.ExtraToken) != token) return
                val binder = intent.extras?.getBinder(AetherAgentModeProcessContract.ExtraBinder) ?: return
                binderQueue.offer(binder)
            }
        }
        try {
            val filter = IntentFilter(AetherAgentModeProcessContract.ActionServiceStarted)
            val handler = Handler(workerThread.looper)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter, null, handler)
            }
            shizukuProcess = launchShizukuRemoteProcess(token)
            return binderQueue.poll(15, TimeUnit.SECONDS)
                ?: throw TimeoutException("Timed out waiting for Agent Mode app_process binder.")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            workerThread.quitSafely()
        }
    }

    /**
     * 反射调用 Shizuku.newProcess，直接运行 Aether 的 app_process 入口，避开 UserServiceStarter。
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun launchShizukuRemoteProcess(token: String): Process {
        val command = arrayOf(
            "/system/bin/app_process",
            "-Djava.class.path=${context.packageCodePath}",
            "/system/bin",
            AetherAgentModeProcessMain::class.java.name,
            "--package=${context.packageName}",
            "--token=$token",
        )
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command as Any, null, null) as? Process
            ?: error("Shizuku.newProcess returned null.")
    }

    /**
     * 处理 Shizuku app_process Binder 死亡，清理本地服务和虚拟显示状态。
     */
    private fun handleShizukuServiceDeath() {
        AetherLog.w(TAG, "Shizuku Agent Mode app_process binder died.")
        shizukuService = null
        shizukuProcess = null
        shizukuDisplayId = null
        _displayState.value = _displayState.value.copy(
            isActive = false,
            isConnecting = false,
            displayId = null,
            status = "Shizuku Agent 模式服务已断开。",
            errorCode = "shizuku_service_disconnected",
            userMessage = "Shizuku 服务连接已断开，当前虚拟显示不可继续使用。",
            suggestion = "请确认 Shizuku 仍在运行，然后重新启动 Agent 模式。",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun inspectAuthorization(settings: AppSettings): AgentModeAuthorizationState =
        when {
            !settings.agentModeAuthorizationEnabled -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Disabled,
                detail = "Agent 模式尚未启用。",
                suggestion = "请在设置 > Agent 模式中启用并选择授权方式。",
                errorCode = "authorization_disabled",
            )

            settings.agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root -> inspectRootAuthorization()

            else -> inspectShizukuAuthorization()
        }

    private suspend fun inspectRootAuthorization(): AgentModeAuthorizationState = withContext(Dispatchers.IO) {
        if (rootService != null) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root Agent 模式服务已连接。",
                errorCode = "root_ready",
            )
        }

        val suPath = findSuPath()
        if (suPath.isBlank()) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "当前设备未检测到可用的 su。",
                suggestion = "请确认设备已 Root，且 su 命令可执行；否则请改用 Shizuku 模式。",
                errorCode = "root_su_missing",
            )
        }

        val probe = runRootAuthorizationProbe(suPath)
        when {
            probe.launchError.isNotBlank() -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = "Root 授权探测命令无法启动：${probe.launchError.take(180)}",
                suggestion = "请确认系统允许 Aether 执行 su，或切换到 Shizuku 模式。",
                errorCode = "root_probe_launch_failed",
            )

            probe.exitCode == 0 -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root 授权已可用。",
                errorCode = "root_ready",
            )

            probe.timedOut -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionMissing,
                detail = "Root 授权请求超时，可能正在等待 Root 管理器确认。",
                suggestion = "请查看 Root 管理器弹窗并允许 Aether，然后刷新 Agent 模式状态。",
                errorCode = "root_permission_timeout",
            )

            else -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = probe.combinedOutput().ifBlank {
                    "Root 权限未授予，Agent 模式无法启动 Root 服务。"
                }.take(280),
                suggestion = "请在 Root 管理器中允许 Aether 获取 su 权限，然后刷新状态。",
                errorCode = "root_permission_denied",
            )
        }
    }

    private fun inspectShizukuAuthorization(): AgentModeAuthorizationState {
        if (!isAnyPackageInstalled(ShizukuManagerPackages)) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotInstalled,
                detail = "未检测到 Shizuku 应用。",
                suggestion = "请先安装 Shizuku，并按 Shizuku 指引启动服务。",
                errorCode = "shizuku_not_installed",
            )
        }
        val isRunning = runCatching { Shizuku.pingBinder() }
            .onFailure { throwable -> AetherLog.w(TAG, "Unable to ping Shizuku binder while inspecting authorization.", throwable) }
            .getOrDefault(false)
        if (!isRunning) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Shizuku 当前未运行或 Binder 不可用。",
                suggestion = "请打开 Shizuku 并启动服务，然后回到 Aether 刷新 Agent 模式状态。",
                errorCode = "shizuku_not_running",
            )
        }
        return runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.Ready,
                    detail = "Shizuku 授权已完成。",
                    errorCode = "shizuku_ready",
                )
            } else {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                    detail = "Aether 尚未获得 Shizuku 授权。",
                    suggestion = "请在 Shizuku 中允许 Aether 使用权限，然后刷新 Agent 模式状态。",
                    errorCode = "shizuku_permission_missing",
                )
            }
        }.getOrElse { throwable ->
            AetherLog.w(TAG, "Unable to inspect Shizuku permission.", throwable)
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = "无法读取 Shizuku 授权状态。",
                suggestion = "请确认 Shizuku 正在运行；如果状态仍异常，请重启 Shizuku 后刷新。",
                errorCode = "shizuku_permission_inspect_failed",
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

    /**
     * 批量执行多个 Agent Mode 步骤，跳过中间截图，只在全部完成后统一截图一次。
     * 每步之间插入短暂延迟确保 UI 响应。
     */
    private suspend fun executeSequence(
        settings: AppSettings,
        displayId: Int,
        steps: org.json.JSONArray,
    ) {
        val service = requireAgentModeService(settings)
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val stepAction = step.optString("action").trim().lowercase()
            val waitMs = step.optLong("wait_ms", step.optLong("waitMs", 0L))
                .coerceIn(0L, 5_000L)
            when (stepAction) {
                "tap" -> {
                    val x = normalizedX(step.optDouble("x", Double.NaN))
                    val y = normalizedY(step.optDouble("y", Double.NaN))
                    if (x != null && y != null) {
                        service.tap(displayId, x, y)
                    }
                }
                "swipe" -> {
                    val x1 = normalizedX(step.optDouble("x1", Double.NaN))
                    val y1 = normalizedY(step.optDouble("y1", Double.NaN))
                    val x2 = normalizedX(step.optDouble("x2", Double.NaN))
                    val y2 = normalizedY(step.optDouble("y2", Double.NaN))
                    val dur = step.optInt("duration_ms", step.optInt("durationMs", 500))
                        .coerceIn(50, 10_000)
                    if (x1 != null && y1 != null && x2 != null && y2 != null) {
                        service.swipe(displayId, x1, y1, x2, y2, dur)
                    }
                }
                "key" -> {
                    val key = step.optString("key").trim()
                    if (key.isNotBlank()) service.key(displayId, key)
                }
                "text" -> {
                    val text = step.optString("text")
                    if (text.isNotBlank()) service.text(displayId, text)
                }
                "wait" -> {
                    // wait_ms 在下方统一处理
                }
                "launch" -> {
                    val target = step.optString("target").trim()
                    if (target.isNotBlank()) launchTarget(settings, target)
                }
                else -> AetherLog.w(TAG, "Sequence step $i: unsupported action '$stepAction', skipped.")
            }
            if (waitMs > 0) delay(waitMs)
            else if (stepAction != "wait") delay(150L)
        }
    }

    private fun throwAgentModeError(
        code: String,
        message: String,
        suggestion: String = "",
        developerDetail: String = "",
        cause: Throwable? = null,
    ): Nothing = throw AgentModeException(
        userError = AgentModeUserFacingError(
            code = code,
            message = message,
            suggestion = suggestion,
            developerDetail = developerDetail,
        ),
        cause = cause,
    )

    private fun updateDisplayFailure(error: AgentModeUserFacingError) {
        _displayState.value = _displayState.value.copy(
            isConnecting = false,
            status = error.message,
            errorCode = error.code,
            userMessage = error.message,
            suggestion = error.suggestion,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun <T> runRemoteCall(
        settings: AppSettings,
        action: String,
        code: String,
        message: String,
        suggestion: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (deadObject: DeadObjectException) {
        clearDeadAgentModeService(settings, deadObject)
        throwAgentModeError(
            code = "service_disconnected",
            message = "Agent 模式服务连接已断开，无法继续执行 ${action}。",
            suggestion = "请重新启动 Agent 模式；如果反复断开，请检查 Shizuku 是否运行或 Root 权限是否稳定。",
            developerDetail = deadObject.message.orEmpty(),
            cause = deadObject,
        )
    } catch (security: SecurityException) {
        throwAgentModeError(
            code = "${code}_permission_denied",
            message = "$message 当前授权不足。",
            suggestion = when (settings.agentModeAuthorizationMethod) {
                AgentModeAuthorizationMethod.Shizuku -> "请在 Shizuku 中确认 Aether 已获授权，然后重新启动 Agent 模式。"
                AgentModeAuthorizationMethod.Root -> "请在 Root 管理器中允许 Aether 获取 Root 权限，然后重试。"
            },
            developerDetail = security.message.orEmpty(),
            cause = security,
        )
    } catch (timeout: TimeoutCancellationException) {
        throwAgentModeError(
            code = "${code}_timeout",
            message = "$message 服务响应超时。",
            suggestion = "请稍后重试；如果持续超时，请重启 Shizuku/Root 服务后再启动 Agent 模式。",
            developerDetail = timeout.message.orEmpty(),
            cause = timeout,
        )
    } catch (agentMode: AgentModeException) {
        throw agentMode
    } catch (throwable: Throwable) {
        throwAgentModeError(
            code = code,
            message = message,
            suggestion = suggestion,
            developerDetail = throwable.message.orEmpty(),
            cause = throwable,
        )
    }

    private fun Throwable.toAgentModeUserError(
        settings: AppSettings,
        action: String,
    ): AgentModeUserFacingError = when (this) {
        is AgentModeException -> userError
        is DeadObjectException -> AgentModeUserFacingError(
            code = "service_disconnected",
            message = "Agent 模式服务连接已断开，无法继续执行 ${action}。",
            suggestion = "请重新启动 Agent 模式；如果反复断开，请检查 Shizuku 是否运行或 Root 权限是否稳定。",
            developerDetail = message.orEmpty(),
        )
        is TimeoutCancellationException -> AgentModeUserFacingError(
            code = "${action}_timeout",
            message = "Agent 模式执行 ${action} 超时。",
            suggestion = "请稍后重试；如果持续超时，请重启 Shizuku/Root 服务后再启动 Agent 模式。",
            developerDetail = message.orEmpty(),
        )
        is SecurityException -> AgentModeUserFacingError(
            code = "${action}_permission_denied",
            message = "Agent 模式没有足够权限执行 ${action}。",
            suggestion = when (settings.agentModeAuthorizationMethod) {
                AgentModeAuthorizationMethod.Shizuku -> "请在 Shizuku 中重新授权 Aether，然后重试。"
                AgentModeAuthorizationMethod.Root -> "请在 Root 管理器中允许 Aether 获取 Root 权限，然后重试。"
            },
            developerDetail = message.orEmpty(),
        )
        is IllegalArgumentException -> AgentModeUserFacingError(
            code = "${action}_invalid_state",
            message = "Agent 模式参数或状态不符合当前操作要求。",
            suggestion = "请刷新 Agent 模式状态后重试；如果是启动应用，请检查应用包名或名称是否正确。",
            developerDetail = message.orEmpty(),
        )
        else -> AgentModeUserFacingError(
            code = "${action}_failed",
            message = "Agent 模式执行 ${action} 失败。",
            suggestion = when (settings.agentModeAuthorizationMethod) {
                AgentModeAuthorizationMethod.Shizuku -> "请确认 Shizuku 正在运行且已授权 Aether，然后重试。"
                AgentModeAuthorizationMethod.Root -> "请确认 Root 权限可用且已授权 Aether，然后重试。"
            },
            developerDetail = message.orEmpty(),
        )
    }

    private fun invalidArguments(
        message: String,
        code: String = "invalid_arguments",
    ): String = JSONObject().apply {
        put("ok", false)
        put("errmsg", message)
        put("error_code", code)
        put("user_message", message)
        put("suggestion", "请检查工具调用参数后重试。")
        put("stdout", "")
    }.toString()

    private fun toolError(
        error: AgentModeUserFacingError,
        action: String,
    ): String = JSONObject().apply {
        put("ok", false)
        put("action", action.ifBlank { "unknown" })
        put("errmsg", error.message)
        put("error_code", error.code)
        put("user_message", error.message)
        put("suggestion", error.suggestion)
        put("developer_detail", error.developerDetail)
        put("stdout", "${error.message}${if (error.suggestion.isBlank()) "" else " ${error.suggestion}"}")
    }.toString()

    /**
     * 在截图边缘绘制归一化坐标刻度标注（0-1000），帮助模型精确估算点击位置。
     * 仅用于发送给模型的 base64 截图，用户预览不受影响。
     */
    private fun annotateScreenshotWithCoordinateRulers(
        pngBytes: ByteArray,
        displayWidth: Int,
        displayHeight: Int,
    ): ByteArray {
        val source = runCatching { BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) }
            .getOrNull() ?: return pngBytes
        val bitmapWidth = source.width
        val bitmapHeight = source.height
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        source.recycle()
        val canvas = Canvas(mutable)

        val tickLength = (bitmapWidth * 0.012f).coerceIn(4f, 12f)
        val fontSize = (bitmapWidth * 0.018f).coerceIn(7f, 16f)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 60, 60)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 60, 60)
            textSize = fontSize
            isFakeBoldText = true
        }
        val bgPaint = Paint().apply {
            color = Color.argb(120, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val step = 50
        val majorStep = 100
        for (tick in 0..1000 step step) {
            val px = tick.toFloat() / 1000f * bitmapWidth
            val isMajor = tick % majorStep == 0
            val len = if (isMajor) tickLength else tickLength * 0.6f
            // 顶部刻度
            canvas.drawLine(px, 0f, px, len, tickPaint)
            // 底部刻度
            canvas.drawLine(px, bitmapHeight.toFloat(), px, bitmapHeight - len, tickPaint)
            // 标注数字（每 100 一个）
            if (isMajor) {
                val label = tick.toString()
                val tw = textPaint.measureText(label)
                val lx = (px - tw / 2).coerceIn(0f, bitmapWidth - tw)
                canvas.drawRect(lx - 1f, 0f, lx + tw + 1f, fontSize + 2f, bgPaint)
                canvas.drawText(label, lx, fontSize, textPaint)
            }
        }
        for (tick in 0..1000 step step) {
            val py = tick.toFloat() / 1000f * bitmapHeight
            val isMajor = tick % majorStep == 0
            val len = if (isMajor) tickLength else tickLength * 0.6f
            // 左侧刻度
            canvas.drawLine(0f, py, len, py, tickPaint)
            // 右侧刻度
            canvas.drawLine(bitmapWidth.toFloat(), py, bitmapWidth - len, py, tickPaint)
            // 标注数字（每 100 一个）
            if (isMajor) {
                val label = tick.toString()
                val tw = textPaint.measureText(label)
                val ly = (py + fontSize / 2).coerceIn(fontSize, bitmapHeight.toFloat())
                canvas.drawRect(0f, ly - fontSize, tw + 2f, ly + 2f, bgPaint)
                canvas.drawText(label, 1f, ly, textPaint)
            }
        }

        return try {
            java.io.ByteArrayOutputStream().use { output ->
                mutable.compress(Bitmap.CompressFormat.JPEG, 85, output)
                output.toByteArray().takeIf { it.isNotEmpty() } ?: pngBytes
            }
        } catch (_: Throwable) {
            pngBytes
        } finally {
            mutable.recycle()
        }
    }

    /**
     * 返回 Agent Mode 虚拟显示规格。
     * 固定使用 900×1600@360dpi，不跟随设备物理分辨率，
     * 以保证截图图片大小可控、模型坐标估算精度一致。
     */
    private fun currentDeviceDisplaySpec(): DisplaySpec = DisplaySpec(
        width = AgentDisplayWidth,
        height = AgentDisplayHeight,
        densityDpi = AgentDisplayDensityDpi,
    )

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

    /**
     * 检测当前设备是否为 MIUI/HyperOS 系统。
     * MIUI 的 LoadedApk.makeApplicationInner 存在已知空指针 bug，
     * 会导致 Shizuku UserService 进程在 Application 创建阶段崩溃。
     */
    @SuppressLint("PrivateApi")
    private fun isMiuiDevice(): Boolean {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val result = get.invoke(null, "ro.miui.ui.version.name", "") as? String
            result.isNullOrBlank().not()
        }.getOrDefault(false)
    }
}
