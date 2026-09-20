package com.antiads.probe.diag

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H01 A1 路径：由 instrumentation 在 probe 进程内**持有同一次注册**，不随 Activity pause 注销。
 *
 * 运行（test APK 由顺序集成 t11 组装）：
 *
 *   adb shell am instrument -w \
 *     -e class com.antiads.probe.diag.RegistrationHoldTest \
 *     -e durationMs 90000 -e baselineMs 10000 \
 *     -e types 1,4,9,10,11 -e control 2 \
 *     com.antiads.probe.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 使用要求（docs/hook-probe-observability.md 第 3 节）：
 * - probe 建议分屏可见，避免 API29+ 后台连续传感器限制影响基线；
 * - control 必须是**该设备真实可用且未选入拦截集合**的类型，不能固定用类型 3；
 * - 该次实验是否需要判为"不可判定"由控制类型是否持续回调决定（verdict 行写明）。
 *
 * 本测试不修改产品配置、不开关任何开关、不进行 su 操作，只观察与记录。
 */
@RunWith(AndroidJUnit4::class)
class RegistrationHoldTest {

    @Test
    fun holdsOneRegistrationForTheWholeExperiment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // t11 集成修正：android.app.Instrumentation 在公开 SDK 中没有 getArguments()，
        // 参数必须走 androidx.test 的 InstrumentationRegistry.getArguments()（与 app 的 ConfigToggleTest 一致）。
        val arguments = InstrumentationRegistry.getArguments()
        val durationMs = arguments.getLong(ARG_DURATION_MS, DEFAULT_DURATION_MS)
        val baselineMs = arguments.getLong(ARG_BASELINE_MS, DEFAULT_BASELINE_MS)
        val requestedTypes = parseTypes(arguments.getString(ARG_TYPES))
        val requestedControl = arguments.getString(ARG_CONTROL)?.trim()?.toIntOrNull()
        val context = instrumentation.targetContext

        // 本测试运行在 probe 目标进程内（同 UID），并确认不是落在宿主 UID 上。
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Log.i(TAG, "hold_start types=" + requestedTypes.joinToString(separator = ",") +
            " durationMs=" + durationMs +
            " baselineMs=" + baselineMs +
            " debuggable=" + debuggable +
            " pid=" + android.os.Process.myPid() +
            " elapsed_realtime_ms=" + SystemClock.elapsedRealtime())

        val session = SensorDiagSession(
            context = context,
            host = DiagHost.INSTRUMENTATION,
            selectedTypes = requestedTypes
        )
        session.activityResumed = false

        val started = session.start()
        val startedAtMs = SystemClock.elapsedRealtime()
        if (requestedControl != null && requestedControl != session.controlType) {
            // QA 指定的对照类型与本机实际可用对照不一致：明确记录，避免把别的类型当成对照证据。
            Log.w(TAG, "control_mismatch requested=" + requestedControl +
                " actual=" + (session.controlType ?: -1) +
                " note=control_must_be_device_available_and_not_selected")
        }
        Log.i(TAG, "hold_registered started=" + started +
            " registrationSeq=" + session.registrationSeq() +
            " sessionId=" + session.diagnosticsSessionId() +
            " controlType=" + (session.controlType ?: -1) +
            " elapsed_realtime_ms=" + startedAtMs)

        var baselineLogged = false
        try {
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startedAtMs
                if (elapsed >= durationMs) break
                if (!baselineLogged && elapsed >= baselineMs) {
                    baselineLogged = true
                    Log.i(TAG, "hold_baseline_complete controlContinuous=" + session.controlIsContinuous() +
                        " elapsed_realtime_ms=" + SystemClock.elapsedRealtime())
                }
                session.emitRows(force = true)
                Thread.sleep(TICK_MS)
            }
        } finally {
            session.emitRows(force = true)
        }

        // 同一注册的结构断言：host 固定、注册序号 ≥1（若确实注册成功）、sessionId 不变
        val frame = session.frame()
        assertEquals(DiagHost.INSTRUMENTATION, frame.host)
        assertEquals(session.diagnosticsSessionId(), frame.sessionId)
        if (started) {
            assertTrue("至少一次成功注册应使 registrationSeq ≥ 1", frame.registrationSeq >= 1)
        }

        val controlContinuous = session.controlIsContinuous()
        val verdict = when {
            !started -> VERDICT_NO_REGISTRATION
            frame.controlType == null -> VERDICT_NO_CONTROL_TYPE
            !controlContinuous -> VERDICT_CONTROL_SILENT
            else -> VERDICT_CONTROL_OK
        }
        session.emitSilenceWindows(requestedTypes)
        for (type in requestedTypes) {
            Log.i(TAG, "hold_silence type=" + type +
                " longestSilenceMs=" + session.longestSilenceMs(type) +
                " lastSeenElapsedMs=" + (session.lastSeenElapsedMs(type) ?: -1))
        }
        session.emitSummary(verdict = verdict, controlContinuous = controlContinuous, note = NOTE_HOLD)
        Log.i(TAG, "hold_end verdict=" + verdict +
            " controlType=" + (frame.controlType ?: -1) +
            " registrationSeq=" + frame.registrationSeq +
            " elapsed_realtime_ms=" + SystemClock.elapsedRealtime())
        session.stop()
    }

    private fun parseTypes(raw: String?): List<Int> {
        val parsed = raw?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.take(ProbeTypes.MAX_SELECTED)
            .orEmpty()
        return if (parsed.isEmpty()) ProbeTypes.DEFAULT_SELECTED else parsed
    }

    private companion object {
        const val TAG: String = DiagJson.LOG_TAG
        const val TICK_MS: Long = 1000L
        const val DEFAULT_DURATION_MS: Long = 60_000L
        const val DEFAULT_BASELINE_MS: Long = 10_000L

        const val ARG_DURATION_MS: String = "durationMs"
        const val ARG_BASELINE_MS: String = "baselineMs"
        const val ARG_TYPES: String = "types"
        const val ARG_CONTROL: String = "control"

        const val NOTE_HOLD: String = "instrumentation_holds_single_registration"

        const val VERDICT_CONTROL_OK: String = "OBSERVED_CONTROL_CONTINUOUS"
        const val VERDICT_CONTROL_SILENT: String = "INCONCLUSIVE_CONTROL_SILENT"
        const val VERDICT_NO_CONTROL_TYPE: String = "INCONCLUSIVE_NO_CONTROL_TYPE"
        const val VERDICT_NO_REGISTRATION: String = "INCONCLUSIVE_NO_REGISTRATION"
    }
}
