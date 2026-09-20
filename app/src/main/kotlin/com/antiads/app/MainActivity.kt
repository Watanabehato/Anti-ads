package com.antiads.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.antiads.accessibility.AccessibilityRuntime
import com.antiads.app.config.RuntimeReportStore
import com.antiads.app.ui.AccessibilityFact
import com.antiads.app.ui.HookReportFact
import com.antiads.app.ui.PackageListActivity
import com.antiads.app.ui.ReportFreshness
import com.antiads.app.ui.StatusFacts
import com.antiads.app.ui.UiInsets
import com.antiads.app.ui.UiViews
import com.antiads.core.config.ConfigStorageState
import com.antiads.core.config.ProtectionConfig
import com.antiads.core.config.Subscription
import com.antiads.core.status.AccessibilityPhase
import com.antiads.core.status.ConfigTransportState
import com.antiads.core.status.HookInstallState

/**
 * 中文首页（docs/requirements.md 第 3 节）。
 *
 * 事实分列，不合成“已全面保护”：
 * - 系统设置里的无障碍授权，与 AccessibilityRuntime 报告的“实际已连接”分开显示；
 * - 增强模式只显示目标进程自报的报告，并标注“进程自报”；
 * - 报告超过 15 秒显示“报告已过期”，且明确不解释为“一定没有注入”；
 * - 保存成功只说明持久化成功（含 revision），不代表已拦截。
 */
class MainActivity : ManagedActivity() {

    private lateinit var masterSwitch: CheckBox
    private lateinit var accessibilityModeSwitch: CheckBox
    private lateinit var hookModeSwitch: CheckBox
    private lateinit var savedStateView: TextView
    private lateinit var configuredCountView: TextView
    private lateinit var accessibilityFactView: TextView
    private lateinit var hookFactView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var binding = false
    private var generation = 0
    private var timerRunning = false
    private var configSubscription: Subscription? = null
    private var accessibilitySubscription: Subscription? = null

    /** 页面可见期间每秒刷新一次；离开页面即停止。 */
    private val statusTick = object : Runnable {
        override fun run() {
            refreshRuntimeFacts()
            if (timerRunning) {
                handler.postDelayed(this, STATUS_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (root, column) = UiViews.scrollColumn(this)

        column.addView(UiViews.title(this, R.string.home_title))
        column.addView(UiViews.body(this, R.string.home_subtitle))

        column.addView(UiViews.section(this, R.string.home_section_switches))
        masterSwitch = UiViews.checkBox(this, R.string.home_master_switch)
        accessibilityModeSwitch = UiViews.checkBox(this, R.string.home_accessibility_switch)
        hookModeSwitch = UiViews.checkBox(this, R.string.home_hook_switch)
        column.addView(masterSwitch)
        column.addView(accessibilityModeSwitch)
        column.addView(hookModeSwitch)
        savedStateView = UiViews.bodyText(this, "")
        configuredCountView = UiViews.bodyText(this, "")
        column.addView(savedStateView)
        column.addView(configuredCountView)

        masterSwitch.setOnCheckedChangeListener { _, checked -> onChangeMaster(checked) }
        accessibilityModeSwitch.setOnCheckedChangeListener { _, checked -> onChangeAccessibilityMode(checked) }
        hookModeSwitch.setOnCheckedChangeListener { _, checked -> onChangeHookMode(checked) }

        column.addView(UiViews.section(this, R.string.home_section_accessibility))
        accessibilityFactView = UiViews.bodyText(this, "")
        column.addView(accessibilityFactView)

        column.addView(UiViews.section(this, R.string.home_section_hook))
        hookFactView = UiViews.bodyText(this, "")
        column.addView(hookFactView)

        column.addView(UiViews.section(this, R.string.home_section_actions))
        column.addView(
            UiViews.button(this, R.string.home_manage_packages).apply {
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, PackageListActivity::class.java))
                }
            }
        )
        column.addView(
            UiViews.button(this, R.string.home_open_accessibility_settings).apply {
                setOnClickListener { openAccessibilitySettings() }
            }
        )
        column.addView(
            UiViews.button(this, R.string.home_open_framework_manager).apply {
                setOnClickListener { openFrameworkManager() }
            }
        )
        column.addView(
            UiViews.button(this, R.string.home_open_probe).apply {
                setOnClickListener { openProbeApp() }
            }
        )

        column.addView(UiViews.section(this, R.string.home_section_limits))
        column.addView(UiViews.body(this, R.string.home_limits_text))

        column.addView(UiViews.section(this, R.string.home_section_disable_recovery))
        column.addView(UiViews.body(this, R.string.home_disable_recovery_text))

        setContentView(root)
        UiInsets.applyToRoot(this, root)
        bindFromConfig(repository.snapshot())
    }

    override fun onStart() {
        super.onStart()
        val myGeneration = ++generation
        configSubscription = repository.observe { config ->
            runOnUiThread {
                if (myGeneration == generation) bindFromConfig(config)
            }
        }
        accessibilitySubscription = AccessibilityRuntime.observe { state ->
            runOnUiThread {
                if (myGeneration == generation) {
                    accessibilityFactView.text = formatAccessibility(
                        StatusFacts.accessibility(isServiceEnabledInSystemSettings(), state, SystemClock.elapsedRealtime())
                    )
                }
            }
        }
    }

    override fun onStop() {
        generation++
        configSubscription?.close()
        configSubscription = null
        accessibilitySubscription?.close()
        accessibilitySubscription = null
        super.onStop()
    }

    /** 从系统设置返回后会重新走 onResume：重新核查系统授权并刷新全部事实。 */
    override fun onResume() {
        super.onResume()
        bindFromConfig(repository.snapshot())
        refreshRuntimeFacts()
        timerRunning = true
        handler.removeCallbacks(statusTick)
        handler.postDelayed(statusTick, STATUS_INTERVAL_MS)
    }

    override fun onPause() {
        timerRunning = false
        handler.removeCallbacks(statusTick)
        super.onPause()
    }

    private fun onChangeMaster(checked: Boolean) {
        if (binding) return
        submitEdit({ config -> config.copy(masterEnabled = checked) }) { result ->
            handleWriteResult(result) { bindFromConfig(it) }
        }
    }

    private fun onChangeAccessibilityMode(checked: Boolean) {
        if (binding) return
        submitEdit({ config -> config.copy(accessibilityEnabled = checked) }) { result ->
            handleWriteResult(result) { bindFromConfig(it) }
        }
    }

    private fun onChangeHookMode(checked: Boolean) {
        if (binding) return
        submitEdit({ config -> config.copy(hookEnabled = checked) }) { result ->
            handleWriteResult(result) { bindFromConfig(it) }
        }
    }

    private fun bindFromConfig(config: ProtectionConfig) {
        binding = true
        masterSwitch.isChecked = config.masterEnabled
        accessibilityModeSwitch.isChecked = config.accessibilityEnabled
        hookModeSwitch.isChecked = config.hookEnabled
        binding = false
        savedStateView.text = getString(R.string.home_saved_state, config.revision, healthText())
        configuredCountView.text = getString(R.string.home_configured_count, config.packages.size)
    }

    private fun refreshRuntimeFacts() {
        val now = SystemClock.elapsedRealtime()
        val state = AccessibilityRuntime.state()
        accessibilityFactView.text = formatAccessibility(
            StatusFacts.accessibility(isServiceEnabledInSystemSettings(), state, now)
        )
        hookFactView.text = formatHookReports(StatusFacts.hookReports(RuntimeReportStore.snapshot(), now))
    }

    private fun formatAccessibility(fact: AccessibilityFact): CharSequence {
        val lines = mutableListOf<String>()
        lines += getString(R.string.accessibility_system_authorized, yesNo(fact.systemEnabled))
        lines += getString(R.string.accessibility_actually_connected, yesNo(fact.connected))
        lines += getString(R.string.accessibility_phase, phaseText(fact.phase))
        fact.activePackage?.let { lines += getString(R.string.accessibility_active_package, it) }
        val actionPackage = fact.lastActionPackage
        if (actionPackage == null) {
            lines += getString(R.string.accessibility_last_action_none)
        } else {
            lines += getString(
                R.string.accessibility_last_action,
                actionPackage,
                (fact.lastActionAgeMs ?: 0L) / 1000L,
                if (fact.lastActionAccepted == true) {
                    getString(R.string.action_accepted_true)
                } else {
                    getString(R.string.action_accepted_false)
                }
            )
        }
        // 只展示稳定错误码本身，不翻译成“成功”（t9 的 AccessibilityRuntime 已无骨架期常量）
        fact.lastErrorCode?.let { lines += getString(R.string.accessibility_error_code, it) }
        return lines.joinToString("\n")
    }

    private fun formatHookReports(facts: List<HookReportFact>): CharSequence {
        if (facts.isEmpty()) return getString(R.string.hook_no_report)
        return facts.joinToString("\n\n") { fact ->
            getString(
                R.string.hook_report_line,
                fact.packageName,
                fact.pid,
                fact.processToken.take(8),
                installStateText(fact.installState),
                transportStateText(fact.transportState),
                fact.policyRevision?.toString() ?: getString(R.string.value_unknown),
                fact.observedCallbacks,
                fact.droppedCallbacks,
                if (fact.freshness == ReportFreshness.FRESH) {
                    getString(R.string.report_age_seconds, fact.ageMs / 1000L)
                } else {
                    getString(R.string.report_expired)
                }
            )
        }
    }

    private fun isServiceEnabledInSystemSettings(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        val services = try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        } catch (e: RuntimeException) {
            return false
        }
        return services.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo
            StatusFacts.matchesAdSkipService(serviceInfo?.packageName, serviceInfo?.name)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.toast_no_accessibility_settings))
        }
    }

    /** 只打开已安装框架管理器的启动入口；找不到时给出可操作的中文说明，不假装能一键授权。 */
    private fun openFrameworkManager() {
        var started = false
        for (candidate in FRAMEWORK_MANAGER_PACKAGES) {
            val intent = packageManager.getLaunchIntentForPackage(candidate)
            if (intent != null) {
                startActivity(intent)
                started = true
                break
            }
        }
        if (!started) {
            toast(getString(R.string.toast_no_framework_manager))
        }
    }

    private fun openProbeApp() {
        val intent = packageManager.getLaunchIntentForPackage(PROBE_PACKAGE)
        if (intent == null) {
            toast(getString(R.string.toast_no_probe))
        } else {
            startActivity(intent)
        }
    }

    private fun yesNo(value: Boolean): String =
        getString(if (value) R.string.value_yes else R.string.value_no)

    private fun healthText(): String {
        val health = repository.health()
        return when (health.state) {
            ConfigStorageState.READY -> getString(R.string.health_ready)
            ConfigStorageState.DEFAULTS_NO_FILE -> getString(R.string.health_defaults)
            ConfigStorageState.RECOVERED_CORRUPT -> getString(R.string.health_corrupt, health.errorCode ?: "-")
            ConfigStorageState.IO_ERROR -> getString(R.string.health_io_error, health.errorCode ?: "-")
        }
    }

    private fun phaseText(phase: AccessibilityPhase): String = when (phase) {
        AccessibilityPhase.DISCONNECTED -> getString(R.string.phase_disconnected)
        AccessibilityPhase.IDLE -> getString(R.string.phase_idle)
        AccessibilityPhase.WATCHING -> getString(R.string.phase_watching)
        AccessibilityPhase.PAUSED -> getString(R.string.phase_paused)
        AccessibilityPhase.ERROR -> getString(R.string.phase_error)
    }

    private fun installStateText(state: HookInstallState): String = when (state) {
        HookInstallState.WAITING_CONTEXT -> getString(R.string.install_state_waiting)
        HookInstallState.INSTALLED -> getString(R.string.install_state_installed)
        HookInstallState.UNSUPPORTED -> getString(R.string.install_state_unsupported)
        HookInstallState.ERROR -> getString(R.string.install_state_error)
    }

    private fun transportStateText(state: ConfigTransportState): String = when (state) {
        ConfigTransportState.NEVER_READ -> getString(R.string.transport_never_read)
        ConfigTransportState.OK -> getString(R.string.transport_ok)
        ConfigTransportState.UNAVAILABLE -> getString(R.string.transport_unavailable)
        ConfigTransportState.UNAUTHORIZED -> getString(R.string.transport_unauthorized)
        ConfigTransportState.INVALID -> getString(R.string.transport_invalid)
        ConfigTransportState.EXPIRED -> getString(R.string.transport_expired)
    }

    private companion object {
        const val STATUS_INTERVAL_MS = 1000L
        const val PROBE_PACKAGE = "com.antiads.probe"

        /** 只是“打开管理器”的候选入口；是否存在、是否已激活模块由用户与框架决定。 */
        val FRAMEWORK_MANAGER_PACKAGES = listOf("org.lsposed.manager", "de.robv.android.xposed.installer")
    }
}
