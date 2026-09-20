package com.antiads.core.config

import kotlinx.serialization.Serializable

/**
 * core 公共配置模型（冻结于 docs/contracts.md 第 2 节）。
 *
 * 约束：
 * - 所有字段名与 JSON 键一致，枚举使用合同规定的大写名字；
 * - 不得序列化 Android 类型；
 * - 默认列表/集合/map 为不可变快照，提交后禁止原地修改；
 * - 初始配置（出厂默认）为 schemaVersion=1 / revision=0 / 所有开关 false / packages 为空。
 */
@Serializable
data class ProtectionConfig(
    val schemaVersion: Int = 1,
    val revision: Long = 0,
    val masterEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val hookEnabled: Boolean = false,
    val packages: Map<String, PackageConfig> = emptyMap()
)

@Serializable
data class PackageConfig(
    val accessibilityEnabled: Boolean = false,
    val hookEnabled: Boolean = false,
    val blockedSensorTypes: Set<Int> = setOf(1, 4, 9, 10, 11),
    val ruleIds: Set<String> = setOf("builtin.conservative.v1")
)

/**
 * 单包最小策略：不含完整应用名单、不含无障碍规则、不含全局配置、不含其他包信息。
 * 关闭 Hook 时 hookEnabled=false 且 blockedSensorTypes=emptySet()。
 */
@Serializable
data class PackagePolicy(
    val schemaVersion: Int = 1,
    val revision: Long,
    val packageName: String,
    val hookEnabled: Boolean,
    val blockedSensorTypes: Set<Int>
)

/** Hook 侧内存策略快照与租约；计时统一使用 SystemClock.elapsedRealtime() 数值。 */
data class CachedPackagePolicy(
    val policy: PackagePolicy,
    val requestStartedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long
)

data class ValidationResult(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}
