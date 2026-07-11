package com.zhousl.aether.ui

import com.zhousl.aether.data.AgentLoopState
import com.zhousl.aether.data.AgentLoopStopReason
import com.zhousl.aether.data.AppLanguage

internal data class AgentLoopStatusText(
    val title: String,
    val detail: String = "",
)

internal fun agentLoopStatusText(
    loopState: AgentLoopState,
    language: AppLanguage,
): AgentLoopStatusText? {
    if (loopState.isAutonomousContinuation && loopState.continuationIndex > 0) {
        val title = if (language == AppLanguage.SimplifiedChinese) {
            "自主继续 ${loopState.continuationIndex}/${loopState.maxContinuationTurns}"
        } else {
            "Continuing autonomously ${loopState.continuationIndex}/${loopState.maxContinuationTurns}"
        }
        return AgentLoopStatusText(
            title = title,
            detail = loopState.nextStep.ifBlank { loopState.reason },
        )
    }
    return when (loopState.stopReason) {
        AgentLoopStopReason.LimitReached -> AgentLoopStatusText(
            title = if (language == AppLanguage.SimplifiedChinese) {
                "已达到自主续跑上限"
            } else {
                "Autonomous limit reached"
            },
            detail = loopState.reason.ifBlank { loopState.nextStep },
        )
        AgentLoopStopReason.WaitingForUser -> AgentLoopStatusText(
            title = if (language == AppLanguage.SimplifiedChinese) "等待用户" else "Waiting for you",
            detail = loopState.reason,
        )
        AgentLoopStopReason.Blocked -> AgentLoopStatusText(
            title = if (language == AppLanguage.SimplifiedChinese) "已阻塞" else "Blocked",
            detail = loopState.reason,
        )
        AgentLoopStopReason.PlanMode -> AgentLoopStatusText(
            title = if (language == AppLanguage.SimplifiedChinese) "计划模式已停止续跑" else "Plan mode stopped continuation",
            detail = loopState.reason.ifBlank { loopState.nextStep },
        )
        AgentLoopStopReason.Disabled -> AgentLoopStatusText(
            title = if (language == AppLanguage.SimplifiedChinese) "自主续跑已关闭" else "Autonomous continuation off",
            detail = loopState.reason.ifBlank { loopState.nextStep },
        )
        else -> null
    }
}
