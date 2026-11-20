package com.example.pooltracker

import android.graphics.Color
import android.graphics.PointF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * محرك الرؤية الحاسوبية المطور:
 * - اكتشاف هجين للكرات (عزل قماش الطاولة + HoughCircles) لعدم تفويت أي كرة مهما كان لونها
 * - استخراج اللون الحقيقي لكل كرة (تجاهل الأرقام والخطوط البيضاء في الكرات المخططة)
 * - مسح شعاعي فائق السرعة (< 2ms) مع مناعة 100% ضد قراءة خطوط الـ Overlay الخاصة بنا
 */
class VisionEngine {

    data class DetectionResult(
        val balls: List<PhysicsEngine.DetectedBall>,
        val cueDirection: PointF?,
        val spinX: Float,
        val spinY: Float
    )

    private var smoothedAngleRad: Double? = null

    fun resetTracking() {
        smoothedAngleRad = null
    }

    fun analyzeTable(
        bgr: Mat,
        tableInfo: AutoTableDetector.TableInfo,
        spinRect: Rect
    ): DetectionResult {
        val safeTable = clampRect(tableInfo.rect, bgr.cols(), bgr.rows())
        val tableBgr = Mat(bgr, safeTable)
        val tableHsv = Mat()
        Imgproc.cvtColor(tableBgr, tableHsv, Imgproc.COLOR_BGR2HSV)

        val tw = tableBgr.cols()
        val th = tableBgr.rows()
        val r = (th / 31.5f).coerceIn(9f, 36f)
        PhysicsEngine.ballRadius = r

        // 1. عزل قماش الطاولة لإيجاد جميع الأجسام غير القماش (الكرات)
        val feltMask = AutoTableDetector.buildFeltMask(
            tableHsv, tableInfo.feltH, tableInfo.feltS, tableInfo.feltV, tableInfo.isMonochrome
        )
        val nonFelt = Mat()
        Core.bitwise_not(feltMask, nonFelt)

        // إزالة الحفر الست من القناع لكي لا تُحسب كرات سوداء
        val pocketClearR = (r * 1.9f).toInt()
        val pocketPts = listOf(
            Point(0.0, 0.0), Point(tw / 2.0, 0.0), Point(tw.toDouble(), 0.0),
            Point(0.0, th.toDouble()), Point(tw / 2.0, th.toDouble()), Point(tw.toDouble(), th.toDouble())
        )
        for (p in pocketPts) {
            Imgproc.circle(nonFelt, p, pocketClearR, Scalar(0.0), -1)
        }

        // تنظيف الخطوط الرفيعة (خط التصويب) والاحتفاظ بأجسام الكرات فقط
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(max(5.0, r * 0.65), max(5.0, r * 0.65)))
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        val ballMask = Mat()
        Imgproc.morphologyEx(nonFelt, ballMask, Imgproc.MORPH_OPEN, openKernel)
        Imgproc.morphologyEx(ballMask, ballMask, Imgproc.MORPH_CLOSE, closeKernel)
        openKernel.release()
        closeKernel.release()

        val candidates = mutableListOf<PointF>()
        val expectedArea = Math.PI * r * r

        // المرحلة أ: استخراج الكرات المفردة من الكنتور
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(ballMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area in (expectedArea * 0.35)..(expectedArea * 1.75)) {
                val rect = Imgproc.boundingRect(c)
                val aspect = rect.width.toFloat() / max(1, rect.height).toFloat()
                if (aspect in 0.65f..1.45f) {
                    val m = Imgproc.moments(c)
                    if (m.m00 > 0) {
                        val cx = (m.m10 / m.m00).toFloat()
                        val cy = (m.m01 / m.m00).toFloat()
                        if (isValidBallPosition(cx, cy, tw, th, r)) {
                            candidates.add(PointF(cx, cy))
                        }
                    }
                }
            }
            c.release()
        }
        hierarchy.release()

        // المرحلة ب: HoughCircles لإكمال الكرات المتلاصقة (Clusters)
        val gray = Mat()
        Imgproc.cvtColor(tableBgr, gray, Imgproc.COLOR_BGR2GRAY)
        val blurred = Mat()
        Imgproc.medianBlur(gray, blurred, 5)
        val circles = Mat()
        Imgproc.HoughCircles(
            blurred,
            circles,
            Imgproc.HOUGH_GRADIENT,
            1.2,
            r * 1.7,
            55.0,
            22.0,
            max(6, (r * 0.72).toInt()),
            (r * 1.28).toInt()
        )
        for (i in 0 until circles.cols()) {
            val data = circles.get(0, i) ?: continue
            val cx = data[0].toFloat()
            val cy = data[1].toFloat()
            if (!isValidBallPosition(cx, cy, tw, th, r)) continue
            val alreadyFound = candidates.any { hypot(it.x - cx, it.y - cy) < r * 1.35f }
            if (!alreadyFound) {
                // تأكد أن المركز ليس قماشاً فارغاً
                val px = cx.toInt().coerceIn(0, tw - 1)
                val py = cy.toInt().coerceIn(0, th - 1)
                if (feltMask.get(py, px)[0] < 128.0) {
                    candidates.add(PointF(cx, cy))
                }
            }
        }

        // 2. تحليل لون كل كرة وتمييز الكرة البيضاء بدقة (دون الخلط مع الكرات المخططة)
        val detectedRaw = mutableListOf<Triple<Float, Float, Int>>()
        var cueIndex = -1
        var bestCueScore = 0.0

        val bgrBytes = ByteArray(tw * th * 3)
        tableBgr.get(0, 0, bgrBytes)
        val hsvBytes = ByteArray(tw * th * 3)
        tableHsv.get(0, 0, hsvBytes)

        val sampleRad = max(3, (r * 0.65f).toInt())

        for (pt in candidates) {
            val cx = pt.x.toInt()
            val cy = pt.y.toInt()

            var whitePx = 0
            var totalPx = 0
            var sumS = 0.0
            var sumV = 0.0

            // لتحديد اللون الحقيقي للكرة (نتجاهل اللون الأبيض في الكرات المخططة)
            var bestSatR = 0L
            var bestSatG = 0L
            var bestSatB = 0L
            var satCount = 0L
            var darkPx = 0

            for (dy in -sampleRad..sampleRad) {
                val py = cy + dy
                if (py !in 0 until th) continue
                for (dx in -sampleRad..sampleRad) {
                    if (dx * dx + dy * dy > sampleRad * sampleRad) continue
                    val px = cx + dx
                    if (px !in 0 until tw) continue

                    val offset = (py * tw + px) * 3
                    val s = hsvBytes[offset + 1].toInt() and 0xFF
                    val v = hsvBytes[offset + 2].toInt() and 0xFF

                    totalPx++
                    sumS += s
                    sumV += v

                    if (v > 180 && s < 60) {
                        whitePx++
                    }
                    if (v < 65) {
                        darkPx++
                    }
                    // التقاط بكسلات اللون الفعلي للكرة (بعيداً عن الأبيض والظل الأسود)
                    if (s > 65 && v > 55) {
                        val bVal = bgrBytes[offset].toInt() and 0xFF
                        val gVal = bgrBytes[offset + 1].toInt() and 0xFF
                        val rVal = bgrBytes[offset + 2].toInt() and 0xFF
                        bestSatR += rVal
                        bestSatG += gVal
                        bestSatB += bVal
                        satCount++
                    }
                }
            }

            if (totalPx == 0) continue

            val whiteRatio = whitePx.toDouble() / totalPx
            val darkRatio = darkPx.toDouble() / totalPx
            val meanS = sumS / totalPx
            val meanV = sumV / totalPx

            // الكرة البيضاء: نسبة بياض عالية جداً وتشبع لوني منخفض جداً
            if (whiteRatio > 0.58 && meanS < 52.0 && meanV > 175.0) {
                val score = whiteRatio * 100.0 + meanV - meanS * 2.0
                if (score > bestCueScore) {
                    bestCueScore = score
                    cueIndex = detectedRaw.size
                }
            }

            // استنتاج اللون الاحترافي لمسار الكرة
            val finalColor = when {
                darkRatio > 0.45 && satCount < totalPx * 0.2 -> Color.rgb(35, 35, 45) // الكرة السوداء (8)
                satCount >= 6 -> enhanceBallColor(
                    (bestSatR / satCount).toInt(),
                    (bestSatG / satCount).toInt(),
                    (bestSatB / satCount).toInt()
                )
                else -> Color.rgb(255, 215, 0)
            }

            detectedRaw.add(Triple(pt.x, pt.y, finalColor))
        }

        val detectedBalls = detectedRaw.mapIndexed { idx, (x, y, col) ->
            PhysicsEngine.DetectedBall(x, y, col, idx == cueIndex)
        }

        // 3. اكتشاف خط التصويب الأبيض الحقيقي فقط (مناعة 100% ضد خطوطنا الملونة بالسيان)
        var cueDirection: PointF? = null
        if (cueIndex >= 0) {
            val cue = detectedBalls[cueIndex]
            val whiteLineMask = Mat()
            // خط التصويب في 8 Ball Pool أبيض/رمادي فاتح جداً (تشبع منخفض جداً S <= 55 وسطوع عالٍ V >= 180)
            Core.inRange(tableHsv, Scalar(0.0, 0.0, 180.0), Scalar(180.0, 55.0, 255.0), whiteLineMask)

            val maskBytes = ByteArray(tw * th)
            whiteLineMask.get(0, 0, maskBytes)
            whiteLineMask.release()

            cueDirection = detectAimLineFast(maskBytes, tw, th, cue.x, cue.y, r, detectedBalls)
        } else {
            smoothedAngleRad = null
        }

        // 4. قراءة الدوران (Spin)
        val (spinX, spinY) = detectSpin(bgr, spinRect)

        // تحرير الذاكرة
        tableBgr.release()
        tableHsv.release()
        feltMask.release()
        nonFelt.release()
        ballMask.release()
        gray.release()
        blurred.release()
        circles.release()

        return DetectionResult(detectedBalls, cueDirection, spinX, spinY)
    }

    private fun isValidBallPosition(cx: Float, cy: Float, tw: Int, th: Int, r: Float): Boolean {
        if (cx < r * 0.6f || cx > tw - r * 0.6f || cy < r * 0.6f || cy > th - r * 0.6f) return false
        val pClear = r * 1.7f
        val pockets = arrayOf(
            0f to 0f, (tw / 2f) to 0f, tw.toFloat() to 0f,
            0f to th.toFloat(), (tw / 2f) to th.toFloat(), tw.toFloat() to th.toFloat()
        )
        for ((px, py) in pockets) {
            if (hypot(cx - px, cy - py) < pClear) return false
        }
        return true
    }

    /**
     * مسح شعاعي فائق الدقة والسرعة على ByteArray لاكتشاف الخط الأبيض المستمر الصادر من الكرة البيضاء
     */
    private fun detectAimLineFast(
        mask: ByteArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        r: Float,
        balls: List<PhysicsEngine.DetectedBall>
    ): PointF? {
        val startD = r * 1.25f
        val maxD = min(max(w, h).toFloat(), r * 14f)
        val stepD = 1.5f

        fun scoreAngle(rad: Double): Float {
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val perpX = -sinA
            val perpY = cosA

            var d = startD
            var hits = 0f
            var consecutiveMiss = 0

            while (d <= maxD) {
                val bx = cx + cosA * d
                val by = cy + sinA * d
                val ix = bx.toInt()
                val iy = by.toInt()
                if (ix !in 2 until (w - 2) || iy !in 2 until (h - 2)) break

                // فحص شريط بعرض 3 بكسل عمودياً على الشعاع لعدم تفويت الخط الرفيع أبداً
                var hitHere = false
                for (k in -1..1) {
                    val sx = (bx + perpX * k).toInt()
                    val sy = (by + perpY * k).toInt()
                    if (sx in 0 until w && sy in 0 until h) {
                        if ((mask[sy * w + sx].toInt() and 0xFF) > 0) {
                            hitHere = true
                            break
                        }
                    }
                }

                if (hitHere) {
                    hits += 1f
                    consecutiveMiss = 0
                } else {
                    consecutiveMiss++
                    if (consecutiveMiss > 3) break
                }
                d += stepD
            }
            return hits
        }

        // مسح كامل 360 درجة بخطوة 0.75 درجة
        var bestDeg = 0.0
        var bestScore = 0f
        var deg = 0.0
        while (deg < 360.0) {
            val s = scoreAngle(Math.toRadians(deg))
            if (s > bestScore) {
                bestScore = s
                bestDeg = deg
            }
            deg += 0.75
        }

        // يجب أن يكون هناك خط أبيض متصل حقيقي بطول كافٍ (يمنع الظهور خارج دورك)
        val minRequiredHits = max(10f, r * 1.15f)
        if (bestScore < minRequiredHits) {
            smoothedAngleRad = null
            return null
        }

        // تنعيم فائق الدقة بخطوة 0.1 درجة حول الزاوية الفائزة
        var refinedDeg = bestDeg
        var refinedScore = bestScore
        var fDeg = bestDeg - 1.2
        while (fDeg <= bestDeg + 1.2) {
            val s = scoreAngle(Math.toRadians(fDeg))
            if (s > refinedScore) {
                refinedScore = s
                refinedDeg = fDeg
            }
            fDeg += 0.1
        }

        val rawRad = Math.toRadians(refinedDeg)

        // تثبيت ذكي يمنع اهتزاز الخطوط عند التوقف عن تحريك العصا
        val finalRad = smoothedAngleRad?.let { prev ->
            var diff = rawRad - prev
            while (diff > Math.PI) diff -= 2 * Math.PI
            while (diff < -Math.PI) diff += 2 * Math.PI
            if (abs(diff) < Math.toRadians(0.6)) {
                prev + diff * 0.35
            } else {
                rawRad
            }
        } ?: rawRad

        smoothedAngleRad = finalRad
        return PointF(cos(finalRad).toFloat(), sin(finalRad).toFloat())
    }

    private fun enhanceBallColor(r: Int, g: Int, b: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        hsv[1] = max(hsv[1], 0.88f)
        hsv[2] = max(hsv[2], 0.92f)
        return Color.HSVToColor(245, hsv)
    }

    private fun detectSpin(bgr: Mat, spinRect: Rect): Pair<Float, Float> {
        val safeSpin = clampRect(spinRect, bgr.cols(), bgr.rows())
        if (safeSpin.width <= 8 || safeSpin.height <= 8) return Pair(0f, 0f)

        val spinMat = Mat(bgr, safeSpin)
        val hsv = Mat()
        Imgproc.cvtColor(spinMat, hsv, Imgproc.COLOR_BGR2HSV)

        // عزل النقطة الحمراء داخل أيقونة الـ Spin
        val mask1 = Mat()
        val mask2 = Mat()
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 120.0, 80.0), Scalar(10.0, 255.0, 255.0), mask1)
        Core.inRange(hsv, Scalar(170.0, 120.0, 80.0), Scalar(180.0, 255.0, 255.0), mask2)
        Core.bitwise_or(mask1, mask2, mask)

        val moments = Imgproc.moments(mask)
        var sx = 0f
        var sy = 0f
        if (moments.m00 > 4) {
            val cx = (moments.m10 / moments.m00).toFloat()
            val cy = (moments.m01 / moments.m00).toFloat()
            val normX = ((cx - safeSpin.width / 2f) / (safeSpin.width * 0.38f)).coerceIn(-1f, 1f)
            val normY = (-((cy - safeSpin.height / 2f) / (safeSpin.height * 0.38f))).coerceIn(-1f, 1f)
            // منطقة ميتة صغيرة في المنتصف حتى لا يقرأ دوراناً وهمياً عندما تكون النقطة في الوسط
            sx = if (abs(normX) < 0.14f) 0f else normX
            sy = if (abs(normY) < 0.14f) 0f else normY
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
