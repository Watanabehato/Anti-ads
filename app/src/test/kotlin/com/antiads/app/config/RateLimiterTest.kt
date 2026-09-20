package com.antiads.app.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每 UID 限流窗口边界（合同：策略读取 ≤5 次/秒，报告 ≤1 次/秒）。 */
class RateLimiterTest {

    @Test
    fun allowsUpToLimitInsideWindowAndBlocksNext() {
        var now = 10_000L
        val limiter = RateLimiter(nowMs = { now }, maxEventsPerWindow = 5)

        repeat(5) { assertTrue(limiter.allow(1001)) }
        assertFalse(limiter.allow(1001))
        now = 10_999L
        assertFalse(limiter.allow(1001))
        now = 11_000L
        assertTrue(limiter.allow(1001))
    }

    @Test
    fun separateUidsHaveSeparateWindows() {
        var now = 0L
        val limiter = RateLimiter(nowMs = { now }, maxEventsPerWindow = 1)

        assertTrue(limiter.allow(1001))
        assertFalse(limiter.allow(1001))
        assertTrue(limiter.allow(1002))
    }

    @Test
    fun clockRollbackKeepsLimiting() {
        var now = 5_000L
        val limiter = RateLimiter(nowMs = { now }, maxEventsPerWindow = 1)
        assertTrue(limiter.allow(1001))
        now = 1_000L
        assertFalse(limiter.allow(1001))
    }

    @Test
    fun trackedUidMapIsBounded() {
        var now = 0L
        val limiter = RateLimiter(nowMs = { now }, maxEventsPerWindow = 1, maxTrackedUids = 2)

        assertTrue(limiter.allow(1))
        assertTrue(limiter.allow(2))
        assertTrue(limiter.allow(3))
        // uid 1 的窗口已被淘汰，因此在同一时间窗内重新放行（内存有界优先于极端精确）
        assertTrue(limiter.allow(1))
        assertFalse(limiter.allow(3))
    }
}
