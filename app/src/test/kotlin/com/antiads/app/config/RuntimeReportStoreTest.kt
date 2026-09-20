package com.antiads.app.config

import com.antiads.core.status.ConfigTransportState
import com.antiads.core.status.HookInstallState
import com.antiads.core.status.HookProcessReport
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 报告存储上限与排序（docs/contracts.md 第 7 节：每包 8 进程、全局 128 条）。 */
class RuntimeReportStoreTest {

    @Before
    fun setUp() {
        RuntimeReportStore.clear()
    }

    @After
    fun tearDown() {
        RuntimeReportStore.clear()
    }

    @Test
    fun keepsAtMostEightProcessesPerPackage() {
        repeat(9) { index ->
            RuntimeReportStore.put(report("com.example.app", pid = 1000 + index), index.toLong())
        }

        val snapshot = RuntimeReportStore.snapshot()
        assertEquals(8, snapshot.size)
        assertEquals(8, RuntimeReportStore.size())
        // 最久未使用的第一条（pid=1000）应被淘汰
        assertTrue(snapshot.none { it.report.pid == 1000 })
    }

    @Test
    fun globalCapIsEnforced() {
        // 每个包只放 1 条报告：先触发全局 128 条上限，而不是每包 8 进程上限
        repeat(140) { index ->
            RuntimeReportStore.put(report("com.example.p" + index, pid = 2000 + index), index.toLong())
        }

        assertEquals(128, RuntimeReportStore.size())
        assertTrue("最旧的条目应被淘汰", RuntimeReportStore.snapshot().none { it.report.packageName == "com.example.p0" })
        assertTrue("最新条目必须保留", RuntimeReportStore.snapshot().any { it.report.packageName == "com.example.p139" })
    }

    @Test
    fun snapshotIsSortedNewestFirst() {
        RuntimeReportStore.put(report("com.example.app", pid = 1), 100L)
        RuntimeReportStore.put(report("com.example.app", pid = 2), 300L)
        RuntimeReportStore.put(report("com.example.app", pid = 3), 200L)

        val receivedAt = RuntimeReportStore.snapshot().map { it.receivedAtElapsedMs }
        assertEquals(listOf(300L, 200L, 100L), receivedAt)
    }

    @Test
    fun sameProcessTokenReplacesPreviousEntry() {
        val token = UUID.randomUUID().toString()
        RuntimeReportStore.put(report("com.example.app", pid = 77, token = token, observed = 1L), 10L)
        RuntimeReportStore.put(report("com.example.app", pid = 77, token = token, observed = 5L), 20L)

        assertEquals(1, RuntimeReportStore.size())
        assertEquals(5L, RuntimeReportStore.snapshot().first().report.observedCallbacks)
    }

    private fun report(
        packageName: String,
        pid: Int,
        token: String = UUID.randomUUID().toString(),
        observed: Long = 0L
    ): HookProcessReport = HookProcessReport(
        packageName = packageName,
        processName = packageName,
        processToken = token,
        pid = pid,
        apiLevel = 29,
        installState = HookInstallState.INSTALLED,
        transportState = ConfigTransportState.OK,
        policyRevision = 1L,
        observedCallbacks = observed,
        droppedCallbacks = 0L
    )
}
