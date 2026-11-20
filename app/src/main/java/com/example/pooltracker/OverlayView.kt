package com.example.pooltracker

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * طبقة الرسم الاحترافية:
 * - مسار الكرة البيضاء بلون سيان مضيء (غير أبيض لتجنب التداخل مع الرؤية الحاسوبية)
 * - مسار كل كرة أخرى بلونها الحقيقي مع إطار أسود للوضوح التام
 * - كرة شبحية (Ghost Ball) عند نقطة التصادم الأولى
 * - نقاط ارتداد وتصادم مضيئة + تنبيه أخضر على الحفرة عند التنبؤ بدخول الكرة (وأحمر عند دخول البيضاء)
 */
class OverlayView(context: Context) : View(context) {

    private var trajectories: List<PhysicsEngine.BallTrajectory> = emptyList()
    private var ghostBall: PointF? = null
    private var detectedBalls: List<PhysicsEngine.DetectedBall> = emptyList()
    private var tableBounds: RectF? = null
    private var isTableLocked = false
    private var currentSpinX = 0f
    private var currentSpinY = 0f
    var showDebug = false
        set(value) {
            field = value
            postInvalidate()
        }

    // خط مسار الكرة البيضاء (سيان ساطع مع ظل أسود)
    private val cueOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        strokeWidth = 7.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 0, 230, 255)
        strokeWidth = 4.2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(22f, 10f), 0f)
    }

    // خط مسار الكرات الملونة
    private val ballOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
        strokeWidth = 8.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val ballLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5.0f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // دائرة الكرة الشبحية (نقطة الاصطدام)
    private val ghostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 0, 230, 255)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    // علامات الارتداد والتصادم
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        style = Paint.Style.FILL
    }

    // تنبيه دخول الكرة في الحفرة
    private val pocketSuccessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 110)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val pocketFoulPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 40, 40)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    // Debug paints
    private val debugTablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 255, 120)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val debugBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 230, 0)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 15, 20, 28)
        style = Paint.Style.FILL
    }

    private val hudDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }

    fun updateFrame(
        simResult: PhysicsEngine.SimulationResult,
        balls: List<PhysicsEngine.DetectedBall>,
        tableRect: RectF?,
        spinX: Float,
        spinY: Float
    ) {
        trajectories = simResult.trajectories
        ghostBall = simResult.ghostBallPos
        detectedBalls = balls
        tableBounds = tableRect
        isTableLocked = tableRect != null
        currentSpinX = spinX
        currentSpinY = spinY
        postInvalidate()
    }

    fun clear() {
        trajectories = emptyList()
        ghostBall = null
        detectedBalls = emptyList()
        tableBounds = null
        isTableLocked = false
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = tableBounds ?: return
        val ox = rect.left
        val oy = rect.top

        // مؤشر حالة صغير جداً أعلى يسار الطاولة (أخضر = تم قفل الطاولة تلقائياً)
        if (showDebug) {
            canvas.drawRect(rect, debugTablePaint)
            for (b in detectedBalls) {
                debugBallPaint.color = if (b.isCue) Color.CYAN else b.color
                canvas.drawCircle(b.x + ox, b.y + oy, PhysicsEngine.ballRadius, debugBallPaint)
            }
        }

        // شريط حالة مصغر غير مزعج
        val hudRect = RectF(ox + 12f, oy - 44f, ox + 250f, oy - 8f)
        if (hudRect.top > 4f) {
            canvas.drawRoundRect(hudRect, 14f, 14f, hudBgPaint)
            hudDotPaint.color = if (isTableLocked) Color.rgb(0, 235, 110) else Color.rgb(255, 180, 0)
            canvas.drawCircle(hudRect.left + 20f, hudRect.centerY(), 7f, hudDotPaint)
            val spinLabel = if (kotlin.math.abs(currentSpinX) > 0.1f || kotlin.math.abs(currentSpinY) > 0.1f) {
                "Auto • Spin"
            } else {
                "Auto Locked"
            }
            canvas.drawText(spinLabel, hudRect.left + 38f, hudRect.centerY() + 9f, hudTextPaint)
        }

        if (trajectories.isEmpty()) return

        // 1. رسم مسارات الكرات الملونة أولاً
        for (traj in trajectories) {
            if (traj.isCue || traj.points.size < 2) continue
            val path = buildPath(traj.points, ox, oy)
            canvas.drawPath(path, ballOutlinePaint)
            ballLinePaint.color = traj.color
            canvas.drawPath(path, ballLinePaint)

            // علامات الارتداد والتصادم
            for (bp in traj.bouncePoints) {
                canvas.drawCircle(bp.x + ox, bp.y + oy, 5.5f, nodePaint)
            }
            for (cp in traj.collisionPoints) {
                canvas.drawCircle(cp.x + ox, cp.y + oy, 6f, nodePaint)
            }

            // حلقة خضراء مضيئة عند دخول الكرة في الحفرة
            traj.pocketedAt?.let { p ->
                canvas.drawCircle(p.x + ox, p.y + oy, PhysicsEngine.ballRadius * 1.45f, pocketSuccessPaint)
            }
        }

        // 2. رسم مسار الكرة البيضاء (في الأعلى لتوضيح كل ارتداداتها وتصادماتها المتتالية)
        for (traj in trajectories) {
            if (!traj.isCue || traj.points.size < 2) continue
            val path = buildPath(traj.points, ox, oy)
            canvas.drawPath(path, cueOutlinePaint)
            canvas.drawPath(path, cueLinePaint)

            for (bp in traj.bouncePoints) {
                canvas.drawCircle(bp.x + ox, bp.y + oy, 5f, ghostBallPaint)
            }

            // تحذير أحمر إذا كانت الكرة البيضاء ستدخل الحفرة (Foul)
            traj.pocketedAt?.let { p ->
                canvas.drawCircle(p.x + ox, p.y + oy, PhysicsEngine.ballRadius * 1.45f, pocketFoulPaint)
            }
        }

        // 3. رسم الكرة الشبحية (Ghost Ball) عند نقطة التصادم الأولى
        ghostBall?.let { g ->
            canvas.drawCircle(g.x + ox, g.y + oy, PhysicsEngine.ballRadius, ghostBallPaint)
            canvas.drawCircle(g.x + ox, g.y + oy, 4f, nodePaint)
        }
    }

    private fun buildPath(points: List<PointF>, ox: Float, oy: Float): Path {
        val path = Path()
        path.moveTo(points[0].x + ox, points[0].y + oy)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x + ox, points[i].y + oy)
        }
        return path
    }
}
