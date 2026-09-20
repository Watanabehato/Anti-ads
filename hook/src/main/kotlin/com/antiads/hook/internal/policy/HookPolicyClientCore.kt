package com.antiads.hook.internal.policy

import com.antiads.core.config.CachedPackagePolicy
import com.antiads.core.config.ConfigConstants
import com.antiads.core.policy.SensorAction
import com.antiads.core.policy.SensorDecision
import com.antiads.core.policy.SensorPolicyEngine
import com.antiads.core.protocol.ConfigProtocol
import com.antiads.core.status.ConfigTransportState
import com.antiads.core.status.HookProcessReport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 目标进程内的策略客户端状态机（纯 Kotlin + java.util.concurrent，无 Android 依赖）。
 *
 * 合同要点（docs/contracts.md 第 7 节）：
 * - 缓存只在内存、初始为 null、重启不恢复；
 * - 传感器回调路径只做内存操作：计数 + 原子 CAS 节流 + 提交后台任务，不做 Binder/磁盘/网络/await；
 * - 只有一个串行 worker，最多一个 in-flight；卡住的调用绝不新建替代线程、绝不堆积请求；
 * - [runFetch] 在发起请求**前**记录 requestStartedAtElapsedMs；回复合法且耗时 ≤1000ms 才发布，
 *   expiresAt = requestStartedAt + 5000（饱和加法，防溢出）；
 * - 错误/拒绝/坏 JSON 一旦确认立即清空缓存；迟到回复丢弃且不得用回复到达时间重新起算租约；
 * - 上报与刷新共用同一后台调度，合并至最多每 5 秒一条，首次可发送时立即发送。
 */
class HookPolicyClientCore(
    private val packageName: String,
    private val clock: () -> Long,
    private val scheduler: HookTaskScheduler,
    private val transport: PolicyTransport,
    private val decoder: PolicyDecoder,
    private val reportSink: (HookProcessReport) -> Unit,
    private val reportContext: () -> HookReportContext,
    private val refreshIntervalMs: Long = ConfigProtocol.REFRESH_INTERVAL_MS,
    private val leaseMs: Long = ConfigProtocol.LEASE_MS,
    private val readDeadlineMs: Long = ConfigProtocol.READ_DEADLINE_MS,
    private val reportIntervalMs: Long = ConfigProtocol.REPORT_INTERVAL_MS
) {

    /** 客户端可观测快照（测试与诊断用；不包含配置原文）。 */
    data class Snapshot(
        val cachedPolicy: CachedPackagePolicy?,
        val transportState: ConfigTransportState,
        val lastErrorCode: String?,
        val observedCallbacks: Long,
        val droppedCallbacks: Long,
        val typeResolveFailures: Long,
        val fetchSubmissions: Long,
        val reportSubmissions: Long,
        val inFlight: Boolean
    )

    @Volatile
    private var cachedPolicy: CachedPackagePolicy? = null

    @Volatile
    private var transportState: ConfigTransportState = ConfigTransportState.NEVER_READ

    @Volatile
    private var lastErrorCode: String? = null

    private val observedCallbacks = AtomicLong(0L)
    private val droppedCallbacks = AtomicLong(0L)
    private val typeResolveFailures = AtomicLong(0L)
    private val lastRequestStartedAt = AtomicLong(NEVER)
    private val lastReportAt = AtomicLong(NEVER)
    private val fetchScheduled = AtomicBoolean(false)
    private val reportScheduled = AtomicBoolean(false)
    private val fetchSubmissions = AtomicLong(0L)
    private val reportSubmissions = AtomicLong(0L)

    // ---------------------------------------------------------------- 回调快路径

    /**
     * 传感器回调快路径：只使用内存快照 + 单调时钟，返回是否丢弃本次 Java 回调。
     * 绝不阻塞、绝不等待 Future、绝不同步访问 Provider/磁盘/网络。
     */
    fun onSensorCallback(sensorType: Int, nowElapsedMs: Long): SensorDecision {
        observedCallbacks.incrementAndGet()
        val decision = SensorPolicyEngine.decide(packageName, sensorType, cachedPolicy, nowElapsedMs)
        if (decision.action == SensorAction.DROP_CALLBACK) {
            droppedCallbacks.incrementAndGet()
        }
        maybeScheduleFetch(nowElapsedMs)
        maybeScheduleReport(nowElapsedMs)
        return decision
    }

    /** 记录一次无法解析 Sensor.type 的受限计数；无论失败多少次都放行。 */
    fun recordTypeResolveFailure() {
        typeResolveFailures.incrementAndGet()
    }

    /** 取得 Context 后的首次异步读取（不受 2000ms 节流限制，但仍只有一个 in-flight）。 */
    fun requestInitialFetch() {
        if (!fetchScheduled.compareAndSet(false, true)) return
        fetchSubmissions.incrementAndGet()
        scheduler.submit(Runnable { runFetch() })
    }

    // ---------------------------------------------------------------- 后台任务

    /** 由后台 worker 调用：发起一次同步读取并处理结果。 */
    fun runFetch() {
        try {
            val startedAt = clock()
            lastRequestStartedAt.set(startedAt)
            val outcome = try {
                transport.fetch(packageName)
            } catch (t: Throwable) {
                PolicyFetchOutcome.Failed(HookTransportCodes.UNAVAILABLE)
            }
            onFetchCompleted(startedAt, outcome)
        } finally {
            fetchScheduled.set(false)
        }
    }

    /** 处理一次读取结果。测试可直接调用以精确控制开始/回复时刻（边界用例）。 */
    fun onFetchCompleted(startedAtMs: Long, outcome: PolicyFetchOutcome) {
        val replyAt = clock()
        when (outcome) {
            is PolicyFetchOutcome.Failed -> {
                cachedPolicy = null
                publishState(ConfigTransportState.UNAVAILABLE, outcome.errorCode)
            }

            is PolicyFetchOutcome.Reply -> handleReply(startedAtMs, replyAt, outcome)
        }
    }

    private fun handleReply(startedAtMs: Long, replyAt: Long, reply: PolicyFetchOutcome.Reply) {
        if (!reply.ok) {
            // 拒绝/限流/内部错误：一旦确认立即清空缓存并放行。
            cachedPolicy = null
            val code = reply.errorCode ?: HookTransportCodes.UNAVAILABLE
            publishState(mapErrorToState(code), code)
            return
        }
        val payload = reply.payloadJson
        if (payload == null) {
            cachedPolicy = null
            publishState(ConfigTransportState.INVALID, HookTransportCodes.MISSING_PAYLOAD)
            return
        }
        val policy = try {
            decoder.decode(payload, packageName)
        } catch (t: Throwable) {
            null
        }
        if (policy == null) {
            cachedPolicy = null
            publishState(ConfigTransportState.INVALID, HookTransportCodes.INVALID_PAYLOAD)
            return
        }
        val elapsed = replyAt - startedAtMs
        if (elapsed < 0L) {
            // 时钟回退：不发布，立即失效并放行。
            cachedPolicy = null
            publishState(ConfigTransportState.INVALID, HookTransportCodes.TIME_REWIND)
            return
        }
        if (elapsed > readDeadlineMs) {
            // 迟到回复：丢弃，旧缓存只能活到它自己的租约期限（绝不用到达时间重新起算）。
            val fresh = cachedPolicy?.let { replyAt < it.expiresAtElapsedMs } ?: false
            publishState(
                if (fresh) transportState else ConfigTransportState.EXPIRED,
                HookTransportCodes.REPLY_DEADLINE_EXCEEDED
            )
            return
        }
        cachedPolicy = CachedPackagePolicy(
            policy = policy,
            requestStartedAtElapsedMs = startedAtMs,
            expiresAtElapsedMs = saturatingAdd(startedAtMs, leaseMs)
        )
        publishState(ConfigTransportState.OK, null)
    }

    private fun maybeScheduleFetch(nowElapsedMs: Long) {
        if (!refreshDue(nowElapsedMs)) return
        if (!fetchScheduled.compareAndSet(false, true)) return
        fetchSubmissions.incrementAndGet()
        scheduler.submit(Runnable { runFetch() })
    }

    private fun refreshDue(nowElapsedMs: Long): Boolean {
        val last = lastRequestStartedAt.get()
        if (last == NEVER) return true
        // 时钟回退不触发风暴：每次读取都会重写 lastRequestStartedAt。
        if (nowElapsedMs < last) return true
        return nowElapsedMs - last >= refreshIntervalMs
    }

    private fun maybeScheduleReport(nowElapsedMs: Long) {
        if (!reportDue(nowElapsedMs)) return
        if (!reportScheduled.compareAndSet(false, true)) return
        // 发送尝试即计时：失败也不立即重试（合同：报告合并至 ≤1 条/5s）。
        lastReportAt.set(nowElapsedMs)
        reportSubmissions.incrementAndGet()
        scheduler.submit(Runnable { runReport() })
    }

    private fun reportDue(nowElapsedMs: Long): Boolean {
        val last = lastReportAt.get()
        if (last == NEVER) return true
        if (nowElapsedMs < last) return true
        return nowElapsedMs - last >= reportIntervalMs
    }

    /** 由后台 worker 调用：构造并投递一条进程报告（不权威、不改变授权）。 */
    fun runReport() {
        try {
            val context = reportContext()
            val snapshot = snapshot()
            val report = HookProcessReport(
                schemaVersion = ConfigConstants.SCHEMA_VERSION_V1,
                packageName = packageName,
                processName = context.processName,
                processToken = context.processToken,
                pid = context.pid,
                apiLevel = context.apiLevel,
                installState = context.installState,
                transportState = snapshot.transportState,
                policyRevision = snapshot.cachedPolicy?.policy?.revision,
                observedCallbacks = snapshot.observedCallbacks,
                droppedCallbacks = snapshot.droppedCallbacks,
                lastErrorCode = snapshot.lastErrorCode
            )
            reportSink(report)
        } catch (t: Throwable) {
            // 上报失败不影响防护，也绝不影响回调路径。
        } finally {
            reportScheduled.set(false)
        }
    }

    // ---------------------------------------------------------------- 诊断读取

    fun snapshot(): Snapshot = Snapshot(
        cachedPolicy = cachedPolicy,
        transportState = transportState,
        lastErrorCode = lastErrorCode,
        observedCallbacks = observedCallbacks.get(),
        droppedCallbacks = droppedCallbacks.get(),
        typeResolveFailures = typeResolveFailures.get(),
        fetchSubmissions = fetchSubmissions.get(),
        reportSubmissions = reportSubmissions.get(),
        inFlight = fetchScheduled.get()
    )

    private fun publishState(state: ConfigTransportState, errorCode: String?) {
        transportState = state
        lastErrorCode = errorCode
    }

    private fun mapErrorToState(errorCode: String): ConfigTransportState = when (errorCode) {
        ConfigProtocol.ERROR_UNAUTHORIZED,
        ConfigProtocol.ERROR_SHARED_UID_UNSUPPORTED,
        ConfigProtocol.ERROR_ISOLATED_OR_UNKNOWN_UID -> ConfigTransportState.UNAUTHORIZED

        ConfigProtocol.ERROR_UNSUPPORTED_VERSION,
        ConfigProtocol.ERROR_INVALID_REQUEST -> ConfigTransportState.INVALID

        else -> ConfigTransportState.UNAVAILABLE
    }

    private companion object {
        const val NEVER: Long = Long.MIN_VALUE

        fun saturatingAdd(base: Long, delta: Long): Long {
            if (base < 0L || delta <= 0L) return -1L
            val sum = base + delta
            return if (sum < base) Long.MAX_VALUE else sum
        }
    }
}
