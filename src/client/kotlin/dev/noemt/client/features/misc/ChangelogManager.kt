package dev.noemt.client.features.misc

import dev.noemt.client.BuildConstants
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.utils.ThreadUtils
import kotlinx.coroutines.launch
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

object ChangelogManager : Module {
    override val id = "changelog"
    override val name = "Changelog Manager"
    override val description = "Fetches and displays version updates and changelogs from server"
    override val type = ModuleType.LEGIT

    private const val CHANGELOG_URL = "https://addons.noemt.dev/changelog"
    private const val CURRENT_VERSION = "1.0.0"

    private val metaFile: File by lazy {
        FabricLoader.getInstance().configDir.resolve("noemtaddons_meta.json").toFile()
    }

    var changelogText: String = ""
        private set
    var isNewVersionDetected: Boolean = false
        private set
    private var hasShownNotification = false

    override fun init() {
        checkVersionAndFetchChangelog()

        register<WorldChangeEvent> {
            if (isNewVersionDetected && !hasShownNotification) {
                hasShownNotification = true
                ThreadUtils.scheduledTask(40) {
                    showUpdateNotification()
                }
            }
        }
    }

    fun checkVersionAndFetchChangelog(force: Boolean = false) {
        ThreadUtils.coroutineScope.launch {
            try {
                val lastSeen = readLastSeenVersion()
                if (lastSeen != CURRENT_VERSION || force) {
                    isNewVersionDetected = true
                }

                val content = fetchChangelog()
                if (content.isNotEmpty()) {
                    changelogText = content
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun fetchChangelog(): String {
        return runCatching {
            val url = URI(CHANGELOG_URL).toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "NoemtAddons/$CURRENT_VERSION")

            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                getDefaultChangelog()
            }
        }.getOrElse { getDefaultChangelog() }
    }

    private fun getDefaultChangelog(): String {
        val s = "$"
        return """
            §b§lNoemtAddons v1.0.0 (${BuildConstants.buildDisplayName})
            
            §e• Modern 26.1.2 Fabric Port
            §7  Fully ported and optimized for Minecraft 26.1.2.
            
            §e• Custom $s Command Prefix
            §7  Use $s instead of / with full native autocompletion.
            
            §e• Player Stalker (${s}stalk {ign})
            §7  Real-time 3D tracer line & player bounding box highlight.
            
            §e• SkyHanni Pathfinder Engine
            §7  Smooth 3D Catmull-Rom Bezier navigation & waypoints.
            
            §e• Dynamic Mod Loader Architecture
            §7  Automatic mod synchronization from update server.
        """.trimIndent()
    }

    private fun readLastSeenVersion(): String {
        return runCatching {
            if (metaFile.exists()) {
                val json = com.google.gson.JsonParser.parseString(metaFile.readText()).asJsonObject
                json.get("last_seen_version")?.asString ?: ""
            } else ""
        }.getOrDefault("")
    }

    fun markVersionSeen() {
        isNewVersionDetected = false
        runCatching {
            val json = com.google.gson.JsonObject()
            json.addProperty("last_seen_version", CURRENT_VERSION)
            metaFile.writeText(json.toString())
        }
    }

    fun showUpdateNotification() {
        val mc = Minecraft.getInstance()
        val text = Component.literal("§b[NoemtAddons] §aNew version §e$CURRENT_VERSION (${BuildConstants.buildDisplayName}) §adetected! ")
        
        val button = Component.literal("§6§l[Click to View Changelog]")
            .withStyle(
                Style.EMPTY
                    .withClickEvent(ClickEvent.RunCommand("\$noemt changelog"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("§eClick to open the in-game changelog viewer!")))
            )

        mc.player?.sendSystemMessage(text.append(button))
    }

    fun openChangelogGui() {
        val mc = Minecraft.getInstance()
        mc.execute {
            markVersionSeen()
            mc.setScreen(ChangelogScreen(changelogText.ifEmpty { getDefaultChangelog() }))
        }
    }
}
