package com.antiads.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.ConfigRepository
import com.antiads.core.config.ConfigValidator
import com.antiads.core.config.PolicyResolver
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.config.Subscription
import com.antiads.core.rules.AdRuleEngine
import com.antiads.core.rules.ConservativeAdRuleEngine
import com.antiads.core.status.SkipActionRecord

/**
 * 免 Root 无障碍跳过服务。
 *
 * 分工（contracts 第 5 节）：共享判定算法在 :core（[AdRuleEngine]），本服务只负责 Android 侧
 * 事件过滤、受限遍历、快照构建、执行前复核、动作与去重冷却。
 *
 * 关键约束：
 * - 只监听 typeWindowStateChanged / typeWindowContentChanged（见 res/xml 服务配置）；
 * - 前台包或 windowId 变化才建立新 epoch，相同窗口的重复事件不重置 epoch、不延长 10 秒窗口期；
 * - 事件在主线程按 250ms 合并扫描；同一 epoch 最多尝试一次 ACTION_CLICK（返回 false 也算尝试）；
 * - 同包两次动作至少间隔 2000ms；
 * - 只对 core 返回的候选（内置规则 + EXPLICIT_AD_SKIP）执行，且执行前复核配置 revision、
 *   active root 包/窗口、锁屏、节点 refresh 可见/可点击/同文案；
 * - 不模拟手势、不使用返回键、不点击父节点、不做模糊匹配；
 * - 未注入依赖、未连接、开关关闭或被排除的包一律不动作。
 */
class AdSkipService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val gate = SkipGate(
        setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
    )

    private val sensitivePackages by lazy { SensitivePackageResolver(applicationContext) }

    /** 规则引擎：默认使用 core 的保守实现；测试可替换（模块内部接缝，非公开 schema）。 */
    internal var ruleEngine: AdRuleEngine = ConservativeAdRuleEngine()

    @Volatile
    private var config: ProtectionConfig? = null

    private var configSubscription: Subscription? = null
    private var dependencySubscription: Subscription? = null
    private var pendingScanRunnable: Runnable? = null
    private var lastForegroundPackage: String? = null
    private var destroyed = false

    /** 系统绑定事实：只有 onServiceConnected 置 true，解绑/销毁置 false；不依赖配置。 */
    @Volatile
    private var serviceConnected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "ad skip service connected")
        destroyed = false
        serviceConnected = true
        gate.reset()
        AccessibilityRuntime.markConnected()
        attachDependency()
        val foreground = currentForegroundPackage()
        lastForegroundPackage = foreground
        publishPhase(foreground)
        startInitialEpoch()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val realEvent = event ?: return
        val packageName = realEvent.packageName?.toString()
        if (!packageName.isNullOrEmpty()) lastForegroundPackage = packageName

        val now = SystemClock.elapsedRealtime()
        when (val outcome = gate.onEvent(realEvent.eventType, packageName, realEvent.windowId, now)) {
            is GateEvent.ScanNow -> runScan()
            is GateEvent.ScanLater -> scheduleScan(outcome.delayMs)
            is GateEvent.Drop -> if (outcome.reason != GuardCode.EVENT_TYPE_FILTERED) publishPhase(packageName)
        }
    }

    override fun onInterrupt() {
        // AccessibilityService.onInterrupt 是抽象方法，不能调用 super；这里只做自己的清理
        // 中断只取消待执行任务并记录，不当作永久断开
        cancelPending(GuardCode.SERVICE_INTERRUPTED)
        AccessibilityRuntime.recordInterrupt(GuardCode.SERVICE_INTERRUPTED)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        destroyed = true
        cleanup()
        super.onDestroy()
    }

    // ---------- 依赖与配置 ----------

    private fun attachDependency() {
        dependencySubscription?.close()
        dependencySubscription = AccessibilityDependencies.attach { repository -> onRepositoryAvailable(repository) }
    }

    private fun onRepositoryAvailable(repository: ConfigRepository) {
        mainHandler.post {
            if (destroyed) return@post
            configSubscription?.close()
            configSubscription = repository.observe { snapshot ->
                // observe 可能来自写线程：只投递主线程，主线程之外不触碰服务状态与 gate
                mainHandler.post { applyConfig(snapshot) }
            }
        }
    }

    private fun applyConfig(snapshot: ProtectionConfig) {
        if (destroyed) return
        config = snapshot
        val target = gate.currentEpoch?.packageName ?: lastForegroundPackage
        val stillEnabled = target != null && PolicyResolver.accessibilityEnabled(snapshot, target)
        if (!stillEnabled) cancelPending(GuardCode.CONFIG_DISABLED)
        publishPhase(target)
    }

    private fun publishPhase(foregroundPackage: String? = lastForegroundPackage) {
        AccessibilityRuntime.updatePhase(
            PhaseCalculator.compute(
                connected = serviceConnected,
                dependencyInstalled = AccessibilityDependencies.repositoryOrNull() != null,
                config = config,
                foregroundPackage = foregroundPackage
            )
        )
    }

    // ---------- 事件合并与扫描调度 ----------

    private fun scheduleScan(delayMs: Long) {
        pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingScanRunnable = null
            runScan()
        }
        pendingScanRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    private fun cancelPending(reason: String) {
        gate.cancelPending(reason)
        pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingScanRunnable = null
    }

    private fun startInitialEpoch() {
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString()
        if (packageName.isNullOrEmpty()) return
        when (val outcome = gate.startInitialEpoch(packageName, root.windowId, SystemClock.elapsedRealtime())) {
            is GateEvent.ScanNow -> runScan()
            is GateEvent.ScanLater -> scheduleScan(outcome.delayMs)
            is GateEvent.Drop -> Unit
        }
    }

    private fun runScan() {
        try {
            scanOnce()
        } catch (t: Throwable) {
            Log.e(TAG, "scan failed: " + t.javaClass.simpleName)
            AccessibilityRuntime.recordError(GuardCode.INTERNAL_ERROR)
        }
    }

    private fun scanOnce() {
        val now = SystemClock.elapsedRealtime()
        val pending = gate.takeDueScan(now) ?: return

        val repositoryInstalled = AccessibilityDependencies.repositoryOrNull() != null
        val snapshotConfig = config
        // 依赖缺失或服务已解绑：保持全关，不做任何遍历与动作
        if (!repositoryInstalled || snapshotConfig == null) {
            publishPhase(pending.packageName)
            return
        }

        val root = rootInActiveWindow ?: run {
            publishPhase(pending.packageName)
            return
        }
        val packageName = root.packageName?.toString()
        if (packageName.isNullOrEmpty() ||
            packageName != pending.packageName ||
            root.windowId != pending.windowId
        ) {
            // 事件窗口与当前 active root 不一致：本次不动作，等待新事件建立 epoch
            publishPhase(packageName)
            return
        }
        if (!PolicyResolver.accessibilityEnabled(snapshotConfig, packageName)) {
            publishPhase(packageName)
            return
        }

        val revisionAtSnapshot = snapshotConfig.revision
        val windowInfo = findWindow(root.windowId)
        val keyguardLockedAtSnapshot = keyguardManager()?.isKeyguardLocked ?: true
        val metrics = resources.displayMetrics
        val collected = WindowSnapshotCollector.collect(
            root = root,
            packageName = packageName,
            windowId = root.windowId,
            foregroundSinceElapsedMs = pending.foregroundSinceElapsedMs,
            capturedAtElapsedMs = now,
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            isApplicationWindow = windowInfo?.type == AccessibilityWindowInfo.TYPE_APPLICATION,
            keyguardLocked = keyguardLockedAtSnapshot,
            sensitivePackage = sensitivePackages.isSensitive(packageName)
        )

        try {
            publishPhase(packageName)
            val candidate = ruleEngine.evaluate(snapshotConfig, collected.snapshot).firstOrNull() ?: return
            val targetNode = collected.nodesById[candidate.nodeId] ?: return

            val refreshed = refreshNode(targetNode)

            // 执行前复核：重新读取 active root、锁屏与配置（可能已在遍历期间变化）
            val rootAfter = try {
                rootInActiveWindow
            } catch (_: Throwable) {
                null
            }
            val latestConfig = config ?: snapshotConfig
            val keyguardLockedNow = keyguardManager()?.isKeyguardLocked ?: true
            // 窗口类型也按当前窗口重新读取：窗口已消失时按“不是应用窗口”保守拒绝
            val isApplicationWindowNow =
                findWindow(root.windowId)?.type == AccessibilityWindowInfo.TYPE_APPLICATION

            // 候选 → 复核请求的映射走共享的 SkipRequestBuilder（与集成测试同一份逻辑）
            val request = SkipRequestBuilder.build(
                nowElapsedMs = now,
                packageName = packageName,
                windowId = root.windowId,
                policy = PolicyGuardInput(
                    connected = serviceConnected,
                    dependencyInstalled = repositoryInstalled,
                    configRevisionAtSnapshot = revisionAtSnapshot,
                    configRevisionNow = latestConfig.revision,
                    packageName = packageName,
                    packageNameValid = ConfigValidator.isValidPackageName(packageName),
                    rejectedPackage = packageName in ConfigConstants.REJECTED_PACKAGE_KEYS,
                    accessibilityEnabledForPackage = PolicyResolver.accessibilityEnabled(latestConfig, packageName),
                    packageRuleIdsContainBuiltin = latestConfig.packages[packageName]?.ruleIds
                        ?.contains(ConfigConstants.BUILTIN_RULE_CONSERVATIVE_V1) == true
                ),
                window = WindowGuardInput(
                    packageName = packageName,
                    windowId = root.windowId,
                    rootPackageName = rootAfter?.packageName?.toString(),
                    rootWindowId = rootAfter?.windowId ?: -1,
                    isApplicationWindow = isApplicationWindowNow,
                    keyguardLocked = keyguardLockedNow,
                    sensitivePackage = sensitivePackages.isSensitive(packageName),
                    traversalComplete = collected.snapshot.traversalComplete,
                    hasEditableOrPasswordNode = collected.hasEditableOrPasswordNode,
                    screenWidthPx = collected.snapshot.screenWidthPx,
                    screenHeightPx = collected.snapshot.screenHeightPx,
                    capturedAtElapsedMs = collected.snapshot.capturedAtElapsedMs,
                    foregroundSinceElapsedMs = collected.snapshot.foregroundSinceElapsedMs
                ),
                snapshot = collected.snapshot,
                candidate = candidate,
                refreshed = refreshed
            ) ?: return

            val authorization = gate.authorizeClick(request)
            if (!authorization.perform) {
                Log.d(TAG, "skip refused: " + authorization.reason)
                return
            }

            val actionAccepted = try {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (t: Throwable) {
                Log.w(TAG, "performAction failed: " + t.javaClass.simpleName)
                false
            }
            // performAction(true) 只说明系统接收了动作；是否真的跳过要由目标页面自身证明。
            AccessibilityRuntime.recordAction(
                SkipActionRecord(
                    packageName = packageName,
                    ruleId = candidate.ruleId,
                    occurredAtElapsedMs = now,
                    actionAccepted = actionAccepted
                )
            )
        } finally {
            collected.releaseAll()
        }
    }

    private fun refreshNode(node: AccessibilityNodeInfo): NodeFields? = try {
        if (node.refresh()) {
            NodeFields(
                text = NodeText.limit(node.text?.toString() ?: ""),
                description = NodeText.limit(node.contentDescription?.toString() ?: ""),
                clickable = node.isClickable,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                editable = node.isEditable,
                password = node.isPassword
            )
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private fun findWindow(windowId: Int): AccessibilityWindowInfo? = try {
        windows?.firstOrNull { it.id == windowId }
    } catch (_: Throwable) {
        null
    }

    private fun keyguardManager(): KeyguardManager? = try {
        getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    } catch (_: Throwable) {
        null
    }

    private fun currentForegroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private fun cleanup() {
        cancelPending(GuardCode.SERVICE_UNBOUND)
        configSubscription?.close()
        configSubscription = null
        dependencySubscription?.close()
        dependencySubscription = null
        AccessibilityDependencies.detach()
        config = null
        lastForegroundPackage = null
        serviceConnected = false
        gate.reset()
        pendingScanRunnable = null
        AccessibilityRuntime.markDisconnected()
    }

    private companion object {
        const val TAG = "AdSkipService"
    }
}
