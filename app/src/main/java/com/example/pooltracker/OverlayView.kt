package com.example.pooltracker

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OverlayView(
    context: Context,
    private val onCalibrationChanged: (RectF) -> Unit
) : View(context) {

    private var trajectories: List<PhysicsEngine.BallTrajectory> = emptyList()
    private var detectedBalls: List<PhysicsEngine.DetectedBall> = emptyList()
    private var tableRect = RectF(200f, 150f, 1200f, 650f)
    var isCalibrationMode = false
        set(value) {
            field = value
            postInvalidate()
        }

    private var activeHandle = 0 // 0=none, 1=topLeft, 2=bottomRight, 3=move
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }

    private val ballPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val ballOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        strokeWidth = 8.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val calibBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 100)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val calibHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 215, 0)
        style = Paint.Style.FILL
    }

    private val calibTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun setTableRect(rect: RectF) {
        tableRect = RectF(rect)
        postInvalidate()
    }

    fun updateResults(
        newTrajectories: List<PhysicsEngine.BallTrajectory>,
        newBalls: List<PhysicsEngine.DetectedBall>
    ) {
        trajectories = newTrajectories
        detectedBalls = newBalls
        postInvalidate()
    }

    fun clear() {
        trajectories = emptyList()
        detectedBalls = emptyList()
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrationMode) return false

        val x = event.x
        val y = event.y
        val handleRadius = 70f

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = when {
                    hypot(x - tableRect.left, y - tableRect.top) < handleRadius -> 1
                    hypot(x - tableRect.right, y - tableRect.bottom) < handleRadius -> 2
                    tableRect.contains(x, y) -> 3
                    else -> 0
                }
                lastTouchX = x
                lastTouchY = y
                return activeHandle != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                when (activeHandle) {
                    1 -> {
                        tableRect.left = (tableRect.left + dx).coerceIn(0f, tableRect.right - 200f)
                        tableRect.top = (tableRect.top + dy).coerceIn(0f, tableRect.bottom - 100f)
                    }
                    2 -> {
                        tableRect.right = (tableRect.right + dx).coerceIn(tableRect.left + 200f, width.toFloat())
                        tableRect.bottom = (tableRect.bottom + dy).coerceIn(tableRect.top + 100f, height.toFloat())
                    }
                    3 -> {
                        val w = tableRect.width()
                        val h = tableRect.height()
                        val newL = (tableRect.left + dx).coerceIn(0f, max(0f, width - w))
                        val newT = (tableRect.top + dy).coerceIn(0f, max(0f, height - h))
                        tableRect.set(newL, newT, newL + w, newT + h)
                    }
                }
                lastTouchX = x
                lastTouchY = y
                onCalibrationChanged(RectF(tableRect))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                activeHandle = 0
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val ox = tableRect.left
        val oy = tableRect.top

        // وضع المعايرة: إظهار الإطار الأخضر والمقابض الصفراء لضبط الطاولة على أي هاتف
        if (isCalibrationMode) {
            canvas.drawRect(tableRect, calibBorderPaint)
            canvas.drawCircle(tableRect.left, tableRect.top, 26f, calibHandlePaint)
            canvas.drawCircle(tableRect.right, tableRect.bottom, 26f, calibHandlePaint)
            canvas.drawText(
                "اسحب الدائرتين الصفراوين لتطابق حواف الطاولة الداخلية ثم اضغط ⚙ للحفظ",
                tableRect.centerX(),
                tableRect.centerY(),
                calibTextPaint
            )
            return
        }

        // رسم مسار كل كرة بلونها الحقيقي
        for (traj in trajectories) {
            if (traj.points.size < 2) continue
            val path = Path()
            path.moveTo(traj.points[0].x + ox, traj.points[0].y + oy)
            for (i in 1 until traj.points.size) {
                path.lineTo(traj.points[i].x + ox, traj.points[i].y + oy)
            }

            if (traj.isCue) {
                canvas.drawPath(path, cuePaint)
            } else {
                // رسم إطار أسود خفيف تحت الخط ليكون واضحاً مهما كان لون الطاولة
                canvas.drawPath(path, ballOutlinePaint)
                ballPathPaint.color = traj.color
                canvas.drawPath(path, ballPathPaint)

                // نقطة نهاية المسار
                val last = traj.points.last()
                canvas.drawCircle(last.x + ox, last.y + oy, 7f, ballPathPaint)
            }
        }
    }
}
