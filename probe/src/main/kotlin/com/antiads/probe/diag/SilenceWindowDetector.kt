package com.antiads.probe.diag

/**
 * 同一注册内的回调静默窗口检测（纯逻辑）。
 *
 * 用途：H01 关闭恢复实验里，QA 需要区分"配置仍有效时的丢弃期"与"恢复后的首个事件"。
 * 每次回调与上次间隔 ≥ [gapThresholdMs] 就记一个窗口（lastBefore → resumedAt），
 * 因此无需与管理端时钟对齐即可读出静默时长；精确 t0→t1 仍由 A2 路径的宿主 instrumentation
 * elapsedRealtime 或人工记录配对（见 docs/hook-probe-observability.md 第 5 节）。
 */
class SilenceWindowDetector(private val gapThresholdMs: Long = 1500L) {

    data class Window(
        val sensorType: Int,
        val lastCallbackBeforeGapMs: Long,
        val resumedAtMs: Long,
        val silenceMs: Long
    )

    private val lastSeen = HashMap<Int, Long>()
    private val windows = ArrayList<Window>()
    private val longestSilence = HashMap<Int, Long>()

    fun onCallback(sensorType: Int, nowElapsedMs: Long) {
        val previous = lastSeen[sensorType]
        if (previous != null) {
            val delta = nowElapsedMs - previous
            if (delta >= gapThresholdMs) {
                windows.add(Window(sensorType, previous, nowElapsedMs, delta))
                val best = longestSilence[sensorType] ?: 0L
                if (delta > best) longestSilence[sensorType] = delta
            }
        }
        lastSeen[sensorType] = nowElapsedMs
    }

    fun windows(): List<Window> = windows.toList()

    fun windowsFor(sensorType: Int): List<Window> = windows.filter { it.sensorType == sensorType }

    fun longestSilenceMs(sensorType: Int): Long = longestSilence[sensorType] ?: 0L

    fun lastSeenElapsedMs(sensorType: Int): Long? = lastSeen[sensorType]
}
