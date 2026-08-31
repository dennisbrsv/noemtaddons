package dev.noemt.client.module

import dev.noemt.client.BuildConstants
import dev.noemt.client.features.blood.AutoBloodCamp
import dev.noemt.client.features.blood.BloodCamp
import dev.noemt.client.features.blood.BloodESP
import dev.noemt.client.features.loadout.LoadoutModule
import dev.noemt.client.features.mask.AutoMaskModule
import dev.noemt.client.features.map.DungeonMap
import dev.noemt.client.features.misc.ChangelogManager
import dev.noemt.client.features.misc.StalkFeature
import dev.noemt.client.features.pathfinder.SkyHanniPathfinder
import dev.noemt.client.remote.DiscordBotManager
import dev.noemt.client.remote.RemoteWebSocketClient

object ModuleManager {
    val modules = mutableListOf<Module>()

    fun register(module: Module) {
        if (!modules.contains(module)) {
            modules.add(module)
        }
    }

    fun init() {
        // Register all modules
        register(DungeonMap)
        register(BloodCamp)
        register(BloodESP)
        register(AutoBloodCamp)
        register(StalkFeature)
        register(SkyHanniPathfinder)
        register(ChangelogManager)
        register(DiscordBotManager)
        register(RemoteWebSocketClient)
        register(LoadoutModule)
        register(AutoMaskModule)

        // Initialize all modules
        for (module in modules) {
            module.init()
        }
    }

    fun isModuleAvailable(module: Module): Boolean = true

    fun isModuleAvailable(id: String): Boolean {
        val mod = modules.find { it.id.equals(id, ignoreCase = true) } ?: return false
        return isModuleAvailable(mod)
    }

    fun getModule(id: String): Module? {
        return modules.find { it.id.equals(id, ignoreCase = true) }
    }
}
