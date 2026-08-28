package dev.noemt.client.utils

import dev.noemt.client.utils.map.core.RoomTile
import dev.noemt.client.utils.map.core.RoomType
import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.utils.ScanUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.*

object PathfindingUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()
    var isControllingMovement = false
        private set

    fun hasLineOfSight(from: Vec3, to: Vec3): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type == HitResult.Type.MISS) return true
        return hit.location.distanceToSqr(to) < 0.6
    }

    fun moveTo(target: Vec3, sprint: Boolean = false) {
        val player = mc.player ?: return
        val dx = target.x - player.x
        val dz = target.z - player.z
        val distSq = dx * dx + dz * dz

        if (distSq < 0.35) {
            stopMovement()
            return
        }

        isControllingMovement = true

        val targetYaw = -atan2(dx, dz) * (180.0 / Math.PI)
        var yawDiff = (targetYaw - player.yRot) % 360.0
        if (yawDiff > 180.0) yawDiff -= 360.0
        if (yawDiff < -180.0) yawDiff += 360.0

        val forward = yawDiff in -65.0..65.0
        val back = yawDiff > 115.0 || yawDiff < -115.0
        val left = yawDiff in -155.0..-25.0
        val right = yawDiff in 25.0..155.0

        mc.options.keyUp.isDown = forward
        mc.options.keyDown.isDown = back
        mc.options.keyLeft.isDown = left
        mc.options.keyRight.isDown = right
        mc.options.keySprint.isDown = sprint && forward

        val shouldJump = player.horizontalCollision || isBlockInMoveDirection(dx, dz)
        mc.options.keyJump.isDown = shouldJump
    }

    private fun isBlockInMoveDirection(dx: Double, dz: Double): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val len = hypot(dx, dz)
        if (len < 0.001) return false
        val normX = (dx / len) * 0.7
        val normZ = (dz / len) * 0.7
        val checkPos = BlockPos.containing(player.x + normX, player.y + 0.5, player.z + normZ)
        val state = level.getBlockState(checkPos)
        return !state.isAir && !state.getCollisionShape(level, checkPos).isEmpty
    }

    fun setStrafeInput(dir: Int) {
        isControllingMovement = (dir != 0)
        mc.options.keyLeft.isDown = (dir == -1)
        mc.options.keyRight.isDown = (dir == 1)
        mc.options.keyUp.isDown = false
        mc.options.keyDown.isDown = false
        mc.options.keySprint.isDown = false
        mc.options.keyJump.isDown = false
    }

    fun stopMovement() {
        if (!isControllingMovement) return
        isControllingMovement = false
        mc.options.keyUp.isDown = false
        mc.options.keyDown.isDown = false
        mc.options.keyLeft.isDown = false
        mc.options.keyRight.isDown = false
        mc.options.keyJump.isDown = false
        mc.options.keySprint.isDown = false
    }

    fun hasCenterLineOfSight(pos: BlockPos, roomCenter: Vec3): Boolean {
        val eyePos = Vec3(pos.x + 0.5, pos.y + 1.62, pos.z + 0.5)
        val centerTarget1 = Vec3(roomCenter.x, 69.5, roomCenter.z)
        val centerTarget2 = Vec3(roomCenter.x, 72.0, roomCenter.z)
        return hasLineOfSight(eyePos, centerTarget1) || hasLineOfSight(eyePos, centerTarget2)
    }

    fun getBloodRoomFloorPositions(): List<BlockPos> {
        val level = mc.level ?: return emptyList()
        val player = mc.player ?: return emptyList()

        val center = dev.noemt.client.features.blood.AutoBloodCamp.getBloodRoomCenter()
            ?: return emptyList()

        val floorPositions = mutableListOf<BlockPos>()
        val cX = center.x
        val cZ = center.z

        for (x in (cX - 13)..(cX + 13)) {
            for (z in (cZ - 13)..(cZ + 13)) {
                for (y in 68..71) {
                    val pos = BlockPos(x, y, z)
                    val state = level.getBlockState(pos)
                    if (state.isAir || state.getCollisionShape(level, pos).isEmpty) continue

                    val above1 = pos.above(1)
                    val above2 = pos.above(2)
                    val s1 = level.getBlockState(above1)
                    val s2 = level.getBlockState(above2)

                    // Must have clear standing headroom of 2 blocks above floor
                    if ((s1.isAir || s1.getCollisionShape(level, above1).isEmpty) &&
                        (s2.isAir || s2.getCollisionShape(level, above2).isEmpty)) {
                        floorPositions.add(pos)
                        break
                    }
                }
            }
        }
        return floorPositions
    }

    private fun minDistanceToTnts(pos: BlockPos, tntPositions: List<Vec3>): Double {
        if (tntPositions.isEmpty()) return Double.MAX_VALUE
        val px = pos.x + 0.5
        val py = pos.y + 1.0
        val pz = pos.z + 0.5
        return tntPositions.minOf { tnt ->
            val d2d = hypot(px - tnt.x, pz - tnt.z)
            val d3d = sqrt((px - tnt.x).pow(2) + (py - tnt.y).pow(2) + (pz - tnt.z).pow(2))
            min(d2d, d3d)
        }
    }

    fun findSafePositionFromTnts(tntPositions: List<Vec3>, minDistance: Double = 6.0): BlockPos? {
        val player = mc.player ?: return null
        val candidates = getBloodRoomFloorPositions()
        if (candidates.isEmpty()) return null

        val roomCenter = dev.noemt.client.features.blood.AutoBloodCamp.getBloodRoomCenter()?.let {
            Vec3(it.x + 0.5, 69.5, it.z + 0.5)
        }

        // Candidates must be >= 6 blocks from TNT and not blocked behind pillars
        val safeCandidates = candidates.filter { pos ->
            minDistanceToTnts(pos, tntPositions) >= minDistance &&
            (roomCenter == null || hasCenterLineOfSight(pos, roomCenter))
        }

        if (safeCandidates.isNotEmpty()) {
            return safeCandidates.minByOrNull { pos ->
                val center = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                player.position().distanceToSqr(center)
            }
        }

        // Fallback: Maximize TNT distance among spots not blocked by pillars
        val nonBlocked = candidates.filter { roomCenter == null || hasCenterLineOfSight(it, roomCenter) }
        return (if (nonBlocked.isNotEmpty()) nonBlocked else candidates).maxByOrNull { minDistanceToTnts(it, tntPositions) }
    }

    fun findAotvSafePositionFromTnts(tntPositions: List<Vec3>, minDistance: Double = 6.0): BlockPos? {
        val player = mc.player ?: return null
        val eyePos = player.eyePosition
        val candidates = getBloodRoomFloorPositions()
        if (candidates.isEmpty()) return null

        val roomCenter = dev.noemt.client.features.blood.AutoBloodCamp.getBloodRoomCenter()?.let {
            Vec3(it.x + 0.5, 69.5, it.z + 0.5)
        }

        // Must be a solid floor block top (never airborne), line-of-sight for Etherwarp, and clear of pillars
        val safeCandidates = candidates.filter { pos ->
            val targetTop = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            val dist = eyePos.distanceTo(targetTop)

            dist in 4.0..14.0 &&
            minDistanceToTnts(pos, tntPositions) >= minDistance &&
            hasLineOfSight(eyePos, targetTop) &&
            (roomCenter == null || hasCenterLineOfSight(pos, roomCenter))
        }

        if (safeCandidates.isNotEmpty()) {
            return safeCandidates.maxByOrNull { minDistanceToTnts(it, tntPositions) }
        }

        return candidates.filter { pos ->
            val targetTop = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            val dist = eyePos.distanceTo(targetTop)
            dist in 4.0..14.0 &&
            hasLineOfSight(eyePos, targetTop) &&
            (roomCenter == null || hasCenterLineOfSight(pos, roomCenter))
        }.maxByOrNull { minDistanceToTnts(it, tntPositions) }
    }

    fun findAotvShootingPosition(target: Vec3, tntPositions: List<Vec3>): BlockPos? {
        val player = mc.player ?: return null
        val eyePos = player.eyePosition
        val candidates = getBloodRoomFloorPositions()
        if (candidates.isEmpty()) return null

        return candidates.filter { pos ->
            val targetTop = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            val candidateEye = Vec3(pos.x + 0.5, pos.y + 1.62, pos.z + 0.5)
            val dist = eyePos.distanceTo(targetTop)

            dist in 4.0..13.0 &&
            minDistanceToTnts(pos, tntPositions) >= 6.0 &&
            hasLineOfSight(eyePos, targetTop) &&
            hasLineOfSight(candidateEye, target)
        }.minByOrNull { pos ->
            val targetTop = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            targetTop.distanceToSqr(target)
        }
    }

    fun findBestShootingPosition(target: Vec3, tntPositions: List<Vec3>): BlockPos? {
        val player = mc.player ?: return null
        val candidates = getBloodRoomFloorPositions()
        if (candidates.isEmpty()) return null

        val safeCandidates = candidates.filter { pos ->
            minDistanceToTnts(pos, tntPositions) >= 6.0
        }

        return safeCandidates
            .filter { pos ->
                val eyePos = Vec3(pos.x + 0.5, pos.y + 1.62, pos.z + 0.5)
                hasLineOfSight(eyePos, target)
            }
            .minByOrNull { pos ->
                val center = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                player.position().distanceToSqr(center)
            } ?: safeCandidates.minByOrNull { pos ->
                val center = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                player.position().distanceToSqr(center)
            }
    }
}
