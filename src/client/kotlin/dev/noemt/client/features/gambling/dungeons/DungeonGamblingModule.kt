package dev.noemt.client.features.gambling.dungeons

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType

object DungeonGamblingModule : Module {
    override val id: String = "dungeon_gambling"
    override val name: String = "Dungeon Chest Slot Machine"
    override val description: String = "Plays a 3-reel animated slot machine inside dungeon chests with sounds and jackpot celebrations."
    override val type: ModuleType = ModuleType.LEGIT

    override fun init() {
        DungeonChestGambling.init()
    }

    override fun isEnabled(): Boolean {
        return ConfigManager.config.gambling.enabled
    }
}
