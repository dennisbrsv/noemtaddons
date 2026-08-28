package dev.noemt.client.features.pathfinder

import dev.noemt.client.render.Render3D.render3DBezier2
import dev.noemt.client.render.Render3D.renderLine
import dev.noemt.client.render.Render3D.renderWaypoint
import dev.noemt.client.render.RenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.sqrt

class SkyHanniPathRenderer(
    var color: Color = Color(0, 230, 255, 230),
    var targetLocation: Vec3 = Vec3.ZERO,
    rawPositions: List<Vec3> = emptyList()
) {
    private val mc: Minecraft get() = Minecraft.getInstance()

    var pathPoints: List<PathPoint> = emptyList()
        private set

    var remainingDistance: Double = 0.0
        private set

    private var nearCurveLength: Double = CURVE_RADIUS

    init {
        setPath(rawPositions, targetLocation)
    }

    fun setPath(rawPositions: List<Vec3>, target: Vec3) {
        targetLocation = target
        if (rawPositions.isEmpty()) {
            pathPoints = emptyList()
            remainingDistance = 0.0
            return
        }
        val subdivided = subdividePositions(rawPositions)
        pathPoints = subdivided.map { PathPoint(it) }
        updateNearSegment()
    }

    fun clear() {
        pathPoints = emptyList()
        remainingDistance = 0.0
    }

    fun render(ctx: RenderContext) {
        if (pathPoints.isEmpty()) return

        // Render target waypoint
        ctx.renderWaypoint(targetLocation, color, label = "§b[Destination] §e(${remainingDistance.toInt()}m)", seeThroughBlocks = true)

        // Render path segments with Bezier curve
        renderPathSegments(ctx)
    }

    private fun renderPathSegments(ctx: RenderContext) {
        val player = mc.player ?: return
        val eyePos = player.eyePosition
        val lookDir = Vec3.directionFromRotation(ctx.camera.xRot(), ctx.camera.yRot())
        val lineStartPos = eyePos.add(lookDir.scale(0.8))

        if (pathPoints.size == 1) {
            renderSingleNodeCurve(ctx, eyePos, lineStartPos, pathPoints[0])
            return
        }

        val (startPos, nextPathIdx) = projectOntoPath(eyePos)
        val walkPositions: List<Vec3> = listOf(startPos) + pathPoints.drop(nextPathIdx).map { it.pos }
        val curveEnd = findBezierEnd(walkPositions, nextPathIdx) ?: return

        val dirToCurve = (curveEnd.pos.subtract(eyePos)).normalize()
        val anchor = lineStartPos.add(dirToCurve.scale(ANCHOR_FORWARD_DIST))
        val scale = anchor.distanceTo(curveEnd.pos) * CONTROL_POINT_SCALE
        val controlPoint = curveEnd.pos.subtract(curveEnd.tangent.scale(scale))

        // Draw smooth entry curve
        ctx.render3DBezier2(anchor, controlPoint, curveEnd.pos, color, lineWidth = NEAR_LINE_WIDTH, depth = false)

        if (curveEnd.nextIdx > pathPoints.lastIndex) return

        // Draw remaining path lines
        val firstFar = pathPoints[curveEnd.nextIdx]
        ctx.renderLine(curveEnd.pos, firstFar.pos, color, thickness = NEAR_LINE_WIDTH, phase = true)
        for (i in curveEnd.nextIdx until pathPoints.lastIndex) {
            val a = pathPoints[i]
            val b = pathPoints[i + 1]
            ctx.renderLine(a.pos, b.pos, color, thickness = NEAR_LINE_WIDTH, phase = true)
        }
    }

    private fun renderSingleNodeCurve(
        ctx: RenderContext,
        eyePos: Vec3,
        lineStartPos: Vec3,
        point: PathPoint
    ) {
        val nodePos = point.pos
        val dirToNode = (nodePos.subtract(eyePos)).normalize()
        val anchor = lineStartPos.add(dirToNode.scale(ANCHOR_FORWARD_DIST))
        val scale = anchor.distanceTo(nodePos) * CONTROL_POINT_SCALE
        val controlPoint = nodePos.subtract(dirToNode.scale(scale))
        ctx.render3DBezier2(anchor, controlPoint, nodePos, color, lineWidth = NEAR_LINE_WIDTH, depth = false)
    }

    fun updateNearSegment() {
        val player = mc.player ?: return
        val playerPosition = player.position()
        if (pathPoints.isEmpty()) return

        for (point in pathPoints) point.isPeek = false
        val closestIdx = findClosestIndex(pathPoints, playerPosition)
        remainingDistance = calculateDistance(closestIdx, playerPosition)

        var totalDist = 0.0
        for (i in (closestIdx + 1)..pathPoints.lastIndex) {
            totalDist += pathPoints[i - 1].pos.distanceTo(pathPoints[i].pos)
            if (totalDist >= CURVE_RADIUS) {
                totalDist = CURVE_RADIUS
                break
            }
        }
        nearCurveLength = totalDist.coerceAtLeast(SUBDIVISION_STEP)
    }

    private fun calculateDistance(closestIdx: Int, playerPosition: Vec3): Double {
        if (pathPoints.isEmpty()) return 0.0
        var distance = pathPoints[closestIdx].pos.distanceTo(playerPosition)
        for (i in closestIdx until pathPoints.lastIndex) {
            distance += pathPoints[i].pos.distanceTo(pathPoints[i + 1].pos)
        }
        return distance + pathPoints.last().pos.distanceTo(targetLocation)
    }

    private fun walkTangent(walkPositions: List<Vec3>, startSegIdx: Int, startPos: Vec3): Vec3 {
        var remaining = TANGENT_LOOKAHEAD
        var prev = startPos
        for (i in startSegIdx until walkPositions.size) {
            val next = walkPositions[i]
            val d = prev.distanceTo(next)
            if (d >= remaining) {
                return (prev.add((next.subtract(prev)).normalize().scale(remaining)).subtract(startPos)).normalize()
            }
            remaining -= d
            prev = next
        }
        return if (prev.distanceToSqr(startPos) > 0.0001) {
            (prev.subtract(startPos)).normalize()
        } else if (walkPositions.size >= 2) {
            (walkPositions.last().subtract(walkPositions[walkPositions.lastIndex - 1])).normalize()
        } else {
            Vec3(0.0, 1.0, 0.0)
        }
    }

    private fun findBezierEnd(walkPositions: List<Vec3>, nextPathIdx: Int): CurveEnd? {
        var totalDist = 0.0
        var result: CurveEnd? = null
        for (i in 1..walkPositions.lastIndex) {
            val segStart = walkPositions[i - 1]
            val segEnd = walkPositions[i]
            val segLen = segStart.distanceTo(segEnd)
            val remaining = nearCurveLength - totalDist
            if (segLen >= remaining) {
                val endPos = segStart.add((segEnd.subtract(segStart)).normalize().scale(remaining))
                return CurveEnd(endPos, walkTangent(walkPositions, i, endPos), nextPathIdx + i - 1)
            }
            totalDist += segLen
            result = CurveEnd(segEnd, (segEnd.subtract(segStart)).normalize(), nextPathIdx + i - 1)
        }
        return result
    }

    private fun projectOntoPath(eyePos: Vec3): Pair<Vec3, Int> {
        var bestDistSq = Double.MAX_VALUE
        var bestPos = pathPoints[0].pos
        var bestNextIdx = 1
        for (i in 0 until pathPoints.lastIndex) {
            val proj = nearestPointOnLine(eyePos, pathPoints[i].pos, pathPoints[i + 1].pos)
            val distSq = eyePos.distanceToSqr(proj)
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestPos = proj
                bestNextIdx = i + 1
            }
        }
        return bestPos to bestNextIdx
    }

    private fun nearestPointOnLine(point: Vec3, a: Vec3, b: Vec3): Vec3 {
        val ab = b.subtract(a)
        val lenSq = ab.lengthSqr()
        if (lenSq == 0.0) return a
        val ap = point.subtract(a)
        val t = (ap.dot(ab) / lenSq).coerceIn(0.0, 1.0)
        return a.add(ab.scale(t))
    }

    private fun catmullRomPoint(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
        val t2 = t * t
        val t3 = t2 * t
        val a = p1.scale(2.0)
        val b = (p2.subtract(p0)).scale(t)
        val c = (p0.scale(2.0).subtract(p1.scale(5.0)).add(p2.scale(4.0)).subtract(p3)).scale(t2)
        val d = (p1.scale(3.0).subtract(p0).subtract(p2.scale(3.0)).add(p3)).scale(t3)
        return a.add(b).add(c).add(d).scale(0.5)
    }

    private fun subdividePositions(positions: List<Vec3>): List<Vec3> {
        if (positions.size < 2) return positions
        val result = mutableListOf<Vec3>()
        result.add(positions.first())
        for (i in 0 until positions.lastIndex) {
            val p0 = positions.getOrElse(i - 1) { positions[i] }
            val p1 = positions[i]
            val p2 = positions[i + 1]
            val p3 = positions.getOrElse(i + 2) { positions[i + 1] }
            val dist = p1.distanceTo(p2)
            val steps = (dist / SUBDIVISION_STEP).toInt().coerceIn(1, 100)
            for (step in 1..steps) {
                result.add(catmullRomPoint(p0, p1, p2, p3, step.toDouble() / steps))
            }
        }
        return result
    }

    private fun findClosestIndex(positions: List<PathPoint>, referencePos: Vec3): Int =
        positions.indices.minByOrNull { positions[it].pos.distanceTo(referencePos) } ?: 0

    data class PathPoint(val pos: Vec3, var isPeek: Boolean = false, var isWater: Boolean = false)
    data class CurveEnd(val pos: Vec3, val tangent: Vec3, val nextIdx: Int)

    companion object {
        private const val SUBDIVISION_STEP = 0.5
        private const val CURVE_RADIUS = 8.0
        private const val ANCHOR_FORWARD_DIST = 1.5
        private const val CONTROL_POINT_SCALE = 0.5
        private const val NEAR_LINE_WIDTH = 3.5f
        private const val TANGENT_LOOKAHEAD = 1.0
    }
}
