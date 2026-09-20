package com.antiads.app

import android.app.Application
import com.antiads.accessibility.AccessibilityDependencies
import com.antiads.app.config.AppConfigRepository

/**
 * 宿主 Application（docs/architecture.md 第 3 节）：
 * 创建/复用配置仓储并安装到无障碍依赖入口。
 *
 * 安装依赖只代表依赖可用，不代表无障碍服务已连接，更不代表已生效；
 * 界面必须把“系统授权”“服务实际连接”“目标进程已读取配置”分开显示。
 */
class AntiAdsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AccessibilityDependencies.install(AppConfigRepository.get(this))
    }
}
