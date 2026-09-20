package com.antiads.accessibility

import com.antiads.core.config.ConfigConstants

/**
 * 敏感包判定（纯函数）。
 *
 * 任何无法确定的输入都按敏感处理：宁可跳过，不在锁屏/系统界面/输入法/权限确认/安装器等
 * 场景误触。固定拒绝集合与 core 的配置拒绝键一致（宿主、android、SystemUI）；其余由
 * Android 侧解析（当前输入法、默认 HOME、权限控制器、安装器）后作为 systemSensitive 传入。
 */
internal object SensitivePackageRules {

    fun alwaysSensitive(hostPackageName: String?): Set<String> {
        val result = LinkedHashSet<String>(ConfigConstants.REJECTED_PACKAGE_KEYS)
        if (!hostPackageName.isNullOrEmpty()) result.add(hostPackageName)
        return result
    }

    fun isSensitive(
        packageName: String?,
        alwaysSensitive: Set<String>,
        systemSensitive: Set<String>
    ): Boolean {
        if (packageName.isNullOrEmpty()) return true
        return packageName in alwaysSensitive || packageName in systemSensitive
    }
}
