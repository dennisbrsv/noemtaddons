package dev.noemt.client.utils

import dev.noemt.client.utils.ChatUtils.formattedText
import dev.noemt.client.utils.ChatUtils.removeFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import kotlin.jvm.optionals.getOrNull

object ItemUtils {
    val ItemStack.customData get() = getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
    val ItemStack.lore get() = getOrDefault(DataComponents.LORE, ItemLore.EMPTY).styledLines().map { it.formattedText }
    val ItemStack.itemUUID get() = customData.getString("uuid").getOrNull() ?: ""
    val ItemStack.cleanDisplayName: String get() = hoverName.string.removeFormatting().trim()
    val ItemStack.cleanLore: List<String> get() = lore.map { it.removeFormatting().trim() }

    enum class ItemRarity(val colorCode: Char) {
        COMMON('f'),
        UNCOMMON('a'),
        RARE('9'),
        EPIC('5'),
        LEGENDARY('6'),
        MYTHIC('d'),
        DIVINE('b'),
        SPECIAL('c'),
        VERY_SPECIAL('c')
    }

    fun ItemStack.hasLore(predicate: (String) -> Boolean): Boolean = cleanLore.any(predicate)
    fun ItemStack.hasLoreContaining(substring: String, ignoreCase: Boolean = true): Boolean =
        cleanLore.any { it.contains(substring, ignoreCase) }

    fun ItemStack.findLore(predicate: (String) -> Boolean): String? = cleanLore.find(predicate)
    fun ItemStack.findLoreContaining(substring: String, ignoreCase: Boolean = true): String? =
        cleanLore.find { it.contains(substring, ignoreCase) }

    fun ItemStack.getSkyblockRarity(): ItemRarity? {
        val lines = cleanLore
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            for (rarity in ItemRarity.entries) {
                if (line.contains(rarity.name)) return rarity
            }
        }
        return null
    }

    fun ItemStack.getStars(): Int {
        val name = cleanDisplayName
        val yellowStars = name.count { it == '✪' }
        val masterStars = name.count { it == '➊' || it == '➋' || it == '➌' || it == '➍' || it == '➎' }
        return yellowStars + masterStars
    }

    fun ItemStack.isRecombobulated(): Boolean {
        val customDataComponent = get(DataComponents.CUSTOM_DATA) ?: return false
        val extra = customDataComponent.copyTag().getCompoundOrEmpty("ExtraAttributes")
        return extra.getIntOr("rarity_upgrades", 0) > 0
    }

    fun ItemStack.getDyedColor(): Int? {
        val dyable = get(DataComponents.DYED_COLOR)
        return dyable?.rgb()
    }

    val ItemStack.skyblockId: String
        get() {
            if (isEmpty) return ""
            val customDataComponent = get(DataComponents.CUSTOM_DATA) ?: return ""
            val tag = customDataComponent.copyTag()
            if (tag.contains("id")) {
                return tag.getString("id").getOrNull()?.replace(":", "-").orEmpty()
            }
            if (tag.contains("ExtraAttributes")) {
                val extra = tag.getCompoundOrEmpty("ExtraAttributes")
                if (extra.contains("id")) {
                    return extra.getStringOr("id", "").replace(":", "-")
                }
            }
            return ""
        }

    fun getSkullTexture(stack: ItemStack): String? {
        if (stack.isEmpty) return null

        // 1. Try DataComponents.PROFILE first (0 NBT copying)
        val profile = stack.get(DataComponents.PROFILE)
        if (profile != null) {
            val textures = profile.partialProfile().properties.get("textures")
            val value = textures.firstOrNull()?.value
            if (!value.isNullOrBlank()) return value
        }

        // 2. Only read CUSTOM_DATA if profile was not present
        val customDataComponent = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val customData = customDataComponent.copyTag()

        // 3. Try CustomData / SkullOwner NBT tag (Hypixel legacy & container format)
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

        // 4. Check for base64 texture in ExtraAttributes or custom_data directly
        val extra = customData.getCompoundOrEmpty("ExtraAttributes")
        if (extra.contains("texture")) {
            val tex = extra.getStringOr("texture", "")
            if (tex.isNotBlank()) return tex
        }

        return null
    }
}
