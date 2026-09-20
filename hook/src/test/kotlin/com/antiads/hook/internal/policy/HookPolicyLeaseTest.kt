package com.antiads.hook.internal.policy

import com.antiads.core.policy.SensorAction
import com.antiads.core.policy.SensorPolicyEngine
import com.antiads.core.protocol.ConfigProtocol
import com.antiads.core.status.ConfigTransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 租约/期限边界（contracts 第 7 节 + docs/hook-probe-observability.md 测试矩阵 M1/M2/M5/M6/M8）。
 *
 * M1  start=1000 reply=1500 → expires=6000；now=5999 丢弃、now=6000 放行。
 * M2  迟到回复（>1000ms）不发布，且不得延长旧租约。
 * M5  到期即放行（含边界）。
 * M6  错误/拒绝立即清缓存。
 * M8  只依赖注入的单调时钟，墙钟变化不影响判定。
 */
class HookPolicyLeaseTest {

    @Test
    fun publishesLeaseFromRequestStartAndRespectsExpiryBoundary() {
        val clock = FakeClock(1000L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.enqueue {
            clock.set(1500L) // 回复到达：耗时 500ms ≤ 1000ms
            PolicyFetchOutcome.Reply(true, policyJson(revision = 7L), null)
        }
        val client = core(clock, scheduler, transport)

        client.onSensorCallback(1, 1000L)
        scheduler.runAll()

        val snapshot = client.snapshot()
        assertNotNull(snapshot.cachedPolicy)
        assertEquals(1000L, snapshot.cachedPolicy!!.requestStartedAtElapsedMs)
        assertEquals(6000L, snapshot.cachedPolicy!!.expiresAtElapsedMs)
        assertEquals(ConfigTransportState.OK, snapshot.transportState)
        assertNull(snapshot.lastErrorCode)
        assertEquals(7L, snapshot.cachedPolicy!!.policy.revision)

        val beforeExpiry = client.onSensorCallback(1, 5999L)
        assertEquals(SensorAction.DROP_CALLBACK, beforeExpiry.action)
        assertEquals(SensorPolicyEngine.REASON_BLOCK_SELECTED_TYPE, beforeExpiry.reason)

        val atExpiry = client.onSensorCallback(1, 6000L)
        assertEquals(SensorAction.ALLOW, atExpiry.action)
        assertEquals(SensorPolicyEngine.REASON_EXPIRED, atExpiry.reason)

        val counters = client.snapshot()
        assertEquals(3L, counters.observedCallbacks)
        assertEquals(1L, counters.droppedCallbacks)
        assertTrue(counters.droppedCallbacks <= counters.observedCallbacks)
    }

    @Test
    fun lateReplyIsDiscardedAndDoesNotExtendExistingLease() {
        val clock = FakeClock(1000L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.enqueue {
            clock.set(1500L)
            PolicyFetchOutcome.Reply(true, policyJson(revision = 3L), null)
        }
        val client = core(clock, scheduler, transport)

        client.onSensorCallback(2, 1000L)
        scheduler.runAll()
        assertEquals(6000L, client.snapshot().cachedPolicy!!.expiresAtElapsedMs)

        // 第二次读取：起点 5000、回复 6500（耗时 1500ms > 1000ms）→ 必须丢弃
        transport.enqueue {
            clock.set(6500L)
            PolicyFetchOutcome.Reply(true, policyJson(revision = 9L), null)
        }
        client.onSensorCallback(2, 5000L)
        scheduler.runAll()

        val snapshot = client.snapshot()
        assertNotNull(snapshot.cachedPolicy)
        assertEquals("迟到回复不得发布新版本", 3L, snapshot.cachedPolicy!!.policy.revision)
        assertEquals("迟到回复不得延长租约", 6000L, snapshot.cachedPolicy!!.expiresAtElapsedMs)
        assertEquals(ConfigTransportState.EXPIRED, snapshot.transportState)
        assertEquals(HookTransportCodes.REPLY_DEADLINE_EXCEEDED, snapshot.lastErrorCode)

        assertEquals(SensorAction.ALLOW, client.onSensorCallback(2, 6000L).action)
    }

    @Test
    fun lateReplyWithoutExistingCacheAllowsImmediately() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.enqueue {
            clock.set(2001L)
            PolicyFetchOutcome.Reply(true, policyJson(), null)
        }
        val client = core(clock, scheduler, transport)

        client.onSensorCallback(1, 0L)
        scheduler.runAll()

        val snapshot = client.snapshot()
        assertNull(snapshot.cachedPolicy)
        assertEquals(ConfigTransportState.EXPIRED, snapshot.transportState)
        assertEquals(HookTransportCodes.REPLY_DEADLINE_EXCEEDED, snapshot.lastErrorCode)
        assertEquals(SensorAction.ALLOW, client.onSensorCallback(1, 5000L).action)
    }

    @Test
    fun rejectedReadClearsCacheImmediatelyAndKeepsFailingOpen() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val client = core(clock, scheduler, transport)

        client.onSensorCallback(1, 0L)
        scheduler.runAll()
        assertEquals(SensorAction.DROP_CALLBACK, client.onSensorCallback(1, 0L).action)

        transport.onFetch = {
            PolicyFetchOutcome.Reply(false, null, ConfigProtocol.ERROR_UNAUTHORIZED)
        }
        clock.set(3000L)
        client.onSensorCallback(1, 3000L)
        scheduler.runAll()

        val snapshot = client.snapshot()
        assertNull("拒绝必须立即清空缓存", snapshot.cachedPolicy)
        assertEquals(ConfigTransportState.UNAUTHORIZED, snapshot.transportState)
        assertEquals(ConfigProtocol.ERROR_UNAUTHORIZED, snapshot.lastErrorCode)
        assertEquals(SensorAction.ALLOW, client.onSensorCallback(1, 3001L).action)
    }

    @Test
    fun providerUnreachableClearsCacheAndNeverBlocksSensors() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val client = core(clock, scheduler, transport)
        client.onSensorCallback(1, 0L)
        scheduler.runAll()

        transport.onFetch = { PolicyFetchOutcome.Failed(HookTransportCodes.UNAVAILABLE) }
        clock.set(2000L)
        client.onSensorCallback(1, 2000L)
        scheduler.runAll()

        val snapshot = client.snapshot()
        assertNull(snapshot.cachedPolicy)
        assertEquals(ConfigTransportState.UNAVAILABLE, snapshot.transportState)
        assertEquals(HookTransportCodes.UNAVAILABLE, snapshot.lastErrorCode)
        assertEquals(SensorAction.ALLOW, client.onSensorCallback(1, 2001L).action)
    }

    @Test
    fun clockRewindNeverDropsAndIsReportedAsInvalid() {
        val clock = FakeClock(1000L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val client = core(clock, scheduler, transport)
        client.onSensorCallback(1, 1000L)
        scheduler.runAll()

        // 时钟回退：SensorPolicyEngine 视为不可靠 → 必须放行
        val decision = client.onSensorCallback(1, 500L)
        assertEquals(SensorAction.ALLOW, decision.action)
        assertEquals(SensorPolicyEngine.REASON_INVALID_POLICY, decision.reason)

        // 回复时间戳早于请求起点：不发布并清空
        transport.onFetch = {
            clock.set(3900L) // 回复时刻"早于"请求起点（起点在 runFetch 开始时为 5000）
            PolicyFetchOutcome.Reply(true, policyJson(revision = 11L), null)
        }
        clock.set(5000L)
        client.onSensorCallback(1, 5000L)
        scheduler.runAll()
        val snapshot = client.snapshot()
        assertNull(snapshot.cachedPolicy)
        assertEquals(ConfigTransportState.INVALID, snapshot.transportState)
        assertEquals(HookTransportCodes.TIME_REWIND, snapshot.lastErrorCode)
    }

    @Test
    fun decisionsIgnoreWallClockChangesAndKeepDroppingWhileLeaseValid() {
        var wallClockMs = 1_600_000_000_000L
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val client = core(clock, scheduler, transport)
        client.onSensorCallback(1, 0L)
        scheduler.runAll()

        val first = client.onSensorCallback(1, 1000L)
        wallClockMs += 365L * 24L * 60L * 60L * 1000L // 墙钟前进一年
        val second = client.onSensorCallback(1, 1000L)

        assertEquals(SensorAction.DROP_CALLBACK, first.action)
        assertEquals(first.action, second.action)
        assertEquals(first.reason, second.reason)
        assertTrue(wallClockMs > 1_600_000_000_000L)
    }
}
