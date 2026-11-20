package com.example.pooltracker

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * كاشف تلقائي ذكي لحدود الطاولة الداخلية يعمل على أي جهاز وأي لون طاولة
 * يمنع ظهور أي مسارات عشوائية في القوائم أو عند انتهاء الجولة.
 */
class AutoTableDetector {

    data class TableInfo(
        val rect: Rect,
        val feltH: Int,
        val feltS: Int,
        val feltV: Int,
        val isMonochrome: Boolean
    )

    private var lockedTable: TableInfo? = null
    private var missedFrames = 0

    fun reset() {
        lockedTable = null
        missedFrames = 0
    }

    fun detect(bgr: Mat): TableInfo? {
        val fullW = bgr.cols()
        val fullH = bgr.rows()
        if (fullW <= fullH || fullW < 400) {
            reset()
            return null
        }

        // تصغير الصورة إلى النصف لتسريع الكشف الفوري
        val scale = 0.5
        val smallBgr = Mat()
        Imgproc.resize(bgr, smallBgr, Size(fullW * scale, fullH * scale), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val hsv = Mat()
        Imgproc.cvtColor(smallBgr, hsv, Imgproc.COLOR_BGR2HSV)
        val sw = hsv.cols()
        val sh = hsv.rows()

        // 1. أخذ عينة من وسط الشاشة لاكتشاف اللون السائد لقماش الطاولة (Felt)
        val sampleX = (sw * 0.28).toInt()
        val sampleY = (sh * 0.34).toInt()
        val sampleW = (sw * 0.44).toInt().coerceAtLeast(10)
        val sampleH = (sh * 0.46).toInt().coerceAtLeast(10)
        val sampleMat = Mat(hsv, Rect(sampleX, sampleY, sampleW, sampleH))

        val sampleBytes = ByteArray(sampleW * sampleH * 3)
        sampleMat.get(0, 0, sampleBytes)
        sampleMat.release()

        val hueHist = IntArray(90) // دقة 2 درجة لكل خانة (0..180)
        val valHist = IntArray(64) // دقة 4 وحدات للرمادي/الأسود
        var coloredCount = 0
        var monoCount = 0

        var idx = 0
        while (idx + 2 < sampleBytes.size) {
            val h = sampleBytes[idx].toInt() and 0xFF
            val s = sampleBytes[idx + 1].toInt() and 0xFF
            val v = sampleBytes[idx + 2].toInt() and 0xFF
            if (v in 30..240) {
                if (s >= 32) {
                    val bin = (h / 2).coerceIn(0, 89)
                    hueHist[bin]++
                    coloredCount++
                } else if (v <= 190) {
                    val vBin = (v / 4).coerceIn(0, 63)
                    valHist[vBin]++
                    monoCount++
                }
            }
            idx += 6 // خطوة بكسلين للسرعة
        }

        val isMono = coloredCount < monoCount * 0.65 && monoCount > 200
        var peakH = 0
        var peakS = 0
        var peakV = 0

        if (!isMono) {
            var maxBin = 0
            var maxCount = 0
            for (b in 0 until 90) {
                if (hueHist[b] > maxCount) {
                    maxCount = hueHist[b]
                    maxBin = b
                }
            }
            if (maxCount < 150) {
                smallBgr.release()
                hsv.release()
                return handleMiss()
            }
            peakH = maxBin * 2 + 1

            // حساب متوسط S و V للون السائد
            var sumS = 0L
            var sumV = 0L
            var cnt = 0L
            idx = 0
            while (idx + 2 < sampleBytes.size) {
                val h = sampleBytes[idx].toInt() and 0xFF
                val s = sampleBytes[idx + 1].toInt() and 0xFF
                val v = sampleBytes[idx + 2].toInt() and 0xFF
                val dh = min(abs(h - peakH), 180 - abs(h - peakH))
                if (dh <= 12 && s >= 30 && v in 30..240) {
                    sumS += s
                    sumV += v
                    cnt++
                }
                idx += 6
            }
            if (cnt < 100) {
                smallBgr.release()
                hsv.release()
                return handleMiss()
            }
            peakS = (sumS / cnt).toInt()
            peakV = (sumV / cnt).toInt()
        } else {
            var maxVBin = 0
            var maxVCount = 0
            for (b in 0 until 64) {
                if (valHist[b] > maxVCount) {
                    maxVCount = valHist[b]
                    maxVBin = b
                }
            }
            peakV = maxVBin * 4 + 2
            peakS = 15
        }

        // 2. إنشاء قناع لون الطاولة (Felt Mask)
        val feltMask = buildFeltMask(hsv, peakH, peakS, peakV, isMono)

        // سد الفراغات مكان الكرات وشعار الطاولة في المنتصف
        val closeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(19.0, 19.0))
        val openK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(feltMask, feltMask, Imgproc.MORPH_CLOSE, closeK)
        Imgproc.morphologyEx(feltMask, feltMask, Imgproc.MORPH_OPEN, openK)
        closeK.release()
        openK.release()

        // 3. العثور على أكبر مستطيل يطابق نسب طاولة 8 Ball Pool (حوالي 2:1)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(feltMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val screenArea = (sw * sh).toDouble()
        var bestRect: Rect? = null
        var bestScore = 0.0

        for (contour in contours) {
            val cArea = Imgproc.contourArea(contour)
            if (cArea < screenArea * 0.18 || cArea > screenArea * 0.78) continue

            val r = Imgproc.boundingRect(contour)
            val aspect = r.width.toDouble() / max(1, r.height).toDouble()
            // نسبة أبعاد منطقة اللعب الداخلية في 8 Ball Pool هي تقريباً 2.0 (بين 1.70 و 2.30)
            if (aspect < 1.65 || aspect > 2.35) continue

            val rectArea = (r.width * r.height).toDouble()
            val fillRatio = cArea / rectArea
            if (fillRatio < 0.72) continue

            // التأكد أن الطاولة متمركزة أفقياً تقريباً وموقعها العمودي أسفل شريط اللاعبين
            val centerX = r.x + r.width / 2.0
            val centerDiff = abs(centerX - sw / 2.0) / sw.toDouble()
            if (centerDiff > 0.12) continue
            if (r.y < sh * 0.10 || r.y > sh * 0.38) continue

            val aspectScore = 1.0 - min(1.0, abs(aspect - 2.0) / 0.5)
            val score = fillRatio * 0.5 + aspectScore * 0.35 + (cArea / screenArea) * 0.15
            if (score > bestScore) {
                bestScore = score
                bestRect = r
            }
        }

        for (c in contours) c.release()
        hierarchy.release()
        feltMask.release()
        smallBgr.release()
        hsv.release()

        if (bestRect == null) {
            return handleMiss()
        }

        // تحويل الإحداثيات للحجم الكامل مع تقليص طفيف (1.2%) لضبط حافة الارتداد الداخلية بدقة
        val invScale = 1.0 / scale
        val rawX = (bestRect.x * invScale).toInt()
        val rawY = (bestRect.y * invScale).toInt()
        val rawW = (bestRect.width * invScale).toInt()
        val rawH = (bestRect.height * invScale).toInt()

        val insetX = (rawW * 0.008).toInt()
        val insetY = (rawH * 0.012).toInt()
        val finalRect = Rect(
            (rawX + insetX).coerceIn(0, fullW - 10),
            (rawY + insetY).coerceIn(0, fullH - 10),
            (rawW - insetX * 2).coerceIn(10, fullW),
            (rawH - insetY * 2).coerceIn(10, fullH)
        )

        missedFrames = 0

        // تنعيم وتثبيت إطار الطاولة لمنع أي اهتزاز
        val prev = lockedTable
        val stabilizedRect = if (prev != null &&
            abs(prev.rect.x - finalRect.x) < 28 &&
            abs(prev.rect.y - finalRect.y) < 28 &&
            abs(prev.rect.width - finalRect.width) < 35 &&
            abs(prev.rect.height - finalRect.height) < 35
        ) {
            Rect(
                (prev.rect.x * 0.85 + finalRect.x * 0.15).toInt(),
                (prev.rect.y * 0.85 + finalRect.y * 0.15).toInt(),
                (prev.rect.width * 0.85 + finalRect.width * 0.15).toInt(),
                (prev.rect.height * 0.85 + finalRect.height * 0.15).toInt()
            )
        } else {
            finalRect
        }

        val info = TableInfo(stabilizedRect, peakH, peakS, peakV, isMono)
        lockedTable = info
        return info
    }

    private fun handleMiss(): TableInfo? {
        missedFrames++
        if (missedFrames > 6) {
            lockedTable = null
            return null
        }
        return lockedTable
    }

    companion object {
        fun buildFeltMask(hsv: Mat, peakH: Int, peakS: Int, peakV: Int, isMono: Boolean): Mat {
            val mask = Mat()
            if (isMono) {
                val lowV = max(0, peakV - 38).toDouble()
                val highV = min(255, peakV + 38).toDouble()
                Core.inRange(hsv, Scalar(0.0, 0.0, lowV), Scalar(180.0, 60.0, highV), mask)
            } else {
                val hTol = 16
                val lowS = max(22, peakS - 75).toDouble()
                val highS = min(255, peakS + 75).toDouble()
                val lowV = max(25, peakV - 75).toDouble()
                val highV = min(255, peakV + 75).toDouble()

                val lowH = peakH - hTol
                val highH = peakH + hTol
                if (lowH >= 0 && highH <= 180) {
                    Core.inRange(
                        hsv,
                        Scalar(lowH.toDouble(), lowS, lowV),
                        Scalar(highH.toDouble(), highS, highV),
                        mask
                    )
                } else {
                    val m1 = Mat()
                    val m2 = Mat()
                    val wrapLow = (lowH + 180) % 180
                    val wrapHigh = highH % 180
                    Core.inRange(hsv, Scalar(wrapLow.toDouble(), lowS, lowV), Scalar(180.0, highS, highV), m1)
                    Core.inRange(hsv, Scalar(0.0, lowS, lowV), Scalar(wrapHigh.toDouble(), highS, highV), m2)
                    Core.bitwise_or(m1, m2, mask)
                    m1.release()
                    m2.release()
                }
            }
            return mask
        }
    }
}
