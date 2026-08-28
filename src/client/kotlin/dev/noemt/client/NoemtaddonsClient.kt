package dev.noemt.client

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventDispatcher
import dev.noemt.client.module.ModuleManager
import dev.noemt.client.module.ModuleType
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.DebugUtils
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.TabListUtils
import dev.noemt.client.utils.ThreadUtils
import dev.noemt.client.remote.DiscordBotManager
import dev.noemt.client.remote.RemoteWebSocketClient
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands

object NoemtaddonsClient : ClientModInitializer {
    override fun onInitializeClient() {
        ConfigManager.init()
        EventDispatcher.init()
        ThreadUtils.init()
        LocationUtils.init()
        TabListUtils.init()
        DungeonListener.init()
        ModuleManager.init()

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val commands = listOf("noemt", "nmt", "noemtaddons")

            for (cmd in commands) {
                dispatcher.register(
                    ClientCommands.literal(cmd)
                        .then(
                            ClientCommands.literal("changelog")
                                .executes {
                                    dev.noemt.client.features.misc.ChangelogManager.openChangelogGui()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("modules")
                                .executes {
                                    ChatUtils.modMessage("&6=== NoemtAddons Modules (&b${BuildConstants.buildDisplayName} Build&6) ===")
                                    for (mod in ModuleManager.modules) {
                                        val isAvail = ModuleManager.isModuleAvailable(mod)
                                        val typeTag = if (mod.type == ModuleType.CHEAT) "&c[CHEAT]" else "&a[LEGIT]"
                                        val statusTag = if (isAvail) "&aActive" else "&8Omitted (Legit build)"
                                        ChatUtils.modMessage(" &7- &f${mod.name} $typeTag: $statusTag")
                                    }
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("list")
                                .executes {
                                    ChatUtils.modMessage("&6=== NoemtAddons Modules (&b${BuildConstants.buildDisplayName} Build&6) ===")
                                    for (mod in ModuleManager.modules) {
                                        val isAvail = ModuleManager.isModuleAvailable(mod)
                                        val typeTag = if (mod.type == ModuleType.CHEAT) "&c[CHEAT]" else "&a[LEGIT]"
                                        val statusTag = if (isAvail) "&aActive" else "&8Omitted (Legit build)"
                                        ChatUtils.modMessage(" &7- &f${mod.name} $typeTag: $statusTag")
                                    }
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("lore")
                                .executes {
                                    DebugUtils.dumpHeldItem()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("item")
                                .executes {
                                    DebugUtils.dumpHeldItem()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("hand")
                                .executes {
                                    DebugUtils.dumpHeldItem()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("dump")
                                .executes {
                                    DebugUtils.dumpAll()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("tab")
                                .executes {
                                    DebugUtils.dumpTabList()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("tablist")
                                .executes {
                                    DebugUtils.dumpTabList()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("score")
                                .executes {
                                    DebugUtils.dumpScoreboard()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("scoreboard")
                                .executes {
                                    DebugUtils.dumpScoreboard()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("entities")
                                .executes {
                                    DebugUtils.dumpBloodEntities()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("mobs")
                                .executes {
                                    DebugUtils.dumpBloodEntities()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("discord")
                                .then(
                                    ClientCommands.literal("test")
                                        .executes {
                                            dev.noemt.client.utils.ChatUtils.modMessage("&e[Discord] Sending test notification...")
                                            DiscordBotManager.sendNotification(
                                                title = "🔔 Test Notification from NoemtAddons",
                                                description = "Discord Bot notification integration is working perfectly!",
                                                fields = mapOf(
                                                    "Player" to (net.minecraft.client.Minecraft.getInstance().player?.name?.string ?: "Unknown"),
                                                    "Server" to "Hypixel Skyblock",
                                                    "Status" to "Online & Active"
                                                )
                                            ).thenAccept { success ->
                                                if (success) {
                                                    dev.noemt.client.utils.ChatUtils.modMessage("&a[Discord] Test notification sent successfully!")
                                                }
                                            }
                                            1
                                        }
                                )
                        )
                        .then(
                            ClientCommands.literal("remote")
                                .then(
                                    ClientCommands.literal("connect")
                                        .executes {
                                            RemoteWebSocketClient.connect()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("disconnect")
                                        .executes {
                                            RemoteWebSocketClient.disconnect()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("status")
                                        .executes {
                                            val statusStr = if (RemoteWebSocketClient.isConnected) "&aConnected" else "&cDisconnected"
                                            dev.noemt.client.utils.ChatUtils.modMessage("&6[Remote Status] State: $statusStr &7| Server: &b${RemoteWebSocketClient.serverUrl}")
                                            1
                                        }
                                )
                        )
                        .executes { context ->
                            val client = context.source.client
                            client.execute {
                                ConfigManager.openGui()
                            }
                            1
                        }
                )
            }

            // Register stalk command
            dispatcher.register(
                ClientCommands.literal("stalk")
                    .then(
                        ClientCommands.argument("ign", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests { ctx, builder ->
                                val players = net.minecraft.client.Minecraft.getInstance().level?.players()?.map { it.name.string } ?: emptyList()
                                net.minecraft.commands.SharedSuggestionProvider.suggest(players + listOf("stop"), builder)
                            }
                            .executes { context ->
                                val ign = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "ign")
                                dev.noemt.client.features.misc.StalkFeature.stalk(ign)
                                1
                            }
                    )
                    .executes {
                        if (dev.noemt.client.features.misc.StalkFeature.targetName != null) {
                            dev.noemt.client.features.misc.StalkFeature.stop()
                        } else {
                            ChatUtils.modMessage("&eUsage: &b\$stalk <ign>&e or &b\$stalk stop")
                        }
                        1
                    }
            )

            // Register pathfinder commands (SkyHanni pathfinder)
            for (pCmd in listOf("path", "goto", "pf", "navigate")) {
                dispatcher.register(
                    ClientCommands.literal(pCmd)
                        .then(
                            ClientCommands.literal("stop")
                                .executes {
                                    dev.noemt.client.features.pathfinder.SkyHanniPathfinder.stop()
                                    ChatUtils.modMessage("&cPathfinding stopped.")
                                    1
                                }
                        )
                        .then(
                            ClientCommands.argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                .then(
                                    ClientCommands.argument("y", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                        .then(
                                            ClientCommands.argument("z", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                                .executes { ctx ->
                                                    val x = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x")
                                                    val y = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "y")
                                                    val z = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z")
                                                    dev.noemt.client.features.pathfinder.SkyHanniPathfinder.pathTo(x, y, z)
                                                    1
                                                }
                                        )
                                )
                        )
                        .then(
                            ClientCommands.argument("ign", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests { ctx, builder ->
                                    val players = net.minecraft.client.Minecraft.getInstance().level?.players()?.map { it.name.string } ?: emptyList()
                                    net.minecraft.commands.SharedSuggestionProvider.suggest(players + listOf("stop"), builder)
                                }
                                .executes { ctx ->
                                    val ign = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "ign")
                                    if (ign.equals("stop", ignoreCase = true)) {
                                        dev.noemt.client.features.pathfinder.SkyHanniPathfinder.stop()
                                        ChatUtils.modMessage("&cPathfinding stopped.")
                                    } else {
                                        val p = net.minecraft.client.Minecraft.getInstance().level?.players()?.find { it.name.string.equals(ign, ignoreCase = true) }
                                        if (p != null) {
                                            dev.noemt.client.features.pathfinder.SkyHanniPathfinder.pathTo(p.x, p.y, p.z)
                                        } else {
                                            ChatUtils.modMessage("&cPlayer $ign not found nearby.")
                                        }
                                    }
                                    1
                                }
                        )
                        .executes {
                            ChatUtils.modMessage("&eUsage: &b\$$pCmd <x> <y> <z>&e, &b\$$pCmd <ign>&e, or &b\$$pCmd stop")
                            1
                        }
                )
            }
        }
    }
}
