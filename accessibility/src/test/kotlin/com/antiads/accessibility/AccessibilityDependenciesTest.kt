package com.antiads.accessibility

import com.antiads.core.config.ConfigHealth
import com.antiads.core.config.ConfigRepository
import com.antiads.core.config.ConfigStorageState
import com.antiads.core.config.ConfigWriteResult
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.config.Subscription
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** 依赖注入入口：install 只保存引用，绝不制造“已连接”假象；两种启动顺序都要生效。 */
class AccessibilityDependenciesTest {

    private class FakeRepository(private val value: ProtectionConfig) : ConfigRepository {
        var observers: Int = 0

        override fun snapshot(): ProtectionConfig = value

        override fun health(): ConfigHealth = ConfigHealth(ConfigStorageState.DEFAULTS_NO_FILE)

        override fun write(config: ProtectionConfig, expectedRevision: Long): ConfigWriteResult =
            ConfigWriteResult.Rejected("NOT_SUPPORTED_IN_TEST")

        override fun observe(listener: (ProtectionConfig) -> Unit): Subscription {
            observers++
            listener(value)
            return Subscription { }
        }
    }

    @Before
    fun setUp() {
        AccessibilityDependencies.resetForTest()
        AccessibilityRuntime.resetForTest()
    }

    @After
    fun tearDown() {
        AccessibilityDependencies.resetForTest()
        AccessibilityRuntime.resetForTest()
    }

    @Test
    fun installExposesRepositoryWithoutClaimingConnection() {
        val repository = FakeRepository(ProtectionConfig())
        AccessibilityDependencies.install(repository)
        assertSame(repository, AccessibilityDependencies.repositoryOrNull())
        assertFalse(AccessibilityRuntime.state().connected)
    }

    @Test
    fun attachAfterInstallReceivesRepositoryImmediately() {
        val repository = FakeRepository(ProtectionConfig())
        AccessibilityDependencies.install(repository)
        var received: ConfigRepository? = null
        val subscription = AccessibilityDependencies.attach { received = it }
        assertSame(repository, received)
        subscription.close()
    }

    @Test
    fun installAfterAttachNotifiesAttachedService() {
        var received: ConfigRepository? = null
        val subscription = AccessibilityDependencies.attach { received = it }
        assertNull(received)
        val repository = FakeRepository(ProtectionConfig())
        AccessibilityDependencies.install(repository)
        assertSame(repository, received)
        subscription.close()
    }

    @Test
    fun detachStopsFurtherNotifications() {
        var calls = 0
        val subscription = AccessibilityDependencies.attach { calls++ }
        AccessibilityDependencies.detach()
        AccessibilityDependencies.install(FakeRepository(ProtectionConfig()))
        assertEquals(0, calls)
        subscription.close()
    }

    @Test
    fun closingAttachmentStopsNotifications() {
        var calls = 0
        val subscription = AccessibilityDependencies.attach { calls++ }
        subscription.close()
        AccessibilityDependencies.install(FakeRepository(ProtectionConfig()))
        assertEquals(0, calls)
    }
}
