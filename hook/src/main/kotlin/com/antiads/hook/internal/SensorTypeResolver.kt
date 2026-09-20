package com.antiads.hook.internal

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 由 SensorEventQueue 的 handle 解析真实 Sensor.type（合同第 7 节）。
 *
 * 只使用反射读取**已验证形状**的进程内对象，不假设 handle == sensor type，也不依赖尚未初始化的
 * SensorEvent.sensor。候选路径按顺序尝试：
 *   1. 队列实例自身的 handle→Sensor 映射字段（如 mSensors）；
 *   2. 队列 → SensorManager（如 mManager）→ 其 handle→Sensor 映射字段（如 mHandleToSensor）。
 *
 * 任一步失败、映射值缺失或 type 越界（不在 1..40）都返回 [Resolution.Unresolved]，
 * 调用方必须放行并记录受限计数。映射路径与每个 Sensor 类的类型读取器会被缓存，避免逐回调扫描字段。
 */
class SensorTypeResolver(
    private val queueMapFields: List<String> = listOf("mSensors"),
    private val managerFields: List<String> = listOf("mManager"),
    private val managerMapFields: List<String> = listOf("mHandleToSensor")
) {

    sealed interface Resolution {
        data class Resolved(val sensorType: Int, val path: String) : Resolution
        data object Unresolved : Resolution
    }

    /** 映射路径访问器（只定位 handle→Sensor 映射，不含 Sensor 类型读取）。 */
    private class MapAccessor(
        val path: String,
        val directMapField: Field?,
        val managerField: Field?,
        val managerMapField: Field?,
        val mapGetMethod: Method
    )

    /** 单个 Sensor 实现类的类型读取器。 */
    private class TypeReader(val typeGetMethod: Method?, val typeField: Field?)

    private val cachedMapAccessor = AtomicReference<MapAccessor?>(null)
    private val typeReaders = ConcurrentHashMap<Class<*>, TypeReader>()

    /** 结构预检：是否存在任一可用的候选字段路径（安装前形状验证，避免"装了但永远放行"）。 */
    fun hasCandidatePath(clazz: Class<*>): Boolean {
        for (name in queueMapFields) {
            val mapField = findField(clazz, name) ?: continue
            if (hasIntGet(mapField.type)) return true
        }
        for (name in managerFields) {
            val managerField = findField(clazz, name) ?: continue
            for (mapName in managerMapFields) {
                val mapField = findField(managerField.type, mapName) ?: continue
                if (hasIntGet(mapField.type)) return true
            }
        }
        return false
    }

    fun resolve(queue: Any, handle: Int): Resolution {
        cachedMapAccessor.get()?.let { accessor ->
            readType(accessor, queue, handle)?.let { return Resolution.Resolved(it, accessor.path) }
            cachedMapAccessor.compareAndSet(accessor, null)
        }
        for (accessor in buildMapAccessors(queue.javaClass)) {
            val type = readType(accessor, queue, handle) ?: continue
            cachedMapAccessor.set(accessor)
            return Resolution.Resolved(type, accessor.path)
        }
        return Resolution.Unresolved
    }

    private fun readType(accessor: MapAccessor, queue: Any, handle: Int): Int? {
        val map = try {
            when {
                accessor.directMapField != null -> accessor.directMapField.get(queue)
                accessor.managerField != null && accessor.managerMapField != null -> {
                    val manager = accessor.managerField.get(queue) ?: return null
                    accessor.managerMapField.get(manager)
                }
                else -> null
            }
        } catch (t: Throwable) {
            null
        } ?: return null

        val sensor = try {
            accessor.mapGetMethod.invoke(map, handle)
        } catch (t: Throwable) {
            null
        } ?: return null

        val type = readSensorType(sensor) ?: return null
        return if (type in MIN_SENSOR_TYPE..MAX_SENSOR_TYPE) type else null
    }

    private fun readSensorType(sensor: Any): Int? {
        val reader = typeReaders.computeIfAbsent(sensor.javaClass) { clazz ->
            val method = clazz.methods.firstOrNull { it.name == "getType" && it.parameterTypes.isEmpty() }
            val field = findField(clazz, "mType")
            TypeReader(method, field)
        }
        reader.typeGetMethod?.let { method ->
            val value = try {
                method.invoke(sensor)
            } catch (t: Throwable) {
                null
            }
            if (value is Int) return value
        }
        reader.typeField?.let { field ->
            val value = try {
                field.get(sensor)
            } catch (t: Throwable) {
                null
            }
            if (value is Int) return value
        }
        return null
    }

    private fun buildMapAccessors(queueClass: Class<*>): List<MapAccessor> {
        val accessors = ArrayList<MapAccessor>(2)
        for (name in queueMapFields) {
            val mapField = findField(queueClass, name) ?: continue
            buildMapAccessor("queue.$name", mapField, null, null)?.let { accessors.add(it) }
        }
        for (managerName in managerFields) {
            val managerField = findField(queueClass, managerName) ?: continue
            for (mapName in managerMapFields) {
                val mapField = findField(managerField.type, mapName) ?: continue
                buildMapAccessor("$managerName.$mapName", null, managerField, mapField)?.let { accessors.add(it) }
            }
        }
        return accessors
    }

    private fun buildMapAccessor(
        path: String,
        directMapField: Field?,
        managerField: Field?,
        managerMapField: Field?
    ): MapAccessor? {
        val mapClass = directMapField?.type ?: managerMapField?.type ?: return null
        val getMethod = mapClass.methods.firstOrNull { method ->
            method.name == "get" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Integer.TYPE
        } ?: return null
        return MapAccessor(path, directMapField, managerField, managerMapField, getMethod)
    }

    private fun hasIntGet(mapClass: Class<*>): Boolean = mapClass.methods.any { method ->
        method.name == "get" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == Integer.TYPE
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = try {
                current.getDeclaredField(name)
            } catch (t: Throwable) {
                null
            }
            if (field != null) {
                runCatching { field.isAccessible = true }
                return field
            }
            current = current.superclass
        }
        return null
    }

    private companion object {
        const val MIN_SENSOR_TYPE: Int = 1
        const val MAX_SENSOR_TYPE: Int = 40
    }
}
