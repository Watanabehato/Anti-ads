package com.antiads.probe.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对照类型必须"设备可用且未被选入拦截集合"，否则显式无对照。 */
class ProbeTypesTest {

    @Test
    fun prefersMagneticFieldWhenAvailableAndNotSelected() {
        val chosen = ProbeTypes.chooseControlType(
            available = setOf(1, 2, 4, 9, 10, 11),
            excluded = setOf(1, 4, 9, 10, 11)
        )
        assertEquals(2, chosen)
    }

    @Test
    fun fallsBackToSmallestAvailableUnselectedType() {
        // PREFERRED_CONTROL_TYPES 顺序为 2,6,13,12,5,8,3；13 与 12 都可用时取 13。
        val chosen = ProbeTypes.chooseControlType(
            available = setOf(1, 4, 9, 12, 13),
            excluded = setOf(1, 4, 9, 10, 11)
        )
        assertEquals(13, chosen)
    }

    @Test
    fun returnsNullWhenNoUsableControlExists() {
        assertNull(ProbeTypes.chooseControlType(available = setOf(1, 4), excluded = setOf(1, 4)))
        assertNull(ProbeTypes.chooseControlType(available = emptySet(), excluded = emptySet()))
    }

    @Test
    fun selectionIsCappedAtFiveTypes() {
        assertEquals(5, ProbeTypes.DEFAULT_SELECTED.size)
        assertTrue(ProbeTypes.DEFAULT_SELECTED.containsAll(listOf(1, 4, 9, 10, 11)))
        assertEquals(5, ProbeTypes.MAX_SELECTED)
    }

    @Test
    fun displayNamesCoverDegradedType() {
        assertEquals("方向(已废弃)", ProbeTypes.displayName(3))
        assertEquals("类型40", ProbeTypes.displayName(40))
    }
}
