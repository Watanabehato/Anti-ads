package com.antiads.probe.diag

/**
 * 诊断快照写文件的节流（纯逻辑，可在 JVM 单测中精确断言）。
 *
 * 约定（docs/probe.md）：debuggable 变体最多 **1 次/秒** 写 `files/probe-diag.json`；
 * 关键状态变化（开始/停止/onPause/onResume）用 `force = true` 立即写一次。
 * 时间由调用方以 `SystemClock.elapsedRealtime()` 数值传入，本类不读时钟。
 */
internal class DiagFileThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {

    private var lastWriteAtMs: Long = NEVER

    /** true 表示本次应写文件；被节流时返回 false 且不更新计时。 */
    fun shouldWrite(nowElapsedMs: Long, force: Boolean): Boolean {
        if (force) {
            lastWriteAtMs = nowElapsedMs
            return true
        }
        if (lastWriteAtMs != NEVER && nowElapsedMs - lastWriteAtMs < minIntervalMs) return false
        lastWriteAtMs = nowElapsedMs
        return true
    }

    fun lastWriteAtMs(): Long = lastWriteAtMs

    fun reset() {
        lastWriteAtMs = NEVER
    }

    companion object {
        const val NEVER: Long = Long.MIN_VALUE
        const val DEFAULT_MIN_INTERVAL_MS: Long = 1000L
    }
}
