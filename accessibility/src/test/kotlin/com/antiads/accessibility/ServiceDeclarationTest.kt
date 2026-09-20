package com.antiads.accessibility

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 服务声明与系统配置的静态检查。
 *
 * 断言的是 contracts 要求的固定属性（文本级）；XML/AAPT 合法性由 assembleDebug 与 lint 保证，
 * 合并后的 Manifest 由集成/QA 在 :app 产物上核验。工作目录默认为模块目录（Gradle Test 语义），
 * 找不到时向上查找，避免因为调用方式不同而给出误导性通过。
 */
class ServiceDeclarationTest {

    @Test
    fun serviceConfigDeclaresContractAttributes() {
        val xml = readModuleFile("src/main/res/xml/accessibility_service_config.xml")
        assertContains(xml, "android:accessibilityEventTypes=\"typeWindowStateChanged|typeWindowContentChanged\"", "只接收两类窗口事件")
        assertContains(xml, "android:accessibilityFeedbackType=\"feedbackGeneric\"", "反馈类型")
        assertContains(xml, "android:notificationTimeout=\"100\"", "通知合并阈值 100ms")
        assertContains(xml, "android:canRetrieveWindowContent=\"true\"", "必须能读取窗口内容")
        assertContains(xml, "android:canPerformGestures=\"false\"", "必须禁用手势")
        assertContains(xml, "android:canRequestTouchExplorationMode=\"false\"", "必须禁用触摸探索")
        assertContains(xml, "android:canRequestFilterKeyEvents=\"false\"", "必须禁用按键过滤")

        val flags = attributeValue(xml, "android:accessibilityFlags")
        assertContains(flags, "flagRetrieveInteractiveWindows", "窗口类型判定需要该 flag")
        assertContains(flags, "flagReportViewIds", "viewId 上报需要该 flag")
        assertFalse("不允许开启触摸探索 flag：" + flags, flags.contains("flagRequestTouchExplorationMode"))
        assertFalse("不允许开启按键过滤 flag：" + flags, flags.contains("flagRequestFilterKeyEvents"))
        assertFalse("不使用指纹手势：" + flags, flags.contains("flagRequestFingerprintGestures"))
    }

    @Test
    fun manifestDeclaresSystemBoundServiceOnly() {
        val manifest = readModuleFile("src/main/AndroidManifest.xml")
        assertContains(manifest, "com.antiads.accessibility.AdSkipService", "服务类名必须是全限定名")
        assertContains(manifest, "android.permission.BIND_ACCESSIBILITY_SERVICE", "必须带系统绑定权限")
        assertContains(manifest, "android:exported=\"true\"", "只由系统绑定的服务需要显式 exported")
        assertContains(manifest, "android.accessibilityservice.AccessibilityService", "必须声明无障碍服务 intent-filter")
        assertContains(manifest, "android:resource=\"@xml/accessibility_service_config\"", "必须引用服务配置 XML")
        val serviceBlock = manifest.substringAfter("<service", "")
        assertTrue(
            "服务声明中不允许出现其它 action（例如可被普通应用启动的入口）：" + serviceBlock,
            !serviceBlock.contains("android.intent.action.MAIN")
        )
    }

    @Test
    fun serviceClassIsAnAccessibilityService() {
        val type = Class.forName("com.antiads.accessibility.AdSkipService")
        assertTrue(
            "AdSkipService 必须继承 AccessibilityService，否则系统无法绑定",
            AccessibilityService::class.java.isAssignableFrom(type)
        )
    }

    private fun assertContains(haystack: String, needle: String, what: String) {
        assertTrue(what + " 缺失：" + needle, haystack.contains(needle))
    }

    private fun attributeValue(xml: String, attributeName: String): String {
        val match = Regex(Regex.escape(attributeName) + "=\"([^\"]*)\"").find(xml)
        assertTrue(attributeName + " 未声明", match != null)
        return match!!.groupValues[1]
    }

    private fun readModuleFile(relative: String): String {
        val candidates = ArrayList<File>()
        candidates.add(File(relative))
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val current = dir ?: return@repeat
            candidates.add(File(current, relative))
            candidates.add(File(File(current, "accessibility"), relative))
            dir = current.parentFile
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "找不到模块文件 " + relative + "；unit test 工作目录=" + System.getProperty("user.dir")
            )
        return found.readText(Charsets.UTF_8)
    }
}
