package com.example.pooltracker

import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * محرك فيزياء ثنائي الأبعاد عالي الدقة (10 خطوات فرعية لكل إطار)
 * يحاكي كامل تسلسل الضربة:
 * - كرة تضرب كرة -> ترتد من الحافة -> تعود لتضرب نفس الكرة أو كرة ثالثة أو رابعة
 * - محاكاة انحناء الـ Top Spin (Follow) والـ Back Spin (Draw) بدقة
 * - محاكاة الـ Side Spin (English) عند الارتداد عن الحواف والانحراف الجانبي (Throw)
 * - محاكاة الحفر الست واكتشاف سقوط أي كرة فيها
 */
object PhysicsEngine {
    var ballRadius: Float = 10f

    data class DetectedBall(
        val x: Float,
        val y: Float,
        val color: Int,
        val isCue: Boolean
    )

    data class BallTrajectory(
        val color: Int,
        val isCue: Boolean,
        val startPos: PointF,
        val points: List<PointF>,
        val bouncePoints: List<PointF>,
        val collisionPoints: List<PointF>,
        val pocketedAt: PointF?
    )

    data class SimulationResult(
        val trajectories: List<BallTrajectory>,
        val firstHitDist: Float
    )

    private class SimBall(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        val isCue: Boolean,
        val startPos: PointF,
        var sideSpin: Float = 0f,
        var topSpin: Float = 0f,
        var spinAxisX: Float = 0f,
        var spinAxisY: Float = 0f,
        var hitFirstBall: Boolean = false,
        var inPocket: Boolean = false,
        var pocketedPoint: PointF? = null,
        val path: MutableList<PointF> = mutableListOf(),
        val bounces: MutableList<PointF> = mutableListOf(),
        val collisions: MutableList<PointF> = mutableListOf()
    )

    fun simulateAllPaths(
        balls: List<DetectedBall>,
        cueDirection: PointF,
        tableWidth: Float,
        tableHeight: Float,
        spinX: Float = 0f,
        spinY: Float = 0f
    ): SimulationResult {
        val dirLen = hypot(cueDirection.x, cueDirection.y)
        if (dirLen < 0.001f) return SimulationResult(emptyList(), 0f)

        val nx = cueDirection.x / dirLen
        val ny = cueDirection.y / dirLen

        val initialSpeed = max(tableWidth, tableHeight) * 0.032f
        val simBalls = balls.map { b ->
            val sp = PointF(b.x, b.y)
            val sb = SimBall(
                x = b.x,
                y = b.y,
                vx = if (b.isCue) nx * initialSpeed else 0f,
                vy = if (b.isCue) ny * initialSpeed else 0f,
                color = if (b.isCue) Color.rgb(0, 235, 255) else b.color,
                isCue = b.isCue,
                startPos = sp,
                sideSpin = if (b.isCue) spinX else 0f,
                topSpin = if (b.isCue) spinY else 0f,
                spinAxisX = nx,
                spinAxisY = ny
            )
            sb.path.add(sp)
            sb
        }

        val cueSim = simBalls.firstOrNull { it.isCue } ?: return SimulationResult(emptyList(), 0f)
        var firstHitDist = Float.POSITIVE_INFINITY

        val r = ballRadius
        val minDist = r * 2f
        val minDistSq = minDist * minDist
        val cornerPocketR = r * 1.85f
        val sidePocketR = r * 1.60f

        val pockets = listOf(
            Pair(PointF(0f, 0f), cornerPocketR),
            Pair(PointF(tableWidth / 2f, -r * 0.25f), sidePocketR),
            Pair(PointF(tableWidth, 0f), cornerPocketR),
            Pair(PointF(0f, tableHeight), cornerPocketR),
            Pair(PointF(tableWidth / 2f, tableHeight + r * 0.25f), sidePocketR),
            Pair(PointF(tableWidth, tableHeight), cornerPocketR)
        )

        val maxSteps = 280
        val subSteps = 10
        val dt = 1f / subSteps
        val frictionPerStep = 0.9885f
        val cushionRestitution = 0.86f
        val ballRestitution = 0.97f

        for (step in 0 until maxSteps) {
            var anyMoving = false

            for (sub in 0 until subSteps) {
                for (b in simBalls) {
                    if (b.inPocket) continue

                    val speed = hypot(b.vx, b.vy)
                    if (speed < 0.18f && (!b.isCue || abs(b.topSpin) < 0.04f)) {
                        b.vx = 0f
                        b.vy = 0f
                        continue
                    }
                    anyMoving = true

                    b.x += b.vx * dt
                    b.y += b.vy * dt

                    // تطبيق تأثير الدوران الأمامي (Follow) أو الخلفي (Draw) بعد أول تصادم
                    if (b.isCue && b.hitFirstBall && abs(b.topSpin) > 0.03f) {
                        val spinAccel = initialSpeed * 0.085f * b.topSpin * dt
                        b.vx += b.spinAxisX * spinAccel
                        b.vy += b.spinAxisY * spinAccel
                        b.topSpin *= (1f - 0.055f * dt)
                    }

                    // فحص دخول أي كرة في الحفر الست
                    for ((pPos, pRad) in pockets) {
                        if (hypot(b.x - pPos.x, b.y - pPos.y) <= pRad) {
                            b.inPocket = true
                            b.vx = 0f
                            b.vy = 0f
                            b.pocketedPoint = pPos
                            b.path.add(PointF(pPos.x, pPos.y))
                            if (b.isCue && firstHitDist == Float.POSITIVE_INFINITY) {
                                firstHitDist = hypot(b.x - b.startPos.x, b.y - b.startPos.y)
                            }
                            break
                        }
                    }
                    if (b.inPocket) continue

                    // الارتداد عن الحواف مع تطبيق Side Spin (English)
                    var bounced = false
                    if (b.x < r) {
                        b.x = r
                        b.vx = abs(b.vx) * cushionRestitution
                        if (abs(b.sideSpin) > 0.03f) {
                            b.vy -= b.sideSpin * abs(b.vx) * 0.45f
                            b.sideSpin *= 0.55f
                        }
                        bounced = true
                    } else if (b.x > tableWidth - r) {
                        b.x = tableWidth - r
                        b.vx = -abs(b.vx) * cushionRestitution
                        if (abs(b.sideSpin) > 0.03f) {
                            b.vy += b.sideSpin * abs(b.vx) * 0.45f
                            b.sideSpin *= 0.55f
                        }
                        bounced = true
                    }

                    if (b.y < r) {
                        b.y = r
                        b.vy = abs(b.vy) * cushionRestitution
                        if (abs(b.sideSpin) > 0.03f) {
                            b.vx += b.sideSpin * abs(b.vy) * 0.45f
                            b.sideSpin *= 0.55f
                        }
                        bounced = true
                    } else if (b.y > tableHeight - r) {
                        b.y = tableHeight - r
                        b.vy = -abs(b.vy) * cushionRestitution
                        if (abs(b.sideSpin) > 0.03f) {
                            b.vx -= b.sideSpin * abs(b.vy) * 0.45f
                            b.sideSpin *= 0.55f
                        }
                        bounced = true
                    }

                    if (bounced) {
                        val pt = PointF(b.x, b.y)
                        b.path.add(pt)
                        b.bounces.add(pt)
                        if (b.isCue) {
                            if (firstHitDist == Float.POSITIVE_INFINITY) {
                                firstHitDist = hypot(b.x - b.startPos.x, b.y - b.startPos.y)
                            }
                            val curSpd = hypot(b.vx, b.vy)
                            if (curSpd > 0.5f && !b.hitFirstBall) {
                                b.spinAxisX = b.vx / curSpd
                                b.spinAxisY = b.vy / curSpd
                            }
                        }
                    }
                }

                // معالجة جميع التصادمات بين الكرات (بما فيها الارتداد المتكرر وضرب نفس الكرة مجدداً)
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
                            val ux = dx / dist
                            val uy = dy / dist
                            val overlap = (minDist - dist) * 0.501f

                            b1.x -= ux * overlap
                            b1.y -= uy * overlap
                            b2.x += ux * overlap
                            b2.y += uy * overlap

                            val rvx = b1.vx - b2.vx
                            val rvy = b1.vy - b2.vy
                            val velAlongNormal = rvx * ux + rvy * uy

                            if (velAlongNormal > 0f) {
                                if (b1.isCue && !b1.hitFirstBall) {
                                    if (firstHitDist == Float.POSITIVE_INFINITY) {
                                        firstHitDist = hypot(b1.x - b1.startPos.x, b1.y - b1.startPos.y)
                                    }
                                    val s = hypot(b1.vx, b1.vy)
                                    if (s > 0.1f) {
                                        b1.spinAxisX = b1.vx / s
                                        b1.spinAxisY = b1.vy / s
                                    }
                                    b1.hitFirstBall = true
                                } else if (b2.isCue && !b2.hitFirstBall) {
                                    if (firstHitDist == Float.POSITIVE_INFINITY) {
                                        firstHitDist = hypot(b2.x - b2.startPos.x, b2.y - b2.startPos.y)
                                    }
                                    val s = hypot(b2.vx, b2.vy)
                                    if (s > 0.1f) {
                                        b2.spinAxisX = b2.vx / s
                                        b2.spinAxisY = b2.vy / s
                                    }
                                    b2.hitFirstBall = true
                                }

                                val impulse = velAlongNormal * ballRestitution
                                b1.vx -= impulse * ux
                                b1.vy -= impulse * uy
                                b2.vx += impulse * ux
                                b2.vy += impulse * uy

                                // تأثير Throw الجانبي الناتج عن Side Spin
                                if (b1.isCue && abs(b1.sideSpin) > 0.1f) {
                                    val throwAmt = b1.sideSpin * impulse * 0.045f
                                    b2.vx += -uy * throwAmt
                                    b2.vy += ux * throwAmt
                                } else if (b2.isCue && abs(b2.sideSpin) > 0.1f) {
                                    val throwAmt = b2.sideSpin * impulse * 0.045f
                                    b1.vx += -uy * throwAmt
                                    b1.vy += ux * throwAmt
                                }

                                val contact = PointF((b1.x + b2.x) * 0.5f, (b1.y + b2.y) * 0.5f)
                                b1.path.add(PointF(b1.x, b1.y))
                                b2.path.add(PointF(b2.x, b2.y))
                                b1.collisions.add(contact)
                                b2.collisions.add(contact)
                            }
                        }
                    }
                }
            }

            // احتكاك القماش وتسجيل النقاط
            for (b in simBalls) {
                if (b.inPocket) continue
                b.vx *= frictionPerStep
                b.vy *= frictionPerStep

                val last = b.path.last()
                if (hypot(b.x - last.x, b.y - last.y) >= 2.2f) {
                    b.path.add(PointF(b.x, b.y))
                }
            }

            if (!anyMoving) break
        }

        if (firstHitDist == Float.POSITIVE_INFINITY) {
            firstHitDist = r * 6f
        }

        val results = simBalls
            .filter { it.path.size >= 2 }
            .map {
                BallTrajectory(
                    color = it.color,
                    isCue = it.isCue,
                    startPos = it.startPos,
                    points = it.path,
                    bouncePoints = it.bounces,
                    collisionPoints = it.collisions,
                    pocketedAt = it.pocketedPoint
                )
            }

        return SimulationResult(results, firstHitDist)
    }
}
