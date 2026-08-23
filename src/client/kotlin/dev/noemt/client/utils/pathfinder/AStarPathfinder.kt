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

class AStarPathfinder(
    val startPos: BlockPos,
    val goalPos: BlockPos,
    val allowAotv: Boolean = true,
    val allowEtherwarp: Boolean = true,
    val maxExpansions: Int = 2000
) {
    private val mc = Minecraft.getInstance()
    private val teleportProfile = if (allowAotv) TeleportAbilityHelper.getBestTeleportItem() else null

    private val openSet = PriorityQueue<PathNode>()
    private val allNodes = HashMap<Long, PathNode>()
    private val closedSet = HashSet<Long>()

    fun findPath(): List<PathNode>? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null

        val startFloor = findNearestFloor(startPos) ?: startPos
        val goalFloor = findNearestFloor(goalPos) ?: goalPos

        // 1. Instant 1-Shot Direct Etherwarp Check (Goal in direct line of sight at sufficient distance/elevation)
        val profile = teleportProfile
        if (allowEtherwarp && profile != null && profile.hasEtherwarp) {
            val startEye = Vec3(startFloor.x + 0.5, startFloor.y + 1.62, startFloor.z + 0.5)
            val distToGoal = startFloor.distManhattan(goalFloor)
            val elevationDiff = abs(startFloor.y - goalFloor.y)

            if ((distToGoal >= 7 || elevationDiff >= 2) && TeleportAbilityHelper.canEtherwarpTo(startEye, goalFloor, profile.etherwarpRange)) {
                val startNode = PathNode(startFloor, null, PathAction.WALK, 0.0, 0.0)
                val goalNode = PathNode(goalFloor, startNode, PathAction.ETHERWARP, 1.5, 0.0, actionData = goalFloor)
                return listOf(startNode, goalNode)
            }
        }

        val startNode = PathNode(
            pos = startFloor,
            parent = null,
            action = PathAction.WALK,
            gCost = 0.0,
            hCost = calculateHeuristic(startFloor, goalFloor)
        )

        openSet.add(startNode)
        allNodes[startNode.packedPos] = startNode

        var closestNode: PathNode = startNode
        var closestDist: Double = startFloor.distSqr(goalFloor)
        var expansions = 0

        while (openSet.isNotEmpty() && expansions < maxExpansions) {
            val current = openSet.poll() ?: break
            val currentPacked = current.packedPos

            if (closedSet.contains(currentPacked)) continue
            closedSet.add(currentPacked)
            expansions++

            val distToGoalSq = current.pos.distSqr(goalFloor)
            if (distToGoalSq < closestDist) {
                closestDist = distToGoalSq
                closestNode = current
            }

            // Goal reached!
            if (current.pos == goalFloor || (distToGoalSq <= 2.0 && abs(current.pos.y - goalFloor.y) <= 1)) {
                return reconstructPath(current)
            }

            // Expand neighbor transitions
            expandNeighbors(current, goalFloor)
        }

        // Return best partial path if goal wasn't strictly reached but made progress
        if (closestNode != startNode && closestDist < startFloor.distSqr(goalFloor)) {
            return reconstructPath(closestNode)
        }

        return null
    }

    private fun expandNeighbors(current: PathNode, goal: BlockPos) {
        val level = mc.level ?: return
        val player = mc.player ?: return
        val pos = current.pos
        val maxStep = MovementPhysicsHelper.maxStepUpHeight
        val maxDrop = MovementPhysicsHelper.maxSafeDropHeight
        val maxGap = MovementPhysicsHelper.maxHorizontalGapJump
        val speedFactor = MovementPhysicsHelper.horizontalSpeedBlocksPerTick / 0.28

        val profile = teleportProfile
        val isTargetHigher = (goal.y - pos.y) > 2

        // 1. Direct Etherwarp Check from current node to goal
        if (allowEtherwarp && profile != null && profile.hasEtherwarp) {
            val eye = current.eyeVec
            if (TeleportAbilityHelper.canEtherwarpTo(eye, goal, profile.etherwarpRange)) {
                addNeighbor(current, goal, PathAction.ETHERWARP, 0.5, goal, actionData = goal)
            }

            // Targeted Goal-Cone Probing (Fast, direct, and hyper-efficient)
            val dx = (goal.x - pos.x).toDouble()
            val dy = (goal.y - pos.y).toDouble()
            val dz = (goal.z - pos.z).toDouble()
            val goalDir = Vec3(dx, dy, dz).normalize()

            val baseYaw = -atan2(goalDir.x, goalDir.z) * (180.0 / Math.PI)
            val basePitch = -atan2(goalDir.y, sqrt(goalDir.x * goalDir.x + goalDir.z * goalDir.z)) * (180.0 / Math.PI)

            val targetedYawOffsets = floatArrayOf(0f, -25f, 25f, -50f, 50f)
            val targetedPitchOffsets = floatArrayOf(0f, 25f, -20f)

            for (pOff in targetedPitchOffsets) {
                val tPitch = (basePitch + pOff).coerceIn(-89.0, 89.0).toFloat()
                for (yOff in targetedYawOffsets) {
                    val tYaw = (baseYaw + yOff).toFloat()
                    val lookVec = RotationUtils.getLookVec(tYaw, tPitch)
                    val rayEnd = eye.add(lookVec.scale(profile.etherwarpRange))
                    val hit = level.clip(ClipContext(eye, rayEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))

                    if (hit.type == HitResult.Type.BLOCK) {
                        val hitPos = (hit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                            ?: BlockPos.containing(hit.location.x, hit.location.y, hit.location.z)
                        val candidateFloor = findNearestFloor(hitPos) ?: hitPos

                        if (candidateFloor != pos && isWalkable(candidateFloor)) {
                            val dist = eye.distanceTo(Vec3(candidateFloor.x + 0.5, candidateFloor.y + 1.0, candidateFloor.z + 0.5))
                            if (dist in 10.0..profile.etherwarpRange) {
                                if (TeleportAbilityHelper.canEtherwarpTo(eye, candidateFloor, profile.etherwarpRange)) {
                                    val cost = if (isTargetHigher && candidateFloor.y > pos.y) 0.8 else 1.2
                                    addNeighbor(current, candidateFloor, PathAction.ETHERWARP, cost, goal, actionData = candidateFloor)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Instant Transmission (Directional Swept Blinks)
        if (profile != null) {
            val blinkRange = profile.instantTransmissionRange
            val eye = current.eyeVec
            val goalDir = Vec3((goal.x - pos.x).toDouble(), (goal.y - pos.y).toDouble(), (goal.z - pos.z).toDouble()).normalize()

            val blinkDirs = arrayOf(
                goalDir,
                Vec3(goalDir.x, 0.0, goalDir.z).normalize(),
                Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0),
                Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0)
            )

            for (bDir in blinkDirs) {
                if (bDir.lengthSqr() < 0.01) continue
                val clearDist = TeleportAbilityHelper.canInstantTransmissionForward(eye, bDir, blinkRange)
                if (clearDist != null && clearDist >= 4.5) {
                    val endPoint = eye.add(bDir.scale(clearDist))
                    val groundPos = BlockPos.containing(endPoint.x, endPoint.y - 1.0, endPoint.z)

                    val landingFloor = if (isWalkable(groundPos)) {
                        groundPos
                    } else if (isWalkable(groundPos.below(1))) {
                        groundPos.below(1)
                    } else null

                    if (landingFloor != null && landingFloor != pos) {
                        val elevationBonus = if (isTargetHigher && landingFloor.y > pos.y) 0.2 else 0.4
                        addNeighbor(current, landingFloor, PathAction.INSTANT_TRANSMISSION, elevationBonus, goal, actionData = endPoint)
                    }
                }
            }
        }

        // 3. Cardinal Walking, Step-ups, and Drops (With low-ceiling structure penalty)
        val cardinals = arrayOf(
            BlockPos(1, 0, 0),
            BlockPos(-1, 0, 0),
            BlockPos(0, 0, 1),
            BlockPos(0, 0, -1)
        )

        for (dir in cardinals) {
            val nextFlat = pos.offset(dir.x, 0, dir.z)

            // Flat Walk
            if (isWalkable(nextFlat)) {
                val structurePenalty = if (isTargetHigher) {
                    val clearance = getOverheadClearance(nextFlat)
                    if (clearance <= 3) 4.0 else if (clearance <= 5) 1.5 else 0.0
                } else 0.0

                // Center-bias penalty (+0.3) and narrow cliff edge penalty (+3.5) to keep player safe in the center of paths
                val wallPenalty = if (!TeleportPathfinder.isClearOfWalls(nextFlat, radius = 1)) 0.3 else 0.0
                val edgePenalty = getNarrowEdgePenalty(nextFlat)

                addNeighbor(current, nextFlat, PathAction.WALK, (1.0 + structurePenalty + wallPenalty + edgePenalty) / speedFactor, goal)
                continue
            }

            // Step Up
            var steppedUp = false
            for (dy in 1..maxStep) {
                val nextUp = pos.offset(dir.x, dy, dir.z)
                if (isWalkable(nextUp) && hasHeadClearance(pos, dy + 2)) {
                    val stepCost = (1.0 + dy * 0.4) / speedFactor + 0.2
                    addNeighbor(current, nextUp, PathAction.JUMP_UP, stepCost, goal)
                    steppedUp = true
                    break
                }
            }
            if (steppedUp) continue

            // Drop Down
            for (dy in 1..maxDrop) {
                val nextDrop = pos.offset(dir.x, -dy, dir.z)
                if (isWalkable(nextDrop)) {
                    var colClear = true
                    for (checkY in 0 downTo -dy + 1) {
                        val checkPos = pos.offset(dir.x, checkY, dir.z)
                        if (!isPassable(checkPos.above(1)) || !isPassable(checkPos.above(2))) {
                            colClear = false
                            break
                        }
                    }
                    if (colClear) {
                        addNeighbor(current, nextDrop, PathAction.DROP, 1.0 + dy * 0.2, goal)
                    }
                    break
                }
            }

            // Gap Jumps
            for (gap in 2..maxGap) {
                val landingPos = pos.offset(dir.x * gap, 0, dir.z * gap)
                val landingDrop = pos.offset(dir.x * gap, -1, dir.z * gap)
                val targetLanding = if (isWalkable(landingPos)) landingPos else if (isWalkable(landingDrop)) landingDrop else null

                if (targetLanding != null) {
                    var gapClear = true
                    for (step in 1 until gap) {
                        val gapBlock = pos.offset(dir.x * step, 0, dir.z * step)
                        if (!isPassable(gapBlock.above(1)) || !isPassable(gapBlock.above(2))) {
                            gapClear = false
                            break
                        }
                    }
                    if (gapClear) {
                        addNeighbor(current, targetLanding, PathAction.SPRINT_JUMP, (gap * 0.8) / speedFactor + 0.3, goal)
                    }
                }
            }
        }

        // 4. Diagonal Walking
        val diagonals = arrayOf(
            Pair(BlockPos(1, 0, 1), Pair(BlockPos(1, 0, 0), BlockPos(0, 0, 1))),
            Pair(BlockPos(1, 0, -1), Pair(BlockPos(1, 0, 0), BlockPos(0, 0, -1))),
            Pair(BlockPos(-1, 0, 1), Pair(BlockPos(-1, 0, 0), BlockPos(0, 0, 1))),
            Pair(BlockPos(-1, 0, -1), Pair(BlockPos(-1, 0, 0), BlockPos(0, 0, -1)))
        )

        for ((diag, orthogonals) in diagonals) {
            val nextDiag = pos.offset(diag.x, 0, diag.z)
            val (o1, o2) = orthogonals
            val c1 = isPassable(pos.offset(o1.x, 1, o1.z)) || isPassable(pos.offset(o1.x, 2, o1.z))
            val c2 = isPassable(pos.offset(o2.x, 1, o2.z)) || isPassable(pos.offset(o2.x, 2, o2.z))
            if (!c1 && !c2) continue

            if (isWalkable(nextDiag)) {
                val structurePenalty = if (isTargetHigher) {
                    val clearance = getOverheadClearance(nextDiag)
                    if (clearance <= 3) 4.0 else if (clearance <= 5) 1.5 else 0.0
                } else 0.0

                val wallPenalty = if (!TeleportPathfinder.isClearOfWalls(nextDiag, radius = 1)) 0.3 else 0.0

                addNeighbor(current, nextDiag, PathAction.WALK, (1.414 + structurePenalty + wallPenalty) / speedFactor, goal)
            }
        }
    }

    private fun addNeighbor(current: PathNode, neighborPos: BlockPos, action: PathAction, transitionCost: Double, goal: BlockPos, actionData: Any? = null) {
        val packed = neighborPos.asLong()
        if (closedSet.contains(packed)) return

        // Theta* Any-Angle Optimization: Connect directly to current.parent if line of sight is walkable!
        var effectiveParent = current
        var effectiveAction = action
        var effectiveGCost = current.gCost + transitionCost
        var effectiveActionData = actionData

        if (action == PathAction.WALK && current.parent != null && current.parent?.action == PathAction.WALK) {
            val grandParent = current.parent!!
            if (abs(grandParent.pos.y - neighborPos.y) <= 1 && isLineOfSightWalkable(grandParent.pos, neighborPos)) {
                val directDist = grandParent.standingVec.distanceTo(Vec3(neighborPos.x + 0.5, neighborPos.y + 1.0, neighborPos.z + 0.5))
                val thetaG = grandParent.gCost + directDist
                if (thetaG < effectiveGCost) {
                    effectiveParent = grandParent
                    effectiveAction = PathAction.WALK
                    effectiveGCost = thetaG
                    effectiveActionData = null
                }
            }
        }

        val existing = allNodes[packed]

        if (existing == null) {
            val node = PathNode(
                pos = neighborPos,
                parent = effectiveParent,
                action = effectiveAction,
                gCost = effectiveGCost,
                hCost = calculateHeuristic(neighborPos, goal),
                actionData = effectiveActionData
            )
            allNodes[packed] = node
            openSet.add(node)
        } else if (effectiveGCost < existing.gCost) {
            openSet.remove(existing)
            existing.parent = effectiveParent
            existing.action = effectiveAction
            existing.gCost = effectiveGCost
            existing.actionData = effectiveActionData
            openSet.add(existing)
        }
    }

    fun isLineOfSightWalkable(from: BlockPos, to: BlockPos): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false

        val startVec = Vec3(from.x + 0.5, from.y + 1.0, from.z + 0.5)
        val endVec = Vec3(to.x + 0.5, to.y + 1.0, to.z + 0.5)
        val dist = startVec.distanceTo(endVec)
        if (dist > 18.0) return false

        val dir = endVec.subtract(startVec).normalize()
        val orthoX = -dir.z * 0.25
        val orthoZ = dir.x * 0.25

        val eyeStart = startVec.add(0.0, 1.62, 0.0)
        val eyeEnd = endVec.add(0.0, 1.62, 0.0)

        // 1. Raycast eye line of sight (Center, Left Shoulder, Right Shoulder) against all block outlines
        val rays = arrayOf(
            Pair(eyeStart, eyeEnd),
            Pair(eyeStart.add(orthoX, 0.0, orthoZ), eyeEnd.add(orthoX, 0.0, orthoZ)),
            Pair(eyeStart.add(-orthoX, 0.0, -orthoZ), eyeEnd.add(-orthoX, 0.0, -orthoZ))
        )

        for ((rStart, rEnd) in rays) {
            val hit = level.clip(ClipContext(rStart, rEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            if (hit.type == HitResult.Type.BLOCK) {
                val hitBlock = (hit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                    ?: BlockPos.containing(hit.location.x, hit.location.y, hit.location.z)
                if (hitBlock != from && hitBlock != to && hitBlock != to.above(1)) return false
            }
        }

        val steps = ceil(dist * 3.0).toInt().coerceAtLeast(1)

        // 2. Sample ground support and body clearance along trajectory
        for (i in 0..steps) {
            val progress = i.toDouble() / steps
            val currentPos = startVec.lerp(endVec, progress)

            val footPos = BlockPos.containing(currentPos.x, currentPos.y, currentPos.z)
            val headPos = footPos.above(1)
            val groundPos = footPos.below(1)

            val sFoot = level.getBlockState(footPos)
            val sHead = level.getBlockState(headPos)
            val sGround = level.getBlockState(groundPos)

            // Foot and head must be 100% pure air (no partial blocks, carpets, slabs, trapdoors, signs)
            if (!sFoot.isAir || !sHead.isAir) return false

            // Ground must be solid floor
            if (sGround.isAir || sGround.getCollisionShape(level, groundPos).isEmpty) return false
        }

        return true
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

    private fun calculateHeuristic(from: BlockPos, to: BlockPos): Double {
        val dx = (from.x - to.x).toDouble()
        val dy = (from.y - to.y).toDouble()
        val dz = (from.z - to.z).toDouble()
        return 1.001 * sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun isWalkable(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        val floorState = level.getBlockState(pos)
        if (floorState.isAir || floorState.getCollisionShape(level, pos).isEmpty) return false

        if (floorState.`is`(Blocks.LAVA) || floorState.`is`(Blocks.FIRE) || floorState.`is`(Blocks.CACTUS)) return false

        val above1 = pos.above(1)
        val above2 = pos.above(2)
        return isPassable(above1) && isPassable(above2)
    }

    fun isPassable(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(pos)
        return state.isAir
    }

    private fun hasHeadClearance(pos: BlockPos, upHeight: Int): Boolean {
        for (y in 1..upHeight) {
            if (!isPassable(pos.above(y))) return false
        }
        return true
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

    fun getNarrowEdgePenalty(pos: BlockPos): Double {
        val level = mc.level ?: return 0.0
        var openSides = 0
        val cardinals = arrayOf(
            BlockPos(1, 0, 0), BlockPos(-1, 0, 0),
            BlockPos(0, 0, 1), BlockPos(0, 0, -1)
        )
        for (c in cardinals) {
            val side = pos.offset(c)
            val sideFloor = side.below(1)
            val sideState = level.getBlockState(sideFloor)
            if (sideState.isAir || sideState.getCollisionShape(level, sideFloor).isEmpty) {
                openSides++
            }
        }
        return if (openSides >= 2) 3.5 else if (openSides == 1) 0.8 else 0.0
    }

    fun findNearestFloor(pos: BlockPos): BlockPos? {
        if (isWalkable(pos)) return pos
        for (dy in 1..6) {
            val below = pos.below(dy)
            if (isWalkable(below)) return below
            val above = pos.above(dy)
            if (isWalkable(above)) return above
        }
        return null
    }
}
