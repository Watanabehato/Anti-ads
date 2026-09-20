package com.antiads.hook.internal.policy

import com.antiads.core.policy.SensorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡住的 Binder 调用（矩阵 M3）：单一 worker、线程数恒定、请求不堆积、回调路径不阻塞。
 *
 * 真实场景：ContentResolver.call 没有可保证取消的通用超时；此时绝不能新建替代线程或堆积请求，
 * 租约在原 expires 处独立过期并放行。
 */
class HookStuckTransportTest {

    @Test
    fun stuckRequestKeepsOneWorkerThreadAndNeverPilesUp() {
        val clock = FakeClock(0L)
        val worker = SingleThreadWorker("antiads-hook-test-worker")
        val release = CountDownLatch(1)
        var fetchCalls = 0

        val transport = PolicyTransport {
            fetchCalls += 1
            release.await(10, TimeUnit.SECONDS)
            PolicyFetchOutcome.Failed(HookTransportCodes.UNAVAILABLE)
        }
        val client = HookPolicyClientCore(
            packageName = "com.antiads.probe",
            clock = { clock.nowMs },
            scheduler = { task -> worker.submit(task) },
            transport = transport,
            decoder = { json, expected -> PolicyJson.decodePolicy(json, expected) },
            reportSink = {},
            reportContext = { testReportContext() }
        )

        try {
            client.onSensorCallback(1, 0L)
            awaitUntil(timeoutMs = 5000L) { fetchCalls == 1 }

            val startedAtNanos = System.nanoTime()
            var drops = 0
            for (step in 1..20) {
                clock.set(step * 1000L)
                val decision = client.onSensorCallback(1, step * 1000L)
                if (decision.action == SensorAction.DROP_CALLBACK) drops += 1
            }
            val callbackElapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

            assertTrue("回调路径不得等待卡住的后台读取（实际 " + callbackElapsedMs + "ms）", callbackElapsedMs < 500L)
            assertEquals("卡住期间绝不发起第二个请求（队列最多一个）", 1, fetchCalls)
            assertEquals("绝不新建替代线程", 1, worker.createdThreadCount())
            assertEquals("无可用策略时必须全部放行", 0, drops)
            assertTrue("无策略时不得累计丢弃", client.snapshot().droppedCallbacks == 0L)

            release.countDown()
            awaitUntil(timeoutMs = 5000L) { !client.snapshot().inFlight }
            assertEquals("恢复后仍然只有一个 worker 线程", 1, worker.createdThreadCount())
            assertEquals("卡住被解除后仍然只有一次读取", 1, fetchCalls)
        } finally {
            release.countDown()
            worker.close()
        }
    }

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        throw AssertionError("等待条件超时")
    }
}
