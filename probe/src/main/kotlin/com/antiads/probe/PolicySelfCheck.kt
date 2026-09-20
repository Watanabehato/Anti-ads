package com.antiads.probe

import android.content.Context
import android.net.Uri
import com.antiads.core.config.ConfigCodec
import com.antiads.core.protocol.ConfigProtocol

/**
 * 可选策略自查（默认关闭，QA 显式打开）：probe 以**自身 UID** 读取自己的最小策略。
 *
 * 用途：区分"Hook 租约还没过期"与"Provider 已经发出新 revision"。
 * 明确说明：这是 probe 自读结果，不是拦截证据，也不代表目标进程的 Hook 状态；
 * 读取失败/被拒绝都只显示状态，不改任何东西（probe 没有任何写配置能力）。
 */
class PolicySelfCheck(private val context: Context, private val packageName: String) {

    data class Result(
        val status: String,
        val revision: Long?,
        val hookEnabled: Boolean?,
        val blockedSensorTypes: List<Int>,
        val errorCode: String?
    )

    fun read(): Result {
        val bundle = try {
            context.contentResolver.call(
                Uri.parse(ConfigProtocol.URI),
                ConfigProtocol.GET_POLICY,
                packageName,
                null
            )
        } catch (t: Throwable) {
            return failure(STATUS_UNAVAILABLE, "TRANSPORT_ERROR")
        } ?: return failure(STATUS_UNAVAILABLE, "NULL_BUNDLE")

        val ok = runCatching { bundle.getBoolean(ConfigProtocol.KEY_OK, false) }.getOrDefault(false)
        if (!ok) {
            val code = runCatching { bundle.getString(ConfigProtocol.KEY_ERROR) }.getOrNull()
            return failure(STATUS_ERROR, code ?: "UNKNOWN_ERROR")
        }

        val payload = runCatching { bundle.getString(ConfigProtocol.KEY_PAYLOAD) }.getOrNull()
            ?: return failure(STATUS_ERROR, "MISSING_PAYLOAD")

        val policy = try {
            ConfigCodec.decodePolicy(payload)
        } catch (t: Throwable) {
            return failure(STATUS_ERROR, "INVALID_PAYLOAD")
        }
        if (policy.packageName != packageName) {
            return failure(STATUS_ERROR, "PACKAGE_MISMATCH")
        }
        return Result(
            status = STATUS_OK,
            revision = policy.revision,
            hookEnabled = policy.hookEnabled,
            blockedSensorTypes = policy.blockedSensorTypes.sorted(),
            errorCode = null
        )
    }

    private fun failure(status: String, errorCode: String) = Result(
        status = status,
        revision = null,
        hookEnabled = null,
        blockedSensorTypes = emptyList(),
        errorCode = errorCode
    )

    private companion object {
        const val STATUS_OK: String = "OK"
        const val STATUS_UNAVAILABLE: String = "UNAVAILABLE"
        const val STATUS_ERROR: String = "ERROR"
    }
}
