package com.antiads.hook.internal.policy

import com.antiads.core.config.ConfigCodec
import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.PackagePolicy

/**
 * 策略 JSON 解码与校验（合同第 6、7 节）：回复必须同时满足
 * 授权（ok=true）、结构合法、包名与当前 loadPackage 包名一致、schemaVersion=1、长度 ≤4096 字节。
 *
 * 任何一条不满足都返回 null；调用边界（[HookPolicyClientCore]）会立即清空缓存并放行。
 */
object PolicyJson {

    fun decodePolicy(payloadJson: String, expectedPackageName: String): PackagePolicy? {
        if (payloadJson.toByteArray(Charsets.UTF_8).size > ConfigConstants.MAX_POLICY_JSON_BYTES) return null
        val policy = try {
            ConfigCodec.decodePolicy(payloadJson)
        } catch (t: Throwable) {
            return null
        }
        if (policy.schemaVersion != ConfigConstants.SCHEMA_VERSION_V1) return null
        if (policy.packageName != expectedPackageName) return null
        return policy
    }
}
