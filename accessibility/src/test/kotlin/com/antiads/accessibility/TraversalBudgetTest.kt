package com.antiads.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 遍历预算：节点数/深度/耗时任一达到上限即不完整，本次不点击。 */
class TraversalBudgetTest {

    @Test
    fun nodeLimitMarksTraversalIncomplete() {
        val budget = TraversalBudget(maxNodes = 3, maxDepth = 20, budgetMs = 8)
        assertTrue(budget.beginNode(0, 0))
        assertTrue(budget.beginNode(1, 0))
        assertTrue(budget.beginNode(1, 0))
        assertFalse(budget.beginNode(1, 0))
        assertFalse(budget.complete)
        assertEquals(3, budget.visitedNodeCount)
    }

    @Test
    fun depthLimitIsInclusive() {
        val budget = TraversalBudget(maxNodes = 300, maxDepth = 2, budgetMs = 8)
        assertTrue(budget.beginNode(2, 0))
        assertFalse(budget.beginNode(3, 0))
        assertFalse(budget.complete)
    }

    @Test
    fun timeBudgetReachingTheLimitMarksIncomplete() {
        val budget = TraversalBudget(maxNodes = 300, maxDepth = 20, budgetMs = 8)
        assertTrue(budget.beginNode(0, 7))
        assertFalse(budget.beginNode(1, 8))
        assertFalse(budget.complete)
    }

    @Test
    fun defaultsMatchContractNumbers() {
        val budget = TraversalBudget()
        var visited = 0
        while (budget.beginNode(0, 0)) visited++
        assertEquals(SkipLimits.MAX_NODES, visited)
        assertFalse(budget.complete)
    }
}
