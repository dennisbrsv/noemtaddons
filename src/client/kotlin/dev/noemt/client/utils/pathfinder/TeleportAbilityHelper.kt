package dev.noemt.client.utils.pathfinder

import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.ItemUtils.customData
import dev.noemt.client.utils.ItemUtils.lore
import dev.noemt.client.utils.ItemUtils.skyblockId
import dev.noemt.client.utils.PlayerUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

data class TeleportItemProfile(
    val slot: Int,
    val itemStack: ItemStack,
    val skyblockId: String,
    val instantTransmissionRange: Double,
    val hasEtherwarp: Boolean,
    val etherwarpRange: Double
)

object TeleportAbilityHelper {
    private val mc: Minecraft get() = Minecraft.getInstance()

    private val instantTransmissionRegex = Regex("""Teleport\s+(\d+)\s+blocks?\s+ahead""", RegexOption.IGNORE_CASE)
    private val etherwarpDistanceRegex = Regex("""(?:targeted\s+block\s+up\s+to|targeted\s+block\s+up\s+to\s+about)\s+(\d+)\s+blocks\s+away""", RegexOption.IGNORE_CASE)

    fun getBestTeleportItem(): TeleportItemProfile? {
        val player = mc.player ?: return null
        var bestProfile: TeleportItemProfile? = null

        for (slot in 0..8) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue

            val profile = parseTeleportProfile(slot, stack) ?: continue
            if (bestProfile == null) {
                bestProfile = profile
            } else {
                val bestScore = (if (bestProfile.hasEtherwarp) 1000 else 0) + (if (bestProfile.skyblockId == "ASPECT_OF_THE_VOID") 100 else 0) + bestProfile.instantTransmissionRange.toInt()
                val currentScore = (if (profile.hasEtherwarp) 1000 else 0) + (if (profile.skyblockId == "ASPECT_OF_THE_VOID") 100 else 0) + profile.instantTransmissionRange.toInt()
                if (currentScore > bestScore) {
                    bestProfile = profile
                }
            }
        }

        return bestProfile
    }

    fun parseTeleportProfile(slot: Int, stack: ItemStack): TeleportItemProfile? {
        val sbId = stack.skyblockId
        val displayName = stack.hoverName.string.removeFormatting()

        val isAote = sbId == "ASPECT_OF_THE_END" || displayName.contains("Aspect of the End", ignoreCase = true)
        val isAotv = sbId == "ASPECT_OF_THE_VOID" || displayName.contains("Aspect of the Void", ignoreCase = true)

        if (!isAote && !isAotv) return null

        val loreLines = stack.lore.map { it.removeFormatting() }
        val fullLore = loreLines.joinToString("\n")

        // 1. Parse Instant Transmission range (Accounting for base 8 + Transmission Tuners +1 each up to 12)
        var instantRange = 8.0
        val matchInstant = instantTransmissionRegex.find(fullLore)
        if (matchInstant != null) {
            instantRange = matchInstant.groupValues[1].toDoubleOrNull() ?: instantRange
        } else {
            val customData = stack.customData
            val tuners = when {
                customData.contains("tuned_transmission") -> customData.getInt("tuned_transmission").orElse(0)
                customData.contains("transmission_tuners") -> customData.getInt("transmission_tuners").orElse(0)
                else -> 0
            }
            instantRange = (8.0 + tuners).coerceIn(8.0, 12.0)
        }

        // 2. Parse Etherwarp Conduit / Etherwarp Merger Ability
        var hasEtherwarp = false
        var etherwarpRange = 57.0

        if (fullLore.contains("Ether Transmission", ignoreCase = true) ||
            fullLore.contains("Etherwarp", ignoreCase = true) ||
            stack.customData.contains("ethermerge")) {

            hasEtherwarp = true
            val matchEther = etherwarpDistanceRegex.find(fullLore)
            if (matchEther != null) {
                etherwarpRange = matchEther.groupValues[1].toDoubleOrNull() ?: 57.0
            } else if (instantRange > 8.0) {
                val tuners = (instantRange - 8.0).toInt()
                etherwarpRange = 57.0 + tuners
            }
        }

        return TeleportItemProfile(
            slot = slot,
            itemStack = stack,
            skyblockId = if (isAotv) "ASPECT_OF_THE_VOID" else "ASPECT_OF_THE_END",
            instantTransmissionRange = instantRange.coerceIn(8.0, 12.0),
            hasEtherwarp = hasEtherwarp,
            etherwarpRange = etherwarpRange.coerceIn(57.0, 61.0)
        )
    }

    fun canEtherwarpTo(fromEye: Vec3, targetBlock: BlockPos, maxRange: Double = 57.0): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false

        // 1. Target block must be a solid ground block
        val floorState = level.getBlockState(targetBlock)
        if (floorState.isAir || floorState.getCollisionShape(level, targetBlock).isEmpty) return false

        // 2. Exactly 2 blocks of pure air MUST exist directly above the target block (carpets, slabs, stairs are not air!)
        val above1 = targetBlock.above(1)
        val above2 = targetBlock.above(2)
        val s1 = level.getBlockState(above1)
        val s2 = level.getBlockState(above2)
        if (!s1.isAir || !s2.isAir) return false

        // 3. Distance & Strict Line of Sight check: Cannot teleport through or graze any blocks (slabs, carpets, stairs, walls)!
        val aimPos = Vec3(targetBlock.x + 0.5, targetBlock.y + 0.5, targetBlock.z + 0.5)
        val dist = fromEye.distanceTo(aimPos)
        if (dist > maxRange || dist < 3.5) return false

        // Center line of sight against ALL block outlines (carpets, slabs, stairs, etc.)
        val hit = level.clip(ClipContext(fromEye, aimPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK) return false

        val hitBlockPos = (hit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
            ?: BlockPos.containing(hit.location.x, hit.location.y, hit.location.z)

        if (hitBlockPos != targetBlock) {
            // Intersected a wall, ceiling, slab, carpet, stair, or obstacle in between!
            return false
        }

        // Side, Top & Bottom clearance raycasts (ensures sightline isn't grazing door frames, wall corners, or slabs)
        val dir = aimPos.subtract(fromEye).normalize()
        val orthoX = -dir.z * 0.22
        val orthoZ = dir.x * 0.22

        val leftEye = fromEye.add(orthoX, 0.0, orthoZ)
        val rightEye = fromEye.add(-orthoX, 0.0, -orthoZ)
        val topEye = fromEye.add(0.0, 0.22, 0.0)
        val bottomEye = fromEye.add(0.0, -0.15, 0.0)

        val leftHit = level.clip(ClipContext(leftEye, aimPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (leftHit.type == HitResult.Type.BLOCK) {
            val lPos = (leftHit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                ?: BlockPos.containing(leftHit.location.x, leftHit.location.y, leftHit.location.z)
            if (lPos != targetBlock) return false
        }

        val rightHit = level.clip(ClipContext(rightEye, aimPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (rightHit.type == HitResult.Type.BLOCK) {
            val rPos = (rightHit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                ?: BlockPos.containing(rightHit.location.x, rightHit.location.y, rightHit.location.z)
            if (rPos != targetBlock) return false
        }

        val topHit = level.clip(ClipContext(topEye, aimPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (topHit.type == HitResult.Type.BLOCK) {
            val tPos = (topHit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                ?: BlockPos.containing(topHit.location.x, topHit.location.y, topHit.location.z)
            if (tPos != targetBlock) return false
        }

        val bottomHit = level.clip(ClipContext(bottomEye, aimPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (bottomHit.type == HitResult.Type.BLOCK) {
            val bPos = (bottomHit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                ?: BlockPos.containing(bottomHit.location.x, bottomHit.location.y, bottomHit.location.z)
            if (bPos != targetBlock) return false
        }

        // 4. Validate body space clearance at landing spot (no walls or fences pinching player)
        val playerBox = net.minecraft.world.phys.AABB(
            targetBlock.x + 0.2, targetBlock.y + 1.0, targetBlock.z + 0.2,
            targetBlock.x + 0.8, targetBlock.y + 2.8, targetBlock.z + 0.8
        )

        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val side1 = above1.offset(dx, 0, dz)
                val sideState1 = level.getBlockState(side1)
                if (!sideState1.isAir && !sideState1.getCollisionShape(level, side1).isEmpty) {
                    val shape = sideState1.getCollisionShape(level, side1)
                    for (box in shape.toAabbs()) {
                        if (box.move(side1).intersects(playerBox)) return false
                    }
                }
            }
        }

        return true
    }

    fun canInstantTransmissionForward(fromEye: Vec3, dir: Vec3, range: Double): Double? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null

        val normDir = dir.normalize()
        // Orthogonal horizontal vector for lateral shoulder/hip clearance
        val orthoX = -normDir.z * 0.28
        val orthoZ = normDir.x * 0.28

        // Multi-point body raycast offsets from foot position (Swept Bounding Cylinder)
        val footPos = Vec3(fromEye.x, fromEye.y - 1.62, fromEye.z)
        val bodyOffsets = arrayOf(
            Vec3(0.0, 0.15, 0.0),                  // Feet center
            Vec3(0.0, 0.90, 0.0),                  // Waist center
            Vec3(0.0, 1.62, 0.0),                  // Eye center
            Vec3(0.0, 1.80, 0.0),                  // Head top (Checks ceilings & low overhangs!)
            Vec3(orthoX, 0.30, orthoZ),            // Left foot/hip
            Vec3(-orthoX, 0.30, -orthoZ),          // Right foot/hip
            Vec3(orthoX, 1.50, orthoZ),            // Left shoulder/head
            Vec3(-orthoX, 1.50, -orthoZ)           // Right shoulder/head
        )

        var minHitDist = range

        // Cast all body rays against ALL block outlines - Cannot teleport through any slabs, carpets, stairs, walls!
        for (offset in bodyOffsets) {
            val rayStart = footPos.add(offset)
            val rayEnd = rayStart.add(normDir.scale(range))
            val hit = level.clip(ClipContext(rayStart, rayEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))

            if (hit.type == HitResult.Type.BLOCK) {
                val hitDist = rayStart.distanceTo(hit.location) - 0.4
                if (hitDist < minHitDist) {
                    minHitDist = hitDist
                }
            }
        }

        return if (minHitDist >= 4.0) minHitDist else null
    }

    fun useHeldItem() {
        val player = mc.player ?: return
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
    }
}
