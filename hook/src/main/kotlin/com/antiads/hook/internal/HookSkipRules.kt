package com.antiads.hook.internal

/**
 * 入口跳过规则（合同第 7 节）：宿主、android/SystemUI 与 system_server 一律不安装 Hook，
 * 也不要求用户把系统框架加入作用域。纯函数，便于单测。
 */
object HookSkipRules {

    const val HOST_PACKAGE: String = "com.antiads.app"
    const val SYSTEM_PACKAGE: String = "android"
    const val SYSTEM_UI_PACKAGE: String = "com.android.systemui"

    /** system_server 的进程名是 "system"，其包名为 android。 */
    const val SYSTEM_PROCESS_NAME: String = "system"

    val SKIPPED_PACKAGES: Set<String> = setOf(SYSTEM_PACKAGE, SYSTEM_UI_PACKAGE, HOST_PACKAGE)

    fun shouldSkip(packageName: String?, processName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return true
        if (packageName in SKIPPED_PACKAGES) return true
        if (processName == SYSTEM_PROCESS_NAME) return true
        return false
    }
}
