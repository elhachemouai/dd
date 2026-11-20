package com.example.pooltracker

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

object PhysicsEngine {
    // عدّل نصف قطر الكرة بالبكسل حسب دقة شاشة هاتفك
    var ballRadius: Float = 22f
    private const val SPIN_SIDE_FACTOR = 0.35f
    private const val SPIN_FOLLOW_FACTOR = 0.50f

    data class PathsResult(
        val cuePath: List<PointF>,
        val targetPath: List<PointF>
    )

    private fun rayCircleIntersect(
        ox: Float, oy: Float, dx: Float, dy: Float,
        cx: Float, cy: Float, rCombined: Float
    ): Float? {
        val fx = ox - cx
        val fy = oy - cy
        val a = dx * dx + dy * dy
        val b = 2f * (fx * dx + fy * dy)
        val c = (fx * fx + fy * fy) - (rCombined * rCombined)

        val discriminant = b * b - 4f * a * c
        if (discriminant < 0f) return null

        val sqrtD = sqrt(discriminant)
        val t1 = (-b - sqrtD) / (2f * a)
        val t2 = (-b + sqrtD) / (2f * a)

        return when {
            t1 > 1f -> t1
            t2 > 1f -> t2
            else -> null
        }
    }

    private fun wallIntersect(
        x: Float, y: Float, dx: Float, dy: Float,
        width: Float, height: Float, r: Float
    ): Pair<Float, Boolean> {
        val xMin = r
        val yMin = r
        val xMax = width - r
        val yMax = height - r

        val tx = when {
            dx > 0f -> (xMax - x) / dx
            dx < 0f -> (xMin - x) / dx
            else -> Float.POSITIVE_INFINITY
        }
        val ty = when {
            dy > 0f -> (yMax - y) / dy
            dy < 0f -> (yMin - y) / dy
            else -> Float.POSITIVE_INFINITY
        }

        return if (tx < ty) Pair(tx, true) else Pair(ty, false)
    }

    fun calculatePaths(
        cuePos: PointF,
        direction: PointF,
        balls: List<PointF>,
        tableWidth: Float,
        tableHeight: Float,
        spinX: Float = 0f,
        spinY: Float = 0f
    ): PathsResult {
        val len = hypot(direction.x, direction.y)
        if (len == 0f) return PathsResult(emptyList(), emptyList())

        val dx = direction.x / len
        val dy = direction.y / len

        val cuePath = mutableListOf(cuePos)
        val targetPath = mutableListOf<PointF>()

        var minT = Float.POSITIVE_INFINITY
        var hitBall: PointF? = null

        for (b in balls) {
            if (hypot(b.x - cuePos.x, b.y - cuePos.y) < ballRadius * 1.5f) continue
            val t = rayCircleIntersect(cuePos.x, cuePos.y, dx, dy, b.x, b.y, ballRadius * 2f)
            if (t != null && t < minT) {
                minT = t
                hitBall = b
            }
        }

        val (tWall, isVertical) = wallIntersect(
            cuePos.x, cuePos.y, dx, dy, tableWidth, tableHeight, ballRadius
        )

        if (hitBall != null && minT < tWall) {
            val hx = cuePos.x + dx * minT
            val hy = cuePos.y + dy * minT
            cuePath.add(PointF(hx, hy))

            val txDir = hitBall.x - hx
            val tyDir = hitBall.y - hy
            val tLen = hypot(txDir, tyDir)

            if (tLen > 0f) {
                val ntx = txDir / tLen
                val nty = tyDir / tLen
                val (tObjWall, _) = wallIntersect(
                    hitBall.x, hitBall.y, ntx, nty, tableWidth, tableHeight, ballRadius
                )
                targetPath.add(hitBall)
                targetPath.add(PointF(hitBall.x + ntx * tObjWall, hitBall.y + nty * tObjWall))

                val dot = dx * ntx + dy * nty
                val tanX = dx - dot * ntx
                val tanY = dy - dot * nty

                val finalCx = tanX + (dx * spinY * SPIN_FOLLOW_FACTOR)
                val finalCy = tanY + (dy * spinY * SPIN_FOLLOW_FACTOR)
                val cLen = hypot(finalCx, finalCy)

                if (cLen > 0.05f) {
                    val ncx = finalCx / cLen
                    val ncy = finalCy / cLen
                    val (tCWall, _) = wallIntersect(hx, hy, ncx, ncy, tableWidth, tableHeight, ballRadius)
                    val dist = min(tCWall, 280f)
                    cuePath.add(PointF(hx + ncx * dist, hy + ncy * dist))
                }
            }
        } else {
            val wx = cuePos.x + dx * tWall
            val wy = cuePos.y + dy * tWall
            cuePath.add(PointF(wx, wy))

            val ndx = if (isVertical) -dx else dx + (spinX * SPIN_SIDE_FACTOR)
            val ndy = if (isVertical) dy + (spinX * SPIN_SIDE_FACTOR) else -dy
            val nLen = hypot(ndx, ndy)

            if (nLen > 0f) {
                val ux = ndx / nLen
                val uy = ndy / nLen
                val (tWall2, _) = wallIntersect(wx, wy, ux, uy, tableWidth, tableHeight, ballRadius)
                cuePath.add(PointF(wx + ux * tWall2, wy + uy * tWall2))
            }
        }

        return PathsResult(cuePath, targetPath)
    }
}
