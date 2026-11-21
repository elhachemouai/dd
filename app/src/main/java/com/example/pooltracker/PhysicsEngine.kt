package com.example.pooltracker

import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * محرك فيزياء احترافي متوازن (v8):
 * - يربط سرعة المحاكاة وطول المسار بقوة السحب المقروءة من شريط القوة الجانبي (Power Bar)
 * - يحصر الرسم في المسار الأساسي + ارتداد نظيف واحد للكرات الهدف (وارتدادين للبيضاء في ضربات الباند)
 *   لتحقيق أعلى دقة بصرية بدون تشابك أو تراكم أخطاء البكسل
 * - يدعم التصادمات المتسلسلة (كرة تضرب كرة أخرى) وتأثير الـ Spin
 */
object PhysicsEngine {
    var ballRadius: Float = 8.5f

    data class DetectedBall(
        val x: Float,
        val y: Float,
        val color: Int,
        val isCue: Boolean
    )

    data class PathSegment(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val bounceLevel: Int
    )

    data class BallTrajectory(
        val color: Int,
        val isCue: Boolean,
        val startPos: PointF,
        val segments: List<PathSegment>,
        val bouncePoints: List<PointF>,
        val pocketedAt: PointF?,
        val endPos: PointF
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
        var bounceCount: Int = 0,
        var inPocket: Boolean = false,
        var stoppedDrawing: Boolean = false,
        var pocketedPoint: PointF? = null,
        var lastRecordedX: Float = startPos.x,
        var lastRecordedY: Float = startPos.y,
        val segments: MutableList<PathSegment> = mutableListOf(),
        val bounces: MutableList<PointF> = mutableListOf()
    ) {
        fun isActiveMoving(): Boolean {
            if (inPocket || stoppedDrawing) return false
            return (vx * vx + vy * vy) > 0.05f || (isCue && hitFirstBall && abs(topSpin) > 0.05f)
        }

        fun recordPoint(nx: Float, ny: Float, force: Boolean = false) {
            val dx = nx - lastRecordedX
            val dy = ny - lastRecordedY
            if (force || dx * dx + dy * dy >= 6.25f) {
                if (dx * dx + dy * dy > 0.04f) {
                    segments.add(PathSegment(lastRecordedX, lastRecordedY, nx, ny, bounceCount))
                    lastRecordedX = nx
                    lastRecordedY = ny
                }
            }
        }
    }

    fun simulateAllPaths(
        balls: List<DetectedBall>,
        cueDirection: PointF,
        tableWidth: Float,
        tableHeight: Float,
        power: Float = 0.70f,
        spinX: Float = 0f,
        spinY: Float = 0f
    ): SimulationResult {
        val dirLen = hypot(cueDirection.x, cueDirection.y)
        if (dirLen < 0.001f) return SimulationResult(emptyList(), 0f)

        val nx = cueDirection.x / dirLen
        val ny = cueDirection.y / dirLen

        val clampedPower = power.coerceIn(0.15f, 1.0f)
        // ربط السرعة الابتدائية بقوة السحب (ضربة خفيفة -> مسار قصير، ضربة قوية -> مسار طويل)
        val initialSpeed = max(tableWidth, tableHeight) * (0.0075f + 0.0225f * clampedPower)

        val simBalls = Array(balls.size) { i ->
            val b = balls[i]
            val sp = PointF(b.x, b.y)
            SimBall(
                x = b.x,
                y = b.y,
                vx = if (b.isCue) nx * initialSpeed else 0f,
                vy = if (b.isCue) ny * initialSpeed else 0f,
                color = if (b.isCue) Color.rgb(225, 248, 255) else b.color,
                isCue = b.isCue,
                startPos = sp,
                sideSpin = if (b.isCue) spinX else 0f,
                topSpin = if (b.isCue) spinY else 0f,
                spinAxisX = nx,
                spinAxisY = ny
            )
        }

        if (simBalls.none { it.isCue }) return SimulationResult(emptyList(), 0f)

        var firstHitDist = Float.POSITIVE_INFINITY

        val r = ballRadius
        val minDist = r * 2f
        val minDistSq = minDist * minDist
        val cornerPocketR = r * 1.82f
        val sidePocketR = r * 1.56f

        val pxArr = floatArrayOf(0f, tableWidth / 2f, tableWidth, 0f, tableWidth / 2f, tableWidth)
        val pyArr = floatArrayOf(0f, -r * 0.20f, 0f, tableHeight, tableHeight + r * 0.20f, tableHeight)
        val prSqArr = floatArrayOf(
            cornerPocketR * cornerPocketR,
            sidePocketR * sidePocketR,
            cornerPocketR * cornerPocketR,
            cornerPocketR * cornerPocketR,
            sidePocketR * sidePocketR,
            cornerPocketR * cornerPocketR
        )

        val maxSteps = 145
        val subSteps = 6
        val dt = 1f / subSteps
        val frictionPerStep = 0.984f
        val cushionRestitution = 0.84f
        val ballRestitution = 0.97f

        // حد أقصى موثوق للارتدادات: ارتدادان للبيضاء (لضربات الباند) وارتداد واحد نظيف للكرة الهدف
        val maxBouncesCue = 2
        val maxBouncesObject = 1

        val n = simBalls.size

        for (step in 0 until maxSteps) {
            var anyMoving = false

            for (sub in 0 until subSteps) {
                for (i in 0 until n) {
                    val b = simBalls[i]
                    if (!b.isActiveMoving()) continue
                    anyMoving = true

                    b.x += b.vx * dt
                    b.y += b.vy * dt

                    // تأثير Top/Back Spin بعد أول تصادم
                    if (b.isCue && b.hitFirstBall && abs(b.topSpin) > 0.05f) {
                        val spinAccel = initialSpeed * 0.078f * b.topSpin * dt
                        b.vx += b.spinAxisX * spinAccel
                        b.vy += b.spinAxisY * spinAccel
                        b.topSpin *= (1f - 0.06f * dt)
                    }

                    // فحص دخول الحفر الست
                    for (p in 0 until 6) {
                        val dx = b.x - pxArr[p]
                        val dy = b.y - pyArr[p]
                        if (dx * dx + dy * dy <= prSqArr[p]) {
                            b.inPocket = true
                            b.vx = 0f
                            b.vy = 0f
                            val pPt = PointF(pxArr[p], pyArr[p])
                            b.pocketedPoint = pPt
                            b.recordPoint(pPt.x, pPt.y, force = true)
                            if (b.isCue && firstHitDist == Float.POSITIVE_INFINITY) {
                                firstHitDist = hypot(b.x - b.startPos.x, b.y - b.startPos.y)
                            }
                            break
                        }
                    }
                    if (b.inPocket) continue

                    // الارتداد عن الحواف مع Side Spin
                    var bounced = false
                    if (b.x < r) {
                        b.x = r
                        b.vx = abs(b.vx) * cushionRestitution
                        if (abs(b.sideSpin) > 0.05f) {
                            b.vy -= b.sideSpin * abs(b.vx) * 0.42f
                            b.sideSpin *= 0.55f
                        }
                        bounced = true
                    } else if (b.x > tableWidth - r) {
                        b.x = tableWidth - r
                        b.vx = -abs(b.vx) * cushionRestitution
                        if (abs(b.sideSpin) > 0.05f) {
                            b.vy += b.sideSpin * abs(b.vx) * 0.42f
                            b.sideSpin *= 0.55f
                        }
                        bounced = true
                    }

                    if (b.y < r) {
                        b.y = r
                        b.vy = abs(b.vy) * cushionRestitution
                        if (abs(b.sideSpin) > 0.05f) {
                            b.vx += b.sideSpin * abs(b.vy) * 0.42f
                            b.sideSpin *= 0.55f
                        }
                        bounced = true
                    } else if (b.y > tableHeight - r) {
                        b.y = tableHeight - r
                        b.vy = -abs(b.vy) * cushionRestitution
                        if (abs(b.sideSpin) > 0.05f) {
                            b.vx -= b.sideSpin * abs(b.vy) * 0.42f
                            b.sideSpin *= 0.55f
                        }
                        bounced = true
                    }

                    if (bounced) {
                        b.recordPoint(b.x, b.y, force = true)
                        b.bounces.add(PointF(b.x, b.y))
                        b.bounceCount++
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
                        val limit = if (b.isCue) maxBouncesCue else maxBouncesObject
                        if (b.bounceCount > limit) {
                            b.stoppedDrawing = true
                            b.vx = 0f
                            b.vy = 0f
                        }
                    }
                }

                // معالجة التصادمات بين الكرات
                for (i in 0 until n) {
                    val b1 = simBalls[i]
                    if (b1.inPocket) continue
                    val b1Moving = (b1.vx * b1.vx + b1.vy * b1.vy) > 0.05f
                    for (j in i + 1 until n) {
                        val b2 = simBalls[j]
                        if (b2.inPocket) continue
                        if (!b1Moving && (b2.vx * b2.vx + b2.vy * b2.vy) <= 0.05f) continue

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

                                if (b1.isCue && abs(b1.sideSpin) > 0.1f) {
                                    val throwAmt = b1.sideSpin * impulse * 0.045f
                                    b2.vx += -uy * throwAmt
                                    b2.vy += ux * throwAmt
                                } else if (b2.isCue && abs(b2.sideSpin) > 0.1f) {
                                    val throwAmt = b2.sideSpin * impulse * 0.045f
                                    b1.vx += -uy * throwAmt
                                    b1.vy += ux * throwAmt
                                }

                                b1.recordPoint(b1.x, b1.y, force = true)
                                b2.recordPoint(b2.x, b2.y, force = true)
                            }
                        }
                    }
                }
            }

            for (i in 0 until n) {
                val b = simBalls[i]
                if (!b.isActiveMoving()) continue
                b.vx *= frictionPerStep
                b.vy *= frictionPerStep
                b.recordPoint(b.x, b.y)
            }

            if (!anyMoving) break
        }

        if (firstHitDist == Float.POSITIVE_INFINITY) {
            firstHitDist = r * 6f
        }

        val results = ArrayList<BallTrajectory>(n)
        for (i in 0 until n) {
            val b = simBalls[i]
            if (b.segments.isNotEmpty()) {
                b.recordPoint(b.x, b.y, force = true)
                results.add(
                    BallTrajectory(
                        color = b.color,
                        isCue = b.isCue,
                        startPos = b.startPos,
                        segments = b.segments,
                        bouncePoints = b.bounces,
                        pocketedAt = b.pocketedPoint,
                        endPos = PointF(b.x, b.y)
                    )
                )
            }
        }

        return SimulationResult(results, firstHitDist)
    }
}
