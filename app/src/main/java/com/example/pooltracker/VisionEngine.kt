package com.example.pooltracker

import android.graphics.Color
import android.graphics.PointF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

class VisionEngine {

    data class DetectionResult(
        val balls: List<PhysicsEngine.DetectedBall>,
        val cueDirection: PointF?,
        val spinX: Float,
        val spinY: Float
    )

    fun analyzeFrame(rgbaMat: Mat, tableRect: Rect, spinRect: Rect): DetectionResult {
        val bgr = Mat()
        Imgproc.cvtColor(rgbaMat, bgr, Imgproc.COLOR_RGBA2BGR)

        val safeTable = clampRect(tableRect, bgr.cols(), bgr.rows())
        val tableMat = Mat(bgr, safeTable)
        val gray = Mat()
        Imgproc.cvtColor(tableMat, gray, Imgproc.COLOR_BGR2GRAY)

        val blurred = Mat()
        Imgproc.medianBlur(gray, blurred, 5)

        val r = PhysicsEngine.ballRadius.toInt().coerceIn(8, 40)
        val circles = Mat()
        Imgproc.HoughCircles(
            blurred,
            circles,
            Imgproc.HOUGH_GRADIENT,
            1.2,
            r * 1.75,
            60.0,
            22.0,
            max(6, (r * 0.72).toInt()),
            (r * 1.28).toInt()
        )

        val rawBalls = mutableListOf<Triple<Float, Float, Int>>()
        var cueIndex = -1
        var bestCueScore = 0.0

        for (i in 0 until circles.cols()) {
            val data = circles.get(0, i) ?: continue
            val cx = data[0].toFloat()
            val cy = data[1].toFloat()

            // استخراج اللون الحقيقي للكرة من مركزها
            val sampleR = max(3, r / 2)
            val rx = (cx.toInt() - sampleR).coerceIn(0, max(0, tableMat.cols() - 1))
            val ry = (cy.toInt() - sampleR).coerceIn(0, max(0, tableMat.rows() - 1))
            val rw = min(sampleR * 2, tableMat.cols() - rx)
            val rh = min(sampleR * 2, tableMat.rows() - ry)
            if (rw <= 0 || rh <= 0) continue

            val roi = Mat(tableMat, Rect(rx, ry, rw, rh))
            val meanBgr = Core.mean(roi).`val`
            roi.release()

            val bVal = meanBgr[0].toInt().coerceIn(0, 255)
            val gVal = meanBgr[1].toInt().coerceIn(0, 255)
            val rVal = meanBgr[2].toInt().coerceIn(0, 255)

            // حساب درجة بياض الكرة (سطوع عالٍ + تشبع لوني منخفض)
            val brightness = (rVal + gVal + bVal) / 3.0
            val maxC = max(rVal, max(gVal, bVal))
            val minC = min(rVal, min(gVal, bVal))
            val saturation = maxC - minC

            val ballColor = enhanceBallColor(rVal, gVal, bVal)
            rawBalls.add(Triple(cx, cy, ballColor))

            if (brightness > 195.0 && saturation < 45) {
                val cueScore = brightness - saturation * 1.5
                if (cueScore > bestCueScore) {
                    bestCueScore = cueScore
                    cueIndex = rawBalls.size - 1
                }
            }
        }

        val detectedBalls = rawBalls.mapIndexed { idx, (x, y, col) ->
            PhysicsEngine.DetectedBall(x, y, col, idx == cueIndex)
        }

        // اكتشاف اتجاه التصويب حول الكرة البيضاء بمسح شعاعي دقيق
        var cueDirection: PointF? = null
        if (cueIndex >= 0) {
            val cue = detectedBalls[cueIndex]
            cueDirection = detectAimDirectionRadial(gray, cue.x, cue.y, PhysicsEngine.ballRadius)
        }

        // قراءة الـ Spin من النقطة الحمراء
        val (spinX, spinY) = detectSpin(bgr, spinRect)

        bgr.release()
        tableMat.release()
        gray.release()
        blurred.release()
        circles.release()

        return DetectionResult(detectedBalls, cueDirection, spinX, spinY)
    }

    /**
     * مسح شعاعي بزاوية 360 درجة حول الكرة البيضاء للعثور على خط التصويب الأبيض بدقة عالية
     */
    private fun detectAimDirectionRadial(gray: Mat, cx: Float, cy: Float, r: Float): PointF? {
        val w = gray.cols()
        val h = gray.rows()
        var bestAngle = 0.0
        var bestScore = 0.0

        val startDist = r * 1.6f
        val endDist = r * 7.0f
        val stepDist = 2.0f

        // مسح أولي كل 1.5 درجة
        var deg = 0.0
        while (deg < 360.0) {
            val rad = Math.toRadians(deg)
            val cosA = cos(rad)
            val sinA = sin(rad)
            var score = 0.0
            var d = startDist
            while (d <= endDist) {
                val px = (cx + cosA * d).toInt()
                val py = (cy + sinA * d).toInt()
                if (px in 0 until w && py in 0 until h) {
                    val v = gray.get(py, px)[0]
                    if (v > 225.0) score += 1.0
                }
                d += stepDist
            }
            if (score > bestScore) {
                bestScore = score
                bestAngle = deg
            }
            deg += 1.5
        }

        if (bestScore < 6.0) return null

        // تنعيم دقيق للزاوية بنطاق ±2 درجة بخطوة 0.25 درجة
        var refinedAngle = bestAngle
        var refinedScore = bestScore
        var fineDeg = bestAngle - 2.0
        while (fineDeg <= bestAngle + 2.0) {
            val rad = Math.toRadians(fineDeg)
            val cosA = cos(rad)
            val sinA = sin(rad)
            var score = 0.0
            var d = startDist
            while (d <= endDist) {
                val px = (cx + cosA * d).toInt()
                val py = (cy + sinA * d).toInt()
                if (px in 0 until w && py in 0 until h) {
                    val v = gray.get(py, px)[0]
                    if (v > 225.0) score += 1.0
                }
                d += 1.5f
            }
            if (score > refinedScore) {
                refinedScore = score
                refinedAngle = fineDeg
            }
            fineDeg += 0.25
        }

        val finalRad = Math.toRadians(refinedAngle)
        return PointF(cos(finalRad).toFloat(), sin(finalRad).toFloat())
    }

    /**
     * توضيح لون المسار ليكون مرئياً بوضوح فوق لون الطاولة
     */
    private fun enhanceBallColor(r: Int, g: Int, b: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        // إذا كانت الكرة سوداء (الكرة 8)، نرسم مسارها بلون رمادي فاتح/أبيض مائل للبنفسجي ليظهر بوضوح
        if (hsv[2] < 0.22f) {
            return Color.rgb(40, 40, 40)
        }
        hsv[1] = max(hsv[1], 0.85f)
        hsv[2] = max(hsv[2], 0.90f)
        return Color.HSVToColor(235, hsv)
    }

    private fun detectSpin(bgr: Mat, spinRect: Rect): Pair<Float, Float> {
        val safeSpin = clampRect(spinRect, bgr.cols(), bgr.rows())
        if (safeSpin.width <= 4 || safeSpin.height <= 4) return Pair(0f, 0f)

        val spinMat = Mat(bgr, safeSpin)
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
            sx = ((cx - safeSpin.width / 2f) / (safeSpin.width / 2f)).coerceIn(-1f, 1f)
            sy = (-((cy - safeSpin.height / 2f) / (safeSpin.height / 2f))).coerceIn(-1f, 1f)
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
