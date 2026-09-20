package com.antiads.hook.internal

import de.robv.android.xposed.XC_MethodHook

/**
 * 安装 SensorEventQueue 分发 Hook（合同第 7 节）。
 *
 * - 定位失败/形状不符 → [Result.Unsupported]（上报 UNSUPPORTED）；
 * - 注册异常 → [Result.Failed]（上报 ERROR）；
 * - 只有真正注册成功才返回 [Result.Installed]；
 * - before-hook 中只有决策为丢弃时才通过 setResult 终止该 void 分发调用，
 *   其余完全交还原实现（不改 values、不伪造 Sensor、不改 registerListener 返回值）。
 */
internal object SensorDispatchHookInstaller {

    sealed interface Result {
        data object Installed : Result
        data class Unsupported(val reason: String) : Result
        data class Failed(val reason: String) : Result

        val reasonOrEmpty: String
            get() = when (this) {
                is Installed -> ""
                is Unsupported -> reason
                is Failed -> reason
            }
    }

    fun install(
        classLoader: ClassLoader,
        resolver: SensorTypeResolver,
        dispatcher: (Any, Int) -> Boolean
    ): Result {
        val targetClass = try {
            Class.forName(SensorDispatchTarget.CLASS_NAME, false, classLoader)
        } catch (t: Throwable) {
            return Result.Unsupported("TARGET_CLASS_NOT_FOUND")
        }

        return when (val analysis = SensorDispatchTarget.analyze(targetClass, resolver)) {
            is SensorDispatchTarget.Analysis.Rejected -> Result.Unsupported(analysis.reason)

            is SensorDispatchTarget.Analysis.Ok -> try {
                de.robv.android.xposed.XposedBridge.hookMethod(analysis.method, DispatchHook(dispatcher))
                Result.Installed
            } catch (t: Throwable) {
                Result.Failed("HOOK_REGISTRATION_FAILED")
            }
        }
    }

    /** before-hook：只在决策为丢弃时终止调用；任何局部异常都让原调用继续。 */
    private class DispatchHook(
        private val dispatcher: (Any, Int) -> Boolean
    ) : XC_MethodHook() {

        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val args = param.args ?: return
                if (args.size <= SensorDispatchTarget.HANDLE_ARG_INDEX) return
                val handle = args[SensorDispatchTarget.HANDLE_ARG_INDEX] as? Int ?: return
                val queue = param.thisObject ?: return
                if (dispatcher(queue, handle)) {
                    // void 方法：设置 result（含 null）表示不再执行原方法，等价于丢弃本次 Java 回调。
                    param.result = null
                }
            } catch (t: Throwable) {
                // 局部异常：原调用继续，绝不因为本模块改变目标行为。
            }
        }
    }
}
