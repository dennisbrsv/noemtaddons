package dev.noemt.client.utils.pathfinder

import dev.noemt.client.utils.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue
import kotlin.math.*

object TeleportPathfinder {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun findTeleportPath(startPos: BlockPos, goalPos: BlockPos): List<PathNode>? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        val profile = TeleportAbilityHelper.getBestTeleportItem()

        val startFloor = findValidLandingFloor(startPos) ?: startPos
        val goalFloor = findValidLandingFloor(goalPos) ?: goalPos
        val startEye = Vec3(startFloor.x + 0.5, startFloor.y + 1.62, startFloor.z + 0.5)
        val goalCenter = Vec3(goalFloor.x + 0.5, goalFloor.y + 1.0, goalFloor.z + 0.5)

        // 1. Direct 1-Shot Teleport (0 intermediate hops)
        if (profile != null && profile.hasEtherwarp) {
            if (TeleportAbilityHelper.canEtherwarpTo(startEye, goalFloor, profile.etherwarpRange)) {
                return listOf(
                    PathNode(startFloor, null, PathAction.WALK, 0.0, 0.0),
                    PathNode(goalFloor, null, PathAction.ETHERWARP, 1.0, 0.0, actionData = goalFloor)
                )
            }
        }

        // Direct forward blink check (Instant Transmission)
        if (profile != null) {
            val dist = startEye.distanceTo(goalCenter)
            if (dist in 4.0..profile.instantTransmissionRange) {
                val dir = goalCenter.subtract(startEye).normalize()
                val clear = TeleportAbilityHelper.canInstantTransmissionForward(startEye, dir, dist)
                if (clear != null && clear >= dist - 1.0) {
                    return listOf(
                        PathNode(startFloor, null, PathAction.WALK, 0.0, 0.0),
                        PathNode(goalFloor, null, PathAction.INSTANT_TRANSMISSION, 1.0, 0.0, actionData = goalCenter)
                    )
                }
            }
        }

        // 2. Vantage Reposition 1-Shot: If direct sightline is blocked by a wall edge/door frame, step 1-6 blocks to an open vantage spot!
        if (profile != null && profile.hasEtherwarp) {
            val vantageSpot = findVantagePointForTarget(startFloor, goalFloor, profile.etherwarpRange, maxWalkDist = 6)
            if (vantageSpot != null && vantageSpot != startFloor) {
                val walkPath = AStarPathfinder(startFloor, vantageSpot, allowAotv = true, allowEtherwarp = false).findPath()
                if (walkPath != null && walkPath.isNotEmpty()) {
                    val lastWalk = walkPath.last()
                    val goalNode = PathNode(goalFloor, lastWalk, PathAction.ETHERWARP, lastWalk.gCost + 1.0, 0.0, actionData = goalFloor)
                    return walkPath + goalNode
                }
            }
        }

        // 3. Ceiling / Structure Exit Check: If under a ceiling and cannot warp to goal, walk out of structure first!
        val startClearance = getOverheadClearance(startFloor)
        if (startClearance <= 4) {
            val exitNode = findNearestStructureExit(startFloor)
            if (exitNode != null && exitNode != startFloor) {
                val exitPath = AStarPathfinder(startFloor, exitNode, allowAotv = true, allowEtherwarp = false).findPath()
                if (exitPath != null && exitPath.isNotEmpty()) {
                    val outsidePath = findTeleportPath(exitNode, goalPos)
                    if (outsidePath != null && outsidePath.isNotEmpty()) {
                        val combined = mutableListOf<PathNode>()
                        combined.addAll(exitPath)
                        combined.addAll(outsidePath.drop(1))
                        return combined
                    }
                }
            }
        }

        // 4. Direct Forward Blink Chain (Instant Transmission across open hallways / terrain)
        if (profile != null) {
            val blinkPath = attemptBlinkChain(startFloor, goalFloor, profile)
            if (blinkPath != null && blinkPath.isNotEmpty()) {
                return blinkPath
            }
        }

        // 4. 2-Hop Teleport Solver: Start -> Open Perch -> Goal
        if (profile != null && profile.hasEtherwarp) {
            val etherRange = profile.etherwarpRange
            val visibleOpenSpots = scanOpenLandingSpots(startEye, etherRange)
            val isAscending = goalFloor.y > startFloor.y + 2

            val twoHopCandidate = visibleOpenSpots
                .filter { mid ->
                    val midEye = Vec3(mid.x + 0.5, mid.y + 1.62, mid.z + 0.5)
                    TeleportAbilityHelper.canEtherwarpTo(midEye, goalFloor, etherRange)
                }
                .minByOrNull { mid ->
                    val midVec = Vec3(mid.x + 0.5, mid.y + 1.0, mid.z + 0.5)
                    val baseDist = startEye.distanceTo(midVec) + midVec.distanceTo(goalCenter)
                    // If target is above, strongly favor ascending upwards first!
                    val elevationReward = if (isAscending && mid.y > startFloor.y) (mid.y - startFloor.y) * 6.0 else 0.0
                    baseDist - elevationReward
                }

            if (twoHopCandidate != null) {
                val startNode = PathNode(startFloor, null, PathAction.WALK, 0.0, 0.0)
                val midNode = PathNode(twoHopCandidate, startNode, PathAction.ETHERWARP, 1.0, 0.0, actionData = twoHopCandidate)
                val goalNode = PathNode(goalFloor, midNode, PathAction.ETHERWARP, 2.0, 0.0, actionData = goalFloor)
                return listOf(startNode, midNode, goalNode)
            }
        }

        // 4. Multi-Hop Macro Visibility Graph Search
        val macroPath = solveMacroVisibilityGraph(startFloor, goalFloor, profile)
        if (macroPath != null && macroPath.isNotEmpty()) {
            return macroPath
        }

        // 5. Fallback: Center-Line Anti-Wall AStar
        return AStarPathfinder(startFloor, goalFloor, allowAotv = true, allowEtherwarp = true).findPath()
    }

    private fun attemptBlinkChain(startFloor: BlockPos, goalFloor: BlockPos, profile: TeleportItemProfile): List<PathNode>? {
        val level = mc.level ?: return null
        val blinkRange = profile.instantTransmissionRange
        val nodes = mutableListOf<PathNode>()

        var currFloor = startFloor
        var currEye = Vec3(currFloor.x + 0.5, currFloor.y + 1.62, currFloor.z + 0.5)
        val goalCenter = Vec3(goalFloor.x + 0.5, goalFloor.y + 1.0, goalFloor.z + 0.5)

        val startNode = PathNode(startFloor, null, PathAction.WALK, 0.0, 0.0)
        nodes.add(startNode)
        var lastNode = startNode

        for (hop in 1..8) {
            val distToGoal = currEye.distanceTo(goalCenter)
            if (distToGoal <= 3.0) {
                if (nodes.size > 1) return nodes else break
            }

            // If we can Etherwarp directly to goal from this blink position, do it!
            if (profile.hasEtherwarp && TeleportAbilityHelper.canEtherwarpTo(currEye, goalFloor, profile.etherwarpRange)) {
                val goalNode = PathNode(goalFloor, lastNode, PathAction.ETHERWARP, lastNode.gCost + 1.0, 0.0, actionData = goalFloor)
                nodes.add(goalNode)
                return nodes
            }

            val dir = goalCenter.subtract(currEye).normalize()
            val clear = TeleportAbilityHelper.canInstantTransmissionForward(currEye, dir, blinkRange.coerceAtMost(distToGoal))
                ?: break

            if (clear < 5.0) break

            val blinkTarget = currEye.add(dir.scale(clear))
            val groundPos = BlockPos.containing(blinkTarget.x, blinkTarget.y - 1.0, blinkTarget.z)
            val landingFloor = if (isValidLandingSpot(groundPos)) groundPos else if (isValidLandingSpot(groundPos.below(1))) groundPos.below(1) else null
            if (landingFloor == null) break

            val blinkNode = PathNode(landingFloor, lastNode, PathAction.INSTANT_TRANSMISSION, lastNode.gCost + 0.8, 0.0, actionData = blinkTarget)
            nodes.add(blinkNode)
            lastNode = blinkNode

            currFloor = landingFloor
            currEye = Vec3(currFloor.x + 0.5, currFloor.y + 1.62, currFloor.z + 0.5)
        }

        return if (nodes.size >= 2 && (currFloor == goalFloor || currEye.distanceTo(goalCenter) <= 4.0)) nodes else null
    }

    private fun solveMacroVisibilityGraph(startFloor: BlockPos, goalFloor: BlockPos, profile: TeleportItemProfile?): List<PathNode>? {
        val level = mc.level ?: return null
        val etherRange = profile?.etherwarpRange ?: 57.0
        val hasEther = profile?.hasEtherwarp == true

        val startEye = Vec3(startFloor.x + 0.5, startFloor.y + 1.62, startFloor.z + 0.5)
        val goalCenter = Vec3(goalFloor.x + 0.5, goalFloor.y + 1.0, goalFloor.z + 0.5)

        val openSet = PriorityQueue<PathNode>()
        val allNodes = HashMap<Long, PathNode>()
        val closedSet = HashSet<Long>()

        val startNode = PathNode(startFloor, null, PathAction.WALK, 0.0, startEye.distanceTo(goalCenter))
        openSet.add(startNode)
        allNodes[startNode.packedPos] = startNode

        var expansions = 0
        val maxExpansions = 500

        while (openSet.isNotEmpty() && expansions < maxExpansions) {
            val current = openSet.poll() ?: break
            val currentPacked = current.packedPos

            if (closedSet.contains(currentPacked)) continue
            closedSet.add(currentPacked)
            expansions++

            val currentEye = current.eyeVec

            if (hasEther && TeleportAbilityHelper.canEtherwarpTo(currentEye, goalFloor, etherRange)) {
                val goalNode = PathNode(goalFloor, current, PathAction.ETHERWARP, current.gCost + 1.0, 0.0, actionData = goalFloor)
                val constructed = reconstructPath(goalNode)
                if (validateEntirePath(constructed, profile)) return constructed
            }

            if (current.pos == goalFloor || current.pos.distManhattan(goalFloor) <= 2) {
                val constructed = reconstructPath(current)
                if (validateEntirePath(constructed, profile)) return constructed
            }

            val neighbors = scanOpenLandingSpots(currentEye, etherRange)
            for (neighbor in neighbors) {
                if (closedSet.contains(neighbor.asLong())) continue

                val neighborVec = Vec3(neighbor.x + 0.5, neighbor.y + 1.0, neighbor.z + 0.5)
                val dist = currentEye.distanceTo(neighborVec)
                if (dist < 10.0) continue

                val newG = current.gCost + 1.0
                val distToGoal = neighborVec.distanceTo(goalCenter)
                val elevationBonus = if (goalFloor.y > startFloor.y && neighbor.y > current.pos.y) (neighbor.y - startFloor.y) * 2.0 else 0.0
                val h = distToGoal - elevationBonus

                val existing = allNodes[neighbor.asLong()]

                if (existing == null) {
                    val node = PathNode(neighbor, current, PathAction.ETHERWARP, newG, h, actionData = neighbor)
                    allNodes[neighbor.asLong()] = node
                    openSet.add(node)
                } else if (newG < existing.gCost) {
                    openSet.remove(existing)
                    existing.parent = current
                    existing.action = PathAction.ETHERWARP
                    existing.gCost = newG
                    existing.actionData = neighbor
                    openSet.add(existing)
                }
            }
        }

        return null
    }

    fun scanOpenLandingSpots(fromEye: Vec3, maxRange: Double): List<BlockPos> {
        val level = mc.level ?: return emptyList()
        val player = mc.player ?: return emptyList()
        val results = mutableListOf<BlockPos>()
        val seen = HashSet<Long>()

        val yawSteps = 24
        val pitchAngles = floatArrayOf(-45f, -25f, -10f, 0f, 15f, 30f, 45f, 60f, 75f)

        for (pitch in pitchAngles) {
            for (i in 0 until yawSteps) {
                val yaw = (i * (360f / yawSteps)) - 180f
                val lookVec = RotationUtils.getLookVec(yaw, pitch)
                val rayEnd = fromEye.add(lookVec.scale(maxRange))

                val hit = level.clip(ClipContext(fromEye, rayEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
                if (hit.type == HitResult.Type.BLOCK) {
                    val hitPos = (hit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                        ?: BlockPos.containing(hit.location.x, hit.location.y, hit.location.z)

                    val candidate = findValidLandingFloor(hitPos) ?: hitPos
                    if (seen.add(candidate.asLong()) && isValidLandingSpot(candidate)) {
                        val dist = fromEye.distanceTo(Vec3(candidate.x + 0.5, candidate.y + 1.0, candidate.z + 0.5))
                        if (dist in 10.0..maxRange) {
                            if (TeleportAbilityHelper.canEtherwarpTo(fromEye, candidate, maxRange)) {
                                results.add(candidate)
                            }
                        }
                    }
                }
            }
        }

        return results
    }

    fun validateEntirePath(path: List<PathNode>, profile: TeleportItemProfile?): Boolean {
        if (path.isEmpty()) return false
        val etherRange = profile?.etherwarpRange ?: 57.0
        val hasEther = profile?.hasEtherwarp == true
        val instantRange = profile?.instantTransmissionRange ?: 12.0

        for (i in 1 until path.size) {
            val prev = path[i - 1]
            val curr = path[i]
            val fromEye = prev.eyeVec

            when (curr.action) {
                PathAction.ETHERWARP -> {
                    if (!hasEther) return false
                    val targetBlock = (curr.actionData as? BlockPos) ?: curr.pos
                    val targetCenter = Vec3(targetBlock.x + 0.5, targetBlock.y + 1.0, targetBlock.z + 0.5)
                    val dist = fromEye.distanceTo(targetCenter)
                    if (dist < 8.0 || dist > etherRange) return false
                    if (!TeleportAbilityHelper.canEtherwarpTo(fromEye, targetBlock, etherRange)) {
                        return false
                    }
                }
                PathAction.INSTANT_TRANSMISSION -> {
                    val targetVec = (curr.actionData as? Vec3) ?: curr.standingVec
                    val dist = fromEye.distanceTo(targetVec)
                    if (dist < 4.0 || dist > instantRange) return false
                    val dir = targetVec.subtract(fromEye).normalize()
                    val clear = TeleportAbilityHelper.canInstantTransmissionForward(fromEye, dir, dist)
                    if (clear == null || clear < dist - 1.0) {
                        return false
                    }
                }
                else -> {
                    if (!isValidLandingSpot(curr.pos)) return false
                }
            }
        }
        return true
    }

    fun isClearOfWalls(pos: BlockPos, radius: Int = 1): Boolean {
        val level = mc.level ?: return true
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (dx == 0 && dz == 0) continue
                val check1 = pos.offset(dx, 1, dz)
                val check2 = pos.offset(dx, 2, dz)
                val s1 = level.getBlockState(check1)
                val s2 = level.getBlockState(check2)
                if (!s1.isAir && !s1.getCollisionShape(level, check1).isEmpty) return false
                if (!s2.isAir && !s2.getCollisionShape(level, check2).isEmpty) return false
            }
        }
        return true
    }

    fun findValidLandingFloor(pos: BlockPos): BlockPos? {
        if (isValidLandingSpot(pos)) return pos
        for (dy in 1..8) {
            val below = pos.below(dy)
            if (isValidLandingSpot(below)) return below
            val above = pos.above(dy)
            if (isValidLandingSpot(above)) return above
        }
        return null
    }

    fun isValidLandingSpot(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        val floorState = level.getBlockState(pos)
        if (floorState.isAir || floorState.getCollisionShape(level, pos).isEmpty) return false
        if (floorState.`is`(Blocks.LAVA) || floorState.`is`(Blocks.FIRE)) return false

        val above1 = pos.above(1)
        val above2 = pos.above(2)
        val s1 = level.getBlockState(above1)
        val s2 = level.getBlockState(above2)
        return s1.isAir && s2.isAir
    }

    fun getOverheadClearance(pos: BlockPos, maxCheck: Int = 16): Int {
        val level = mc.level ?: return maxCheck
        for (dy in 1..maxCheck) {
            val checkPos = pos.above(dy)
            val state = level.getBlockState(checkPos)
            if (!state.isAir) {
                return dy
            }
        }
        return maxCheck
    }

    fun findNearestStructureExit(start: BlockPos, maxSearchRadius: Int = 24): BlockPos? {
        val level = mc.level ?: return null
        var bestExit: BlockPos? = null
        var bestDist = Double.MAX_VALUE

        for (dx in -maxSearchRadius..maxSearchRadius) {
            for (dz in -maxSearchRadius..maxSearchRadius) {
                if (dx * dx + dz * dz > maxSearchRadius * maxSearchRadius) continue
                for (dy in -3..4) {
                    val candidate = start.offset(dx, dy, dz)
                    if (isValidLandingSpot(candidate)) {
                        val clearance = getOverheadClearance(candidate, maxCheck = 16)
                        if (clearance >= 7) { // Open sky or tall outdoor clearance!
                            val dist = start.distSqr(candidate)
                            if (dist < bestDist) {
                                bestDist = dist
                                bestExit = candidate
                            }
                        }
                    }
                }
            }
        }
        return bestExit
    }

    fun findVantagePointForTarget(startPos: BlockPos, targetBlock: BlockPos, maxRange: Double, maxWalkDist: Int = 6): BlockPos? {
        val level = mc.level ?: return null
        val candidates = mutableListOf<BlockPos>()

        for (dx in -maxWalkDist..maxWalkDist) {
            for (dz in -maxWalkDist..maxWalkDist) {
                if (dx * dx + dz * dz > maxWalkDist * maxWalkDist || (dx == 0 && dz == 0)) continue
                for (dy in -2..2) {
                    val candidate = startPos.offset(dx, dy, dz)
                    if (isValidLandingSpot(candidate)) {
                        val vEye = Vec3(candidate.x + 0.5, candidate.y + 1.62, candidate.z + 0.5)
                        if (TeleportAbilityHelper.canEtherwarpTo(vEye, targetBlock, maxRange)) {
                            candidates.add(candidate)
                        }
                    }
                }
            }
        }

        return candidates.minByOrNull { startPos.distSqr(it) }
    }

    private fun reconstructPath(endNode: PathNode): List<PathNode> {
        val path = mutableListOf<PathNode>()
        var curr: PathNode? = endNode
        while (curr != null) {
            path.add(curr)
            curr = curr.parent
        }
        return path.reversed()
    }
}
