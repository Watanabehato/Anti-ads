package com.antiads.hook.internal.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 刷新节流与"无流量不轮询"（矩阵 M4/M7）。
 *
 * M4 回调 t=0,1,999,1999,2000,2001 → 只在 t=0 与 t=2000 各发起一次读取（CAS 节流）。
 * M7 取得 Context 后只有一次初始读取；没有传感器流量时不再有任何访问（不存在定时轮询）。
 */
class HookRefreshThrottleTest {

    @Test
    fun refreshIsThrottledToAtMostOnePerTwoSeconds() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val client = core(clock, scheduler, transport)

        for (timestamp in listOf(0L, 1L, 999L, 1999L, 2000L, 2001L)) {
            clock.set(timestamp)
            client.onSensorCallback(1, timestamp)
            scheduler.runAll()
        }

        assertEquals("2 秒节流内只允许两次读取", 2, transport.calls)
    }

    @Test
    fun noSensorTrafficMeansNoFurtherProviderAccess() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val client = core(clock, scheduler, transport)

        client.requestInitialFetch()
        scheduler.runAll()
        assertEquals(1, transport.calls)

        // 30 秒流逝但没有任何 sensor 回调：不得有任何轮询访问
        for (second in 1..30) {
            clock.set(second * 1000L)
        }
        scheduler.runAll()
        assertEquals("空闲进程不轮询", 1, transport.calls)

        // 新回调到来时才唤醒刷新（距上次发起 ≥2000ms）
        client.onSensorCallback(1, clock.nowMs)
        scheduler.runAll()
        assertEquals(2, transport.calls)
    }

    @Test
    fun firstFetchIsNotThrottledByInitialRead() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val client = core(clock, scheduler, transport)

        client.requestInitialFetch()
        val firstSubmission = client.snapshot().fetchSubmissions

        // 初始读取仍在进行时再次请求：绝不并发第二个请求（最多一个 in-flight）
        client.requestInitialFetch()
        assertEquals(firstSubmission, client.snapshot().fetchSubmissions)

        scheduler.runAll()
        assertEquals(1, transport.calls)
    }
}
