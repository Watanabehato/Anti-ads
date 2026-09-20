package com.antiads.app

import android.app.Application

/**
 * 宿主 Application —— 骨架占位，由 t8(ui-engineer / 开发 B) 实现。
 *
 * 正式实现（docs/architecture.md 第 3 节）需在 onCreate 中完成：
 * AccessibilityDependencies.install(AppConfigRepository.get(this))，
 * 并保证 Provider 先于 Application.onCreate 启动时也能惰性初始化同一实例。
 */
class AntiAdsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO(t8): 创建 AppConfigRepository 并安装到 AccessibilityDependencies
    }
}
