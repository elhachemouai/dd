package com.example.pooltracker

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * تصميم بصري نظيف واحترافي (v8):
 * - خطوط رفيعة ثابتة وصلبة بلون كل كرة مع حد داكن خفيف (بدون توهج مشوش)
 * - دائرة صغيرة أنيقة لنقطة التوقف المتوقعة لكل كرة
 * - إشارة خضراء واضحة على الحفرة عند التنبؤ بدخول كرة هدف، وحمراء لتحذير سقوط الكرة البيضاء
 * - شريط معلومات مصغّر يظهر نسبة قوة الضربة لحظة سحب شريط القوة
 */
class OverlayView(context: Context) : View(context) {

    private var trajectories: List<PhysicsEngine.BallTrajectory> = emptyList()
    private var firstHitDistCap: Float = 0f
    private var detectedBalls: List<PhysicsEngine.DetectedBall> = emptyList()
    private var tableRectCap: RectF? = null
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    private var ballRadiusCap: Float = 8.5f
    private var currentPower: Float = 0.70f
    private var isPullingPower: Boolean = false
    private var spinX: Float = 0f
    private var spinY: Float = 0f

    var showDebug = false
        set(value) {
            field = value
            postInvalidate()
        }

    private val cueCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 0, 0, 0)
        strokeWidth = 3.8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val cueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 225, 248, 255)
        strokeWidth = 2.0f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val ballCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val ballCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val stopRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
    }

    private val stopDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pocketSuccessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 0, 235, 115)
        strokeWidth = 4.2f
        style = Paint.Style.STROKE
    }

    private val pocketFoulPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 50, 60)
        strokeWidth = 4.2f
        style = Paint.Style.STROKE
    }

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(165, 15, 20, 28)
        style = Paint.Style.FILL
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val debugTablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(165, 0, 255, 130)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val debugBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
    }

    fun updateFrame(
        simResult: PhysicsEngine.SimulationResult,
        balls: List<PhysicsEngine.DetectedBall>,
        tableRectInCap: RectF?,
        sx: Float,
        sy: Float,
        ballRInCap: Float,
        power: Float,
        pullingPower: Boolean,
        sX: Float,
        sY: Float
    ) {
        trajectories = simResult.trajectories
        firstHitDistCap = simResult.firstHitDist
        detectedBalls = balls
        tableRectCap = tableRectInCap
        scaleX = sx
        scaleY = sy
        ballRadiusCap = ballRInCap
        currentPower = power
        isPullingPower = pullingPower
        spinX = sX
        spinY = sY
        postInvalidate()
    }

    fun clear() {
        if (trajectories.isEmpty() && tableRectCap == null) return
        trajectories = emptyList()
        detectedBalls = emptyList()
        tableRectCap = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val tCap = tableRectCap ?: return

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val winOffX = loc[0].toFloat()
        val winOffY = loc[1].toFloat()

        val ox = tCap.left * scaleX - winOffX
        val oy = tCap.top * scaleY - winOffY

        fun mapX(xInTable: Float) = ox + xInTable * scaleX
        fun mapY(yInTable: Float) = oy + yInTable * scaleY

        val screenR = ballRadiusCap * ((scaleX + scaleY) * 0.5f)

        if (showDebug) {
            val screenRect = RectF(
                tCap.left * scaleX - winOffX,
                tCap.top * scaleY - winOffY,
                tCap.right * scaleX - winOffX,
                tCap.bottom * scaleY - winOffY
            )
            canvas.drawRect(screenRect, debugTablePaint)
            for (b in detectedBalls) {
                debugBallPaint.color = if (b.isCue) Color.CYAN else b.color
                canvas.drawCircle(mapX(b.x), mapY(b.y), screenR, debugBallPaint)
            }
        }

        if (trajectories.isEmpty()) return

        // شريط حالة مصغّر يظهر فقط عند سحب القوة أو تفعيل الـ Spin
        if (isPullingPower || abs(spinX) > 0.1f || abs(spinY) > 0.1f) {
            val label = buildString {
                if (isPullingPower) append("PWR ${(currentPower * 100).toInt()}%")
                if (abs(spinX) > 0.1f || abs(spinY) > 0.1f) {
                    if (isNotEmpty()) append(" • ")
                    append("SPIN")
                }
            }
            val textW = hudTextPaint.measureText(label)
            val pillLeft = ox + 10f
            val pillTop = (oy - 42f).coerceAtLeast(8f)
            val pillRect = RectF(pillLeft, pillTop, pillLeft + textW + 28f, pillTop + 34f)
            canvas.drawRoundRect(pillRect, 14f, 14f, hudBgPaint)
            canvas.drawText(label, pillLeft + 14f, pillTop + 25f, hudTextPaint)
        }

        // 1. رسم مسارات الكرات الملونة (خطوط رفيعة نظيفة + دائرة نقطة التوقف)
        for (traj in trajectories) {
            if (traj.isCue || traj.segments.isEmpty()) continue
            drawCleanObjectPath(canvas, traj, skipDistCap = ballRadiusCap * 1.12f, ::mapX, ::mapY, screenR)
        }

        // 2. رسم مسار الكرة البيضاء بعد أول تصادم (يترك خط اللعبة الأصلي مكشوفاً 100%)
        for (traj in trajectories) {
            if (!traj.isCue || traj.segments.isEmpty()) continue
            val path = buildTrimmedPath(traj.segments, skipDistCap = firstHitDistCap, maxBounce = 2, ::mapX, ::mapY)
            if (path != null) {
                canvas.drawPath(path, cueCasingPaint)
                canvas.drawPath(path, cueLinePaint)
            }

            if (traj.pocketedAt != null) {
                val p = traj.pocketedAt
                canvas.drawCircle(mapX(p.x), mapY(p.y), screenR * 1.35f, pocketFoulPaint)
            } else if (path != null) {
                val ex = mapX(traj.endPos.x)
                val ey = mapY(traj.endPos.y)
                stopRingPaint.color = Color.argb(215, 225, 248, 255)
                stopDotPaint.color = Color.argb(215, 225, 248, 255)
                canvas.drawCircle(ex, ey, 6.0f, stopRingPaint)
                canvas.drawCircle(ex, ey, 2.2f, stopDotPaint)
            }
        }
    }

    private fun drawCleanObjectPath(
        canvas: Canvas,
        traj: PhysicsEngine.BallTrajectory,
        skipDistCap: Float,
        mapX: (Float) -> Float,
        mapY: (Float) -> Float,
        screenR: Float
    ) {
        val rCol = Color.red(traj.color)
        val gCol = Color.green(traj.color)
        val bCol = Color.blue(traj.color)

        // المسار الأساسي (level 0) صلب وواضح، والارتداد الأول (level 1) أنعم قليلاً
        for (level in 0..1) {
            val path = buildLevelPath(traj.segments, targetLevel = level, skipDistCap = skipDistCap, mapX, mapY)
                ?: continue

            val coreW = if (level == 0) 2.5f else 1.9f
            val alpha = if (level == 0) 245 else 185

            ballCasingPaint.color = Color.argb((alpha * 0.45f).toInt(), 0, 0, 0)
            ballCasingPaint.strokeWidth = coreW + 1.8f
            canvas.drawPath(path, ballCasingPaint)

            ballCorePaint.color = Color.argb(alpha, rCol, gCol, bCol)
            ballCorePaint.strokeWidth = coreW
            canvas.drawPath(path, ballCorePaint)
        }

        // دائرة صغيرة أنيقة في نقطة التوقف أو حلقة خضراء واضحة على الحفرة
        if (traj.pocketedAt != null) {
            val p = traj.pocketedAt
            canvas.drawCircle(mapX(p.x), mapY(p.y), screenR * 1.35f, pocketSuccessPaint)
        } else {
            val ex = mapX(traj.endPos.x)
            val ey = mapY(traj.endPos.y)
            stopRingPaint.color = Color.argb(225, rCol, gCol, bCol)
            stopDotPaint.color = Color.argb(225, rCol, gCol, bCol)
            canvas.drawCircle(ex, ey, 5.5f, stopRingPaint)
            canvas.drawCircle(ex, ey, 2.0f, stopDotPaint)
        }
    }

    private fun buildLevelPath(
        segments: List<PhysicsEngine.PathSegment>,
        targetLevel: Int,
        skipDistCap: Float,
        mapX: (Float) -> Float,
        mapY: (Float) -> Float
    ): Path? {
        val path = Path()
        var acc = 0f
        var hasContent = false
        var lastX = Float.NaN
        var lastY = Float.NaN

        for (s in segments) {
            val segLen = hypot(s.x2 - s.x1, s.y2 - s.y1)
            if (segLen < 0.001f) continue

            val segStart = acc
            val segEnd = acc + segLen
            acc = segEnd

            if (s.bounceLevel != targetLevel || segEnd <= skipDistCap) continue

            val sx: Float
            val sy: Float
            if (segStart < skipDistCap) {
                val ratio = ((skipDistCap - segStart) / segLen).coerceIn(0f, 1f)
                sx = s.x1 + (s.x2 - s.x1) * ratio
                sy = s.y1 + (s.y2 - s.y1) * ratio
            } else {
                sx = s.x1
                sy = s.y1
            }

            val msx = mapX(sx)
            val msy = mapY(sy)
            val mex = mapX(s.x2)
            val mey = mapY(s.y2)

            if (!hasContent || hypot(msx - lastX, msy - lastY) > 2.0f) {
                path.moveTo(msx, msy)
            }
            path.lineTo(mex, mey)
            lastX = mex
            lastY = mey
            hasContent = true
        }
        return if (hasContent) path else null
    }

    private fun buildTrimmedPath(
        segments: List<PhysicsEngine.PathSegment>,
        skipDistCap: Float,
        maxBounce: Int,
        mapX: (Float) -> Float,
        mapY: (Float) -> Float
    ): Path? {
        val path = Path()
        var acc = 0f
        var hasContent = false
        var lastX = Float.NaN
        var lastY = Float.NaN

        for (s in segments) {
            if (s.bounceLevel > maxBounce) break
            val segLen = hypot(s.x2 - s.x1, s.y2 - s.y1)
            if (segLen < 0.001f) continue

            val segStart = acc
            val segEnd = acc + segLen
            acc = segEnd
            if (segEnd <= skipDistCap) continue

            val sx: Float
            val sy: Float
            if (segStart < skipDistCap) {
                val ratio = ((skipDistCap - segStart) / segLen).coerceIn(0f, 1f)
                sx = s.x1 + (s.x2 - s.x1) * ratio
                sy = s.y1 + (s.y2 - s.y1) * ratio
            } else {
                sx = s.x1
                sy = s.y1
            }

            val msx = mapX(sx)
            val msy = mapY(sy)
            val mex = mapX(s.x2)
            val mey = mapY(s.y2)

            if (!hasContent || hypot(msx - lastX, msy - lastY) > 2.0f) {
                path.moveTo(msx, msy)
            }
            path.lineTo(mex, mey)
            lastX = mex
            lastY = mey
            hasContent = true
        }
        return if (hasContent) path else null
    }
}
