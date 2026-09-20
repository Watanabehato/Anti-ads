package com.antiads.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 敏感包判定：无法确定的输入一律按敏感处理（跳过）。 */
class SensitivePackageRulesTest {

    private val always = SensitivePackageRules.alwaysSensitive(Fixtures.PKG)
    private val system = setOf("com.example.ime", "com.example.launcher")

    @Test
    fun hostAndSystemUiAreAlwaysSensitive() {
        assertTrue("com.antiads.app" in always)
        assertTrue("android" in always)
        assertTrue("com.android.systemui" in always)
        assertTrue(Fixtures.PKG in always)
    }

    @Test
    fun unknownOrEmptyPackageIsSensitive() {
        assertTrue(SensitivePackageRules.isSensitive(null, always, system))
        assertTrue(SensitivePackageRules.isSensitive("", always, system))
    }

    @Test
    fun resolvedSystemPackagesAreSensitive() {
        assertTrue(SensitivePackageRules.isSensitive("com.example.ime", always, system))
        assertTrue(SensitivePackageRules.isSensitive("com.example.launcher", always, system))
    }

    @Test
    fun ordinaryThirdPartyPackageIsNotSensitive() {
        assertFalse(SensitivePackageRules.isSensitive("com.example.video", always, system))
    }
}
