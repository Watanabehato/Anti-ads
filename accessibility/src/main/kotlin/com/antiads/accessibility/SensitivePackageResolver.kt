package com.antiads.accessibility

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Android 侧敏感包解析（判定逻辑在 [SensitivePackageRules] 中，可纯 JVM 测试）。
 *
 * 只使用不需要扩大包可见性的公开 API（默认输入法设置、PackageManager.resolveActivity、
 * 已知系统包名），全部调用失败时退回“已知系统包”集合；真正的判定由纯函数完成，
 * 任何异常/空值都视为敏感（跳过）。
 */
internal class SensitivePackageResolver(private val context: Context) {

    private val alwaysSensitive: Set<String> = SensitivePackageRules.alwaysSensitive(context.packageName)

    private var cachedAtElapsedMs: Long = Long.MIN_VALUE
    private var cached: Set<String> = emptySet()

    fun isSensitive(packageName: String?): Boolean =
        SensitivePackageRules.isSensitive(packageName, alwaysSensitive, systemSensitivePackages())

    private fun systemSensitivePackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val cachedValue = cached
        if (cachedAtElapsedMs != Long.MIN_VALUE && now - cachedAtElapsedMs < CACHE_TTL_MS) return cachedValue
        val resolved = resolveSystemSensitivePackages()
        cached = resolved
        cachedAtElapsedMs = now
        return resolved
    }

    private fun resolveSystemSensitivePackages(): Set<String> {
        val result = LinkedHashSet<String>(KNOWN_SYSTEM_SENSITIVE)
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotEmpty() }
                ?.let { result.add(it) }
        } catch (_: Throwable) {
            // 读取设置失败：忽略，保持保守集合
        }
        try {
            val inputMethodManager =
                context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputMethodManager?.enabledInputMethodList?.forEach { info ->
                val pkg = try {
                    info.packageName
                } catch (_: Throwable) {
                    null
                }
                if (!pkg.isNullOrEmpty()) result.add(pkg)
            }
        } catch (_: Throwable) {
            // 输入法列表不可得：忽略
        }
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                ?.let { result.add(it) }
        } catch (_: Throwable) {
            // 无法解析默认 HOME：只保留已知集合
        }
        return result
    }

    private companion object {
        const val CACHE_TTL_MS: Long = 5_000L

        /** 无法枚举时也要保护的已知系统包（权限控制器、安装器、设置、锁屏）。 */
        val KNOWN_SYSTEM_SENSITIVE: Set<String> = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.settings",
            "com.android.keyguard"
        )
    }
}
