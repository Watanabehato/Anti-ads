package com.antiads.probe.diag

/**
 * 诊断页按钮与周期刷新状态（纯逻辑，可在 JVM 单测中精确断言）。
 *
 * 背景（QA-03 实测）：离开页面时 onPause 停止采样并移除 ticker，但按钮状态没有同步，
 * 返回后出现“提示可重新开始、开始按钮却禁用、停止按钮仍可用”，且周期刷新未重建。
 *
 * 规则：
 * - `startEnabled = !samplingRunning`：**任何**停止路径（显式停止 / onPause 自动注销）之后都必须能显式重新开始；
 * - `stopEnabled = samplingRunning`：没有采样在跑时“停止”必须禁用；
 * - `periodicRefresh = activityResumed`：返回页面后必须重建周期刷新（状态行、自查与诊断快照节流都依赖它）；
 * - `startSampling` **恒为 false**：生命周期规则永不请求自动开始，采样只能由用户点击“开始采样”触发，
 *   不允许用自动重注册绕过“显式开始”的合同要求。
 */
internal data class ProbeUiState(
    val startEnabled: Boolean,
    val stopEnabled: Boolean,
    val periodicRefresh: Boolean,
    val startSampling: Boolean
)

internal object ProbeUiStateRules {

    fun of(samplingRunning: Boolean, activityResumed: Boolean): ProbeUiState = ProbeUiState(
        startEnabled = !samplingRunning,
        stopEnabled = samplingRunning,
        periodicRefresh = activityResumed,
        startSampling = false
    )
}
