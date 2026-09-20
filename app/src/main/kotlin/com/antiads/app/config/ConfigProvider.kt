package com.antiads.app.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import com.antiads.core.protocol.ConfigProtocol
import java.io.FileNotFoundException

/**
 * 受控跨进程配置服务（docs/contracts.md 第 6 节，exported 但只读）。
 *
 * - 只提供 GET_POLICY 与 REPORT_HOOK 两个 call 方法；没有 get_all_config/write_config 等远程写接口，
 *   第三方无法修改用户配置（宿主自身的写入走本地 Repository，不经过本 Provider）；
 * - 每次 call 入口先捕获 Binder.getCallingUid()/getCallingPid()，验证完成前不清除调用身份，
 *   本实现全程不使用 clearCallingIdentity，也不信任 arg/payload 自报值；
 * - query/getType 返回 null，insert/update/delete/bulkInsert/openFile/openAssetFile 一律拒绝；
 * - GET_POLICY 只返回已通过鉴权包的最小策略；REPORT_HOOK 只接收进程自报诊断，不写配置、不改授权。
 */
class ConfigProvider : ContentProvider() {

    private var router: ProviderCallRouter? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val repository = AppConfigRepository.get(ctx)
        router = ProviderCallRouter(
            configSnapshot = { repository.snapshot() },
            packageLookup = { uid -> ctx.packageManager.getPackagesForUid(uid) },
            sameUser = { uid -> UserHandle.getUserHandleForUid(uid) == Process.myUserHandle() },
            // 公开 API 无法查询“他人 UID”是否为 isolated（UserHandle.isIsolated 非公开，
            // Process.isIsolatedUid 为隐藏接口）。isolated 进程没有稳定包归属，
            // 因此由 getPackagesForUid 空集合走 ISOLATED_OR_UNKNOWN_UID（合同第 6 节第 3 步）。
            isolatedUid = { uid -> uid == Process.myUid() && Process.isIsolated() },
            applicationUid = { uid -> Process.isApplicationUid(uid) },
            nowMs = { SystemClock.elapsedRealtime() },
            reportSink = { report, receivedAt -> RuntimeReportStore.put(report, receivedAt) }
        )
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val payload = readPayload(extras)
        val activeRouter = router ?: run {
            onCreate()
            router
        } ?: return errorBundle(ConfigProtocol.ERROR_INTERNAL_ERROR)
        val response = activeRouter.handle(
            ProviderRequest(uid = uid, pid = pid, method = method, argPackage = arg, payloadJson = payload)
        )
        return response.toBundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = denyEntry(ProviderEntryPoint.QUERY)

    override fun getType(uri: Uri): String? = denyEntry(ProviderEntryPoint.GET_TYPE)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = denyEntry(ProviderEntryPoint.INSERT)

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = denyEntry(ProviderEntryPoint.UPDATE) ?: 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        denyEntry(ProviderEntryPoint.DELETE) ?: 0

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int =
        denyEntry(ProviderEntryPoint.BULK_INSERT) ?: 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        denyEntry(ProviderEntryPoint.OPEN_FILE)
        throw FileNotFoundException("Anti-ads 配置 Provider 不提供文件访问")
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        denyEntry(ProviderEntryPoint.OPEN_ASSET_FILE)
        throw FileNotFoundException("Anti-ads 配置 Provider 不提供文件访问")
    }

    /** 拒绝策略来自可单测的 ProviderEntryPolicy；这里不返回任何数据。 */
    private fun denyEntry(entryPoint: ProviderEntryPoint): Nothing? =
        when (ProviderEntryPolicy.decide(entryPoint)) {
            ProviderEntryDecision.RETURN_NULL -> null
            ProviderEntryDecision.THROW_UNSUPPORTED ->
                throw UnsupportedOperationException("只读 Provider：$entryPoint 被拒绝")
        }

    private fun readPayload(extras: Bundle?): String? = try {
        extras?.getString(ConfigProtocol.KEY_PAYLOAD)
    } catch (e: RuntimeException) {
        null
    }

    private fun ProviderResponse.toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putBoolean(ConfigProtocol.KEY_OK, ok)
        if (ok) {
            payload?.let { bundle.putString(ConfigProtocol.KEY_PAYLOAD, it) }
        } else {
            bundle.putString(ConfigProtocol.KEY_ERROR, errorCode ?: ConfigProtocol.ERROR_INTERNAL_ERROR)
        }
        return bundle
    }

    private fun errorBundle(errorCode: String): Bundle {
        val bundle = Bundle()
        bundle.putBoolean(ConfigProtocol.KEY_OK, false)
        bundle.putString(ConfigProtocol.KEY_ERROR, errorCode)
        return bundle
    }
}
