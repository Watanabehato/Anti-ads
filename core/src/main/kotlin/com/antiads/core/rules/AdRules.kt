package com.antiads.core.rules

import com.antiads.core.config.ProtectionConfig

/** 屏幕像素矩形（左上右下）。 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 一次遍历得到的节点快照。
 *
 * nodeId 仅是当前快照内从 0 开始的唯一索引，不是持久 view ID，不允许跨快照复用。
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

/** 窗口级输入快照；敏感字段（锁屏、敏感包）由无障碍侧实时判定后传入。 */
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

/** 规则候选；仅表示“可以尝试一次点击”，执行前后仍需无障碍侧全部复核。 */
data class SkipCandidate(val nodeId: Int, val ruleId: String, val reason: String)

interface AdRuleEngine {
    fun evaluate(config: ProtectionConfig, window: AdWindowSnapshot): List<SkipCandidate>
}

/**
 * 骨架占位实现 —— 规则实现与几何/时间边界单测由 t7（core 实现任务）交付。
 *
 * 当前语义为“永远不产生候选”：不点击、不宣称保护。这里不构造假成功，也不返回
 * 任何未经验证的候选；t7 落地前任何界面/诊断不得据此显示“已保护”。
 */
class ConservativeAdRuleEngine : AdRuleEngine {
    override fun evaluate(config: ProtectionConfig, window: AdWindowSnapshot): List<SkipCandidate> = emptyList()
}
