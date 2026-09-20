package com.antiads.core.policy

import com.antiads.core.config.CachedPackagePolicy
import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.PackageConfig
import com.antiads.core.config.PolicyResolver
import com.antiads.core.config.ProtectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 作用范围、停用与恢复约定（docs/contracts.md 第 2、4 节）。
 *
 * 重点：未被选择的应用、未选择的传感器类型、关闭的开关与过期租约一律“保持原始行为”，
 * 且不得把“没有策略”解释成“已激活”。
 */
class SensorPolicyScopeTest {

    private val adsApp = "com.example.ads"
    private val motionApp = "com.example.sport"

    private fun config(
        master: Boolean = true,
        hookGlobal: Boolean = true,
        adsHook: Boolean = true,
        adsTypes: Set<Int> = setOf(1, 4, 9, 10, 11)
    ) = ProtectionConfig(
        revision = 9L,
        masterEnabled = master,
        accessibilityEnabled = false,
        hookEnabled = hookGlobal,
        packages = mapOf(
            adsApp to PackageConfig(
                accessibilityEnabled = false,
                hookEnabled = adsHook,
                blockedSensorTypes = adsTypes
            )
        )
    )

    private fun cachedFor(packageName: String, cfg: ProtectionConfig, start: Long = 1_000L, lease: Long = 5_000L): CachedPackagePolicy {
        val policy = PolicyResolver.packagePolicy(cfg, packageName)
        return CachedPackagePolicy(policy = policy, requestStartedAtElapsedMs = start, expiresAtElapsedMs = start + lease)
    }

    @Test
    fun allowedMotionAppKeepsReceivingCallbacks() {
        // 运动应用未出现在配置中：既没有策略，也不应被拦截
        val cached = cachedFor(motionApp, config())
        for (type in listOf(1, 4, 9, 10, 11, 8, 18, 19)) {
            val decision = SensorPolicyEngine.decide(motionApp, type, cached, 2_000L)
            assertEquals(SensorAction.ALLOW, decision.action)
        }
    }

    @Test
    fun userKeptMotionAppOffEvenWhenConfigured() {
        // 用户显式给运动应用留空（不启用 Hook）时同样保持原始行为
        val cfg = ProtectionConfig(
            revision = 9L,
            masterEnabled = true,
            hookEnabled = true,
            packages = mapOf(
                adsApp to PackageConfig(hookEnabled = true, blockedSensorTypes = setOf(1)),
                motionApp to PackageConfig(hookEnabled = false, blockedSensorTypes = setOf(1, 4, 9, 10, 11))
            )
        )
        val cached = cachedFor(motionApp, cfg)
        assertFalse(cached.policy.hookEnabled)
        assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide(motionApp, 1, cached, 2_000L).action)
    }

    @Test
    fun unselectedSensorTypesKeepWorking() {
        val cached = cachedFor(adsApp, config(adsTypes = setOf(1, 4)))
        // 未选择的类型（重力/线性加速度/旋转矢量/光线/距离/计步）必须继续回调
        for (type in listOf(9, 10, 11, 5, 8, 18, 19)) {
            val decision = SensorPolicyEngine.decide(adsApp, type, cached, 2_000L)
            assertEquals(SensorAction.ALLOW, decision.action)
            assertEquals(SensorPolicyEngine.REASON_TYPE_NOT_SELECTED, decision.reason)
        }
        // 选择的类型仍然生效
        assertEquals(SensorAction.DROP_CALLBACK, SensorPolicyEngine.decide(adsApp, 4, cached, 2_000L).action)
    }

    @Test
    fun masterOrGlobalOffMeansOriginalBehaviour() {
        val masterOff = cachedFor(adsApp, config(master = false))
        assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide(adsApp, 1, masterOff, 2_000L).action)
        assertEquals(SensorPolicyEngine.REASON_DISABLED, SensorPolicyEngine.decide(adsApp, 1, masterOff, 2_000L).reason)

        val globalOff = cachedFor(adsApp, config(hookGlobal = false))
        assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide(adsApp, 1, globalOff, 2_000L).action)
    }

    @Test
    fun emptySensorSelectionKeepsOriginalBehaviour() {
        val cached = cachedFor(adsApp, config(adsTypes = emptySet()))
        for (type in ConfigConstants.ALLOWED_SENSOR_TYPES) {
            assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide(adsApp, type, cached, 2_000L).action)
        }
    }

    @Test
    fun leaseExpiryAlwaysFallsBackToAllow() {
        val cached = cachedFor(adsApp, config(), start = 1_000L, lease = 5_000L)
        assertEquals(SensorAction.DROP_CALLBACK, SensorPolicyEngine.decide(adsApp, 1, cached, 5_999L).action)
        val expired = SensorPolicyEngine.decide(adsApp, 1, cached, 6_000L)
        assertEquals(SensorAction.ALLOW, expired.action)
        assertEquals(SensorPolicyEngine.REASON_EXPIRED, expired.reason)
    }

    @Test
    fun wrongOrMissingPackageNeverDrops() {
        val cached = cachedFor(adsApp, config())
        assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide("com.example.other", 1, cached, 2_000L).action)
        assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide("", 1, cached, 2_000L).action)
        assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide(adsApp, -1, cached, 2_000L).action)
        assertEquals(SensorAction.ALLOW, SensorPolicyEngine.decide(adsApp, 1, null, 2_000L).action)
    }
}
