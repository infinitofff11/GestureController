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
import java.util.concurrent.ConcurrentLinkedQueue

class HandLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener
) {
    private var handLandmarker: HandLandmarker? = null
    private var lastTimestamp = 0L

    // 复用 bitmapBuffer，避免每帧分配
    private var bitmapBuffer: Bitmap? = null
    private var bufferWidth = 0
    private var bufferHeight = 0

    // 待回收的 Bitmap 队列：MediaPipe 按序处理，回调也按序触发
    // 每个条目记录入队时间戳，超时未回调的自动清理，防止会话重建时丢帧导致队列永久堵塞
    private data class PendingBitmap(val bitmap: Bitmap, val timestamp: Long)
    private val pendingBitmaps = ConcurrentLinkedQueue<PendingBitmap>()

    // 最大待处理帧数，超过则丢弃新帧，防止队列无界增长导致内存泄漏
    private val MAX_PENDING = 3

    // Bitmap 超时时间（毫秒），超过此时间仍未被回调回收则视为 MediaPipe 丢帧，手动清理
    private val BITMAP_TIMEOUT_MS = 1000L

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
                    // 按序回收最早入队的 Bitmap
                    pendingBitmaps.poll()?.bitmap?.recycle()
                    listener.onResults(result)
                }
                .setErrorListener { e: RuntimeException ->
                    pendingBitmaps.poll()?.bitmap?.recycle()
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

        // 超时清理：扫描队首，回收超过 BITMAP_TIMEOUT_MS 仍未被回调的 Bitmap
        // 这能防止 MediaPipe 会话重建时静默丢帧导致队列永久堵塞
        val now = SystemClock.uptimeMillis()
        while (true) {
            val head = pendingBitmaps.peek() ?: break
            if (now - head.timestamp > BITMAP_TIMEOUT_MS) {
                pendingBitmaps.poll()?.bitmap?.recycle()
            } else {
                break
            }
        }

        // 限制待处理帧数，防止队列无界增长导致内存泄漏
        if (pendingBitmaps.size >= MAX_PENDING) {
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

            // 复用 bitmapBuffer，仅在尺寸变化时重建
            if (bitmapBuffer == null || bufferWidth != imageProxy.width || bufferHeight != imageProxy.height) {
                bitmapBuffer?.recycle()
                bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
                )
                bufferWidth = imageProxy.width
                bufferHeight = imageProxy.height
            }
            bitmapBuffer!!.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            imageProxy.close()

            // 旋转和翻转图像（前置摄像头需要镜像）
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer!!, 0, 0, bitmapBuffer!!.width, bitmapBuffer!!.height,
                matrix, true
            )

            // 入队等待回调回收，记录当前时间戳用于超时清理
            pendingBitmaps.add(PendingBitmap(rotatedBitmap, SystemClock.uptimeMillis()))

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
        // 回收队列中所有待处理 Bitmap
        pendingBitmaps.forEach { it.bitmap.recycle() }
        pendingBitmaps.clear()
        bitmapBuffer?.recycle()
        bitmapBuffer = null
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
