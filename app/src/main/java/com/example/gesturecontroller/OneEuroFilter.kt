package com.example.gesturecontroller

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * 一欧元滤波器 (1€ Filter)
 * 自适应低通滤波器：静止时强平滑消除抖动，快速移动时低延迟跟手
 *
 * 参考: Casiez et al., "1€ Filter: A Simple Speed-based Low-pass Filter for
 * Noisy Input in Interactive Systems", CHI 2012
 */
class OneEuroFilter(
    private var minCutoff: Float = 1.2f,  // 静止时截止频率，越低越平滑
    private var beta: Float = 0.007f,       // 速度系数，越高对快速移动响应越好
    private var dCutoff: Float = 1.0f       // 导数平滑截止频率
) {
    private var lastTime: Long = 0
    private var lastValue: Float = 0f
    private var lastDerivative: Float = 0f
    private var initialized = false

    fun filter(value: Float, timestamp: Long): Float {
        if (!initialized) {
            lastValue = value
            lastDerivative = 0f
            lastTime = timestamp
            initialized = true
            return value
        }

        val dt = (timestamp - lastTime).coerceAtLeast(1) / 1000.0f
        lastTime = timestamp

        // 估计导数
        val derivative = (value - lastValue) / dt

        // 平滑导数
        val alphaD = smoothingFactor(dt, dCutoff)
        val smoothedDerivative = alphaD * derivative + (1 - alphaD) * lastDerivative

        // 动态截止频率：速度越快截止频率越高
        val cutoff = minCutoff + beta * abs(smoothedDerivative)

        // 平滑值
        val alpha = smoothingFactor(dt, cutoff)
        val filteredValue = alpha * value + (1 - alpha) * lastValue

        lastValue = filteredValue
        lastDerivative = smoothedDerivative

        return filteredValue
    }

    private fun smoothingFactor(dt: Float, cutoff: Float): Float {
        val r = 2f * PI.toFloat() * cutoff * dt
        return r / (r + 1f)
    }

    fun reset() {
        initialized = false
        lastValue = 0f
        lastDerivative = 0f
        lastTime = 0
    }
}
