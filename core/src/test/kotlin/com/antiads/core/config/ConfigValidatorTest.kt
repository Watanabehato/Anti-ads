package com.antiads.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配置校验边界（QA t13 要求：缺 schema、revision 边界、包名与类型集合合法性）。 */
class ConfigValidatorTest {

    @Test
    fun defaultConfigIsValidAndAllOff() {
        val config = ProtectionConfig()
        assertTrue(ConfigValidator.validate(config).isValid)
        assertFalse(config.masterEnabled)
        assertFalse(config.accessibilityEnabled)
        assertFalse(config.hookEnabled)
        assertTrue(config.packages.isEmpty())
        assertEquals(0L, config.revision)
    }

    @Test
    fun unsupportedSchemaVersionRejected() {
        val result = ConfigValidator.validate(ProtectionConfig(schemaVersion = 2))
        assertFalse(result.isValid)
        assertTrue(result.errors.contains(ConfigValidator.ERROR_UNSUPPORTED_SCHEMA))
    }

    @Test
    fun revisionBoundaries() {
        assertTrue(ConfigValidator.validate(ProtectionConfig(revision = 0L)).isValid)
        assertTrue(ConfigValidator.validate(ProtectionConfig(revision = ConfigConstants.MAX_REVISION)).isValid)
        assertFalse(ConfigValidator.validate(ProtectionConfig(revision = -1L)).isValid)
        assertFalse(ConfigValidator.validate(ProtectionConfig(revision = Long.MAX_VALUE)).isValid)
    }

    @Test
    fun packageNameSyntax() {
        assertTrue(ConfigValidator.isValidPackageName("com.antiads.probe"))
        assertTrue(ConfigValidator.isValidPackageName("a.b"))
        assertTrue(ConfigValidator.isValidPackageName("com.example.app_2"))
        assertFalse(ConfigValidator.isValidPackageName(""))
        assertFalse(ConfigValidator.isValidPackageName("   "))
        assertFalse(ConfigValidator.isValidPackageName("single"))
        assertFalse(ConfigValidator.isValidPackageName("com..double"))
        assertFalse(ConfigValidator.isValidPackageName("com.example.*"))
        assertFalse(ConfigValidator.isValidPackageName("com/example/app"))
        assertFalse(ConfigValidator.isValidPackageName("1com.example"))
        assertFalse(ConfigValidator.isValidPackageName("com.example."))
        assertFalse(ConfigValidator.isValidPackageName("a".repeat(256) + ".b"))
    }

    @Test
    fun hostAndSystemUiKeysRejected() {
        val host = ProtectionConfig(packages = mapOf("com.antiads.app" to PackageConfig()))
        assertFalse(ConfigValidator.validate(host).isValid)
        val systemUi = ProtectionConfig(packages = mapOf("com.android.systemui" to PackageConfig()))
        assertFalse(ConfigValidator.validate(systemUi).isValid)
        val androidPkg = ProtectionConfig(packages = mapOf("android" to PackageConfig()))
        assertFalse(ConfigValidator.validate(androidPkg).isValid)
    }

    @Test
    fun sensorTypesOnlyAllowedSubset() {
        val ok = ProtectionConfig(packages = mapOf("com.example.a" to PackageConfig(blockedSensorTypes = setOf(1, 11))))
        assertTrue(ConfigValidator.validate(ok).isValid)
        val empty = ProtectionConfig(packages = mapOf("com.example.a" to PackageConfig(blockedSensorTypes = emptySet())))
        assertTrue(ConfigValidator.validate(empty).isValid)
        val unknownType = ProtectionConfig(packages = mapOf("com.example.a" to PackageConfig(blockedSensorTypes = setOf(1, 5))))
        assertFalse(ConfigValidator.validate(unknownType).isValid)
        val lightSensor = ProtectionConfig(packages = mapOf("com.example.a" to PackageConfig(blockedSensorTypes = setOf(5))))
        assertFalse(ConfigValidator.validate(lightSensor).isValid)
    }

    @Test
    fun ruleIdsOnlyBuiltinOrEmpty() {
        val builtin = ProtectionConfig(
            packages = mapOf(
                "com.example.a" to PackageConfig(ruleIds = setOf(ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1))
            )
        )
        assertTrue(ConfigValidator.validate(builtin).isValid)
        val empty = ProtectionConfig(packages = mapOf("com.example.a" to PackageConfig(ruleIds = emptySet())))
        assertTrue(ConfigValidator.validate(empty).isValid)
        val custom = ProtectionConfig(packages = mapOf("com.example.a" to PackageConfig(ruleIds = setOf("custom.v1"))))
        assertFalse(ConfigValidator.validate(custom).isValid)
    }

    @Test
    fun packageCountLimitIs500() {
        val tooMany = (0 until 501).associate { "com.example.p" + it to PackageConfig() }
        assertFalse(ConfigValidator.validate(ProtectionConfig(packages = tooMany)).isValid)
        val exact = (0 until 500).associate { "com.example.p" + it to PackageConfig() }
        assertTrue(ConfigValidator.validate(ProtectionConfig(packages = exact)).isValid)
    }

    @Test
    fun errorCodesDoNotLeakInput() {
        val config = ProtectionConfig(packages = mapOf("com.example.a" to PackageConfig(blockedSensorTypes = setOf(99))))
        val errors = ConfigValidator.validate(config).errors
        assertTrue(errors.isNotEmpty())
        assertFalse(errors.any { it.contains("com.example.a") })
    }
}
