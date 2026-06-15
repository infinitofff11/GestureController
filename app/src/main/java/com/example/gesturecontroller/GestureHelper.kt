package com.example.gesturecontroller

import com.example.gesturecontroller.model.GestureType
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 手势判定引擎 — 静态手指方向检测
 *
 * 方案：
 * - 食指伸出 + 指向上方 → 下滑（滚动向下）
 * - 食指伸出 + 指向下方 → 上滑（滚动向上）
 * - 食指伸出 + 指向左方 → 返回
 * - 食指伸出 + 指向右方 → 最近任务
 * - 握拳（所有手指弯曲）→ 点击
 *
 * 关键设计：方向判定只看食指是否伸直且有明确方向，
 * 不要求其他手指必须弯曲（自然状态下很难可靠判断弯曲）
 */
class GestureHelper {

    // ===== 食指伸直判定 =====
    // 食指指尖(8)到MCP(5)的距离，大于此值视为伸直
    private val INDEX_EXTENDED_MIN_DIST = 0.10f

    // ===== 握拳判定 =====
    // 所有指尖到PIP的距离都小于此值视为握拳
    private val FIST_TIP_TO_PIP_THRESHOLD = 0.06f

    // ===== 方向判定 =====
    // 食指指尖(8)相对于MCP(5)的位移比例，用于区分上下左右
    private val DIRECTION_MIN_RATIO = 1.2f  // 主方向位移是次方向的1.2倍以上

    // ===== 冷却 =====
    private var cooldownFrames = 0
    private val COOLDOWN_FRAMES = 10

    // ===== 稳定性：连续帧确认 =====
    private var pendingGesture: GestureType = GestureType.NONE
    private var confirmCount = 0
    private val CONFIRM_FRAMES = 3

    fun detectGesture(landmarks: List<NormalizedLandmark>): GestureType {
        if (cooldownFrames > 0) {
            cooldownFrames--
            return GestureType.NONE
        }

        // ===== 1. 握拳 → 点击 =====
        if (isFist(landmarks)) {
            return confirmGesture(GestureType.TAP)
        }

        // ===== 2. 食指伸直 → 方向判定 =====
        // 只看食指是否伸直，不要求其他手指状态
        if (isIndexExtended(landmarks)) {
            val direction = detectFingerDirection(landmarks)
            if (direction != GestureType.NONE) {
                return confirmGesture(direction)
            }
        }

        // 其他状态：重置确认
        pendingGesture = GestureType.NONE
        confirmCount = 0
        return GestureType.NONE
    }

    private fun confirmGesture(gesture: GestureType): GestureType {
        if (gesture == pendingGesture) {
            confirmCount++
            if (confirmCount >= CONFIRM_FRAMES) {
                pendingGesture = GestureType.NONE
                confirmCount = 0
                cooldownFrames = COOLDOWN_FRAMES
                return gesture
            }
        } else {
            pendingGesture = gesture
            confirmCount = 1
        }
        return GestureType.NONE
    }

    /**
     * 食指是否伸直
     * 判断食指指尖(8)到MCP(5)的距离是否足够大
     */
    private fun isIndexExtended(landmarks: List<NormalizedLandmark>): Boolean {
        val dx = landmarks[8].x() - landmarks[5].x()
        val dy = landmarks[8].y() - landmarks[5].y()
        return sqrt(dx * dx + dy * dy) > INDEX_EXTENDED_MIN_DIST
    }

    /**
     * 检测食指指向的方向
     * 通过食指指尖(8)相对于MCP(5)的位移判断
     */
    private fun detectFingerDirection(landmarks: List<NormalizedLandmark>): GestureType {
        val dx = landmarks[8].x() - landmarks[5].x()
        val dy = landmarks[8].y() - landmarks[5].y()

        return when {
            // 指向上方（dy<0，图像y轴向下）→ 系统下滑
            dy < 0 && abs(dy) > abs(dx) * DIRECTION_MIN_RATIO -> GestureType.SWIPE_DOWN
            // 指向下方 → 系统上滑
            dy > 0 && abs(dy) > abs(dx) * DIRECTION_MIN_RATIO -> GestureType.SWIPE_UP
            // 指向左方 → 返回
            dx < 0 && abs(dx) > abs(dy) * DIRECTION_MIN_RATIO -> GestureType.SWIPE_LEFT
            // 指向右方 → 最近任务
            dx > 0 && abs(dx) > abs(dy) * DIRECTION_MIN_RATIO -> GestureType.SWIPE_RIGHT
            else -> GestureType.NONE
        }
    }

    /**
     * 握拳检测：所有四指的指尖到PIP距离都很小
     */
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
        pendingGesture = GestureType.NONE
        confirmCount = 0
    }
}
