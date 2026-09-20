package com.antiads.hook.internal

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.antiads.core.policy.SensorAction
import com.antiads.core.status.HookInstallState
import com.antiads.hook.internal.policy.HookPolicyClientCore
import com.antiads.hook.internal.policy.HookReportContext
import com.antiads.hook.internal.policy.HookTransportCodes
import com.antiads.hook.internal.policy.PolicyJson
import com.antiads.hook.internal.policy.SingleThreadWorker
import android.app.AndroidAppHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 目标进程内的运行时接线（合同第 7 节）。
 *
 * 生命周期：
 * 1. loadPackage（框架提供 packageName/processName，绝不使用自报值）→ 安装 before-hook；
 * 2. 取得 Application Context（Xposed 当前 Application 或 Application.attach after-hook）→
 *    校验包身份后建立唯一后台 worker 与策略客户端；
 * 3. 没有 Context 时保持 [HookInstallState.WAITING_CONTEXT]，所有回调原样放行；
 * 4. 单进程只安装一组 Hook 与一个客户端（原子 guard 防重复）。
 */
internal object HookRuntime {

    const val WORKER_NAME: String = "antiads-hook-worker"

    private val installGuard = AtomicBoolean(false)
    private val contextGuard = AtomicBoolean(false)

    private val resolver = SensorTypeResolver()

    @Volatile
    private var worker: SingleThreadWorker? = null

    @Volatile
    private var client: HookPolicyClientCore? = null

    @Volatile
    private var targetPackageName: String = ""

    @Volatile
    private var targetProcessName: String = ""

    @Volatile
    private var processToken: String = ""

    @Volatile
    private var installState: HookInstallState = HookInstallState.WAITING_CONTEXT

    // ------------------------------------------------------------------ 入口

    fun onLoadPackage(packageName: String, processName: String, classLoader: ClassLoader) {
        if (!installGuard.compareAndSet(false, true)) return

        targetPackageName = packageName
        targetProcessName = processName
        processToken = UUID.randomUUID().toString()

        val result = SensorDispatchHookInstaller.install(classLoader, resolver) { queue, handle ->
            shouldDropSensorCallback(queue, handle)
        }
        installState = when (result) {
            is SensorDispatchHookInstaller.Result.Installed -> HookInstallState.WAITING_CONTEXT
            is SensorDispatchHookInstaller.Result.Unsupported -> HookInstallState.UNSUPPORTED
            is SensorDispatchHookInstaller.Result.Failed -> HookInstallState.ERROR
        }
        HookLog.info(
            "load_package pkg=" + packageName +
                " process=" + processName +
                " state=" + installState.name +
                " reason=" + result.reasonOrEmpty.ifEmpty { "-" }
        )

        val application = currentApplication()
        if (application != null) {
            attachContext(application)
        } else {
            installApplicationAttachHook()
        }
    }

    /**
     * Context 就绪：确认包身份后建立客户端。
     * 不跨进程借宿主 Context；身份不符则保持放行并记录。
     */
    fun attachContext(context: Context) {
        if (!contextGuard.compareAndSet(false, true)) return

        val expected = targetPackageName
        val actual = runCatching { context.packageName }.getOrNull()
        if (actual == null || actual != expected) {
            contextGuard.set(false)
            HookLog.warn("context_identity_mismatch expected=" + expected + " actual=" + (actual ?: "null"))
            return
        }

        val appContext = context.applicationContext ?: context
        val createdWorker = SingleThreadWorker(WORKER_NAME)
        worker = createdWorker

        val created = HookPolicyClientCore(
            packageName = expected,
            clock = { SystemClock.elapsedRealtime() },
            scheduler = { task -> createdWorker.submit(task) },
            transport = ProviderPolicyTransport(appContext),
            decoder = { json, pkg -> PolicyJson.decodePolicy(json, pkg) },
            reportSink = ProviderHookReportSender(appContext),
            reportContext = {
                HookReportContext(
                    processName = targetProcessName,
                    processToken = processToken,
                    pid = Process.myPid(),
                    apiLevel = Build.VERSION.SDK_INT,
                    installState = installState
                )
            }
        )
        client = created

        if (installState == HookInstallState.WAITING_CONTEXT) {
            installState = HookInstallState.INSTALLED
        }
        created.requestInitialFetch()
        HookLog.info(
            "context_attached pkg=" + expected +
                " process=" + targetProcessName +
                " token=" + processToken.take(8) +
                " state=" + installState.name
        )
    }

    // ------------------------------------------------------------------ 回调快路径

    /**
     * before-hook 决策：只在内存快照上判断，返回 true 表示丢弃本次 Java 回调。
     * 任何不确定情况（无 Context、无策略、类型无法解析、异常）都返回 false（原调用继续）。
     */
    fun shouldDropSensorCallback(queue: Any, handle: Int): Boolean {
        val core = client ?: return false
        val type = when (val resolution = resolver.resolve(queue, handle)) {
            is SensorTypeResolver.Resolution.Unresolved -> {
                core.recordTypeResolveFailure()
                return false
            }

            is SensorTypeResolver.Resolution.Resolved -> resolution.sensorType
        }
        val decision = core.onSensorCallback(type, SystemClock.elapsedRealtime())
        return decision.action == SensorAction.DROP_CALLBACK
    }

    // ------------------------------------------------------------------ 诊断/测试

    fun installStateSnapshot(): HookInstallState = installState

    fun clientSnapshot(): HookPolicyClientCore.Snapshot? = client?.snapshot()

    fun targetIdentity(): List<String> = listOf(targetPackageName, targetProcessName, processToken)

    /** 显式关闭后台 worker；进程终止由系统清理，正常路径无需调用。 */
    fun shutdown() {
        client = null
        val current = worker
        worker = null
        runCatching { current?.close() }
    }

    // ------------------------------------------------------------------ 内部

    private fun currentApplication(): Application? = try {
        AndroidAppHelper.currentApplication()
    } catch (t: Throwable) {
        null
    }

    /**
     * 后备路径：loadPackage 时还没有 Application 时，挂 Application.attach(Context) after-hook。
     * 安装失败也不影响放行语义（保持 WAITING_CONTEXT）。
     */
    private fun installApplicationAttachHook() {
        try {
            val attachMethod = XposedHelpers.findMethodExactIfExists(
                Application::class.java,
                "attach",
                Context::class.java
            ) ?: run {
                HookLog.warn("attach_hook_missing")
                return
            }
            XposedBridge.hookMethod(
                attachMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val application = param.thisObject as? Application ?: return
                        attachContext(application.applicationContext ?: application)
                    }
                }
            )
            HookLog.info("attach_hook_installed")
        } catch (t: Throwable) {
            HookLog.warn("attach_hook_failed")
        }
    }

    /** 供诊断行使用（不参与决策）。 */
    fun transportSummary(): String {
        val snapshot = client?.snapshot() ?: return HookTransportCodes.UNAVAILABLE
        return snapshot.transportState.name
    }
}
