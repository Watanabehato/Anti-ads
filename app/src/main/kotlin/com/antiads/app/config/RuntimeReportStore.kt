package com.antiads.app.config

import com.antiads.core.status.HookProcessReport
import com.antiads.core.status.ReceivedHookReport
import java.util.LinkedHashMap

/**
 * 目标进程自报诊断的内存存储（docs/contracts.md 第 7 节）。
 *
 * - 只由 ConfigProvider 在完成身份校验、包名比对与 pid 覆盖后写入；
 * - 只保留内存，重启后不还原“在线”；
 * - 每包最多 8 个进程、全局最多 128 条，按最久未使用淘汰；
 * - 计数为进程自报，不作为授权依据，也不构成“可信框架证明”。
 */
object RuntimeReportStore {

    private const val MAX_PROCESSES_PER_PACKAGE = 8
    private const val MAX_REPORTS = 128

    private val lock = Any()
    private val reports = LinkedHashMap<String, ReceivedHookReport>(16, 0.75f, true)

    /** 线程安全快照；按接收时间从新到旧排序，同刻用令牌/PID 保证确定性。 */
    fun snapshot(): List<ReceivedHookReport> = synchronized(lock) {
        reports.values.sortedWith(
            compareByDescending<ReceivedHookReport> { it.receivedAtElapsedMs }
                .thenBy { it.report.processToken }
                .thenBy { it.report.pid }
        )
    }

    internal fun put(report: HookProcessReport, receivedAtElapsedMs: Long) {
        synchronized(lock) {
            reports.remove(keyOf(report))
            reports[keyOf(report)] = ReceivedHookReport(report, receivedAtElapsedMs)
            evict(report.packageName)
        }
    }

    internal fun clear() {
        synchronized(lock) {
            reports.clear()
        }
    }

    internal fun size(): Int = synchronized(lock) { reports.size }

    internal fun keyOf(report: HookProcessReport): String =
        report.packageName + "|" + report.pid + "|" + report.processToken

    private fun evict(packageName: String) {
        while (true) {
            val samePackage = reports.entries.filter { it.value.report.packageName == packageName }
            if (samePackage.size <= MAX_PROCESSES_PER_PACKAGE) break
            reports.remove(samePackage.first().key)
        }
        while (reports.size > MAX_REPORTS) {
            val eldest = reports.entries.firstOrNull() ?: break
            reports.remove(eldest.key)
        }
    }
}
