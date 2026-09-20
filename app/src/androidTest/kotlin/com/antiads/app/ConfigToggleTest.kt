package com.antiads.app

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.antiads.app.config.AppConfigRepository
import com.antiads.core.config.ConfigCodec
import com.antiads.core.config.ConfigHealth
import com.antiads.core.config.ConfigRepository
import com.antiads.core.config.ConfigWriteResult
import com.antiads.core.config.ProtectionConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 宿主 UID 配置总开关仪器测试（QA H01 备用路径 A2：由宿主自身的真实写入路径制造外部状态变化）。
 *
 * 运行方式（test APK 由顺序集成 t11 组装，runner 统一 AndroidJUnitRunner）：
 *
 *   adb shell am instrument -w -e action master_off \
 *     com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner
 *   adb shell am instrument -w -e class com.antiads.app.ConfigToggleTest -e action master_on \
 *     com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 约束（captain 交接，配合 docs/contracts.md 第 3、6 节）：
 * - 只调用宿主内部真实仓储的合同写路径 write(config, expectedRevision)，不新增也不使用任何 exported 写配置接口；
 * - 不启动任何 Activity、不依赖界面、不做 Root/su 操作；
 * - 只存在于 androidTest 源集，不进入主 APK；
 * - 本测试会真实翻转总开关并落盘（这正是 H01 A2 需要的外部状态变化），日志行为机器可读证据：
 *   antiads_config_toggle action=... result=... revision_before=... revision_after=... flipped=... elapsed_ms=...
 */
@RunWith(AndroidJUnit4::class)
class ConfigToggleTest {

    @Test
    fun toggleMasterSwitchThroughHostRepositoryWritePath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // t8 首项修正：android.app.Instrumentation 没有 getArguments()，参数必须走 androidx.test 的 InstrumentationRegistry。
        val action = InstrumentationRegistry.getArguments().getString(ARG_ACTION)?.trim()?.lowercase().orEmpty()
        if (action != ACTION_MASTER_ON && action != ACTION_MASTER_OFF) {
            fail("必须通过 -e action 传入 master_on 或 master_off（实际：$action）")
        }
        val desiredMasterEnabled = action == ACTION_MASTER_ON

        val context: Context = instrumentation.targetContext.applicationContext
        assertTrue(
            "必须在宿主应用 UID 内运行（expected=${context.applicationInfo.uid} actual=${Process.myUid()}）",
            Process.myUid() == context.applicationInfo.uid
        )

        // 与 AntiAdsApplication.onCreate / ConfigProvider 相同的惰性单例：走真实宿主写入路径，不另造仓储实现。
        val repository: ConfigRepository = AppConfigRepository.get(context)
        val healthBefore = repository.health()

        var attempt = 0
        while (true) {
            attempt += 1
            val before = repository.snapshot()

            if (before.masterEnabled == desiredMasterEnabled) {
                // 目标状态已成立：不算失败，但 flipped=false，QA 不能把它当作 H01 A2 的状态变化证据。
                val line = buildLine(
                    action = action,
                    outcome = OUTCOME_ALREADY_AT_TARGET,
                    revisionBefore = before.revision,
                    revisionAfter = before.revision,
                    elapsedMs = 0L,
                    flipped = false,
                    healthBefore = healthBefore,
                    healthAfter = repository.health()
                )
                Log.i(TAG, line)
                reportStatus(instrumentation, action, OUTCOME_ALREADY_AT_TARGET, before.revision, before.revision, 0L, false)
                assertEquals("总开关应保持目标值", desiredMasterEnabled, repository.snapshot().masterEnabled)
                return
            }

            // 合同第 3 节：输入 config.revision 必须等于 expectedRevision；只改总开关，不动包配置。
            val candidate = before.copy(masterEnabled = desiredMasterEnabled, revision = before.revision)
            val startedAtMs = SystemClock.elapsedRealtime()
            val result = repository.write(candidate, before.revision)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs

            when (result) {
                is ConfigWriteResult.Saved -> {
                    val after = repository.snapshot()
                    val persisted = readPersistedConfig(context)

                    assertEquals("Saved.config.revision 必须是 expectedRevision+1", before.revision + 1L, result.config.revision)
                    assertEquals("Saved.config 的总开关必须是目标值", desiredMasterEnabled, result.config.masterEnabled)
                    assertEquals("内存快照必须已发布新版本", result.config.revision, after.revision)
                    assertEquals("内存快照的总开关必须是目标值", desiredMasterEnabled, after.masterEnabled)
                    assertEquals("本次写入不得改动包配置", before.packages, after.packages)
                    assertEquals("磁盘配置 revision 必须与 Saved 一致", result.config.revision, persisted.revision)
                    assertEquals("磁盘配置总开关必须是目标值", desiredMasterEnabled, persisted.masterEnabled)
                    assertEquals("磁盘配置不得改动包配置", before.packages, persisted.packages)
                    assertEquals("全局无障碍开关不得被本次写入改动", before.accessibilityEnabled, persisted.accessibilityEnabled)
                    assertEquals("全局增强模式开关不得被本次写入改动", before.hookEnabled, persisted.hookEnabled)

                    val line = buildLine(
                        action = action,
                        outcome = OUTCOME_SAVED,
                        revisionBefore = before.revision,
                        revisionAfter = result.config.revision,
                        elapsedMs = elapsedMs,
                        flipped = true,
                        healthBefore = healthBefore,
                        healthAfter = repository.health()
                    )
                    Log.i(TAG, line)
                    reportStatus(instrumentation, action, OUTCOME_SAVED, before.revision, result.config.revision, elapsedMs, true)
                    return
                }

                is ConfigWriteResult.Rejected -> {
                    val outcome = OUTCOME_REJECTED_PREFIX + "_" + result.reason
                    val line = buildLine(
                        action = action,
                        outcome = outcome,
                        revisionBefore = before.revision,
                        revisionAfter = before.revision,
                        elapsedMs = elapsedMs,
                        flipped = false,
                        healthBefore = healthBefore,
                        healthAfter = repository.health()
                    )
                    if (result.reason == AppConfigRepository.REASON_CONFLICT && attempt < MAX_WRITE_ATTEMPTS) {
                        // 冲突时读取新快照后重试，绝不自动覆盖另一方的更改（合同第 3 节）。
                        Log.i(TAG, "$line retry=$attempt/$MAX_WRITE_ATTEMPTS")
                        continue
                    }
                    Log.w(TAG, line)
                    reportStatus(instrumentation, action, outcome, before.revision, before.revision, elapsedMs, false)
                    throw AssertionError("总开关写入失败（" + result.reason + "）：" + line)
                }
            }
        }
    }

    /** 直接读取宿主私有配置文件，作为“已落盘”的独立证据（合同第 1 节：filesDir/protection-config-v1.json）。 */
    private fun readPersistedConfig(context: Context): ProtectionConfig {
        val file = File(context.filesDir, CONFIG_FILE_NAME)
        if (!file.isFile) {
            throw AssertionError("持久化文件不存在：" + file.absolutePath)
        }
        val json = file.readText(Charsets.UTF_8)
        return try {
            ConfigCodec.decodeConfig(json)
        } catch (e: IllegalArgumentException) {
            throw AssertionError("持久化文件无法按 schemaVersion=1 解码：" + e.message, e)
        }
    }

    private fun buildLine(
        action: String,
        outcome: String,
        revisionBefore: Long,
        revisionAfter: Long,
        elapsedMs: Long,
        flipped: Boolean,
        healthBefore: ConfigHealth,
        healthAfter: ConfigHealth
    ): String = buildString {
        append("antiads_config_toggle")
        append(" action=").append(action)
        append(" result=").append(outcome)
        append(" revision_before=").append(revisionBefore)
        append(" revision_after=").append(revisionAfter)
        append(" flipped=").append(flipped)
        append(" elapsed_ms=").append(elapsedMs)
        append(" health_before=").append(healthBefore.state)
        append(" health_error_before=").append(healthBefore.errorCode ?: "-")
        append(" health_after=").append(healthAfter.state)
        append(" app_uid=").append(Process.myUid())
        append(" pid=").append(Process.myPid())
        append(" elapsed_realtime_ms=").append(SystemClock.elapsedRealtime())
    }

    /** 供 am instrument -w 直接抓取的机器可读结果；没有监听者时静默忽略。 */
    private fun reportStatus(
        instrumentation: Instrumentation,
        action: String,
        outcome: String,
        revisionBefore: Long,
        revisionAfter: Long,
        elapsedMs: Long,
        flipped: Boolean
    ) {
        val bundle = Bundle().apply {
            putString("anti_ads_action", action)
            putString("anti_ads_result", outcome)
            putLong("anti_ads_revision_before", revisionBefore)
            putLong("anti_ads_revision_after", revisionAfter)
            putLong("anti_ads_elapsed_ms", elapsedMs)
            putBoolean("anti_ads_flipped", flipped)
        }
        runCatching { instrumentation.sendStatus(STATUS_CODE_RESULT, bundle) }
    }

    private companion object {
        const val TAG = "AntiAdsConfigToggle"

        /** H01 A2 备用路径的外部驱动参数。 */
        const val ARG_ACTION = "action"
        const val ACTION_MASTER_ON = "master_on"
        const val ACTION_MASTER_OFF = "master_off"

        /** 合同第 1 节固定的宿主私有配置文件名。 */
        const val CONFIG_FILE_NAME = "protection-config-v1.json"

        const val OUTCOME_SAVED = "SAVED"
        const val OUTCOME_ALREADY_AT_TARGET = "ALREADY_AT_TARGET"
        const val OUTCOME_REJECTED_PREFIX = "REJECTED"
        const val MAX_WRITE_ATTEMPTS = 5

        /** Instrumentation.REPORT_VALUE_RESULT_OK，用于 am instrument -w 抓取。 */
        const val STATUS_CODE_RESULT = 0
    }
}
