package com.antiads.app

import android.app.Activity
import android.widget.Toast
import com.antiads.app.config.AppConfigRepository
import com.antiads.core.config.ConfigWriteResult
import com.antiads.core.config.ProtectionConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 管理界面基类（MainActivity / PackageListActivity / PackageDetailActivity 共用）。
 *
 * - 全部界面共享同一个 AppConfigRepository 惰性单例（与 Application、Provider 一致）；
 * - 所有落盘写入在后台单线程执行，主线程只渲染快照（docs/contracts.md 第 3 节）；
 * - 冲突（Rejected(CONFLICT)）时读新快照并提示重新操作，绝不自动覆盖另一方的更改。
 */
abstract class ManagedActivity : Activity() {

    protected val repository: AppConfigRepository by lazy { AppConfigRepository.get(applicationContext) }

    private val writeExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var destroyed = false

    protected fun submitEdit(
        mutate: (ProtectionConfig) -> ProtectionConfig,
        onResult: (ConfigWriteResult) -> Unit
    ) {
        val current = repository.snapshot()
        val updated = try {
            mutate(current)
        } catch (e: RuntimeException) {
            onResult(ConfigWriteResult.Rejected(AppConfigRepository.REASON_INVALID))
            return
        }
        writeExecutor.execute {
            val result = repository.write(updated, current.revision)
            runOnUiThread {
                if (!destroyed && !isFinishing) {
                    onResult(result)
                }
            }
        }
    }

    /** 统一的保存结果处理：成功提示版本号；被拒绝时回读最新快照并说明原因。 */
    protected fun handleWriteResult(result: ConfigWriteResult, render: (ProtectionConfig) -> Unit) {
        when (result) {
            is ConfigWriteResult.Saved -> {
                render(result.config)
                toast(getString(R.string.toast_saved_revision, result.config.revision))
            }
            is ConfigWriteResult.Rejected -> {
                render(repository.snapshot())
                toast(reasonText(result.reason))
            }
        }
    }

    protected fun reasonText(reason: String): String = when (reason) {
        AppConfigRepository.REASON_CONFLICT -> getString(R.string.toast_rejected_conflict)
        AppConfigRepository.REASON_INVALID -> getString(R.string.toast_rejected_invalid)
        AppConfigRepository.REASON_IO_ERROR -> getString(R.string.toast_rejected_io)
        AppConfigRepository.REASON_REVISION_EXHAUSTED -> getString(R.string.toast_rejected_exhausted)
        else -> getString(R.string.toast_rejected_unknown, reason)
    }

    protected fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        destroyed = true
        writeExecutor.shutdown()
        super.onDestroy()
    }
}
