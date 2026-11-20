package com.example.pooltracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private val visionEngine = VisionEngine()
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(this)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        windowManager.addView(overlayView, params)

        bgThread = HandlerThread("PoolVisionThread").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("DATA")
        } ?: return START_NOT_STICKY

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        setupScreenCapture()
        return START_STICKY
    }

    private fun setupScreenCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        // نسب تقريبية لمنطقة الطاولة والـ Spin في الوضع الأفقي (Landscape)
        // يمكنك تعديل هذه النسب حسب أبعاد شاشة هاتفك
        val tableRect = Rect(
            (width * 0.18).toInt(),
            (height * 0.22).toInt(),
            (width * 0.64).toInt(),
            (height * 0.68).toInt()
        )
        val spinRect = Rect(
            (width * 0.90).toInt(),
            (height * 0.02).toInt(),
            (width * 0.08).toInt(),
            (height * 0.14).toInt()
        )

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection?.createVirtualDisplay(
            "PoolCapture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, bgHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val mat = Mat(height, width + rowPadding / pixelStride, CvType.CV_8UC4)
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                mat.put(0, 0, bytes)

                val cropped = Mat(mat, Rect(0, 0, width, height))
                val detection = visionEngine.analyzeFrame(cropped, tableRect, spinRect)

                if (detection.cueBall != null && detection.direction != null) {
                    val paths = PhysicsEngine.calculatePaths(
                        detection.cueBall,
                        detection.direction,
                        detection.balls,
                        tableRect.width.toFloat(),
                        tableRect.height.toFloat(),
                        detection.spinX,
                        detection.spinY
                    )
                    overlayView.updatePaths(paths, tableRect.x.toFloat(), tableRect.y.toFloat())
                } else {
                    overlayView.updatePaths(
                        PhysicsEngine.PathsResult(emptyList(), emptyList()),
                        tableRect.x.toFloat(),
                        tableRect.y.toFloat()
                    )
                }

                cropped.release()
                mat.release()
            } finally {
                image.close()
            }
        }, bgHandler)
    }

    private fun startForegroundNotification() {
        val channelId = "pool_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Pool Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("أداة تتبع مسار البلياردو تعمل")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(overlayView)
        mediaProjection?.stop()
        imageReader?.close()
        bgThread?.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
