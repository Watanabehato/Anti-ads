package com.antiads.app.config

import java.util.ArrayDeque

/**
 * 每 UID 的滑动窗口限流（docs/contracts.md 第 6 节：策略读取 ≤5 次/秒，报告 ≤1 次/秒）。
 *
 * 时钟由调用方注入（生产用 SystemClock.elapsedRealtime），便于单测精确验证窗口边界。
 * 时钟回退时不淘汰旧记录（保守做法：继续限流而不是放开）。
 */
internal class RateLimiter(
    private val nowMs: () -> Long,
    private val maxEventsPerWindow: Int,
    private val windowMs: Long = 1000L,
    private val maxTrackedUids: Int = 64
) {

    private val lock = Any()
    private val windows = LinkedHashMap<Int, ArrayDeque<Long>>(16, 0.75f, true)

    fun allow(uid: Int): Boolean {
        synchronized(lock) {
            val now = nowMs()
            val window = windows.getOrPut(uid) { ArrayDeque() }
            while (window.isNotEmpty()) {
                val oldest = window.peekFirst() ?: break
                if (now - oldest < windowMs) break
                window.pollFirst()
            }
            if (window.size >= maxEventsPerWindow) {
                return false
            }
            window.addLast(now)
            evictIfNeeded()
            return true
        }
    }

    private fun evictIfNeeded() {
        while (windows.size > maxTrackedUids) {
            val eldest = windows.entries.firstOrNull() ?: return
            windows.remove(eldest.key)
        }
    }
}
