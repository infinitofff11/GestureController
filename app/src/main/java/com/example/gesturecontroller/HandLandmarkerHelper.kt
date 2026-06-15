package com.example.gesturecontroller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener
) {
    private var handLandmarker: HandLandmarker? = null
    private var lastTimestamp = 0L

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: HandLandmarkerResult, _: MPImage ->
                    listener.onResults(result)
                }
                .setErrorListener { e: RuntimeException ->
                    Log.e(TAG, "MediaPipe error: ${e.message}")
                    listener.onError(e.message ?: "Unknown error")
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaPipe failed to initialize", e)
            listener.onError("模型加载失败: ${e.message}")
        } catch (e: RuntimeException) {
            Log.e(TAG, "MediaPipe failed to load model", e)
            listener.onError("模型加载失败: ${e.message}")
        }
    }

    /**
     * 处理实时流帧
     * 注意：此方法负责关闭 ImageProxy
     */
    fun detectLiveStream(imageProxy: ImageProxy) {
        if (handLandmarker == null) {
            imageProxy.close()
            return
        }

        try {
            // 确保时间戳严格递增（MediaPipe 要求）
            var frameTime = SystemClock.uptimeMillis()
            if (frameTime <= lastTimestamp) {
                frameTime = lastTimestamp + 1
            }
            lastTimestamp = frameTime

            // 从 ImageProxy 复制像素到 Bitmap
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            // 关闭 ImageProxy（只关闭一次）
            imageProxy.close()

            // 旋转和翻转图像（前置摄像头需要镜像）
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )
            // 回收中间 Bitmap
            if (rotatedBitmap !== bitmapBuffer) {
                bitmapBuffer.recycle()
            }

            // 转换为 MPImage 并执行异步检测
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            handLandmarker?.detectAsync(mpImage, frameTime)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
            // 确保 ImageProxy 被关闭
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    fun isReady(): Boolean = handLandmarker != null

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }

    interface LandmarkerListener {
        fun onResults(result: HandLandmarkerResult)
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "HandLandmarkerHelper"
        private const val MODEL_PATH = "models/hand_landmarker.task"
    }
}
