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
        return DungeonChestType.getByNameStartsWith(itemName) != null
    }

    fun modifyTooltip(original: List<Component>): List<Component> {
        val result = mutableListOf<Component>()
        var inContentsSection = false

        for (comp in original) {
            val text = comp.string.removeFormatting().trim()

            if (text.equals("Contents", ignoreCase = true) || text.equals("Rewards", ignoreCase = true)) {
                result.add(comp)
                result.add(Component.literal("§7  • §d??? §8(Hidden by Slot Machine)"))
                result.add(Component.literal("§7  • §e??? §8(Spin to reveal!)"))
                result.add(Component.literal(""))
                inContentsSection = true
                continue
            }

            if (inContentsSection) {
                if (text.startsWith("Cost") || text.startsWith("Click to open") || text.isEmpty()) {
                    inContentsSection = false
                } else {
                    // Skip detailed contents lines
                    continue
                }
            }

            if (text.startsWith("Cost")) {
                result.add(Component.literal("§7Cost: §6§k1234567§r §6Coins"))
                continue
            }

            result.add(comp)
        }

        return result
    }
}
