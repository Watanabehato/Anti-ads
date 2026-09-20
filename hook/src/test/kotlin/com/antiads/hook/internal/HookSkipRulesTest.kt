package com.antiads.hook.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 入口跳过规则：宿主、android/SystemUI、system_server 一律不安装。 */
class HookSkipRulesTest {

    @Test
    fun skipsHostSystemUiAndSystemServer() {
        assertTrue(HookSkipRules.shouldSkip("com.antiads.app", "com.antiads.app"))
        assertTrue(HookSkipRules.shouldSkip("android", "system"))
        assertTrue(HookSkipRules.shouldSkip("com.android.systemui", "com.android.systemui"))
        assertTrue(HookSkipRules.shouldSkip(null, "com.example"))
        assertTrue(HookSkipRules.shouldSkip("", ""))
    }

    @Test
    fun installsForOrdinaryTargets() {
        assertFalse(HookSkipRules.shouldSkip("com.antiads.probe", "com.antiads.probe"))
        assertFalse(HookSkipRules.shouldSkip("com.example.game", "com.example.game:remote"))
        assertFalse(HookSkipRules.shouldSkip("com.android.chrome", "com.android.chrome"))
    }
}
