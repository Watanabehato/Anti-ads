package com.antiads.core.config

import com.antiads.core.status.ConfigTransportState
import com.antiads.core.status.HookInstallState
import com.antiads.core.status.HookProcessReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON 编解码边界（QA t13 要求：缺 schema、坏 JSON、未知版本/字段与越界数据）。 */
class ConfigCodecTest {

    private val enabledConfig = ProtectionConfig(
        revision = 7,
        masterEnabled = true,
        accessibilityEnabled = true,
        hookEnabled = true,
        packages = mapOf(
            "com.antiads.probe" to PackageConfig(
                accessibilityEnabled = true,
                hookEnabled = true,
                blockedSensorTypes = setOf(1, 4, 9, 10, 11),
                ruleIds = setOf(ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1)
            )
        )
    )

    private val missingSchemaJson =
        """{"revision":1,"masterEnabled":true,"accessibilityEnabled":true,"hookEnabled":true,"packages":{}}"""

    @Test
    fun configRoundTripKeepsFieldsAndDefaults() {
        val json = ConfigCodec.encodeConfig(enabledConfig)
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"masterEnabled\":true"))
        assertEquals(enabledConfig, ConfigCodec.decodeConfig(json))
    }

    @Test
    fun defaultConfigRoundTrip() {
        val decoded = ConfigCodec.decodeConfig(ConfigCodec.encodeConfig(ProtectionConfig()))
        assertEquals(ProtectionConfig(), decoded)
        assertFalse(decoded.masterEnabled)
        assertFalse(decoded.hookEnabled)
    }

    @Test
    fun missingSchemaVersionIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConfigCodec.decodeConfig(missingSchemaJson)
        }
        assertEquals("UNSUPPORTED_SCHEMA_VERSION", error.message)
    }

    @Test
    fun unknownSchemaVersionIsRejected() {
        val json =
            """{"schemaVersion":2,"revision":0,"masterEnabled":false,"accessibilityEnabled":false,"hookEnabled":false,"packages":{}}"""
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeConfig(json) }
    }

    @Test
    fun brokenJsonIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeConfig("{ not json") }
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeConfig("[1,2,3]") }
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeConfig("") }
    }

    @Test
    fun wrongFieldTypeIsRejected() {
        val json =
            """{"schemaVersion":1,"revision":"abc","masterEnabled":false,"accessibilityEnabled":false,"hookEnabled":false,"packages":{}}"""
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeConfig(json) }
    }

    @Test
    fun invalidPackageNameAndSensorTypeAreRejected() {
        val badName =
            """{"schemaVersion":1,"revision":0,"masterEnabled":true,"accessibilityEnabled":false,"hookEnabled":false,"packages":{"bad name":{"accessibilityEnabled":true,"hookEnabled":false,"blockedSensorTypes":[1],"ruleIds":[]}}}"""
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeConfig(badName) }

        val badSensor =
            """{"schemaVersion":1,"revision":0,"masterEnabled":true,"accessibilityEnabled":false,"hookEnabled":true,"packages":{"com.example.a":{"accessibilityEnabled":false,"hookEnabled":true,"blockedSensorTypes":[5],"ruleIds":[]}}}"""
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeConfig(badSensor) }
    }

    @Test
    fun unknownExtraFieldIsIgnored() {
        val json =
            """{"schemaVersion":1,"revision":3,"masterEnabled":false,"accessibilityEnabled":false,"hookEnabled":false,"packages":{},"futureKey":"x"}"""
        assertEquals(3L, ConfigCodec.decodeConfig(json).revision)
    }

    @Test
    fun oversizedPayloadIsRejected() {
        val huge = "x".repeat(ConfigConstants.MAX_CONFIG_JSON_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeConfig(huge) }
    }

    @Test
    fun policyEncodeDecodeAndValidation() {
        val policy = PackagePolicy(
            schemaVersion = 1,
            revision = 7,
            packageName = "com.antiads.probe",
            hookEnabled = true,
            blockedSensorTypes = setOf(1, 10)
        )
        assertEquals(policy, ConfigCodec.decodePolicy(ConfigCodec.encodePolicy(policy)))

        val missingSchema = """{"revision":7,"packageName":"com.antiads.probe","hookEnabled":true,"blockedSensorTypes":[1]}"""
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodePolicy(missingSchema) }

        val badPackage = """{"schemaVersion":1,"revision":7,"packageName":"probe","hookEnabled":true,"blockedSensorTypes":[1]}"""
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodePolicy(badPackage) }

        val badType = """{"schemaVersion":1,"revision":7,"packageName":"com.antiads.probe","hookEnabled":true,"blockedSensorTypes":[5]}"""
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodePolicy(badType) }
    }

    @Test
    fun reportEncodeDecodeAndBounds() {
        val valid = HookProcessReport(
            packageName = "com.antiads.probe",
            processName = "com.antiads.probe",
            processToken = "123e4567-e89b-12d3-a456-426614174000",
            pid = 4242,
            apiLevel = 29,
            installState = HookInstallState.INSTALLED,
            transportState = ConfigTransportState.OK,
            policyRevision = 7L,
            observedCallbacks = 120L,
            droppedCallbacks = 30L
        )
        assertEquals(valid, ConfigCodec.decodeHookReport(ConfigCodec.encodeHookReport(valid)))

        val unknownEnum = ConfigCodec.encodeHookReport(valid).replace("INSTALLED", "SOMETHING_ELSE")
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeHookReport(unknownEnum) }

        val badToken = valid.copy(processToken = "not-a-uuid")
        assertThrows(IllegalArgumentException::class.java) {
            ConfigCodec.decodeHookReport(ConfigCodec.encodeHookReport(badToken))
        }

        val badPid = valid.copy(pid = 0)
        assertThrows(IllegalArgumentException::class.java) {
            ConfigCodec.decodeHookReport(ConfigCodec.encodeHookReport(badPid))
        }

        val badCounters = valid.copy(observedCallbacks = 1L, droppedCallbacks = 5L)
        assertThrows(IllegalArgumentException::class.java) {
            ConfigCodec.decodeHookReport(ConfigCodec.encodeHookReport(badCounters))
        }

        val noSchema = """{"packageName":"com.antiads.probe","processName":"com.antiads.probe","processToken":"123e4567-e89b-12d3-a456-426614174000","pid":42,"apiLevel":29,"installState":"INSTALLED","transportState":"OK","policyRevision":7,"observedCallbacks":1,"droppedCallbacks":0}"""
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decodeHookReport(noSchema) }
    }
}
