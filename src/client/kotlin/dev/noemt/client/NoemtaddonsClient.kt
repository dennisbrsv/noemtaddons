package dev.noemt.client

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventDispatcher
import dev.noemt.client.features.blood.AutoBloodCamp
import dev.noemt.client.features.blood.BloodCamp
import dev.noemt.client.features.blood.BloodESP
import dev.noemt.client.features.map.DungeonMap
import dev.noemt.client.utils.DebugUtils
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.TabListUtils
import dev.noemt.client.utils.ThreadUtils
import dev.noemt.client.remote.DiscordBotManager
import dev.noemt.client.remote.RemoteWebSocketClient
import dev.noemt.client.utils.pathfinder.PathfinderManager
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
        BloodCamp.init()
        BloodESP.init()
        AutoBloodCamp.init()
        DungeonMap.init()
        PathfinderManager.init()
        RemoteWebSocketClient.init()

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val commands = listOf("noemt", "nmt", "noemtaddons")

            for (cmd in commands) {
                dispatcher.register(
                    ClientCommands.literal(cmd)
                        .then(
                            ClientCommands.literal("pf")
                                .then(
                                    ClientCommands.literal("stop")
                                        .executes {
                                            PathfinderManager.cancel()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("look")
                                        .executes {
                                            val mc = net.minecraft.client.Minecraft.getInstance()
                                            val hit = mc.hitResult as? net.minecraft.world.phys.BlockHitResult
                                            if (hit != null && hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                                                PathfinderManager.navigateTo(hit.blockPos)
                                            } else {
                                                dev.noemt.client.utils.ChatUtils.modMessage("&c[Pathfinder] Not looking at a valid block.")
                                            }
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("log")
                                        .executes {
                                            dev.noemt.client.utils.pathfinder.PathfinderFlightRecorder.dumpToClipboard()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("debug")
                                        .executes {
                                            dev.noemt.client.utils.pathfinder.PathfinderFlightRecorder.dumpToClipboard()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("dump")
                                        .executes {
                                            dev.noemt.client.utils.pathfinder.PathfinderFlightRecorder.dumpToClipboard()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(
                                            ClientCommands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(
                                                    ClientCommands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes { ctx ->
                                                            val x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x")
                                                            val y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y")
                                                            val z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z")
                                                            PathfinderManager.navigateTo(net.minecraft.core.BlockPos(x, y, z))
                                                            1
                                                        }
                                                )
                                        )
                                )
                        )
                        .then(
                            ClientCommands.literal("goto")
                                .then(
                                    ClientCommands.literal("log")
                                        .executes {
                                            dev.noemt.client.utils.pathfinder.PathfinderFlightRecorder.dumpToClipboard()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("debug")
                                        .executes {
                                            dev.noemt.client.utils.pathfinder.PathfinderFlightRecorder.dumpToClipboard()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("dump")
                                        .executes {
                                            dev.noemt.client.utils.pathfinder.PathfinderFlightRecorder.dumpToClipboard()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(
                                            ClientCommands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(
                                                    ClientCommands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes { ctx ->
                                                            val x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x")
                                                            val y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y")
                                                            val z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z")
                                                            PathfinderManager.navigateTo(net.minecraft.core.BlockPos(x, y, z))
                                                            1
                                                        }
                                                )
                                        )
                                )
                        )
                        .then(
                            ClientCommands.literal("path")
                                .then(
                                    ClientCommands.literal("stop")
                                        .executes {
                                            PathfinderManager.cancel()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(
                                            ClientCommands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(
                                                    ClientCommands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes { ctx ->
                                                            val x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x")
                                                            val y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y")
                                                            val z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z")
                                                            PathfinderManager.navigateTo(net.minecraft.core.BlockPos(x, y, z))
                                                            1
                                                        }
                                                )
                                        )
                                )
                        )
                        .then(
                            ClientCommands.literal("stop")
                                .executes {
                                    PathfinderManager.cancel()
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
        }
    }
}
