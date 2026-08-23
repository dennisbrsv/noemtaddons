package dev.noemt.client.utils

import dev.noemt.client.utils.ChatUtils.formattedText
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import kotlin.jvm.optionals.getOrNull

object ItemUtils {
    val ItemStack.customData get() = getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
    val ItemStack.lore get() = getOrDefault(DataComponents.LORE, ItemLore.EMPTY).styledLines().map { it.formattedText }
    val ItemStack.itemUUID get() = customData.getString("uuid").getOrNull() ?: ""

    val ItemStack.skyblockId: String
        get() {
            if (isEmpty) return ""
            val customData = customData
            var sbItemID: String? = null

            if (customData.contains("id")) sbItemID = customData.getString("id").getOrNull()?.replace(":", "-")
            return sbItemID.orEmpty()
        }

    fun getSkullTexture(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val profile = stack.get(DataComponents.PROFILE) ?: return null
        val properties = profile.partialProfile().properties
        return properties["textures"].firstOrNull()?.value
    }
}
