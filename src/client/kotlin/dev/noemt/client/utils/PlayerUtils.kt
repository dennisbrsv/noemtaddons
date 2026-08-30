package dev.noemt.client.utils

import dev.noemt.client.mixin.IKeyMapping
import dev.noemt.client.utils.ItemUtils.skyblockId
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

object PlayerUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun getHotbarSlot(slot: Int): ItemStack? {
        val player = mc.player ?: return null
        if (!Inventory.isHotbarSlot(slot)) return null
        return player.inventory.getItem(slot)
    }

    fun findHotbarSlot(predicate: (ItemStack) -> Boolean): Int? {
        return (0..8).firstOrNull { idx ->
            val stack = getHotbarSlot(idx) ?: return@firstOrNull false
            if (stack.isEmpty) return@firstOrNull false
            predicate(stack)
        }
    }

    fun swapToSlot(slot: Int) {
        val player = mc.player ?: return
        if (!Inventory.isHotbarSlot(slot)) return
        if (player.inventory.selectedSlot == slot) return
        player.inventory.selectedSlot = slot
    }

    fun leftClick() {
        if (dev.noemt.client.features.loadout.LoadoutManager.isSwapping || mc.screen != null) return
        val key = mc.options.keyAttack
        key.isDown = true
        (key as? IKeyMapping)?.let { it.clickCount = it.clickCount + 1 }
        key.isDown = false
    }

    fun rightClick() {
        if (dev.noemt.client.features.loadout.LoadoutManager.isSwapping || mc.screen != null) return
        val key = mc.options.keyUse
        key.isDown = true
        (key as? IKeyMapping)?.let { it.clickCount = it.clickCount + 1 }
        key.isDown = false
    }

    fun swingArm() {
        if (dev.noemt.client.features.loadout.LoadoutManager.isSwapping || mc.screen != null) return
        val player = mc.player ?: return
        if (!player.swinging || player.swingTime < 0) {
            player.swingingArm = InteractionHand.MAIN_HAND
            player.swingTime = -1
            player.swinging = true
        }
    }

    fun toggleSneak(state: Boolean) {
        mc.options.keyShift.isDown = state
    }

    fun attackEntity(entity: net.minecraft.world.entity.Entity? = null) {
        if (dev.noemt.client.features.loadout.LoadoutManager.isSwapping || mc.screen != null) return
        val player = mc.player ?: return
        if (entity != null && player.distanceTo(entity) <= 5.5) {
            mc.gameMode?.attack(player, entity)
        }
        player.swing(InteractionHand.MAIN_HAND)
        leftClick()
    }
}
