package com.antiads.probe.diag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断行格式必须是可机器解析的一行 JSON，且不包含界面文本/传感器读数。 */
class DiagJsonTest {

    private fun frame(rows: List<TypeMeasurement>) = DiagFrame(
        seq = 3L,
        sessionId = "11111111-2222-4333-8444-555555555555",
        registrationSeq = 2,
        host = DiagHost.INSTRUMENTATION,
        activityResumed = false,
        pid = 1234,
        elapsedMs = 98765L,
        controlType = 2,
        rows = rows
    )

    private fun row(
        sensorType: Int = 1,
        selected: Boolean = true,
        registerResult: Boolean? = true,
        state: SamplingState = SamplingState.REGISTERED,
        callbacks: Long = 42L,
        lastCallback: Long? = 98700L
    ) = TypeMeasurement(
        sensorType = sensorType,
        exists = true,
        selected = selected,
        registerAttempted = true,
        registerResult = registerResult,
        samplingState = state,
        callbacks = callbacks,
        callbacksSinceRegister = callbacks,
        lastCallbackElapsedMs = lastCallback,
        gapSinceLastMs = lastCallback?.let { 98765L - it },
        shakes = 2L
    )

    @Test
    fun rowLineCarriesIdentityAndCounters() {
        val line = DiagJson.row(frame(listOf(row())), row())
        assertTrue(line.contains("\"tag\":\"" + DiagJson.LOG_TAG + "\""))
        assertTrue(line.contains("\"sessionId\":\"11111111-2222-4333-8444-555555555555\""))
        assertTrue(line.contains("\"registrationSeq\":2"))
        assertTrue(line.contains("\"host\":\"INSTRUMENTATION\""))
        assertTrue(line.contains("\"activityResumed\":false"))
        assertTrue(line.contains("\"type\":1"))
        assertTrue(line.contains("\"callbacks\":42"))
        assertTrue(line.contains("\"samplingState\":\"REGISTERED\""))
        assertTrue(line.contains("\"control\":false"))
        assertTrue("行必须单行输出", !line.contains("\n"))
        assertTrue(line.startsWith("{") && line.endsWith("}"))
    }

    @Test
    fun nullFieldsAreEmittedAsJsonNull() {
        val line = DiagJson.row(
            frame(listOf(row(registerResult = null, lastCallback = null))),
            row(registerResult = null, lastCallback = null)
        )
        assertTrue(line.contains("\"registerResult\":null"))
        assertTrue(line.contains("\"lastCallbackElapsedMs\":null"))
        assertTrue(line.contains("\"gapSinceLastMs\":null"))
    }

    @Test
    fun controlRowIsMarked() {
        val controlRow = row(sensorType = 2, selected = false)
        val line = DiagJson.row(frame(listOf(controlRow)), controlRow)
        assertTrue(line.contains("\"control\":true"))
        assertTrue(line.contains("\"selected\":false"))
    }

    @Test
    fun summaryCarriesVerdictAndControlGate() {
        val line = DiagJson.summary(
            frame(listOf(row())),
            verdict = "INCONCLUSIVE_CONTROL_SILENT",
            controlContinuous = false,
            note = "no_callbacks_during_window"
        )
        assertTrue(line.contains("\"kind\":\"summary\""))
        assertTrue(line.contains("\"verdict\":\"INCONCLUSIVE_CONTROL_SILENT\""))
        assertTrue(line.contains("\"controlContinuous\":false"))
        assertTrue(line.contains("\"controlType\":2"))
    }

    @Test
    fun noteIsEscaped() {
        val line = DiagJson.summary(frame(listOf(row())), "X", true, "quote\"and\\slash")
        assertTrue(line.contains("quote\\\"and\\\\slash"))
    }

    @Test
    fun snapshotIsParseableAndCarriesOnlyDiagFields() {
        val json = DiagJson.snapshot(
            frame(listOf(row(), row(sensorType = 2, selected = false))),
            writtenAtElapsedMs = 99_000L,
            samplingRunning = true
        )
        assertTrue("文件内容必须是单行 JSON", !json.contains("\n"))

        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(
            setOf(
                "tag", "kind", "schemaVersion", "sessionId", "registrationSeq", "host", "pid",
                "activityResumed", "samplingRunning", "elapsedMs", "writtenAtElapsedMs",
                "controlType", "rows"
            ),
            parsed.keys
        )
        assertEquals(DiagJson.LOG_TAG, parsed["tag"]!!.jsonPrimitive.content)
        assertEquals(DiagJson.SNAPSHOT_KIND, parsed["kind"]!!.jsonPrimitive.content)
        assertEquals(DiagJson.SNAPSHOT_SCHEMA_VERSION, parsed["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("11111111-2222-4333-8444-555555555555", parsed["sessionId"]!!.jsonPrimitive.content)
        assertEquals(2, parsed["registrationSeq"]!!.jsonPrimitive.int)
        assertEquals("INSTRUMENTATION", parsed["host"]!!.jsonPrimitive.content)
        assertEquals(true, parsed["samplingRunning"]!!.jsonPrimitive.boolean)
        assertEquals(99_000L, parsed["writtenAtElapsedMs"]!!.jsonPrimitive.int.toLong())
        assertEquals(2, parsed["controlType"]!!.jsonPrimitive.int)

        val rows = parsed["rows"]!!.jsonArray
        assertEquals(2, rows.size)
        // 行字段固定：只有计数/状态/时间戳，没有界面文本、输入内容或传感器读数
        assertEquals(
            setOf(
                "tag", "seq", "sessionId", "registrationSeq", "host", "activityResumed", "pid",
                "elapsedMs", "type", "exists", "selected", "registerResult", "samplingState",
                "callbacks", "callbacksSinceRegister", "lastCallbackElapsedMs", "gapSinceLastMs",
                "shakes", "control"
            ),
            rows[0].jsonObject.keys
        )
    }

    @Test
    fun snapshotFileLineRecordsPathAndForce() {
        val line = DiagJson.snapshotFile("/data/user/0/com.antiads.probe/files/probe-diag.json", true, 12_345L)
        assertTrue(line.contains("\"kind\":\"snapshotFile\""))
        assertTrue(line.contains("\"force\":true"))
        assertTrue(line.contains("probe-diag.json"))
        assertTrue(line.contains("\"elapsedMs\":12345"))
    }

    @Test
    fun silenceWindowLineExposesBeforeAndAfterTimestamps() {
        val line = DiagJson.silenceWindow(sensorType = 1, lastBeforeMs = 1000L, resumedAtMs = 6800L, silenceMs = 5800L)
        assertTrue(line.contains("\"kind\":\"silenceWindow\""))
        assertTrue(line.contains("\"lastCallbackBeforeGapMs\":1000"))
        assertTrue(line.contains("\"resumedAtMs\":6800"))
        assertTrue(line.contains("\"silenceMs\":5800"))
    }
}
