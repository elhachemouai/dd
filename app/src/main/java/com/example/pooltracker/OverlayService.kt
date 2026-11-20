package com.example.pooltracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var calibButton: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val visionEngine = VisionEngine()
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentWidth = 0
    private var currentHeight = 0
    private var currentTableRect = RectF()
    private var isProcessing = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            checkScreenSizeAndRestart()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = OverlayView(this) { updatedRect ->
            currentTableRect = updatedRect
            saveCalibration(updatedRect)
            PhysicsEngine.ballRadius = (updatedRect.height() / 34f).coerceIn(10f, 36f)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        overlayParams.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(overlayView, overlayParams)

        // زر عائم صغير (⚙) لتفعيل أو إغلاق وضع معايرة الطاولة في أي وقت
        calibButton = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 30, 30, 30))
            setPadding(22, 12, 22, 12)
            setOnClickListener { toggleCalibrationMode() }
        }
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 120
        }
        windowManager.addView(calibButton, btnParams)

        bgThread = HandlerThread("PoolVisionThread").apply { start() }
        bgHandler = Handler(bgThread!!.looper)

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, mainHandler)
    }

    private fun toggleCalibrationMode() {
        val enable = !overlayView.isCalibrationMode
        overlayView.isCalibrationMode = enable
        calibButton.setBackgroundColor(
            if (enable) Color.argb(220, 0, 180, 80) else Color.argb(170, 30, 30, 30)
        )
        overlayParams.flags = if (enable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        windowManager.updateViewLayout(overlayView, overlayParams)
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

        // مطلوب رسمياً في أندرويد 14+ قبل إنشاء VirtualDisplay
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
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            setupScreenCapture(w, h)
        }
    }

    private fun loadCalibration(w: Int, h: Int): RectF {
        val prefs = getSharedPreferences("pool_calib", Context.MODE_PRIVATE)
        val l = prefs.getFloat("left", w * 0.16f)
        val t = prefs.getFloat("top", h * 0.18f)
        val r = prefs.getFloat("right", w * 0.84f)
        val b = prefs.getFloat("bottom", h * 0.90f)
        return RectF(l.coerceIn(0f, w.toFloat()), t.coerceIn(0f, h.toFloat()),
            r.coerceIn(0f, w.toFloat()), b.coerceIn(0f, h.toFloat()))
    }

    private fun saveCalibration(rect: RectF) {
        getSharedPreferences("pool_calib", Context.MODE_PRIVATE).edit()
            .putFloat("left", rect.left)
            .putFloat("top", rect.top)
            .putFloat("right", rect.right)
            .putFloat("bottom", rect.bottom)
            .apply()
    }

    private fun setupScreenCapture(width: Int, height: Int) {
        if (mediaProjection == null) return

        // لا تعمل المحاكاة إلا في الوضع الأفقي (داخل اللعبة)
        if (width < height) {
            overlayView.clear()
            return
        }

        currentTableRect = loadCalibration(width, height)
        overlayView.setTableRect(currentTableRect)
        PhysicsEngine.ballRadius = (currentTableRect.height() / 34f).coerceIn(10f, 36f)

        val spinRect = Rect(
            (width * 0.89).toInt(),
            (height * 0.01).toInt(),
            (width * 0.09).toInt(),
            (height * 0.18).toInt()
        )

        val dpi = resources.displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PoolCapturePro", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, bgHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (isProcessing || overlayView.isCalibrationMode) {
                image.close()
                return@setOnImageAvailableListener
            }
            isProcessing = true
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
                val tRect = Rect(
                    currentTableRect.left.toInt(),
                    currentTableRect.top.toInt(),
                    currentTableRect.width().toInt().coerceAtLeast(10),
                    currentTableRect.height().toInt().coerceAtLeast(10)
                )

                val detection = visionEngine.analyzeFrame(cropped, tRect, spinRect)

                val trajectories = if (detection.cueDirection != null && detection.balls.isNotEmpty()) {
                    PhysicsEngine.simulateAllPaths(
                        detection.balls,
                        detection.cueDirection,
                        tRect.width.toFloat(),
                        tRect.height.toFloat(),
                        detection.spinX,
                        detection.spinY
                    )
                } else {
                    emptyList()
                }

                overlayView.updateResults(trajectories, detection.balls)

                cropped.release()
                mat.release()
            } catch (_: Exception) {
            } finally {
                image.close()
                isProcessing = false
            }
        }, bgHandler)
    }

    private fun startForegroundNotification() {
        val channelId = "pool_overlay_pro_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Pool Tracker Pro",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Pool Tracker Pro يعمل")
            .setContentText("اضغط ⚙ داخل اللعبة لضبط حدود الطاولة")
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
        windowManager.removeView(overlayView)
        windowManager.removeView(calibButton)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        bgThread?.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
