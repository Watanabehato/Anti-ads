package com.antiads.probe

import android.app.Activity
import android.graphics.Point
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

/**
 * 广告样例 Activity（contracts 第 8 节）。只产生本地测试内容，无外部写配置、无敏感动作、
 * 不伪装成真实商业广告验证；页面自身不注册传感器。
 *
 * Intent String extra `scenario`：ad_positive / no_ad_label / non_clickable / bottom_button /
 * editable_window，未知值回落 no_ad_label。
 *
 * 坐标约束（与 core 规则一致）：正例的"跳过 5 秒"中心 X ≥ 屏宽 65%、中心 Y ≤ 屏高 25%，
 * 面积为屏幕面积的 ≤12%，且"广告"上下文来自另一个独立可见节点。
 */
class AdFixtureActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var adContextView: TextView
    private lateinit var skipView: View
    private var editableView: EditText? = null

    private var scenario: String = SCENARIO_NO_AD_LABEL
    private var insetTop: Int = 0
    private var insetBottom: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var positioned: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scenario = normalize(intent?.getStringExtra(EXTRA_SCENARIO))

        root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { _, insets ->
            insetTop = insets.systemWindowInsetTop
            insetBottom = insets.systemWindowInsetBottom
            positionViews()
            insets
        }

        adContextView = TextView(this).apply {
            text = getString(R.string.fixture_ad_context)
            textSize = 16f
            visibility = View.VISIBLE
        }
        statusView = TextView(this).apply {
            text = getString(R.string.fixture_status_idle)
            textSize = 16f
        }
        skipView = buildSkipView()
        root.addView(adContextView)
        root.addView(statusView)
        root.addView(skipView)

        val title = TextView(this).apply {
            text = getString(R.string.fixture_scenario_title_fmt, scenario, getString(R.string.fixture_local_only))
            textSize = 13f
        }
        root.addView(title)
        title.layoutParams = frameParams(left = 16, top = 16, width = ViewGroup.LayoutParams.WRAP_CONTENT, height = ViewGroup.LayoutParams.WRAP_CONTENT)

        when (scenario) {
            SCENARIO_NO_AD_LABEL -> adContextView.visibility = View.GONE
            SCENARIO_NON_CLICKABLE -> {
                skipView.isClickable = false
                skipView.isFocusable = false
            }

            SCENARIO_BOTTOM_BUTTON -> Unit
            SCENARIO_EDITABLE_WINDOW -> {
                val edit = EditText(this).apply { hint = getString(R.string.fixture_editable_hint) }
                editableView = edit
                root.addView(edit)
            }

            else -> Unit
        }

        setContentView(root)
        root.post { positionViews() }
        Log.i(
            LOG_TAG,
            "fixture_open scenario=" + scenario +
                " package=" + packageName +
                " elapsed_realtime_ms=" + SystemClock.elapsedRealtime()
        )
    }

    override fun onResume() {
        super.onResume()
        positionViews()
        Log.i(LOG_TAG, "fixture_resume scenario=" + scenario + " window=" + root.width + "x" + root.height)
    }

    private fun buildSkipView(): View = when (scenario) {
        SCENARIO_NON_CLICKABLE -> TextView(this).apply {
            text = getString(R.string.fixture_skip_text)
            textSize = 18f
            isClickable = false
            isEnabled = true
        }

        else -> Button(this).apply {
            text = getString(R.string.fixture_skip_text)
            isClickable = true
            isEnabled = true
            setOnClickListener { onSkipClicked(it) }
        }
    }

    private fun onSkipClicked(view: View) {
        statusView.text = getString(R.string.fixture_status_skipped)
        Log.i(
            LOG_TAG,
            "fixture_skip_clicked scenario=" + scenario +
                " view=" + view.javaClass.simpleName +
                " center_x=" + (view.left + view.width / 2) +
                " center_y=" + (view.top + view.height / 2) +
                " elapsed_realtime_ms=" + SystemClock.elapsedRealtime()
        )
    }

    /** 按可用窗口（扣除系统栏）布置，并把实测坐标写入日志供 QA 核对 65%/25% 约束。 */
    private fun positionViews() {
        if (!::root.isInitialized || root.width == 0 || root.height == 0) return
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        screenWidth = if (screenWidth > 0) screenWidth else size.x
        screenHeight = if (screenHeight > 0) screenHeight else size.y

        val usableTop = insetTop + dp(8)
        val usableBottom = screenHeight - insetBottom - dp(8)
        val width = dp(SKIP_WIDTH_DP)
        val height = dp(SKIP_HEIGHT_DP)

        val skipTop = if (scenario == SCENARIO_BOTTOM_BUTTON) {
            (usableBottom - height).coerceAtLeast(usableTop)
        } else {
            usableTop
        }
        val skipLeft = (screenWidth - width - dp(8)).coerceAtLeast(0)
        skipView.layoutParams = frameParams(skipLeft, skipTop, width, height)

        adContextView.layoutParams = frameParams(
            left = (skipLeft - dp(AD_CONTEXT_WIDTH_DP)).coerceAtLeast(0),
            top = skipTop,
            width = dp(AD_CONTEXT_WIDTH_DP),
            height = height
        )

        statusView.layoutParams = frameParams(
            left = dp(16),
            top = (usableBottom - height).coerceAtLeast(usableTop),
            width = ViewGroup.LayoutParams.WRAP_CONTENT,
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        )

        editableView?.let { edit ->
            edit.layoutParams = frameParams(dp(16), dp(120), screenWidth - dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        if (!positioned) {
            positioned = true
            Log.i(LOG_TAG, buildPositionLine(skipLeft, skipTop, width, height))
        }
    }

    private fun buildPositionLine(left: Int, top: Int, width: Int, height: Int): String {
        val centerX = left + width / 2
        val centerY = top + height / 2
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        val area = width.toLong() * height.toLong()
        return "fixture_layout scenario=" + scenario +
            " screen=" + screenWidth + "x" + screenHeight +
            " skip_center_x=" + centerX + " skip_center_y=" + centerY +
            " center_x_ratio=" + ratio(centerX, screenWidth) +
            " center_y_ratio=" + ratio(centerY, screenHeight) +
            " area_ratio=" + if (screenArea == 0L) "0" else String.format(Locale.ROOT, "%.4f", area.toDouble() / screenArea.toDouble()) +
            " insets_top=" + insetTop + " insets_bottom=" + insetBottom +
            " ad_context_visible=" + (adContextView.visibility == View.VISIBLE) +
            " clickable=" + skipView.isClickable
    }

    private fun ratio(value: Int, total: Int): String =
        if (total == 0) "0" else String.format(Locale.ROOT, "%.4f", value.toDouble() / total.toDouble())

    private fun frameParams(left: Int, top: Int, width: Int, height: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
            this.leftMargin = left
            this.topMargin = top
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun normalize(value: String?): String {
        val candidate = value?.trim().orEmpty()
        return when (candidate) {
            SCENARIO_AD_POSITIVE,
            SCENARIO_NO_AD_LABEL,
            SCENARIO_NON_CLICKABLE,
            SCENARIO_BOTTOM_BUTTON,
            SCENARIO_EDITABLE_WINDOW -> candidate

            else -> SCENARIO_NO_AD_LABEL
        }
    }

    companion object {
        const val EXTRA_SCENARIO: String = "scenario"
        const val LOG_TAG: String = "AntiAdsProbe.Fixture"

        const val SCENARIO_AD_POSITIVE: String = "ad_positive"
        const val SCENARIO_NO_AD_LABEL: String = "no_ad_label"
        const val SCENARIO_NON_CLICKABLE: String = "non_clickable"
        const val SCENARIO_BOTTOM_BUTTON: String = "bottom_button"
        const val SCENARIO_EDITABLE_WINDOW: String = "editable_window"

        val SCENARIOS: List<String> = listOf(
            SCENARIO_AD_POSITIVE,
            SCENARIO_NO_AD_LABEL,
            SCENARIO_NON_CLICKABLE,
            SCENARIO_BOTTOM_BUTTON,
            SCENARIO_EDITABLE_WINDOW
        )

        private const val SKIP_WIDTH_DP = 132
        private const val SKIP_HEIGHT_DP = 48
        private const val AD_CONTEXT_WIDTH_DP = 72
    }
}
