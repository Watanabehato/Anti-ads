package com.antiads.app.ui

import com.antiads.core.status.AccessibilityPhase
import com.antiads.core.status.AccessibilityRuntimeState
import com.antiads.core.status.ConfigTransportState
import com.antiads.core.status.HookInstallState
import com.antiads.core.status.HookProcessReport
import com.antiads.core.status.ReceivedHookReport
import com.antiads.core.status.SkipActionRecord
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态事实映射：15 秒过期边界、系统授权与“实际连接”分列、报告排序。 */
class StatusFactsTest {

    @Test
    fun freshnessUsesFifteenSecondBoundary() {
        assertEquals(ReportFreshness.FRESH, StatusFacts.freshness(receivedAtElapsedMs = 0L, nowElapsedMs = 14_999L))
        assertEquals(ReportFreshness.FRESH, StatusFacts.freshness(receivedAtElapsedMs = 0L, nowElapsedMs = 15_000L))
        assertEquals(ReportFreshness.STALE, StatusFacts.freshness(receivedAtElapsedMs = 0L, nowElapsedMs = 15_001L))
    }

    @Test
    fun hookReportsOrderFreshFirstThenNewest() {
        val facts = StatusFacts.hookReports(
            listOf(
                received("com.example.a", pid = 1, receivedAt = 0L),
                received("com.example.b", pid = 2, receivedAt = 9_000L),
                received("com.example.c", pid = 3, receivedAt = 14_000L)
            ),
            nowElapsedMs = 20_000L
        )

        // 新鲜报告在前并按时间从新到旧：6s 前、11s 前为新鲜；20s 前已过期
        assertEquals(listOf("com.example.c", "com.example.b", "com.example.a"), facts.map { it.packageName })
        assertEquals(ReportFreshness.FRESH, facts[0].freshness)
        assertEquals(ReportFreshness.FRESH, facts[1].freshness)
        assertEquals(ReportFreshness.STALE, facts[2].freshness)
        assertEquals(6_000L, facts[0].ageMs)
        assertEquals(11_000L, facts[1].ageMs)
        assertEquals(20_000L, facts[2].ageMs)
    }

    @Test
    fun accessibilityAuthorizedAndConnectedStaySeparateFacts() {
        val fact = StatusFacts.accessibility(
            systemEnabled = true,
            state = AccessibilityRuntimeState(
                connected = false,
                phase = AccessibilityPhase.DISCONNECTED,
                lastErrorCode = "NOT_IMPLEMENTED"
            ),
            nowElapsedMs = 10_000L
        )

        assertTrue(fact.systemEnabled)
        assertFalse(fact.connected)
        assertEquals("NOT_IMPLEMENTED", fact.lastErrorCode)
        assertEquals(AccessibilityPhase.DISCONNECTED, fact.phase)
    }

    @Test
    fun accessibilityReportsLastActionAgeAndAcceptedFlag() {
        val fact = StatusFacts.accessibility(
            systemEnabled = true,
            state = AccessibilityRuntimeState(
                connected = true,
                phase = AccessibilityPhase.WATCHING,
                activePackage = "com.example.target",
                lastAction = SkipActionRecord(
                    packageName = "com.example.target",
                    ruleId = "builtin.conservative.v1",
                    occurredAtElapsedMs = 8_000L,
                    actionAccepted = false
                )
            ),
            nowElapsedMs = 10_000L
        )

        assertEquals("com.example.target", fact.lastActionPackage)
        assertEquals(2_000L, fact.lastActionAgeMs)
        assertEquals(false, fact.lastActionAccepted)
        assertEquals("com.example.target", fact.activePackage)
    }

    @Test
    fun serviceIdentityUsesHostPackageAndExactServiceClass() {
        // 真实 API29 系统条目：packageName 是宿主包，类名来自 :accessibility 库（QA-01 实测值）
        assertTrue(
            StatusFacts.matchesAdSkipService(
                HOST_PACKAGE,
                "com.antiads.app",
                "com.antiads.accessibility.AdSkipService"
            )
        )
        assertTrue(
            StatusFacts.matchesAdSkipService(
                HOST_PACKAGE,
                HOST_PACKAGE,
                StatusFacts.AD_SKIP_SERVICE_CLASS
            )
        )
    }

    @Test
    fun libraryNamespaceIsNotTheServiceOwningPackage() {
        // 回归（QA-01）：库 namespace 不等于组件归属包，用它当归属包时必须判定为“不是本产品服务”
        assertFalse(
            StatusFacts.matchesAdSkipService(
                HOST_PACKAGE,
                "com.antiads.accessibility",
                "com.antiads.accessibility.AdSkipService"
            )
        )
        // 反向：宿主包本身不是库 namespace
        assertNotEquals(HOST_PACKAGE, "com.antiads.accessibility")
    }

    @Test
    fun serviceIdentityRejectsWrongOrMissingIdentifiers() {
        assertFalse(StatusFacts.matchesAdSkipService(HOST_PACKAGE, null, null))
        assertFalse(StatusFacts.matchesAdSkipService(HOST_PACKAGE, "com.other.app", StatusFacts.AD_SKIP_SERVICE_CLASS))
        assertFalse(StatusFacts.matchesAdSkipService(HOST_PACKAGE, HOST_PACKAGE, null))
        // 类名必须完整精确匹配：同后缀的其他类不算
        assertFalse(
            StatusFacts.matchesAdSkipService(
                HOST_PACKAGE,
                HOST_PACKAGE,
                "other.pkg.com.antiads.accessibility.AdSkipService"
            )
        )
        assertFalse(StatusFacts.matchesAdSkipService(HOST_PACKAGE, HOST_PACKAGE, "com.antiads.accessibility.AdSkipServiceX"))
        // 宿主包名缺失时不得匹配（避免把空 host 当成通配）
        assertFalse(StatusFacts.matchesAdSkipService("", "", StatusFacts.AD_SKIP_SERVICE_CLASS))
    }

    private fun received(packageName: String, pid: Int, receivedAt: Long): ReceivedHookReport =
        ReceivedHookReport(
            report = HookProcessReport(
                packageName = packageName,
                processName = packageName,
                processToken = UUID.randomUUID().toString(),
                pid = pid,
                apiLevel = 29,
                installState = HookInstallState.INSTALLED,
                transportState = ConfigTransportState.OK,
                policyRevision = 1L,
                observedCallbacks = 1L,
                droppedCallbacks = 0L
            ),
            receivedAtElapsedMs = receivedAt
        )

    private companion object {
        /** 宿主 applicationId（debug 无 suffix，与 app/build.gradle.kts 的 applicationId 一致）。 */
        const val HOST_PACKAGE: String = "com.antiads.app"
    }
}
