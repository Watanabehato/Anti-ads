package com.antiads.probe.diag

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * 诊断会话：真实 SensorManager 注册/注销 + 逐类型计数 + 机器可读诊断行。
 *
 * contracts 第 8 节公共约定不变（默认 SENSOR_DELAY_NORMAL、显式开始/停止、onPause 注销、
 * onResume 不自动重启、最多 5 种配置支持类型 + 至少一种设备可用未选对照）。
 * 额外提供的仅是内部诊断面（logcat 行 + debuggable 时的文件快照），供 QA 证明"同一注册"。
 *
 * 本类被 Activity（host=ACTIVITY）与 instrumentation（host=INSTRUMENTATION）复用；
 * instrumentation 承载不改变 SensorManager 调用语义，只是不随 Activity pause 注销。
 */
class SensorDiagSession(
    private val context: Context,
    private val host: DiagHost = DiagHost.ACTIVITY,
    val selectedTypes: List<Int> = ProbeTypes.DEFAULT_SELECTED,
    private val delay: Int = SensorManager.SENSOR_DELAY_NORMAL
) {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val sensors: LinkedHashMap<Int, Sensor?> = LinkedHashMap()
    private val tracker: DiagTracker
    private val silence = HashMap<Int, SilenceWindowDetector>()
    private val shakeCounter = ShakeCounter()
    private val lastEmitByType = HashMap<Int, Long>()
    private val sessionId: String = UUID.randomUUID().toString()
    private val debuggable: Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val diagFileThrottle = DiagFileThrottle()

    val controlType: Int?

    var activityResumed: Boolean = false
    var emitCount: Long = 0L
        private set

    private var running: Boolean = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensorType = event.sensor.type
            val now = SystemClock.elapsedRealtime()
            tracker.onCallback(sensorType, now)
            silence.getOrPut(sensorType) { SilenceWindowDetector() }.onCallback(sensorType, now)
            if (isThreeAxis(sensorType)) {
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                val before = shakeCounter.shakes
                shakeCounter.onMagnitude(magnitude, now)
                if (shakeCounter.shakes > before) tracker.onShake(sensorType)
            }
            // 节流由回调路径自行判断，避免每个传感器事件都构造整帧行（≤1 次/秒/类型）。
            val lastEmit = lastEmitByType[sensorType]
            if (lastEmit == null || now - lastEmit >= EMIT_INTERVAL_MS) {
                emitRows(force = false)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        val available = LinkedHashMap<Int, Boolean>()
        val candidates = LinkedHashSet(selectedTypes)
        for (type in candidates) {
            val sensor = runCatching { sensorManager?.getDefaultSensor(type) }.getOrNull()
            sensors[type] = sensor
            available[type] = sensor != null
        }
        // 对照类型必须设备真实可用且不在拦截集合内（否则显式"无对照"）
        val availableTypes = LinkedHashSet<Int>()
        for (type in ProbeTypes.PREFERRED_CONTROL_TYPES) {
            val present = runCatching { sensorManager?.getDefaultSensor(type) }.getOrNull() != null
            if (present) availableTypes.add(type)
        }
        for (type in ALL_CONTROL_SCAN_TYPES) {
            if (runCatching { sensorManager?.getDefaultSensor(type) }.getOrNull() != null) availableTypes.add(type)
        }
        val detectedControlType = ProbeTypes.chooseControlType(availableTypes, candidates.toSet())
        controlType = detectedControlType
        if (detectedControlType != null) {
            val sensor = runCatching { sensorManager?.getDefaultSensor(detectedControlType) }.getOrNull()
            sensors[detectedControlType] = sensor
            available[detectedControlType] = sensor != null
        }
        tracker = DiagTracker(
            selectedTypes = selectedTypes,
            controlType = controlType,
            available = available,
            host = host,
            sessionId = sessionId,
            pid = Process.myPid()
        )
    }

    fun isRunning(): Boolean = running

    fun registrationSeq(): Int = tracker.registrationSeq

    fun diagnosticsSessionId(): String = sessionId

    fun totalShakes(): Long = tracker.totalShakes()

    fun significantMotionEvents(): Long = shakeCounter.significantMotionEvents

    /** 显式开始采样：每种类型一次 registerListener，记录真实返回值。 */
    fun start(): Boolean {
        if (running) return false
        val manager = sensorManager ?: return false
        var anyRegistered = false
        for ((type, sensor) in sensors) {
            if (sensor == null) continue
            val success = runCatching {
                manager.registerListener(listener, sensor, delay)
            }.getOrDefault(false)
            tracker.markRegisterAttempt(type, success)
            if (success) anyRegistered = true
            emitRows(force = true)
        }
        running = anyRegistered
        if (!anyRegistered) {
            tracker.markUnregistered()
            emitSummary(verdict = VERDICT_NO_REGISTRATION, controlContinuous = false, note = "no_sensor_registered")
        } else {
            emitSummary(verdict = VERDICT_STARTED, controlContinuous = false, note = "sampling_started")
        }
        return running
    }

    /** 显式停止：注销全部监听（onPause 也调用同一路径）。 */
    fun stop() {
        val manager = sensorManager
        if (manager != null) {
            runCatching { manager.unregisterListener(listener) }
        }
        tracker.markUnregistered()
        running = false
        emitRows(force = true)
        emitSummary(verdict = VERDICT_STOPPED, controlContinuous = controlIsContinuous(), note = "sampling_stopped")
    }

    /** 每个类型一行诊断输出：状态变化立即输出，其余节流 ≤1 次/秒/类型。 */
    fun emitRows(force: Boolean) {
        if (!running && !force) return
        val frame = frame()
        for (row in frame.rows) {
            val last = lastEmitByType[row.sensorType]
            val now = frame.elapsedMs
            if (force || last == null || now - last >= EMIT_INTERVAL_MS) {
                lastEmitByType[row.sensorType] = now
                Log.i(DiagJson.LOG_TAG, DiagJson.row(frame, row))
                emitCount += 1
            }
        }
    }

    fun frame(): DiagFrame = tracker.frame(
        seq = emitCount,
        activityResumed = activityResumed,
        nowElapsedMs = SystemClock.elapsedRealtime()
    )

    fun rows(): List<TypeMeasurement> = tracker.rows(SystemClock.elapsedRealtime())

    /**
     * 对照类型是否在观察窗内持续收到回调。
     * 该类型在承载方式下也停止回调（后台传感器限制/息屏/省电）时，本次实验必须判为不可判定。
     */
    fun controlIsContinuous(): Boolean {
        val control = controlType ?: return false
        val row = rows().firstOrNull { it.sensorType == control } ?: return false
        if (!row.exists || row.registerResult != true) return false
        return (row.gapSinceLastMs ?: Long.MAX_VALUE) <= CONTROL_GAP_LIMIT_MS && row.callbacks >= 2
    }

    fun emitSummary(verdict: String, controlContinuous: Boolean, note: String?) {
        Log.i(DiagJson.LOG_TAG, DiagJson.summary(frame(), verdict, controlContinuous, note))
    }

    fun emitSilenceWindows(sensorTypes: Collection<Int>) {
        for (type in sensorTypes) {
            val detector = silence[type] ?: continue
            for (window in detector.windows()) {
                Log.i(
                    DiagJson.LOG_TAG,
                    DiagJson.silenceWindow(type, window.lastCallbackBeforeGapMs, window.resumedAtMs, window.silenceMs)
                )
            }
        }
    }

    fun longestSilenceMs(sensorType: Int): Long = silence[sensorType]?.longestSilenceMs(sensorType) ?: 0L

    fun lastSeenElapsedMs(sensorType: Int): Long? = silence[sensorType]?.lastSeenElapsedMs(sensorType)

    /**
     * debuggable 变体额外写文件快照（docs/probe.md 承诺的 `files/probe-diag.json`），便于 adb run-as 拉取。
     *
     * - 只在 debuggable 构建里生效：非 debuggable 直接返回 null，不进入任何运行路径；
     * - 节流：默认最多 1 次/秒；关键状态变化（开始/停止/onPause/onResume）传 `force = true` 立即写一次；
     * - 返回写入路径；返回 null 表示“未写”（非 debuggable 或被节流）或写入失败；
     * - 内容只含计数、状态与时间戳（见 [DiagJson.snapshot]），不含界面文本/输入内容/传感器读数；
     * - 生命周期安全：任何异常都被吞掉并返回 null，绝不影响采样、日志与界面。
     */
    fun dumpFile(nowElapsedMs: Long, force: Boolean): String? {
        if (!debuggable) return null
        if (!diagFileThrottle.shouldWrite(nowElapsedMs, force)) return null
        return try {
            val file = File(context.filesDir, DIAG_FILE_NAME)
            val json = DiagJson.snapshot(
                frame(),
                writtenAtElapsedMs = nowElapsedMs,
                samplingRunning = running
            )
            file.writeText(json, Charsets.UTF_8)
            file.absolutePath
        } catch (t: Throwable) {
            null
        }
    }

    /** 最近一次写快照的 elapsedRealtime 数值；从未写过时为 [DiagFileThrottle.NEVER]。 */
    fun lastSnapshotAtMs(): Long = diagFileThrottle.lastWriteAtMs()

    private fun isThreeAxis(sensorType: Int): Boolean =
        sensorType in THREE_AXIS_TYPES

    private companion object {
        const val EMIT_INTERVAL_MS: Long = 1000L
        const val CONTROL_GAP_LIMIT_MS: Long = 3000L
        const val DIAG_FILE_NAME: String = "probe-diag.json"

        const val VERDICT_STARTED: String = "SAMPLING_STARTED"
        const val VERDICT_STOPPED: String = "SAMPLING_STOPPED"
        const val VERDICT_NO_REGISTRATION: String = "INCONCLUSIVE_NO_REGISTRATION"

        val THREE_AXIS_TYPES: Set<Int> = setOf(1, 2, 4, 9, 10, 11)
        val ALL_CONTROL_SCAN_TYPES: List<Int> = listOf(2, 3, 5, 6, 8, 12, 13, 15, 16, 17, 18, 19, 20, 21)
    }
}
