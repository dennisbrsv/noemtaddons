package dev.noemt.client.utils

import dev.noemt.client.utils.ItemUtils.skyblockId
import dev.noemt.client.utils.map.core.RoomType
import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.utils.ScanUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

object AOTVHelper {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val scope = CoroutineScope(Dispatchers.Default)
    var isTeleporting = false
        private set

    fun isAotv(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack.`is`(Items.DIAMOND_SHOVEL)) {
            val name = stack.hoverName.string
            if (name.contains("Aspect of the Void", ignoreCase = true)) return true
        }
        if (stack.skyblockId == "ASPECT_OF_THE_VOID") return true
        return false
    }

    fun findAotvHotbarSlot(): Int? {
        return PlayerUtils.findHotbarSlot { isAotv(it) }
    }

    fun hasAotv(): Boolean = findAotvHotbarSlot() != null

    fun isValidFloorTeleportTarget(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(pos)
        if (state.isAir) return false
        if (state.getCollisionShape(level, pos).isEmpty) return false
        if (pos.y !in 68..70) return false

        val above1 = pos.above(1)
        val above2 = pos.above(2)
        val state1 = level.getBlockState(above1)
        val state2 = level.getBlockState(above2)

        if (!state1.isAir && !state1.getCollisionShape(level, above1).isEmpty) return false
        if (!state2.isAir && !state2.getCollisionShape(level, above2).isEmpty) return false

        val targetVec = Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        val room = ScanUtils.getRoomFromPos(targetVec)
        if (room?.data?.type != RoomType.BLOOD) return false

        if (!PathfindingUtils.hasPillarClearance(pos)) return false

        val bloodRoom = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD }
        val roomCenter = bloodRoom?.let { Vec3(it.centerPos.x.toDouble(), 69.0, it.centerPos.z.toDouble()) }
        if (roomCenter != null && !PathfindingUtils.hasCenterLineOfSight(pos, roomCenter)) return false

        return true
    }

    fun castTeleport(restoreSlot: Int? = null, onFinish: (() -> Unit)? = null): Boolean {
        if (isTeleporting) return false
        val slot = findAotvHotbarSlot() ?: return false
        val player = mc.player ?: return false
        val prevSlot = restoreSlot ?: player.inventory.selectedSlot

        isTeleporting = true
        MouseRotationHelper.clearTarget()
        MouseRotationHelper.isSuppressed = true

        scope.launch {
            try {
                PlayerUtils.swapToSlot(slot)
                PlayerUtils.toggleSneak(true)
                delay(30)

                PlayerUtils.rightClick()
                PlayerUtils.swingArm()
                delay(35)

                PlayerUtils.toggleSneak(false)
                delay(25)
                PlayerUtils.swapToSlot(prevSlot)
                onFinish?.invoke()
            } finally {
                PlayerUtils.toggleSneak(false)
                isTeleporting = false
                MouseRotationHelper.isSuppressed = false
            }
        }
        return true
    }
}
