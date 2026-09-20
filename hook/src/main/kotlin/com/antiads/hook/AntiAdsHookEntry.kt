package com.antiads.hook

import com.antiads.hook.internal.HookRuntime
import com.antiads.hook.internal.HookSkipRules
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed / Xposed Legacy（API 82）入口，类名与 assets/xposed_init 一致（合同第 1、7 节）。
 *
 * - 使用框架提供的 packageName/processName，禁止以自报 arg 代替；
 * - 宿主、android/SystemUI 与 system_server 直接跳过；
 * - 任何异常都不得影响目标进程：捕获后仅记录，加载流程照常继续。
 */
class AntiAdsHookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam?.packageName ?: return
        val processName = lpparam.processName
        if (HookSkipRules.shouldSkip(packageName, processName)) return

        try {
            HookRuntime.onLoadPackage(
                packageName = packageName,
                processName = processName ?: packageName,
                classLoader = lpparam.classLoader
            )
        } catch (t: Throwable) {
            runCatching {
                XposedBridge.log("AntiAds: loadPackage failed: " + t.javaClass.name)
            }
        }
    }
}
