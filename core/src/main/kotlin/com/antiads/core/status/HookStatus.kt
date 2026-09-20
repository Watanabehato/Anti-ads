package com.antiads.core.status

import kotlinx.serialization.Serializable

/** Java 传感器分发 Hook 的安装事实。UNSUPPORTED/ERROR 必须原样上报，不能降级为“无报告”。 */
@Serializable
enum class HookInstallState { WAITING_CONTEXT, INSTALLED, UNSUPPORTED, ERROR }

/** 目标进程读取宿主配置的传输状态。 */
@Serializable
enum class ConfigTransportState { NEVER_READ, OK, UNAVAILABLE, UNAUTHORIZED, INVALID, EXPIRED }

/**
 * 目标进程自报状态（不是可信框架证明，也不是授权依据）。
 *
 * 计数每次进程实例独立；processToken 每次进程实例生成新 UUID。
 */
@Serializable
data class HookProcessReport(
    val schemaVersion: Int = 1,
    val packageName: String,
    val processName: String,
    val processToken: String,
    val pid: Int,
    val apiLevel: Int,
    val installState: HookInstallState,
    val transportState: ConfigTransportState,
    val policyRevision: Long?,
    val observedCallbacks: Long,
    val droppedCallbacks: Long,
    val lastErrorCode: String? = null
)

/** receivedAtElapsedMs 由服务端自己的 elapsedRealtime 戳记，不信任请求时间。 */
data class ReceivedHookReport(
    val report: HookProcessReport,
    val receivedAtElapsedMs: Long
)
