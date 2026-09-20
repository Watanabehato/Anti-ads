package com.antiads.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * 首页 —— 骨架占位，由 t8 实现真实中文界面（总开关、两种模式、状态事实分列展示）。
 *
 * 占位页只说明工程处于骨架阶段，不显示任何未经验证的“已保护/已激活”。
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
