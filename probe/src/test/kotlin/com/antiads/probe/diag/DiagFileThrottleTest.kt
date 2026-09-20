package com.antiads.probe.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA-04 回归：诊断快照写文件必须 ≤1 次/秒，且在关键状态变化时可立即写一次。 */
class DiagFileThrottleTest {

    @Test
    fun firstWriteIsAllowed() {
        val throttle = DiagFileThrottle()
        assertTrue(throttle.shouldWrite(nowElapsedMs = 1_000L, force = false))
        assertEquals(1_000L, throttle.lastWriteAtMs())
    }

    @Test
    fun writesWithinOneSecondAreThrottled() {
        val throttle = DiagFileThrottle()
        assertTrue(throttle.shouldWrite(1_000L, force = false))
        assertFalse(throttle.shouldWrite(1_001L, force = false))
        assertFalse(throttle.shouldWrite(1_999L, force = false))
        // 被节流时不更新计时，因此从 2000 起恰好 1 秒后仍可写
        assertEquals(1_000L, throttle.lastWriteAtMs())
        assertTrue(throttle.shouldWrite(2_000L, force = false))
    }

    @Test
    fun forceWriteIsImmediateAndResetsWindow() {
        val throttle = DiagFileThrottle()
        assertTrue(throttle.shouldWrite(1_000L, force = false))
        assertTrue("状态变化必须立即写", throttle.shouldWrite(1_100L, force = true))
        assertEquals(1_100L, throttle.lastWriteAtMs())
        assertFalse("force 之后仍然节流", throttle.shouldWrite(1_500L, force = false))
        assertTrue(throttle.shouldWrite(2_100L, force = false))
    }

    @Test
    fun resetClearsThrottleWindow() {
        val throttle = DiagFileThrottle()
        throttle.shouldWrite(1_000L, force = false)
        assertFalse(throttle.shouldWrite(1_100L, force = false))
        throttle.reset()
        assertEquals(DiagFileThrottle.NEVER, throttle.lastWriteAtMs())
        assertTrue(throttle.shouldWrite(1_100L, force = false))
    }

    @Test
    fun customIntervalIsRespected() {
        val throttle = DiagFileThrottle(minIntervalMs = 2_500L)
        assertTrue(throttle.shouldWrite(0L, force = false))
        assertFalse(throttle.shouldWrite(2_499L, force = false))
        assertTrue(throttle.shouldWrite(2_500L, force = false))
    }
}
