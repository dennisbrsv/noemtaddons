package dev.noemt.client.features.pathfinder

import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.ThreadUtils
import dev.noemt.client.utils.WorldUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

object SkyHanniPathfinder : Module {
    override val id = "pathfinder"
    override val name = "SkyHanni Pathfinder"
    override val description = "Smooth 3D A* navigation and Catmull-Rom Bezier path rendering"
    override val type = ModuleType.LEGIT

    private val mc: Minecraft get() = Minecraft.getInstance()
    private val renderer = SkyHanniPathRenderer()

    var isNavigating: Boolean = false
        private set
    var targetLocation: Vec3? = null
        private set

    private var currentJob: Job? = null

    override fun init() {
        register<WorldChangeEvent> {
            stop()
        }

        register<TickEvent.Start> {
            if (!isNavigating) return@register
            val player = mc.player ?: return@register
            val target = targetLocation ?: return@register

            val playerPos = player.position()
            val dist = playerPos.distanceTo(target)

            if (dist < 2.5) {
                ChatUtils.modMessage("&aArrived at destination!")
                stop()
                return@register
            }

            renderer.updateNearSegment()
        }

        register<RenderWorldEvent> {
            if (!isNavigating) return@register
            renderer.render(event.ctx)
        }
    }

    fun pathTo(x: Double, y: Double, z: Double) {
        val target = Vec3(x, y, z)
        targetLocation = target
        isNavigating = true

        ChatUtils.modMessage("&aSearching path to &e${x.toInt()}, ${y.toInt()}, ${z.toInt()}&a...")

        currentJob?.cancel()
        currentJob = ThreadUtils.coroutineScope.launch {
            val player = mc.player ?: return@launch
            val start = player.blockPosition()
            val dest = BlockPos(x.toInt(), y.toInt(), z.toInt())

            val rawPath = findPathAStar(start, dest)
            if (rawPath.isEmpty()) {
                ChatUtils.modMessage("&cCould not find a navigable path to destination.")
                stop()
                return@launch
            }

            val vecPath = rawPath.map { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
            mc.execute {
                renderer.setPath(vecPath, target)
                ChatUtils.modMessage("&aPath calculated! &e(${renderer.remainingDistance.toInt()}m)")
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        isNavigating = false
        targetLocation = null
        renderer.clear()
    }

    private class Node(
        val pos: BlockPos,
        val parent: Node?,
        val gCost: Double,
        val hCost: Double
    ) : Comparable<Node> {
        val fCost: Double get() = gCost + hCost
        override fun compareTo(other: Node): Int = fCost.compareTo(other.fCost)
    }

    private fun findPathAStar(start: BlockPos, goal: BlockPos, maxNodes: Int = 10000): List<BlockPos> {
        val openQueue = PriorityQueue<Node>()
        val gScoreMap = HashMap<BlockPos, Double>()
        val closedSet = HashSet<BlockPos>()

        val startNode = Node(start, null, 0.0, heuristic(start, goal))
        openQueue.add(startNode)
        gScoreMap[start] = 0.0

        var count = 0
        var closestNode: Node = startNode

        val dirs = arrayOf(
            BlockPos(1, 0, 0), BlockPos(-1, 0, 0),
            BlockPos(0, 0, 1), BlockPos(0, 0, -1),
            BlockPos(1, 0, 1), BlockPos(-1, 0, 1),
            BlockPos(1, 0, -1), BlockPos(-1, 0, -1)
        )

        while (openQueue.isNotEmpty() && count++ < maxNodes) {
            val current = openQueue.poll() ?: break
            if (current.hCost < closestNode.hCost) {
                closestNode = current
            }

            if (current.pos == goal || current.pos.distManhattan(goal) <= 1) {
                return reconstructPath(current)
            }

            closedSet.add(current.pos)

            for (dir in dirs) {
                val isDiag = dir.x != 0 && dir.z != 0
                val horizCost = if (isDiag) 1.414 else 1.0

                // Check standard, jump (+1 y), and drops (-1, -2, -3 y)
                val yOffsets = intArrayOf(0, 1, -1, -2, -3)
                for (dy in yOffsets) {
                    val nextPos = current.pos.offset(dir.x, dy, dir.z)
                    if (closedSet.contains(nextPos)) continue

                    if (!isWalkable(nextPos)) continue
                    if (isDiag && !isDiagonalSafe(current.pos, nextPos)) continue

                    val moveCost = horizCost + if (dy > 0) 1.2 else if (dy < 0) abs(dy) * 0.8 else 0.0
                    val tentativeG = current.gCost + moveCost

                    val existingG = gScoreMap[nextPos]
                    if (existingG == null || tentativeG < existingG) {
                        gScoreMap[nextPos] = tentativeG
                        val nextNode = Node(nextPos, current, tentativeG, heuristic(nextPos, goal))
                        openQueue.add(nextNode)
                    }
                }
            }
        }

        return if (closestNode.parent != null) reconstructPath(closestNode) else emptyList()
    }

    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun isWalkable(pos: BlockPos): Boolean {
        val ground = WorldUtils.getBlockAt(pos.below())
        val feet = WorldUtils.getBlockAt(pos)
        val head = WorldUtils.getBlockAt(pos.above())

        val groundSolid = ground != Blocks.AIR && ground != Blocks.LAVA && ground != Blocks.FIRE && ground != Blocks.CACTUS
        val feetPassable = feet == Blocks.AIR || feet == Blocks.WATER || feet == Blocks.TALL_GRASS || feet == Blocks.SHORT_GRASS
        val headPassable = head == Blocks.AIR || head == Blocks.WATER

        return groundSolid && feetPassable && headPassable
    }

    private fun isDiagonalSafe(from: BlockPos, to: BlockPos): Boolean {
        val corner1 = BlockPos(from.x, from.y, to.z)
        val corner2 = BlockPos(to.x, from.y, from.z)
        return isPassable(corner1) && isPassable(corner2)
    }

    private fun isPassable(pos: BlockPos): Boolean {
        val feet = WorldUtils.getBlockAt(pos)
        val head = WorldUtils.getBlockAt(pos.above())
        return (feet == Blocks.AIR || feet == Blocks.WATER) && (head == Blocks.AIR || head == Blocks.WATER)
    }

    private fun reconstructPath(node: Node): List<BlockPos> {
        val path = mutableListOf<BlockPos>()
        var curr: Node? = node
        while (curr != null) {
            path.add(curr.pos)
            curr = curr.parent
        }
        return path.reversed()
    }
}
