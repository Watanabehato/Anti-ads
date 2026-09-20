package com.antiads.hook.internal

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Java 传感器分发目标点分析（合同第 7 节）。
 *
 * 只定位受支持的实例方法
 * `android.hardware.SystemSensorManager$SensorEventQueue.dispatchSensorEvent(int, float[], int, long)`，
 * 并做类/方法形状验证：名称、参数列表、非静态、以及存在可用的 handle→Sensor 路径。
 * 形状不符一律返回 [Analysis.Rejected]，调用方记录 UNSUPPORTED 而不是假装已生效。
 *
 * 这里不依赖 Xposed API，便于在纯 JVM 单测里用等价形状的假类驱动。
 */
internal object SensorDispatchTarget {

    const val CLASS_NAME: String = "android.hardware.SystemSensorManager\$SensorEventQueue"
    const val METHOD_NAME: String = "dispatchSensorEvent"

    /** handle 参数下标（其余为 float[] values、int size、long timestamp）。 */
    const val HANDLE_ARG_INDEX: Int = 0

    private val EXPECTED_PARAMETER_TYPES: Array<Class<*>> = arrayOf(
        Integer.TYPE,
        FloatArray::class.java,
        Integer.TYPE,
        java.lang.Long.TYPE
    )

    sealed interface Analysis {
        data class Ok(val method: Method) : Analysis
        data class Rejected(val reason: String) : Analysis
    }

    fun analyze(clazz: Class<*>, resolver: SensorTypeResolver): Analysis {
        val method = clazz.declaredMethods.firstOrNull { candidate ->
            candidate.name == METHOD_NAME && candidate.parameterTypes.contentEquals(EXPECTED_PARAMETER_TYPES)
        } ?: return Analysis.Rejected("METHOD_SHAPE_MISMATCH")

        if (Modifier.isStatic(method.modifiers)) return Analysis.Rejected("METHOD_NOT_INSTANCE")
        if (Modifier.isAbstract(method.modifiers)) return Analysis.Rejected("METHOD_ABSTRACT")
        if (!resolver.hasCandidatePath(clazz)) return Analysis.Rejected("NO_SENSOR_HANDLE_PATH")

        runCatching { method.isAccessible = true }
        return Analysis.Ok(method)
    }
}
