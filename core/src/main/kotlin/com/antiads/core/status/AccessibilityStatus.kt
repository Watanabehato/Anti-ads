package com.antiads.core.status

/** 无障碍运行阶段（core 定义、:accessibility 发布、:app 读取）。 */
enum class AccessibilityPhase { DISCONNECTED, IDLE, WATCHING, PAUSED, ERROR }

/** 最近一次规则/包/时间/动作返回值；不记录屏幕文本、输入值或节点完整树。 */
data class SkipActionRecord(
    val packageName: String,
    val ruleId: String,
    val occurredAtElapsedMs: Long,
    val actionAccepted: Boolean
)

/**
 * 纯配置不能令 connected=true：只有系统实际绑定服务后才为 true。
 * 连接但总开关关闭为 PAUSED；已连接无目标为 IDLE；正在合格目标观察为 WATCHING。
 */
data class AccessibilityRuntimeState(
    val connected: Boolean = false,
    val phase: AccessibilityPhase = AccessibilityPhase.DISCONNECTED,
    val activePackage: String? = null,
    val lastAction: SkipActionRecord? = null,
    val lastErrorCode: String? = null
)
