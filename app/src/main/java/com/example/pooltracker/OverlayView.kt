package com.example.pooltracker

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayView(context: Context) : View(context) {

    private var cuePath: List<PointF> = emptyList()
    private var targetPath: List<PointF> = emptyList()
    private var detectedBalls: List<PointF> = emptyList()
    private var cueBall: PointF? = null
    private var tableBounds: RectF? = null
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 50, 50)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val debugTablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 255, 0)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val debugBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 255, 255)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    fun updateOverlay(
        result: PhysicsEngine.PathsResult,
        balls: List<PointF>,
        cue: PointF?,
        tableX: Float,
        tableY: Float,
        tableW: Float,
        tableH: Float,
        showDebug: Boolean
    ) {
        cuePath = result.cuePath
        targetPath = result.targetPath
        detectedBalls = if (showDebug) balls else emptyList()
        cueBall = if (showDebug) cue else null
        tableBounds = if (showDebug) RectF(tableX, tableY, tableX + tableW, tableY + tableH) else null
        offsetX = tableX
        offsetY = tableY
        postInvalidate()
    }

    fun clear() {
        cuePath = emptyList()
        targetPath = emptyList()
        detectedBalls = emptyList()
        cueBall = null
        tableBounds = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        tableBounds?.let { canvas.drawRect(it, debugTablePaint) }

        for (b in detectedBalls) {
            canvas.drawCircle(b.x + offsetX, b.y + offsetY, PhysicsEngine.ballRadius, debugBallPaint)
        }

        for (i in 0 until cuePath.size - 1) {
            canvas.drawLine(
                cuePath[i].x + offsetX,
                cuePath[i].y + offsetY,
                cuePath[i + 1].x + offsetX,
                cuePath[i + 1].y + offsetY,
                cuePaint
            )
        }

        if (targetPath.size >= 2) {
            canvas.drawLine(
                targetPath[0].x + offsetX,
                targetPath[0].y + offsetY,
                targetPath[1].x + offsetX,
                targetPath[1].y + offsetY,
                targetPaint
            )
        }
    }
}
