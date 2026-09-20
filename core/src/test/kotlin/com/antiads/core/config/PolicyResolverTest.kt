package com.antiads.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开关交集（QA t13 要求）：只有 masterEnabled && 全局模式 && 当前包模式才为 true。 */
class PolicyResolverTest {

    private fun config(
        master: Boolean,
        accessGlobal: Boolean,
        hookGlobal: Boolean,
        packageAccess: Boolean,
        packageHook: Boolean,
        revision: Long = 5L,
        sensorTypes: Set<Int> = setOf(1, 4, 9, 10, 11)
    ) = ProtectionConfig(
        revision = revision,
        masterEnabled = master,
        accessibilityEnabled = accessGlobal,
        hookEnabled = hookGlobal,
        packages = mapOf(
            "com.example.target" to PackageConfig(
                accessibilityEnabled = packageAccess,
                hookEnabled = packageHook,
                blockedSensorTypes = sensorTypes
            )
        )
    )

    @Test
    fun allThreeSwitchesOnMeansEnabled() {
        val cfg = config(true, true, true, true, true)
        assertTrue(PolicyResolver.accessibilityEnabled(cfg, "com.example.target"))
        assertTrue(PolicyResolver.packagePolicy(cfg, "com.example.target").hookEnabled)
    }

    @Test
    fun anySwitchOffMeansDisabled() {
        assertFalse(PolicyResolver.accessibilityEnabled(config(false, true, true, true, true), "com.example.target"))
        assertFalse(PolicyResolver.accessibilityEnabled(config(true, false, true, true, true), "com.example.target"))
        assertFalse(PolicyResolver.accessibilityEnabled(config(true, true, true, false, true), "com.example.target"))
        assertFalse(PolicyResolver.packagePolicy(config(false, true, true, true, true), "com.example.target").hookEnabled)
        assertFalse(PolicyResolver.packagePolicy(config(true, true, false, true, true), "com.example.target").hookEnabled)
        assertFalse(PolicyResolver.packagePolicy(config(true, true, true, true, false), "com.example.target").hookEnabled)
    }

    @Test
    fun unknownPackageIsAllOff() {
        val cfg = config(true, true, true, true, true)
        assertFalse(PolicyResolver.accessibilityEnabled(cfg, "com.example.other"))
        val policy = PolicyResolver.packagePolicy(cfg, "com.example.other")
        assertFalse(policy.hookEnabled)
        assertTrue(policy.blockedSensorTypes.isEmpty())
    }

    @Test
    fun disabledPolicyKeepsRealRevision() {
        // 关闭“全局 Hook 开关”（第三个参数），而不是无障碍全局开关
        val policy = PolicyResolver.packagePolicy(
            config(true, true, false, true, true, revision = 12L),
            "com.example.target"
        )
        assertFalse(policy.hookEnabled)
        assertTrue(policy.blockedSensorTypes.isEmpty())
        assertEquals(12L, policy.revision)
        assertEquals("com.example.target", policy.packageName)
        assertEquals(1, policy.schemaVersion)
    }

    @Test
    fun minimalPolicyHasNoOtherPackages() {
        val cfg = ProtectionConfig(
            revision = 3L,
            masterEnabled = true,
            accessibilityEnabled = true,
            hookEnabled = true,
            packages = mapOf(
                "com.example.target" to PackageConfig(hookEnabled = true, blockedSensorTypes = setOf(1)),
                "com.example.other" to PackageConfig(hookEnabled = true, blockedSensorTypes = setOf(4))
            )
        )
        val policy = PolicyResolver.packagePolicy(cfg, "com.example.target")
        assertEquals(setOf(1), policy.blockedSensorTypes)
        assertFalse(ConfigCodec.encodePolicy(policy).contains("com.example.other"))
    }
}
