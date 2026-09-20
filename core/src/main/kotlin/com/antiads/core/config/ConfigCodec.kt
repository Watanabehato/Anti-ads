package com.antiads.core.config

import com.antiads.core.status.HookProcessReport
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * 配置/策略/报告的 JSON 编解码（冻结签名见 docs/contracts.md 第 2、7 节）。
 *
 * 规则：
 * - encodeDefaults=true、ignoreUnknownKeys=true；
 * - 解码先检查 JSON 对象中的 schemaVersion 键：缺失或非 1 一律拒绝，不用默认值静默解释成 v1；
 * - 未知枚举、错误字段类型、越界数据、无效包名抛 IllegalArgumentException，调用边界必须捕获并放行；
 * - 持久化与 wire 均使用 UTF-8；config JSON 上限 256 KiB。
 */
object ConfigCodec {

    private val format = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = false
        allowStructuredMapKeys = false
    }

    private const val ERROR_UNSUPPORTED_SCHEMA = "UNSUPPORTED_SCHEMA_VERSION"
    private const val ERROR_INVALID_JSON = "INVALID_JSON"
    private const val ERROR_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    private const val ERROR_INVALID_POLICY = "INVALID_POLICY"
    private const val ERROR_INVALID_REPORT = "INVALID_REPORT"

    // ---------------- ProtectionConfig ----------------

    fun encodeConfig(value: ProtectionConfig): String {
        requireValid(ConfigValidator.validate(value))
        return format.encodeToString(ProtectionConfig.serializer(), value)
    }

    fun decodeConfig(json: String): ProtectionConfig {
        val obj = parseObjectObject(json)
        requireSchemaV1(obj)
        val value = decodeOrThrow { format.decodeFromJsonElement(ProtectionConfig.serializer(), obj) }
        requireValid(ConfigValidator.validate(value))
        return value
    }

    // ---------------- PackagePolicy ----------------

    fun encodePolicy(value: PackagePolicy): String {
        requirePolicyValid(value)
        return format.encodeToString(PackagePolicy.serializer(), value)
    }

    fun decodePolicy(json: String): PackagePolicy {
        val obj = parseObjectObject(json)
        requireSchemaV1(obj)
        val value = decodeOrThrow { format.decodeFromJsonElement(PackagePolicy.serializer(), obj) }
        requirePolicyValid(value)
        return value
    }

    // ---------------- HookProcessReport ----------------

    fun encodeHookReport(value: HookProcessReport): String {
        requireReportValid(value)
        return format.encodeToString(HookProcessReport.serializer(), value)
    }

    fun decodeHookReport(json: String): HookProcessReport {
        val obj = parseObjectObject(json)
        requireSchemaV1(obj)
        val value = decodeOrThrow { format.decodeFromJsonElement(HookProcessReport.serializer(), obj) }
        requireReportValid(value)
        return value
    }

    // ---------------- 内部校验 ----------------

    private fun parseObjectObject(json: String): JsonObject {
        if (json.toByteArray(Charsets.UTF_8).size > ConfigConstants.MAX_CONFIG_JSON_BYTES) {
            throw IllegalArgumentException(ERROR_TOO_LARGE)
        }
        val element = try {
            format.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(ERROR_INVALID_JSON, e)
        }
        return element as? JsonObject ?: throw IllegalArgumentException(ERROR_INVALID_JSON)
    }

    private fun requireSchemaV1(obj: JsonObject) {
        val raw = obj["schemaVersion"] ?: throw IllegalArgumentException(ERROR_UNSUPPORTED_SCHEMA)
        val version = (raw as? JsonPrimitive)?.let { if (it.isString) null else runCatching { it.int }.getOrNull() }
            ?: throw IllegalArgumentException(ERROR_UNSUPPORTED_SCHEMA)
        if (version != ConfigConstants.SCHEMA_VERSION_V1) {
            throw IllegalArgumentException(ERROR_UNSUPPORTED_SCHEMA)
        }
    }

    private inline fun <T> decodeOrThrow(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw IllegalArgumentException(ERROR_INVALID_JSON, e)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException(ERROR_INVALID_JSON, e)
    }

    private fun requireValid(result: ValidationResult) {
        if (!result.isValid) {
            throw IllegalArgumentException(result.errors.joinToString(separator = ","))
        }
    }

    private fun requirePolicyValid(policy: PackagePolicy) {
        if (policy.schemaVersion != ConfigConstants.SCHEMA_VERSION_V1) {
            throw IllegalArgumentException(ERROR_UNSUPPORTED_SCHEMA)
        }
        if (!ConfigValidator.isValidPackageName(policy.packageName)) {
            throw IllegalArgumentException(ERROR_INVALID_POLICY)
        }
        if (policy.revision < 0 || policy.revision > ConfigConstants.MAX_REVISION) {
            throw IllegalArgumentException(ERROR_INVALID_POLICY)
        }
        if (!ConfigConstants.ALLOWED_SENSOR_TYPES.containsAll(policy.blockedSensorTypes)) {
            throw IllegalArgumentException(ERROR_INVALID_POLICY)
        }
    }

    private fun requireReportValid(report: HookProcessReport) {
        if (report.schemaVersion != ConfigConstants.SCHEMA_VERSION_V1) {
            throw IllegalArgumentException(ERROR_UNSUPPORTED_SCHEMA)
        }
        if (!ConfigValidator.isValidPackageName(report.packageName)) {
            throw IllegalArgumentException(ERROR_INVALID_REPORT)
        }
        if (report.processName.isEmpty() || report.processName.length > ConfigConstants.MAX_PROCESS_NAME_LENGTH) {
            throw IllegalArgumentException(ERROR_INVALID_REPORT)
        }
        if (!isUuid(report.processToken)) {
            throw IllegalArgumentException(ERROR_INVALID_REPORT)
        }
        if (report.pid <= 0) {
            throw IllegalArgumentException(ERROR_INVALID_REPORT)
        }
        if (report.apiLevel <= 0) {
            throw IllegalArgumentException(ERROR_INVALID_REPORT)
        }
        if (report.policyRevision != null &&
            (report.policyRevision < 0 || report.policyRevision > ConfigConstants.MAX_REVISION)
        ) {
            throw IllegalArgumentException(ERROR_INVALID_REPORT)
        }
        if (report.observedCallbacks < 0 || report.droppedCallbacks < 0) {
            throw IllegalArgumentException(ERROR_INVALID_REPORT)
        }
        if (report.droppedCallbacks > report.observedCallbacks) {
            throw IllegalArgumentException(ERROR_INVALID_REPORT)
        }
    }

    private fun isUuid(token: String): Boolean {
        if (token.length != ConfigConstants.PROCESS_TOKEN_LENGTH) return false
        return runCatching { UUID.fromString(token) }.isSuccess
    }
}
