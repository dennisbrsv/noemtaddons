package dev.noemt.client.config

import io.github.notenoughupdates.moulconfig.managed.ManagedConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.io.File

object ConfigManager {
    private val configFile: File = FabricLoader.getInstance().configDir.resolve("noemtaddons.json").toFile()

    val holder: ManagedConfig<NoemtaddonsConfig> = ManagedConfig.create(
        configFile,
        NoemtaddonsConfig::class.java
    )

    val config: NoemtaddonsConfig
        get() = holder.instance

    fun init() {
        try {
            holder.reloadFromFile()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Auto-save when client is stopping / closing
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            save()
        }

        // JVM shutdown hook backup
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                save()
            } catch (ignored: Exception) {}
        })
    }

    fun save() {
        try {
            holder.saveToFile()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun openGui() {
        Minecraft.getInstance().execute {
            try {
                holder.openConfigGui()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}
