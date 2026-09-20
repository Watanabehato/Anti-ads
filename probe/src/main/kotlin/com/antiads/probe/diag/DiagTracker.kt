package com.antiads.probe.diag

/**
 * 逐类型计数与状态（纯逻辑，无 Android 依赖）。
 *
 * 语义约定：
 * - [callbacks] 跨注册累计，[callbacksSinceRegister] 每次成功注册后归零；
 * - [registrationSeq] 每次成功的 registerListener 递增，[sessionId] 每次注册批次生成新 UUID；
 * - 同一注册的判据是"同一进程实例内 sessionId 与 registrationSeq 不变"（进程重启不保证 +1）。
 */
class DiagTracker(
    private val selectedTypes: List<Int>,
    private val controlType: Int?,
    available: Map<Int, Boolean>,
    private val host: DiagHost,
    private val sessionId: String,
    private val pid: Int
) {

    private class Entry(val sensorType: Int, val selected: Boolean, val exists: Boolean) {
        var registerAttempted: Boolean = false
        var registerResult: Boolean? = null
        var samplingState: SamplingState = SamplingState.IDLE
        var callbacks: Long = 0L
        var callbacksSinceRegister: Long = 0L
        var lastCallbackElapsedMs: Long? = null
        var shakes: Long = 0L
    }

    private val entries: LinkedHashMap<Int, Entry> = LinkedHashMap()

    var registrationSeq: Int = 0
        private set

    init {
        for (type in selectedTypes) {
            entries[type] = Entry(type, selected = true, exists = available[type] == true)
        }
        if (controlType != null && !entries.containsKey(controlType)) {
            entries[controlType] = Entry(controlType, selected = false, exists = available[controlType] == true)
        }
    }

    fun selectedTypes(): List<Int> = entries.values.filter { it.selected }.map { it.sensorType }

    fun controlType(): Int? = controlType

    fun markRegisterAttempt(sensorType: Int, success: Boolean) {
        val entry = entries[sensorType] ?: return
        entry.registerAttempted = true
        entry.registerResult = success
        entry.samplingState = if (success) SamplingState.REGISTERED else SamplingState.REGISTER_FAILED
        if (success) {
            registrationSeq += 1
            entry.callbacksSinceRegister = 0L
            entry.lastCallbackElapsedMs = null
        }
    }

    fun markUnregistered() {
        for (entry in entries.values) {
            if (entry.samplingState == SamplingState.REGISTERED) {
                entry.samplingState = SamplingState.UNREGISTERED
            }
        }
    }

    fun onCallback(sensorType: Int, nowElapsedMs: Long) {
        val entry = entries[sensorType] ?: return
        entry.callbacks += 1
        entry.callbacksSinceRegister += 1
        entry.lastCallbackElapsedMs = nowElapsedMs
    }

    fun onShake(sensorType: Int) {
        val entry = entries[sensorType] ?: return
        entry.shakes += 1
    }

    fun totalShakes(): Long = entries.values.sumOf { it.shakes }

    fun rows(nowElapsedMs: Long): List<TypeMeasurement> = entries.values.map { entry ->
        TypeMeasurement(
            sensorType = entry.sensorType,
            exists = entry.exists,
            selected = entry.selected,
            registerAttempted = entry.registerAttempted,
            registerResult = entry.registerResult,
            samplingState = entry.samplingState,
            callbacks = entry.callbacks,
            callbacksSinceRegister = entry.callbacksSinceRegister,
            lastCallbackElapsedMs = entry.lastCallbackElapsedMs,
            gapSinceLastMs = entry.lastCallbackElapsedMs?.let { nowElapsedMs - it },
            shakes = entry.shakes
        )
    }

    fun frame(seq: Long, activityResumed: Boolean, nowElapsedMs: Long): DiagFrame = DiagFrame(
        seq = seq,
        sessionId = sessionId,
        registrationSeq = registrationSeq,
        host = host,
        activityResumed = activityResumed,
        pid = pid,
        elapsedMs = nowElapsedMs,
        controlType = controlType,
        rows = rows(nowElapsedMs)
    )

    fun isAnyRegistered(): Boolean = entries.values.any { it.samplingState == SamplingState.REGISTERED }
}
