package com.example.pooltracker

import android.graphics.PointF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class VisionEngine {

    data class DetectionResult(
        val cueBall: PointF?,
        val direction: PointF?,
        val balls: List<PointF>,
        val spinX: Float,
        val spinY: Float
    )

    fun analyzeFrame(rgbaMat: Mat, tableRect: Rect, spinRect: Rect): DetectionResult {
        val bgr = Mat()
        Imgproc.cvtColor(rgbaMat, bgr, Imgproc.COLOR_RGBA2BGR)

        // 1. استخراج منطقة الطاولة
        val safeTableRect = clampRect(tableRect, bgr.cols(), bgr.rows())
        val tableMat = Mat(bgr, safeTableRect)
        val gray = Mat()
        Imgproc.cvtColor(tableMat, gray, Imgproc.COLOR_BGR2GRAY)

        val blurred = Mat()
        Imgproc.medianBlur(gray, blurred, 5)

        val radius = PhysicsEngine.ballRadius.toInt()
        val circles = Mat()
        Imgproc.HoughCircles(
            blurred,
            circles,
            Imgproc.HOUGH_GRADIENT,
            1.2,
            radius * 1.8,
            50.0,
            25.0,
            max(5, radius - 4),
            radius + 4
        )

        val balls = mutableListOf<PointF>()
        var cueBall: PointF? = null
        var maxBrightness = 0.0

        for (i in 0 until circles.cols()) {
            val data = circles.get(0, i) ?: continue
            val x = data[0].toFloat()
            val y = data[1].toFloat()
            balls.add(PointF(x, y))

            val rx = max(0, x.toInt() - 5)
            val ry = max(0, y.toInt() - 5)
            val rw = min(10, gray.cols() - rx)
            val rh = min(10, gray.rows() - ry)
            if (rw > 0 && rh > 0) {
                val roi = Mat(gray, Rect(rx, ry, rw, rh))
                val brightness = Core.mean(roi).`val`[0]
                roi.release()
                if (brightness > maxBrightness && brightness > 200.0) {
                    maxBrightness = brightness
                    cueBall = PointF(x, y)
                }
            }
        }

        // 2. اكتشاف خط التصويب الأبيض
        var direction: PointF? = null
        if (cueBall != null) {
            val whiteMask = Mat()
            Imgproc.threshold(gray, whiteMask, 235.0, 255.0, Imgproc.THRESH_BINARY)
            val lines = Mat()
            Imgproc.HoughLinesP(whiteMask, lines, 1.0, Math.PI / 180.0, 30, 40.0, 10.0)

            var bestDist = Float.POSITIVE_INFINITY
            for (i in 0 until lines.rows()) {
                val l = lines.get(i, 0) ?: continue
                val x1 = l[0].toFloat()
                val y1 = l[1].toFloat()
                val x2 = l[2].toFloat()
                val y2 = l[3].toFloat()

                val d1 = hypot(x1 - cueBall.x, y1 - cueBall.y)
                val d2 = hypot(x2 - cueBall.x, y2 - cueBall.y)
                val minDist = min(d1, d2)

                if (minDist < PhysicsEngine.ballRadius * 2.5f && minDist < bestDist) {
                    bestDist = minDist
                    direction = if (d1 < d2) {
                        PointF(x2 - x1, y2 - y1)
                    } else {
                        PointF(x1 - x2, y1 - y2)
                    }
                }
            }
            whiteMask.release()
            lines.release()
        }

        // 3. قراءة الـ Spin من النقطة الحمراء
        val (spinX, spinY) = detectSpin(bgr, spinRect)

        // تحرير الذاكرة
        bgr.release()
        tableMat.release()
        gray.release()
        blurred.release()
        circles.release()

        return DetectionResult(cueBall, direction, balls, spinX, spinY)
    }

    private fun detectSpin(bgr: Mat, spinRect: Rect): Pair<Float, Float> {
        val safeSpinRect = clampRect(spinRect, bgr.cols(), bgr.rows())
        if (safeSpinRect.width <= 0 || safeSpinRect.height <= 0) return Pair(0f, 0f)

        val spinMat = Mat(bgr, safeSpinRect)
        val hsv = Mat()
        Imgproc.cvtColor(spinMat, hsv, Imgproc.COLOR_BGR2HSV)

        val mask1 = Mat()
        val mask2 = Mat()
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 120.0, 70.0), Scalar(10.0, 255.0, 255.0), mask1)
        Core.inRange(hsv, Scalar(170.0, 120.0, 70.0), Scalar(180.0, 255.0, 255.0), mask2)
        Core.bitwise_or(mask1, mask2, mask)

        val moments = Imgproc.moments(mask)
        var sx = 0f
        var sy = 0f
        if (moments.m00 > 0) {
            val cx = (moments.m10 / moments.m00).toFloat()
            val cy = (moments.m01 / moments.m00).toFloat()
            sx = ((cx - safeSpinRect.width / 2f) / (safeSpinRect.width / 2f)).coerceIn(-1f, 1f)
            sy = (-((cy - safeSpinRect.height / 2f) / (safeSpinRect.height / 2f))).coerceIn(-1f, 1f)
        }

        spinMat.release()
        hsv.release()
        mask1.release()
        mask2.release()
        mask.release()

        return Pair(sx, sy)
    }

    private fun clampRect(r: Rect, maxW: Int, maxH: Int): Rect {
        val x = r.x.coerceIn(0, max(0, maxW - 1))
        val y = r.y.coerceIn(0, max(0, maxH - 1))
        val w = r.width.coerceIn(1, max(1, maxW - x))
        val h = r.height.coerceIn(1, max(1, maxH - y))
        return Rect(x, y, w, h)
    }
}
