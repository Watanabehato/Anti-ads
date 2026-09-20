package com.antiads.accessibility

import com.antiads.core.rules.AdWindowSnapshot
import com.antiads.core.rules.SkipCandidate

/**
 * core 候选 → 执行前复核请求 的唯一映射（纯函数，无 Android 依赖）。
 *
 * 提取目的（t11 集成）：服务与集成测试必须走**同一份**映射逻辑，
 * 避免“测试里临时拼一份请求、线上是另一份”造成的假验证。
 *
 * 返回 null 表示候选 nodeId 在当前快照中不存在（快照与候选不一致）——调用方必须放弃本次动作。
 */
internal object SkipRequestBuilder {

    fun build(
        nowElapsedMs: Long,
        packageName: String,
        windowId: Int,
        policy: PolicyGuardInput,
        window: WindowGuardInput,
        snapshot: AdWindowSnapshot,
        candidate: SkipCandidate,
        refreshed: NodeFields?
    ): ClickRequest? {
        val snapshotNode = snapshot.nodes.firstOrNull { it.nodeId == candidate.nodeId } ?: return null
        return ClickRequest(
            nowElapsedMs = nowElapsedMs,
            packageName = packageName,
            windowId = windowId,
            policy = policy,
            window = window,
            candidate = CandidateGuardInput(
                candidateRuleId = candidate.ruleId,
                candidateReason = candidate.reason,
                snapshotNode = NodeFields(
                    text = snapshotNode.text,
                    description = snapshotNode.contentDescription,
                    clickable = snapshotNode.clickable,
                    enabled = snapshotNode.enabled,
                    visible = snapshotNode.visible,
                    editable = snapshotNode.editable,
                    password = snapshotNode.password
                ),
                refreshed = refreshed
            )
        )
    }
}
