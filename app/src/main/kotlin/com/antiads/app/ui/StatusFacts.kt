package com.antiads.app.ui

import com.antiads.core.protocol.ConfigProtocol
import com.antiads.core.status.AccessibilityPhase
import com.antiads.core.status.AccessibilityRuntimeState
import com.antiads.core.status.ConfigTransportState
import com.antiads.core.status.HookInstallState
import com.antiads.core.status.ReceivedHookReport

/** 报告新鲜度：超过 REPORT_STALE_MS（15000ms）显示“报告已过期”。 */
internal enum class ReportFreshness { FRESH, STALE }

internal data class HookReportFact(
    val key: String,
    val packageName: String,
    val pid: Int,
    val processToken: String,
    val ageMs: Long,
    val freshness: ReportFreshness,
    val installState: HookInstallState,
    val transportState: ConfigTransportState,
    val policyRevision: Long?,
    val observedCallbacks: Long,
    val droppedCallbacks: Long,
    val lastErrorCode: String?
)

internal data class AccessibilityFact(
    val systemEnabled: Boolean,
    val connected: Boolean,
    val phase: AccessibilityPhase,
    val activePackage: String?,
    val lastActionPackage: String?,
    val lastActionAgeMs: Long?,
    val lastActionAccepted: Boolean?,
    val lastErrorCode: String?
)

/**
 * 状态事实聚合（纯逻辑，可在纯 JVM 单测中验证）。
 *
 * 关键约束（docs/contracts.md 第 5、7 节）：
 * - “系统设置已授权”与“服务实际已连接”是两个必须分开显示的事实；
 * - 报告超过 15 秒显示已过期，不能解释为“一定没有注入”；
 * - 报告是进程自报，不作为拦截是否生效的证明。
 */
internal object StatusFacts {

    /**
     * 无障碍服务的**类名**。实现来自 :accessibility 库，但组件在最终 APK 中归属宿主包。
     * 注意：没有“库 namespace”常量——服务归属包必须用宿主 applicationId 判断（见下）。
     */
    const val AD_SKIP_SERVICE_CLASS: String = "com.antiads.accessibility.AdSkipService"

    fun freshness(receivedAtElapsedMs: Long, nowElapsedMs: Long): ReportFreshness {
        val age = nowElapsedMs - receivedAtElapsedMs
        return if (age > ConfigProtocol.REPORT_STALE_MS) ReportFreshness.STALE else ReportFreshness.FRESH
    }

    /** 新鲜报告在前；同类按时间从新到旧。 */
    fun hookReports(reports: List<ReceivedHookReport>, nowElapsedMs: Long): List<HookReportFact> =
        reports.map { received ->
            val report = received.report
            HookReportFact(
                key = report.packageName + "|" + report.pid + "|" + report.processToken,
                packageName = report.packageName,
                pid = report.pid,
                processToken = report.processToken,
                ageMs = (nowElapsedMs - received.receivedAtElapsedMs).coerceAtLeast(0L),
                freshness = freshness(received.receivedAtElapsedMs, nowElapsedMs),
                installState = report.installState,
                transportState = report.transportState,
                policyRevision = report.policyRevision,
                observedCallbacks = report.observedCallbacks,
                droppedCallbacks = report.droppedCallbacks,
                lastErrorCode = report.lastErrorCode
            )
        }.sortedWith(
            compareByDescending<HookReportFact> { if (it.freshness == ReportFreshness.FRESH) 1 else 0 }
                .thenBy { it.ageMs }
        )

    fun accessibility(
        systemEnabled: Boolean,
        state: AccessibilityRuntimeState,
        nowElapsedMs: Long
    ): AccessibilityFact {
        val action = state.lastAction
        return AccessibilityFact(
            systemEnabled = systemEnabled,
            connected = state.connected,
            phase = state.phase,
            activePackage = state.activePackage,
            lastActionPackage = action?.packageName,
            lastActionAgeMs = action?.let { (nowElapsedMs - it.occurredAtElapsedMs).coerceAtLeast(0L) },
            lastActionAccepted = action?.actionAccepted,
            lastErrorCode = state.lastErrorCode
        )
    }

    /**
     * 系统“已启用服务”列表里的条目是否就是本产品的无障碍服务。
     *
     * 归属包必须等于**宿主自身包名**（applicationId，运行时由 `Context.packageName` 提供）：
     * 服务实现来自 :accessibility 库，但合并进最终 APK 后系统看到的组件是
     * `<宿主包>/com.antiads.accessibility.AdSkipService`，返回的 packageName 是**宿主包**而不是库 namespace。
     * 历史缺陷（QA-01）：这里硬编码库 namespace `com.antiads.accessibility`，导致真实授权后仍显示“未授权”。
     *
     * 类名仍要求**完整精确匹配**，避免把其他服务误判成本产品服务。
     */
    fun matchesAdSkipService(
        hostPackageName: String,
        servicePackageName: String?,
        serviceClassName: String?
    ): Boolean =
        hostPackageName.isNotEmpty() &&
            servicePackageName == hostPackageName &&
            serviceClassName == AD_SKIP_SERVICE_CLASS
}
