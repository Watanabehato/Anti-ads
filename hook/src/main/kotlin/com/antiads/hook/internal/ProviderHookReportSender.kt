package com.antiads.hook.internal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.antiads.core.config.ConfigCodec
import com.antiads.core.config.ConfigConstants
import com.antiads.core.protocol.ConfigProtocol
import com.antiads.core.status.HookProcessReport

/**
 * 进程自报上报（合同第 6、7 节）：只在后台 worker 线程调用，服务端会强制覆盖 pid。
 *
 * 报告不写配置、不改变授权、不改系统/框架状态；失败静默（不影响防护）。
 * 无论投递是否成功，都输出一条机器可读诊断行供 QA 抓取（受报告节流限制，≤1 条/5s）。
 */
internal class ProviderHookReportSender(private val context: Context) : (HookProcessReport) -> Unit {

    override fun invoke(report: HookProcessReport) {
        val json = try {
            ConfigCodec.encodeHookReport(report)
        } catch (t: Throwable) {
            HookLog.warn("report_encode_failed")
            return
        }
        if (json.toByteArray(Charsets.UTF_8).size > ConfigConstants.MAX_POLICY_JSON_BYTES) {
            HookLog.warn("report_too_large")
            return
        }

        val extras = Bundle().apply { putString(ConfigProtocol.KEY_PAYLOAD, json) }
        val delivered = try {
            val reply = context.contentResolver.call(
                Uri.parse(ConfigProtocol.URI),
                ConfigProtocol.REPORT_HOOK,
                report.packageName,
                extras
            )
            reply?.getBoolean(ConfigProtocol.KEY_OK, false) == true
        } catch (t: Throwable) {
            false
        }

        HookLog.diag(
            buildString {
                append("antiads_hook_report")
                append(" package=").append(report.packageName)
                append(" process=").append(report.processName)
                append(" token=").append(report.processToken)
                append(" pid=").append(report.pid)
                append(" api=").append(report.apiLevel)
                append(" install=").append(report.installState.name)
                append(" transport=").append(report.transportState.name)
                append(" policy_revision=").append(report.policyRevision ?: "-")
                append(" observed=").append(report.observedCallbacks)
                append(" dropped=").append(report.droppedCallbacks)
                append(" error=").append(report.lastErrorCode ?: "-")
                append(" delivered=").append(delivered)
                append(" elapsed_realtime_ms=").append(SystemClock.elapsedRealtime())
            }
        )
    }
}
