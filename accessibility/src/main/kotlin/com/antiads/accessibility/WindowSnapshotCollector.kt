package com.antiads.accessibility

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.antiads.core.rules.AdWindowSnapshot
import com.antiads.core.rules.Bounds
import com.antiads.core.rules.UiNode

/** 一次受限遍历的产物：core 快照 + 短时 nodeId→节点映射（仅本次处理内有效）。 */
internal class CollectedWindow(
    val snapshot: AdWindowSnapshot,
    val nodesById: Map<Int, AccessibilityNodeInfo>,
    val hasEditableOrPasswordNode: Boolean
) {
    /** 释放本次遍历取得的节点引用（旧版本适当 recycle；API33+ recycle 已无副作用）。 */
    fun releaseAll() {
        for (node in nodesById.values) recycleQuietly(node)
    }

    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        @Suppress("DEPRECATION")
        try {
            node.recycle()
        } catch (_: Throwable) {
            // 已回收或框架持有引用时忽略
        }
    }
}

/**
 * Android 节点适配：把当前窗口的节点树转换成 core 的 [AdWindowSnapshot]。
 *
 * 约束（contracts 第 5 节）：
 * - 单次最多 [SkipLimits.MAX_NODES] 个节点、深度 [SkipLimits.MAX_DEPTH]、耗时
 *   [SkipLimits.TRAVERSAL_BUDGET_MS] 毫秒；达到任一上限令 traversalComplete=false；
 * - 文本快照每字段上限 [SkipLimits.MAX_FIELD_CHARS] 字符，不保存界面全文；
 * - 不访问父节点、不点击、不做匹配；只做字段搬运。
 */
internal object WindowSnapshotCollector {

    fun collect(
        root: AccessibilityNodeInfo,
        packageName: String,
        windowId: Int,
        foregroundSinceElapsedMs: Long,
        capturedAtElapsedMs: Long,
        screenWidthPx: Int,
        screenHeightPx: Int,
        isApplicationWindow: Boolean,
        keyguardLocked: Boolean,
        sensitivePackage: Boolean
    ): CollectedWindow {
        val budget = TraversalBudget()
        val startedAt = SystemClock.uptimeMillis()
        val nodes = ArrayList<UiNode>()
        val nodesById = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        var hasEditableOrPassword = false

        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (!budget.beginNode(depth, SystemClock.uptimeMillis() - startedAt)) break

            val text = NodeText.limit(node.text?.toString() ?: "")
            val description = NodeText.limit(node.contentDescription?.toString() ?: "")
            val editable = node.isEditable
            val password = node.isPassword
            if (editable || password) hasEditableOrPassword = true

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val nodeId = nodes.size
            nodes.add(
                UiNode(
                    nodeId = nodeId,
                    text = text,
                    contentDescription = description,
                    viewId = node.viewIdResourceName,
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                    visible = node.isVisibleToUser,
                    editable = editable,
                    password = password,
                    bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
                )
            )
            nodesById[nodeId] = node

            val childDepth = depth + 1
            val childCount = try {
                node.childCount
            } catch (_: Throwable) {
                0
            }
            for (index in 0 until childCount) {
                val child = try {
                    node.getChild(index)
                } catch (_: Throwable) {
                    null
                } ?: continue
                stack.addLast(child to childDepth)
            }
        }

        // 预算耗尽时队列里仍有已获取但未访问的引用：立即释放，避免泄漏。
        while (stack.isNotEmpty()) {
            val (node, _) = stack.removeLast()
            @Suppress("DEPRECATION")
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
            } catch (_: Throwable) {
                // 忽略
            }
        }

        val snapshot = AdWindowSnapshot(
            packageName = packageName,
            windowId = windowId,
            capturedAtElapsedMs = capturedAtElapsedMs,
            foregroundSinceElapsedMs = foregroundSinceElapsedMs,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            isApplicationWindow = isApplicationWindow,
            keyguardLocked = keyguardLocked,
            sensitivePackage = sensitivePackage,
            traversalComplete = budget.complete,
            nodes = nodes
        )
        return CollectedWindow(snapshot, nodesById, hasEditableOrPassword)
    }
}
