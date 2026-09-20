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

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
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
        refresh()
    }

    override fun onPause() {
        session.activityResumed = false
        if (session.isRunning()) {
            session.stop()
            hintView.text = getString(R.string.probe_paused_hint)
        }
        handler.removeCallbacks(ticker)
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
        hintView.text = getString(if (started) R.string.probe_running_hint else R.string.probe_start_failed_hint)
        startButton.isEnabled = !started
        stopButton.isEnabled = started
        refresh()
        handler.removeCallbacks(ticker)
        if (started) handler.postDelayed(ticker, REFRESH_INTERVAL_MS)
    }

    private fun stopSampling() {
        session.stop()
        handler.removeCallbacks(ticker)
        hintView.text = getString(R.string.probe_stopped_hint)
        startButton.isEnabled = true
        stopButton.isEnabled = false
        refresh()
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
