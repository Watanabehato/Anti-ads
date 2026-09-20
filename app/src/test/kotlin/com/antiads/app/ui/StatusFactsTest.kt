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
    fun serviceIdentityMatchRequiresExactPackageAndClass() {
        assertTrue(
            StatusFacts.matchesAdSkipService(
                "com.antiads.accessibility",
                "com.antiads.accessibility.AdSkipService"
            )
        )
        assertFalse(StatusFacts.matchesAdSkipService("com.antiads.accessibility", null))
        assertFalse(StatusFacts.matchesAdSkipService("com.other.app", "com.antiads.accessibility.AdSkipService"))
        assertFalse(StatusFacts.matchesAdSkipService(null, null))
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
}
