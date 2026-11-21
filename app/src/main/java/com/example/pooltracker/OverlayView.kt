package com.example.pooltracker

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * طبقة رسم احترافية مصممة خصيصاً لمنع التداخل الذاتي مع التقاط الشاشة:
 * - تترك الجزء الأول من خط التصويب الأبيض للعبة مكشوفاً 100% فلا يحدث أي وميض أبداً
 * - تبدأ مسار كل كرة من حافتها الخارجية (لا تغطي وجه الكرة)
 * - لا ترسم دوائر سوداء أو نقاطاً بيضاء قد تخدع محرك الرؤية
 */
class OverlayView(context: Context) : View(context) {

    private var trajectories: List<PhysicsEngine.BallTrajectory> = emptyList()
    private var firstHitDistCap: Float = 0f
    private var detectedBalls: List<PhysicsEngine.DetectedBall> = emptyList()
    private var tableRectCap: RectF? = null
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    private var ballRadiusCap: Float = 10f
    private var currentSpinX: Float = 0f
    private var currentSpinY: Float = 0f

    var showDebug = false
        set(value) {
            field = value
            postInvalidate()
        }

    // خط مسار الكرة البيضاء (سيان نيون أنيق بدون إطار أسود مزعج)
    private val cueGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 0, 235, 255)
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cueCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 0, 240, 255)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }

    // خطوط مسارات الكرات الملونة (توهج ناعم + خط واضح بنفس لون الكرة)
    private val ballGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val ballCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4.2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // علامات الارتداد والتصادم (نقاط صغيرة ملونة غير بيضاء)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // حلقات الحفر (خضراء لدخول الهدف، حمراء لتحذير سقوط الكرة البيضاء)
    private val pocketSuccessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 110)
        strokeWidth = 5.5f
        style = Paint.Style.STROKE
    }

    private val pocketFoulPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 45, 45)
        strokeWidth = 5.5f
        style = Paint.Style.STROKE
    }

    private val debugTablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 255, 120)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val debugBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.8f
        style = Paint.Style.STROKE
    }

    fun updateFrame(
        simResult: PhysicsEngine.SimulationResult,
        balls: List<PhysicsEngine.DetectedBall>,
        tableRectInCap: RectF?,
        sx: Float,
        sy: Float,
        ballRInCap: Float,
        spinX: Float,
        spinY: Float
    ) {
        trajectories = simResult.trajectories
        firstHitDistCap = simResult.firstHitDist
        detectedBalls = balls
        tableRectCap = tableRectInCap
        scaleX = sx
        scaleY = sy
        ballRadiusCap = ballRInCap
        currentSpinX = spinX
        currentSpinY = spinY
        postInvalidate()
    }

    fun clear() {
        trajectories = emptyList()
        detectedBalls = emptyList()
        tableRectCap = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val tCap = tableRectCap ?: return

        // تعويض دقيق لأي إزاحة من النوتش أو شريط الحالة على أي هاتف
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val winOffX = loc[0].toFloat()
        val winOffY = loc[1].toFloat()

        val ox = tCap.left * scaleX - winOffX
        val oy = tCap.top * scaleY - winOffY

        fun mapX(xInTable: Float) = ox + xInTable * scaleX
        fun mapY(yInTable: Float) = oy + yInTable * scaleY

        // وضع المعاينة الاختياري (عند الضغط على زر 👁)
        if (showDebug) {
            val screenRect = RectF(
                tCap.left * scaleX - winOffX,
                tCap.top * scaleY - winOffY,
                tCap.right * scaleX - winOffX,
                tCap.bottom * scaleY - winOffY
            )
            canvas.drawRect(screenRect, debugTablePaint)
            val rScreen = ballRadiusCap * ((scaleX + scaleY) * 0.5f)
            for (b in detectedBalls) {
                debugBallPaint.color = if (b.isCue) Color.CYAN else b.color
                canvas.drawCircle(mapX(b.x), mapY(b.y), rScreen, debugBallPaint)
            }
        }

        if (trajectories.isEmpty()) return

        val screenR = ballRadiusCap * ((scaleX + scaleY) * 0.5f)

        // 1. رسم مسار كل كرة ملونة (تبدأ من حافة الكرة بعد 1.2*r حتى لا تغطي جسم الكرة)
        for (traj in trajectories) {
            if (traj.isCue || traj.points.size < 2) continue
            val path = buildTrimmedPath(traj.points, skipDist = ballRadiusCap * 1.20f, ::mapX, ::mapY)
            if (path != null) {
                ballGlowPaint.color = Color.argb(65, Color.red(traj.color), Color.green(traj.color), Color.blue(traj.color))
                ballCorePaint.color = traj.color
                canvas.drawPath(path, ballGlowPaint)
                canvas.drawPath(path, ballCorePaint)
            }

            // نقاط الارتداد والتصادم المتسلسل بلون الكرة
            markerPaint.color = traj.color
            for (bp in traj.bouncePoints) {
                canvas.drawCircle(mapX(bp.x), mapY(bp.y), 4.5f, markerPaint)
            }
            for (cp in traj.collisionPoints) {
                canvas.drawCircle(mapX(cp.x), mapY(cp.y), 5.0f, markerPaint)
            }

            // حلقة خضراء حول الحفرة إذا كانت الكرة ستدخلها
            traj.pocketedAt?.let { p ->
                canvas.drawCircle(mapX(p.x), mapY(p.y), screenR * 1.4f, pocketSuccessPaint)
            }
        }

        // 2. رسم مسار الكرة البيضاء (يتجاوز الجزء الأول من خط اللعبة الأبيض ليظل مكشوفاً 100%!)
        val cueSkipDist = min(firstHitDistCap, ballRadiusCap * 6.2f)
        for (traj in trajectories) {
            if (!traj.isCue || traj.points.size < 2) continue
            val path = buildTrimmedPath(traj.points, skipDist = cueSkipDist, ::mapX, ::mapY)
            if (path != null) {
                canvas.drawPath(path, cueGlowPaint)
                canvas.drawPath(path, cueCorePaint)
            }

            markerPaint.color = Color.argb(230, 0, 235, 255)
            for (bp in traj.bouncePoints) {
                canvas.drawCircle(mapX(bp.x), mapY(bp.y), 4.5f, markerPaint)
            }

            // تحذير أحمر إذا كانت الكرة البيضاء ستسقط في الحفرة
            traj.pocketedAt?.let { p ->
                canvas.drawCircle(mapX(p.x), mapY(p.y), screenR * 1.4f, pocketFoulPaint)
            }
        }
    }

    /**
     * يبني المسار مع تجاوز مسافة بداية محددة (skipDist) لحماية الكرات وخط التصويب من التغطية
     */
    private fun buildTrimmedPath(
        points: List<PointF>,
        skipDist: Float,
        mapX: (Float) -> Float,
        mapY: (Float) -> Float
    ): Path? {
        if (points.size < 2) return null
        val path = Path()
        var acc = 0f
        var started = false

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val segLen = hypot(p2.x - p1.x, p2.y - p1.y)
            if (segLen < 0.001f) continue

            if (!started) {
                if (acc + segLen >= skipDist) {
                    val remain = (skipDist - acc).coerceIn(0f, segLen)
                    val ratio = remain / segLen
                    val sx = p1.x + (p2.x - p1.x) * ratio
                    val sy = p1.y + (p2.y - p1.y) * ratio
                    path.moveTo(mapX(sx), mapY(sy))
                    path.lineTo(mapX(p2.x), mapY(p2.y))
                    started = true
                }
                acc += segLen
            } else {
                path.lineTo(mapX(p2.x), mapY(p2.y))
            }
        }
        return if (started) path else null
    }
}
