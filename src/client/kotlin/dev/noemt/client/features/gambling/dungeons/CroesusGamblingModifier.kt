package dev.noemt.client.features.gambling.dungeons

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.features.gambling.chest.DungeonChestType
import dev.noemt.client.utils.ChatUtils.removeFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object CroesusGamblingModifier {

    fun shouldModifyTooltip(stack: ItemStack, screenTitle: String): Boolean {
        if (!ConfigManager.config.gambling.enabled || !ConfigManager.config.gambling.croesusEnabled || !ConfigManager.config.gambling.hideCroesusContents) {
            return false
        }

        val cleanTitle = screenTitle.removeFormatting().trim()
        val isCroesusScreen = cleanTitle.startsWith("Catacombs - ") ||
                cleanTitle.startsWith("Master Catacombs - ") ||
                cleanTitle.contains("Croesus", ignoreCase = true)

        if (!isCroesusScreen) return false

        if (!stack.`is`(Items.PLAYER_HEAD)) return false

        val itemName = stack.hoverName.string.removeFormatting().trim()
        val chestType = DungeonChestType.getByNameStartsWith(itemName) ?: return false

        val allowedChests = if (ConfigManager.config.gambling.chestTypes == 0) {
            listOf(DungeonChestType.OBSIDIAN, DungeonChestType.BEDROCK)
        } else {
            DungeonChestType.entries
        }

        return chestType in allowedChests
    }

    fun modifyTooltip(original: List<Component>): List<Component> {
        val result = mutableListOf<Component>()
        var skippingContents = false

        for (comp in original) {
            val text = comp.string.removeFormatting().trim()

            if (text.equals("Contents", ignoreCase = true) || text.equals("Rewards", ignoreCase = true)) {
                result.add(comp)
                result.add(Component.literal("§7Hidden by NoemtAddons"))
                result.add(Component.literal("§7\"Dungeon Gambling\" feature."))
                result.add(Component.literal(""))
                skippingContents = true
                continue
            }

            if (skippingContents) {
                if (text.startsWith("Cost", ignoreCase = true) || text.startsWith("Click to open", ignoreCase = true)) {
                    skippingContents = false
                } else {
                    // Skip reward content line
                    continue
                }
            }

            if (text.startsWith("Cost", ignoreCase = true)) {
                result.add(Component.literal("§7Cost"))
                result.add(Component.literal("§6§kxxxxxxx§r §6Coins"))
                result.add(Component.literal(""))
                continue
            }

            result.add(comp)
        }

        return result
    }
}
