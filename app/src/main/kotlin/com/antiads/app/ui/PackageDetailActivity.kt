package com.antiads.app.ui

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import com.antiads.app.ManagedActivity
import com.antiads.app.R
import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.ConfigWriteResult
import com.antiads.core.config.PackageConfig
import com.antiads.core.config.ProtectionConfig

/**
 * 每应用配置页：免 Root 跳过开关、增强模式开关、传感器类型选择、影响提示、移除配置。
 *
 * 保存成功只表示持久化成功；页面额外提示“总开关/全局开关未开启时该配置不生效”，
 * 不显示任何“已拦截”结论。
 */
class PackageDetailActivity : ManagedActivity() {

    private lateinit var packageName: String
    private lateinit var accessibilitySwitch: CheckBox
    private lateinit var hookSwitch: CheckBox
    private lateinit var inactiveNoteView: TextView
    private val sensorSwitches = LinkedHashMap<Int, CheckBox>()
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (packageName.isEmpty()) {
            finish()
            return
        }
        val (root, column) = UiViews.scrollColumn(this)
        column.addView(UiViews.title(this, R.string.detail_title))
        column.addView(UiViews.bodyText(this, getString(R.string.detail_package, packageName)))
        column.addView(UiViews.bodyText(this, labelLine()))

        column.addView(UiViews.section(this, R.string.detail_section_modes))
        accessibilitySwitch = UiViews.checkBox(this, R.string.detail_accessibility_switch)
        hookSwitch = UiViews.checkBox(this, R.string.detail_hook_switch)
        column.addView(accessibilitySwitch)
        column.addView(hookSwitch)
        accessibilitySwitch.setOnCheckedChangeListener { _, checked ->
            updatePackage { it.copy(accessibilityEnabled = checked) }
        }
        hookSwitch.setOnCheckedChangeListener { _, checked ->
            updatePackage { it.copy(hookEnabled = checked) }
        }

        column.addView(UiViews.section(this, R.string.detail_section_sensors))
        for (sensorType in SENSOR_TYPES) {
            val view = UiViews.checkBox(this, sensorTitleRes(sensorType))
            sensorSwitches[sensorType] = view
            column.addView(view)
            view.setOnCheckedChangeListener { _, _ -> onSensorToggled() }
        }
        column.addView(UiViews.body(this, R.string.detail_sensor_note))
        column.addView(UiViews.body(this, R.string.detail_rule_note))

        inactiveNoteView = UiViews.body(this, R.string.detail_inactive_note)
        inactiveNoteView.visibility = View.GONE
        column.addView(inactiveNoteView)

        column.addView(UiViews.body(this, R.string.detail_effect_note))

        column.addView(
            UiViews.button(this, R.string.detail_remove_button).apply { setOnClickListener { removePackage() } }
        )

        setContentView(root)
        UiInsets.applyToRoot(this, root)
        bindFromConfig(repository.snapshot())
    }

    private fun labelLine(): String {
        val label = try {
            packageManager.getApplicationInfo(packageName, 0).let { packageManager.getApplicationLabel(it) }.toString()
        } catch (e: Exception) {
            null
        }
        return if (label.isNullOrBlank() || label == packageName) {
            getString(R.string.detail_label_unavailable)
        } else {
            getString(R.string.detail_label, label)
        }
    }

    private fun onSensorToggled() {
        if (binding) return
        val selected = sensorSwitches.filterValues { it.isChecked }.keys.toSet()
        updatePackage { it.copy(blockedSensorTypes = selected) }
    }

    private fun updatePackage(transform: (PackageConfig) -> PackageConfig) {
        if (binding) return
        submitEdit({ config ->
            val existing = config.packages[packageName] ?: PackageConfig()
            config.copy(packages = config.packages + (packageName to transform(existing)))
        }) { result ->
            handleWriteResult(result) { bindFromConfig(it) }
        }
    }

    private fun removePackage() {
        submitEdit({ config -> config.copy(packages = config.packages - packageName) }) { result ->
            when (result) {
                is ConfigWriteResult.Saved -> {
                    toast(getString(R.string.detail_removed, packageName))
                    finish()
                }
                is ConfigWriteResult.Rejected -> {
                    bindFromConfig(repository.snapshot())
                    toast(reasonText(result.reason))
                }
            }
        }
    }

    private fun bindFromConfig(config: ProtectionConfig) {
        val pkgConfig = config.packages[packageName]
        binding = true
        accessibilitySwitch.isChecked = pkgConfig?.accessibilityEnabled == true
        hookSwitch.isChecked = pkgConfig?.hookEnabled == true
        val blocked = pkgConfig?.blockedSensorTypes ?: ConfigConstants.DEFAULT_BLOCKED_SENSOR_TYPES
        for ((sensorType, view) in sensorSwitches) {
            view.isChecked = sensorType in blocked
        }
        val accessibilityActive = config.masterEnabled && config.accessibilityEnabled
        val hookActive = config.masterEnabled && config.hookEnabled
        val inactive = (accessibilitySwitch.isChecked && !accessibilityActive) ||
            (hookSwitch.isChecked && !hookActive)
        binding = false
        inactiveNoteView.visibility = if (inactive) View.VISIBLE else View.GONE
    }

    private fun sensorTitleRes(sensorType: Int): Int = when (sensorType) {
        SENSOR_ACCELEROMETER -> R.string.sensor_accelerometer
        SENSOR_GYROSCOPE -> R.string.sensor_gyroscope
        SENSOR_GRAVITY -> R.string.sensor_gravity
        SENSOR_LINEAR_ACCELERATION -> R.string.sensor_linear_acceleration
        SENSOR_ROTATION_VECTOR -> R.string.sensor_rotation_vector
        else -> R.string.value_unknown
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "com.antiads.app.extra.PACKAGE_NAME"

        private const val SENSOR_ACCELEROMETER = 1
        private const val SENSOR_GYROSCOPE = 4
        private const val SENSOR_GRAVITY = 9
        private const val SENSOR_LINEAR_ACCELERATION = 10
        private const val SENSOR_ROTATION_VECTOR = 11

        /** 与 core 允许集合一致，只列出可分别关闭的 5 种类型。 */
        private val SENSOR_TYPES = listOf(
            SENSOR_ACCELEROMETER,
            SENSOR_GYROSCOPE,
            SENSOR_GRAVITY,
            SENSOR_LINEAR_ACCELERATION,
            SENSOR_ROTATION_VECTOR
        )
    }
}
