package dev.noemt.client.features.mask

import net.minecraft.world.item.ItemStack

enum class MaskType(val displayName: String, val skyblockId: String, val baseCooldownMs: Long) {
    SPIRIT("Spirit Mask", "SPIRIT_MASK", 30_000L),
    BONZO("Bonzo's Mask", "BONZO_MASK", 212_000L);

    companion object {
        val SPIRIT_ITEM_REGEX = Regex("""(?:\bSpirit\s+Mask\b)""", RegexOption.IGNORE_CASE)
        val BONZO_ITEM_REGEX = Regex("""(?:\bBonzo(?:'s)?\s+Mask\b)""", RegexOption.IGNORE_CASE)

        fun fromItemStack(stack: ItemStack): MaskType? {
            if (stack.isEmpty) return null
            val id = dev.noemt.client.utils.ItemUtils.run { stack.skyblockId }.uppercase()
            val name = dev.noemt.client.utils.ItemUtils.run { stack.cleanDisplayName }

            return when {
                id.contains("SPIRIT_MASK") || SPIRIT_ITEM_REGEX.containsMatchIn(name) -> SPIRIT
                id.contains("BONZO_MASK") || BONZO_ITEM_REGEX.containsMatchIn(name) -> BONZO
                else -> null
            }
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
