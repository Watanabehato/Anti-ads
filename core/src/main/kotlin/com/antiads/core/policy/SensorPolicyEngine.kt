package com.antiads.core.policy

import com.antiads.core.config.CachedPackagePolicy
import com.antiads.core.config.ConfigConstants

/** 传感器回调决策结果。DROP_CALLBACK 仅用于 Java 分发路径，不代表应用行为已恢复。 */
enum class SensorAction { ALLOW, DROP_CALLBACK }

/** reason 为稳定英文码，仅用于可解释状态与测试，不依赖异常文本。 */
data class SensorDecision(val action: SensorAction, val reason: String)

/**
 * 传感器策略纯函数（冻结签名与必要条件见 docs/contracts.md 第 4 节）。
 *
 * core 不读取系统时钟：调用者显式传入 Android SystemClock.elapsedRealtime() 数值。
 * 任何不确定情况（无快照、未知 schema、包名不符、时间异常、租约异常）一律 ALLOW。
 */
object SensorPolicyEngine {

    /** 允许的租约时长范围（毫秒）。 */
    const val MIN_LEASE_MS: Long = 1L
    const val MAX_LEASE_MS: Long = 5000L

    const val REASON_DISABLED: String = "DISABLED"
    const val REASON_NO_POLICY: String = "NO_POLICY"
    const val REASON_EXPIRED: String = "EXPIRED"
    const val REASON_PACKAGE_MISMATCH: String = "PACKAGE_MISMATCH"
    const val REASON_TYPE_NOT_SELECTED: String = "TYPE_NOT_SELECTED"
    const val REASON_INVALID_POLICY: String = "INVALID_POLICY"
    const val REASON_BLOCK_SELECTED_TYPE: String = "BLOCK_SELECTED_TYPE"

    fun decide(
        packageName: String,
        sensorType: Int,
        cachedPolicy: CachedPackagePolicy?,
        nowElapsedMs: Long
    ): SensorDecision {
        val cached = cachedPolicy ?: return allow(REASON_NO_POLICY)
        val policy = cached.policy

        if (policy.schemaVersion != ConfigConstants.SCHEMA_VERSION_V1) return allow(REASON_INVALID_POLICY)
        if (policy.packageName != packageName) return allow(REASON_PACKAGE_MISMATCH)
        if (!policy.hookEnabled) return allow(REASON_DISABLED)

        val start = cached.requestStartedAtElapsedMs
        val expires = cached.expiresAtElapsedMs

        // 负数、未来时间戳、时钟回退、溢出、异常租约一律放行
        if (start < 0L || expires < 0L || nowElapsedMs < 0L) return allow(REASON_INVALID_POLICY)
        if (nowElapsedMs < start) return allow(REASON_INVALID_POLICY)
        val lease = expires - start
        if (expires < start || lease < MIN_LEASE_MS || lease > MAX_LEASE_MS) return allow(REASON_INVALID_POLICY)

        // 边界：now >= expires 必须放行
        if (nowElapsedMs >= expires) return allow(REASON_EXPIRED)

        if (sensorType !in policy.blockedSensorTypes) return allow(REASON_TYPE_NOT_SELECTED)

        return SensorDecision(SensorAction.DROP_CALLBACK, REASON_BLOCK_SELECTED_TYPE)
    }

    private fun allow(reason: String) = SensorDecision(SensorAction.ALLOW, reason)
}
