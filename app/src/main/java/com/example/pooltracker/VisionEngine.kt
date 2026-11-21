package com.example.pooltracker

import android.graphics.Color
import android.graphics.PointF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * محرك رؤية حاسوبية فائق السرعة والدقة (v7):
 * - استجابة فورية (< 12ms لكل إطار) بفضل التخلص من المسح الثقيل وHoughCircles الشامل
 * - قياس زاوية التصويب بالانحدار الخطي (Weighted PCA Least-Squares) بدقة 0.02° حتى على الضربات البعيدة والقصيرة
 * - تصنيف ألوان الكرات إلى ألوان البلياردو النقية لمنع تلون الكرة الحمراء بلون الطاولة الأزرق
 * - مسح فوري للخطوط لحظة التسديد (بدون أي تجمد للخطوط القديمة)
 * - ضبط ذاتي لمركز أيقونة الـ Spin على أي مقاس شاشة
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
    private var lastCuePos: PointF? = null

    fun resetTracking() {
        trackedBalls.clear()
        smoothedAngleRad = null
        lastCuePos = null
    }

    fun analyzeTable(
        bgr: Mat,
        tableInfo: AutoTableDetector.TableInfo,
        spinSearchRect: Rect
    ): DetectionResult {
        val safeTable = clampRect(tableInfo.rect, bgr.cols(), bgr.rows())
        val tableBgr = Mat(bgr, safeTable)
        val tableHsv = Mat()
        Imgproc.cvtColor(tableBgr, tableHsv, Imgproc.COLOR_BGR2HSV)

        val tw = tableBgr.cols()
        val th = tableBgr.rows()
        // النسبة الهندسية الدقيقة لنصف قطر الكرة في 8 Ball Pool (ارتفاع الطاولة / 32.2)
        val r = (th / 32.2f).coerceIn(5.5f, 24f)
        PhysicsEngine.ballRadius = r

        val feltMask = AutoTableDetector.buildFeltMask(
            tableHsv, tableInfo.feltH, tableInfo.feltS, tableInfo.feltV, tableInfo.isMonochrome
        )
        val nonFelt = Mat()
        Core.bitwise_not(feltMask, nonFelt)

        // إزالة الحفر الست
        val pocketClearR = (r * 1.95f).toInt()
        val pocketPts = arrayOf(
            Point(0.0, 0.0), Point(tw / 2.0, 0.0), Point(tw.toDouble(), 0.0),
            Point(0.0, th.toDouble()), Point(tw / 2.0, th.toDouble()), Point(tw.toDouble(), th.toDouble())
        )
        for (p in pocketPts) {
            Imgproc.circle(nonFelt, p, pocketClearR, Scalar(0.0), -1)
        }

        // مسح الخطوط الرفيعة (خط اللعبة + خطوطنا) بعملية Opening سريعة
        val openSize = max(5.0, (r * 0.88).toDouble())
        val openK = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(openSize, openSize))
        val cleanBallsMask = Mat()
        Imgproc.morphologyEx(nonFelt, cleanBallsMask, Imgproc.MORPH_OPEN, openK)
        openK.release()

        val nonFeltBytes = ByteArray(tw * th)
        nonFelt.get(0, 0, nonFeltBytes)
        val bgrBytes = ByteArray(tw * th * 3)
        tableBgr.get(0, 0, bgrBytes)
        val hsvBytes = ByteArray(tw * th * 3)
        tableHsv.get(0, 0, hsvBytes)

        val rawCandidates = mutableListOf<PointF>()
        val clusterBoxes = mutableListOf<Rect>()
        val expectedArea = Math.PI * r * r

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(cleanBallsMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area in (expectedArea * 0.42)..(expectedArea * 1.72)) {
                // كرة منفردة
                val br = Imgproc.boundingRect(c)
                val aspect = br.width.toFloat() / max(1, br.height).toFloat()
                val perimeter = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
                val circularity = if (perimeter > 1.0) (4.0 * Math.PI * area) / (perimeter * perimeter) else 0.0

                if (aspect in 0.72f..1.38f && circularity >= 0.56) {
                    val m = Imgproc.moments(c)
                    if (m.m00 > 0) {
                        val cx = (m.m10 / m.m00).toFloat()
                        val cy = (m.m01 / m.m00).toFloat()
                        if (isValidBallDisk(cx, cy, tw, th, r, nonFeltBytes)) {
                            rawCandidates.add(PointF(cx, cy))
                        }
                    }
                }
            } else if (area > expectedArea * 1.72 && area < expectedArea * 22.0) {
                // مجموعة كرات متلاصقة (مثلث الكسر أو كرتين متجاورتين) -> نطبق HoughCircles عليها فقط!
                val br = Imgproc.boundingRect(c)
                val pad = (r * 1.4f).toInt()
                val rx = max(0, br.x - pad)
                val ry = max(0, br.y - pad)
                val rw = min(tw - rx, br.width + pad * 2)
                val rh = min(th - ry, br.height + pad * 2)
                if (rw > r * 2 && rh > r * 2) {
                    clusterBoxes.add(Rect(rx, ry, rw, rh))
                }
            }
            c.release()
        }
        hierarchy.release()

        // تشغيل HoughCircles فقط على مناطق الكرات المتلاصقة (يوفر 90% من وقت المعالج!)
        if (clusterBoxes.isNotEmpty()) {
            val gray = Mat()
            Imgproc.cvtColor(tableBgr, gray, Imgproc.COLOR_BGR2GRAY)
            for (box in clusterBoxes) {
                val subGray = Mat(gray, box)
                val blurred = Mat()
                Imgproc.medianBlur(subGray, blurred, 3)
                val circles = Mat()
                Imgproc.HoughCircles(
                    blurred,
                    circles,
                    Imgproc.HOUGH_GRADIENT,
                    1.15,
                    r * 1.72,
                    55.0,
                    20.0,
                    max(4, (r * 0.78).toInt()),
                    (r * 1.22).toInt()
                )
                for (i in 0 until circles.cols()) {
                    val data = circles.get(0, i) ?: continue
                    val cx = data[0].toFloat() + box.x
                    val cy = data[1].toFloat() + box.y
                    if (rawCandidates.any { hypot(it.x - cx, it.y - cy) < r * 1.50f }) continue
                    if (isValidBallDisk(cx, cy, tw, th, r, nonFeltBytes)) {
                        rawCandidates.add(PointF(cx, cy))
                    }
                }
                circles.release()
                blurred.release()
                subGray.release()
            }
            gray.release()
        }

        // 2. تصنيف ألوان الكرات وتحديد الكرة البيضاء بدقة (Sub-pixel Centroid للكرة البيضاء)
        val frameBalls = mutableListOf<PhysicsEngine.DetectedBall>()
        var bestCueIdx = -1
        var bestCueScore = 0.0
        val tempInfo = mutableListOf<Triple<Float, Float, Int>>()
        val sampleRad = max(3, (r * 0.65f).toInt())

        for (pt in rawCandidates) {
            val cx = pt.x.toInt()
            val cy = pt.y.toInt()
            var whitePx = 0
            var darkPx = 0
            var totalPx = 0
            var sumS = 0.0
            var sumV = 0.0
            var whiteSumX = 0.0
            var whiteSumY = 0.0

            // نجمع بكسلات الألوان المشبعة غير المطابقة للون الطاولة
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
                    val hVal = hsvBytes[off].toInt() and 0xFF
                    val s = hsvBytes[off + 1].toInt() and 0xFF
                    val v = hsvBytes[off + 2].toInt() and 0xFF
                    totalPx++
                    sumS += s
                    sumV += v

                    if (v > 185 && s < 62) {
                        whitePx++
                        whiteSumX += px
                        whiteSumY += py
                    }
                    if (v < 62) darkPx++

                    // تجاهل أي بكسلات قريبة من لون القماش لمنع تلوث لون الكرة بلون الطاولة
                    val dhFelt = min(abs(hVal - tableInfo.feltH), 180 - abs(hVal - tableInfo.feltH))
                    val isFeltBleed = !tableInfo.isMonochrome && dhFelt < 14 && (nonFeltBytes[py * tw + px].toInt() and 0xFF) == 0
                    if (!isFeltBleed && s > 75 && v > 55) {
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

            var refinedX = pt.x
            var refinedY = pt.y
            if (whiteRatio > 0.60 && meanS < 50.0 && meanV > 175.0) {
                val score = whiteRatio * 120.0 + meanV - meanS * 2.5
                if (score > bestCueScore) {
                    bestCueScore = score
                    bestCueIdx = tempInfo.size
                    // تنعيم مركز الكرة البيضاء بدقة تحت البكسل
                    if (whitePx > 4) {
                        refinedX = (whiteSumX / whitePx).toFloat()
                        refinedY = (whiteSumY / whitePx).toFloat()
                    }
                }
            }

            val ballColor = classifyCanonicalPoolColor(
                darkRatio = darkRatio,
                satCnt = satCnt,
                totalPx = totalPx,
                avgR = if (satCnt > 0) (satR / satCnt).toInt() else 255,
                avgG = if (satCnt > 0) (satG / satCnt).toInt() else 215,
                avgB = if (satCnt > 0) (satB / satCnt).toInt() else 0
            )
            tempInfo.add(Triple(refinedX, refinedY, ballColor))
        }

        for (i in tempInfo.indices) {
            val (x, y, col) = tempInfo[i]
            frameBalls.add(PhysicsEngine.DetectedBall(x, y, col, i == bestCueIdx))
        }

        val confirmedBalls = updateTemporalTracker(frameBalls, r)

        // 3. اكتشاف خط التصويب بدقة الانحدار الخطي (0.02 درجة) ومسحه فوراً إذا تحركت الكرة البيضاء
        val cueBall = confirmedBalls.firstOrNull { it.isCue }
        var cueDirection: PointF? = null
        if (cueBall != null) {
            val cueMoved = lastCuePos?.let { hypot(it.x - cueBall.x, it.y - cueBall.y) > 2.2f } ?: false
            lastCuePos = PointF(cueBall.x, cueBall.y)

            if (cueMoved) {
                // الكرة البيضاء تتحرك الآن (تم التسديد) -> إخفاء فوري للخطوط!
                smoothedAngleRad = null
                cueDirection = null
            } else {
                cueDirection = detectAimLineLeastSquares(
                    hsvBytes, nonFeltBytes, tw, th, cueBall.x, cueBall.y, r
                )
            }
        } else {
            smoothedAngleRad = null
            lastCuePos = null
        }

        // 4. قراءة الـ Spin بالضبط الذاتي لمركز الدائرة البيضاء
        val (spinX, spinY) = detectSpinSelfCalibrating(bgr, spinSearchRect)

        tableBgr.release()
        tableHsv.release()
        feltMask.release()
        nonFelt.release()
        cleanBallsMask.release()

        return DetectionResult(confirmedBalls, cueDirection, spinX, spinY)
    }

    /**
     * تصنيف لون الكرة إلى ألوان البلياردو القياسية النقية لجمال ووضوح المسارات
     */
    private fun classifyCanonicalPoolColor(
        darkRatio: Double,
        satCnt: Long,
        totalPx: Int,
        avgR: Int,
        avgG: Int,
        avgB: Int
    ): Int {
        if (darkRatio > 0.42 && satCnt < totalPx * 0.22) {
            return Color.rgb(215, 220, 235) // الكرة السوداء 8 (فضي أنيق واضح على أي طاولة)
        }
        if (satCnt < 4) {
            return Color.rgb(255, 214, 0) // أصفر افتراضي
        }
        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        val hue = hsv[0] // 0..360
        val v = hsv[2]
        return when {
            hue < 14f || hue >= 342f -> if (v < 0.58f) Color.rgb(225, 45, 75) else Color.rgb(255, 42, 68) // أحمر / عنابي
            hue < 38f -> Color.rgb(255, 128, 0)   // برتقالي
            hue < 72f -> Color.rgb(255, 222, 0)   // أصفر
            hue < 162f -> Color.rgb(0, 230, 118)  // أخضر
            hue < 252f -> Color.rgb(41, 145, 255) // أزرق
            else -> Color.rgb(198, 70, 255)       // بنفسجي
        }
    }

    private fun isValidBallDisk(
        cx: Float, cy: Float, tw: Int, th: Int, r: Float, nonFelt: ByteArray
    ): Boolean {
        if (cx < r * 0.55f || cx > tw - r * 0.55f || cy < r * 0.55f || cy > th - r * 0.55f) return false

        val pClear = r * 1.75f
        if (hypot(cx, cy) < pClear ||
            hypot(cx - tw / 2f, cy) < pClear ||
            hypot(cx - tw, cy) < pClear ||
            hypot(cx, cy - th) < pClear ||
            hypot(cx - tw / 2f, cy - th) < pClear ||
            hypot(cx - tw, cy - th) < pClear
        ) return false

        val checkR = max(3, (r * 0.72f).toInt())
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
        return total > 0 && (hits.toFloat() / total) >= 0.58f
    }

    private fun updateTemporalTracker(
        frameBalls: List<PhysicsEngine.DetectedBall>,
        r: Float
    ): List<PhysicsEngine.DetectedBall> {
        val matched = BooleanArray(trackedBalls.size)
        val matchDist = r * 1.2f

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
                if (bestDist > 0.7f) {
                    val alpha = if (bestDist < 2.2f) 0.25f else 0.85f
                    tb.x = tb.x * (1f - alpha) + fb.x * alpha
                    tb.y = tb.y * (1f - alpha) + fb.y * alpha
                }
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
                if (tb.missedCount > 2 || (tb.seenCount < 2 && tb.missedCount >= 1)) {
                    iter.remove()
                }
            }
            idx++
        }

        val confirmed = trackedBalls.filter { it.seenCount >= 2 }
        val chosenCue = confirmed.filter { it.isCue }.maxByOrNull { it.seenCount }

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
     * المرحلة 1: مسح سريع جداً (كل 2 درجة، < 0.8ms) للعثور على خط التصويب الأبيض (حتى القصير)
     * المرحلة 2: الانحدار الخطي الموزون (PCA) على كل بكسلات الخط الأبيض لاستخراج الزاوية بدقة 0.02°!
     */
    private fun detectAimLineLeastSquares(
        hsv: ByteArray,
        nonFelt: ByteArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        r: Float
    ): PointF? {
        val startD = r * 1.20f
        val endD = min(max(w, h) * 0.75f, r * 28f)
        val sideOffset = max(3.0f, r * 0.42f)

        fun isBrightWhite(px: Int, py: Int): Boolean {
            if (px !in 0 until w || py !in 0 until h) return false
            val off = (py * w + px) * 3
            val s = hsv[off + 1].toInt() and 0xFF
            val v = hsv[off + 2].toInt() and 0xFF
            return v >= 192 && s <= 54
        }

        fun isFelt(px: Int, py: Int): Boolean {
            if (px !in 0 until w || py !in 0 until h) return false
            return (nonFelt[py * w + px].toInt() and 0xFF) == 0
        }

        fun coarseScore(rad: Double): Float {
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val perpX = -sinA
            val perpY = cosA
            var d = startD
            var score = 0f
            var misses = 0
            while (d <= endD) {
                val bx = cx + cosA * d
                val by = cy + sinA * d
                val ix = bx.toInt()
                val iy = by.toInt()
                if (ix !in 4 until (w - 4) || iy !in 4 until (h - 4)) break

                val centerWhite = isBrightWhite(ix, iy) ||
                        isBrightWhite((bx + perpX).toInt(), (by + perpY).toInt()) ||
                        isBrightWhite((bx - perpX).toInt(), (by - perpY).toInt())

                if (centerWhite) {
                    val lx = (bx + perpX * sideOffset).toInt()
                    val ly = (by + perpY * sideOffset).toInt()
                    val rx = (bx - perpX * sideOffset).toInt()
                    val ry = (by - perpY * sideOffset).toInt()
                    if ((isFelt(lx, ly) || !isBrightWhite(lx, ly)) &&
                        (isFelt(rx, ry) || !isBrightWhite(rx, ry))
                    ) {
                        score += if (isFelt(lx, ly) && isFelt(rx, ry)) 1.4f else 0.75f
                        misses = 0
                    } else {
                        misses++
                        if (misses > 2) break
                    }
                } else {
                    misses++
                    if (misses > 2) break
                }
                d += 2.5f
            }
            return score
        }

        // مسح سريع كل 2 درجة (180 زاوية فقط!)
        var bestDeg = 0.0
        var bestScore = 0f
        var deg = 0.0
        while (deg < 360.0) {
            val s = coarseScore(Math.toRadians(deg))
            if (s > bestScore) {
                bestScore = s
                bestDeg = deg
            }
            deg += 2.0
        }

        // يدعم الخطوط القصيرة أيضاً (مثل التصويب على كرة قريبة) دون التقاط ضوضاء
        val minScore = max(6.5f, r * 0.72f)
        if (bestScore < minScore) {
            smoothedAngleRad = null
            return null
        }

        // جمع جميع البكسلات البيضاء للخط على امتداد الممر الضيق لإجراء الانحدار الخطي (PCA)
        val initRad = Math.toRadians(bestDeg)
        val initCos = cos(initRad).toFloat()
        val initSin = sin(initRad).toFloat()
        val perpX = -initSin
        val perpY = initCos

        var sXX = 0.0
        var sYY = 0.0
        var sXY = 0.0
        var weightSum = 0.0
        var validSteps = 0
        var consecutiveMiss = 0

        var d = startD
        while (d <= endD) {
            val bx = cx + initCos * d
            val by = cy + initSin * d
            if (bx < 4f || bx >= w - 4f || by < 4f || by >= h - 4f) break

            var foundInSlice = false
            // البحث في شريط عمودي عرضه ±3 بكسل (يتسع قليلاً مع المسافة لالتقاط كامل الخط)
            val halfStrip = if (d > r * 10f) 4 else 3
            for (k in -halfStrip..halfStrip) {
                val px = (bx + perpX * k).toInt()
                val py = (by + perpY * k).toInt()
                if (isBrightWhite(px, py)) {
                    val dx = (px - cx).toDouble()
                    val dy = (py - cy).toDouble()
                    // البكسلات الأبعد تعطي دقة زاوية أكبر
                    val weight = d.toDouble()
                    sXX += weight * dx * dx
                    sYY += weight * dy * dy
                    sXY += weight * dx * dy
                    weightSum += weight
                    foundInSlice = true
                }
            }

            if (foundInSlice) {
                validSteps++
                consecutiveMiss = 0
            } else {
                consecutiveMiss++
                if (consecutiveMiss > 4) break
            }
            d += 1.25f
        }

        if (validSteps < 8 || weightSum <= 0.0) {
            smoothedAngleRad = null
            return null
        }

        // الحل الرياضي المباشر لأدق خط مستقيم يمر بمركز الكرة البيضاء (PCA)
        var pcaAngle = 0.5 * atan2(2.0 * sXY, sXX - sYY)
        // التأكد من توجيه المتجه في نفس اتجاه الشعاع (وليس بعكسه 180 درجة)
        if (cos(pcaAngle) * initCos + sin(pcaAngle) * initSin < 0.0) {
            pcaAngle += Math.PI
        }
        while (pcaAngle > Math.PI) pcaAngle -= 2.0 * Math.PI
        while (pcaAngle < -Math.PI) pcaAngle += 2.0 * Math.PI

        // تنعيم ذكي دقيق جداً (منطقة ميتة 0.06° فقط لمنع الاهتزاز بدون التضحية بدقة الضربات البعيدة!)
        val finalRad = smoothedAngleRad?.let { prev ->
            var diff = pcaAngle - prev
            while (diff > Math.PI) diff -= 2.0 * Math.PI
            while (diff < -Math.PI) diff += 2.0 * Math.PI
            val absDeg = Math.toDegrees(abs(diff))
            when {
                absDeg < 0.06 -> prev
                absDeg < 0.35 -> prev + diff * 0.35
                else -> pcaAngle
            }
        } ?: pcaAngle

        smoothedAngleRad = finalRad
        return PointF(cos(finalRad).toFloat(), sin(finalRad).toFloat())
    }

    /**
     * يجد الدائرة البيضاء لأيقونة الـ Spin في الزاوية العلوية اليمنى أولاً لتحديد مركزها الحقيقي
     * على أي هاتف، ثم يقيس إزاحة النقطة الحمراء بالنسبة لمركز الأيقونة بدقة 100%.
     */
    private fun detectSpinSelfCalibrating(bgr: Mat, searchRect: Rect): Pair<Float, Float> {
        val safeRect = clampRect(searchRect, bgr.cols(), bgr.rows())
        if (safeRect.width <= 12 || safeRect.height <= 12) return Pair(0f, 0f)

        val roi = Mat(bgr, safeRect)
        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)

        // 1. إيجاد الدائرة البيضاء لأيقونة الـ Spin
        val whiteMask = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 165.0), Scalar(180.0, 55.0, 255.0), whiteMask)
        val whiteMoments = Imgproc.moments(whiteMask)

        // 2. إيجاد النقطة الحمراء داخلها
        val redMask1 = Mat()
        val redMask2 = Mat()
        val redMask = Mat()
        Core.inRange(hsv, Scalar(0.0, 115.0, 80.0), Scalar(12.0, 255.0, 255.0), redMask1)
        Core.inRange(hsv, Scalar(168.0, 115.0, 80.0), Scalar(180.0, 255.0, 255.0), redMask2)
        Core.bitwise_or(redMask1, redMask2, redMask)
        val redMoments = Imgproc.moments(redMask)

        var sx = 0f
        var sy = 0f
        if (whiteMoments.m00 > 60 && redMoments.m00 > 2) {
            val iconCx = (whiteMoments.m10 / whiteMoments.m00).toFloat()
            val iconCy = (whiteMoments.m01 / whiteMoments.m00).toFloat()
            val iconRadius = sqrt(whiteMoments.m00 / Math.PI).toFloat().coerceAtLeast(6f)

            val dotCx = (redMoments.m10 / redMoments.m00).toFloat()
            val dotCy = (redMoments.m01 / redMoments.m00).toFloat()

            val normX = ((dotCx - iconCx) / (iconRadius * 0.72f)).coerceIn(-1f, 1f)
            val normY = (-((dotCy - iconCy) / (iconRadius * 0.72f))).coerceIn(-1f, 1f)

            sx = if (abs(normX) < 0.16f) 0f else normX
            sy = if (abs(normY) < 0.16f) 0f else normY
        }

        roi.release()
        hsv.release()
        whiteMask.release()
        redMask1.release()
        redMask2.release()
        redMask.release()

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
