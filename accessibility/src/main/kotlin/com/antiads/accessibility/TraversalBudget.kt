package com.antiads.accessibility

/**
 * 受限遍历预算（纯逻辑）。
 *
 * 达到任一上限（节点数、深度、耗时）即标记 complete=false，本次不点击。
 * 边界语义：深度 <= MAX_DEPTH 允许；节点数达到 MAX_NODES 后不再访问新节点；耗时达到
 * TRAVERSAL_BUDGET_MS 即视为达到上限（合同用词为“达到任一上限”）。
 */
internal class TraversalBudget(
    private val maxNodes: Int = SkipLimits.MAX_NODES,
    private val maxDepth: Int = SkipLimits.MAX_DEPTH,
    private val budgetMs: Long = SkipLimits.TRAVERSAL_BUDGET_MS
) {
    private var visited: Int = 0

    var complete: Boolean = true
        private set

    val visitedNodeCount: Int get() = visited

    /** 是否可以访问该节点；返回 false 时本次遍历已判定为不完整。 */
    fun beginNode(depth: Int, elapsedMs: Long): Boolean {
        if (depth > maxDepth || visited >= maxNodes || elapsedMs >= budgetMs) {
            complete = false
            return false
        }
        visited++
        return true
    }
}
