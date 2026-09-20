package com.antiads.accessibility

import com.antiads.core.config.ConfigRepository

/**
 * 无障碍依赖入口 —— 骨架占位，由 t9(accessibility-engineer / 开发 C) 实现。
 *
 * 冻结签名（docs/contracts.md 第 3 节）：只提供 install(repository)。
 * 正式语义：服务未注入依赖时必须处于 ERROR/全关，禁止空依赖返回假成功。
 */
object AccessibilityDependencies {

    @Volatile
    private var repository: ConfigRepository? = null

    /** 由 :app 的 Application.onCreate 调用；骨架阶段只保存引用，不代表服务已连接。 */
    fun install(repository: ConfigRepository) {
        this.repository = repository
        // TODO(t9): 通知已连接的服务实例刷新订阅；未连接时保持 ERROR/全关语义
    }
}
