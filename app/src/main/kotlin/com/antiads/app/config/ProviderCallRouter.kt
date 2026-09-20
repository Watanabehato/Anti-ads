package com.antiads.app.config

import com.antiads.core.config.ConfigCodec
import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.ConfigValidator
import com.antiads.core.config.PolicyResolver
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.protocol.ConfigProtocol
import com.antiads.core.status.HookProcessReport

/**
 * 跨进程配置调用路由（docs/contracts.md 第 6 节）。
 *
 * 全部依赖以函数注入，使授权判定可以在纯 JVM 单测里覆盖：
 * 单包匹配、arg 伪造、shared UID 多包、无包/isolated、跨用户、非应用 UID、拒绝包名、
 * 未知方法、每 UID 限流、payload 超长与未知 schema、报告包名比对与 pid 覆盖。
 *
 * 不在本类做任何 Binder 身份清除：调用身份由 ConfigProvider 在入口捕获后作为请求参数传入。
 */
internal class ProviderCallRouter(
    private val configSnapshot: () -> ProtectionConfig,
    private val packageLookup: (Int) -> Array<String>?,
    private val sameUser: (Int) -> Boolean,
    private val isolatedUid: (Int) -> Boolean,
    private val applicationUid: (Int) -> Boolean,
    private val nowMs: () -> Long,
    private val reportSink: (HookProcessReport, Long) -> Unit,
    private val policyLimiter: RateLimiter = RateLimiter(nowMs, GET_LIMIT_PER_SECOND),
    private val hookReportLimiter: RateLimiter = RateLimiter(nowMs, REPORT_LIMIT_PER_SECOND)
) {

    fun handle(request: ProviderRequest): ProviderResponse {
        if (!ProviderMethodPolicy.isAllowed(request.method)) {
            return ProviderResponse.error(ConfigProtocol.ERROR_INVALID_REQUEST)
        }
        val verifiedPackage = when (val verification = verifyCaller(request.uid, request.argPackage)) {
            is CallerVerification.Allowed -> verification.packageName
            is CallerVerification.Rejected -> return ProviderResponse.error(verification.errorCode)
        }
        return when (request.method) {
            ConfigProtocol.GET_POLICY -> handleGetPolicy(request, verifiedPackage)
            ConfigProtocol.REPORT_HOOK -> handleHookReport(request, verifiedPackage)
            else -> ProviderResponse.error(ConfigProtocol.ERROR_INVALID_REQUEST)
        }
    }

    /** 鉴权顺序固定：同用户 → 应用 UID → 枚举真实包集合 → 单包且与 arg 完全相等 → 非拒绝包名。 */
    internal fun verifyCaller(uid: Int, argPackage: String?): CallerVerification {
        if (!sameUser(uid)) return CallerVerification.Rejected(ConfigProtocol.ERROR_UNAUTHORIZED)
        if (!applicationUid(uid)) return CallerVerification.Rejected(ConfigProtocol.ERROR_UNAUTHORIZED)
        val packages = try {
            packageLookup(uid)?.toList()
        } catch (e: RuntimeException) {
            return CallerVerification.Rejected(ConfigProtocol.ERROR_UNAUTHORIZED)
        } ?: return CallerVerification.Rejected(ConfigProtocol.ERROR_ISOLATED_OR_UNKNOWN_UID)
        val distinct = packages.filter { it.isNotBlank() }.distinct()
        if (isolatedUid(uid) || distinct.isEmpty()) {
            return CallerVerification.Rejected(ConfigProtocol.ERROR_ISOLATED_OR_UNKNOWN_UID)
        }
        if (distinct.size > 1) {
            return CallerVerification.Rejected(ConfigProtocol.ERROR_SHARED_UID_UNSUPPORTED)
        }
        val single = distinct.first()
        if (argPackage != single) return CallerVerification.Rejected(ConfigProtocol.ERROR_UNAUTHORIZED)
        if (!ConfigValidator.isValidPackageName(single)) {
            return CallerVerification.Rejected(ConfigProtocol.ERROR_UNAUTHORIZED)
        }
        if (single in ConfigConstants.REJECTED_PACKAGE_KEYS) {
            return CallerVerification.Rejected(ConfigProtocol.ERROR_UNAUTHORIZED)
        }
        return CallerVerification.Allowed(single)
    }

    private fun handleGetPolicy(request: ProviderRequest, verifiedPackage: String): ProviderResponse {
        if (!policyLimiter.allow(request.uid)) {
            return ProviderResponse.error(ConfigProtocol.ERROR_RATE_LIMITED)
        }
        val policy = try {
            PolicyResolver.packagePolicy(configSnapshot(), verifiedPackage)
        } catch (e: RuntimeException) {
            return ProviderResponse.error(ConfigProtocol.ERROR_INTERNAL_ERROR)
        }
        val json = try {
            ConfigCodec.encodePolicy(policy)
        } catch (e: IllegalArgumentException) {
            return ProviderResponse.error(ConfigProtocol.ERROR_INTERNAL_ERROR)
        }
        if (json.toByteArray(Charsets.UTF_8).size > ConfigConstants.MAX_POLICY_JSON_BYTES) {
            return ProviderResponse.error(ConfigProtocol.ERROR_INTERNAL_ERROR)
        }
        return ProviderResponse.ok(json)
    }

    private fun handleHookReport(request: ProviderRequest, verifiedPackage: String): ProviderResponse {
        if (!hookReportLimiter.allow(request.uid)) {
            return ProviderResponse.error(ConfigProtocol.ERROR_RATE_LIMITED)
        }
        if (request.pid <= 0) {
            return ProviderResponse.error(ConfigProtocol.ERROR_INVALID_REQUEST)
        }
        val payload = request.payloadJson ?: return ProviderResponse.error(ConfigProtocol.ERROR_INVALID_REQUEST)
        if (payload.toByteArray(Charsets.UTF_8).size > ConfigConstants.MAX_POLICY_JSON_BYTES) {
            return ProviderResponse.error(ConfigProtocol.ERROR_INVALID_REQUEST)
        }
        val report = try {
            ConfigCodec.decodeHookReport(payload)
        } catch (e: IllegalArgumentException) {
            return ProviderResponse.error(errorCodeForDecodeFailure(e))
        } catch (e: RuntimeException) {
            return ProviderResponse.error(ConfigProtocol.ERROR_INVALID_REQUEST)
        }
        if (report.packageName != verifiedPackage) {
            return ProviderResponse.error(ConfigProtocol.ERROR_UNAUTHORIZED)
        }
        // 服务端强制覆盖 pid：请求自报 pid 不作为身份，也不写入存储
        val sanitized = report.copy(pid = request.pid)
        return try {
            reportSink(sanitized, nowMs())
            ProviderResponse.ok(null)
        } catch (e: RuntimeException) {
            ProviderResponse.error(ConfigProtocol.ERROR_INTERNAL_ERROR)
        }
    }

    private fun errorCodeForDecodeFailure(e: IllegalArgumentException): String {
        val code = e.message.orEmpty()
        return if (code.contains(DECODE_UNSUPPORTED_SCHEMA)) {
            ConfigProtocol.ERROR_UNSUPPORTED_VERSION
        } else {
            ConfigProtocol.ERROR_INVALID_REQUEST
        }
    }

    companion object {
        const val GET_LIMIT_PER_SECOND: Int = 5
        const val REPORT_LIMIT_PER_SECOND: Int = 1

        /** ConfigCodec 对未知/缺失 schemaVersion 的稳定错误码。 */
        private const val DECODE_UNSUPPORTED_SCHEMA: String = "UNSUPPORTED_SCHEMA_VERSION"
    }
}
