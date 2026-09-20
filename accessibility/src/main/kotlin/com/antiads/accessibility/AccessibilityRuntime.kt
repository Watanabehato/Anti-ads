package com.antiads.accessibility

import com.antiads.core.config.Subscription
import com.antiads.core.status.AccessibilityPhase
import com.antiads.core.status.AccessibilityRuntimeState

/**
 * 无障碍运行状态发布入口 —— 骨架占位，由 t9 实现。
 *
 * 骨架语义：永远显式未连接（DISCONNECTED + lastErrorCode=NOT_IMPLEMENTED），
 * 绝不返回 connected=true；纯配置不能令 connected=true。
 */
object AccessibilityRuntime {

    const val ERROR_NOT_IMPLEMENTED: String = "NOT_IMPLEMENTED"

    fun state(): AccessibilityRuntimeState = AccessibilityRuntimeState(
        connected = false,
        phase = AccessibilityPhase.DISCONNECTED,
        activePackage = null,
        lastAction = null,
        lastErrorCode = ERROR_NOT_IMPLEMENTED
    )

    /** 立即回调一次当前状态；骨架不产生后续通知，close 幂等。 */
    fun observe(listener: (AccessibilityRuntimeState) -> Unit): Subscription {
        listener(state())
        return Subscription { }
    }
}
