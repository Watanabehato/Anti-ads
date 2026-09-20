package com.antiads.accessibility

/**
 * 前台 epoch：包 + windowId 相同即同一 epoch。
 *
 * epoch 只由事件（或首次连接时观察到的当前窗口）建立；相同窗口的重复 state/content 事件
 * 不重置 epoch、也不延长窗口期（窗口期以 foregroundSinceElapsedMs 计算）。
 */
internal data class ForegroundEpoch(
    val packageName: String,
    val windowId: Int,
    val foregroundSinceElapsedMs: Long,
    val attempted: Boolean = false
)

/** 一次事件的下场：立即扫描、延迟合并扫描，或丢弃（带拒绝码）。 */
internal sealed interface GateEvent {
    data class ScanNow(val epoch: ForegroundEpoch) : GateEvent
    data class ScanLater(val delayMs: Long, val epoch: ForegroundEpoch) : GateEvent
    data class Drop(val reason: String) : GateEvent
}

/** 已到期的排队扫描；服务据此构建快照。 */
internal data class PendingScan(
    val packageName: String,
    val windowId: Int,
    val foregroundSinceElapsedMs: Long
)

/** 执行前复核的全部输入。 */
internal data class ClickRequest(
    val nowElapsedMs: Long,
    val packageName: String,
    val windowId: Int,
    val policy: PolicyGuardInput,
    val window: WindowGuardInput,
    val candidate: CandidateGuardInput
)

/** 是否真的调用 performAction。reason=OK 且 perform=true 时才动作。 */
internal data class ClickAuthorization(val perform: Boolean, val reason: String)

/**
 * 免 Root 无障碍跳过的执行约束状态机（纯逻辑，无 Android 依赖，可精确单测）。
 *
 * 负责：事件类型过滤、foreground epoch 建立与取消、250ms 事件合并、同 epoch 只尝试一次、
 * 同包 2000ms 冷却、执行前复核编排。所有时间都由调用方以 elapsedRealtime 数值传入。
 */
internal class SkipGate(private val acceptedEventTypes: Set<Int>) {

    private var epoch: ForegroundEpoch? = null
    private var pendingScanAtElapsedMs: Long? = null
    private var lastScanAtElapsedMs: Long = Long.MIN_VALUE
    private val lastAttemptAtByPackage = LinkedHashMap<String, Long>()

    val currentEpoch: ForegroundEpoch?
        get() = epoch

    fun pendingScanAt(): Long? = pendingScanAtElapsedMs

    /** 同包冷却表条目数（仅用于测试与诊断，不暴露给产品层）。 */
    fun throttleEntryCount(): Int = lastAttemptAtByPackage.size

    fun lastAttemptAt(packageName: String): Long? = lastAttemptAtByPackage[packageName]

    /**
     * 处理一个无障碍事件。只有合同允许的两类窗口事件且包名可确定的输入才会进入状态机。
     */
    fun onEvent(eventType: Int, packageName: String?, windowId: Int, nowElapsedMs: Long): GateEvent {
        if (eventType !in acceptedEventTypes) return GateEvent.Drop(GuardCode.EVENT_TYPE_FILTERED)
        if (packageName.isNullOrEmpty()) return GateEvent.Drop(GuardCode.WINDOW_IDENTITY_UNKNOWN)

        val current = epoch
        if (current == null || current.packageName != packageName || current.windowId != windowId) {
            // 前台包或 windowId 变化：新建 epoch 并取消旧任务（含已排队的扫描）
            pendingScanAtElapsedMs = null
            epoch = ForegroundEpoch(packageName, windowId, nowElapsedMs)
        }
        return schedule(nowElapsedMs)
    }

    /**
     * 首次连接时把当前窗口作为新 epoch（contracts 第 5 节允许）；仍遵守全部后续过滤。
     */
    fun startInitialEpoch(packageName: String?, windowId: Int, nowElapsedMs: Long): GateEvent {
        if (packageName.isNullOrEmpty()) return GateEvent.Drop(GuardCode.WINDOW_IDENTITY_UNKNOWN)
        pendingScanAtElapsedMs = null
        epoch = ForegroundEpoch(packageName, windowId, nowElapsedMs)
        return schedule(nowElapsedMs)
    }

    /** 取走已到期的排队扫描；未到期、被取消或无 epoch 时返回 null。 */
    fun takeDueScan(nowElapsedMs: Long): PendingScan? {
        val pending = pendingScanAtElapsedMs ?: return null
        if (pending > nowElapsedMs) return null
        pendingScanAtElapsedMs = null
        lastScanAtElapsedMs = nowElapsedMs
        val current = epoch ?: return null
        return PendingScan(current.packageName, current.windowId, current.foregroundSinceElapsedMs)
    }

    /**
     * 取消所有排队动作（停用/撤权/关闭/中断）。这里只丢弃延迟工作与 epoch 关联的排队扫描，
     * 是否还能动作仍由 evaluate 的目标选择复核决定。
     */
    fun cancelPending(@Suppress("UNUSED_PARAMETER") reason: String) {
        pendingScanAtElapsedMs = null
    }

    /** 服务销毁/解绑：清空全部状态。 */
    fun reset() {
        epoch = null
        pendingScanAtElapsedMs = null
        lastScanAtElapsedMs = Long.MIN_VALUE
        lastAttemptAtByPackage.clear()
    }

    /** 执行前复核（不消耗 epoch 尝试次数）。 */
    fun evaluate(request: ClickRequest): GuardDecision {
        val current = epoch ?: return GuardDecision.deny(GuardCode.NOT_WATCHING)
        if (current.packageName != request.packageName || current.windowId != request.windowId) {
            return GuardDecision.deny(GuardCode.WINDOW_CHANGED)
        }
        if (current.attempted) return GuardDecision.deny(GuardCode.EPOCH_ALREADY_ATTEMPTED)
        val lastAttempt = lastAttemptAtByPackage[request.packageName]
        if (lastAttempt != null &&
            request.nowElapsedMs - lastAttempt < SkipLimits.SAME_PACKAGE_MIN_INTERVAL_MS
        ) {
            return GuardDecision.deny(GuardCode.THROTTLED)
        }
        ExecutionGuards.checkPolicy(request.policy).let { if (!it.allowed) return it }
        ExecutionGuards.checkWindow(request.window, request.nowElapsedMs).let { if (!it.allowed) return it }
        return ExecutionGuards.checkCandidate(request.candidate)
    }

    /**
     * 授权一次点击：先判定，再把“本 epoch 已尝试”固定下来，然后才由调用方执行 performAction。
     *
     * 顺序是合同要求的关键点——即使系统随后返回 false（动作被拒绝），该 epoch 也已消耗，
     * 不会紧密重试；同包 2000ms 冷却同样以“尝试”计数。
     */
    fun authorizeClick(request: ClickRequest): ClickAuthorization {
        val decision = evaluate(request)
        if (!decision.allowed) return ClickAuthorization(perform = false, reason = decision.reason)
        recordAttempt(request.packageName, request.nowElapsedMs)
        return ClickAuthorization(perform = true, reason = GuardCode.OK)
    }

    /** 记录一次动作尝试（无论系统返回 true 还是 false）。 */
    fun recordAttempt(packageName: String, nowElapsedMs: Long) {
        epoch = epoch?.copy(attempted = true)
        lastAttemptAtByPackage[packageName] = nowElapsedMs
        trimThrottleTable()
    }

    private fun trimThrottleTable() {
        while (lastAttemptAtByPackage.size > SkipLimits.MAX_THROTTLE_ENTRIES) {
            val oldestKey = lastAttemptAtByPackage.entries.minByOrNull { it.value }?.key ?: return
            lastAttemptAtByPackage.remove(oldestKey)
        }
    }

    private fun schedule(nowElapsedMs: Long): GateEvent {
        val current = epoch ?: return GateEvent.Drop(GuardCode.NOT_WATCHING)
        if (current.attempted) return GateEvent.Drop(GuardCode.EPOCH_ALREADY_ATTEMPTED)

        val pending = pendingScanAtElapsedMs
        if (pending != null && pending <= nowElapsedMs) {
            pendingScanAtElapsedMs = null
            lastScanAtElapsedMs = nowElapsedMs
            return GateEvent.ScanNow(current)
        }

        val earliest = if (lastScanAtElapsedMs == Long.MIN_VALUE) {
            nowElapsedMs
        } else {
            maxOf(nowElapsedMs, lastScanAtElapsedMs + SkipLimits.SCAN_MIN_INTERVAL_MS)
        }
        if (pending == null && earliest <= nowElapsedMs) {
            lastScanAtElapsedMs = nowElapsedMs
            return GateEvent.ScanNow(current)
        }

        // 合并事件：保持最早的到期时间，不让后续事件把扫描一直往后推。
        val next = if (pending == null) earliest else minOf(pending, earliest)
        pendingScanAtElapsedMs = next
        return GateEvent.ScanLater(next - nowElapsedMs, current)
    }
}
