package com.antiads.accessibility

import com.antiads.core.config.Subscription
import com.antiads.core.status.AccessibilityPhase
import com.antiads.core.status.AccessibilityRuntimeState
import com.antiads.core.status.SkipActionRecord
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 无障碍运行状态发布入口（:accessibility 发布、:app 读取；签名冻结于 contracts 第 5 节）。
 *
 * 语义（与合同一致）：
 * - 纯配置不能令 connected=true：只有系统实际调用过 onServiceConnected 才会置位；
 * - observe 注册后立即回调一次当前状态，close 幂等，取消后不再开始新回调；
 * - 回调可能来自服务主线程，消费方自行投递 UI 线程；监听器异常被隔离；
 * - 只保留最近一次规则/包/时间/动作返回值，不记录屏幕文本、输入值或节点完整树。
 */
object AccessibilityRuntime {

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(AccessibilityRuntimeState) -> Unit>()
    private var state = AccessibilityRuntimeState()

    fun state(): AccessibilityRuntimeState = synchronized(lock) { state }

    fun observe(listener: (AccessibilityRuntimeState) -> Unit): Subscription {
        val current = synchronized(lock) {
            listeners.add(listener)
            state
        }
        notifySafely(listener, current)
        return Subscription { synchronized(lock) { listeners.remove(listener) } }
    }

    /** 服务被系统绑定（onServiceConnected）。绝不因为安装了配置就调用它。 */
    internal fun markConnected() {
        publishIfChanged { it.copy(connected = true) }
    }

    /** 服务解绑/销毁：清除 connected，保留最近一次动作记录（可核验事实）。 */
    internal fun markDisconnected() {
        publishIfChanged {
            AccessibilityRuntimeState(
                connected = false,
                phase = AccessibilityPhase.DISCONNECTED,
                activePackage = null,
                lastAction = it.lastAction,
                lastErrorCode = null
            )
        }
    }

    /** 阶段更新（由 PhaseCalculator 计算，重复状态不重复通知）。 */
    internal fun updatePhase(result: PhaseResult) {
        publishIfChanged {
            it.copy(phase = result.phase, activePackage = result.activePackage, lastErrorCode = result.errorCode)
        }
    }

    /** 记录一次动作尝试的真实返回值（performAction(true) 只表示系统接收了动作）。 */
    internal fun recordAction(record: SkipActionRecord) {
        publishIfChanged { it.copy(lastAction = record) }
    }

    /** 记录中断（onInterrupt）：不改变 connected，不当作永久断开。 */
    internal fun recordInterrupt(code: String) {
        publishIfChanged { it.copy(lastErrorCode = code) }
    }

    /** 记录内部错误（例如扫描异常）。 */
    internal fun recordError(code: String) {
        publishIfChanged {
            it.copy(phase = AccessibilityPhase.ERROR, activePackage = null, lastErrorCode = code)
        }
    }

    /** 测试隔离用：清空状态与监听器。产品代码只使用 markConnected/markDisconnected/updatePhase。 */
    internal fun resetForTest() {
        synchronized(lock) {
            listeners.clear()
            state = AccessibilityRuntimeState()
        }
    }

    private fun publishIfChanged(transform: (AccessibilityRuntimeState) -> AccessibilityRuntimeState) {
        val next = synchronized(lock) {
            val current = state
            val candidate = transform(current)
            if (candidate == current) return
            state = candidate
            candidate
        }
        for (listener in listeners) {
            if (isRegistered(listener)) notifySafely(listener, next)
        }
    }

    private fun isRegistered(listener: (AccessibilityRuntimeState) -> Unit): Boolean =
        synchronized(lock) { listeners.contains(listener) }

    private fun notifySafely(listener: (AccessibilityRuntimeState) -> Unit, value: AccessibilityRuntimeState) {
        try {
            listener(value)
        } catch (_: Throwable) {
            // 监听器异常被隔离：不能影响状态发布，也不能让服务崩溃
        }
    }
}
