package com.antiads.probe.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 摇动计数只用于观察运动事件变化，阈值/窗口行为必须可预测。 */
class ShakeCounterTest {

    @Test
    fun countsShakeOnDirectionChangesWithinWindow() {
        val counter = ShakeCounter(thresholdG = 1.0f, directionChangesForShake = 2, windowMs = 1000L)
        counter.onMagnitude(2.0f, 0L)
        assertEquals(0L, counter.shakes)
        counter.onMagnitude(0.1f, 100L)
        assertEquals(0L, counter.shakes) // 第 1 次方向翻转还不够
        counter.onMagnitude(2.0f, 150L)
        assertEquals(1L, counter.shakes) // 第 2 次方向翻转 → 记一次摇动
        assertEquals(3L, counter.significantMotionEvents)
    }

    @Test
    fun ignoresValuesWithinDeadZone() {
        val counter = ShakeCounter(thresholdG = 1.2f)
        counter.onMagnitude(1.25f, 0L) // 阈值 ±0.15 死区内
        counter.onMagnitude(1.10f, 100L)
        assertEquals(0L, counter.significantMotionEvents)
        assertEquals(0L, counter.shakes)
    }

    @Test
    fun resetsCounters() {
        val counter = ShakeCounter(thresholdG = 1.0f, directionChangesForShake = 1, windowMs = 1000L)
        counter.onMagnitude(3.0f, 0L)
        counter.onMagnitude(0.0f, 50L)
        assertTrue(counter.shakes > 0L)
        counter.reset()
        assertEquals(0L, counter.shakes)
        assertEquals(0L, counter.significantMotionEvents)
    }

    @Test
    fun windowExpiryClearsDirectionChanges() {
        val counter = ShakeCounter(thresholdG = 1.0f, directionChangesForShake = 2, windowMs = 100L)
        counter.onMagnitude(3.0f, 0L)
        counter.onMagnitude(0.0f, 500L) // 超出窗口：方向变化计数已清零
        assertEquals(0L, counter.shakes)
    }
}
