package com.antiads.probe.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-03 回归：离开/返回页面的按钮、采样状态与周期刷新必须一致。
 *
 * 实测缺陷：onPause 停止采样并移除 ticker 后没有同步按钮，返回时提示可以重新开始，
 * 但"开始采样"仍禁用、"停止"仍可用，且周期刷新没有重建。
 */
class ProbeUiStateRulesTest {

    @Test
    fun afterPauseStopsSamplingStartIsEnabledAndStopIsDisabled() {
        // onPause 已停止采样、页面不再前台
        val state = ProbeUiStateRules.of(samplingRunning = false, activityResumed = false)
        assertTrue("停止后必须能显式重新开始", state.startEnabled)
        assertFalse("没有采样在跑时停止必须禁用", state.stopEnabled)
        assertFalse("不在前台不该继续周期刷新", state.periodicRefresh)
        assertFalse("生命周期规则不得自动开始采样", state.startSampling)
    }

    @Test
    fun returningToPageReEnablesExplicitStartAndPeriodicRefresh() {
        // 用户从 HOME 返回：页面在前台，但采样仍处于停止状态
        val state = ProbeUiStateRules.of(samplingRunning = false, activityResumed = true)
        assertTrue(state.startEnabled)
        assertFalse(state.stopEnabled)
        assertTrue("返回后必须重建周期刷新（状态行/自查/诊断快照依赖它）", state.periodicRefresh)
        assertFalse("返回页面不得自动重注册，必须由用户点开始", state.startSampling)
    }

    @Test
    fun runningSamplingKeepsStartDisabled() {
        val state = ProbeUiStateRules.of(samplingRunning = true, activityResumed = true)
        assertFalse(state.startEnabled)
        assertTrue(state.stopEnabled)
        assertTrue(state.periodicRefresh)
    }

    @Test
    fun autoStartIsNeverRequestedInAnyCombination() {
        for (running in listOf(false, true)) {
            for (resumed in listOf(false, true)) {
                assertFalse(
                    "任何组合下都不得自动开始采样: running=" + running + " resumed=" + resumed,
                    ProbeUiStateRules.of(running, resumed).startSampling
                )
            }
        }
    }
}
