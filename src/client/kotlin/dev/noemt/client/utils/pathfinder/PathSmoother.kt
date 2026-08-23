package dev.noemt.client.utils.pathfinder

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

object PathSmoother {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun smoothPath(rawPath: List<PathNode>): List<PathNode> {
        if (rawPath.size <= 2) return rawPath

        val smoothed = mutableListOf<PathNode>()
        smoothed.add(rawPath.first())

        val profile = TeleportAbilityHelper.getBestTeleportItem()
        val blinkRange = profile?.instantTransmissionRange ?: 12.0
        var currentIndex = 0

        while (currentIndex < rawPath.size - 1) {
            val currentNode = rawPath[currentIndex]

            // If the current node or next transition requires a specific teleport/jump action, keep it intact
            if (currentNode.action == PathAction.ETHERWARP || currentNode.action == PathAction.INSTANT_TRANSMISSION || currentNode.action == PathAction.SPRINT_JUMP) {
                currentIndex++
                smoothed.add(rawPath[currentIndex])
                continue
            }

            var furthestReachable = currentIndex + 1

            // Look ahead as far as possible for direct line-of-sight walking or forward blinks
            for (checkIndex in (currentIndex + 2) until rawPath.size) {
                val candidate = rawPath[checkIndex]

                // Do not skip over Etherwarp nodes
                if (candidate.action == PathAction.ETHERWARP) {
                    break
                }

                val dist = currentNode.standingVec.distanceTo(candidate.standingVec)
                val elevDiff = Math.abs(candidate.pos.y - currentNode.pos.y)

                if (profile != null && dist in 6.5..blinkRange && elevDiff <= 3) {
                    val dir = candidate.standingVec.subtract(currentNode.standingVec).normalize()
                    val clear = TeleportAbilityHelper.canInstantTransmissionForward(currentNode.eyeVec, dir, dist)
                    if (clear != null && clear >= dist - 1.0) {
                        furthestReachable = checkIndex
                        continue
                    }
                }

                if (elevDiff <= 2 && canWalkDirectly(currentNode.standingVec, candidate.standingVec)) {
                    furthestReachable = checkIndex
                } else {
                    break
                }
            }

            val targetNode = rawPath[furthestReachable]
            val segDist = currentNode.standingVec.distanceTo(targetNode.standingVec)

            // If segment is long enough and clear for Instant Transmission, upgrade to a normal forward blink!
            if (profile != null && segDist >= 6.5 && segDist <= blinkRange && targetNode.action == PathAction.WALK) {
                val dir = targetNode.standingVec.subtract(currentNode.standingVec).normalize()
                val clear = TeleportAbilityHelper.canInstantTransmissionForward(currentNode.eyeVec, dir, segDist)
                if (clear != null && clear >= segDist - 1.0) {
                    val blinkNode = PathNode(
                        pos = targetNode.pos,
                        parent = currentNode,
                        action = PathAction.INSTANT_TRANSMISSION,
                        gCost = currentNode.gCost + 0.2,
                        hCost = targetNode.hCost,
                        actionData = targetNode.eyeVec
                    )
                    smoothed.add(blinkNode)
                    currentIndex = furthestReachable
                    continue
                }
            }

            currentIndex = furthestReachable
            smoothed.add(rawPath[currentIndex])
        }

        return smoothed
    }

    private fun canWalkDirectly(start: Vec3, end: Vec3): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false

        val dist = start.distanceTo(end)
        val steps = ceil(dist * 2.5).toInt().coerceAtLeast(1)

        val eyeStart = start.add(0.0, 1.62, 0.0)
        val eyeEnd = end.add(0.0, 1.62, 0.0)

        // 1. Raycast eye line of sight against all block outlines (slabs, stairs, carpets, walls)
        val eyeHit = level.clip(ClipContext(eyeStart, eyeEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (eyeHit.type != HitResult.Type.MISS && eyeHit.location.distanceToSqr(eyeEnd) > 0.5) return false

        // 2. Sample player bounding box clearance along trajectory
        for (i in 0..steps) {
            val progress = i.toDouble() / steps
            val currentPos = start.lerp(end, progress)

            val footPos = BlockPos.containing(currentPos.x, currentPos.y, currentPos.z)
            val headPos = footPos.above(1)
            val groundPos = footPos.below(1)

            val sFoot = level.getBlockState(footPos)
            val sHead = level.getBlockState(headPos)
            val sGround = level.getBlockState(groundPos)

            // Foot and head must be 100% pure air (no carpets, slabs, trapdoors, stairs)
            if (!sFoot.isAir || !sHead.isAir) return false

            // Ground below must be solid floor
            if (sGround.isAir || sGround.getCollisionShape(level, groundPos).isEmpty) return false
            if (sGround.`is`(net.minecraft.world.level.block.Blocks.LAVA) || sGround.`is`(net.minecraft.world.level.block.Blocks.FIRE)) return false
        }

        return true
    }
}
