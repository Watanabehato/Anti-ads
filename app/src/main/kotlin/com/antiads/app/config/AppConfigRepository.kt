package com.antiads.app.config

import android.content.Context
import com.antiads.core.config.ConfigCodec
import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.ConfigHealth
import com.antiads.core.config.ConfigRepository
import com.antiads.core.config.ConfigStorageState
import com.antiads.core.config.ConfigValidator
import com.antiads.core.config.ConfigWriteResult
import com.antiads.core.config.PackageConfig
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.config.Subscription
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 宿主配置仓储（docs/contracts.md 第 3 节，由 :app 实现）。
 *
 * - snapshot()/health() 线程安全、非阻塞、纯内存；
 * - write() 串行锁内做 revision 比较（CONFLICT/INVALID/REVISION_EXHAUSTED）→ 原子落盘（IO_ERROR）；
 *   只有落盘成功才发布新快照并返回 Saved；
 * - observe() 注册即回调一次当前快照，close 幂等，listener 异常被隔离且不影响已落盘事务；
 * - 构造器为 internal：单元测试可注入内存 ConfigStore，生产入口只有 get(Context) 惰性单例
 *   （Provider 可能先于 Application.onCreate 运行，因此必须与 Context 绑定而不是与 Activity 绑定）。
 */
class AppConfigRepository internal constructor(private val store: ConfigStore) : ConfigRepository {

    @Volatile
    private var currentSnapshot: ProtectionConfig = DEFAULT_CONFIG

    @Volatile
    private var currentHealth: ConfigHealth = ConfigHealth(ConfigStorageState.DEFAULTS_NO_FILE)

    private val writeLock = Any()
    private val listeners = CopyOnWriteArrayList<(ProtectionConfig) -> Unit>()

    init {
        loadInitial()
    }

    override fun snapshot(): ProtectionConfig = currentSnapshot

    override fun health(): ConfigHealth = currentHealth

    override fun write(config: ProtectionConfig, expectedRevision: Long): ConfigWriteResult {
        val saved: ProtectionConfig
        synchronized(writeLock) {
            val current = currentSnapshot
            if (expectedRevision != current.revision) {
                return ConfigWriteResult.Rejected(REASON_CONFLICT)
            }
            if (config.revision != expectedRevision) {
                return ConfigWriteResult.Rejected(REASON_INVALID)
            }
            if (expectedRevision >= ConfigConstants.MAX_REVISION) {
                return ConfigWriteResult.Rejected(REASON_REVISION_EXHAUSTED)
            }
            val next = config.copy(
                schemaVersion = ConfigConstants.SCHEMA_VERSION_V1,
                revision = expectedRevision + 1L
            ).frozenCopy()
            if (!ConfigValidator.validate(next).isValid) {
                return ConfigWriteResult.Rejected(REASON_INVALID)
            }
            val json = try {
                ConfigCodec.encodeConfig(next)
            } catch (e: IllegalArgumentException) {
                return ConfigWriteResult.Rejected(REASON_INVALID)
            }
            try {
                store.write(json)
            } catch (e: IOException) {
                return ConfigWriteResult.Rejected(REASON_IO_ERROR)
            } catch (e: RuntimeException) {
                return ConfigWriteResult.Rejected(REASON_IO_ERROR)
            }
            currentSnapshot = next
            currentHealth = ConfigHealth(ConfigStorageState.READY)
            saved = next
        }
        // 锁外回调：不允许在持锁状态调用 listener
        notifyListeners(saved)
        return ConfigWriteResult.Saved(saved)
    }

    override fun observe(listener: (ProtectionConfig) -> Unit): Subscription {
        listeners.add(listener)
        invokeSafely(listener, currentSnapshot)
        return Subscription { listeners.remove(listener) }
    }

    private fun loadInitial() {
        val text = try {
            store.read()
        } catch (e: IOException) {
            currentSnapshot = DEFAULT_CONFIG
            currentHealth = ConfigHealth(ConfigStorageState.IO_ERROR, ERROR_CODE_IO)
            return
        } catch (e: RuntimeException) {
            currentSnapshot = DEFAULT_CONFIG
            currentHealth = ConfigHealth(ConfigStorageState.IO_ERROR, ERROR_CODE_IO)
            return
        }
        if (text == null) {
            currentSnapshot = DEFAULT_CONFIG
            currentHealth = ConfigHealth(ConfigStorageState.DEFAULTS_NO_FILE)
            return
        }
        val decoded = try {
            ConfigCodec.decodeConfig(text)
        } catch (e: IllegalArgumentException) {
            currentSnapshot = DEFAULT_CONFIG
            currentHealth = ConfigHealth(ConfigStorageState.RECOVERED_CORRUPT, sanitizeCode(e.message))
            return
        } catch (e: RuntimeException) {
            currentSnapshot = DEFAULT_CONFIG
            currentHealth = ConfigHealth(ConfigStorageState.RECOVERED_CORRUPT, ERROR_CODE_CORRUPT)
            return
        }
        currentSnapshot = decoded.frozenCopy()
        currentHealth = ConfigHealth(ConfigStorageState.READY)
    }

    private fun notifyListeners(config: ProtectionConfig) {
        for (listener in listeners) {
            invokeSafely(listener, config)
        }
    }

    private fun invokeSafely(listener: (ProtectionConfig) -> Unit, config: ProtectionConfig) {
        try {
            listener(config)
        } catch (e: RuntimeException) {
            // 隔离：listener 异常不能让已落盘事务失败；也不输出配置内容或界面文本
        }
    }

    /** 只接受稳定错误码形式的消息，避免把文件内容带进 UI/日志。 */
    private fun sanitizeCode(message: String?): String {
        val candidate = message.orEmpty()
        return if (STABLE_CODE.matches(candidate)) candidate else ERROR_CODE_CORRUPT
    }

    private fun ProtectionConfig.frozenCopy(): ProtectionConfig = ProtectionConfig(
        schemaVersion = schemaVersion,
        revision = revision,
        masterEnabled = masterEnabled,
        accessibilityEnabled = accessibilityEnabled,
        hookEnabled = hookEnabled,
        packages = packages.mapValues { (_, pkg) ->
            PackageConfig(
                accessibilityEnabled = pkg.accessibilityEnabled,
                hookEnabled = pkg.hookEnabled,
                blockedSensorTypes = pkg.blockedSensorTypes.toSet(),
                ruleIds = pkg.ruleIds.toSet()
            )
        }
    )

    companion object {
        /** ConfigWriteResult.Rejected 的稳定原因码（docs/contracts.md 第 3 节）。 */
        const val REASON_CONFLICT: String = "CONFLICT"
        const val REASON_INVALID: String = "INVALID"
        const val REASON_IO_ERROR: String = "IO_ERROR"
        const val REASON_REVISION_EXHAUSTED: String = "REVISION_EXHAUSTED"

        const val ERROR_CODE_IO: String = "IO_ERROR"
        const val ERROR_CODE_CORRUPT: String = "CORRUPT"

        private val STABLE_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")

        /** 出厂默认：全关、无应用配置（docs/contracts.md 第 2 节）。 */
        val DEFAULT_CONFIG: ProtectionConfig = ProtectionConfig()

        @Volatile
        private var instance: AppConfigRepository? = null

        fun get(context: Context): AppConfigRepository {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: AppConfigRepository(AtomicFileConfigStore(context.applicationContext)).also {
                    instance = it
                }
            }
        }
    }
}
