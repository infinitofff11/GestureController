package com.example.gesturecontroller.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.gesturecontroller.GestureExecutor
import com.example.gesturecontroller.GestureHelper
import com.example.gesturecontroller.HandLandmarkerHelper
import com.example.gesturecontroller.MainActivity
import com.example.gesturecontroller.R
import com.example.gesturecontroller.model.GestureType
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executor

class OverlayService : Service(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var gestureIcon: ImageView? = null
    private var gestureText: TextView? = null
    private var isControlEnabled = true
    private val mainHandler = Handler(Looper.getMainLooper())

    // 手势识别相关
    private var landmarkerHelper: HandLandmarkerHelper? = null
    private val gestureHelper = GestureHelper()
    private val gestureExecutor = GestureExecutor()
    private var screenWidth = 0
    private var screenHeight = 0
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzerThread: HandlerThread? = null
    private var analyzerHandler: Handler? = null

    // Activity 提供的预览 SurfaceProvider，用于同时显示摄像头画面
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    // 已绑定的 Preview 实例引用，SurfaceProvider 变化时直接更新，避免 unbindAll 重建会话
    private var boundPreview: Preview? = null
    // 标记相机是否已绑定，防止重复绑定导致会话重建
    private var isCameraBound = false

    companion object {
        var instance: OverlayService? = null
            private set

        fun isRunning(): Boolean = instance != null

        private const val CHANNEL_ID = "gesture_controller_channel"
        private const val NOTIFICATION_ID = 1
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        showOverlay()
        initLandmarker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return START_STICKY
    }

    /**
     * Activity 调用此方法注册/更新 PreviewView 的 SurfaceProvider
     * 直接更新已绑定的 Preview，不触发 unbindAll，避免相机会话重建导致 MediaPipe 中断
     */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = provider
        // 直接更新 Preview 的 SurfaceProvider，不重建会话
        boundPreview?.setSurfaceProvider(provider)
    }

    private fun initLandmarker() {
        landmarkerHelper = HandLandmarkerHelper(
            this,
            object : HandLandmarkerHelper.LandmarkerListener {
                override fun onResults(result: HandLandmarkerResult) {
                    processLandmarkResult(result)
                }
                override fun onError(error: String) {}
            }
        )

        if (landmarkerHelper?.isReady() == true) {
            startCamera()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startCamera() {
        // 创建后台线程用于 ImageAnalysis，避免阻塞主线程
        analyzerThread = HandlerThread("CameraAnalyzer").also { it.start() }
        analyzerHandler = Handler(analyzerThread!!.looper)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 绑定摄像头用例：只绑定一次，同时绑定 Preview + ImageAnalysis
     * SurfaceProvider 变化时通过 setPreviewSurfaceProvider 直接更新，不重建会话
     */
    private fun bindCameraUseCases() {
        // 防止重复绑定，避免会话重建导致 MediaPipe 中断
        if (isCameraBound) return
        val provider = cameraProvider ?: return

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                val executor: java.util.concurrent.Executor = if (analyzerHandler != null) {
                    java.util.concurrent.Executor { runnable -> analyzerHandler!!.post(runnable) }
                } else {
                    ContextCompat.getMainExecutor(this)
                }
                it.setAnalyzer(executor) { imageProxy ->
                    landmarkerHelper?.detectLiveStream(imageProxy)
                }
            }

        // 始终创建 Preview 并保留引用，SurfaceProvider 可为 null（后台时无预览）
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewSurfaceProvider)
        boundPreview = preview

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            isCameraBound = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Camera binding failed", e)
        }
    }

    private fun processLandmarkResult(result: HandLandmarkerResult) {
        val landmarks = result.landmarks()
        if (landmarks.isNullOrEmpty()) return

        val handLandmarks = landmarks[0]
        val gesture = gestureHelper.detectGesture(handLandmarks, SystemClock.uptimeMillis())

        if (gesture != GestureType.NONE) {
            executeGesture(gesture)
        }
    }

    private fun executeGesture(gesture: GestureType) {
        if (!isControlEnabled) return
        if (screenWidth <= 0 || screenHeight <= 0) return

        gestureExecutor.execute(gesture, screenWidth, screenHeight)
        updateGestureDisplay(getGestureDisplayName(gesture), getGestureIcon(gesture))
    }

    private fun getGestureDisplayName(gesture: GestureType): String = when (gesture) {
        GestureType.SWIPE_UP -> "上滑-屏幕上滑"
        GestureType.SWIPE_DOWN -> "下滑-屏幕下滑"
        GestureType.SWIPE_LEFT -> "左滑-返回"
        GestureType.SWIPE_RIGHT -> "右滑-主屏"
        GestureType.TAP -> "握拳-点击"
        GestureType.NONE -> ""
    }

    private fun getGestureIcon(gesture: GestureType): Int = when (gesture) {
        GestureType.SWIPE_UP -> android.R.drawable.arrow_up_float
        GestureType.SWIPE_DOWN -> android.R.drawable.arrow_down_float
        GestureType.SWIPE_LEFT -> android.R.drawable.ic_menu_revert
        GestureType.SWIPE_RIGHT -> android.R.drawable.ic_menu_recent_history
        GestureType.TAP -> android.R.drawable.ic_menu_mylocation
        GestureType.NONE -> android.R.drawable.ic_menu_help
    }

    @SuppressLint("InflateParams")
    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 200

        overlayView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_panel, null)

        gestureIcon = overlayView?.findViewById(R.id.iv_gesture_icon)
        gestureText = overlayView?.findViewById(R.id.tv_gesture_name)

        val toggleBtn = overlayView?.findViewById<ImageView>(R.id.iv_toggle)
        toggleBtn?.setOnClickListener {
            isControlEnabled = !isControlEnabled
            toggleBtn.setImageResource(
                if (isControlEnabled) android.R.drawable.ic_media_play
                else android.R.drawable.ic_media_pause
            )
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    fun updateGestureDisplay(gestureName: String, iconRes: Int) {
        mainHandler.post {
            gestureText?.text = gestureName
            gestureIcon?.setImageResource(iconRes)
        }
    }

    fun isControlEnabled(): Boolean = isControlEnabled

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手势控制服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持手势识别在后台运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手势控制器")
            .setContentText("手势识别运行中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraProvider?.unbindAll()
        isCameraBound = false
        boundPreview = null
        landmarkerHelper?.close()
        overlayView?.let { windowManager.removeView(it) }
        previewSurfaceProvider = null
        analyzerThread?.quitSafely()
        analyzerThread = null
        analyzerHandler = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
