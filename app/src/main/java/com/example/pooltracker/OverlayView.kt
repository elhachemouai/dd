package com.example.pooltracker

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayView(context: Context) : View(context) {

    private var cuePath: List<PointF> = emptyList()
    private var targetPath: List<PointF> = emptyList()
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 50, 50)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    fun updatePaths(result: PhysicsEngine.PathsResult, tableOffsetX: Float, tableOffsetY: Float) {
        cuePath = result.cuePath
        targetPath = result.targetPath
        offsetX = tableOffsetX
        offsetY = tableOffsetY
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

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
