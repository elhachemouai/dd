package com.example.pooltracker

import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * محرك فيزياء ثنائي الأبعاد يحاكي التصادمات المتسلسلة (كرة تضرب كرة تضرب كرة أخرى)
 * مع مراعاة الارتداد عن الحواف، الحفر الست، والدوران (Top / Back / Side Spin).
 */
object PhysicsEngine {
    var ballRadius: Float = 18f

    data class DetectedBall(
        val x: Float,
        val y: Float,
        val color: Int,
        val isCue: Boolean
    )

    data class BallTrajectory(
        val color: Int,
        val isCue: Boolean,
        val points: List<PointF>
    )

    private class SimBall(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        val isCue: Boolean,
        var spinX: Float = 0f,
        var spinY: Float = 0f,
        var inPocket: Boolean = false,
        var hitFirstBall: Boolean = false,
        val path: MutableList<PointF> = mutableListOf()
    )

    fun simulateAllPaths(
        balls: List<DetectedBall>,
        cueDirection: PointF,
        tableWidth: Float,
        tableHeight: Float,
        spinX: Float = 0f,
        spinY: Float = 0f
    ): List<BallTrajectory> {
        val dirLen = hypot(cueDirection.x, cueDirection.y)
        if (dirLen < 0.001f) return emptyList()

        val nx = cueDirection.x / dirLen
        val ny = cueDirection.y / dirLen

        val initialSpeed = max(tableWidth, tableHeight) * 0.022f
        val simBalls = balls.map { b ->
            val sb = SimBall(
                x = b.x,
                y = b.y,
                vx = if (b.isCue) nx * initialSpeed else 0f,
                vy = if (b.isCue) ny * initialSpeed else 0f,
                color = if (b.isCue) Color.WHITE else b.color,
                isCue = b.isCue,
                spinX = if (b.isCue) spinX else 0f,
                spinY = if (b.isCue) spinY else 0f
            )
            sb.path.add(PointF(b.x, b.y))
            sb
        }

        val r = ballRadius
        val minDist = r * 2f
        val minDistSq = minDist * minDist
        val pocketRadius = r * 1.65f

        // إحداثيات الحفر الست (الزوايا الأربع + المنتصف العلوي والسفلي)
        val pockets = listOf(
            PointF(0f, 0f),
            PointF(tableWidth / 2f, -r * 0.25f),
            PointF(tableWidth, 0f),
            PointF(0f, tableHeight),
            PointF(tableWidth / 2f, tableHeight + r * 0.25f),
            PointF(tableWidth, tableHeight)
        )

        val maxSteps = 220
        val subSteps = 3
        val frictionPerStep = 0.991f
        val cushionRestitution = 0.86f
        val ballRestitution = 0.96f

        for (step in 0 until maxSteps) {
            var anyMoving = false

            for (sub in 0 until subSteps) {
                val dt = 1f / subSteps

                // 1. تحريك الكرات وتطبيق الاحتكاك والـ Top/Back Spin
                for (b in simBalls) {
                    if (b.inPocket) continue
                    val speed = hypot(b.vx, b.vy)
                    if (speed < 0.15f) {
                        b.vx = 0f
                        b.vy = 0f
                        continue
                    }
                    anyMoving = true

                    b.x += b.vx * dt
                    b.y += b.vy * dt

                    // تطبيق تأثير Top/Back Spin على الكرة البيضاء بعد أول تصادم
                    if (b.isCue && b.hitFirstBall && abs(b.spinY) > 0.05f) {
                        val spinAccel = initialSpeed * 0.045f * b.spinY * dt
                        b.vx += nx * spinAccel
                        b.vy += ny * spinAccel
                        b.spinY *= 0.96f
                    }

                    // فحص دخول الكرة في إحدى الحفر
                    for (p in pockets) {
                        if (hypot(b.x - p.x, b.y - p.y) < pocketRadius) {
                            b.inPocket = true
                            b.vx = 0f
                            b.vy = 0f
                            b.path.add(PointF(p.x, p.y))
                            break
                        }
                    }
                    if (b.inPocket) continue

                    // 2. الارتداد عن الحواف مع تأثير Side Spin (English)
                    if (b.x < r) {
                        b.x = r
                        b.vx = abs(b.vx) * cushionRestitution
                        if (b.isCue && abs(b.spinX) > 0.05f) {
                            b.vy += b.spinX * abs(b.vx) * 0.42f
                            b.spinX *= 0.5f
                        }
                    } else if (b.x > tableWidth - r) {
                        b.x = tableWidth - r
                        b.vx = -abs(b.vx) * cushionRestitution
                        if (b.isCue && abs(b.spinX) > 0.05f) {
                            b.vy -= b.spinX * abs(b.vx) * 0.42f
                            b.spinX *= 0.5f
                        }
                    }

                    if (b.y < r) {
                        b.y = r
                        b.vy = abs(b.vy) * cushionRestitution
                        if (b.isCue && abs(b.spinX) > 0.05f) {
                            b.vx -= b.spinX * abs(b.vy) * 0.42f
                            b.spinX *= 0.5f
                        }
                    } else if (b.y > tableHeight - r) {
                        b.y = tableHeight - r
                        b.vy = -abs(b.vy) * cushionRestitution
                        if (b.isCue && abs(b.spinX) > 0.05f) {
                            b.vx += b.spinX * abs(b.vy) * 0.42f
                            b.spinX *= 0.5f
                        }
                    }
                }

                // 3. معالجة التصادمات بين جميع الكرات (سلاسل التصادم المتعددة)
                for (i in simBalls.indices) {
                    val b1 = simBalls[i]
                    if (b1.inPocket) continue
                    for (j in i + 1 until simBalls.size) {
                        val b2 = simBalls[j]
                        if (b2.inPocket) continue

                        val dx = b2.x - b1.x
                        val dy = b2.y - b1.y
                        val distSq = dx * dx + dy * dy

                        if (distSq < minDistSq && distSq > 0.0001f) {
                            val dist = hypot(dx, dy)
                            val overlap = (minDist - dist) * 0.5f
                            val ux = dx / dist
                            val uy = dy / dist

                            // فصل الكرتين لمنع التداخل
                            b1.x -= ux * overlap
                            b1.y -= uy * overlap
                            b2.x += ux * overlap
                            b2.y += uy * overlap

                            // السرعة النسبية على محور التصادم
                            val rvx = b1.vx - b2.vx
                            val rvy = b1.vy - b2.vy
                            val velAlongNormal = rvx * ux + rvy * uy

                            if (velAlongNormal > 0f) {
                                val impulse = velAlongNormal * ballRestitution
                                b1.vx -= impulse * ux
                                b1.vy -= impulse * uy
                                b2.vx += impulse * ux
                                b2.vy += impulse * uy

                                if (b1.isCue) b1.hitFirstBall = true
                                if (b2.isCue) b2.hitFirstBall = true
                            }
                        }
                    }
                }
            }

            // تطبيق الاحتكاك وتسجيل نقاط المسار
            for (b in simBalls) {
                if (b.inPocket) continue
                b.vx *= frictionPerStep
                b.vy *= frictionPerStep

                val last = b.path.last()
                if (hypot(b.x - last.x, b.y - last.y) >= 4f) {
                    b.path.add(PointF(b.x, b.y))
                }
            }

            if (!anyMoving) break
        }

        // إرجاع مسارات الكرات التي تحركت فقط
        return simBalls
            .filter { it.path.size >= 2 }
            .map { BallTrajectory(it.color, it.isCue, it.path) }
    }
}
