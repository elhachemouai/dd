package com.example.pooltracker

import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * محرك فيزياء احترافي عالي الدقة (Sub-pixel stepping)
 * يحاكي:
 * - التصادمات المتسلسلة (كرة تضرب كرة تضرب أخرى أو تعود من الحافة لتضرب نفس الكرة أو غيرها)
 * - الدوران الأمامي والخلفي (Follow / Draw Spin) بمسارات منحنية حقيقية
 * - الدوران الجانبي (Side Spin / English) عند الارتداد عن الحواف والـ Throw
 * - الحفر الست واكتشاف الكرات الداخلة في الحفرة
 */
object PhysicsEngine {
    var ballRadius: Float = 16f

    data class DetectedBall(
        val x: Float,
        val y: Float,
        val color: Int,
        val isCue: Boolean
    )

    data class BallTrajectory(
        val color: Int,
        val isCue: Boolean,
        val points: List<PointF>,
        val bouncePoints: List<PointF>,
        val collisionPoints: List<PointF>,
        val pocketedAt: PointF?
    )

    data class SimulationResult(
        val trajectories: List<BallTrajectory>,
        val ghostBallPos: PointF?
    )

    private class SimBall(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        val isCue: Boolean,
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
        if (dirLen < 0.001f) return SimulationResult(emptyList(), null)

        val nx = cueDirection.x / dirLen
        val ny = cueDirection.y / dirLen

        // سرعة ابتدائية قوية كافية لمحاكاة الارتدادات المتعددة والتصادمات المتسلسلة
        val initialSpeed = max(tableWidth, tableHeight) * 0.030f
        val simBalls = balls.map { b ->
            val sb = SimBall(
                x = b.x,
                y = b.y,
                vx = if (b.isCue) nx * initialSpeed else 0f,
                vy = if (b.isCue) ny * initialSpeed else 0f,
                color = if (b.isCue) Color.rgb(0, 230, 255) else b.color,
                isCue = b.isCue,
                sideSpin = if (b.isCue) spinX else 0f,
                topSpin = if (b.isCue) spinY else 0f,
                spinAxisX = nx,
                spinAxisY = ny
            )
            sb.path.add(PointF(b.x, b.y))
            sb
        }

        val r = ballRadius
        val minDist = r * 2f
        val minDistSq = minDist * minDist
        val cornerPocketR = r * 1.75f
        val sidePocketR = r * 1.55f

        val pockets = listOf(
            Pair(PointF(0f, 0f), cornerPocketR),
            Pair(PointF(tableWidth / 2f, -r * 0.25f), sidePocketR),
            Pair(PointF(tableWidth, 0f), cornerPocketR),
            Pair(PointF(0f, tableHeight), cornerPocketR),
            Pair(PointF(tableWidth / 2f, tableHeight + r * 0.25f), sidePocketR),
            Pair(PointF(tableWidth, tableHeight), cornerPocketR)
        )

        var ghostBallPos: PointF? = null

        // خطوات دقيقة جداً لضمان عدم تخطي أي تصادم حتى لو ارتدت الكرة بسرعة وعادت لضرب نفس الكرة
        val maxSteps = 260
        val subSteps = 8
        val dt = 1f / subSteps
        val frictionPerStep = 0.988f
        val cushionRestitution = 0.85f
        val ballRestitution = 0.965f

        for (step in 0 until maxSteps) {
            var anyMoving = false

            for (sub in 0 until subSteps) {
                // 1. تحديث حركة كل كرة والـ Spin والحفر والحواف
                for (b in simBalls) {
                    if (b.inPocket) continue

                    val speed = hypot(b.vx, b.vy)
                    if (speed < 0.25f && (!b.isCue || abs(b.topSpin) < 0.05f)) {
                        b.vx = 0f
                        b.vy = 0f
                        continue
                    }
                    anyMoving = true

                    b.x += b.vx * dt
                    b.y += b.vy * dt

                    // تأثير Top Spin (Follow) و Back Spin (Draw) بعد أول تصادم لتوليد مسار منحني حقيقي
                    if (b.isCue && b.hitFirstBall && abs(b.topSpin) > 0.03f) {
                        val spinForce = initialSpeed * 0.075f * b.topSpin * dt
                        b.vx += b.spinAxisX * spinForce
                        b.vy += b.spinAxisY * spinForce
                        b.topSpin *= (1f - 0.055f * dt)
                    }

                    // فحص دخول الكرة في الحفرة
                    for ((pPos, pRad) in pockets) {
                        if (hypot(b.x - pPos.x, b.y - pPos.y) <= pRad) {
                            b.inPocket = true
                            b.vx = 0f
                            b.vy = 0f
                            b.pocketedPoint = pPos
                            b.path.add(PointF(pPos.x, pPos.y))
                            break
                        }
                    }
                    if (b.inPocket) continue

                    // الارتداد عن الحواف الأربع مع تطبيق Side Spin (English)
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
                        // تحديث محور الـ Spin بعد الارتداد عن الحافة
                        val curSpd = hypot(b.vx, b.vy)
                        if (b.isCue && curSpd > 0.5f && !b.hitFirstBall) {
                            b.spinAxisX = b.vx / curSpd
                            b.spinAxisY = b.vy / curSpd
                        }
                    }
                }

                // 2. معالجة جميع التصادمات بين الكرات (بما في ذلك الكرة تعود لتضرب نفس الكرة أو كرة ثالثة)
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
                                // تسجيل نقطة الكرة الشبحية (Ghost Ball) عند أول تصادم للكرة البيضاء
                                if (ghostBallPos == null) {
                                    if (b1.isCue) ghostBallPos = PointF(b1.x, b1.y)
                                    else if (b2.isCue) ghostBallPos = PointF(b2.x, b2.y)
                                }

                                // تسجيل محور الحركة قبل الاصطدام لحساب انحناء الـ Top/Back Spin بدقة
                                if (b1.isCue && !b1.hitFirstBall) {
                                    val s = hypot(b1.vx, b1.vy)
                                    if (s > 0.1f) {
                                        b1.spinAxisX = b1.vx / s
                                        b1.spinAxisY = b1.vy / s
                                    }
                                    b1.hitFirstBall = true
                                }
                                if (b2.isCue && !b2.hitFirstBall) {
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

                                // تأثير طفيف للـ Spin Throw على الكرة المضروبة
                                if (b1.isCue && abs(b1.sideSpin) > 0.1f) {
                                    val throwFactor = b1.sideSpin * impulse * 0.045f
                                    b2.vx += -uy * throwFactor
                                    b2.vy += ux * throwFactor
                                } else if (b2.isCue && abs(b2.sideSpin) > 0.1f) {
                                    val throwFactor = b2.sideSpin * impulse * 0.045f
                                    b1.vx += -uy * throwFactor
                                    b1.vy += ux * throwFactor
                                }

                                val contactPt = PointF((b1.x + b2.x) * 0.5f, (b1.y + b2.y) * 0.5f)
                                b1.path.add(PointF(b1.x, b1.y))
                                b2.path.add(PointF(b2.x, b2.y))
                                b1.collisions.add(contactPt)
                                b2.collisions.add(contactPt)
                            }
                        }
                    }
                }
            }

            // تطبيق احتكاك الطاولة وتسجيل المسارات بسلاسة
            for (b in simBalls) {
                if (b.inPocket) continue
                b.vx *= frictionPerStep
                b.vy *= frictionPerStep

                val last = b.path.last()
                if (hypot(b.x - last.x, b.y - last.y) >= 3.5f) {
                    b.path.add(PointF(b.x, b.y))
                }
            }

            if (!anyMoving) break
        }

        val results = simBalls
            .filter { it.path.size >= 2 }
            .map {
                BallTrajectory(
                    color = it.color,
                    isCue = it.isCue,
                    points = it.path,
                    bouncePoints = it.bounces,
                    collisionPoints = it.collisions,
                    pocketedAt = it.pocketedPoint
                )
            }

        return SimulationResult(results, ghostBallPos)
    }
}
