package com.antiads.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.antiads.probe.diag.DiagJson
import com.antiads.probe.diag.ProbeTypes
import com.antiads.probe.diag.ProbeUiStateRules
import com.antiads.probe.diag.SensorDiagSession

/**
 * 诊断首页（contracts 第 8 节）。
 *
 * - 使用真实 SensorManager / SensorEventListener，逐类型显示是否存在、registerListener 返回值、
 *   累计回调数、最近回调 elapsedRealtime 与当前启停状态；
 * - 默认 SENSOR_DELAY_NORMAL，开始/停止为显式按钮；
 * - onPause 注销全部监听并停止 UI timer，onResume **不**自动重启采样（页面上有明确提示）；
 * - 一次实验最多 5 种配置支持类型，并给出至少一种设备可用且未被选中的对照类型；
 * - 无 INTERNET 权限；本页不注册"广告"，也不伪装成商业广告验证。
 */
class MainActivity : Activity() {

    private lateinit var session: SensorDiagSession
    private val handler = Handler(Looper.getMainLooper())
    private val rowViews = LinkedHashMap<Int, TextView>()

    private lateinit var stateView: TextView
    private lateinit var controlView: TextView
    private lateinit var shakeView: TextView
    private lateinit var hintView: TextView
    private lateinit var selfCheckView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var selfCheckEnabled: CheckBox? = null
    private var selfCheck: PolicySelfCheck? = null
    private var lastSelfCheckAtMs: Long = 0L

    /** 生命周期状态（QA-03）：onPause 停止采样后置位，用于提示与按钮同步，直到用户显式开始/停止。 */
    private var pausedByLifecycle: Boolean = false
    private var lastStartFailed: Boolean = false
    private var hasStopped: Boolean = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            // QA-04：debuggable 变体按不超过 1 次/秒 写 files/probe-diag.json（非 debuggable 内部直接跳过）
            writeDiagSnapshot(force = false)
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SensorDiagSession(context = this, host = com.antiads.probe.diag.DiagHost.ACTIVITY)
        selfCheck = PolicySelfCheck(this, packageName)
        setContentView(buildContentView())
        startDiagnosticsNotice()
    }

    override fun onResume() {
        super.onResume()
        session.activityResumed = true
        // QA-03：返回页面必须重建周期刷新并同步按钮状态；不自动开始采样（见 ProbeUiStateRules）
        applyUiState()
        writeDiagSnapshot(force = true)
    }

    override fun onPause() {
        session.activityResumed = false
        if (session.isRunning()) {
            session.stop()
            pausedByLifecycle = true
        }
        // QA-03：停止后同步按钮/提示/周期刷新，保证返回时"开始采样"可用、"停止"禁用
        applyUiState()
        writeDiagSnapshot(force = true)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (session.isRunning()) session.stop()
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        container.addView(headline(R.string.probe_title))
        container.addView(body(R.string.probe_note))

        stateView = body(R.string.probe_state_placeholder)
        controlView = body(R.string.probe_state_placeholder)
        shakeView = body(R.string.probe_state_placeholder)
        hintView = body(R.string.probe_start_hint)
        container.addView(stateView)
        container.addView(controlView)
        container.addView(shakeView)

        for (type in ProbeTypes.DEFAULT_SELECTED) {
            val view = body(R.string.probe_state_placeholder)
            rowViews[type] = view
            container.addView(view)
        }
        val controlType = session.controlType
        if (controlType != null && !rowViews.containsKey(controlType)) {
            val view = body(R.string.probe_state_placeholder)
            rowViews[controlType] = view
            container.addView(view)
        }

        container.addView(hintView)

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply {
            text = getString(R.string.probe_button_start)
            setOnClickListener { startSampling() }
        }
        stopButton = Button(this).apply {
            text = getString(R.string.probe_button_stop)
            setOnClickListener { stopSampling() }
        }
        buttonRow.addView(startButton)
        buttonRow.addView(stopButton)
        container.addView(buttonRow)

        container.addView(headline(R.string.probe_section_fixtures))
        for (scenario in SCENARIO_ORDER) {
            container.addView(
                Button(this).apply {
                    text = getString(R.string.probe_button_scenario_fmt, scenario)
                    setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, AdFixtureActivity::class.java)
                                .putExtra(AdFixtureActivity.EXTRA_SCENARIO, scenario)
                        )
                    }
                }
            )
        }
        container.addView(body(R.string.probe_fixture_note))

        container.addView(headline(R.string.probe_section_self_check))
        selfCheckEnabled = CheckBox(this).apply {
            text = getString(R.string.probe_self_check_toggle)
            setOnCheckedChangeListener { _, checked -> if (checked) runSelfCheck() else selfCheckView.text = getString(R.string.probe_self_check_off) }
        }
        selfCheckView = body(R.string.probe_self_check_off)
        container.addView(selfCheckEnabled)
        container.addView(selfCheckView)

        container.addView(headline(R.string.probe_section_lifecycle))
        container.addView(body(R.string.probe_lifecycle_note))

        return ScrollView(this).apply { addView(container) }
    }

    private fun startDiagnosticsNotice() {
        Log.i(
            DiagJson.LOG_TAG,
            "{\"tag\":\"" + DiagJson.LOG_TAG + "\",\"kind\":\"probe_open\",\"host\":\"ACTIVITY\",\"pid\":" +
                android.os.Process.myPid() + ",\"sessionId\":\"" + session.diagnosticsSessionId() +
                "\",\"controlType\":" + (session.controlType ?: "null") +
                ",\"elapsedMs\":" + SystemClock.elapsedRealtime() + "}"
        )
    }

    private fun startSampling() {
        val started = session.start()
        pausedByLifecycle = false
        lastStartFailed = !started
        hasStopped = started
        applyUiState()
        writeDiagSnapshot(force = true)
    }

    private fun stopSampling() {
        session.stop()
        pausedByLifecycle = false
        lastStartFailed = false
        hasStopped = true
        applyUiState()
        writeDiagSnapshot(force = true)
    }

    /**
     * 按钮、提示与周期刷新的唯一同步点（QA-03）。
     *
     * 所有会改变采样状态的地方（显式开始/停止、onPause 自动注销、onResume 返回）都必须经过这里，
     * 避免出现"采样已停但按钮仍是运行态"。规则本身永不请求自动开始，
     * 因此返回页面后只能由用户点击"开始采样"重新注册（不得用自动重注册绕开显式开始）。
     */
    private fun applyUiState() {
        val state = ProbeUiStateRules.of(
            samplingRunning = session.isRunning(),
            activityResumed = session.activityResumed
        )
        startButton.isEnabled = state.startEnabled
        stopButton.isEnabled = state.stopEnabled
        hintView.text = getString(
            when {
                session.isRunning() -> R.string.probe_running_hint
                pausedByLifecycle -> R.string.probe_paused_hint
                lastStartFailed -> R.string.probe_start_failed_hint
                hasStopped -> R.string.probe_stopped_hint
                else -> R.string.probe_start_hint
            }
        )
        refresh()
        handler.removeCallbacks(ticker)
        if (state.periodicRefresh) handler.postDelayed(ticker, REFRESH_INTERVAL_MS)
        // 恒为 false：生命周期逻辑不得自动重注册（"显式开始"是合同要求）
        if (state.startSampling) startSampling()
    }

    /** QA-04：debuggable 下写诊断快照；非 debuggable、被节流或写入失败时静默跳过。 */
    private fun writeDiagSnapshot(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val path = session.dumpFile(now, force)
        if (path != null) {
            Log.i(DiagJson.LOG_TAG, DiagJson.snapshotFile(path, force, now))
        }
    }

    private fun refresh() {
        val frame = session.frame()
        stateView.text = getString(
            R.string.probe_state_line,
            getString(if (session.isRunning()) R.string.probe_state_running else R.string.probe_state_stopped),
            frame.sessionId.take(8),
            frame.registrationSeq,
            yesNo(frame.activityResumed)
        )
        controlView.text = getString(
            R.string.probe_control_line,
            frame.controlType?.let { getString(R.string.probe_control_fmt, ProbeTypes.displayName(it), it) }
                ?: getString(R.string.probe_control_none)
        )
        shakeView.text = getString(
            R.string.probe_shake_line,
            session.totalShakes(),
            session.significantMotionEvents()
        )
        for (row in frame.rows) {
            val view = rowViews[row.sensorType] ?: continue
            view.text = getString(
                R.string.probe_row_format,
                ProbeTypes.displayName(row.sensorType),
                row.sensorType,
                yesNo(row.exists),
                row.registerResult?.let { yesNo(it) } ?: getString(R.string.probe_not_attempted),
                row.callbacks,
                row.callbacksSinceRegister,
                row.gapSinceLastMs?.let { getString(R.string.probe_last_ms_fmt, it) } ?: getString(R.string.probe_no_callback),
                row.samplingState.name,
                row.shakes
            )
        }
        if (selfCheckEnabled?.isChecked == true) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSelfCheckAtMs >= SELF_CHECK_INTERVAL_MS) runSelfCheck()
        }
    }

    private fun runSelfCheck() {
        lastSelfCheckAtMs = SystemClock.elapsedRealtime()
        val result = selfCheck?.read()
        selfCheckView.text = if (result == null) {
            getString(R.string.probe_self_check_off)
        } else {
            getString(
                R.string.probe_self_check_fmt,
                result.status,
                result.revision?.toString() ?: "-",
                result.hookEnabled?.toString() ?: "-",
                result.blockedSensorTypes.joinToString(separator = ","),
                result.errorCode ?: "-"
            )
        }
    }

    private fun yesNo(value: Boolean): String =
        getString(if (value) R.string.probe_yes else R.string.probe_no)

    private fun headline(resId: Int): TextView = TextView(this).apply {
        setText(resId)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    private fun body(resId: Int): TextView = TextView(this).apply {
        setText(resId)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_INTERVAL_MS: Long = 1000L
        const val SELF_CHECK_INTERVAL_MS: Long = 1000L

        val SCENARIO_ORDER: List<String> = AdFixtureActivity.SCENARIOS
    }
}
