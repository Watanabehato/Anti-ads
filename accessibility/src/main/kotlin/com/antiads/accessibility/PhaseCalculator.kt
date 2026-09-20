package com.antiads.accessibility

import com.antiads.core.config.PolicyResolver
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.status.AccessibilityPhase

/** 一次阶段计算的结论（errorCode 只在 ERROR/中断等情况下出现）。 */
internal data class PhaseResult(
    val phase: AccessibilityPhase,
    val activePackage: String?,
    val errorCode: String?
)

/**
 * 状态阶段计算（纯函数）。
 *
 * 规则来自 contracts 第 5 节：
 * - 只有系统实际绑定服务后 connected 才为 true（本对象只根据传入的 connected 计算）；
 * - 未注入依赖 => ERROR + DEPENDENCY_MISSING（禁止空依赖返回假成功）；
 * - 连接但总开关/全局无障碍关闭 => PAUSED；
 * - 已连接且有合格目标 => WATCHING（activePackage 为该包）；已连接无目标 => IDLE。
 */
internal object PhaseCalculator {

    fun compute(
        connected: Boolean,
        dependencyInstalled: Boolean,
        config: ProtectionConfig?,
        foregroundPackage: String?
    ): PhaseResult {
        if (!connected) return PhaseResult(AccessibilityPhase.DISCONNECTED, null, null)
        if (!dependencyInstalled) {
            return PhaseResult(AccessibilityPhase.ERROR, null, GuardCode.DEPENDENCY_MISSING)
        }
        if (config == null) {
            return PhaseResult(AccessibilityPhase.ERROR, null, GuardCode.CONFIG_UNAVAILABLE)
        }
        if (!config.masterEnabled || !config.accessibilityEnabled) {
            return PhaseResult(AccessibilityPhase.PAUSED, null, null)
        }
        if (foregroundPackage != null && PolicyResolver.accessibilityEnabled(config, foregroundPackage)) {
            return PhaseResult(AccessibilityPhase.WATCHING, foregroundPackage, null)
        }
        return PhaseResult(AccessibilityPhase.IDLE, null, null)
    }
}
