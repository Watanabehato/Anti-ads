package com.antiads.accessibility

import com.antiads.core.config.PackageConfig
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.status.AccessibilityPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 状态阶段：纯配置不能令 connected=true；依赖缺失必须是 ERROR 而不是假成功。 */
class PhaseCalculatorTest {

    private fun config(
        master: Boolean,
        accessibility: Boolean,
        packageEnabled: Boolean?
    ): ProtectionConfig = ProtectionConfig(
        schemaVersion = 1,
        revision = 3L,
        masterEnabled = master,
        accessibilityEnabled = accessibility,
        hookEnabled = false,
        packages = if (packageEnabled == null) {
            emptyMap()
        } else {
            mapOf(Fixtures.PKG to PackageConfig(accessibilityEnabled = packageEnabled))
        }
    )

    @Test
    fun disconnected() {
        val result = PhaseCalculator.compute(
            connected = false,
            dependencyInstalled = true,
            config = config(true, true, true),
            foregroundPackage = Fixtures.PKG
        )
        assertEquals(AccessibilityPhase.DISCONNECTED, result.phase)
        assertNull(result.activePackage)
        assertNull(result.errorCode)
    }

    @Test
    fun missingDependencyIsError() {
        val result = PhaseCalculator.compute(
            connected = true,
            dependencyInstalled = false,
            config = null,
            foregroundPackage = Fixtures.PKG
        )
        assertEquals(AccessibilityPhase.ERROR, result.phase)
        assertEquals(GuardCode.DEPENDENCY_MISSING, result.errorCode)
        assertNull(result.activePackage)
    }

    @Test
    fun missingConfigIsError() {
        val result = PhaseCalculator.compute(
            connected = true,
            dependencyInstalled = true,
            config = null,
            foregroundPackage = Fixtures.PKG
        )
        assertEquals(AccessibilityPhase.ERROR, result.phase)
        assertEquals(GuardCode.CONFIG_UNAVAILABLE, result.errorCode)
    }

    @Test
    fun masterSwitchOffIsPaused() {
        val result = PhaseCalculator.compute(true, true, config(false, true, true), Fixtures.PKG)
        assertEquals(AccessibilityPhase.PAUSED, result.phase)
        assertNull(result.activePackage)
    }

    @Test
    fun globalAccessibilityOffIsPaused() {
        val result = PhaseCalculator.compute(true, true, config(true, false, true), Fixtures.PKG)
        assertEquals(AccessibilityPhase.PAUSED, result.phase)
    }

    @Test
    fun noForegroundPackageIsIdle() {
        val result = PhaseCalculator.compute(true, true, config(true, true, true), null)
        assertEquals(AccessibilityPhase.IDLE, result.phase)
        assertNull(result.activePackage)
    }

    @Test
    fun unselectedPackageIsIdle() {
        val result = PhaseCalculator.compute(true, true, config(true, true, null), Fixtures.PKG)
        assertEquals(AccessibilityPhase.IDLE, result.phase)
        assertNull(result.activePackage)
    }

    @Test
    fun packageSwitchOffIsIdle() {
        val result = PhaseCalculator.compute(true, true, config(true, true, false), Fixtures.PKG)
        assertEquals(AccessibilityPhase.IDLE, result.phase)
    }

    @Test
    fun qualifyingTargetIsWatching() {
        val result = PhaseCalculator.compute(true, true, config(true, true, true), Fixtures.PKG)
        assertEquals(AccessibilityPhase.WATCHING, result.phase)
        assertEquals(Fixtures.PKG, result.activePackage)
        assertNull(result.errorCode)
    }
}
