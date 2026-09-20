package com.antiads.core.config

/**
 * 配置校验（纯函数，冻结签名见 docs/contracts.md 第 2 节）。
 *
 * 返回的 errors 为稳定英文码，不含用户输入内容（不把包名、文本写入错误串，避免泄漏到日志）。
 */
object ConfigValidator {

    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    const val ERROR_UNSUPPORTED_SCHEMA: String = "UNSUPPORTED_SCHEMA_VERSION"
    const val ERROR_REVISION_OUT_OF_RANGE: String = "REVISION_OUT_OF_RANGE"
    const val ERROR_TOO_MANY_PACKAGES: String = "TOO_MANY_PACKAGES"
    const val ERROR_INVALID_PACKAGE_KEY: String = "INVALID_PACKAGE_KEY"
    const val ERROR_REJECTED_PACKAGE_KEY: String = "REJECTED_PACKAGE_KEY"
    const val ERROR_INVALID_SENSOR_TYPES: String = "INVALID_SENSOR_TYPES"
    const val ERROR_INVALID_RULE_IDS: String = "INVALID_RULE_IDS"

    /** 校验语法：≥2 个点分标识段、每段以字母开头、长度上限、拒绝空白/通配符/斜杠。 */
    fun isValidPackageName(packageName: String): Boolean {
        if (packageName.isEmpty() || packageName.length > ConfigConstants.MAX_PACKAGE_NAME_LENGTH) return false
        if (packageName.isBlank()) return false
        return PACKAGE_NAME_REGEX.matches(packageName)
    }

    fun validate(config: ProtectionConfig): ValidationResult {
        val errors = mutableListOf<String>()

        if (config.schemaVersion != ConfigConstants.SCHEMA_VERSION_V1) {
            errors += ERROR_UNSUPPORTED_SCHEMA
        }
        if (config.revision < 0 || config.revision > ConfigConstants.MAX_REVISION) {
            errors += ERROR_REVISION_OUT_OF_RANGE
        }
        if (config.packages.size > ConfigConstants.MAX_PACKAGES) {
            errors += ERROR_TOO_MANY_PACKAGES
        }

        for ((key, pkg) in config.packages) {
            if (key in ConfigConstants.REJECTED_PACKAGE_KEYS) {
                errors += ERROR_REJECTED_PACKAGE_KEY
                continue
            }
            if (!isValidPackageName(key)) {
                errors += ERROR_INVALID_PACKAGE_KEY
                continue
            }
            if (!ConfigConstants.ALLOWED_SENSOR_TYPES.containsAll(pkg.blockedSensorTypes)) {
                errors += ERROR_INVALID_SENSOR_TYPES
            }
            if (!ConfigConstants.ALLOWED_RULE_IDS.containsAll(pkg.ruleIds)) {
                errors += ERROR_INVALID_RULE_IDS
            }
        }

        return ValidationResult(errors.distinct().sorted())
    }
}
