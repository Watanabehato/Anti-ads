package com.antiads.accessibility

import com.antiads.accessibility.Fixtures.CONTENT_CHANGED
import com.antiads.accessibility.Fixtures.OTHER_PKG
import com.antiads.accessibility.Fixtures.OTHER_WIN
import com.antiads.accessibility.Fixtures.PKG
import com.antiads.accessibility.Fixtures.STATE_CHANGED
import com.antiads.accessibility.Fixtures.T0
import com.antiads.accessibility.Fixtures.UNRELATED_EVENT
import com.antiads.accessibility.Fixtures.WIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 事件过滤、epoch 去重、250ms 合并、同包 2000ms 冷却、撤权取消排队。 */
class SkipGateTest {

    @Test
    fun nonWindowEventIsDropped() {
        val gate = Fixtures.gate()
        val result = gate.onEvent(UNRELATED_EVENT, PKG, WIN, T0)
        assertTrue(result is GateEvent.Drop)
        assertEquals(GuardCode.EVENT_TYPE_FILTERED, (result as GateEvent.Drop).reason)
        assertNull(gate.currentEpoch)
    }

    @Test
    fun missingPackageNameIsDropped() {
        val gate = Fixtures.gate()
        val result = gate.onEvent(STATE_CHANGED, null, WIN, T0)
        assertEquals(GuardCode.WINDOW_IDENTITY_UNKNOWN, (result as GateEvent.Drop).reason)
        assertNull(gate.currentEpoch)
    }

    @Test
    fun windowStateChangeCreatesEpochAndScansImmediately() {
        val gate = Fixtures.gate()
        val result = gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        assertTrue(result is GateEvent.ScanNow)
        val epoch = gate.currentEpoch
        assertNotNull(epoch)
        assertEquals(PKG, epoch!!.packageName)
        assertEquals(WIN, epoch.windowId)
        assertEquals(T0, epoch.foregroundSinceElapsedMs)
        assertFalse(epoch.attempted)
    }

    @Test
    fun repeatedContentEventsKeepEpochAndWindowStart() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        gate.onEvent(CONTENT_CHANGED, PKG, WIN, T0 + 1_000)
        gate.onEvent(CONTENT_CHANGED, PKG, WIN, T0 + 2_000)
        assertEquals(T0, gate.currentEpoch!!.foregroundSinceElapsedMs)
    }

    @Test
    fun contentEventsDoNotExtendTheTenSecondWindow() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        gate.onEvent(CONTENT_CHANGED, PKG, WIN, T0 + 9_000)

        val stillInside = gate.evaluate(
            Fixtures.request(
                nowElapsedMs = T0 + 9_000,
                window = Fixtures.window(capturedAtElapsedMs = T0 + 9_000, foregroundSinceElapsedMs = T0)
            )
        )
        assertTrue(stillInside.allowed)

        val afterWindow = gate.evaluate(
            Fixtures.request(
                nowElapsedMs = T0 + 10_001,
                window = Fixtures.window(capturedAtElapsedMs = T0 + 10_001, foregroundSinceElapsedMs = T0)
            )
        )
        assertEquals(GuardCode.WINDOW_AGE_OUT_OF_RANGE, afterWindow.reason)
    }

    @Test
    fun eventsAreCoalescedAt250msAndScanIsConsumedOnce() {
        val gate = Fixtures.gate()
        val first = gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        assertTrue(first is GateEvent.ScanNow)

        val merged = gate.onEvent(CONTENT_CHANGED, PKG, WIN, T0 + 100)
        assertTrue(merged is GateEvent.ScanLater)
        assertEquals(150L, (merged as GateEvent.ScanLater).delayMs)

        assertNull(gate.takeDueScan(T0 + SkipLimits.SCAN_MIN_INTERVAL_MS - 1))
        val due = gate.takeDueScan(T0 + SkipLimits.SCAN_MIN_INTERVAL_MS)
        assertNotNull(due)
        assertEquals(PKG, due!!.packageName)
        assertEquals(WIN, due.windowId)
        assertEquals(T0, due.foregroundSinceElapsedMs)
        assertNull(gate.takeDueScan(T0 + SkipLimits.SCAN_MIN_INTERVAL_MS))
    }

    @Test
    fun newWindowReplacesEpochAndDropsTheOldQueuedScan() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        gate.onEvent(CONTENT_CHANGED, PKG, WIN, T0 + 100)

        val switched = gate.onEvent(CONTENT_CHANGED, PKG, OTHER_WIN, T0 + 120)
        assertTrue(switched is GateEvent.ScanLater)
        assertEquals(OTHER_WIN, gate.currentEpoch!!.windowId)
        assertEquals(T0 + 120, gate.currentEpoch!!.foregroundSinceElapsedMs)

        val due = gate.takeDueScan(T0 + SkipLimits.SCAN_MIN_INTERVAL_MS)
        assertNotNull(due)
        assertEquals(OTHER_WIN, due!!.windowId)
        assertEquals(T0 + 120, due.foregroundSinceElapsedMs)
    }

    @Test
    fun newPackageStartsANewEpoch() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        gate.onEvent(STATE_CHANGED, OTHER_PKG, WIN, T0 + 10)
        assertEquals(OTHER_PKG, gate.currentEpoch!!.packageName)
        assertEquals(T0 + 10, gate.currentEpoch!!.foregroundSinceElapsedMs)
    }

    @Test
    fun oneAttemptPerEpochEvenWhenTheSystemRejectsTheAction() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)

        val authorized = gate.authorizeClick(Fixtures.request(nowElapsedMs = T0))
        assertTrue(authorized.perform)
        // 系统随后返回 false（动作被拒绝）也必须消耗本 epoch：不再紧密重试
        val again = gate.authorizeClick(
            Fixtures.request(
                nowElapsedMs = T0 + 10,
                window = Fixtures.window(capturedAtElapsedMs = T0 + 10)
            )
        )
        assertFalse(again.perform)
        assertEquals(GuardCode.EPOCH_ALREADY_ATTEMPTED, again.reason)

        val event = gate.onEvent(CONTENT_CHANGED, PKG, WIN, T0 + 20)
        assertEquals(GuardCode.EPOCH_ALREADY_ATTEMPTED, (event as GateEvent.Drop).reason)
    }

    @Test
    fun samePackageThrottleAppliesAcrossEpochsAt2000msBoundary() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        assertTrue(gate.authorizeClick(Fixtures.request(nowElapsedMs = T0)).perform)

        gate.onEvent(STATE_CHANGED, PKG, OTHER_WIN, T0 + 500)
        val at1999 = gate.authorizeClick(
            Fixtures.request(
                nowElapsedMs = T0 + 1_999,
                windowId = OTHER_WIN,
                window = Fixtures.window(
                    windowId = OTHER_WIN,
                    rootWindowId = OTHER_WIN,
                    capturedAtElapsedMs = T0 + 1_999,
                    foregroundSinceElapsedMs = T0 + 500
                )
            )
        )
        assertEquals(GuardCode.THROTTLED, at1999.reason)

        val at2000 = gate.authorizeClick(
            Fixtures.request(
                nowElapsedMs = T0 + SkipLimits.SAME_PACKAGE_MIN_INTERVAL_MS,
                windowId = OTHER_WIN,
                window = Fixtures.window(
                    windowId = OTHER_WIN,
                    rootWindowId = OTHER_WIN,
                    capturedAtElapsedMs = T0 + SkipLimits.SAME_PACKAGE_MIN_INTERVAL_MS,
                    foregroundSinceElapsedMs = T0 + 500
                )
            )
        )
        assertTrue(at2000.perform)
    }

    @Test
    fun clockRollbackDoesNotBypassTheThrottle() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        gate.authorizeClick(Fixtures.request(nowElapsedMs = T0))
        gate.onEvent(STATE_CHANGED, PKG, OTHER_WIN, T0 + 500)
        val rolledBack = gate.authorizeClick(
            Fixtures.request(
                nowElapsedMs = T0 - 5_000,
                windowId = OTHER_WIN,
                window = Fixtures.window(
                    windowId = OTHER_WIN,
                    rootWindowId = OTHER_WIN,
                    capturedAtElapsedMs = T0 - 5_000,
                    foregroundSinceElapsedMs = T0 - 5_000
                )
            )
        )
        assertEquals(GuardCode.THROTTLED, rolledBack.reason)
    }

    @Test
    fun cancelPendingDropsQueuedWork() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        val queued = gate.onEvent(CONTENT_CHANGED, PKG, WIN, T0 + 100)
        assertTrue(queued is GateEvent.ScanLater)
        assertNotNull(gate.pendingScanAt())

        gate.cancelPending(GuardCode.CONFIG_DISABLED)
        assertNull(gate.pendingScanAt())
        assertNull(gate.takeDueScan(T0 + 5_000))
    }

    @Test
    fun revocationResetClearsEpochAndThrottle() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        gate.authorizeClick(Fixtures.request(nowElapsedMs = T0))
        assertEquals(1, gate.throttleEntryCount())

        gate.reset()
        assertNull(gate.currentEpoch)
        assertEquals(0, gate.throttleEntryCount())
        assertEquals(GuardCode.NOT_WATCHING, gate.evaluate(Fixtures.request(nowElapsedMs = T0)).reason)
    }

    @Test
    fun evaluateRejectsRequestsForAnotherWindow() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        val decision = gate.evaluate(
            Fixtures.request(
                nowElapsedMs = T0,
                windowId = OTHER_WIN,
                window = Fixtures.window(windowId = OTHER_WIN, rootWindowId = OTHER_WIN)
            )
        )
        assertEquals(GuardCode.WINDOW_CHANGED, decision.reason)
    }

    @Test
    fun policyGuardsFlowThroughEvaluate() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        assertEquals(
            GuardCode.DEPENDENCY_MISSING,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, policy = Fixtures.policy(dependencyInstalled = false))).reason
        )
        assertEquals(
            GuardCode.POLICY_DISABLED,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, policy = Fixtures.policy(enabled = false))).reason
        )
        assertEquals(
            GuardCode.REJECTED_PACKAGE,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, policy = Fixtures.policy(rejected = true))).reason
        )
        assertEquals(
            GuardCode.CONFIG_REVISION_CHANGED,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, policy = Fixtures.policy(revisionNow = 8L))).reason
        )
    }

    @Test
    fun windowGuardsFlowThroughEvaluate() {
        val gate = Fixtures.gate()
        gate.onEvent(STATE_CHANGED, PKG, WIN, T0)
        assertEquals(
            GuardCode.KEYGUARD_LOCKED,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, window = Fixtures.window(keyguardLocked = true))).reason
        )
        assertEquals(
            GuardCode.SENSITIVE_PACKAGE,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, window = Fixtures.window(sensitivePackage = true))).reason
        )
        assertEquals(
            GuardCode.TRAVERSAL_INCOMPLETE,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, window = Fixtures.window(traversalComplete = false))).reason
        )
        assertEquals(
            GuardCode.EDITABLE_NODE_IN_WINDOW,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, window = Fixtures.window(hasEditableOrPasswordNode = true))).reason
        )
        assertEquals(
            GuardCode.WINDOW_CHANGED,
            gate.evaluate(Fixtures.request(nowElapsedMs = T0, window = Fixtures.window(rootPackageName = OTHER_PKG))).reason
        )
    }

    @Test
    fun initialEpochFromObservationIsAccepted() {
        val gate = Fixtures.gate()
        val result = gate.startInitialEpoch(PKG, WIN, T0)
        assertTrue(result is GateEvent.ScanNow)
        assertEquals(T0, gate.currentEpoch!!.foregroundSinceElapsedMs)
        assertTrue(gate.evaluate(Fixtures.request(nowElapsedMs = T0)).allowed)
    }

    @Test
    fun throttleTableStaysBounded() {
        val gate = Fixtures.gate()
        for (index in 1..60) {
            val pkg = "com.example.p" + index
            val now = T0 + index * 1_000L
            gate.onEvent(STATE_CHANGED, pkg, WIN, now)
            gate.authorizeClick(
                Fixtures.request(
                    nowElapsedMs = now,
                    packageName = pkg,
                    policy = Fixtures.policy(packageName = pkg),
                    window = Fixtures.window(
                        capturedAtElapsedMs = now,
                        foregroundSinceElapsedMs = now
                    )
                )
            )
        }
        assertTrue(gate.throttleEntryCount() <= SkipLimits.MAX_THROTTLE_ENTRIES)
    }
}
