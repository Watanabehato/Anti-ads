package com.antiads.hook.internal.policy

import com.antiads.core.status.HookInstallState
import com.antiads.core.status.HookProcessReport

/** 可控单调时钟（毫秒），语义等同 SystemClock.elapsedRealtime() 数值。 */
internal class FakeClock(var nowMs: Long = 0L) {
    fun set(value: Long) {
        nowMs = value
    }

    fun advance(delta: Long) {
        nowMs += delta
    }
}

/** 记录被提交的后台任务；runAll() 立即串行执行（等价于 worker 始终空闲）。 */
internal class RecordingScheduler : HookTaskScheduler {
    private val tasks = ArrayDeque<Runnable>()

    var submitted: Int = 0
        private set

    override fun submit(task: Runnable) {
        submitted += 1
        tasks.addLast(task)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
        }
    }

    fun pendingCount(): Int = tasks.size
}

/** 可编程传输层：优先使用 enqueue 的脚本，否则使用 onFetch。 */
internal class FakeTransport : PolicyTransport {
    var calls: Int = 0
        private set

    var onFetch: (() -> PolicyFetchOutcome)? = null

    private val scripted = ArrayDeque<() -> PolicyFetchOutcome>()

    fun enqueue(handler: () -> PolicyFetchOutcome) {
        scripted.addLast(handler)
    }

    override fun fetch(packageName: String): PolicyFetchOutcome {
        calls += 1
        val handler = scripted.removeFirstOrNull() ?: onFetch ?: error("no programmed outcome")
        return handler()
    }
}

/**
 * 生成 PackagePolicy JSON：字段与 core ConfigCodec 一致，另可注入额外字段做负例。
 * 这里用单引号字符拼接，避免测试代码里出现难读的多重转义。
 */
internal fun policyJson(
    packageName: String = "com.antiads.probe",
    revision: Long = 7L,
    hookEnabled: Boolean = true,
    blockedSensorTypes: Collection<Int> = setOf(1, 4, 9, 10, 11),
    schemaVersion: Int = 1,
    /** 原样注入的额外 JSON 片段（不含前导逗号），用于未知字段/超长等负例。 */
    extraRawJson: String = ""
): String {
    val q = '"'
    val types = blockedSensorTypes.joinToString(separator = ",")
    val extra = if (extraRawJson.isEmpty()) "" else "," + extraRawJson
    return "{" + q + "schemaVersion" + q + ":" + schemaVersion + "," +
        q + "revision" + q + ":" + revision + "," +
        q + "packageName" + q + ":" + q + packageName + q + "," +
        q + "hookEnabled" + q + ":" + hookEnabled + "," +
        q + "blockedSensorTypes" + q + ":[" + types + "]" + extra + "}"
}

internal fun testReportContext(
    packageName: String = "com.antiads.probe",
    installState: HookInstallState = HookInstallState.INSTALLED
): HookReportContext = HookReportContext(
    processName = packageName + ":main",
    processToken = "00000000-0000-4000-8000-000000000000",
    pid = 4321,
    apiLevel = 29,
    installState = installState
)

internal fun core(
    clock: FakeClock,
    scheduler: HookTaskScheduler,
    transport: PolicyTransport,
    packageName: String = "com.antiads.probe",
    reportSink: (HookProcessReport) -> Unit = {},
    installState: HookInstallState = HookInstallState.INSTALLED
): HookPolicyClientCore = HookPolicyClientCore(
    packageName = packageName,
    clock = { clock.nowMs },
    scheduler = scheduler,
    transport = transport,
    decoder = { json, expected -> PolicyJson.decodePolicy(json, expected) },
    reportSink = reportSink,
    reportContext = { testReportContext(packageName, installState) }
)
