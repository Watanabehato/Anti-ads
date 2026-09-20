package com.antiads.app.config

import com.antiads.core.protocol.ConfigProtocol

/**
 * Provider 内部请求模型（不含 Android 类型，便于纯 JVM 单测）。
 *
 * uid/pid 必须由 ConfigProvider 在 call 入口用 Binder.getCallingUid()/getCallingPid() 捕获后传入；
 * argPackage 是请求自报的身份提示，不是身份依据。
 */
internal data class ProviderRequest(
    val uid: Int,
    val pid: Int,
    val method: String?,
    val argPackage: String?,
    val payloadJson: String?
)

/** 与 ConfigProtocol 的 ok/error/payload 约定一一对应；失败时 payload 必须为 null。 */
internal data class ProviderResponse(val ok: Boolean, val payload: String?, val errorCode: String?) {
    companion object {
        fun ok(payload: String?): ProviderResponse = ProviderResponse(true, payload, null)
        fun error(errorCode: String): ProviderResponse = ProviderResponse(false, null, errorCode)
    }
}

internal sealed interface CallerVerification {
    data class Allowed(val packageName: String) : CallerVerification
    data class Rejected(val errorCode: String) : CallerVerification
}

/** ContentProvider 的非 call 入口；v1 全部只读或拒绝。 */
internal enum class ProviderEntryPoint { QUERY, GET_TYPE, INSERT, UPDATE, DELETE, BULK_INSERT, OPEN_FILE, OPEN_ASSET_FILE }

internal enum class ProviderEntryDecision { RETURN_NULL, THROW_UNSUPPORTED }

/**
 * 非 call 入口的拒绝策略（可单测）：
 * query/getType 返回 null（不返回任何数据），insert/update/delete/bulkInsert/openFile/openAssetFile 直接抛错。
 */
internal object ProviderEntryPolicy {

    fun decide(entryPoint: ProviderEntryPoint): ProviderEntryDecision = when (entryPoint) {
        ProviderEntryPoint.QUERY, ProviderEntryPoint.GET_TYPE -> ProviderEntryDecision.RETURN_NULL
        ProviderEntryPoint.INSERT,
        ProviderEntryPoint.UPDATE,
        ProviderEntryPoint.DELETE,
        ProviderEntryPoint.BULK_INSERT,
        ProviderEntryPoint.OPEN_FILE,
        ProviderEntryPoint.OPEN_ASSET_FILE -> ProviderEntryDecision.THROW_UNSUPPORTED
    }

    fun dataReturningEntryPoints(): Set<ProviderEntryPoint> =
        ProviderEntryPoint.entries.filter { decide(it) == ProviderEntryDecision.RETURN_NULL }.toSet()
}

/** call 方法白名单：只有两个冻结方法，其余（含 CRUD 语义名）一律 INVALID_REQUEST。 */
internal object ProviderMethodPolicy {

    val ALLOWED: Set<String> = setOf(ConfigProtocol.GET_POLICY, ConfigProtocol.REPORT_HOOK)

    /** 显式登记禁止通过 call 暴露的方法名，避免将来误加“远程写配置”。 */
    val FORBIDDEN: Set<String> = setOf(
        "query", "insert", "update", "delete", "openFile", "bulkInsert",
        "get_all_config", "write_config", "set_package", "update_package", "clear_config"
    )

    fun isAllowed(method: String?): Boolean = method != null && method in ALLOWED
}
