package com.antiads.hook.internal.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 策略 JSON 解码与校验边界（坏 JSON、未知 schema、包名不符、类型越界、超长）。 */
class PolicyJsonTest {

    private val expected = "com.antiads.probe"

    @Test
    fun acceptsValidPolicyAndIgnoresUnknownFields() {
        val policy = PolicyJson.decodePolicy(
            policyJson(revision = 42L, extraRawJson = "\"futureField\":1"),
            expected
        )
        assertNotNull(policy)
        assertEquals(42L, policy!!.revision)
        assertEquals(setOf(1, 4, 9, 10, 11), policy.blockedSensorTypes)
    }

    @Test
    fun acceptsDisabledPolicyWithEmptyTypeSet() {
        val policy = PolicyJson.decodePolicy(
            policyJson(hookEnabled = false, blockedSensorTypes = emptySet()),
            expected
        )
        assertNotNull(policy)
        assertEquals(false, policy!!.hookEnabled)
        assertEquals(emptySet<Int>(), policy.blockedSensorTypes)
    }

    @Test
    fun rejectsPackageMismatch() {
        assertNull(PolicyJson.decodePolicy(policyJson(packageName = "com.other.target"), expected))
    }

    @Test
    fun rejectsMissingOrUnknownSchemaVersion() {
        val withoutVersion = "{\"revision\":1,\"packageName\":\"com.antiads.probe\"," +
            "\"hookEnabled\":true,\"blockedSensorTypes\":[]}"
        assertNull(PolicyJson.decodePolicy(withoutVersion, expected))
        assertNull(PolicyJson.decodePolicy(policyJson(schemaVersion = 2), expected))
    }

    @Test
    fun rejectsBrokenJsonAndWrongTypes() {
        assertNull(PolicyJson.decodePolicy("{not-json", expected))
        val wrongType = "{\"schemaVersion\":1,\"revision\":\"seven\",\"packageName\":\"com.antiads.probe\"," +
            "\"hookEnabled\":true,\"blockedSensorTypes\":[]}"
        assertNull(PolicyJson.decodePolicy(wrongType, expected))
    }

    @Test
    fun rejectsSensorTypeOutsideAllowedSet() {
        assertNull(PolicyJson.decodePolicy(policyJson(blockedSensorTypes = listOf(5)), expected))
        assertNull(PolicyJson.decodePolicy(policyJson(blockedSensorTypes = listOf(1, 4, 9, 10, 11, 2)), expected))
    }

    @Test
    fun rejectsPayloadLargerThanProtocolLimit() {
        val padding = "x".repeat(5000)
        val oversized = policyJson(extraRawJson = "\"pad\":\"" + padding + "\"")
        assertNull(PolicyJson.decodePolicy(oversized, expected))
    }
}
