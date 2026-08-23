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

        val forward = yawDiff in -60.0..60.0
        val back = yawDiff > 120.0 || yawDiff < -120.0
        val left = yawDiff in -150.0..-30.0
        val right = yawDiff in 30.0..150.0

        mc.options.keyUp.isDown = forward
        mc.options.keyDown.isDown = back
        mc.options.keyLeft.isDown = left
        mc.options.keyRight.isDown = right
        mc.options.keySprint.isDown = sprint && forward

        val shouldJump = player.horizontalCollision || isBlockInFront()
        mc.options.keyJump.isDown = shouldJump
    }

    private fun isBlockInFront(): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val look = RotationUtils.getLookVec(player.yRot, 0f).scale(0.7)
        val checkPos = BlockPos.containing(player.x + look.x, player.y + 0.5, player.z + look.z)
        val state = level.getBlockState(checkPos)
        return !state.isAir && !state.getCollisionShape(level, checkPos).isEmpty
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

    fun hasPillarClearance(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val b1 = pos.offset(dx, 1, dz)
                val b2 = pos.offset(dx, 2, dz)
                val s1 = level.getBlockState(b1)
                val s2 = level.getBlockState(b2)
                if (!s1.isAir && !s1.getCollisionShape(level, b1).isEmpty) return false
                if (!s2.isAir && !s2.getCollisionShape(level, b2).isEmpty) return false
            }
        }
        return true
    }

    fun hasCenterLineOfSight(pos: BlockPos, roomCenter: Vec3): Boolean {
        val eyePos = Vec3(pos.x + 0.5, pos.y + 1.62, pos.z + 0.5)
        val centerTarget1 = Vec3(roomCenter.x, 69.5, roomCenter.z)
        val centerTarget2 = Vec3(roomCenter.x, 72.0, roomCenter.z)
        return hasLineOfSight(eyePos, centerTarget1) || hasLineOfSight(eyePos, centerTarget2)
    }

    fun getBloodRoomFloorPositions(): List<BlockPos> {
        val level = mc.level ?: return emptyList()
        val bloodRoom = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD } ?: return emptyList()
        val roomCenter = Vec3(bloodRoom.centerPos.x.toDouble(), 69.0, bloodRoom.centerPos.z.toDouble())
        val floorPositions = mutableListOf<BlockPos>()

        val tiles = bloodRoom.tiles.filterIsInstance<RoomTile>()
        for (tile in tiles) {
            val startX = tile.x - 13
            val endX = tile.x + 13
            val startZ = tile.z - 13
            val endZ = tile.z + 13

            for (x in startX..endX) {
                for (z in startZ..endZ) {
                    for (y in 68..70) {
                        val pos = BlockPos(x, y, z)
                        val state = level.getBlockState(pos)
                        if (state.isAir || state.getCollisionShape(level, pos).isEmpty) continue

                        val above1 = pos.above(1)
                        val above2 = pos.above(2)
                        val s1 = level.getBlockState(above1)
                        val s2 = level.getBlockState(above2)

                        if ((s1.isAir || s1.getCollisionShape(level, above1).isEmpty) &&
                            (s2.isAir || s2.getCollisionShape(level, above2).isEmpty)) {

                            if (hasPillarClearance(pos) && hasCenterLineOfSight(pos, roomCenter)) {
                                floorPositions.add(pos)
                            }
                            break
                        }
                    }
                }
            }
        }
        return floorPositions
    }

    fun findSafePositionFromTnts(tntPositions: List<Vec3>, minDistance: Double = 7.5): BlockPos? {
        val player = mc.player ?: return null
        val candidates = getBloodRoomFloorPositions()
        if (candidates.isEmpty()) return null

        return candidates
            .filter { pos ->
                val center = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                tntPositions.all { tnt -> center.distanceTo(tnt) >= minDistance }
            }
            .minByOrNull { pos ->
                val center = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                player.position().distanceToSqr(center)
            }
    }

    fun findAotvSafePositionFromTnts(tntPositions: List<Vec3>, minDistance: Double = 7.5): BlockPos? {
        val player = mc.player ?: return null
        val eyePos = player.eyePosition
        val candidates = getBloodRoomFloorPositions()
        if (candidates.isEmpty()) return null

        return candidates.filter { pos ->
            val targetTop = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            val dist = eyePos.distanceTo(targetTop)

            dist in 3.5..13.0 &&
            tntPositions.all { tnt -> targetTop.distanceTo(tnt) >= minDistance } &&
            hasLineOfSight(eyePos, targetTop)
        }.maxByOrNull { pos ->
            val targetTop = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            tntPositions.minOfOrNull { it.distanceTo(targetTop) } ?: 0.0
        }
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
            tntPositions.all { tnt -> targetTop.distanceTo(tnt) >= 7.0 } &&
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
            val center = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            tntPositions.all { tnt -> center.distanceTo(tnt) >= 7.0 }
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
