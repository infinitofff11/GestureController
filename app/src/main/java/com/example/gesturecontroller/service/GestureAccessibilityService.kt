package com.example.gesturecontroller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GestureA11yService"
        var instance: GestureAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * 执行滑动手势
     */
    fun performSwipe(startX: Float, startY: Float,
                     endX: Float, endY: Float, duration: Long = 300L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Swipe gesture completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.d(TAG, "Swipe gesture cancelled")
            }
        }, null)
    }

    /**
     * 执行点击手势
     */
    fun performClick(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Click gesture completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.d(TAG, "Click gesture cancelled")
            }
        }, null)
    }

    /**
     * 执行返回操作（全局返回键）
     */
    fun performGlobalBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "Global back performed")
    }

    /**
     * 执行最近任务操作
     */
    fun performRecentApps() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        Log.d(TAG, "Recent apps performed")
    }

    /**
     * 回到主屏（Home键）
     */
    fun performHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "Home performed")
    }
}
