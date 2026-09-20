package com.antiads.probe.diag

/**
 * 诊断数据模型与机器可读输出（docs/hook-probe-observability.md 第 2 节）。
 *
 * 这些类型是 probe 的**内部**诊断面：不改变 contracts 第 8 节的公共界面约定，
 * 只为 QA 提供同一注册（registrationSeq/sessionId/pid）与逐类型计数证据。
 */
enum class SamplingState { IDLE, REGISTERED, REGISTER_FAILED, UNREGISTERED }

/** 承载方式：公共界面固定为 ACTIVITY；instrumentation 会话用它证明"同一注册"不随 Activity 生命周期变化。 */
enum class DiagHost { ACTIVITY, INSTRUMENTATION }

data class TypeMeasurement(
    val sensorType: Int,
    val exists: Boolean,
    val selected: Boolean,
    val registerAttempted: Boolean,
    val registerResult: Boolean?,
    val samplingState: SamplingState,
    val callbacks: Long,
    val callbacksSinceRegister: Long,
    val lastCallbackElapsedMs: Long?,
    val gapSinceLastMs: Long?,
    val shakes: Long
)

data class DiagFrame(
    val seq: Long,
    val sessionId: String,
    val registrationSeq: Int,
    val host: DiagHost,
    val activityResumed: Boolean,
    val pid: Int,
    val elapsedMs: Long,
    val controlType: Int?,
    val rows: List<TypeMeasurement>
)
