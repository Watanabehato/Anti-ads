package com.antiads.accessibility

import com.antiads.core.config.PackageConfig
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.status.AccessibilityPhase
import com.antiads.core.status.AccessibilityRuntimeState
import com.antiads.core.status.SkipActionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 状态发布：纯配置不能令 connected=true；订阅语义与异常隔离按合同执行。 */
class AccessibilityRuntimeTest {

    private fun config(
        master: Boolean = true,
        accessibility: Boolean = true,
        packageEnabled: Boolean = true
    ): ProtectionConfig = ProtectionConfig(
        schemaVersion = 1,
        revision = 4L,
        masterEnabled = master,
        accessibilityEnabled = accessibility,
        hookEnabled = false,
        packages = mapOf(Fixtures.PKG to PackageConfig(accessibilityEnabled = packageEnabled))
    )

    @Before
    fun setUp() {
        AccessibilityRuntime.resetForTest()
    }

    @After
    fun tearDown() {
        AccessibilityRuntime.resetForTest()
    }

    @Test
    fun defaultStateIsDisconnected() {
        val state = AccessibilityRuntime.state()
        assertFalse(state.connected)
        assertEquals(AccessibilityPhase.DISCONNECTED, state.phase)
        assertNull(state.activePackage)
        assertNull(state.lastAction)
        assertNull(state.lastErrorCode)
    }

    @Test
    fun observeReceivesCurrentStateImmediately() {
        var received: AccessibilityRuntimeState? = null
        val subscription = AccessibilityRuntime.observe { received = it }
        assertNotNull(received)
        assertFalse(received!!.connected)
        assertEquals(AccessibilityPhase.DISCONNECTED, received!!.phase)
        subscription.close()
    }

    @Test
    fun connectedWithoutDependencyIsError() {
        AccessibilityRuntime.markConnected()
        AccessibilityRuntime.updatePhase(PhaseCalculator.compute(true, false, null, null))
        val state = AccessibilityRuntime.state()
        assertTrue(state.connected)
        assertEquals(AccessibilityPhase.ERROR, state.phase)
        assertEquals(GuardCode.DEPENDENCY_MISSING, state.lastErrorCode)
    }

    @Test
    fun connectedWithMasterOffIsPaused() {
        AccessibilityRuntime.markConnected()
        AccessibilityRuntime.updatePhase(
            PhaseCalculator.compute(true, true, config(master = false), Fixtures.PKG)
        )
        assertEquals(AccessibilityPhase.PAUSED, AccessibilityRuntime.state().phase)
    }

    @Test
    fun connectedWithTargetIsWatching() {
        AccessibilityRuntime.markConnected()
        AccessibilityRuntime.updatePhase(
            PhaseCalculator.compute(true, true, config(), Fixtures.PKG)
        )
        val state = AccessibilityRuntime.state()
        assertEquals(AccessibilityPhase.WATCHING, state.phase)
        assertEquals(Fixtures.PKG, state.activePackage)
    }

    @Test
    fun duplicatePhasePublicationDoesNotNotifyTwice() {
        var notifications = 0
        val subscription = AccessibilityRuntime.observe { notifications++ }
        AccessibilityRuntime.markConnected()
        AccessibilityRuntime.updatePhase(
            PhaseCalculator.compute(true, true, config(), Fixtures.PKG)
        )
        val afterFirst = notifications
        AccessibilityRuntime.updatePhase(
            PhaseCalculator.compute(true, true, config(), Fixtures.PKG)
        )
        assertEquals(afterFirst, notifications)
        subscription.close()
    }

    @Test
    fun closeIsIdempotentAndStopsFurtherNotifications() {
        var notifications = 0
        val subscription = AccessibilityRuntime.observe { notifications++ }
        assertEquals(1, notifications)
        subscription.close()
        subscription.close()
        AccessibilityRuntime.markConnected()
        assertEquals(1, notifications)
    }

    @Test
    fun listenerExceptionIsIsolated() {
        var secondListenerCalled = false
        val bad = AccessibilityRuntime.observe { throw IllegalStateException("listener boom") }
        val good = AccessibilityRuntime.observe { secondListenerCalled = true }
        AccessibilityRuntime.markConnected()
        assertTrue(secondListenerCalled)
        good.close()
        bad.close()
    }

    @Test
    fun actionRecordIsPublishedAsIs() {
        AccessibilityRuntime.markConnected()
        val record = SkipActionRecord(Fixtures.PKG, "builtin.conservative.v1", 1_234L, true)
        AccessibilityRuntime.recordAction(record)
        assertEquals(record, AccessibilityRuntime.state().lastAction)
    }

    @Test
    fun disconnectKeepsLastActionButClearsConnection() {
        AccessibilityRuntime.markConnected()
        AccessibilityRuntime.recordAction(SkipActionRecord(Fixtures.PKG, "builtin.conservative.v1", 1_234L, false))
        AccessibilityRuntime.markDisconnected()
        val state = AccessibilityRuntime.state()
        assertFalse(state.connected)
        assertEquals(AccessibilityPhase.DISCONNECTED, state.phase)
        assertNotNull(state.lastAction)
        assertFalse(state.lastAction!!.actionAccepted)
    }

    @Test
    fun interruptIsRecordedWithoutClaimingDisconnection() {
        AccessibilityRuntime.markConnected()
        AccessibilityRuntime.recordInterrupt(GuardCode.SERVICE_INTERRUPTED)
        val state = AccessibilityRuntime.state()
        assertTrue(state.connected)
        assertEquals(GuardCode.SERVICE_INTERRUPTED, state.lastErrorCode)
    }

    @Test
    fun internalErrorMovesToErrorPhase() {
        AccessibilityRuntime.markConnected()
        AccessibilityRuntime.recordError(GuardCode.INTERNAL_ERROR)
        val state = AccessibilityRuntime.state()
        assertTrue(state.connected)
        assertEquals(AccessibilityPhase.ERROR, state.phase)
        assertEquals(GuardCode.INTERNAL_ERROR, state.lastErrorCode)
    }
}
