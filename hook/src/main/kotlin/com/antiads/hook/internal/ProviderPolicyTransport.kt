package com.antiads.hook.internal

import android.content.Context
import android.net.Uri
import com.antiads.core.protocol.ConfigProtocol
import com.antiads.hook.internal.policy.HookTransportCodes
import com.antiads.hook.internal.policy.PolicyFetchOutcome
import com.antiads.hook.internal.policy.PolicyTransport

/**
 * 以目标进程自身 UID 调用宿主只读 Provider（合同第 6 节）。
 *
 * - 只在后台 worker 线程调用；
 * - 只用 Bundle 的 primitive/String；
 * - 找不到 Provider、SecurityException、null Bundle、未知 ok/error 组合都视为读取失败（不阻断目标）；
 * - 本类不提供任何写配置能力。
 */
internal class ProviderPolicyTransport(private val context: Context) : PolicyTransport {

    override fun fetch(packageName: String): PolicyFetchOutcome {
        val bundle = try {
            context.contentResolver.call(
                Uri.parse(ConfigProtocol.URI),
                ConfigProtocol.GET_POLICY,
                packageName,
                null
            )
        } catch (t: Throwable) {
            return PolicyFetchOutcome.Failed(HookTransportCodes.UNAVAILABLE)
        } ?: return PolicyFetchOutcome.Failed(HookTransportCodes.UNAVAILABLE)

        val ok = try {
            bundle.getBoolean(ConfigProtocol.KEY_OK, false)
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            val code = try {
                bundle.getString(ConfigProtocol.KEY_ERROR)
            } catch (t: Throwable) {
                null
            }
            return PolicyFetchOutcome.Reply(false, null, code ?: HookTransportCodes.UNAVAILABLE)
        }

        val payload = try {
            bundle.getString(ConfigProtocol.KEY_PAYLOAD)
        } catch (t: Throwable) {
            null
        }
        return PolicyFetchOutcome.Reply(true, payload, null)
    }
}
