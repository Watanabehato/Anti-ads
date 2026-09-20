package com.antiads.probe

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * 诊断首页 —— 骨架占位，由 t10 实现（真实 SensorManager 计数、广告样例入口）。
 * 占位页不注册传感器，也不产生任何实测结论。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply {
            text = getString(R.string.skeleton_placeholder)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(label)
    }
}
