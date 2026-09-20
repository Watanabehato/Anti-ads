package com.antiads.hook.internal.policy

import com.antiads.core.policy.SensorAction
import com.antiads.core.policy.SensorPolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败即放行与"关闭/排除目标后恢复正常"（合同第 4、7 节；矩阵 M9/M11）。
 *
 * 这些是安全属性：任何不确定情况都不允许丢弃回调，也不允许改写传感器数据。
 */
class FailOpenDecisionTest {

    @Test
    fun noPolicyMeansEverySensorTypeIsAllowed() {
        val client = core(FakeClock(0L), RecordingScheduler(), FakeTransport())
        for (sensorType in 1..40) {
            val decision = client.onSensorCallback(sensorType, 0L)
            assertEquals(SensorAction.ALLOW, decision.action)
            assertEquals(SensorPolicyEngine.REASON_NO_POLICY, decision.reason)
        }
        assertEquals(0L, client.snapshot().droppedCallbacks)
        assertEquals(40L, client.snapshot().observedCallbacks)
    }

    @Test
    fun onlySelectedTypesAreDroppedWhilePolicyEnabled() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = {
            PolicyFetchOutcome.Reply(true, policyJson(blockedSensorTypes = setOf(1, 4)), null)
        }
        val client = core(clock, scheduler, transport)
        client.onSensorCallback(1, 0L)
        scheduler.runAll()

        assertEquals(SensorAction.DROP_CALLBACK, client.onSensorCallback(1, 10L).action)
        assertEquals(SensorAction.DROP_CALLBACK, client.onSensorCallback(4, 10L).action)
        assertEquals(SensorAction.ALLOW, client.onSensorCallback(9, 10L).action)
        assertEquals(
            SensorPolicyEngine.REASON_TYPE_NOT_SELECTED,
            client.onSensorCallback(9, 10L).reason
        )
    }

    @Test
    fun disablingHookRestoresFutureCallbacks() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val client = core(clock, scheduler, transport)
        client.onSensorCallback(1, 0L)
        scheduler.runAll()
        assertEquals(SensorAction.DROP_CALLBACK, client.onSensorCallback(1, 100L).action)

        // 管理端关闭总开关：Provider 返回 hookEnabled=false 且类型集合为空
        transport.onFetch = {
            PolicyFetchOutcome.Reply(
                true,
                policyJson(revision = 8L, hookEnabled = false, blockedSensorTypes = emptySet()),
                null
            )
        }
        clock.set(2000L)
        client.onSensorCallback(1, 2000L)
        scheduler.runAll()

        val decision = client.onSensorCallback(1, 2100L)
        assertEquals(SensorAction.ALLOW, decision.action)
        assertEquals(SensorPolicyEngine.REASON_DISABLED, decision.reason)
        assertNotNull(client.snapshot().cachedPolicy)
        assertEquals(8L, client.snapshot().cachedPolicy!!.policy.revision)
    }

    @Test
    fun emptyTypeSelectionNeverDrops() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = {
            PolicyFetchOutcome.Reply(true, policyJson(blockedSensorTypes = emptySet()), null)
        }
        val client = core(clock, scheduler, transport)
        client.onSensorCallback(1, 0L)
        scheduler.runAll()

        for (sensorType in listOf(1, 4, 9, 10, 11)) {
            assertEquals(SensorAction.ALLOW, client.onSensorCallback(sensorType, 100L).action)
        }
        assertEquals(0L, client.snapshot().droppedCallbacks)
    }

    @Test
    fun policyForAnotherPackageIsNeverApplied() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = {
            PolicyFetchOutcome.Reply(true, policyJson(packageName = "com.other.target"), null)
        }
        val client = core(clock, scheduler, transport)
        client.onSensorCallback(1, 0L)
        scheduler.runAll()

        assertEquals(SensorAction.ALLOW, client.onSensorCallback(1, 100L).action)
        assertEquals(null, client.snapshot().cachedPolicy)
    }

    @Test
    fun typeResolveFailuresAreCountedAndNeverDrop() {
        val client = core(FakeClock(0L), RecordingScheduler(), FakeTransport())
        repeat(5) { client.recordTypeResolveFailure() }
        val snapshot = client.snapshot()
        assertEquals(5L, snapshot.typeResolveFailures)
        assertEquals(0L, snapshot.droppedCallbacks)
        assertTrue(snapshot.observedCallbacks == 0L)
    }
}
