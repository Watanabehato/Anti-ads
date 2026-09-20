package com.antiads.probe.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 逐类型计数、注册序号与"同一注册"判据的纯逻辑行为。 */
class DiagTrackerTest {

    private fun tracker(
        selected: List<Int> = listOf(1, 4),
        control: Int? = 2,
        available: Map<Int, Boolean> = mapOf(1 to true, 4 to true, 2 to true)
    ) = DiagTracker(
        selectedTypes = selected,
        controlType = control,
        available = available,
        host = DiagHost.ACTIVITY,
        sessionId = "session-1",
        pid = 42
    )

    @Test
    fun missingSensorTypeIsShownAsAbsentAndNotRegistered() {
        val tracker = tracker(available = mapOf(1 to true, 4 to false, 2 to true))
        tracker.markRegisterAttempt(1, true)
        val rows = tracker.rows(nowElapsedMs = 0L)
        val absent = rows.first { it.sensorType == 4 }
        assertFalse(absent.exists)
        assertNull(absent.registerResult)
        assertEquals(SamplingState.IDLE, absent.samplingState)
        val present = rows.first { it.sensorType == 1 }
        assertTrue(present.exists)
        assertEquals(true, present.registerResult)
        assertEquals(SamplingState.REGISTERED, present.samplingState)
    }

    @Test
    fun failedRegistrationIsReportedVerbatim() {
        val tracker = tracker()
        tracker.markRegisterAttempt(1, false)
        val row = tracker.rows(0L).first { it.sensorType == 1 }
        assertEquals(false, row.registerResult)
        assertEquals(SamplingState.REGISTER_FAILED, row.samplingState)
        assertEquals(0, tracker.registrationSeq)
    }

    @Test
    fun registrationSeqCountsSuccessfulRegistrationsOnly() {
        val tracker = tracker()
        tracker.markRegisterAttempt(1, true)
        tracker.markRegisterAttempt(4, true)
        tracker.markRegisterAttempt(2, false)
        assertEquals(2, tracker.registrationSeq)
    }

    @Test
    fun callbacksAccumulateAndResetPerRegistration() {
        val tracker = tracker()
        tracker.markRegisterAttempt(1, true)
        tracker.onCallback(1, 100L)
        tracker.onCallback(1, 200L)
        var row = tracker.rows(250L).first { it.sensorType == 1 }
        assertEquals(2L, row.callbacks)
        assertEquals(2L, row.callbacksSinceRegister)
        assertEquals(50L, row.gapSinceLastMs)

        // 同一进程内的第二次注册：累计保留、本次注册归零（判据仍是 sessionId + registrationSeq）
        tracker.markRegisterAttempt(1, false)
        tracker.markRegisterAttempt(1, true)
        row = tracker.rows(300L).first { it.sensorType == 1 }
        assertEquals(2L, row.callbacks)
        assertEquals(0L, row.callbacksSinceRegister)
        assertEquals(2, tracker.registrationSeq)
        assertNull(row.gapSinceLastMs)
    }

    @Test
    fun unregisterKeepsCountersButMarksState() {
        val tracker = tracker()
        tracker.markRegisterAttempt(1, true)
        tracker.onCallback(1, 10L)
        tracker.markUnregistered()
        val row = tracker.rows(20L).first { it.sensorType == 1 }
        assertEquals(SamplingState.UNREGISTERED, row.samplingState)
        assertEquals(1L, row.callbacks)
        assertFalse(tracker.isAnyRegistered())
    }

    @Test
    fun controlTypeIsASeparateRow() {
        val tracker = tracker()
        val rows = tracker.rows(0L)
        assertEquals(listOf(1, 4, 2), rows.map { it.sensorType })
        assertTrue(rows.first { it.sensorType == 2 }.let { !it.selected })
        assertEquals(2, tracker.controlType())
    }

    @Test
    fun frameCarriesHostAndSessionIdentity() {
        val tracker = tracker()
        tracker.markRegisterAttempt(1, true)
        val frame = tracker.frame(seq = 5L, activityResumed = true, nowElapsedMs = 777L)
        assertEquals(5L, frame.seq)
        assertEquals("session-1", frame.sessionId)
        assertEquals(DiagHost.ACTIVITY, frame.host)
        assertEquals(42, frame.pid)
        assertEquals(777L, frame.elapsedMs)
        assertEquals(1, frame.registrationSeq)
        assertTrue(frame.rows.isNotEmpty())
    }
}
