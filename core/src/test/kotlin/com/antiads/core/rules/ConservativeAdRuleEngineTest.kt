package com.antiads.core.rules

import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.PackageConfig
import com.antiads.core.config.ProtectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置规则 v1 的边界测试。
 *
 * 目标是能发现“误触（不该点击却点击）”与“关闭失效（该关闭仍点击）”两类缺陷：
 * 每条必要条件都有一个反例，几何与时间采用精确边界值。
 */
class ConservativeAdRuleEngineTest {

    private val engine = ConservativeAdRuleEngine()
    private val target = "com.example.target"
    private val ruleIds = setOf(ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1)

    // ---------------- 构造器 ----------------

    private fun config(
        master: Boolean = true,
        accessGlobal: Boolean = true,
        packageAccess: Boolean = true,
        ruleIdsForPackage: Set<String> = ruleIds,
        key: String = target,
        schemaVersion: Int = 1,
        hookGlobal: Boolean = false
    ) = ProtectionConfig(
        schemaVersion = schemaVersion,
        revision = 3L,
        masterEnabled = master,
        accessibilityEnabled = accessGlobal,
        hookEnabled = hookGlobal,
        packages = mapOf(
            key to PackageConfig(
                accessibilityEnabled = packageAccess,
                hookEnabled = false,
                ruleIds = ruleIdsForPackage
            )
        )
    )

    /** 1080x2400 屏幕上的右上角跳过按钮：中心 (970,150)，面积 14000。 */
    private fun skipBounds(left: Int = 900, top: Int = 100, right: Int = 1040, bottom: Int = 200) =
        Bounds(left, top, right, bottom)

    private fun node(
        id: Int,
        text: String = "",
        description: String = "",
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        editable: Boolean = false,
        password: Boolean = false,
        bounds: Bounds = skipBounds()
    ) = UiNode(
        nodeId = id,
        text = text,
        contentDescription = description,
        viewId = null,
        clickable = clickable,
        enabled = enabled,
        visible = visible,
        editable = editable,
        password = password,
        bounds = bounds
    )

    private fun contextNode(id: Int = 1, text: String = "广告") =
        node(id = id, text = text, clickable = false, bounds = Bounds(100, 1200, 300, 1300))

    private fun window(
        nodes: List<UiNode>,
        packageName: String = target,
        capturedAt: Long = 5_000L,
        foregroundSince: Long = 0L,
        width: Int = 1080,
        height: Int = 2400,
        isApplicationWindow: Boolean = true,
        keyguardLocked: Boolean = false,
        sensitivePackage: Boolean = false,
        traversalComplete: Boolean = true
    ) = AdWindowSnapshot(
        packageName = packageName,
        windowId = 42,
        capturedAtElapsedMs = capturedAt,
        foregroundSinceElapsedMs = foregroundSince,
        screenWidthPx = width,
        screenHeightPx = height,
        isApplicationWindow = isApplicationWindow,
        keyguardLocked = keyguardLocked,
        sensitivePackage = sensitivePackage,
        traversalComplete = traversalComplete,
        nodes = nodes
    )

    private fun positiveWindow() = window(nodes = listOf(contextNode(), node(0, text = "跳过 5 秒")))

    // ---------------- 正例 ----------------

    @Test
    fun explicitAdContextAndSkipButtonProduceSingleCandidate() {
        val result = engine.evaluate(config(), positiveWindow())
        assertEquals(1, result.size)
        assertEquals(0, result[0].nodeId)
        assertEquals(ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1, result[0].ruleId)
        assertEquals(ConservativeAdRuleEngine.REASON_EXPLICIT_AD_SKIP, result[0].reason)
    }

    @Test
    fun chineseAndEnglishSkipVariantsMatch() {
        val variants = listOf("跳过", "跳过 5", "跳过5秒", "跳过 5 s", "5秒跳过", "5 s 跳过", "Skip", "SKIP ADS", "skip 3 s", " 跳过 5 秒 ")
        for (variant in variants) {
            val result = engine.evaluate(config(), window(nodes = listOf(contextNode(), node(0, text = variant))))
            assertEquals("文案应被接受: " + variant, 1, result.size)
        }
    }

    @Test
    fun englishMatchingIsCaseInsensitiveAndChineseContextAccepted() {
        val result = engine.evaluate(
            config(),
            window(nodes = listOf(contextNode(text = " Advertisement "), node(0, description = "Skip ads 5 s")))
        )
        assertEquals(1, result.size)
    }

    @Test
    fun separateContextNodeLetsCandidateAlsoCarryAdLabel() {
        // 候选自身同时带“广告”描述时，只要还有另一个独立上下文节点就仍然成立
        val candidate = node(0, text = "跳过", description = "广告")
        val result = engine.evaluate(config(), window(nodes = listOf(candidate, contextNode(id = 2))))
        assertEquals(1, result.size)
    }

    // ---------------- 误触反例 ----------------

    @Test
    fun skipWithoutAdContextProducesNothing() {
        val result = engine.evaluate(config(), window(nodes = listOf(node(0, text = "跳过 5 秒"))))
        assertTrue(result.isEmpty())
    }

    @Test
    fun adContextFromSameNodeOnlyProducesNothing() {
        val single = node(0, text = "跳过", description = "广告")
        assertTrue(engine.evaluate(config(), window(nodes = listOf(single))).isEmpty())
    }

    @Test
    fun invisibleOrDisabledOrNonClickableCandidatesAreIgnored() {
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过", clickable = false)))).isEmpty())
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过", enabled = false)))).isEmpty())
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过", visible = false)))).isEmpty())
    }

    @Test
    fun invisibleAdContextIsNotEnough() {
        val invisibleContext = contextNode().copy(visible = false)
        assertTrue(engine.evaluate(config(), window(listOf(invisibleContext, node(0, text = "跳过")))).isEmpty())
    }

    @Test
    fun misMatchingLabelsAreRejected() {
        val mismatches = listOf(
            "跳过并购买", "点击领取", "继续", "允许", "点击跳过", "跳过广告", "skip ad now",
            "skip ads 5 seconds", "跳 过", "关闭广告", "跳过5秒后继续"
        )
        for (label in mismatches) {
            val result = engine.evaluate(config(), window(nodes = listOf(contextNode(), node(0, text = label))))
            assertTrue("文案不应命中: " + label, result.isEmpty())
        }
    }

    @Test
    fun fuzzyAdContextLabelsAreRejected() {
        val fuzzy = listOf("广告 5", "本广告", "ad.", "ads", "advertisement!")
        for (label in fuzzy) {
            val result = engine.evaluate(config(), window(nodes = listOf(contextNode(text = label), node(0, text = "跳过"))))
            assertTrue("上下文不应命中: " + label, result.isEmpty())
        }
    }

    @Test
    fun editableOrPasswordNodeBlocksWholeWindow() {
        val withEdit = window(listOf(contextNode(), node(0, text = "跳过"), node(5, text = "搜索", editable = true, bounds = Bounds(10, 2000, 200, 2100))))
        assertTrue(engine.evaluate(config(), withEdit).isEmpty())

        val withPassword = window(listOf(contextNode(), node(0, text = "跳过"), node(5, password = true, bounds = Bounds(10, 2000, 200, 2100))))
        assertTrue(engine.evaluate(config(), withPassword).isEmpty())
    }

    @Test
    fun windowLevelGuardsBlockCandidates() {
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过")), isApplicationWindow = false)).isEmpty())
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过")), keyguardLocked = true)).isEmpty())
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过")), sensitivePackage = true)).isEmpty())
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过")), traversalComplete = false)).isEmpty())
    }

    @Test
    fun abnormalScreenDimensionsBlockCandidates() {
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过")), width = 0)).isEmpty())
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过")), height = -1)).isEmpty())
        assertTrue(
            engine.evaluate(
                config(),
                window(listOf(contextNode(), node(0, text = "跳过")), width = ConservativeAdRuleEngine.MAX_SCREEN_DIMENSION_PX + 1)
            ).isEmpty()
        )
    }

    @Test
    fun emptyNodeListProducesNothing() {
        assertTrue(engine.evaluate(config(), window(emptyList())).isEmpty())
    }

    // ---------------- 时间边界 ----------------

    @Test
    fun foregroundWindowAgeBoundaryIsTenSeconds() {
        val nodes = listOf(contextNode(), node(0, text = "跳过"))
        assertEquals(1, engine.evaluate(config(), window(nodes, capturedAt = 10_000L, foregroundSince = 0L)).size)
        assertTrue(engine.evaluate(config(), window(nodes, capturedAt = 10_001L, foregroundSince = 0L)).isEmpty())
        assertTrue(engine.evaluate(config(), window(nodes, capturedAt = 9_999L, foregroundSince = 10_000L)).isEmpty())
    }

    @Test
    fun contentChangedCannotExtendTheWindow() {
        // 持续 content changed 只更新 capturedAt，不更新 foregroundSince → 超过 10 秒后不再点击
        val nodes = listOf(contextNode(), node(0, text = "跳过"))
        val longLived = window(nodes, capturedAt = 60_000L, foregroundSince = 1_000L)
        assertTrue(engine.evaluate(config(), longLived).isEmpty())
    }

    // ---------------- 几何边界 ----------------

    @Test
    fun geometryBoundariesAreExact() {
        val nodes = { bounds: Bounds -> window(listOf(contextNode(), node(0, text = "跳过", bounds = bounds))) }

        // 1080x2400：中心 X ≥ 702、中心 Y ≤ 600、面积 ≤ 311040
        assertEquals(1, engine.evaluate(config(), nodes(Bounds(600, 0, 804, 1000))).size)   // 中心 X=702（恰好 65%），Y=500
        assertTrue(engine.evaluate(config(), nodes(Bounds(598, 0, 802, 1000))).isEmpty())   // 中心 X=700（低于 65%）
        assertEquals(1, engine.evaluate(config(), nodes(Bounds(600, 0, 1080, 600))).size)   // 中心 Y=300
        assertTrue(engine.evaluate(config(), nodes(Bounds(600, 0, 1080, 1202))).isEmpty())  // 中心 Y=601（超过 25%）
        assertEquals(1, engine.evaluate(config(), nodes(Bounds(600, 0, 1080, 648))).size)   // 面积=480*648=311040（恰好 12%）
        assertTrue(engine.evaluate(config(), nodes(Bounds(600, 0, 1080, 650))).isEmpty())   // 面积=480*650=312000（超 12%）
    }

    @Test
    fun invalidBoundsAreRejected() {
        val nodes = { bounds: Bounds -> window(listOf(contextNode(), node(0, text = "跳过", bounds = bounds))) }
        assertTrue(engine.evaluate(config(), nodes(Bounds(900, 100, 900, 200))).isEmpty())   // 零宽
        assertTrue(engine.evaluate(config(), nodes(Bounds(900, 200, 1040, 100))).isEmpty())  // 负高
        assertTrue(engine.evaluate(config(), nodes(Bounds(-10, 100, 1040, 200))).isEmpty())  // 左越界
        assertTrue(engine.evaluate(config(), nodes(Bounds(900, -5, 1040, 200))).isEmpty())   // 上越界
        assertTrue(engine.evaluate(config(), nodes(Bounds(900, 100, 1081, 200))).isEmpty())  // 右越界
        assertTrue(engine.evaluate(config(), nodes(Bounds(900, 100, 1040, 2401))).isEmpty()) // 下越界
    }

    @Test
    fun screenDimensionLimitIsExactAndConservative() {
        val limit = ConservativeAdRuleEngine.MAX_SCREEN_DIMENSION_PX
        assertEquals(100_000, limit)

        // 上界内的大屏幕：按比例放在右上角的按钮仍能被命中（证明比例乘法在 Long 域正确）
        val bigNodes = listOf(
            contextNode().copy(bounds = Bounds(10_000, 40_000, 20_000, 50_000)),
            node(0, text = "跳过", bounds = Bounds(66_000, 0, 69_000, 20_000))
        )
        assertEquals(1, engine.evaluate(config(), window(bigNodes, width = limit, height = limit)).size)

        // 超限：保守不匹配，返回空列表
        assertTrue(engine.evaluate(config(), window(bigNodes, width = limit + 1, height = limit)).isEmpty())
        assertTrue(engine.evaluate(config(), window(bigNodes, width = limit, height = limit + 1)).isEmpty())
    }

    @Test
    fun extremeBoundsDoNotOverflow() {
        val huge = Bounds(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过", bounds = huge)))).isEmpty())
        val negative = Bounds(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE + 10, Int.MIN_VALUE + 10)
        assertTrue(engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过", bounds = negative)))).isEmpty())

        // left + right 若按 Int 计算会溢出（2e9 + 2e9 > Int.MAX_VALUE）：越界守卫先于算术生效
        val wouldOverflowInt = Bounds(2_000_000_000, 0, 2_000_000_100, 100)
        assertTrue(
            engine.evaluate(config(), window(listOf(contextNode(), node(0, text = "跳过", bounds = wouldOverflowInt)))).isEmpty()
        )
    }

    @Test
    fun onlyOneCandidateIsReturnedWithDeterministicOrdering() {
        val low = node(7, text = "跳过", bounds = Bounds(900, 300, 1040, 340))
        val high = node(3, text = "跳过 5 秒", bounds = Bounds(900, 100, 1040, 140))
        val result = engine.evaluate(config(), window(listOf(contextNode(), low, high)))
        assertEquals(1, result.size)
        assertEquals(3, result[0].nodeId)

        // 同一 top：right 更大者优先
        val smallerRight = node(9, text = "跳过", bounds = Bounds(900, 100, 1000, 140))
        val largerRight = node(4, text = "跳过", bounds = Bounds(900, 100, 1040, 140))
        assertEquals(4, engine.evaluate(config(), window(listOf(contextNode(), smallerRight, largerRight)))[0].nodeId)

        // top 与 right 相同：nodeId 更小者优先
        val first = node(2, text = "跳过", bounds = Bounds(900, 100, 1040, 140))
        val second = node(8, text = "跳过", bounds = Bounds(900, 100, 1040, 140))
        assertEquals(2, engine.evaluate(config(), window(listOf(contextNode(), first, second)))[0].nodeId)
    }

    // ---------------- 开关关闭 / 目标排除（不得伪造激活） ----------------

    @Test
    fun masterOrGlobalSwitchOffProducesNoCandidate() {
        assertTrue(engine.evaluate(config(master = false), positiveWindow()).isEmpty())
        assertTrue(engine.evaluate(config(accessGlobal = false), positiveWindow()).isEmpty())
        assertTrue(engine.evaluate(config(packageAccess = false), positiveWindow()).isEmpty())
    }

    @Test
    fun unconfiguredPackageIsUntouched() {
        assertTrue(engine.evaluate(config(), positiveWindow().copy(packageName = "com.example.other")).isEmpty())
        assertTrue(engine.evaluate(config(), positiveWindow().copy(packageName = "single")).isEmpty())
        assertTrue(engine.evaluate(config(), positiveWindow().copy(packageName = "")).isEmpty())
    }

    @Test
    fun rejectedSystemAndHostTargetsAreNeverTouched() {
        for (rejected in ConfigConstants.REJECTED_PACKAGE_KEYS) {
            val cfg = config(key = rejected)
            val win = positiveWindow().copy(packageName = rejected)
            assertTrue("不应处理: " + rejected, engine.evaluate(cfg, win).isEmpty())
        }
    }

    @Test
    fun emptyRuleSelectionDisablesTheRule() {
        assertTrue(engine.evaluate(config(ruleIdsForPackage = emptySet()), positiveWindow()).isEmpty())
        assertTrue(engine.evaluate(config(ruleIdsForPackage = setOf("custom.v1")), positiveWindow()).isEmpty())
    }

    @Test
    fun unknownSchemaVersionProducesNoCandidate() {
        assertTrue(engine.evaluate(config(schemaVersion = 2), positiveWindow()).isEmpty())
    }
}
