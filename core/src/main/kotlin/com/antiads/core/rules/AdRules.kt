package com.antiads.core.rules

import com.antiads.core.config.ProtectionConfig

/** 屏幕像素矩形（左上右下）。 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 一次遍历得到的节点快照。
 *
 * nodeId 仅是当前快照内从 0 开始的唯一索引，不是持久 view ID，不允许跨快照复用。
 * 文本快照由无障碍侧按字段上限截断后传入；core 不读取 Android 节点。
 */
data class UiNode(
    val nodeId: Int,
    val text: String,
    val contentDescription: String,
    val viewId: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val bounds: Bounds
)

/** 窗口级输入快照；锁屏、敏感包、遍历完整性由无障碍侧实时判定后传入。 */
data class AdWindowSnapshot(
    val packageName: String,
    val windowId: Int,
    val capturedAtElapsedMs: Long,
    val foregroundSinceElapsedMs: Long,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val isApplicationWindow: Boolean,
    val keyguardLocked: Boolean,
    val sensitivePackage: Boolean,
    val traversalComplete: Boolean,
    val nodes: List<UiNode>
)

/** 规则候选：仅表示“可以尝试一次点击”，执行前后仍需无障碍侧全部复核。 */
data class SkipCandidate(val nodeId: Int, val ruleId: String, val reason: String)

interface AdRuleEngine {
    fun evaluate(config: ProtectionConfig, window: AdWindowSnapshot): List<SkipCandidate>
}
