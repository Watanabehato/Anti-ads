package com.antiads.app.config

import com.antiads.core.config.ConfigCodec
import com.antiads.core.config.PackageConfig
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.protocol.ConfigProtocol
import com.antiads.core.status.ConfigTransportState
import com.antiads.core.status.HookInstallState
import com.antiads.core.status.HookProcessReport
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 鉴权与协议测试（docs/contracts.md 第 6 节）。
 *
 * 这里验证的是“拿到已捕获 uid/pid 之后”的判定逻辑与协议应答；
 * 真实 Binder.getCallingUid() 捕获、clearCallingIdentity 之前完成授权、以及跨进程成功读取，
 * 需要设备上的 instrumentation/诊断进程证据（本任务无设备，未做设备验证）。
 */
class ProviderCallRouterTest {

    @Test
    fun singleMatchingPackageGetsOnlyItsOwnMinimalPolicy() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)

        val response = environment.router()
            .handle(ProviderRequest(TARGET_UID, 4242, ConfigProtocol.GET_POLICY, TARGET, null))

        assertTrue(response.ok)
        assertNull(response.errorCode)
        val payload = response.payload
        assertNotNull(payload)
        val policy = ConfigCodec.decodePolicy(payload!!)
        assertEquals(TARGET, policy.packageName)
        assertTrue(policy.hookEnabled)
        assertEquals(setOf(1, 4, 9, 10, 11), policy.blockedSensorTypes)
        assertEquals(7L, policy.revision)
        assertFalse("不得返回其他包信息", payload.contains(OTHER))
    }

    @Test
    fun unconfiguredPackageStillGetsDisabledPolicy() {
        val environment = Environment()
        environment.allow(TARGET_UID, "com.example.unconfigured")

        val response = environment.router()
            .handle(ProviderRequest(TARGET_UID, 4242, ConfigProtocol.GET_POLICY, "com.example.unconfigured", null))

        assertTrue(response.ok)
        val policy = ConfigCodec.decodePolicy(response.payload!!)
        assertFalse(policy.hookEnabled)
        assertTrue(policy.blockedSensorTypes.isEmpty())
        assertEquals(7L, policy.revision)
    }

    @Test
    fun forgedArgPackageIsRejected() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)

        val response = environment.router()
            .handle(ProviderRequest(TARGET_UID, 4242, ConfigProtocol.GET_POLICY, OTHER, null))

        assertRejected(response, ConfigProtocol.ERROR_UNAUTHORIZED)
    }

    @Test
    fun sharedUidWithMultiplePackagesIsRejected() {
        val environment = Environment()
        environment.allow(TARGET_UID, "com.example.a", "com.example.b")

        val response = environment.router()
            .handle(ProviderRequest(TARGET_UID, 4242, ConfigProtocol.GET_POLICY, "com.example.a", null))

        assertRejected(response, ConfigProtocol.ERROR_SHARED_UID_UNSUPPORTED)
    }

    @Test
    fun unknownAndIsolatedUidsAreRejected() {
        val unknown = Environment()
        unknown.sameUser += 10_050
        unknown.applicationUid += 10_050
        assertRejected(
            unknown.router().handle(ProviderRequest(10_050, 1, ConfigProtocol.GET_POLICY, TARGET, null)),
            ConfigProtocol.ERROR_ISOLATED_OR_UNKNOWN_UID
        )

        val isolated = Environment()
        isolated.allow(10_051, TARGET)
        isolated.isolated += 10_051
        assertRejected(
            isolated.router().handle(ProviderRequest(10_051, 1, ConfigProtocol.GET_POLICY, TARGET, null)),
            ConfigProtocol.ERROR_ISOLATED_OR_UNKNOWN_UID
        )

        val noPackage = Environment()
        noPackage.allow(10_052)
        assertRejected(
            noPackage.router().handle(ProviderRequest(10_052, 1, ConfigProtocol.GET_POLICY, TARGET, null)),
            ConfigProtocol.ERROR_ISOLATED_OR_UNKNOWN_UID
        )
    }

    @Test
    fun crossUserAndNonApplicationUidsAreRejected() {
        val crossUser = Environment()
        crossUser.applicationUid += 10_060
        crossUser.packagesByUid[10_060] = arrayOf(TARGET)
        assertRejected(
            crossUser.router().handle(ProviderRequest(10_060, 1, ConfigProtocol.GET_POLICY, TARGET, null)),
            ConfigProtocol.ERROR_UNAUTHORIZED
        )

        val systemUid = Environment()
        systemUid.sameUser += 1000
        systemUid.packagesByUid[1000] = arrayOf("android")
        assertRejected(
            systemUid.router().handle(ProviderRequest(1000, 1, ConfigProtocol.GET_POLICY, "android", null)),
            ConfigProtocol.ERROR_UNAUTHORIZED
        )
    }

    @Test
    fun rejectedPackageNamesAreNeverServable() {
        val host = Environment()
        host.allow(10_070, "com.antiads.app")
        assertRejected(
            host.router().handle(ProviderRequest(10_070, 1, ConfigProtocol.GET_POLICY, "com.antiads.app", null)),
            ConfigProtocol.ERROR_UNAUTHORIZED
        )

        val systemUi = Environment()
        systemUi.allow(10_071, "com.android.systemui")
        assertRejected(
            systemUi.router()
                .handle(ProviderRequest(10_071, 1, ConfigProtocol.GET_POLICY, "com.android.systemui", null)),
            ConfigProtocol.ERROR_UNAUTHORIZED
        )
    }

    @Test
    fun unknownAndMutationMethodsAreRejected() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)
        val router = environment.router()

        for (method in ProviderMethodPolicy.FORBIDDEN) {
            assertRejected(
                router.handle(ProviderRequest(TARGET_UID, 1, method, TARGET, null)),
                ConfigProtocol.ERROR_INVALID_REQUEST,
                "方法 " + method + " 必须被拒绝"
            )
        }
        assertRejected(
            router.handle(ProviderRequest(TARGET_UID, 1, "unknown_method_v9", TARGET, null)),
            ConfigProtocol.ERROR_INVALID_REQUEST
        )
        assertRejected(
            router.handle(ProviderRequest(TARGET_UID, 1, null, TARGET, null)),
            ConfigProtocol.ERROR_INVALID_REQUEST
        )
    }

    @Test
    fun policyReadIsRateLimitedPerUid() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)
        val router = environment.router()

        repeat(5) { index ->
            assertTrue("第 " + (index + 1) + " 次读取应通过", router.handle(getRequest()).ok)
        }
        assertRejected(router.handle(getRequest()), ConfigProtocol.ERROR_RATE_LIMITED)

        environment.now += RATE_WINDOW_MS
        assertTrue(router.handle(getRequest()).ok)
    }

    @Test
    fun hookReportIsRateLimitedToOnePerSecond() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)
        val router = environment.router()

        assertTrue(router.handle(reportRequest()).ok)
        assertRejected(router.handle(reportRequest()), ConfigProtocol.ERROR_RATE_LIMITED)

        environment.now += RATE_WINDOW_MS
        assertTrue(router.handle(reportRequest()).ok)
        assertEquals(2, environment.saved.size)
    }

    @Test
    fun hookReportForOtherPackageIsRejected() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)

        val response = environment.router().handle(
            ProviderRequest(TARGET_UID, 4242, ConfigProtocol.REPORT_HOOK, TARGET, reportJson(OTHER))
        )

        assertRejected(response, ConfigProtocol.ERROR_UNAUTHORIZED)
        assertTrue(environment.saved.isEmpty())
    }

    @Test
    fun hookReportPidIsOverriddenWithCapturedPid() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)

        val response = environment.router().handle(
            ProviderRequest(TARGET_UID, 4242, ConfigProtocol.REPORT_HOOK, TARGET, reportJson(TARGET, pid = 9999))
        )

        assertTrue(response.ok)
        assertNull(response.payload)
        assertEquals(1, environment.saved.size)
        val (saved, receivedAt) = environment.saved.first()
        assertEquals(4242, saved.pid)
        assertEquals(environment.now, receivedAt)
        assertEquals(TARGET, saved.packageName)
    }

    @Test
    fun hookReportWithBadPayloadIsRejected() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)
        val router = environment.router()

        // 每 UID 报告限流为 1 次/秒：每次调用前推进时钟，否则后续调用会被挡在 RATE_LIMITED，
        // 而不是走到 payload 校验分支。
        assertRejected(
            router.handle(ProviderRequest(TARGET_UID, 1, ConfigProtocol.REPORT_HOOK, TARGET, null)),
            ConfigProtocol.ERROR_INVALID_REQUEST
        )
        environment.now += RATE_WINDOW_MS
        assertRejected(
            router.handle(ProviderRequest(TARGET_UID, 1, ConfigProtocol.REPORT_HOOK, TARGET, "x".repeat(5000))),
            ConfigProtocol.ERROR_INVALID_REQUEST
        )
        environment.now += RATE_WINDOW_MS
        assertRejected(
            router.handle(
                ProviderRequest(TARGET_UID, 1, ConfigProtocol.REPORT_HOOK, TARGET, "{\"schemaVersion\":2}")
            ),
            ConfigProtocol.ERROR_UNSUPPORTED_VERSION
        )
        environment.now += RATE_WINDOW_MS
        val token = UUID.randomUUID().toString()
        val badCounters = "{\"schemaVersion\":1,\"packageName\":\"" + TARGET +
            "\",\"processName\":\"p\",\"processToken\":\"" + token +
            "\",\"pid\":5,\"apiLevel\":29,\"installState\":\"INSTALLED\",\"transportState\":\"OK\"," +
            "\"policyRevision\":1,\"observedCallbacks\":1,\"droppedCallbacks\":5}"
        assertRejected(
            router.handle(ProviderRequest(TARGET_UID, 1, ConfigProtocol.REPORT_HOOK, TARGET, badCounters)),
            ConfigProtocol.ERROR_INVALID_REQUEST
        )
        assertEquals(0, environment.saved.size)
    }

    @Test
    fun hookReportWithNonPositiveCapturedPidIsRejected() {
        val environment = Environment()
        environment.allow(TARGET_UID, TARGET)

        val response = environment.router().handle(
            ProviderRequest(TARGET_UID, 0, ConfigProtocol.REPORT_HOOK, TARGET, reportJson(TARGET))
        )

        assertRejected(response, ConfigProtocol.ERROR_INVALID_REQUEST)
    }

    private fun getRequest() = ProviderRequest(TARGET_UID, 4242, ConfigProtocol.GET_POLICY, TARGET, null)

    private fun reportRequest() =
        ProviderRequest(TARGET_UID, 4242, ConfigProtocol.REPORT_HOOK, TARGET, reportJson(TARGET))

    private fun reportJson(packageName: String, pid: Int = 4242): String = ConfigCodec.encodeHookReport(
        HookProcessReport(
            packageName = packageName,
            processName = packageName,
            processToken = UUID.randomUUID().toString(),
            pid = pid,
            apiLevel = 29,
            installState = HookInstallState.INSTALLED,
            transportState = ConfigTransportState.OK,
            policyRevision = 7L,
            observedCallbacks = 12L,
            droppedCallbacks = 3L
        )
    )

    private fun assertRejected(response: ProviderResponse, expectedError: String, message: String = "") {
        assertFalse(message, response.ok)
        assertEquals(message, expectedError, response.errorCode)
        assertNull(message, response.payload)
    }

    private class Environment {

        var now: Long = 100_000L

        val packagesByUid: MutableMap<Int, Array<String>?> = mutableMapOf()
        val sameUser: MutableSet<Int> = mutableSetOf()
        val applicationUid: MutableSet<Int> = mutableSetOf()
        val isolated: MutableSet<Int> = mutableSetOf()
        val saved: MutableList<Pair<HookProcessReport, Long>> = mutableListOf()

        var config: ProtectionConfig = ProtectionConfig(
            masterEnabled = true,
            accessibilityEnabled = true,
            hookEnabled = true,
            revision = 7L,
            packages = mapOf(
                TARGET to PackageConfig(accessibilityEnabled = true, hookEnabled = true),
                OTHER to PackageConfig(hookEnabled = true)
            )
        )

        fun allow(uid: Int, vararg packages: String) {
            sameUser += uid
            applicationUid += uid
            packagesByUid[uid] = packages.toList().toTypedArray()
        }

        fun router(): ProviderCallRouter = ProviderCallRouter(
            configSnapshot = { config },
            packageLookup = { uid -> packagesByUid[uid] },
            sameUser = { uid -> uid in sameUser },
            isolatedUid = { uid -> uid in isolated },
            applicationUid = { uid -> uid in applicationUid },
            nowMs = { now },
            reportSink = { report, receivedAt -> saved += report to receivedAt }
        )
    }

    private companion object {
        const val TARGET = "com.example.target"
        const val OTHER = "com.example.other"
        const val TARGET_UID = 10_100

        /** 限流窗口长度（RateLimiter 默认 1000ms）。 */
        const val RATE_WINDOW_MS = 1_000L
    }
}
