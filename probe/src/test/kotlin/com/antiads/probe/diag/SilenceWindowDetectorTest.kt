package com.antiads.probe.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 静默窗口检测：只把 ≥ 阈值的回调缺口记为恢复证据。 */
class SilenceWindowDetectorTest {

    @Test
    fun recordsWindowOnlyForGapsAboveThreshold() {
        val detector = SilenceWindowDetector(gapThresholdMs = 1500L)
        detector.onCallback(1, 0L)
        detector.onCallback(1, 200L)
        detector.onCallback(1, 400L)
        assertTrue(detector.windows().isEmpty())

        detector.onCallback(1, 7000L) // 缺口 6600ms
        val window = detector.windows().single()
        assertEquals(1, window.sensorType)
        assertEquals(400L, window.lastCallbackBeforeGapMs)
        assertEquals(7000L, window.resumedAtMs)
        assertEquals(6600L, window.silenceMs)
        assertEquals(6600L, detector.longestSilenceMs(1))
    }

    @Test
    fun tracksPerSensorTypeIndependently() {
        val detector = SilenceWindowDetector(gapThresholdMs = 1000L)
        detector.onCallback(1, 0L)
        detector.onCallback(2, 0L)
        detector.onCallback(1, 500L)
        detector.onCallback(2, 5000L)
        assertEquals(1, detector.windowsFor(2).size)
        assertTrue(detector.windowsFor(1).isEmpty())
        assertEquals(5000L, detector.lastSeenElapsedMs(2))
        assertEquals(500L, detector.lastSeenElapsedMs(1))
    }

    @Test
    fun firstCallbackNeverCreatesWindow() {
        val detector = SilenceWindowDetector(gapThresholdMs = 1000L)
        detector.onCallback(1, 0L)
        assertTrue(detector.windows().isEmpty())
    }
}
