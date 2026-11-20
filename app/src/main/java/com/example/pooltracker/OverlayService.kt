package com.example.pooltracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView
    private lateinit var debugButton: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val tableDetector = AutoTableDetector()
    private val visionEngine = VisionEngine()
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentWidth = 0
    private var currentHeight = 0
    private var isProcessing = false
    private var lastFrameTime = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            checkScreenSizeAndRestart()
        }
    }

    override fun onCreate() {
        super.onCreate()
        OpenCVLoader.initLocal()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(this)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // إعداد الطبقة لتغطي الشاشة الكاملة بدقة 1:1 حتى مع وجود نوتش أو ثقب كاميرا
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(overlayView, overlayParams)

        // زر صغير جداً (👁) لإظهار/إخفاء إطار الطاولة المكتشف تلقائياً للتحقق
        debugButton = TextView(this).apply {
            text = "👁"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(140, 20, 25, 35))
            setPadding(18, 10, 18, 10)
            setOnClickListener {
                overlayView.showDebug = !overlayView.showDebug
                setBackgroundColor(
                    if (overlayView.showDebug) Color.argb(200, 0, 160, 90)
                    else Color.argb(140, 20, 25, 35)
                )
            }
        }
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 140
        }
        windowManager.addView(debugButton, btnParams)

        bgThread = HandlerThread("PoolProVisionThread").apply { start() }
        bgHandler = Handler(bgThread!!.looper)

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkScreenSizeAndRestart()
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

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)

        checkScreenSizeAndRestart()
        return START_STICKY
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun checkScreenSizeAndRestart() {
        val (w, h) = getRealScreenSize()
        if (w == currentWidth && h == currentHeight && virtualDisplay != null) return
        currentWidth = w
        currentHeight = h

        bgHandler?.post {
            tableDetector.reset()
            visionEngine.resetTracking()
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            setupScreenCapture(w, h)
        }
    }

    private fun setupScreenCapture(width: Int, height: Int) {
        if (mediaProjection == null) return

        // الأداة تتوقف تماماً وتمسح الشاشة خارج الوضع الأفقي
        if (width <= height) {
            overlayView.clear()
            return
        }

        val spinRect = Rect(
            (width * 0.89).toInt(),
            (height * 0.01).toInt(),
            (width * 0.09).toInt(),
            (height * 0.17).toInt()
        )

        val dpi = resources.displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PoolCaptureV4", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, bgHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (isProcessing || now - lastFrameTime < 42L) { // ~24 FPS مستقرة
                image.close()
                return@setOnImageAvailableListener
            }
            isProcessing = true
            lastFrameTime = now

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val rgba = Mat(height, width + rowPadding / pixelStride, CvType.CV_8UC4)
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                rgba.put(0, 0, bytes)

                val croppedRgba = Mat(rgba, Rect(0, 0, width, height))
                val bgr = Mat()
                Imgproc.cvtColor(croppedRgba, bgr, Imgproc.COLOR_RGBA2BGR)

                // 1. الاكتشاف التلقائي للطاولة والتحقق من وجود جولة فعلية
                val tableInfo = tableDetector.detect(bgr)
                if (tableInfo == null) {
                    visionEngine.resetTracking()
                    overlayView.clear()
                } else {
                    // 2. تحليل الكرات وخط التصويب والـ Spin
                    val detection = visionEngine.analyzeTable(bgr, tableInfo, spinRect)

                    // 3. المحاكاة الفيزيائية المتسلسلة لكل الكرات
                    val simResult = if (detection.cueDirection != null && detection.balls.isNotEmpty()) {
                        PhysicsEngine.simulateAllPaths(
                            detection.balls,
                            detection.cueDirection,
                            tableInfo.rect.width.toFloat(),
                            tableInfo.rect.height.toFloat(),
                            detection.spinX,
                            detection.spinY
                        )
                    } else {
                        PhysicsEngine.SimulationResult(emptyList(), null)
                    }

                    val rectF = RectF(
                        tableInfo.rect.x.toFloat(),
                        tableInfo.rect.y.toFloat(),
                        (tableInfo.rect.x + tableInfo.rect.width).toFloat(),
                        (tableInfo.rect.y + tableInfo.rect.height).toFloat()
                    )

                    overlayView.updateFrame(
                        simResult,
                        detection.balls,
                        rectF,
                        detection.spinX,
                        detection.spinY
                    )
                }

                bgr.release()
                croppedRgba.release()
                rgba.release()
            } catch (_: Exception) {
            } finally {
                image.close()
                isProcessing = false
            }
        }, bgHandler)
    }

    private fun startForegroundNotification() {
        val channelId = "pool_overlay_v4_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Pool Tracker Pro v4",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Pool Tracker Pro v4 يعمل")
            .setContentText("الاكتشاف التلقائي للطاولة مفعّل")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.unregisterDisplayListener(displayListener)
        try {
            windowManager.removeView(overlayView)
            windowManager.removeView(debugButton)
        } catch (_: Exception) {
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        bgThread?.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
