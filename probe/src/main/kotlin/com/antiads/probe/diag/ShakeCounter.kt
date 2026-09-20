package com.antiads.probe.diag

import kotlin.math.abs

/**
 * 摇动诊断计数（纯逻辑，仅用于 QA 观察"运动事件/摇动"是否随开关变化）。
 *
 * 这不是产品功能，也不构成"广告拦截成功"的证据：它只统计加速度幅值超过阈值后的方向翻转次数。
 * 阈值与窗口都可由诊断界面调整，默认值仅用于观察同等条件下的相对变化。
 */
class ShakeCounter(
    private val thresholdG: Float = 1.2f,
    private val directionChangesForShake: Int = 2,
    private val windowMs: Long = 600L
) {

    var significantMotionEvents: Long = 0L
        private set

    var shakes: Long = 0L
        private set

    private var previousSign: Int = 0
    private var directionChanges: Int = 0
    private var windowStartedAtMs: Long = 0L

    fun onMagnitude(magnitudeG: Float, nowElapsedMs: Long) {
        if (windowStartedAtMs == 0L) windowStartedAtMs = nowElapsedMs
        if (nowElapsedMs - windowStartedAtMs > windowMs) {
            directionChanges = 0
            windowStartedAtMs = nowElapsedMs
        }
        val delta = magnitudeG - thresholdG
        val sign = when {
            delta > 0.15f -> 1
            delta < -0.15f -> -1
            else -> 0
        }
        if (sign == 0) return
        significantMotionEvents += 1
        if (previousSign != 0 && sign != previousSign) {
            directionChanges += 1
            if (directionChanges >= directionChangesForShake) {
                shakes += 1
                directionChanges = 0
                windowStartedAtMs = nowElapsedMs
            }
        }
        previousSign = sign
    }

    fun reset() {
        significantMotionEvents = 0L
        shakes = 0L
        previousSign = 0
        directionChanges = 0
        windowStartedAtMs = 0L
    }
}
