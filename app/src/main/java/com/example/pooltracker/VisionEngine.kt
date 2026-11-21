package com.example.pooltracker

import android.graphics.Color
import android.graphics.PointF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * محرك رؤية حاسوبية مصمم لمنع الكرات الوهمية ومنع تداخل الـ Overlay:
 * 1. التحقق الصارم من امتلاء جسم الكرة (يرفض الخطوط الرفيعة والدوائر الفارغة وشعار الطاولة)
 * 2. متتبع زمني (Temporal Tracker): لا تُقبل الكرة إلا إذا ظهرت بثبات لإطارين متتاليين
 * 3. كاشف خط التصويب الرفيع المحاط بالقماش (Thin White Line Bounded by Felt) بدقة 0.08 درجة
 */
class VisionEngine {

    data class DetectionResult(
        val balls: List<PhysicsEngine.DetectedBall>,
        val cueDirection: PointF?,
        val spinX: Float,
        val spinY: Float
    )

    private class TrackedBall(
        var x: Float,
        var y: Float,
        var color: Int,
        var isCue: Boolean,
        var seenCount: Int = 1,
        var missedCount: Int = 0
    )

    private val trackedBalls = mutableListOf<TrackedBall>()
    private var smoothedAngleRad: Double? = null
    private var lastValidCueDir: PointF? = null
    private var aimLostFrames = 0

    fun resetTracking() {
        trackedBalls.clear()
        smoothedAngleRad = null
        lastValidCueDir = null
        aimLostFrames = 0
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
        // نصف قطر الكرة القياسي في 8 Ball Pool (حوالي th / 31.8)
        val r = (th / 31.8f).coerceIn(6f, 26f)
        PhysicsEngine.ballRadius = r

        // 1. بناء قناع قماش الطاولة (يشمل الشعار وخط الكسر)
        val feltMask = AutoTableDetector.buildFeltMask(
            tableHsv, tableInfo.feltH, tableInfo.feltS, tableInfo.feltV, tableInfo.isMonochrome
        )
        val nonFelt = Mat()
        Core.bitwise_not(feltMask, nonFelt)

        // مسح الحفر الست
        val pocketClearR = (r * 2.0f).toInt()
        val pocketPts = arrayOf(
            Point(0.0, 0.0), Point(tw / 2.0, 0.0), Point(tw.toDouble(), 0.0),
            Point(0.0, th.toDouble()), Point(tw / 2.0, th.toDouble()), Point(tw.toDouble(), th.toDouble())
        )
        for (p in pocketPts) {
            Imgproc.circle(nonFelt, p, pocketClearR, Scalar(0.0), -1)
        }

        // إزالة الخطوط الرفيعة (سواء خط اللعبة أو خطوط الـ Overlay) بعملية Opening بنصف قطر كافٍ
        val openSize = max(5.0, (r * 0.90).toDouble())
        val openK = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(openSize, openSize))
        val cleanBallsMask = Mat()
        Imgproc.morphologyEx(nonFelt, cleanBallsMask, Imgproc.MORPH_OPEN, openK)
        openK.release()

        // استخراج مصفوفات البايت مرة واحدة للسرعة الفائقة
        val nonFeltBytes = ByteArray(tw * th)
        nonFelt.get(0, 0, nonFeltBytes)
        val bgrBytes = ByteArray(tw * th * 3)
        tableBgr.get(0, 0, bgrBytes)
        val hsvBytes = ByteArray(tw * th * 3)
        tableHsv.get(0, 0, hsvBytes)

        val rawCandidates = mutableListOf<PointF>()
        val expectedArea = Math.PI * r * r

        // المرحلة أ: الكرات المنفصلة عبر الكنتور الصارم
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(cleanBallsMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area in (expectedArea * 0.42)..(expectedArea * 1.65)) {
                val br = Imgproc.boundingRect(c)
                val aspect = br.width.toFloat() / max(1, br.height).toFloat()
                // التحقق من الاستدارة (يرفض العصا وأي خطوط أو أشكال مستطيلة)
                val perimeter = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
                val circularity = if (perimeter > 1.0) (4.0 * Math.PI * area) / (perimeter * perimeter) else 0.0

                if (aspect in 0.72f..1.38f && circularity >= 0.58) {
                    val m = Imgproc.moments(c)
                    if (m.m00 > 0) {
                        val cx = (m.m10 / m.m00).toFloat()
                        val cy = (m.m01 / m.m00).toFloat()
                        if (isValidBallDisk(cx, cy, tw, th, r, nonFeltBytes)) {
                            rawCandidates.add(PointF(cx, cy))
                        }
                    }
                }
            }
            c.release()
        }
        hierarchy.release()

        // المرحلة ب: HoughCircles الصارم للكرات المتلاصقة فقط (مقيد بامتلاء القرص غير القماشي)
        val gray = Mat()
        Imgproc.cvtColor(tableBgr, gray, Imgproc.COLOR_BGR2GRAY)
        val blurred = Mat()
        Imgproc.medianBlur(gray, blurred, 3)
        val circles = Mat()
        Imgproc.HoughCircles(
            blurred,
            circles,
            Imgproc.HOUGH_GRADIENT,
            1.2,
            r * 1.82,
            65.0,
            24.0,
            max(5, (r * 0.78).toInt()),
            (r * 1.22).toInt()
        )
        for (i in 0 until circles.cols()) {
            val data = circles.get(0, i) ?: continue
            val cx = data[0].toFloat()
            val cy = data[1].toFloat()
            if (rawCandidates.any { hypot(it.x - cx, it.y - cy) < r * 1.55f }) continue
            if (isValidBallDisk(cx, cy, tw, th, r, nonFeltBytes)) {
                rawCandidates.add(PointF(cx, cy))
            }
        }

        // 2. تصنيف لون كل كرة وتحديد الكرة البيضاء
        val frameBalls = mutableListOf<PhysicsEngine.DetectedBall>()
        var bestCueIdx = -1
        var bestCueScore = 0.0
        val tempInfo = mutableListOf<Triple<Float, Float, Int>>()
        val sampleRad = max(3, (r * 0.62f).toInt())

        for (pt in rawCandidates) {
            val cx = pt.x.toInt()
            val cy = pt.y.toInt()
            var whitePx = 0
            var darkPx = 0
            var totalPx = 0
            var sumS = 0.0
            var sumV = 0.0
            var satR = 0L
            var satG = 0L
            var satB = 0L
            var satCnt = 0L

            for (dy in -sampleRad..sampleRad) {
                val py = cy + dy
                if (py !in 0 until th) continue
                for (dx in -sampleRad..sampleRad) {
                    if (dx * dx + dy * dy > sampleRad * sampleRad) continue
                    val px = cx + dx
                    if (px !in 0 until tw) continue

                    val off = (py * tw + px) * 3
                    val s = hsvBytes[off + 1].toInt() and 0xFF
                    val v = hsvBytes[off + 2].toInt() and 0xFF
                    totalPx++
                    sumS += s
                    sumV += v

                    if (v > 185 && s < 58) whitePx++
                    if (v < 65) darkPx++
                    if (s > 72 && v > 55) {
                        satB += bgrBytes[off].toInt() and 0xFF
                        satG += bgrBytes[off + 1].toInt() and 0xFF
                        satR += bgrBytes[off + 2].toInt() and 0xFF
                        satCnt++
                    }
                }
            }
            if (totalPx == 0) continue

            val whiteRatio = whitePx.toDouble() / totalPx
            val darkRatio = darkPx.toDouble() / totalPx
            val meanS = sumS / totalPx
            val meanV = sumV / totalPx

            if (whiteRatio > 0.62 && meanS < 48.0 && meanV > 175.0) {
                val score = whiteRatio * 120.0 + meanV - meanS * 2.5
                if (score > bestCueScore) {
                    bestCueScore = score
                    bestCueIdx = tempInfo.size
                }
            }

            val ballColor = when {
                darkRatio > 0.45 && satCnt < totalPx * 0.22 -> Color.rgb(175, 175, 195) // الكرة 8 السوداء (لون فضي واضح فوق أي طاولة)
                satCnt >= 5 -> enhanceBallColor(
                    (satR / satCnt).toInt(),
                    (satG / satCnt).toInt(),
                    (satB / satCnt).toInt()
                )
                else -> Color.rgb(255, 215, 0)
            }
            tempInfo.add(Triple(pt.x, pt.y, ballColor))
        }

        for (i in tempInfo.indices) {
            val (x, y, col) = tempInfo[i]
            frameBalls.add(PhysicsEngine.DetectedBall(x, y, col, i == bestCueIdx))
        }

        // 3. التتبع الزمني وتنقية الكرات الوهمية
        val confirmedBalls = updateTemporalTracker(frameBalls, r)

        // 4. اكتشاف خط التصويب الرفيع الأبيض المحاط بالقماش (خارج منطقة رسم خطوطنا تماماً)
        val cueBall = confirmedBalls.firstOrNull { it.isCue }
        var cueDirection: PointF? = null
        if (cueBall != null) {
            val detectedDir = detectThinWhiteAimLine(
                hsvBytes, nonFeltBytes, tw, th, cueBall.x, cueBall.y, r
            )
            if (detectedDir != null) {
                cueDirection = detectedDir
                lastValidCueDir = detectedDir
                aimLostFrames = 0
            } else {
                aimLostFrames++
                // الاحتفاظ بالاتجاه لـ 2 إطار فقط لتفادي الوميض اللحظي دون تأخير الاختفاء عند التسديد
                if (aimLostFrames <= 2 && lastValidCueDir != null) {
                    cueDirection = lastValidCueDir
                } else {
                    smoothedAngleRad = null
                    lastValidCueDir = null
                }
            }
        } else {
            smoothedAngleRad = null
            lastValidCueDir = null
        }

        // 5. قراءة الدوران (Spin)
        val (spinX, spinY) = detectSpin(bgr, spinRect)

        // تحرير الذاكرة
        tableBgr.release()
        tableHsv.release()
        feltMask.release()
        nonFelt.release()
        cleanBallsMask.release()
        gray.release()
        blurred.release()
        circles.release()

        return DetectionResult(confirmedBalls, cueDirection, spinX, spinY)
    }

    /**
     * يتحقق من أن القرص المكتشف هو كرة حقيقية ممتلئة وليست خطاً أو حلقة أو نقطة على الحافة
     */
    private fun isValidBallDisk(
        cx: Float, cy: Float, tw: Int, th: Int, r: Float, nonFelt: ByteArray
    ): Boolean {
        if (cx < r * 0.7f || cx > tw - r * 0.7f || cy < r * 0.7f || cy > th - r * 0.7f) return false

        // تجاهل مناطق الحفر الست
        val pClear = r * 1.85f
        if (hypot(cx, cy) < pClear ||
            hypot(cx - tw / 2f, cy) < pClear ||
            hypot(cx - tw, cy) < pClear ||
            hypot(cx, cy - th) < pClear ||
            hypot(cx - tw / 2f, cy - th) < pClear ||
            hypot(cx - tw, cy - th) < pClear
        ) return false

        // فحص امتلاء القرص الداخلي (نصف قطر 0.75 * r) ببكسلات غير القماش
        val checkR = max(3, (r * 0.75f).toInt())
        val ix = cx.toInt()
        val iy = cy.toInt()
        var hits = 0
        var total = 0
        for (dy in -checkR..checkR) {
            val py = iy + dy
            if (py !in 0 until th) continue
            for (dx in -checkR..checkR) {
                if (dx * dx + dy * dy > checkR * checkR) continue
                val px = ix + dx
                if (px !in 0 until tw) continue
                total++
                if ((nonFelt[py * tw + px].toInt() and 0xFF) > 0) hits++
            }
        }
        // الكرة الحقيقية ممتلئة بنسبة > 62%، بينما الخطوط الرفيعة والشعارات الباهتة أقل بكثير
        return total > 0 && (hits.toFloat() / total) >= 0.62f
    }

    /**
     * متتبع زمني يمنع تماماً ظهور كرات وهمية ويثبت مواقع الكرات الحقيقية بسلاسة
     */
    private fun updateTemporalTracker(
        frameBalls: List<PhysicsEngine.DetectedBall>,
        r: Float
    ): List<PhysicsEngine.DetectedBall> {
        val matched = BooleanArray(trackedBalls.size)
        val matchDist = r * 1.15f

        for (fb in frameBalls) {
            var bestIdx = -1
            var bestDist = matchDist
            for (i in trackedBalls.indices) {
                if (matched[i]) continue
                val d = hypot(trackedBalls[i].x - fb.x, trackedBalls[i].y - fb.y)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            if (bestIdx >= 0) {
                matched[bestIdx] = true
                val tb = trackedBalls[bestIdx]
                // تنعيم ذكي: ثابتة تماماً عند السكون وسريعة عند الحركة
                val alpha = if (bestDist < 2.0f) 0.25f else 0.75f
                tb.x = tb.x * (1f - alpha) + fb.x * alpha
                tb.y = tb.y * (1f - alpha) + fb.y * alpha
                tb.color = fb.color
                tb.isCue = fb.isCue
                tb.seenCount = min(tb.seenCount + 1, 20)
                tb.missedCount = 0
            } else {
                trackedBalls.add(TrackedBall(fb.x, fb.y, fb.color, fb.isCue))
            }
        }

        val iter = trackedBalls.iterator()
        var idx = 0
        while (iter.hasNext()) {
            val tb = iter.next()
            if (!matched[idx]) {
                tb.missedCount++
                if (tb.missedCount > 3 || (tb.seenCount < 2 && tb.missedCount >= 1)) {
                    iter.remove()
                }
            }
            idx++
        }

        // التأكد من وجود كرة بيضاء واحدة فقط
        val confirmed = trackedBalls.filter { it.seenCount >= 2 }
        val cueCandidates = confirmed.filter { it.isCue }
        val chosenCue = cueCandidates.maxByOrNull { it.seenCount }

        return confirmed.map { tb ->
            PhysicsEngine.DetectedBall(
                x = tb.x,
                y = tb.y,
                color = tb.color,
                isCue = (tb === chosenCue)
            )
        }
    }

    /**
     * يكتشف خط التصويب الأبيض الرفيع المحاط بالقماش على جانبيه (مناعة تامة ضد العصا وخطوط الـ Overlay)
     */
    private fun detectThinWhiteAimLine(
        hsv: ByteArray,
        nonFelt: ByteArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        r: Float
    ): PointF? {
        val startD = r * 1.25f
        val endD = min(max(w, h).toFloat(), r * 8.5f)
        val stepD = 1.25f
        val sideOffset = max(3.2f, r * 0.42f)

        fun isBrightWhite(px: Int, py: Int): Boolean {
            if (px !in 0 until w || py !in 0 until h) return false
            val off = (py * w + px) * 3
            val s = hsv[off + 1].toInt() and 0xFF
            val v = hsv[off + 2].toInt() and 0xFF
            return v >= 188 && s <= 56
        }

        fun isFelt(px: Int, py: Int): Boolean {
            if (px !in 0 until w || py !in 0 until h) return false
            return (nonFelt[py * w + px].toInt() and 0xFF) == 0
        }

        fun scoreAngle(rad: Double): Float {
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val perpX = -sinA
            val perpY = cosA

            var d = startD
            var score = 0f
            var missStreak = 0

            while (d <= endD) {
                val bx = cx + cosA * d
                val by = cy + sinA * d
                val ix = bx.toInt()
                val iy = by.toInt()
                if (ix !in 4 until (w - 4) || iy !in 4 until (h - 4)) break

                // فحص وجود خط أبيض في المركز (عرض ±1 بكسل)
                val centerWhite = isBrightWhite(ix, iy) ||
                        isBrightWhite((bx + perpX).toInt(), (by + perpY).toInt()) ||
                        isBrightWhite((bx - perpX).toInt(), (by - perpY).toInt())

                if (centerWhite) {
                    // التحقق من أن جانبي الخط قماش نظيف (لضمان أنه خط رفيع وليس عصا أو كرة!)
                    val lx = (bx + perpX * sideOffset).toInt()
                    val ly = (by + perpY * sideOffset).toInt()
                    val rx = (bx - perpX * sideOffset).toInt()
                    val ry = (by - perpY * sideOffset).toInt()
                    val leftClean = isFelt(lx, ly) || !isBrightWhite(lx, ly)
                    val rightClean = isFelt(rx, ry) || !isBrightWhite(rx, ry)

                    if (leftClean && rightClean) {
                        score += if (isFelt(lx, ly) && isFelt(rx, ry)) 1.35f else 0.75f
                        missStreak = 0
                    } else {
                        // جسم أبيض عريض (ليس خط التصويب الرفيع)
                        missStreak++
                        if (missStreak > 2) break
                    }
                } else {
                    missStreak++
                    if (missStreak > 3) break
                }
                d += stepD
            }
            return score
        }

        // مسح أولي 360 درجة بخطوة 1.0 درجة
        var bestDeg = 0.0
        var bestScore = 0f
        var deg = 0.0
        while (deg < 360.0) {
            val s = scoreAngle(Math.toRadians(deg))
            if (s > bestScore) {
                bestScore = s
                bestDeg = deg
            }
            deg += 1.0
        }

        // يجب أن يكون خط التصويب طويلاً وواضحاً بما يكفي (يمنع المسارات العشوائية تماماً)
        val minRequiredScore = max(12f, r * 1.45f)
        if (bestScore < minRequiredScore) {
            return null
        }

        // تنعيم فائق الدقة بخطوة 0.08 درجة حول الزاوية الفائزة
        var refinedDeg = bestDeg
        var refinedScore = bestScore
        var fDeg = bestDeg - 1.4
        while (fDeg <= bestDeg + 1.4) {
            val s = scoreAngle(Math.toRadians(fDeg))
            if (s > refinedScore) {
                refinedScore = s
                refinedDeg = fDeg
            }
            fDeg += 0.08
        }

        val rawRad = Math.toRadians(refinedDeg)

        // تثبيت تكيفي ذكي: يمنع أي اهتزاز عند التوقف ويستجيب فوراً للتحريك
        val finalRad = smoothedAngleRad?.let { prev ->
            var diff = rawRad - prev
            while (diff > Math.PI) diff -= 2 * Math.PI
            while (diff < -Math.PI) diff += 2 * Math.PI
            when {
                abs(diff) < Math.toRadians(0.22) -> prev + diff * 0.25
                abs(diff) < Math.toRadians(1.2) -> prev + diff * 0.55
                else -> rawRad
            }
        } ?: rawRad

        smoothedAngleRad = finalRad
        return PointF(cos(finalRad).toFloat(), sin(finalRad).toFloat())
    }

    private fun enhanceBallColor(r: Int, g: Int, b: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        hsv[1] = max(hsv[1], 0.90f)
        hsv[2] = max(hsv[2], 0.95f)
        return Color.HSVToColor(235, hsv)
    }

    private fun detectSpin(bgr: Mat, spinRect: Rect): Pair<Float, Float> {
        val safeSpin = clampRect(spinRect, bgr.cols(), bgr.rows())
        if (safeSpin.width <= 8 || safeSpin.height <= 8) return Pair(0f, 0f)

        val spinMat = Mat(bgr, safeSpin)
        val hsv = Mat()
        Imgproc.cvtColor(spinMat, hsv, Imgproc.COLOR_BGR2HSV)

        val mask1 = Mat()
        val mask2 = Mat()
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 125.0, 80.0), Scalar(10.0, 255.0, 255.0), mask1)
        Core.inRange(hsv, Scalar(170.0, 125.0, 80.0), Scalar(180.0, 255.0, 255.0), mask2)
        Core.bitwise_or(mask1, mask2, mask)

        val moments = Imgproc.moments(mask)
        var sx = 0f
        var sy = 0f
        if (moments.m00 > 3) {
            val cx = (moments.m10 / moments.m00).toFloat()
            val cy = (moments.m01 / moments.m00).toFloat()
            val normX = ((cx - safeSpin.width / 2f) / (safeSpin.width * 0.36f)).coerceIn(-1f, 1f)
            val normY = (-((cy - safeSpin.height / 2f) / (safeSpin.height * 0.36f))).coerceIn(-1f, 1f)
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
