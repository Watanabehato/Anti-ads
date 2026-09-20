package com.antiads.core.config

/**
 * 策略交集解析（纯函数，冻结签名见 docs/contracts.md 第 2 节）。
 *
 * 只有 masterEnabled && 全局对应模式 && 当前包对应模式才为 true；包不存在视为全关。
 */
object PolicyResolver {

    fun accessibilityEnabled(config: ProtectionConfig, packageName: String): Boolean {
        val pkg = config.packages[packageName] ?: return false
        return config.masterEnabled && config.accessibilityEnabled && pkg.accessibilityEnabled
    }

    /**
     * 生成只包含该包的最小策略。关闭 Hook 时返回 hookEnabled=false 且 blockedSensorTypes=emptySet()，
     * 并保留真实 config.revision（便于目标进程判断是否已收到最新版本）。
     */
    fun packagePolicy(config: ProtectionConfig, packageName: String): PackagePolicy {
        val pkg = config.packages[packageName]
        val enabled = pkg != null &&
            config.masterEnabled &&
            config.hookEnabled &&
            pkg.hookEnabled

        return PackagePolicy(
            schemaVersion = config.schemaVersion,
            revision = config.revision,
            packageName = packageName,
            hookEnabled = enabled,
            blockedSensorTypes = if (enabled) pkg.blockedSensorTypes else emptySet()
        )
    }
}
