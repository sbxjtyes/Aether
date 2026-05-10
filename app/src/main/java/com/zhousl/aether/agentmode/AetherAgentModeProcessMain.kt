package com.zhousl.aether.agentmode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.annotation.Keep

object AetherAgentModeProcessContract {
    const val ActionServiceStarted = "com.zhousl.aether.agentmode.ACTION_SERVICE_STARTED"
    const val ExtraToken = "com.zhousl.aether.agentmode.extra.TOKEN"
    const val ExtraBinder = "com.zhousl.aether.agentmode.extra.BINDER"
}

@Keep
object AetherAgentModeProcessMain {
    /**
     * app_process 入口函数，创建 Agent Mode Binder 服务并通过一次性广播返回给主进程。
     */
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
            throw throwable
        }
    }

    /**
     * 解析启动参数并保持当前 Binder 服务进程存活。
     */
    private fun run(args: Array<String>) {
        val packageName = requiredArgument(args, "--package=")
        val token = requiredArgument(args, "--token=")
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
        val context = systemContext()
        val binder = AetherAgentModeShizukuService(context).asBinder()
        sendStartedBroadcast(
            context = context,
            packageName = packageName,
            token = token,
            binder = binder,
        )
        Looper.loop()
    }

    /**
     * 从 app_process 参数中读取指定前缀的必填参数。
     */
    private fun requiredArgument(args: Array<String>, prefix: String): String = args
        .firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf(String::isNotBlank)
        ?: error("Missing required Agent Mode process argument: $prefix")

    /**
     * 通过 ActivityThread.systemMain 获取不触发应用 Application 创建的系统 Context。
     */
    private fun systemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain = activityThreadClass.getDeclaredMethod("systemMain")
        systemMain.isAccessible = true
        val activityThread = systemMain.invoke(null)
            ?: error("ActivityThread.systemMain returned null.")
        val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext")
        getSystemContext.isAccessible = true
        return getSystemContext.invoke(activityThread) as? Context
            ?: error("System context was not available for Agent Mode process.")
    }

    /**
     * 将服务 Binder 发送回 Aether 主进程动态注册的接收器。
     */
    private fun sendStartedBroadcast(
        context: Context,
        packageName: String,
        token: String,
        binder: IBinder,
    ) {
        val extras = Bundle().apply {
            putString(AetherAgentModeProcessContract.ExtraToken, token)
            putBinder(AetherAgentModeProcessContract.ExtraBinder, binder)
        }
        val intent = Intent(AetherAgentModeProcessContract.ActionServiceStarted)
            .setPackage(packageName)
            .putExtras(extras)
        context.sendBroadcast(intent)
    }
}
