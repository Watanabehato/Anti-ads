package com.antiads.app

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antiads.app.ui.StatusFacts
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QA-01 设备级回归：无障碍服务组件在**真实包管理器**中归属宿主包，而不是 :accessibility 的库 namespace。
 *
 * 修复前的缺陷是“用库 namespace 当服务所属包”，在 JVM 侧只能靠纯逻辑用例覆盖；
 * 这个 instrumentation 用例直接查 PackageManager，验证最终 APK 的真实组件身份。
 * 需要设备/模拟器（由独立 QA 执行）；本用例不修改任何配置或系统状态。
 */
@RunWith(AndroidJUnit4::class)
class ServiceOwnershipTest {

    @Test
    fun adSkipServiceComponentBelongsToHostPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hostPackage = context.packageName

        assertEquals("com.antiads.app", hostPackage)
        assertNotEquals("com.antiads.accessibility", hostPackage)

        val component = ComponentName(hostPackage, StatusFacts.AD_SKIP_SERVICE_CLASS)
        // 组件不存在会抛 NameNotFoundException → 直接判为失败
        val info = context.packageManager.getServiceInfo(component, 0)

        assertEquals(hostPackage, info.packageName)
        assertNotEquals("com.antiads.accessibility", info.packageName)
        assertEquals(StatusFacts.AD_SKIP_SERVICE_CLASS, info.name)

        // 用库 namespace 作为归属包必须解析不到该组件（这正是修复前会发生的错误匹配）
        val wrongComponent = ComponentName("com.antiads.accessibility", StatusFacts.AD_SKIP_SERVICE_CLASS)
        val wrongResolved = try {
            context.packageManager.getServiceInfo(wrongComponent, 0)
        } catch (e: Exception) {
            null
        }
        assertTrue(wrongResolved == null || wrongResolved.packageName != hostPackage)
    }
}
