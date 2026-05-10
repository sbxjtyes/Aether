package com.zhousl.aether.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import com.zhousl.aether.termux.TermuxContract

private const val ClipboardExtraIsSensitive = "android.content.extra.IS_SENSITIVE"

/**
 * 复制 Termux 外部应用配置命令，并将剪贴板内容标记为敏感以抑制系统预览浮窗。
 */
internal fun copyTermuxSetupCommandToClipboard(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(
        "Aether Termux setup command",
        TermuxContract.ExternalAppsSetupCommand,
    ).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipboardExtraIsSensitive, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
