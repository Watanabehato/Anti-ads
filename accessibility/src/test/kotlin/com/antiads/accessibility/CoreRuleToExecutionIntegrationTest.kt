package com.antiads.accessibility

import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.ConfigValidator
import com.antiads.core.config.PackageConfig
import com.antiads.core.config.PolicyResolver
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.rules.AdWindowSnapshot
import com.antiads.core.rules.Bounds
import com.antiads.core.rules.ConservativeAdRuleEngine
import com.antiads.core.rules.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨模块集成链（t11）：真实 core 规则引擎 → 真实快照 → 共享映射 → 真实执行前复核。
 *
 * 这条链是“判定的唯一算法在 core、执行约束在 accessibility”的接口边界，
 * 用生产代码路径（SkipRequestBuilder + SkipGate + ExecutionGuards + 真实 ConservativeAdRuleEngine）验证：
 * - 正例：真实 core 产生的候选能通过全部执行前复核并被授权 performAction；
 * - 关闭/排除/敏感窗口：core 不产生候选，且即便传入过期候选也会被拒绝。
 *
 * 注意：本测试仍是 JVM 纯逻辑，不证明系统授权、无障碍连接或真实广告页面结果（见 docs/qa-plan.md 设备项）。
 */
class CoreRuleToExecutionIntegrationTest {

    private val engine = ConservativeAdRuleEngine()
    private val pkg = Fixtures.PKG
    private val win = Fixtures.WIN
    private val now = Fixtures.T0
    private val screenWidth = 1080
    private val screenHeight = 2340

    private fun config(
        master: Boolean = true,
        accessGlobal: Boolean = true,
        packageAccess: Boolean = true,
        ruleIds: Set<String> = setOf(ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1),
        revision: Long = 7L
    ) = ProtectionConfig(
        revision = revision,
        masterEnabled = master,
        accessibilityEnabled = accessGlobal,
        hookEnabled = false,
        packages = mapOf(
            pkg to PackageConfig(
                accessibilityEnabled = packageAccess,
                hookEnabled = false,
                ruleIds = ruleIds
            )
        )
    )

    private fun node(
        id: Int,
        text: String = "",
        description: String = "",
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        editable: Boolean = false,
        password: Boolean = false,
        bounds: Bounds
    ) = UiNode(id, text, description, null, clickable, enabled, visible, editable, password, bounds)

    /** 右上角跳过按钮：中心 (970,150)。 */
    private fun skipBounds() = Bounds(900, 100, 1040, 200)

    /** 独立可见的广告上下文节点。 */
    private fun adContext() = node(1, text = "广告", clickable = false, bounds = Bounds(100, 1200, 300, 1300))

    private fun snapshot(
        nodes: List<UiNode>,
        packageName: String = pkg,
        sensitivePackage: Boolean = false,
        keyguardLocked: Boolean = false,
        traversalComplete: Boolean = true
    ) = AdWindowSnapshot(
        packageName = packageName,
        windowId = win,
        capturedAtElapsedMs = now,
        foregroundSinceElapsedMs = now - 1_000L,
        screenWidthPx = screenWidth,
        screenHeightPx = screenHeight,
        isApplicationWindow = true,
        keyguardLocked = keyguardLocked,
        sensitivePackage = sensitivePackage,
        traversalComplete = traversalComplete,
        nodes = nodes
    )

    private fun positiveSnapshot() = snapshot(listOf(adContext(), node(0, text = "跳过 5 秒", bounds = skipBounds())))

    /** 与服务内联构造一致的策略复核输入（走真实 ConfigValidator/PolicyResolver）。 */
    private fun policyFrom(cfg: ProtectionConfig, revisionNow: Long = cfg.revision): PolicyGuardInput = PolicyGuardInput(
        connected = true,
        dependencyInstalled = true,
        configRevisionAtSnapshot = cfg.revision,
        configRevisionNow = revisionNow,
        packageName = pkg,
        packageNameValid = ConfigValidator.isValidPackageName(pkg),
        rejectedPackage = pkg in ConfigConstants.REJECTED_PACKAGE_KEYS,
        accessibilityEnabledForPackage = PolicyResolver.accessibilityEnabled(cfg, pkg),
        packageRuleIdsContainBuiltin = cfg.packages[pkg]?.ruleIds
            ?.contains(ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1) == true
    )

    private fun windowGuard(
        snap: AdWindowSnapshot,
        hasEditableOrPasswordNode: Boolean = snap.nodes.any { it.editable || it.password },
        sensitivePackage: Boolean = snap.sensitivePackage,
        keyguardLocked: Boolean = snap.keyguardLocked
    ) = Fixtures.window(
        packageName = snap.packageName,
        windowId = snap.windowId,
        rootPackageName = snap.packageName,
        rootWindowId = snap.windowId,
        isApplicationWindow = snap.isApplicationWindow,
        keyguardLocked = keyguardLocked,
        sensitivePackage = sensitivePackage,
        traversalComplete = snap.traversalComplete,
        hasEditableOrPasswordNode = hasEditableOrPasswordNode,
        screenWidthPx = snap.screenWidthPx,
        screenHeightPx = snap.screenHeightPx,
        capturedAtElapsedMs = snap.capturedAtElapsedMs,
        foregroundSinceElapsedMs = snap.foregroundSinceElapsedMs
    )

    private fun request(
        cfg: ProtectionConfig,
        snap: AdWindowSnapshot,
        candidateId: Int,
        refreshed: NodeFields? = NodeFields(
            text = snap.nodes.firstOrNull { it.nodeId == candidateId }?.text ?: "",
            description = "",
            clickable = true,
            enabled = true,
            visible = true,
            editable = false,
            password = false
        ),
        hasEditableOrPasswordNode: Boolean = snap.nodes.any { it.editable || it.password }
    ): ClickRequest? {
        val candidate = engine.evaluate(cfg, snap).firstOrNull() ?: return null
        return SkipRequestBuilder.build(
            nowElapsedMs = now,
            packageName = snap.packageName,
            windowId = snap.windowId,
            policy = policyFrom(cfg),
            window = windowGuard(snap, hasEditableOrPasswordNode = hasEditableOrPasswordNode),
            snapshot = snap,
            candidate = candidate,
            refreshed = refreshed
        )
    }

    // ---------------- 正例：真实 core 候选 → 授权执行 ----------------

    @Test
    fun realCoreCandidateIsAuthorizedForExecution() {
        val cfg = config()
        val snap = positiveSnapshot()
        val candidate = engine.evaluate(cfg, snap)
        assertEquals(1, candidate.size)
        assertEquals(0, candidate[0].nodeId)
        assertEquals(ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1, candidate[0].ruleId)
        assertEquals(ConservativeAdRuleEngine.REASON_EXPLICIT_AD_SKIP, candidate[0].reason)

        val gate = Fixtures.gate()
        gate.startInitialEpoch(pkg, win, now)
        val built = request(cfg, snap, candidateId = 0)
        assertTrue(built != null)
        val authorization = gate.authorizeClick(built!!)
        assertTrue("真实 core 候选应通过全部执行前复核", authorization.perform)
        assertEquals(GuardCode.OK, authorization.reason)
    }

    @Test
    fun sameEpochIsAuthorizedOnlyOnce() {
        val cfg = config()
        val gate = Fixtures.gate()
        gate.startInitialEpoch(pkg, win, now)
        val first = gate.authorizeClick(request(cfg, positiveSnapshot(), candidateId = 0)!!)
        assertTrue(first.perform)
        val second = gate.authorizeClick(request(cfg, positiveSnapshot(), candidateId = 0)!!)
        assertFalse(second.perform)
        assertEquals(GuardCode.EPOCH_ALREADY_ATTEMPTED, second.reason)
    }

    // ---------------- 关闭与排除：core 不产生候选 ----------------

    @Test
    fun masterSwitchOffBreaksTheChainAtTheEngine() {
        val cfg = config(master = false)
        val snap = positiveSnapshot()
        assertTrue(engine.evaluate(cfg, snap).isEmpty())
        assertNull(request(cfg, snap, candidateId = 0))

        // 防御性：即使把关闭后的配置拿去复核一个"历史候选"，也必须被拒绝
        val stale = SkipRequestBuilder.build(
            nowElapsedMs = now,
            packageName = pkg,
            windowId = win,
            policy = policyFrom(cfg),
            window = windowGuard(snap),
            snapshot = snap,
            candidate = com.antiads.core.rules.SkipCandidate(
                nodeId = 0,
                ruleId = ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1,
                reason = ConservativeAdRuleEngine.REASON_EXPLICIT_AD_SKIP
            ),
            refreshed = NodeFields("跳过 5 秒", "", true, true, true, false, false)
        )!!
        val gate = Fixtures.gate()
        gate.startInitialEpoch(pkg, win, now)
        val authorization = gate.authorizeClick(stale)
        assertFalse(authorization.perform)
        assertEquals(GuardCode.POLICY_DISABLED, authorization.reason)
    }

    @Test
    fun ruleSelectionOffBreaksTheChain() {
        val cfg = config(ruleIds = emptySet())
        assertTrue(engine.evaluate(cfg, positiveSnapshot()).isEmpty())
    }

    @Test
    fun sensitiveWindowAndKeyguardBreakTheChainBeforeAndAfterTheEngine() {
        val cfg = config()
        val sensitive = snapshot(
            listOf(adContext(), node(0, text = "跳过 5 秒", bounds = skipBounds())),
            sensitivePackage = true
        )
        assertTrue("敏感包不应产生候选", engine.evaluate(cfg, sensitive).isEmpty())

        val locked = snapshot(
            listOf(adContext(), node(0, text = "跳过 5 秒", bounds = skipBounds())),
            keyguardLocked = true
        )
        assertTrue("锁屏不应产生候选", engine.evaluate(cfg, locked).isEmpty())
    }

    // ---------------- 误触与快照不一致 ----------------

    @Test
    fun editableNodeInWindowBlocksARealisticAdLayout() {
        val cfg = config()
        val snap = snapshot(
            listOf(
                adContext(),
                node(0, text = "跳过 5 秒", bounds = skipBounds()),
                node(2, text = "搜索", editable = true, bounds = Bounds(20, 2000, 400, 2100))
            )
        )
        assertTrue("整窗出现可编辑节点时不点击", engine.evaluate(cfg, snap).isEmpty())

        // 防御性：即使快照在 core 侧未标记，执行前复核也必须拒绝
        val gate = Fixtures.gate()
        gate.startInitialEpoch(pkg, win, now)
        val request = SkipRequestBuilder.build(
            nowElapsedMs = now,
            packageName = pkg,
            windowId = win,
            policy = policyFrom(cfg),
            window = windowGuard(snap, hasEditableOrPasswordNode = true),
            snapshot = snap,
            candidate = com.antiads.core.rules.SkipCandidate(
                nodeId = 0,
                ruleId = ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1,
                reason = ConservativeAdRuleEngine.REASON_EXPLICIT_AD_SKIP
            ),
            refreshed = NodeFields("跳过 5 秒", "", true, true, true, false, false)
        )!!
        val authorization = gate.authorizeClick(request)
        assertFalse(authorization.perform)
        assertEquals(GuardCode.EDITABLE_NODE_IN_WINDOW, authorization.reason)
    }

    @Test
    fun leftSideButtonIsRejectedByTheRealEngine() {
        val cfg = config()
        val snap = snapshot(listOf(adContext(), node(0, text = "跳过 5 秒", bounds = Bounds(100, 100, 240, 200))))
        assertTrue(engine.evaluate(cfg, snap).isEmpty())
    }

    @Test
    fun textChangeBetweenSnapshotAndRefreshIsRejected() {
        val cfg = config()
        val snap = positiveSnapshot()
        val request = request(
            cfg,
            snap,
            candidateId = 0,
            refreshed = NodeFields("跳过 10 秒", "", true, true, true, false, false)
        )!!
        val gate = Fixtures.gate()
        gate.startInitialEpoch(pkg, win, now)
        val authorization = gate.authorizeClick(request)
        assertFalse(authorization.perform)
        assertEquals(GuardCode.NODE_TEXT_CHANGED, authorization.reason)
    }

    @Test
    fun missingRefreshedNodeIsRejected() {
        val cfg = config()
        val request = request(cfg, positiveSnapshot(), candidateId = 0, refreshed = null)!!
        val gate = Fixtures.gate()
        gate.startInitialEpoch(pkg, win, now)
        val authorization = gate.authorizeClick(request)
        assertFalse(authorization.perform)
        assertEquals(GuardCode.REFRESH_FAILED, authorization.reason)
    }

    @Test
    fun configRevisionChangedBetweenSnapshotAndExecutionIsRejected() {
        val cfg = config()
        val snap = positiveSnapshot()
        val request = SkipRequestBuilder.build(
            nowElapsedMs = now,
            packageName = pkg,
            windowId = win,
            policy = policyFrom(cfg, revisionNow = cfg.revision + 1),
            window = windowGuard(snap),
            snapshot = snap,
            candidate = engine.evaluate(cfg, snap).first(),
            refreshed = NodeFields("跳过 5 秒", "", true, true, true, false, false)
        )!!
        val gate = Fixtures.gate()
        gate.startInitialEpoch(pkg, win, now)
        val authorization = gate.authorizeClick(request)
        assertFalse(authorization.perform)
        assertEquals(GuardCode.CONFIG_REVISION_CHANGED, authorization.reason)
    }

    @Test
    fun windowChangedAfterSnapshotIsRejected() {
        val cfg = config()
        val snap = positiveSnapshot()
        val request = SkipRequestBuilder.build(
            nowElapsedMs = now,
            packageName = pkg,
            windowId = win,
            policy = policyFrom(cfg),
            window = windowGuard(snap).copy(rootWindowId = Fixtures.OTHER_WIN),
            snapshot = snap,
            candidate = engine.evaluate(cfg, snap).first(),
            refreshed = NodeFields("跳过 5 秒", "", true, true, true, false, false)
        )!!
        val gate = Fixtures.gate()
        gate.startInitialEpoch(pkg, win, now)
        val authorization = gate.authorizeClick(request)
        assertFalse(authorization.perform)
        assertEquals(GuardCode.WINDOW_CHANGED, authorization.reason)
    }
}
