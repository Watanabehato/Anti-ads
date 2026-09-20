package com.antiads.hook.internal

/**
 * 与 Android 运行时等价形状的假类（仅测试用）：
 * 队列持有 handle→Sensor 映射（mSensors），或经 mManager 间接持有（mHandleToSensor）。
 * 字段名与 AOSP 形状一致，用于驱动反射解析逻辑。
 */
internal class FakeSensor(private val sensorType: Int) {
    fun getType(): Int = sensorType
}

internal class FakeHandleMap(private val entries: Map<Int, Any>) {
    fun get(handle: Int): Any? = entries[handle]
}

internal class FakeSensorManager(val mHandleToSensor: FakeHandleMap)

internal class FakeQueueWithOwnMap(val mSensors: FakeHandleMap) {
    @Suppress("UNUSED_PARAMETER")
    fun dispatchSensorEvent(handle: Int, values: FloatArray, size: Int, timestamp: Long) = Unit
}

internal class FakeQueueWithManager(val mManager: FakeSensorManager) {
    @Suppress("UNUSED_PARAMETER")
    fun dispatchSensorEvent(handle: Int, values: FloatArray, size: Int, timestamp: Long) = Unit
}

internal class FakeQueueWithoutHandlePath(val unrelated: String)

internal class FakeQueueCorrectShapeWithoutHandlePath {
    @Suppress("UNUSED_PARAMETER")
    fun dispatchSensorEvent(handle: Int, values: FloatArray, size: Int, timestamp: Long) = Unit
}

internal class FakeQueueWrongMethodShape(val mSensors: FakeHandleMap) {
    @Suppress("UNUSED_PARAMETER")
    fun dispatchSensorEvent(handle: Int, values: FloatArray, size: Int) = Unit
}

internal class FakeQueueStaticShape {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun dispatchSensorEvent(handle: Int, values: FloatArray, size: Int, timestamp: Long) = Unit
    }
}
