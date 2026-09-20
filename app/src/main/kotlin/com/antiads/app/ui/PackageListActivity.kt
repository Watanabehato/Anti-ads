package com.antiads.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.antiads.app.ManagedActivity
import com.antiads.app.R
import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.ConfigValidator
import com.antiads.core.config.ConfigWriteResult
import com.antiads.core.config.PackageConfig

/**
 * 应用选择页：已配置应用 + 可启动应用 + 按包名手工添加。
 *
 * Android 11（API30）+ 包可见性：只依赖 Manifest 的 LAUNCHER intent queries 与 probe 的明确包查询，
 * 不申请 QUERY_ALL_PACKAGES；列表可能不完整时给出中文解释与手工添加入口。
 */
class PackageListActivity : ManagedActivity() {

    private lateinit var configuredAdapter: PackageRowAdapter
    private lateinit var launchableAdapter: PackageRowAdapter
    private lateinit var manualInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiViews.dp(this@PackageListActivity, 16f), UiViews.dp(this@PackageListActivity, 12f), UiViews.dp(this@PackageListActivity, 16f), UiViews.dp(this@PackageListActivity, 12f))
        }

        root.addView(UiViews.title(this, R.string.list_title))
        root.addView(UiViews.body(this, R.string.list_visibility_note))

        root.addView(UiViews.section(this, R.string.list_section_configured))
        configuredAdapter = PackageRowAdapter { row -> openDetail(row.packageName) }
        val configuredList = ListView(this).apply { adapter = configuredAdapter; isTextFilterEnabled = false }
        val configuredEmpty = UiViews.body(this, R.string.list_empty_configured)
        root.addView(configuredList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(configuredEmpty)
        configuredList.emptyView = configuredEmpty

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manualInput = EditText(this).apply {
            hint = getString(R.string.list_add_hint)
            setSingleLine(true)
        }
        addRow.addView(manualInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        addRow.addView(
            UiViews.button(this, R.string.list_add_button).apply { setOnClickListener { addManualPackage() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        )
        root.addView(addRow)

        root.addView(UiViews.section(this, R.string.list_section_launchable))
        launchableAdapter = PackageRowAdapter { row -> openDetail(row.packageName) }
        val launchableList = ListView(this).apply { adapter = launchableAdapter }
        val launchableEmpty = UiViews.body(this, R.string.list_empty_launchable)
        root.addView(launchableList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
        root.addView(launchableEmpty)
        launchableList.emptyView = launchableEmpty

        setContentView(root)
        UiInsets.applyToRoot(this, root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val config = repository.snapshot()
        val configuredRows = config.packages.keys.sorted().map { packageName ->
            val pkgConfig = config.packages[packageName]
            PackageRow(
                packageName = packageName,
                title = labelOf(packageName),
                summary = getString(
                    R.string.row_configured_summary,
                    booleanText(pkgConfig?.accessibilityEnabled == true),
                    booleanText(pkgConfig?.hookEnabled == true)
                )
            )
        }
        configuredAdapter.submit(configuredRows)

        val launchableRows = launchablePackages()
            .filter { it !in config.packages.keys }
            .map { packageName ->
                PackageRow(
                    packageName = packageName,
                    title = labelOf(packageName),
                    summary = getString(R.string.row_not_configured)
                )
            }
        launchableAdapter.submit(launchableRows)
    }

    private fun addManualPackage() {
        val candidate = manualInput.text?.toString()?.trim().orEmpty()
        if (!ConfigValidator.isValidPackageName(candidate)) {
            toast(getString(R.string.list_add_invalid))
            return
        }
        if (candidate in ConfigConstants.REJECTED_PACKAGE_KEYS) {
            toast(getString(R.string.list_add_rejected))
            return
        }
        if (repository.snapshot().packages.containsKey(candidate)) {
            openDetail(candidate)
            return
        }
        // 新建包配置保持两个模式开关关闭（docs/contracts.md 第 2 节），默认传感器集合来自 core 常量
        submitEdit({ config -> config.copy(packages = config.packages + (candidate to PackageConfig())) }) { result ->
            refresh()
            when (result) {
                is ConfigWriteResult.Saved -> {
                    manualInput.setText("")
                    toast(getString(R.string.list_add_success, candidate))
                }
                is ConfigWriteResult.Rejected -> toast(reasonText(result.reason))
            }
        }
    }

    private fun openDetail(packageName: String) {
        startActivity(
            Intent(this, PackageDetailActivity::class.java)
                .putExtra(PackageDetailActivity.EXTRA_PACKAGE_NAME, packageName)
        )
    }

    /** 只查询 LAUNCHER 应用（依赖 Manifest queries），不做全包扫描。 */
    private fun launchablePackages(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            packageManager.queryIntentActivities(intent, 0)
        } catch (e: RuntimeException) {
            return emptyList()
        }
        return resolved.mapNotNull { it.activityInfo?.packageName }
            .filter { it.isNotBlank() && it != packageName }
            .distinct()
            .sorted()
    }

    /** 包可见性不足时读不到应用名：此时只显示包名，不猜测。 */
    private fun labelOf(packageName: String): String {
        val label = try {
            packageManager.getApplicationInfo(packageName, 0).let { packageManager.getApplicationLabel(it) }.toString()
        } catch (e: Exception) {
            null
        }
        return if (label.isNullOrBlank() || label == packageName) {
            packageName
        } else {
            label + getString(R.string.row_label_suffix, packageName)
        }
    }

    private fun booleanText(value: Boolean): String =
        getString(if (value) R.string.value_yes else R.string.value_no)

    private inner class PackageRowAdapter(private val onClick: (PackageRow) -> Unit) : BaseAdapter() {

        private var rows: List<PackageRow> = emptyList()

        fun submit(newRows: List<PackageRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val context = parent.context
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, UiViews.dp(context, 8f), 0, UiViews.dp(context, 8f))
            }
            container.addView(TextView(context).apply { text = row.title; textSize = 15f })
            container.addView(TextView(context).apply { text = row.summary; textSize = 12f })
            container.setOnClickListener { onClick(row) }
            return container
        }
    }

    private data class PackageRow(val packageName: String, val title: String, val summary: String)
}
