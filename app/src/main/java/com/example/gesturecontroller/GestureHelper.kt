package com.example.gesturecontroller

import com.example.gesturecontroller.model.GestureType
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 手势判定引擎 — 食指指尖滑动检测
 *
 * 防抖体系（单指）：
 * 1. 一欧元滤波器平滑食指指尖坐标，消除模型高频噪声
 * 2. 死区过滤：累积位移未超阈值时不触发，过滤微颤
 * 3. 主轴优先+方向置信度：|dy|/|dx|或|dx|/|dy|需>1.5才触发，斜向运动不触发
 * 4. 恢复期静止门控：手势触发后冷却结束进入恢复期，要求手指连续静止才允许新跟踪
 * 5. 反向抑制窗口：触发后一段时间内抑制反向手势，过滤回弹误触
 *
 * 手势映射：
 * - 食指向上滑 → 屏幕下滑（SWIPE_DOWN）
 * - 食指向下滑 → 屏幕上滑（SWIPE_UP）
 * - 食指向左滑 → 返回（SWIPE_LEFT）
 * - 食指向右滑 → 回到主屏（SWIPE_RIGHT）
 * - 握拳 → 点击（TAP）
 */
class GestureHelper {

    // ===== 食指伸直判定 =====
    private val INDEX_EXTENDED_MIN_DIST = 0.10f

    // ===== 握拳判定 =====
    private val FIST_TIP_TO_PIP_THRESHOLD = 0.06f

    // ===== 死区（归一化坐标） =====
    private val DEAD_ZONE = 0.004f

    // ===== 触发阈值（归一化坐标） =====
    private val TRIGGER_THRESHOLD = 0.05f

    // ===== 主轴置信度比 =====
    // 主轴位移需是次轴的1.5倍以上才触发，否则视为斜向运动不触发
    private val AXIS_RATIO_THRESHOLD = 1.5f

    // ===== 最大累积位移 =====
    // 位移累积超过此值仍未触发（斜向运动），重置跟踪避免无限累积
    private val MAX_ACCUM_DIST = 0.12f

    // ===== 冷却（帧） =====
    private var cooldownFrames = 0
    private val COOLDOWN_FRAMES = 8

    // ===== 恢复期：静止门控 =====
    // 冷却结束后进入恢复期，要求手指连续静止才允许新跟踪
    private var isRecovering = false
    private var stillFrames = 0
    private val STILL_FRAMES = 3
    private val STILL_VELOCITY = 0.006f  // 单帧位移低于此值视为静止（覆盖MediaPipe静止抖动）
    private var lastFx = 0f
    private var lastFy = 0f
    // 恢复期最大超时帧数，防止MediaPipe静止抖动导致静止门控永远无法通过
    private var recoverTimeout = 0
    private val RECOVER_TIMEOUT_FRAMES = 30  // 约1秒后强制退出恢复期

    // ===== 反向抑制窗口 =====
    // 触发后一段时间内抑制反向手势
    private var lastTriggeredGesture: GestureType = GestureType.NONE
    private var reverseSuppressFrames = 0
    private val REVERSE_SUPPRESS_FRAMES = 15

    // ===== 握拳确认 =====
    private var fistConfirmCount = 0
    private val FIST_CONFIRM_FRAMES = 3

    // ===== 滑动跟踪状态 =====
    private var isTracking = false
    private var startX = 0f
    private var startY = 0f

    // ===== 1€ 滤波器 =====
    private val filterX = OneEuroFilter()
    private val filterY = OneEuroFilter()

    fun detectGesture(landmarks: List<NormalizedLandmark>, timestamp: Long): GestureType {

        // ===== 冷却期：滤波但不触发 =====
        if (cooldownFrames > 0) {
            cooldownFrames--
            if (isIndexExtended(landmarks)) {
                lastFx = filterX.filter(landmarks[8].x(), timestamp)
                lastFy = filterY.filter(landmarks[8].y(), timestamp)
            }
            return GestureType.NONE
        }

        // ===== 恢复期：静止门控，手指未稳定前不开始新跟踪 =====
        if (isRecovering) {
            if (reverseSuppressFrames > 0) reverseSuppressFrames--
            recoverTimeout++

            // 超时强制退出恢复期，防止MediaPipe静止抖动导致静止门控永远无法通过
            if (recoverTimeout >= RECOVER_TIMEOUT_FRAMES) {
                isRecovering = false
                stillFrames = 0
                recoverTimeout = 0
                // 超时退出时不立即返回，继续向下执行正常检测
            } else {
                if (isIndexExtended(landmarks)) {
                    val fx = filterX.filter(landmarks[8].x(), timestamp)
                    val fy = filterY.filter(landmarks[8].y(), timestamp)

                    val vx = fx - lastFx
                    val vy = fy - lastFy
                    val velocity = sqrt(vx * vx + vy * vy)

                    lastFx = fx
                    lastFy = fy

                    if (velocity < STILL_VELOCITY) {
                        stillFrames++
                        if (stillFrames >= STILL_FRAMES) {
                            // 手指已稳定，退出恢复期，允许新跟踪
                            isRecovering = false
                            stillFrames = 0
                            recoverTimeout = 0
                        }
                    } else {
                        // 仍在移动（如回弹），重置静止计数
                        stillFrames = 0
                    }
                } else {
                    stillFrames = 0
                }
                return GestureType.NONE
            }
        }

        // ===== 正常检测阶段 =====

        // 1. 握拳 → 点击（不受恢复期/反向抑制影响）
        if (isFist(landmarks)) {
            isTracking = false
            return confirmTap()
        }

        // 2. 食指伸直 → 滑动跟踪
        if (isIndexExtended(landmarks)) {
            val fx = filterX.filter(landmarks[8].x(), timestamp)
            val fy = filterY.filter(landmarks[8].y(), timestamp)
            lastFx = fx
            lastFy = fy

            if (!isTracking) {
                startX = fx
                startY = fy
                isTracking = true
            } else {
                val dx = fx - startX
                val dy = fy - startY
                val dist = sqrt(dx * dx + dy * dy)

                // 死区过滤
                if (dist < DEAD_ZONE) {
                    return GestureType.NONE
                }

                // 累积位移超过触发阈值
                if (dist > TRIGGER_THRESHOLD) {
                    // 主轴优先 + 方向置信度判定
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    val maxVal = maxOf(absDx, absDy)
                    val minVal = minOf(absDx, absDy)

                    // 方向置信度不足（斜向运动），不触发
                    if (maxVal < minVal * AXIS_RATIO_THRESHOLD) {
                        // 累积过大仍未明确方向，重置跟踪起点重新判断
                        if (dist > MAX_ACCUM_DIST) {
                            startX = fx
                            startY = fy
                        }
                        return GestureType.NONE
                    }

                    val gesture = if (absDy > absDx) {
                        // 垂直主轴
                        if (dy < 0) GestureType.SWIPE_UP    // 向上滑 → 屏幕下滑
                        else GestureType.SWIPE_DOWN           // 向下滑 → 屏幕上滑
                    } else {
                        // 水平主轴
                        if (dx < 0) GestureType.SWIPE_LEFT    // 向左滑 → 返回
                        else GestureType.SWIPE_RIGHT           // 向右滑 → 主屏
                    }

                    // 反向抑制检查
                    if (reverseSuppressFrames > 0 && isReverseGesture(gesture, lastTriggeredGesture)) {
                        isTracking = false
                        reverseSuppressFrames = 0
                        return GestureType.NONE
                    }

                    // 触发成功，进入冷却+恢复期
                    isTracking = false
                    cooldownFrames = COOLDOWN_FRAMES
                    isRecovering = true
                    stillFrames = 0
                    recoverTimeout = 0
                    reverseSuppressFrames = REVERSE_SUPPRESS_FRAMES
                    lastTriggeredGesture = gesture
                    return gesture
                }
            }
        } else {
            isTracking = false
            fistConfirmCount = 0
        }

        return GestureType.NONE
    }

    private fun confirmTap(): GestureType {
        fistConfirmCount++
        if (fistConfirmCount >= FIST_CONFIRM_FRAMES) {
            fistConfirmCount = 0
            cooldownFrames = COOLDOWN_FRAMES
            // 握拳不设恢复期和反向抑制（静态动作无回弹问题）
            isRecovering = false
            reverseSuppressFrames = 0
            lastTriggeredGesture = GestureType.TAP
            return GestureType.TAP
        }
        return GestureType.NONE
    }

    /**
     * 判断两个手势是否互为反向
     * 上↔下，左↔右
     */
    private fun isReverseGesture(current: GestureType, last: GestureType): Boolean {
        return when (current) {
            GestureType.SWIPE_UP -> last == GestureType.SWIPE_DOWN
            GestureType.SWIPE_DOWN -> last == GestureType.SWIPE_UP
            GestureType.SWIPE_LEFT -> last == GestureType.SWIPE_RIGHT
            GestureType.SWIPE_RIGHT -> last == GestureType.SWIPE_LEFT
            else -> false
        }
    }

    private fun isIndexExtended(landmarks: List<NormalizedLandmark>): Boolean {
        val dx = landmarks[8].x() - landmarks[5].x()
        val dy = landmarks[8].y() - landmarks[5].y()
        return sqrt(dx * dx + dy * dy) > INDEX_EXTENDED_MIN_DIST
    }

    private fun isFist(landmarks: List<NormalizedLandmark>): Boolean {
        val fingerPairs = listOf(
            8 to 6,    // 食指
            12 to 10,  // 中指
            16 to 14,  // 无名指
            20 to 18   // 小指
        )
        return fingerPairs.all { (tip, pip) ->
            val dx = landmarks[tip].x() - landmarks[pip].x()
            val dy = landmarks[tip].y() - landmarks[pip].y()
            sqrt(dx * dx + dy * dy) < FIST_TIP_TO_PIP_THRESHOLD
        }
    }

    fun reset() {
        cooldownFrames = 0
        isRecovering = false
        stillFrames = 0
        recoverTimeout = 0
        reverseSuppressFrames = 0
        lastTriggeredGesture = GestureType.NONE
        isTracking = false
        fistConfirmCount = 0
        filterX.reset()
        filterY.reset()
    }
}
