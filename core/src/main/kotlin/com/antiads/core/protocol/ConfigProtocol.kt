package com.antiads.core.protocol

/**
 * 跨进程 Provider 协议常量（冻结见 docs/contracts.md 第 6 节）。
 *
 * 调用形式：ContentResolver.call(Uri.parse(URI), method, packageName, extras)。
 * arg（packageName）是请求身份提示，不是身份依据；服务端必须用 Binder.getCallingUid() 校验。
 */
object ConfigProtocol {
    const val AUTHORITY = "com.antiads.app.config"
    const val URI = "content://com.antiads.app.config"
    const val GET_POLICY = "get_policy_v1"
    const val REPORT_HOOK = "report_hook_v1"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_PAYLOAD = "payload"
    const val REFRESH_INTERVAL_MS = 2000L
    const val LEASE_MS = 5000L
    const val READ_DEADLINE_MS = 1000L
    const val REPORT_INTERVAL_MS = 5000L
    const val REPORT_STALE_MS = 15000L

    /** 规范错误码（失败无 payload）。 */
    const val ERROR_UNAUTHORIZED = "UNAUTHORIZED"
    const val ERROR_SHARED_UID_UNSUPPORTED = "SHARED_UID_UNSUPPORTED"
    const val ERROR_ISOLATED_OR_UNKNOWN_UID = "ISOLATED_OR_UNKNOWN_UID"
    const val ERROR_INVALID_REQUEST = "INVALID_REQUEST"
    const val ERROR_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val ERROR_RATE_LIMITED = "RATE_LIMITED"
    const val ERROR_INTERNAL_ERROR = "INTERNAL_ERROR"
}
