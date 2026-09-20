package com.antiads.core.config

/**
 * core 内部与跨模块共用的固定常量（不改变冻结签名，仅为避免各模块复制字面量）。
 *
 * 数值来源：docs/contracts.md 第 1、2 节（固定标识、验证上限、内置规则 ID）。
 */
object ConfigConstants {
    /** 当前唯一受支持的配置/策略/报告 schema 版本。 */
    const val SCHEMA_VERSION_V1: Int = 1

    /** 内置保守规则 ID（A/C/D 共用）。 */
    const val BUILTIN_RULE_CONSERVATIVE_V1: String = "builtin.conservative.v1"

    /** 默认受保护传感器类型：加速度计、陀螺仪、重力、线性加速度、旋转矢量。 */
    val DEFAULT_BLOCKED_SENSOR_TYPES: Set<Int> = setOf(1, 4, 9, 10, 11)

    /** 允许出现在 blockedSensorTypes 中的类型全量集合。 */
    val ALLOWED_SENSOR_TYPES: Set<Int> = setOf(1, 4, 9, 10, 11)

    /** 只允许出现的规则 ID（空集合合法，表示不匹配任何规则）。 */
    val ALLOWED_RULE_IDS: Set<String> = setOf(BUILTIN_RULE_CONSERVATIVE_V1)

    /** 用户不能通过手工 JSON 为宿主/系统 UI 开启操作。 */
    val REJECTED_PACKAGE_KEYS: Set<String> = setOf("android", "com.android.systemui", "com.antiads.app")

    const val MAX_CONFIG_JSON_BYTES: Int = 256 * 1024
    const val MAX_PACKAGES: Int = 500
    const val MAX_PACKAGE_NAME_LENGTH: Int = 255
    const val MAX_REVISION: Long = Long.MAX_VALUE - 1

    /** 进程上报字段上限（contracts 第 6 节）。 */
    const val MAX_PROCESS_NAME_LENGTH: Int = 160
    const val PROCESS_TOKEN_LENGTH: Int = 36
    const val MAX_POLICY_JSON_BYTES: Int = 4096
}
