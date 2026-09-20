package com.antiads.probe.diag

/**
 * 诊断行格式化：一行一条 JSON，字段顺序固定、无外部依赖、可在纯 JVM 单测中精确断言。
 *
 * 只包含计数、状态与时间戳；不含界面文本、传感器读数或包列表。
 */
object DiagJson {

    const val LOG_TAG: String = "AntiAdsProbe.Diag"

    fun row(frame: DiagFrame, row: TypeMeasurement): String = buildString {
        append('{')
        appendField("tag", LOG_TAG)
        append(',')
        appendField("seq", frame.seq)
        append(',')
        appendField("sessionId", frame.sessionId)
        append(',')
        appendField("registrationSeq", frame.registrationSeq)
        append(',')
        appendField("host", frame.host.name)
        append(',')
        appendField("activityResumed", frame.activityResumed)
        append(',')
        appendField("pid", frame.pid)
        append(',')
        appendField("elapsedMs", frame.elapsedMs)
        append(',')
        appendField("type", row.sensorType)
        append(',')
        appendField("exists", row.exists)
        append(',')
        appendField("selected", row.selected)
        append(',')
        appendField("registerResult", row.registerResult)
        append(',')
        appendField("samplingState", row.samplingState.name)
        append(',')
        appendField("callbacks", row.callbacks)
        append(',')
        appendField("callbacksSinceRegister", row.callbacksSinceRegister)
        append(',')
        appendField("lastCallbackElapsedMs", row.lastCallbackElapsedMs)
        append(',')
        appendField("gapSinceLastMs", row.gapSinceLastMs)
        append(',')
        appendField("shakes", row.shakes)
        append(',')
        appendField("control", frame.controlType != null && frame.controlType == row.sensorType)
        append('}')
    }

    /**
     * 会话汇总行：QA 用 verdict 判定该次实验是否可判定。
     * controlContinuous=false（对照类型在观察窗内没有持续回调）时必须标 INCONCLUSIVE，
     * 不能把"后台传感器限制/息屏"误判为拦截生效。
     */
    fun summary(
        frame: DiagFrame,
        verdict: String,
        controlContinuous: Boolean,
        note: String?
    ): String = buildString {
        append('{')
        appendField("tag", LOG_TAG)
        append(',')
        appendField("kind", "summary")
        append(',')
        appendField("sessionId", frame.sessionId)
        append(',')
        appendField("registrationSeq", frame.registrationSeq)
        append(',')
        appendField("host", frame.host.name)
        append(',')
        appendField("pid", frame.pid)
        append(',')
        appendField("elapsedMs", frame.elapsedMs)
        append(',')
        appendField("controlType", frame.controlType)
        append(',')
        appendField("controlContinuous", controlContinuous)
        append(',')
        appendField("verdict", verdict)
        append(',')
        appendField("note", note)
        append('}')
    }

    /** 静默窗口（同一注册内的回调缺口），用于恢复时间证据。 */
    fun silenceWindow(sensorType: Int, lastBeforeMs: Long, resumedAtMs: Long, silenceMs: Long): String = buildString {
        append('{')
        appendField("tag", LOG_TAG)
        append(',')
        appendField("kind", "silenceWindow")
        append(',')
        appendField("type", sensorType)
        append(',')
        appendField("lastCallbackBeforeGapMs", lastBeforeMs)
        append(',')
        appendField("resumedAtMs", resumedAtMs)
        append(',')
        appendField("silenceMs", silenceMs)
        append('}')
    }

    /**
     * Activity 诊断快照（docs/probe.md 承诺的 debuggable 文件内容）。
     *
     * 结构固定为对象：`schemaVersion/kind/sessionId/registrationSeq/host/pid/activityResumed/
     * samplingRunning/elapsedMs/writtenAtElapsedMs/controlType/rows[]`，
     * 只含计数、状态与时间戳——**不含**界面文本、输入内容、包列表或传感器读数。
     */
    fun snapshot(frame: DiagFrame, writtenAtElapsedMs: Long, samplingRunning: Boolean): String = buildString {
        append('{')
        appendField("tag", LOG_TAG)
        append(',')
        appendField("kind", SNAPSHOT_KIND)
        append(',')
        appendField("schemaVersion", SNAPSHOT_SCHEMA_VERSION)
        append(',')
        appendField("sessionId", frame.sessionId)
        append(',')
        appendField("registrationSeq", frame.registrationSeq)
        append(',')
        appendField("host", frame.host.name)
        append(',')
        appendField("pid", frame.pid)
        append(',')
        appendField("activityResumed", frame.activityResumed)
        append(',')
        appendField("samplingRunning", samplingRunning)
        append(',')
        appendField("elapsedMs", frame.elapsedMs)
        append(',')
        appendField("writtenAtElapsedMs", writtenAtElapsedMs)
        append(',')
        appendField("controlType", frame.controlType)
        append(',')
        append('"').append("rows").append('"').append(':').append('[')
        frame.rows.forEachIndexed { index, row ->
            if (index > 0) append(',')
            append(row(frame, row))
        }
        append(']')
        append('}')
    }

    /** 写快照成功的诊断行（便于 QA 在 logcat 里确认文件确实生成）。 */
    fun snapshotFile(path: String, force: Boolean, elapsedMs: Long): String = buildString {
        append('{')
        appendField("tag", LOG_TAG)
        append(',')
        appendField("kind", "snapshotFile")
        append(',')
        appendField("force", force)
        append(',')
        appendField("path", path)
        append(',')
        appendField("elapsedMs", elapsedMs)
        append('}')
    }

    const val SNAPSHOT_KIND: String = "probeDiagSnapshot"
    const val SNAPSHOT_SCHEMA_VERSION: Int = 1

    private fun StringBuilder.appendField(name: String, value: String?) {
        append('"').append(name).append("\":")
        if (value == null) {
            append("null")
        } else {
            append('"').append(escape(value)).append('"')
        }
    }

    private fun StringBuilder.appendField(name: String, value: Long?) {
        append('"').append(name).append("\":").append(value ?: "null")
    }

    private fun StringBuilder.appendField(name: String, value: Int?) {
        append('"').append(name).append("\":").append(value ?: "null")
    }

    private fun StringBuilder.appendField(name: String, value: Boolean?) {
        append('"').append(name).append("\":").append(value ?: "null")
    }

    private fun escape(value: String): String {
        val builder = StringBuilder(value.length)
        for (character in value) {
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (character < ' ') builder.append("?") else builder.append(character)
            }
        }
        return builder.toString()
    }
}
