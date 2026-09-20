package com.antiads.accessibility

import com.antiads.core.config.ConfigConstants

/** 纯 JVM 单测共用夹具；数值只用于说明执行约束，不代表设备实测。 */
internal object Fixtures {
    const val PKG: String = "com.example.target"
    const val OTHER_PKG: String = "com.example.other"
    const val WIN: Int = 11
    const val OTHER_WIN: Int = 12

    /** 单调时钟基准（elapsedRealtime 数值）。 */
    const val T0: Long = 1_000_000L

    const val STATE_CHANGED: Int = 0x20
    const val CONTENT_CHANGED: Int = 0x800
    const val UNRELATED_EVENT: Int = 0x1

    fun node(
        text: String = "跳过 5 秒",
        description: String = "",
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        editable: Boolean = false,
        password: Boolean = false
    ): NodeFields = NodeFields(text, description, clickable, enabled, visible, editable, password)

    fun policy(
        connected: Boolean = true,
        dependencyInstalled: Boolean = true,
        revisionAtSnapshot: Long = 7L,
        revisionNow: Long = 7L,
        packageName: String = PKG,
        valid: Boolean = true,
        rejected: Boolean = false,
        enabled: Boolean = true,
        ruleIdsContainBuiltin: Boolean = true
    ): PolicyGuardInput = PolicyGuardInput(
        connected = connected,
        dependencyInstalled = dependencyInstalled,
        configRevisionAtSnapshot = revisionAtSnapshot,
        configRevisionNow = revisionNow,
        packageName = packageName,
        packageNameValid = valid,
        rejectedPackage = rejected,
        accessibilityEnabledForPackage = enabled,
        packageRuleIdsContainBuiltin = ruleIdsContainBuiltin
    )

    fun window(
        packageName: String = PKG,
        windowId: Int = WIN,
        rootPackageName: String? = PKG,
        rootWindowId: Int = WIN,
        isApplicationWindow: Boolean = true,
        keyguardLocked: Boolean = false,
        sensitivePackage: Boolean = false,
        traversalComplete: Boolean = true,
        hasEditableOrPasswordNode: Boolean = false,
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2340,
        capturedAtElapsedMs: Long = T0,
        foregroundSinceElapsedMs: Long = T0
    ): WindowGuardInput = WindowGuardInput(
        packageName = packageName,
        windowId = windowId,
        rootPackageName = rootPackageName,
        rootWindowId = rootWindowId,
        isApplicationWindow = isApplicationWindow,
        keyguardLocked = keyguardLocked,
        sensitivePackage = sensitivePackage,
        traversalComplete = traversalComplete,
        hasEditableOrPasswordNode = hasEditableOrPasswordNode,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
        capturedAtElapsedMs = capturedAtElapsedMs,
        foregroundSinceElapsedMs = foregroundSinceElapsedMs
    )

    fun candidate(
        ruleId: String = ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1,
        reason: String = REASON_EXPLICIT_AD_SKIP,
        snapshotNode: NodeFields = node(),
        refreshed: NodeFields? = node()
    ): CandidateGuardInput = CandidateGuardInput(
        candidateRuleId = ruleId,
        candidateReason = reason,
        snapshotNode = snapshotNode,
        refreshed = refreshed
    )

    fun request(
        nowElapsedMs: Long = T0,
        packageName: String = PKG,
        windowId: Int = WIN,
        policy: PolicyGuardInput = policy(),
        window: WindowGuardInput = window(),
        candidate: CandidateGuardInput = candidate()
    ): ClickRequest = ClickRequest(
        nowElapsedMs = nowElapsedMs,
        packageName = packageName,
        windowId = windowId,
        policy = policy,
        window = window,
        candidate = candidate
    )

    /** 合同允许的两类窗口事件。 */
    fun gate(): SkipGate = SkipGate(setOf(STATE_CHANGED, CONTENT_CHANGED))
}
