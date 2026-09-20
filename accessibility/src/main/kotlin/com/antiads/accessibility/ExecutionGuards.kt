package com.antiads.accessibility

import com.antiads.core.config.ConfigConstants
import java.util.Locale

/** 节点可核验字段（无 Android 类型，便于纯 JVM 测试）。 */
internal data class NodeFields(
    val text: String,
    val description: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val editable: Boolean,
    val password: Boolean
) {
    /** 返回 trim + 小写（Locale.ROOT）后的副本；与 core 规则的规范化方式一致。 */
    fun normalized(): NodeFields = copy(
        text = NodeText.normalize(text),
        description = NodeText.normalize(description)
    )

    /** 是否自身可点击（不向父容器递归）。 */
    val isActionable: Boolean get() = clickable && enabled && visible
}

/** 文本规范化与快照字段上限。 */
internal object NodeText {
    fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    fun limit(value: String, max: Int = SkipLimits.MAX_FIELD_CHARS): String =
        if (value.length <= max) value else value.substring(0, max)
}

/**
 * 购买/登录/权限授权等敏感动作词。
 *
 * 这是二次防线：候选文案已由 core 规则限制为“跳过”类文案，且本模块还要求 refresh 后
 * 文案与快照完全一致。命中该名单即拒绝，宁可漏点也不误触。
 */
internal object SensitiveActionText {
    private val DENIED_TERMS: List<String> = listOf(
        "购买", "支付", "付款", "充值", "开通", "订阅", "续费", "充值",
        "登录", "注册", "授权", "允许", "同意", "确认", "领取", "继续", "开始",
        "安装", "下载",
        "buy", "purchase", "subscribe", "login", "sign in", "sign up",
        "allow", "grant", "install", "download", "accept", "confirm", "continue"
    )

    fun denies(normalizedText: String): Boolean =
        normalizedText.isNotEmpty() && DENIED_TERMS.any { normalizedText.contains(it) }
}

/** 一次执行前复核的结论。 */
internal data class GuardDecision(val allowed: Boolean, val reason: String) {
    companion object {
        val OK: GuardDecision = GuardDecision(true, GuardCode.OK)

        fun deny(reason: String): GuardDecision = GuardDecision(false, reason)
    }
}

/** 目标选择与配置复核输入（执行前重新读取，不使用快照时刻的缓存）。 */
internal data class PolicyGuardInput(
    val connected: Boolean,
    val dependencyInstalled: Boolean,
    val configRevisionAtSnapshot: Long,
    val configRevisionNow: Long,
    val packageName: String,
    val packageNameValid: Boolean,
    val rejectedPackage: Boolean,
    val accessibilityEnabledForPackage: Boolean,
    val packageRuleIdsContainBuiltin: Boolean
)

/** 窗口复核输入：锁屏、包/窗口身份、遍历完整性、窗口期与快照年龄。 */
internal data class WindowGuardInput(
    val packageName: String,
    val windowId: Int,
    val rootPackageName: String?,
    val rootWindowId: Int,
    val isApplicationWindow: Boolean,
    val keyguardLocked: Boolean,
    val sensitivePackage: Boolean,
    val traversalComplete: Boolean,
    val hasEditableOrPasswordNode: Boolean,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val capturedAtElapsedMs: Long,
    val foregroundSinceElapsedMs: Long
)

/** 候选复核输入：规则来源、快照节点字段与 refresh 后的真实字段。 */
internal data class CandidateGuardInput(
    val candidateRuleId: String,
    val candidateReason: String,
    val snapshotNode: NodeFields,
    val refreshed: NodeFields?
)

/**
 * 无状态的执行前复核（纯函数）。所有判定只依赖入参，便于逐条边界测试。
 * 这里是“执行约束”的第二道闸门：core 规则负责“是不是广告跳过”，本对象负责
 * “此刻是否仍然可以点、是否只是候选自身、是否属于敏感动作”。
 */
internal object ExecutionGuards {

    fun checkPolicy(input: PolicyGuardInput): GuardDecision {
        if (!input.connected) return GuardDecision.deny(GuardCode.NOT_CONNECTED)
        if (!input.dependencyInstalled) return GuardDecision.deny(GuardCode.DEPENDENCY_MISSING)
        if (input.rejectedPackage) return GuardDecision.deny(GuardCode.REJECTED_PACKAGE)
        if (!input.packageNameValid) return GuardDecision.deny(GuardCode.INVALID_PACKAGE)
        if (!input.accessibilityEnabledForPackage) return GuardDecision.deny(GuardCode.POLICY_DISABLED)
        if (!input.packageRuleIdsContainBuiltin) return GuardDecision.deny(GuardCode.RULE_NOT_ENABLED)
        if (input.configRevisionAtSnapshot != input.configRevisionNow) {
            return GuardDecision.deny(GuardCode.CONFIG_REVISION_CHANGED)
        }
        return GuardDecision.OK
    }

    /** nowElapsedMs 由调用方传入（同一次判定的统一时刻），不放在输入对象里以免出现两个“现在”。 */
    fun checkWindow(input: WindowGuardInput, nowElapsedMs: Long): GuardDecision {
        if (input.packageName.isEmpty()) return GuardDecision.deny(GuardCode.WINDOW_IDENTITY_UNKNOWN)
        if (input.rootPackageName == null ||
            input.rootPackageName != input.packageName ||
            input.rootWindowId != input.windowId
        ) {
            return GuardDecision.deny(GuardCode.WINDOW_CHANGED)
        }
        if (!input.isApplicationWindow) return GuardDecision.deny(GuardCode.NOT_APPLICATION_WINDOW)
        if (input.keyguardLocked) return GuardDecision.deny(GuardCode.KEYGUARD_LOCKED)
        if (input.sensitivePackage) return GuardDecision.deny(GuardCode.SENSITIVE_PACKAGE)
        if (!input.traversalComplete) return GuardDecision.deny(GuardCode.TRAVERSAL_INCOMPLETE)
        if (input.screenWidthPx <= 0 || input.screenHeightPx <= 0) {
            return GuardDecision.deny(GuardCode.WINDOW_SIZE_INVALID)
        }
        if (input.hasEditableOrPasswordNode) return GuardDecision.deny(GuardCode.EDITABLE_NODE_IN_WINDOW)

        val windowAgeMs = input.capturedAtElapsedMs - input.foregroundSinceElapsedMs
        if (windowAgeMs < 0 || windowAgeMs > SkipLimits.WINDOW_MAX_AGE_MS) {
            return GuardDecision.deny(GuardCode.WINDOW_AGE_OUT_OF_RANGE)
        }
        val snapshotAgeMs = nowElapsedMs - input.capturedAtElapsedMs
        if (snapshotAgeMs < 0 || snapshotAgeMs > SkipLimits.SNAPSHOT_MAX_AGE_MS) {
            return GuardDecision.deny(GuardCode.SNAPSHOT_STALE)
        }
        return GuardDecision.OK
    }

    fun checkCandidate(input: CandidateGuardInput): GuardDecision {
        if (input.candidateRuleId != ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1) {
            return GuardDecision.deny(GuardCode.RULE_NOT_BUILTIN)
        }
        if (input.candidateReason != REASON_EXPLICIT_AD_SKIP) {
            return GuardDecision.deny(GuardCode.REASON_NOT_EXPLICIT_AD_SKIP)
        }
        val snapshot = input.snapshotNode.normalized()
        if (snapshot.editable || snapshot.password) return GuardDecision.deny(GuardCode.NODE_EDITABLE)
        if (!snapshot.isActionable) return GuardDecision.deny(GuardCode.NODE_NOT_ACTIONABLE)

        val refreshed = input.refreshed?.normalized() ?: return GuardDecision.deny(GuardCode.REFRESH_FAILED)
        if (refreshed.editable || refreshed.password) return GuardDecision.deny(GuardCode.REFRESH_EDITABLE)
        if (!refreshed.isActionable) return GuardDecision.deny(GuardCode.REFRESH_NOT_ACTIONABLE)
        if (refreshed.text != snapshot.text || refreshed.description != snapshot.description) {
            return GuardDecision.deny(GuardCode.NODE_TEXT_CHANGED)
        }
        if (SensitiveActionText.denies(refreshed.text) || SensitiveActionText.denies(refreshed.description)) {
            return GuardDecision.deny(GuardCode.SENSITIVE_ACTION)
        }
        return GuardDecision.OK
    }
}
