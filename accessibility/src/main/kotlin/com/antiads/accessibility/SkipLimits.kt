package com.antiads.accessibility

/**
 * 无障碍执行约束的固定上限（docs/contracts.md 第 5 节「C 的执行约束」）。
 *
 * 这些是 :accessibility 的内部实现常量，不属于对外公开 schema，也不提供任何外部控制接口。
 * 数值与合同一一对应，修改必须同步文档与测试。
 */
internal object SkipLimits {
    /** 服务主线程延迟合并事件：两次扫描之间至少间隔 250ms。 */
    const val SCAN_MIN_INTERVAL_MS: Long = 250L

    /** 快照超过该年龄必须丢弃并重新匹配。 */
    const val SNAPSHOT_MAX_AGE_MS: Long = 500L

    /** 只处理窗口进入后的短窗口；content changed 不延长该窗口。 */
    const val WINDOW_MAX_AGE_MS: Long = 10_000L

    /** 同包两次动作（performAction 尝试）之间的最小间隔。 */
    const val SAME_PACKAGE_MIN_INTERVAL_MS: Long = 2_000L

    /** 单次遍历最多节点数。 */
    const val MAX_NODES: Int = 300

    /** 单次遍历最大深度（根为 0）。 */
    const val MAX_DEPTH: Int = 20

    /** 单次遍历耗时预算（毫秒）。 */
    const val TRAVERSAL_BUDGET_MS: Long = 8L

    /** 文本快照每字段上限（字符）。 */
    const val MAX_FIELD_CHARS: Int = 256

    /** 同包冷却表最大条目数，避免无限增长。 */
    const val MAX_THROTTLE_ENTRIES: Int = 32
}

/** 内置规则返回的稳定 reason（contracts 第 5 节：返回列表最多 1 项，reason 固定 EXPLICIT_AD_SKIP）。 */
internal const val REASON_EXPLICIT_AD_SKIP: String = "EXPLICIT_AD_SKIP"

/**
 * 拒绝码：稳定英文码，用于日志、文档与测试断言；属于实现细节，不构成对外协议。
 * 每个码只表示“本次没有动作”，不表示广告一定不存在。
 */
internal object GuardCode {
    const val OK: String = "OK"

    // 连接与依赖
    const val NOT_CONNECTED: String = "NOT_CONNECTED"
    const val DEPENDENCY_MISSING: String = "DEPENDENCY_MISSING"
    const val CONFIG_UNAVAILABLE: String = "CONFIG_UNAVAILABLE"

    // 目标选择与配置
    const val NOT_WATCHING: String = "NOT_WATCHING"
    const val CONFIG_REVISION_CHANGED: String = "CONFIG_REVISION_CHANGED"
    const val POLICY_DISABLED: String = "POLICY_DISABLED"
    const val REJECTED_PACKAGE: String = "REJECTED_PACKAGE"
    const val INVALID_PACKAGE: String = "INVALID_PACKAGE"
    const val RULE_NOT_ENABLED: String = "RULE_NOT_ENABLED"

    // 事件与窗口
    const val EVENT_TYPE_FILTERED: String = "EVENT_TYPE_FILTERED"
    const val WINDOW_IDENTITY_UNKNOWN: String = "WINDOW_IDENTITY_UNKNOWN"
    const val WINDOW_CHANGED: String = "WINDOW_CHANGED"
    const val NOT_APPLICATION_WINDOW: String = "NOT_APPLICATION_WINDOW"
    const val KEYGUARD_LOCKED: String = "KEYGUARD_LOCKED"
    const val SENSITIVE_PACKAGE: String = "SENSITIVE_PACKAGE"
    const val TRAVERSAL_INCOMPLETE: String = "TRAVERSAL_INCOMPLETE"
    const val WINDOW_SIZE_INVALID: String = "WINDOW_SIZE_INVALID"
    const val EDITABLE_NODE_IN_WINDOW: String = "EDITABLE_NODE_IN_WINDOW"
    const val WINDOW_AGE_OUT_OF_RANGE: String = "WINDOW_AGE_OUT_OF_RANGE"
    const val SNAPSHOT_STALE: String = "SNAPSHOT_STALE"

    // 去重与冷却
    const val EPOCH_ALREADY_ATTEMPTED: String = "EPOCH_ALREADY_ATTEMPTED"
    const val THROTTLED: String = "THROTTLED"

    // 候选与节点
    const val RULE_NOT_BUILTIN: String = "RULE_NOT_BUILTIN"
    const val REASON_NOT_EXPLICIT_AD_SKIP: String = "REASON_NOT_EXPLICIT_AD_SKIP"
    const val NODE_EDITABLE: String = "NODE_EDITABLE"
    const val NODE_NOT_ACTIONABLE: String = "NODE_NOT_ACTIONABLE"
    const val REFRESH_FAILED: String = "REFRESH_FAILED"
    const val REFRESH_EDITABLE: String = "REFRESH_EDITABLE"
    const val REFRESH_NOT_ACTIONABLE: String = "REFRESH_NOT_ACTIONABLE"
    const val NODE_TEXT_CHANGED: String = "NODE_TEXT_CHANGED"
    const val SENSITIVE_ACTION: String = "SENSITIVE_ACTION"

    // 服务生命周期
    const val SERVICE_INTERRUPTED: String = "SERVICE_INTERRUPTED"
    const val SERVICE_UNBOUND: String = "SERVICE_UNBOUND"
    const val CONFIG_DISABLED: String = "CONFIG_DISABLED"
    const val INTERNAL_ERROR: String = "INTERNAL_ERROR"
}
