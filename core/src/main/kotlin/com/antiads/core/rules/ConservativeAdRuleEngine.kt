package com.antiads.core.rules

import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.ConfigValidator
import com.antiads.core.config.PackageConfig
import com.antiads.core.config.PolicyResolver
import com.antiads.core.config.ProtectionConfig

/**
 * 内置保守规则 v1（必要条件见 docs/contracts.md 第 5 节，本类是其唯一实现）。
 *
 * 行为约定：
 * - 只输出**候选**，绝不执行动作，也不读取 Android 节点、不读取系统时钟；
 * - 任何输入不合法、信息不足或存在歧义的情况一律返回空列表（即不点击）；
 * - “看到跳过文本”不构成点击授权：必须同时存在**独立且可见**的广告上下文节点；
 * - 关闭总开关/全局无障碍/每包无障碍、包被排除或包名非法时不产生任何候选，
 *   目标应用原始行为保持不变（不伪造“已保护/已激活”）。
 */
class ConservativeAdRuleEngine : AdRuleEngine {

    override fun evaluate(config: ProtectionConfig, window: AdWindowSnapshot): List<SkipCandidate> {
        val packageConfig = resolvePackageConfig(config, window) ?: return emptyList()
        if (!isEligibleWindow(window)) return emptyList()
        if (packageConfig.ruleIds.isEmpty()) return emptyList()
        // 任一可编辑/密码节点出现即整窗放弃，不因“未选中该节点”而放宽
        if (window.nodes.any { it.editable || it.password }) return emptyList()

        val contextNodeIds = window.nodes
            .filter { it.visible && isAdContextNode(it) }
            .map { it.nodeId }
        if (contextNodeIds.isEmpty()) return emptyList()

        val candidate = window.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.clickable }
            .filter { isSkipLabel(it) }
            .filter { node -> contextNodeIds.any { it != node.nodeId } }
            .filter { isInTopRightRegion(it.bounds, window.screenWidthPx, window.screenHeightPx) }
            .sortedWith(compareBy({ it.bounds.top }, { -it.bounds.right }, { it.nodeId }))
            .firstOrNull()
            ?: return emptyList()

        return listOf(
            SkipCandidate(
                nodeId = candidate.nodeId,
                ruleId = RULE_ID,
                reason = REASON_EXPLICIT_AD_SKIP
            )
        )
    }

    /** 总开关、全局/每包无障碍开关、包名合法性与内置规则选择。 */
    private fun resolvePackageConfig(config: ProtectionConfig, window: AdWindowSnapshot): PackageConfig? {
        if (config.schemaVersion != ConfigConstants.SCHEMA_VERSION_V1) return null
        val packageName = window.packageName
        if (!ConfigValidator.isValidPackageName(packageName)) return null
        if (packageName in ConfigConstants.REJECTED_PACKAGE_KEYS) return null
        if (!PolicyResolver.accessibilityEnabled(config, packageName)) return null
        val packageConfig = config.packages[packageName] ?: return null
        if (ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1 !in packageConfig.ruleIds) return null
        return packageConfig
    }

    /** 窗口级必要条件：非应用窗口、锁屏、敏感包、遍历不完整或异常尺寸一律放弃。 */
    private fun isEligibleWindow(window: AdWindowSnapshot): Boolean {
        if (!window.isApplicationWindow) return false
        if (window.keyguardLocked) return false
        if (window.sensitivePackage) return false
        if (!window.traversalComplete) return false
        if (window.screenWidthPx <= 0 || window.screenHeightPx <= 0) return false
        if (window.screenWidthPx > MAX_SCREEN_DIMENSION_PX) return false
        if (window.screenHeightPx > MAX_SCREEN_DIMENSION_PX) return false
        val foregroundAgeMs = window.capturedAtElapsedMs - window.foregroundSinceElapsedMs
        return foregroundAgeMs >= 0L && foregroundAgeMs <= MAX_FOREGROUND_AGE_MS
    }

    /** 广告上下文：文案或 description 经 trim/小写规范化后严格等于固定标签。 */
    private fun isAdContextNode(node: UiNode): Boolean {
        val text = node.text.trim().lowercase()
        val description = node.contentDescription.trim().lowercase()
        return text in AD_CONTEXT_LABELS || description in AD_CONTEXT_LABELS
    }

    /** 候选文案：text 或 description 任一完整匹配跳过正则，先 trim 且长度受限。 */
    private fun isSkipLabel(node: UiNode): Boolean {
        return matchesSkipLabel(node.text) || matchesSkipLabel(node.contentDescription)
    }

    private fun matchesSkipLabel(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_LABEL_LENGTH) return false
        return SKIP_LABEL_PATTERNS.any { it.matches(value) }
    }

    /**
     * 位置约束：完全在屏幕内、正面积、面积不超过屏幕 12%、中心 X 不小于屏宽 65%、
     * 中心 Y 不大于屏高 25%。全部使用 Long 计算；异常尺寸已在上游被拒。
     */
    private fun isInTopRightRegion(bounds: Bounds, screenWidthPx: Int, screenHeightPx: Int): Boolean {
        val left = bounds.left.toLong()
        val top = bounds.top.toLong()
        val right = bounds.right.toLong()
        val bottom = bounds.bottom.toLong()

        if (right <= left || bottom <= top) return false
        if (left < 0L || top < 0L) return false
        if (right > screenWidthPx.toLong() || bottom > screenHeightPx.toLong()) return false

        val area = (right - left) * (bottom - top)
        val screenArea = screenWidthPx.toLong() * screenHeightPx.toLong()
        if (area * 100L > screenArea * 12L) return false

        // left+right 是两倍中心 X：10*(left+right) >= 13*W ⟺ cx >= 0.65*W
        if (10L * (left + right) < 13L * screenWidthPx.toLong()) return false
        // top+bottom 是两倍中心 Y：2*(2*cy) <= H ⟺ cy <= 0.25*H
        if (2L * (top + bottom) > screenHeightPx.toLong()) return false
        return true
    }

    companion object {
        /** 内置规则 ID（取自 ConfigConstants，避免各模块复制字面量）。 */
        const val RULE_ID: String = ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1

        /** 候选原因码：仅用于可解释状态与测试。 */
        const val REASON_EXPLICIT_AD_SKIP: String = "EXPLICIT_AD_SKIP"

        /** 只处理窗口进入后的短窗口；持续 content changed 不能延长它。 */
        const val MAX_FOREGROUND_AGE_MS: Long = 10_000L

        /** 候选文案长度上限（先 trim）。 */
        const val MAX_LABEL_LENGTH: Int = 40

        /** 屏幕尺寸合理性上限，避免异常输入导致几何计算溢出。 */
        const val MAX_SCREEN_DIMENSION_PX: Int = 100_000

        /** 严格匹配的广告上下文标签（先 trim + 小写规范化；中文不受影响）。 */
        val AD_CONTEXT_LABELS: Set<String> = setOf("广告", "ad", "advertisement")

        /** 跳过文案正则：中文两种语序 + 英文（忽略大小写）。 */
        val SKIP_LABEL_PATTERNS: List<Regex> = listOf(
            Regex("""^跳过(?:\s*[0-9]{1,2}\s*(?:秒|s)?)?$"""),
            Regex("""^[0-9]{1,2}\s*(?:秒|s)?\s*跳过$"""),
            Regex("""^skip(?:\s+ads?)?(?:\s+[0-9]{1,2}\s*s)?$""", RegexOption.IGNORE_CASE)
        )
    }
}
