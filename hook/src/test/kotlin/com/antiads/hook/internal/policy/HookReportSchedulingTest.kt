package com.antiads.hook.internal.policy

import com.antiads.core.config.ConfigCodec
import com.antiads.core.config.ConfigConstants
import com.antiads.core.status.ConfigTransportState
import com.antiads.core.status.HookInstallState
import com.antiads.core.status.HookProcessReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告节流与内容（矩阵 M10）：合并至最多每 5 秒一条、首次可发送时立即发送、
 * 计数单调且报告可被 Provider 侧解码校验。
 */
class HookReportSchedulingTest {

    @Test
    fun reportsAreMergedToOnePerFiveSecondsAndStayValid() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(revision = 7L), null) }
        val reports = ArrayList<HookProcessReport>()
        val client = core(clock, scheduler, transport, reportSink = { reports.add(it) })

        client.onSensorCallback(1, 0L)
        scheduler.runAll()
        assertEquals("首次可发送时必须立即上报", 1, reports.size)

        for (timestamp in listOf(1000L, 2000L, 3000L, 4000L, 4999L)) {
            clock.set(timestamp)
            client.onSensorCallback(1, timestamp)
            scheduler.runAll()
        }
        assertEquals("5 秒窗口内合并为一条", 1, reports.size)

        clock.set(5000L)
        client.onSensorCallback(1, 5000L)
        scheduler.runAll()
        assertEquals(2, reports.size)

        val report = reports.last()
        assertEquals(ConfigConstants.SCHEMA_VERSION_V1, report.schemaVersion)
        assertEquals("com.antiads.probe", report.packageName)
        assertEquals("com.antiads.probe:main", report.processName)
        assertEquals(4321, report.pid)
        assertEquals(29, report.apiLevel)
        assertEquals(HookInstallState.INSTALLED, report.installState)
        assertEquals(ConfigTransportState.OK, report.transportState)
        assertEquals(7L, report.policyRevision)
        assertTrue(report.droppedCallbacks <= report.observedCallbacks)
        assertTrue(report.observedCallbacks >= 6L)
        assertEquals(null, report.lastErrorCode)

        // 报告必须能通过 Provider 侧同款校验（core ConfigCodec）
        val json = ConfigCodec.encodeHookReport(report)
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertEquals(report.processToken, ConfigCodec.decodeHookReport(json).processToken)
    }

    @Test
    fun reportFailureIsIsolatedAndStillThrottled() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        var attempts = 0
        val client = core(clock, scheduler, transport, reportSink = {
            attempts += 1
            throw IllegalStateException("simulated binder failure")
        })

        client.onSensorCallback(1, 0L)
        scheduler.runAll()
        for (timestamp in listOf(1000L, 2000L, 3000L, 4000L)) {
            clock.set(timestamp)
            client.onSensorCallback(1, timestamp)
            scheduler.runAll()
        }
        assertEquals("上报失败不得导致紧接重试", 1, attempts)

        clock.set(5000L)
        client.onSensorCallback(1, 5000L)
        scheduler.runAll()
        assertEquals(2, attempts)
    }

    @Test
    fun unsupportedInstallStateIsReportedVerbatim() {
        val clock = FakeClock(0L)
        val scheduler = RecordingScheduler()
        val transport = FakeTransport()
        transport.onFetch = { PolicyFetchOutcome.Reply(true, policyJson(), null) }
        val reports = ArrayList<HookProcessReport>()
        val client = core(
            clock = clock,
            scheduler = scheduler,
            transport = transport,
            reportSink = { reports.add(it) },
            installState = HookInstallState.UNSUPPORTED
        )

        client.onSensorCallback(1, 0L)
        scheduler.runAll()

        assertEquals(1, reports.size)
        assertEquals(HookInstallState.UNSUPPORTED, reports.first().installState)
        assertEquals(0L, reports.first().droppedCallbacks)
    }
}
