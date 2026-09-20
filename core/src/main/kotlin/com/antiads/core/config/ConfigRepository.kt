package com.antiads.core.config

/**
 * 配置仓储契约（冻结签名见 docs/contracts.md 第 3 节）。
 *
 * 实现由 :app 的 AppConfigRepository 提供；core 只定义接口，不依赖 Android。
 */
fun interface Subscription {
    fun close()
}

enum class ConfigStorageState { READY, DEFAULTS_NO_FILE, RECOVERED_CORRUPT, IO_ERROR }

data class ConfigHealth(val state: ConfigStorageState, val errorCode: String? = null)

sealed interface ConfigWriteResult {
    data class Saved(val config: ProtectionConfig) : ConfigWriteResult
    data class Rejected(val reason: String) : ConfigWriteResult
}

/**
 * snapshot()/health() 线程安全、非阻塞、纯内存。
 * write() 只允许 :app 内部调用（本地调用，不走 exported Provider）。
 * observe() 注册后立即回调一次当前快照；close 幂等；回调可能来自写线程。
 */
interface ConfigRepository {
    fun snapshot(): ProtectionConfig
    fun health(): ConfigHealth
    fun write(config: ProtectionConfig, expectedRevision: Long): ConfigWriteResult
    fun observe(listener: (ProtectionConfig) -> Unit): Subscription
}
