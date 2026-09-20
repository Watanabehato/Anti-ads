package com.antiads.hook.internal.policy

import com.antiads.core.config.PackagePolicy
import com.antiads.core.status.HookInstallState

/**
 * Hook 进程内策略客户端的抽象边界（纯 Kotlin，JVM 单测可注入假实现）。
 *
 * 设计约束（docs/contracts.md 第 6、7 节）：
 * - 传输层在**后台线程**同步执行一次 ContentResolver.call；
 * - 调度层保证"单一串行 worker、最多一个 in-flight、队列不超过一个"，绝不新建替代线程；
 * - 解码层负责授权/结构/包名/schema 校验，任何不合法一律返回 null 让上层立即清缓存并放行。
 */
sealed interface PolicyFetchOutcome {

    /** 收到了 Provider 回复。ok=false 时 [errorCode] 为规范错误码或客户端传输码。 */
    data class Reply(
        val ok: Boolean,
        val payloadJson: String?,
        val errorCode: String?
    ) : PolicyFetchOutcome

    /** 无法完成调用（找不到 Provider、SecurityException、远端异常等），统一按不可达处理。 */
    data class Failed(val errorCode: String) : PolicyFetchOutcome
}

/** 客户端侧传输失败码（不属于 Provider 协议错误码，仅用于报告 lastErrorCode）。 */
object HookTransportCodes {
    const val UNAVAILABLE: String = "UNAVAILABLE"
    const val MISSING_PAYLOAD: String = "MISSING_PAYLOAD"
    const val INVALID_PAYLOAD: String = "INVALID_PAYLOAD"
    const val REPLY_DEADLINE_EXCEEDED: String = "REPLY_DEADLINE_EXCEEDED"
    const val TIME_REWIND: String = "TIME_REWIND"

    /** 无法确定 Sensor.type 的受限计数原因（只计数，不拦截）。 */
    const val TYPE_RESOLVE_FAILED: String = "TYPE_RESOLVE_FAILED"
}

/** 后台任务调度：真实实现是单线程队列（SingleThreadWorker），测试可注入可控实现。 */
fun interface HookTaskScheduler {
    fun submit(task: Runnable)
}

/** 同步读取一次策略；只在后台 worker 线程调用，绝不在传感器回调路径调用。 */
fun interface PolicyTransport {
    fun fetch(packageName: String): PolicyFetchOutcome
}

/** 解码并校验策略；任何不合法（坏 JSON、未知 schema、包名不符、类型越界）返回 null。 */
fun interface PolicyDecoder {
    fun decode(payloadJson: String, expectedPackageName: String): PackagePolicy?
}

/** 上报所需的进程级事实（纯数据，由 Android 层在报告时刻提供）。 */
data class HookReportContext(
    val processName: String,
    val processToken: String,
    val pid: Int,
    val apiLevel: Int,
    val installState: HookInstallState
)
