package com.example.gesturecontroller

import com.example.gesturecontroller.model.GestureType
import com.example.gesturecontroller.service.GestureAccessibilityService

class GestureExecutor {

    fun execute(gesture: GestureType, screenWidth: Int, screenHeight: Int) {
        val service = GestureAccessibilityService.instance ?: return

        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f

        when (gesture) {
            GestureType.SWIPE_UP -> {
                service.performSwipe(centerX, screenHeight * 0.8f, centerX, screenHeight * 0.2f, 300L)
            }
            GestureType.SWIPE_DOWN -> {
                service.performSwipe(centerX, screenHeight * 0.2f, centerX, screenHeight * 0.8f, 300L)
            }
            GestureType.SWIPE_LEFT -> {
                service.performGlobalBack()
            }
            GestureType.SWIPE_RIGHT -> {
                service.performHome()
            }
            GestureType.TAP -> {
                service.performClick(centerX, centerY)
            }
            GestureType.NONE -> {}
        }
    }
}
