package com.example.pooltracker

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AutoTableDetector {

    data class TableInfo(
        val rect: Rect,
        val feltH: Int,
        val feltS: Int,
        val feltV: Int,
        val isMonochrome: Boolean
    )

    private var lockedTable: TableInfo? = null
    private var candidateTable: TableInfo? = null
    private var candidateStreak = 0
    private var framesSinceVerify = 0
    private var verifyFailStreak = 0

    fun reset() {
        lockedTable = null
        candidateTable = null
        candidateStreak = 0
        framesSinceVerify = 0
        verifyFailStreak = 0
    }

    fun getOrDetect(bgr: Mat): TableInfo? {
        val w = bgr.cols()
        val h = bgr.rows()
        if (w <= h || w < 320) {
            reset()
            return null
        }

        val current = lockedTable
        if (current != null) {
            framesSinceVerify++
            if (framesSinceVerify < 20) {
                return current
            }
            framesSinceVerify = 0
            if (verifyLockedTable(bgr, current)) {
                verifyFailStreak = 0
                return current
            } else {
                verifyFailStreak++
                if (verifyFailStreak >= 3) {
                    reset()
                    return null
                }
                return current
            }
        }

        val detected = detectFresh(bgr) ?: run {
            candidateStreak = 0
            candidateTable = null
            return null
        }

        val prevCand = candidateTable
        if (prevCand != null &&
            abs(prevCand.rect.x - detected.rect.x) <= 14 &&
            abs(prevCand.rect.y - detected.rect.y) <= 14 &&
            abs(prevCand.rect.width - detected.rect.width) <= 18 &&
            abs(prevCand.rect.height - detected.rect.height) <= 18
        ) {
            candidateStreak++
            val merged = Rect(
                (prevCand.rect.x + detected.rect.x) / 2,
                (prevCand.rect.y + detected.rect.y) / 2,
                (prevCand.rect.width + detected.rect.width) / 2,
                (prevCand.rect.height + detected.rect.height) / 2
            )
            candidateTable = detected.copy(rect = merged)
            if (candidateStreak >= 2) {
                lockedTable = candidateTable
                verifyFailStreak = 0
                framesSinceVerify = 0
                return lockedTable
            }
        } else {
            candidateTable = detected
            candidateStreak = 1
        }
        return null
    }

    private fun verifyLockedTable(bgr: Mat, info: TableInfo): Boolean {
        val r = info.rect
        if (r.x < 0 || r.y < 0 || r.x + r.width > bgr.cols() || r.y + r.height > bgr.rows()) {
            return false
        }
        val roi = Mat(bgr, r)
        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = buildFeltMask(hsv, info.feltH, info.feltS, info.feltV, info.isMonochrome)
        val feltPixels = Core.countNonZero(mask)
        val totalPixels = r.width * r.height
        roi.release()
        hsv.release()
        mask.release()

        return totalPixels > 0 && (feltPixels.toDouble() / totalPixels) > 0.42
    }

    private fun detectFresh(bgr: Mat): TableInfo? {
        val sw = bgr.cols()
        val sh = bgr.rows()

        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

        val sx = (sw * 0.25).toInt()
        val sy = (sh * 0.30).toInt()
        val sampleW = (sw * 0.50).toInt().coerceAtLeast(16)
        val sampleH = (sh * 0.48).toInt().coerceAtLeast(16)
        val sampleMat = Mat(hsv, Rect(sx, sy, sampleW, sampleH))

        val bytes = ByteArray(sampleW * sampleH * 3)
        sampleMat.get(0, 0, bytes)
        sampleMat.release()

        val hueHist = IntArray(90)
        val valHist = IntArray(64)
        var coloredCount = 0
        var monoCount = 0

        var idx = 0
        while (idx + 2 < bytes.size) {
            val h = bytes[idx].toInt() and 0xFF
            val s = bytes[idx + 1].toInt() and 0xFF
            val v = bytes[idx + 2].toInt() and 0xFF
            if (v in 28..240) {
                if (s >= 30) {
                    hueHist[(h / 2).coerceIn(0, 89)]++
                    coloredCount++
                } else if (v <= 185) {
                    valHist[(v / 4).coerceIn(0, 63)]++
                    monoCount++
                }
            }
            idx += 6
        }

        val isMono = coloredCount < monoCount * 0.65 && monoCount > 250
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
            if (maxCount < 180) {
                hsv.release()
                return null
            }
            peakH = maxBin * 2 + 1

            var sumS = 0L
            var sumV = 0L
            var cnt = 0L
            idx = 0
            while (idx + 2 < bytes.size) {
                val h = bytes[idx].toInt() and 0xFF
                val s = bytes[idx + 1].toInt() and 0xFF
                val v = bytes[idx + 2].toInt() and 0xFF
                val dh = min(abs(h - peakH), 180 - abs(h - peakH))
                if (dh <= 12 && s >= 28 && v in 28..240) {
                    sumS += s
                    sumV += v
                    cnt++
                }
                idx += 6
            }
            if (cnt < 120) {
                hsv.release()
                return null
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
            peakS = 16
        }

        val feltMask = buildFeltMask(hsv, peakH, peakS, peakV, isMono)
        hsv.release()

        val closeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(21.0, 21.0))
        val openK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(feltMask, feltMask, Imgproc.MORPH_CLOSE, closeK)
        Imgproc.morphologyEx(feltMask, feltMask, Imgproc.MORPH_OPEN, openK)
        closeK.release()
        openK.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(feltMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val screenArea = (sw * sh).toDouble()
        var bestRect: Rect? = null
        var bestScore = 0.0

        for (c in contours) {
            val cArea = Imgproc.contourArea(c)
            if (cArea < screenArea * 0.20 || cArea > screenArea * 0.78) continue

            val r = Imgproc.boundingRect(c)
            val aspect = r.width.toDouble() / max(1, r.height).toDouble()
            if (aspect < 1.72 || aspect > 2.28) continue

            val fillRatio = cArea / (r.width * r.height).toDouble()
            if (fillRatio < 0.78) continue

            val centerX = r.x + r.width / 2.0
            if (abs(centerX - sw / 2.0) / sw > 0.11) continue
            if (r.y < sh * 0.11 || r.y > sh * 0.36) continue

            val aspectScore = 1.0 - min(1.0, abs(aspect - 2.0) / 0.4)
            val score = fillRatio * 0.55 + aspectScore * 0.35 + (cArea / screenArea) * 0.10
            if (score > bestScore) {
                bestScore = score
                bestRect = r
            }
        }

        for (c in contours) c.release()
        hierarchy.release()
        feltMask.release()

        val found = bestRect ?: return null

        // تقليص دقيق لإزالة شطفة الوسادة الجانبية والوقوف على خط الارتداد الداخلي بالضبط
        val insetX = (found.width * 0.022).toInt()
        val insetY = (found.height * 0.036).toInt()
        val finalRect = Rect(
            (found.x + insetX).coerceIn(0, sw - 20),
            (found.y + insetY).coerceIn(0, sh - 20),
            (found.width - insetX * 2).coerceIn(20, sw),
            (found.height - insetY * 2).coerceIn(20, sh)
        )

        return TableInfo(finalRect, peakH, peakS, peakV, isMono)
    }

    companion object {
        fun buildFeltMask(hsv: Mat, peakH: Int, peakS: Int, peakV: Int, isMono: Boolean): Mat {
            val mask = Mat()
            if (isMono) {
                val lowV = max(0, peakV - 42).toDouble()
                val highV = min(230, peakV + 42).toDouble()
                Core.inRange(hsv, Scalar(0.0, 0.0, lowV), Scalar(180.0, 58.0, highV), mask)
            } else {
                val hTol = 16
                val lowS = max(20, peakS - 85).toDouble()
                val highS = min(255, peakS + 85).toDouble()
                val lowV = max(25, peakV - 95).toDouble()
                val highV = min(240, peakV + 75).toDouble()

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
