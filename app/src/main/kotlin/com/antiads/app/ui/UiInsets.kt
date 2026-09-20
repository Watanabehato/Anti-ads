package com.antiads.app.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * targetSdk 35 的系统栏适配：
 * - API30+：显式关闭 decor fits（Android 15 上已强制边到边），自行按 systemBars 内边距；
 * - API29：系统默认行为已让内容避开系统栏，不额外处理。
 *
 * 说明：需要真机/模拟器确认视觉效果，本任务无设备，未做交互实测。
 */
internal object UiInsets {

    @Suppress("DEPRECATION") // API35 起该方法被标记废弃（边到边已强制），但 API30~34 仍需要它才能拿到 systemBars insets
    fun applyToRoot(activity: Activity, root: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
    }
}
