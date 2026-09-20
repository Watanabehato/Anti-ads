package com.antiads.core.policy

import com.antiads.core.config.CachedPackagePolicy
import com.antiads.core.config.PackagePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** 租约与时间边界（QA t13：4999/5000ms；contracts 第 8 节的 1000/1500/6000 场景）。 */
class SensorPolicyEngineTest {

    private val pkg = "com.antiads.probe"

    private fun policy(
        schema: Int = 1,
        revision: Long = 7L,
        enabled: Boolean = true,
        types: Set<Int> = setOf(1, 4, 9, 10, 11),
        packageName: String = pkg
    ) = PackagePolicy(
        schemaVersion = schema,
        revision = revision,
        packageName = packageName,
        hookEnabled = enabled,
        blockedSensorTypes = types
    )

    private fun cached(
        policy: PackagePolicy = policy(),
        start: Long = 1000L,
        expires: Long = 6000L
    ) = CachedPackagePolicy(policy = policy, requestStartedAtElapsedMs = start, expiresAtElapsedMs = expires)

    @Test
    fun noSnapshotAllows() {
        val decision = SensorPolicyEngine.decide(pkg, 1, null, 1000L)
        assertEquals(SensorAction.ALLOW, decision.action)
        assertEquals(SensorPolicyEngine.REASON_NO_POLICY, decision.reason)
    }

    @Test
    fun contractScenario5999DropsAnd6000Allows() {
        assertEquals(SensorAction.DROP_CALLBACK, SensorPolicyEngine.decide(pkg, 1, cached(), 5999L).action)
        val boundary = SensorPolicyEngine.decide(pkg, 1, cached(), 6000L)
        assertEquals(SensorAction.ALLOW, boundary.action)
        assertEquals(SensorPolicyEngine.REASON_EXPIRED, boundary.reason)
    }

    @Test
    fun leaseLengthBoundaries() {
        val lease4999 = cached(start = 1000L, expires = 5999L)
        assertEquals(SensorAction.DROP_CALLBACK, SensorPolicyEngine.decide(pkg, 1, lease4999, 5000L).action)

        val lease5000 = cached(start = 1000L, expires = 6000L)
        assertEquals(SensorAction.DROP_CALLBACK, SensorPolicyEngine.decide(pkg, 1, lease5000, 5999L).action)

        val lease5001 = cached(start = 1000L, expires = 6001L)
        val tooLong = SensorPolicyEngine.decide(pkg, 1, lease5001, 2000L)
        assertEquals(SensorAction.ALLOW, tooLong.action)
        assertEquals(SensorPolicyEngine.REASON_INVALID_POLICY, tooLong.reason)

        val zeroLease = cached(start = 1000L, expires = 1000L)
        assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide(pkg, 1, zeroLease, 1000L).action)
    }

    @Test
    fun notSelectedTypeAndDisabledPolicyAllow() {
        val onlyAccelerometer = cached(policy = policy(types = setOf(1)))
        val notSelected = SensorPolicyEngine.decide(pkg, 4, onlyAccelerometer, 2000L)
        assertEquals(SensorAction.ALLOW, notSelected.action)
        assertEquals(SensorPolicyEngine.REASON_TYPE_NOT_SELECTED, notSelected.reason)

        val disabled = cached(policy = policy(enabled = false, types = emptySet()))
        val decision = SensorPolicyEngine.decide(pkg, 1, disabled, 2000L)
        assertEquals(SensorAction.ALLOW, decision.action)
        assertEquals(SensorPolicyEngine.REASON_DISABLED, decision.reason)
    }

    @Test
    fun emptyTypeSetNeverDrops() {
        val cachedPolicy = cached(policy = policy(types = emptySet()))
        for (type in listOf(1, 4, 9, 10, 11)) {
            assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide(pkg, type, cachedPolicy, 2000L).action)
        }
    }

    @Test
    fun packageMismatchAllows() {
        val cachedPolicy = cached(policy = policy(packageName = "com.example.other"))
        val decision = SensorPolicyEngine.decide(pkg, 1, cachedPolicy, 2000L)
        assertEquals(SensorAction.ALLOW, decision.action)
        assertEquals(SensorPolicyEngine.REASON_PACKAGE_MISMATCH, decision.reason)
    }

    @Test
    fun unknownSchemaAllows() {
        val cachedPolicy = cached(policy = policy(schema = 2))
        val decision = SensorPolicyEngine.decide(pkg, 1, cachedPolicy, 2000L)
        assertEquals(SensorAction.ALLOW, decision.action)
        assertEquals(SensorPolicyEngine.REASON_INVALID_POLICY, decision.reason)
    }

    @Test
    fun negativeAndRollbackTimestampsAllow() {
        assertEquals(
            SensorAction.ALLOW,
            SensorPolicyEngine.decide(pkg, 1, cached(start = -1L, expires = 4000L), 1000L).action
        )
        assertEquals(
            SensorAction.ALLOW,
            SensorPolicyEngine.decide(pkg, 1, cached(start = 1000L, expires = -1L), 1000L).action
        )
        assertEquals(
            SensorAction.ALLOW,
            SensorPolicyEngine.decide(pkg, 1, cached(), -5L).action
        )
        val rollback = SensorPolicyEngine.decide(pkg, 1, cached(start = 1000L, expires = 6000L), 999L)
        assertEquals(SensorAction.ALLOW, rollback.action)
        assertEquals(SensorPolicyEngine.REASON_INVALID_POLICY, rollback.reason)
    }

    @Test
    fun validSnapshotDropsSelectedType() {
        val decision = SensorPolicyEngine.decide(pkg, 9, cached(), 1000L)
        assertEquals(SensorAction.DROP_CALLBACK, decision.action)
        assertEquals(SensorPolicyEngine.REASON_BLOCK_SELECTED_TYPE, decision.reason)
    }
}
