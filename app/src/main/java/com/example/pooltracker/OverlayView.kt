package com.example.pooltracker

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * واجهة رسم ليزرية أنيقة واحترافية (v7):
 * - خطوط رفيعة وحادة (2.6px) مع تظليل خفيف للوضوح على أي لون طاولة (بدون التوهج السميك المزعج)
 * - تدرج شفافية أنيق: المسار الأساسي واضح تماماً، والارتدادات الثانوية أنعم وأرق لتجنب الفوضى
 * - أسهم اتجاه صغيرة أنيقة (Chevrons) على المسارات لتوضيح اتجاه حركة كل كرة
 * - حلقة خضراء نظيفة على الحفرة عند التنبؤ بدخول الكرة، وحمراء لتحذير سقوط الكرة البيضاء
 */
class OverlayView(context: Context) : View(context) {

    private var trajectories: List<PhysicsEngine.BallTrajectory> = emptyList()
    private var firstHitDistCap: Float = 0f
    private var detectedBalls: List<PhysicsEngine.DetectedBall> = emptyList()
    private var tableRectCap: RectF? = null
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    private var ballRadiusCap: Float = 8.5f

    var showDebug = false
        set(value) {
            field = value
            postInvalidate()
        }

    // خط الكرة البيضاء بعد التصادم (ليزر سيان رفيع ومقطّع بأناقة)
    private val cueCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 0, 0, 0)
        strokeWidth = 4.2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val cueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 70, 240, 255)
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    // خطوط الكرات الملونة (رفيعة، حادة، احترافية)
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

    // أسهم الاتجاه ونقاط الارتداد
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val endDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bounceRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.0f
        style = Paint.Style.STROKE
    }

    // حلقات الحفر
    private val pocketSuccessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 235, 120)
        strokeWidth = 4.0f
        style = Paint.Style.STROKE
    }

    private val pocketFoulPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 50, 60)
        strokeWidth = 4.0f
        style = Paint.Style.STROKE
    }

    // Debug paints
    private val debugTablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 255, 130)
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
        ballRInCap: Float
    ) {
        trajectories = simResult.trajectories
        firstHitDistCap = simResult.firstHitDist
        detectedBalls = balls
        tableRectCap = tableRectInCap
        scaleX = sx
        scaleY = sy
        ballRadiusCap = ballRInCap
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

        // 1. رسم مسارات الكرات الملونة (مقسمة حسب مستوى الارتداد لأناقة ووضوح تام)
        for (traj in trajectories) {
            if (traj.isCue || traj.segments.isEmpty()) continue
            drawSleekBallTrajectory(canvas, traj, skipDistCap = ballRadiusCap * 1.12f, ::mapX, ::mapY, screenR)
        }

        // 2. رسم مسار الكرة البيضاء بعد التصادم (يبدأ بعد نقطة الاصطدام ليظل خط اللعبة مكشوفاً 100%)
        for (traj in trajectories) {
            if (!traj.isCue || traj.segments.isEmpty()) continue
            val path = buildTrimmedSegmentPath(traj.segments, skipDistCap = firstHitDistCap, maxBounce = 2, ::mapX, ::mapY)
            if (path != null) {
                canvas.drawPath(path, cueCasingPaint)
                canvas.drawPath(path, cueLinePaint)
            }

            // نقطة التوقف الصغيرة للكرة البيضاء
            if (traj.pocketedAt == null) {
                endDotPaint.color = Color.argb(200, 70, 240, 255)
                canvas.drawCircle(mapX(traj.endPos.x), mapY(traj.endPos.y), 4.0f, endDotPaint)
            } else {
                val p = traj.pocketedAt
                canvas.drawCircle(mapX(p.x), mapY(p.y), screenR * 1.35f, pocketFoulPaint)
            }
        }
    }

    private fun drawSleekBallTrajectory(
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

        // رسم كل مستوى ارتداد بسُمك وشفافية متدرجة (المسار الرئيسي بارز، الارتدادات أنعم)
        for (level in 0..2) {
            val path = buildLevelPath(traj.segments, targetLevel = level, skipDistCap = skipDistCap, mapX, mapY)
                ?: continue

            val coreWidth = when (level) {
                0 -> 2.8f
                1 -> 2.1f
                else -> 1.6f
            }
            val alpha = when (level) {
                0 -> 240
                1 -> 175
                else -> 115
            }

            ballCasingPaint.color = Color.argb((alpha * 0.45f).toInt(), 0, 0, 0)
            ballCasingPaint.strokeWidth = coreWidth + 2.0f
            canvas.drawPath(path, ballCasingPaint)

            ballCorePaint.color = Color.argb(alpha, rCol, gCol, bCol)
            ballCorePaint.strokeWidth = coreWidth
            canvas.drawPath(path, ballCorePaint)
        }

        // رسم سهم اتجاه صغير أنيق في منتصف المسار الرئيسي (level 0)
        drawDirectionChevron(canvas, traj.segments, skipDistCap, Color.argb(235, rCol, gCol, bCol), mapX, mapY)

        // حلقات صغيرة أنيقة عند نقاط الارتداد عن الحواف
        bounceRingPaint.color = Color.argb(210, rCol, gCol, bCol)
        for (bp in traj.bouncePoints) {
            canvas.drawCircle(mapX(bp.x), mapY(bp.y), 4.2f, bounceRingPaint)
        }

        // نقطة التوقف النهائية أو حلقة النجاح حول الحفرة
        if (traj.pocketedAt != null) {
            val p = traj.pocketedAt
            canvas.drawCircle(mapX(p.x), mapY(p.y), screenR * 1.35f, pocketSuccessPaint)
        } else {
            endDotPaint.color = Color.argb(190, rCol, gCol, bCol)
            canvas.drawCircle(mapX(traj.endPos.x), mapY(traj.endPos.y), 3.8f, endDotPaint)
        }
    }

    private fun drawDirectionChevron(
        canvas: Canvas,
        segments: List<PhysicsEngine.PathSegment>,
        skipDistCap: Float,
        color: Int,
        mapX: (Float) -> Float,
        mapY: (Float) -> Float
    ) {
        // نحسب طول المسار الرئيسي (level 0) ونضع سهماً صغيراً عند 55% من طوله
        var totalPrimary = 0f
        for (s in segments) {
            if (s.bounceLevel == 0) totalPrimary += hypot(s.x2 - s.x1, s.y2 - s.y1)
        }
        val targetDist = skipDistCap + (totalPrimary - skipDistCap) * 0.55f
        if (totalPrimary - skipDistCap < 18f) return

        var acc = 0f
        for (s in segments) {
            if (s.bounceLevel != 0) break
            val len = hypot(s.x2 - s.x1, s.y2 - s.y1)
            if (acc + len >= targetDist && len > 0.1f) {
                val ratio = ((targetDist - acc) / len).coerceIn(0f, 1f)
                val mx = mapX(s.x1 + (s.x2 - s.x1) * ratio)
                val my = mapY(s.y1 + (s.y2 - s.y1) * ratio)
                val angle = atan2((s.y2 - s.y1).toDouble(), (s.x2 - s.x1).toDouble())
                val wingLen = 10f
                val spread = Math.toRadians(148.0)

                val x1 = mx + (cos(angle + spread) * wingLen).toFloat()
                val y1 = my + (sin(angle + spread) * wingLen).toFloat()
                val x2 = mx + (cos(angle - spread) * wingLen).toFloat()
                val y2 = my + (sin(angle - spread) * wingLen).toFloat()

                arrowPaint.color = color
                canvas.drawLine(mx, my, x1, y1, arrowPaint)
                canvas.drawLine(mx, my, x2, y2, arrowPaint)
                return
            }
            acc += len
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

    private fun buildTrimmedSegmentPath(
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
