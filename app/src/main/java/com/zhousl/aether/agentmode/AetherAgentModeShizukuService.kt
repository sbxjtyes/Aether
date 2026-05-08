package com.zhousl.aether.agentmode

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import androidx.core.graphics.createBitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AetherAgentModeSvc"
private const val AndroidPackage = "android"
private const val AndroidShellPackage = "com.android.shell"
private const val AndroidSystemUid = 1000
private const val AndroidShellUid = 2000
private const val VirtualDisplayFlagPublic = 1 shl 0
private const val VirtualDisplayFlagOwnContentOnly = 1 shl 3
private const val VirtualDisplayFlagSupportsTouch = 1 shl 6
private const val VirtualDisplayFlagDestroyContentOnRemoval = 1 shl 8
private const val VirtualDisplayFlagTrusted = 1 shl 10
private const val VirtualDisplayFlagTouchFeedbackDisabled = 1 shl 13
private const val VirtualDisplayFlagOwnFocus = 1 shl 14
private const val VirtualDisplayFlagStealTopFocusDisabled = 1 shl 16

private const val InjectInputEventModeWaitForFinish = 2
private const val TapDurationMillis = 60L
private const val KeyPressDurationMillis = 30L
private const val ClipboardPasteDelayMillis = 160L
private const val DisplayFocusSettleMillis = 80L
private const val InputRetryDelayMillis = 120L
private const val InputInjectionMaxAttempts = 3
private const val TextPasteMaxAttempts = 3

class AetherAgentModeShizukuService @Keep constructor(
    private val context: Context,
) : IAetherAgentModeService.Stub() {
    private val virtualDisplayContext = context.contextMatchingCurrentUidForDisplayCreation()
    private val displayManager = virtualDisplayContext.getSystemService<DisplayManager>()!!

    init {
        Log.i(
            TAG,
            "Agent Mode service started uid=${Process.myUid()} basePackage=${context.packageName} " +
                "virtualDisplayPackage=${virtualDisplayContext.packageName}",
        )
    }
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val imageReaders = ConcurrentHashMap<Int, ImageReader>()
    private val clipboardManager = virtualDisplayContext.getSystemService<ClipboardManager>()
    private val pixelCopyThread = HandlerThread("aether-agentmode-pixelcopy").apply { start() }
    private val pixelCopyHandler = Handler(pixelCopyThread.looper)

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
    ): Int {
        val display = displayManager.createVirtualDisplay(
            name,
            width,
            height,
            density,
            surface,
            agentVirtualDisplayFlags(),
        )
        val displayId = display.display.displayId
        displays[displayId] = display
        return displayId
    }

    override fun createOwnedDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
    ): Int {
        val reader = ImageReader.newInstance(
            width,
            height,
            android.graphics.PixelFormat.RGBA_8888,
            2,
        )
        val display = displayManager.createVirtualDisplay(
            name,
            width,
            height,
            density,
            reader.surface,
            agentVirtualDisplayFlags(),
        )
        val displayId = display.display.displayId
        displays[displayId] = display
        imageReaders[displayId] = reader
        return displayId
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
        imageReaders.remove(displayId)?.close()
    }

    override fun launchPackage(packageName: String, displayId: Int) {
        ensureManagedDisplay(displayId)
        val baseIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launchable activity for $packageName.")
        val targetDisplay = displayManager.getDisplay(displayId)
            ?: error("Display $displayId is not available.")
        val displayContext = context.createDisplayContext(targetDisplay)
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = displayId

        focusDisplayForInput(displayId)
        val failures = mutableListOf<String>()
        val directIntent = Intent(baseIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        if (runCatching {
                displayContext.startActivity(directIntent, options.toBundle())
            }.onFailure { throwable ->
                failures += "direct display-context launch: ${throwable.launchFailureSummary()}"
                Log.w(
                    TAG,
                    "Direct display-context launch failed for $packageName on display $displayId; " +
                        "trying PendingIntent: ${throwable.launchFailureSummary()}",
                )
            }.isSuccess
        ) {
            settleAfterLaunch(displayId)
            return
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            displayContext,
            baseIntent.filterHashCode(),
            Intent(baseIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            },
            pendingIntentFlags,
        )
        if (runCatching {
                pendingIntent.send(
                    context,
                    0,
                    null,
                    null,
                    null,
                    null,
                    options.toBundle(),
                )
            }.onFailure { throwable ->
                failures += "PendingIntent launch: ${throwable.launchFailureSummary()}"
                Log.w(
                    TAG,
                    "PendingIntent launch failed for $packageName on display $displayId; " +
                        "trying shell am fallback: ${throwable.launchFailureSummary()}",
                )
            }.isSuccess
        ) {
            settleAfterLaunch(displayId)
            return
        }

        if (runCatching { launchPackageWithAm(baseIntent, displayId) }
                .onFailure { throwable -> failures += "am start fallback: ${throwable.launchFailureSummary()}" }
                .isSuccess
        ) {
            settleAfterLaunch(displayId)
            return
        }

        error(
            "Unable to launch $packageName on Agent Mode display $displayId. " +
                failures.joinToString("; "),
        )
    }

    private fun settleAfterLaunch(displayId: Int) {
        SystemClock.sleep(DisplayFocusSettleMillis * 2)
        focusDisplayForInput(displayId)
    }

    private fun launchPackageWithAm(intent: Intent, displayId: Int) {
        val component = intent.component
            ?: error("Launch intent did not expose a concrete activity component.")
        val command = listOf(
            "/system/bin/am",
            "start",
            "--display",
            displayId.toString(),
            "-n",
            component.flattenToShortString(),
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error(output.ifBlank { "am start failed with exit code $exitCode." })
        }
    }

    override fun runInputCommand(command: String) {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error(output.ifBlank { "Input command failed with exit code $exitCode." })
        }
    }

    override fun tap(displayId: Int, x: Int, y: Int) {
        ensureManagedDisplay(displayId)
        val (clampedX, clampedY) = clampedPoint(displayId, x.toFloat(), y.toFloat())
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, clampedX, clampedY)
        SystemClock.sleep(TapDurationMillis)
        injectMotionEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            clampedX,
            clampedY,
        )
    }

    override fun swipe(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ) {
        ensureManagedDisplay(displayId)
        val duration = durationMs.coerceIn(50, 10_000)
        val (startX, startY) = clampedPoint(displayId, x1.toFloat(), y1.toFloat())
        val (endX, endY) = clampedPoint(displayId, x2.toFloat(), y2.toFloat())
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY)

        val steps = (duration / 16).coerceIn(3, 80)
        for (step in 1 until steps) {
            val progress = step.toFloat() / steps.toFloat()
            val x = startX + ((endX - startX) * progress)
            val y = startY + ((endY - startY) * progress)
            SystemClock.sleep((duration / steps).toLong().coerceAtLeast(1L))
            injectMotionEvent(
                displayId,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                x,
                y,
            )
        }

        SystemClock.sleep((duration / steps).toLong().coerceAtLeast(1L))
        injectMotionEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            endX,
            endY,
        )
    }

    override fun key(displayId: Int, keyCode: String) {
        ensureManagedDisplay(displayId)
        shortcutKeyCode(keyCode)?.let { shortcutCode ->
            injectKeyShortcut(displayId, shortcutCode)
            return
        }
        val code = parseKeyCode(keyCode)
        val downTime = SystemClock.uptimeMillis()
        injectKeyEvent(displayId, downTime, downTime, KeyEvent.ACTION_DOWN, code, 0)
        SystemClock.sleep(KeyPressDurationMillis)
        injectKeyEvent(displayId, downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0)
    }

    override fun text(displayId: Int, text: String) {
        ensureManagedDisplay(displayId)
        if (text.isEmpty()) return
        pasteText(displayId, text)
    }

    override fun capturePng(displayId: Int): ByteArray = capturePngBytes(displayId)

    override fun capturePngPipe(displayId: Int): ParcelFileDescriptor {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    output.write(capturePngBytes(displayId))
                    output.flush()
                }
            } catch (throwable: Throwable) {
                runCatching { writeSide.closeWithError(throwable.message ?: throwable.javaClass.simpleName) }
                Log.e(TAG, "capturePngPipe failed for display $displayId: ${throwable.message}", throwable)
            }
        }, "aether-agentmode-capture-pipe").apply {
            isDaemon = true
            start()
        }
        return readSide
    }

    private fun capturePngBytes(displayId: Int): ByteArray {
        val display = displays[displayId]
            ?: error("Display $displayId is not managed by Aether Agent Mode.")
        val width = display.display.mode?.physicalWidth?.takeIf { it > 0 } ?: display.display.width
        val height = display.display.mode?.physicalHeight?.takeIf { it > 0 } ?: display.display.height
        val bitmap = createBitmap(width, height)
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(display.surface, bitmap, { copyResult ->
            result = copyResult
            latch.countDown()
        }, pixelCopyHandler)
        if (!latch.await(2, TimeUnit.SECONDS)) {
            bitmap.recycle()
            error("Timed out while capturing display $displayId.")
        }
        if (result != PixelCopy.SUCCESS) {
            bitmap.recycle()
            error("PixelCopy failed for display $displayId with code $result.")
        }
        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun listDisplaysJson(): String =
        JSONArray().apply {
            displayManager.displays.forEach { display ->
                val size = Point()
                @Suppress("DEPRECATION")
                display.getSize(size)
                put(
                    JSONObject().apply {
                        put("display_id", display.displayId)
                        put("name", display.name.orEmpty())
                        put("width", display.mode?.physicalWidth ?: size.x)
                        put("height", display.mode?.physicalHeight ?: size.y)
                        put("is_aether_display", displays.containsKey(display.displayId))
                    }
                )
            }
        }.toString()

    private fun agentVirtualDisplayFlags(): Int =
        VirtualDisplayFlagPublic or
            VirtualDisplayFlagOwnContentOnly or
            VirtualDisplayFlagSupportsTouch or
            VirtualDisplayFlagDestroyContentOnRemoval or
            VirtualDisplayFlagTrusted or
            VirtualDisplayFlagTouchFeedbackDisabled or
            VirtualDisplayFlagOwnFocus or
            VirtualDisplayFlagStealTopFocusDisabled

    private fun ensureManagedDisplay(displayId: Int) {
        if (!displays.containsKey(displayId)) {
            error("Display $displayId is not managed by Aether Agent Mode.")
        }
    }

    private fun clampedPoint(displayId: Int, x: Float, y: Float): Pair<Float, Float> {
        val display = displays[displayId]?.display
            ?: displayManager.getDisplay(displayId)
            ?: error("Display $displayId is not available.")
        val width = (display.mode?.physicalWidth ?: display.width).coerceAtLeast(1)
        val height = (display.mode?.physicalHeight ?: display.height).coerceAtLeast(1)
        return x.coerceIn(0f, (width - 1).toFloat()) to y.coerceIn(0f, (height - 1).toFloat())
    }

    private fun injectMotionEvent(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectInputEventOnDisplay(displayId, event)
    }

    private fun injectKeyEvent(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        keyCode: Int,
        metaState: Int,
        scanCode: Int = 0,
        flags: Int = 0,
    ) {
        val event = KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            scanCode,
            flags,
            InputDevice.SOURCE_KEYBOARD,
        )
        injectInputEventOnDisplay(displayId, event)
    }

    private fun injectInputEventOnDisplay(displayId: Int, event: InputEvent) {
        try {
            setInputEventDisplayId(event, displayId)
            val inputManager = inputManagerInstance()
            val method = inputManager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            var lastFailure: Throwable? = null
            repeat(InputInjectionMaxAttempts) { attempt ->
                if (attempt > 0) {
                    SystemClock.sleep(InputRetryDelayMillis)
                }
                focusDisplayForInput(displayId)
                if (attempt == 0) {
                    SystemClock.sleep(DisplayFocusSettleMillis)
                }
                val injected = runCatching {
                    method.invoke(inputManager, event, InjectInputEventModeWaitForFinish) as Boolean
                }.onFailure { throwable ->
                    lastFailure = throwable
                    Log.w(
                        TAG,
                        "Input injection attempt ${attempt + 1}/$InputInjectionMaxAttempts failed " +
                            "for display $displayId: ${throwable.message ?: throwable.javaClass.simpleName}",
                    )
                }.getOrDefault(false)
                if (injected) return
            }
            error(
                "Input event was rejected by Android input manager for display $displayId after " +
                    "$InputInjectionMaxAttempts attempts." +
                    (lastFailure?.message?.let { " Last failure: $it" } ?: ""),
            )
        } finally {
            if (event is MotionEvent) {
                event.recycle()
            }
        }
    }

    private fun focusDisplayForInput(displayId: Int) {
        runCatching {
            val windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal")
            val getWindowManagerService = windowManagerGlobal.getDeclaredMethod("getWindowManagerService")
            getWindowManagerService.isAccessible = true
            val windowManagerService = getWindowManagerService.invoke(null)
                ?: error("Window manager service was not available.")
            val setFocusedDisplay = windowManagerService.javaClass.methods.firstOrNull { method ->
                method.name == "setFocusedDisplay" && method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType),
                )
            } ?: error("setFocusedDisplay(int) was not available.")
            setFocusedDisplay.invoke(windowManagerService, displayId)
        }.onFailure { throwable ->
            Log.w(
                TAG,
                "Unable to request input focus for display $displayId: " +
                    (throwable.message ?: throwable.javaClass.simpleName),
            )
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun setInputEventDisplayId(event: InputEvent, displayId: Int) {
        val method = InputEvent::class.java.getDeclaredMethod(
            "setDisplayId",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(event, displayId)
    }

    private fun inputManagerInstance(): Any {
        val inputManagerClass = Class.forName("android.hardware.input.InputManager")
        val getInstance = inputManagerClass.getDeclaredMethod("getInstance")
        getInstance.isAccessible = true
        return getInstance.invoke(null)
            ?: error("Android input manager was not available.")
    }

    private fun injectKeyShortcut(displayId: Int, keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        injectKeyEvent(displayId, downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, KeyEvent.META_CTRL_ON)
        SystemClock.sleep(KeyPressDurationMillis)
        injectKeyEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            keyCode,
            KeyEvent.META_CTRL_ON,
        )
    }

    private fun pasteText(displayId: Int, text: String) {
        val clipboard = clipboardManager
            ?: error("Android clipboard manager was not available for text paste.")
        var lastFailure: Throwable? = null
        repeat(TextPasteMaxAttempts) { attempt ->
            if (attempt > 0) {
                SystemClock.sleep(InputRetryDelayMillis)
            }
            if (runCatching {
                    focusDisplayForInput(displayId)
                    SystemClock.sleep(DisplayFocusSettleMillis)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Aether Agent Mode", text))
                    SystemClock.sleep(ClipboardPasteDelayMillis)
                    injectKeyShortcut(displayId, KeyEvent.KEYCODE_V)
                }.onFailure { throwable ->
                    lastFailure = throwable
                    Log.w(
                        TAG,
                        "Text paste attempt ${attempt + 1}/$TextPasteMaxAttempts failed " +
                            "for display $displayId: ${throwable.message ?: throwable.javaClass.simpleName}",
                    )
                }.isSuccess
            ) {
                return
            }
        }
        error(
            "Text paste failed for display $displayId after $TextPasteMaxAttempts attempts." +
                (lastFailure?.message?.let { " Last failure: $it" } ?: ""),
        )
    }

    private fun shortcutKeyCode(rawValue: String): Int? = when (rawValue.normalizedKeyAlias()) {
        "SELECT_ALL", "CTRL_A", "CONTROL_A" -> KeyEvent.KEYCODE_A
        "COPY", "CTRL_C", "CONTROL_C" -> KeyEvent.KEYCODE_C
        "CUT", "CTRL_X", "CONTROL_X" -> KeyEvent.KEYCODE_X
        "PASTE", "CTRL_V", "CONTROL_V" -> KeyEvent.KEYCODE_V
        else -> null
    }

    private fun parseKeyCode(rawValue: String): Int {
        val normalized = rawValue.trim()
        normalized.toIntOrNull()?.let { return it }
        when (normalized.normalizedKeyAlias()) {
            "BACKSPACE" -> return KeyEvent.KEYCODE_DEL
            "DELETE" -> return KeyEvent.KEYCODE_FORWARD_DEL
            "ESC" -> return KeyEvent.KEYCODE_ESCAPE
            "SPACE" -> return KeyEvent.KEYCODE_SPACE
        }
        val direct = KeyEvent.keyCodeFromString(normalized)
        if (direct != KeyEvent.KEYCODE_UNKNOWN) return direct
        val prefixed = KeyEvent.keyCodeFromString("KEYCODE_${normalized.uppercase()}")
        if (prefixed != KeyEvent.KEYCODE_UNKNOWN) return prefixed
        error("Unsupported key code '$rawValue'.")
    }
}

private fun Throwable.launchFailureSummary(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

private fun String.normalizedKeyAlias(): String = trim()
    .uppercase()
    .replace('-', '_')
    .replace(' ', '_')
    .removePrefix("KEYCODE_")

private fun Context.contextMatchingCurrentUidForDisplayCreation(): Context {
    val currentUid = Process.myUid()
    val packageName = when (currentUid) {
        AndroidSystemUid -> AndroidPackage
        AndroidShellUid -> AndroidShellPackage
        else -> return this
    }
    return runCatching {
        createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
    }.onSuccess {
        Log.i(
            TAG,
            "Using $packageName context for virtual display creation because service runs as uid=$currentUid.",
        )
    }.onFailure { throwable ->
        Log.w(
            TAG,
            "Unable to create $packageName context for uid=$currentUid; virtual display creation may fail: " +
                (throwable.message ?: throwable.javaClass.simpleName),
        )
    }.getOrDefault(this)
}
