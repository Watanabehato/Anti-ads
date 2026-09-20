package com.antiads.hook.internal

import android.util.Log

/**
 * 受限日志（合同第 3、7.8 节）：只记录固定状态码、计数与时间，不记录界面文本、
 * 传感器读数、配置原文或堆栈转储；传感器回调路径绝不逐次打日志。
 */
internal object HookLog {

    const val TAG: String = "AntiAdsHook"

    /** 机器可读诊断行（QA 抓取用），最多随报告节流输出（≤1 条/5s）。 */
    const val DIAG_TAG: String = "AntiAdsHook.Diag"

    fun info(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    fun diag(line: String) {
        runCatching { Log.i(DIAG_TAG, line) }
    }
}
