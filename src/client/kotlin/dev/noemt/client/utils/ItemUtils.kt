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

        // 1. Try DataComponents.PROFILE
        val profile = stack.get(DataComponents.PROFILE)
        if (profile != null) {
            val textures = profile.partialProfile().properties.get("textures")
            val value = textures.firstOrNull()?.value
            if (!value.isNullOrBlank()) return value
        }

        // 2. Try CustomData / SkullOwner NBT tag (Hypixel legacy & container format)
        val customData = stack.customData
        if (customData.contains("SkullOwner")) {
            val skullOwner = customData.getCompoundOrEmpty("SkullOwner")
            val props = skullOwner.getCompoundOrEmpty("Properties")
            val texturesList = props.getListOrEmpty("textures")
            if (!texturesList.isEmpty()) {
                val firstTag = texturesList.getCompoundOrEmpty(0)
                val value = firstTag.getStringOr("Value", "")
                if (value.isNotBlank()) return value
            }
        }

        // 3. Check for base64 texture in ExtraAttributes or custom_data directly
        val extra = customData.getCompoundOrEmpty("ExtraAttributes")
        if (extra.contains("texture")) {
            val tex = extra.getStringOr("texture", "")
            if (tex.isNotBlank()) return tex
        }

        return null
    }
}
