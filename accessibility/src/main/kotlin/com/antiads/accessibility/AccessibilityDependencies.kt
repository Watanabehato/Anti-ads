package com.antiads.accessibility

import com.antiads.core.config.ConfigRepository
import com.antiads.core.config.Subscription

/**
 * 无障碍依赖入口（签名冻结于 contracts 第 3 节）：只提供 install(repository)。
 *
 * - 由 :app 的 AntiAdsApplication.onCreate 调用；Provider 提前初始化也不会改变语义；
 * - install 只保存引用并通知“已经连接”的服务实例，绝不修改 AccessibilityRuntime.connected；
 * - 服务未注入依赖时保持 ERROR/全关：没有任何候选会被执行，也不会假成功。
 */
object AccessibilityDependencies {

    private val lock = Any()
    private var repository: ConfigRepository? = null
    private var listener: ((ConfigRepository) -> Unit)? = null

    fun install(repository: ConfigRepository) {
        val callback = synchronized(lock) {
            this.repository = repository
            listener
        }
        callback?.invoke(repository)
    }

    /** 服务读取当前依赖；返回 null 表示尚未注入（服务必须全关）。 */
    internal fun repositoryOrNull(): ConfigRepository? = repository

    /**
     * 服务注册依赖回调。
     * 若 install 早于 onServiceConnected，这里会立即同步回调一次（覆盖两种正常时序）。
     */
    internal fun attach(listener: (ConfigRepository) -> Unit): Subscription {
        val existing = synchronized(lock) {
            this.listener = listener
            repository
        }
        if (existing != null) listener(existing)
        return Subscription {
            synchronized(lock) { if (this.listener === listener) this.listener = null }
        }
    }

    /** 服务解绑/销毁：停止接收依赖回调。 */
    internal fun detach() {
        synchronized(lock) { listener = null }
    }

    /** 测试隔离用。 */
    internal fun resetForTest() {
        synchronized(lock) {
            repository = null
            listener = null
        }
    }
}
