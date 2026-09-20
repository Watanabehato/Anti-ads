package com.antiads.hook.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** handle → Sensor.type 解析（合同第 7 节）：不假设 handle == type，失败必须 Unresolved。 */
class SensorTypeResolverTest {

    private val resolver = SensorTypeResolver()

    @Test
    fun resolvesViaQueueOwnMap() {
        val queue = FakeQueueWithOwnMap(
            FakeHandleMap(mapOf(11 to FakeSensor(4), 12 to FakeSensor(9)))
        )
        val resolution = resolver.resolve(queue, 12)
        assertEquals(SensorTypeResolver.Resolution.Resolved(9, "queue.mSensors"), resolution)
    }

    @Test
    fun resolvesViaManagerMap() {
        val queue = FakeQueueWithManager(
            FakeSensorManager(FakeHandleMap(mapOf(3 to FakeSensor(1))))
        )
        val resolution = resolver.resolve(queue, 3)
        assertEquals(SensorTypeResolver.Resolution.Resolved(1, "mManager.mHandleToSensor"), resolution)
    }

    @Test
    fun handleIsNotAssumedToBeSensorType() {
        // handle=7 对应 type=10：解析结果必须是 10 而不是 7
        val queue = FakeQueueWithOwnMap(FakeHandleMap(mapOf(7 to FakeSensor(10))))
        assertEquals(
            SensorTypeResolver.Resolution.Resolved(10, "queue.mSensors"),
            resolver.resolve(queue, 7)
        )
    }

    @Test
    fun unknownHandleIsUnresolved() {
        val queue = FakeQueueWithOwnMap(FakeHandleMap(mapOf(1 to FakeSensor(1))))
        assertEquals(SensorTypeResolver.Resolution.Unresolved, resolver.resolve(queue, 99))
    }

    @Test
    fun outOfRangeSensorTypeIsUnresolved() {
        val queue = FakeQueueWithOwnMap(FakeHandleMap(mapOf(1 to FakeSensor(999))))
        assertEquals(SensorTypeResolver.Resolution.Unresolved, resolver.resolve(queue, 1))
    }

    @Test
    fun missingPathIsUnresolvedAndDetectedByShapeCheck() {
        val queue = FakeQueueWithoutHandlePath("nope")
        assertEquals(SensorTypeResolver.Resolution.Unresolved, resolver.resolve(queue, 1))
        assertFalse(resolver.hasCandidatePath(FakeQueueWithoutHandlePath::class.java))
    }

    @Test
    fun shapeCheckAcceptsBothSupportedPaths() {
        assertTrue(resolver.hasCandidatePath(FakeQueueWithOwnMap::class.java))
        assertTrue(resolver.hasCandidatePath(FakeQueueWithManager::class.java))
    }
}
