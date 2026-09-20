package com.antiads.hook.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标点形状验证（合同第 7 节）：名称、参数列表、实例方法、存在 handle→Sensor 路径，
 * 任一不满足都必须 UNSUPPORTED，而不是"装了但永远放行"。
 */
class SensorDispatchTargetTest {

    private val resolver = SensorTypeResolver()

    @Test
    fun acceptsExpectedShape() {
        val analysis = SensorDispatchTarget.analyze(FakeQueueWithOwnMap::class.java, resolver)
        assertTrue(analysis is SensorDispatchTarget.Analysis.Ok)
        val method = (analysis as SensorDispatchTarget.Analysis.Ok).method
        assertEquals("dispatchSensorEvent", method.name)
        assertEquals(4, method.parameterTypes.size)
        assertEquals(java.lang.Integer.TYPE, method.parameterTypes[0])
        assertEquals(FloatArray::class.java, method.parameterTypes[1])
        assertEquals(java.lang.Integer.TYPE, method.parameterTypes[2])
        assertEquals(java.lang.Long.TYPE, method.parameterTypes[3])
    }

    @Test
    fun rejectsDifferentParameterList() {
        val analysis = SensorDispatchTarget.analyze(FakeQueueWrongMethodShape::class.java, resolver)
        assertEquals(
            SensorDispatchTarget.Analysis.Rejected("METHOD_SHAPE_MISMATCH"),
            analysis
        )
    }

    @Test
    fun rejectsQueueWithoutTheExpectedMethod() {
        val analysis = SensorDispatchTarget.analyze(FakeQueueWithoutHandlePath::class.java, resolver)
        assertEquals(
            SensorDispatchTarget.Analysis.Rejected("METHOD_SHAPE_MISMATCH"),
            analysis
        )
    }

    @Test
    fun rejectsQueueWithoutHandleToSensorPath() {
        val analysis = SensorDispatchTarget.analyze(FakeQueueCorrectShapeWithoutHandlePath::class.java, resolver)
        assertEquals(
            SensorDispatchTarget.Analysis.Rejected("NO_SENSOR_HANDLE_PATH"),
            analysis
        )
    }

    @Test
    fun rejectsStaticMethod() {
        val analysis = SensorDispatchTarget.analyze(FakeQueueStaticShape::class.java, resolver)
        assertEquals(
            SensorDispatchTarget.Analysis.Rejected("METHOD_NOT_INSTANCE"),
            analysis
        )
    }

    @Test
    fun rejectsMissingTargetMethod() {
        val analysis = SensorDispatchTarget.analyze(String::class.java, resolver)
        assertEquals(
            SensorDispatchTarget.Analysis.Rejected("METHOD_SHAPE_MISMATCH"),
            analysis
        )
    }
}
