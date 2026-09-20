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
