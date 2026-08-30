package dev.noemt.client.features.mask

import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.ItemUtils
import net.minecraft.world.item.ItemStack

enum class MaskType(val displayName: String, val skyblockId: String, val baseCooldownMs: Long) {
    SPIRIT("Spirit Mask", "SPIRIT_MASK", 30_000L),
    BONZO("Bonzo's Mask", "BONZO_MASK", 212_000L);

    companion object {
        val SPIRIT_ITEM_REGEX = Regex("""(?:\bSpirit\s+Mask\b)""", RegexOption.IGNORE_CASE)
        val BONZO_ITEM_REGEX = Regex("""(?:\bBonzo(?:'s)?\s+Mask\b)""", RegexOption.IGNORE_CASE)
        val COOLDOWN_LORE_REGEX = Regex("""Cooldown:\s*(\d+(?:\.\d+)?)\s*s""", RegexOption.IGNORE_CASE)

        fun fromItemStack(stack: ItemStack): MaskType? {
            if (stack.isEmpty) return null
            val id = ItemUtils.run { stack.skyblockId }.uppercase()
            val name = ItemUtils.run { stack.cleanDisplayName }

            return when {
                id.contains("SPIRIT_MASK") || SPIRIT_ITEM_REGEX.containsMatchIn(name) -> SPIRIT
                id.contains("BONZO_MASK") || BONZO_ITEM_REGEX.containsMatchIn(name) -> BONZO
                else -> null
            }
        }

        fun parseCooldownMs(stack: ItemStack, defaultCooldownMs: Long): Long {
            if (stack.isEmpty) return defaultCooldownMs
            val lore = ItemUtils.run { stack.lore }
            for (line in lore) {
                val clean = line.removeFormatting().trim()
                val match = COOLDOWN_LORE_REGEX.find(clean)
                if (match != null) {
                    val seconds = match.groupValues[1].toDoubleOrNull()
                    if (seconds != null && seconds > 0) {
                        return (seconds * 1000.0).toLong()
                    }
                }
            }
            return defaultCooldownMs
        }
    }
}

enum class MaskSwapStage {
    IDLE,
    PRE_CMD_WAIT,
    WAITING_GUI_OPEN,
    GUI_OPEN_WAIT,
    POST_CLICK_WAIT,
    FINALIZE
}

enum class MaskSwapMode {
    EQUIP_MASK,
    REVERT_HELMET
}

data class TrackedMaskItem(
    val type: MaskType,
    val inventorySlot: Int,
    val item: ItemStack,
    val displayName: String,
    val cooldownDurationMs: Long = type.baseCooldownMs,
    val isOnCooldown: Boolean,
    val cooldownRemainingMs: Long
)

data class OriginalHelmetData(
    val displayName: String,
    val skyblockId: String,
    val itemUUID: String,
    val skullTexture: String?,
    val dyedColor: Int?,
    val itemType: String,
    val loreLines: List<String>,
    val inventorySlot: Int
)
