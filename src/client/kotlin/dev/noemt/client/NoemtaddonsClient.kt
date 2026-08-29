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
import dev.noemt.client.features.gambling.chest.DungeonChestType
import dev.noemt.client.features.gambling.dungeons.DungeonFloor
import dev.noemt.client.features.gambling.dungeons.DungeonSlotMachineScreen
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
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
        dev.noemt.client.features.gambling.dungeons.SkyblockPriceService.init()
        dev.noemt.client.features.gambling.dungeons.DungeonChestGambling.init()
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
                        .then(buildLoadoutCommandNode("loadouts"))
                        .then(buildLoadoutCommandNode("loadout"))
                        .then(buildSlotsCommandNode("slots"))
                        .then(buildSlotsCommandNode("slot"))
                        .then(buildSlotsCommandNode("gambling"))
                        .then(
                            ClientCommands.literal("dumpchest")
                                .executes {
                                    DebugUtils.dumpCurrentChest()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("gambadebug")
                                .executes {
                                    dev.noemt.client.features.gambling.dungeons.DungeonChestGambling.debugMode = !dev.noemt.client.features.gambling.dungeons.DungeonChestGambling.debugMode
                                    val state = if (dev.noemt.client.features.gambling.dungeons.DungeonChestGambling.debugMode) "&aENABLED" else "&cDISABLED"
                                    ChatUtils.modMessage("&e[Gambling Debug] Live container debugging is now $state&e.")
                                    1
                                }
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

            // Quick shortcut aliases
            dispatcher.register(buildLoadoutCommandNode("als"))
            dispatcher.register(buildSlotsCommandNode("slots"))
            dispatcher.register(buildSlotsCommandNode("gambling"))
            dispatcher.register(
                ClientCommands.literal("dumpchest")
                    .executes {
                        DebugUtils.dumpCurrentChest()
                        1
                    }
            )
            dispatcher.register(
                ClientCommands.literal("gambadebug")
                    .executes {
                        dev.noemt.client.features.gambling.dungeons.DungeonChestGambling.debugMode = !dev.noemt.client.features.gambling.dungeons.DungeonChestGambling.debugMode
                        val state = if (dev.noemt.client.features.gambling.dungeons.DungeonChestGambling.debugMode) "&aENABLED" else "&cDISABLED"
                        ChatUtils.modMessage("&e[Gambling Debug] Live container debugging is now $state&e.")
                        1
                    }
            )
            dispatcher.register(
                ClientCommands.literal("lore")
                    .executes {
                        DebugUtils.dumpHoveredOrHeldItem()
                        1
                    }
            )
            dispatcher.register(
                ClientCommands.literal("item")
                    .executes {
                        DebugUtils.dumpHoveredOrHeldItem()
                        1
                    }
            )
            dispatcher.register(
                ClientCommands.literal("hand")
                    .executes {
                        DebugUtils.dumpHoveredOrHeldItem()
                        1
                    }
            )

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

    private fun buildLoadoutCommandNode(literal: String): com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> {
        return ClientCommands.literal(literal)
            .then(
                ClientCommands.literal("gui")
                    .executes {
                        net.minecraft.client.Minecraft.getInstance().execute {
                            net.minecraft.client.Minecraft.getInstance().setScreen(dev.noemt.client.features.loadout.LoadoutScreen())
                        }
                        1
                    }
            )
            .then(
                ClientCommands.literal("menu")
                    .executes {
                        net.minecraft.client.Minecraft.getInstance().execute {
                            net.minecraft.client.Minecraft.getInstance().setScreen(dev.noemt.client.features.loadout.LoadoutScreen())
                        }
                        1
                    }
            )
            .then(
                ClientCommands.literal("sync")
                    .executes {
                        dev.noemt.client.features.loadout.LoadoutManager.requestSkyblockSync()
                        ChatUtils.modMessage("&b[Loadout] &aSent SkyBlock /loadouts sync request!")
                        1
                    }
            )
            .then(
                ClientCommands.literal("toggle")
                    .executes {
                        dev.noemt.client.features.loadout.LoadoutManager.toggleAB()
                        1
                    }
            )
            .then(
                ClientCommands.literal("back")
                    .executes {
                        dev.noemt.client.features.loadout.LoadoutManager.swapToPrevious()
                        1
                    }
            )
            .then(
                ClientCommands.literal("prev")
                    .executes {
                        dev.noemt.client.features.loadout.LoadoutManager.swapToPrevious()
                        1
                    }
            )
            .then(
                ClientCommands.literal("current")
                    .executes {
                        val current = dev.noemt.client.features.loadout.LoadoutManager.getCurrentLoadout()
                        val name = current?.name ?: "None"
                        val id = current?.id ?: "none"
                        ChatUtils.modMessage("&b[Loadout] &aCurrent Active Loadout: &e$name &7(ID: &f$id&7)")
                        ChatUtils.modMessage("&7  Toggle pair: &b${dev.noemt.client.features.loadout.LoadoutManager.loadoutAId} &7⇄ &b${dev.noemt.client.features.loadout.LoadoutManager.loadoutBId}")
                        1
                    }
            )
            .then(
                ClientCommands.literal("swap")
                    .then(
                        ClientCommands.argument("target", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests { ctx, builder ->
                                val ids = dev.noemt.client.features.loadout.LoadoutManager.loadouts.keys + (1..12).map { it.toString() }
                                net.minecraft.commands.SharedSuggestionProvider.suggest(ids, builder)
                            }
                            .executes { ctx ->
                                val target = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "target")
                                val targetId = target.toIntOrNull()?.let { "loadout_$it" } ?: target
                                dev.noemt.client.features.loadout.LoadoutManager.swapTo(targetId, "Command ($literal swap)")
                                1
                            }
                    )
            )
            .then(
                ClientCommands.literal("list")
                    .executes {
                        ChatUtils.modMessage("&b&l=== Configured Loadouts ===")
                        val curr = dev.noemt.client.features.loadout.LoadoutManager.currentLoadoutId
                        for ((id, lo) in dev.noemt.client.features.loadout.LoadoutManager.loadouts) {
                            val activeIndicator = if (id == curr) " &a[ACTIVE]" else ""
                            ChatUtils.modMessage("&e• &6${lo.name} &7(&b$id&7)$activeIndicator")
                            ChatUtils.modMessage("    &7SkyBlock Loadout: &fSlot ${lo.loadoutSlot}")
                            if (lo.petName != null) ChatUtils.modMessage("    &7Pet: &f${lo.petName}")
                            if (lo.commands.isNotEmpty()) ChatUtils.modMessage("    &7Commands: &f${lo.commands.joinToString()}")
                        }
                        ChatUtils.modMessage("&b&l=== Conditional Rules ===")
                        for (r in dev.noemt.client.features.loadout.LoadoutManager.rules) {
                            val state = if (r.enabled) "&a[ENABLED]" else "&c[DISABLED]"
                            ChatUtils.modMessage("&e• $state &f${r.name} &7-> &b${r.targetLoadoutId} &7(ID: &f${r.id}&7)")
                        }
                        1
                    }
            )
            .then(
                ClientCommands.literal("set")
                    .then(
                        ClientCommands.literal("A")
                            .then(
                                ClientCommands.argument("loadout_id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .suggests { ctx, builder ->
                                        val ids = dev.noemt.client.features.loadout.LoadoutManager.loadouts.keys + (1..12).map { it.toString() }
                                        net.minecraft.commands.SharedSuggestionProvider.suggest(ids, builder)
                                    }
                                    .executes { ctx ->
                                        val id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "loadout_id")
                                        val targetId = id.toIntOrNull()?.let { "loadout_$it" } ?: id
                                        dev.noemt.client.features.loadout.LoadoutManager.loadoutAId = targetId
                                        ChatUtils.modMessage("&b[Loadout] &aSet Toggle Loadout A to: &e$targetId")
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("B")
                            .then(
                                ClientCommands.argument("loadout_id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .suggests { ctx, builder ->
                                        val ids = dev.noemt.client.features.loadout.LoadoutManager.loadouts.keys + (1..12).map { it.toString() }
                                        net.minecraft.commands.SharedSuggestionProvider.suggest(ids, builder)
                                    }
                                    .executes { ctx ->
                                        val id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "loadout_id")
                                        val targetId = id.toIntOrNull()?.let { "loadout_$it" } ?: id
                                        dev.noemt.client.features.loadout.LoadoutManager.loadoutBId = targetId
                                        ChatUtils.modMessage("&b[Loadout] &aSet Toggle Loadout B to: &e$targetId")
                                        1
                                    }
                            )
                    )
            )
            .then(
                ClientCommands.literal("rule")
                    .then(
                        ClientCommands.literal("toggle")
                            .then(
                                ClientCommands.argument("rule_id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .suggests { ctx, builder ->
                                        val ids = dev.noemt.client.features.loadout.LoadoutManager.rules.map { it.id }
                                        net.minecraft.commands.SharedSuggestionProvider.suggest(ids, builder)
                                    }
                                    .executes { ctx ->
                                        val ruleId = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "rule_id")
                                        val rule = dev.noemt.client.features.loadout.LoadoutManager.rules.find { it.id.equals(ruleId, ignoreCase = true) }
                                        if (rule != null) {
                                            rule.enabled = !rule.enabled
                                            dev.noemt.client.features.loadout.LoadoutManager.saveData()
                                            val s = if (rule.enabled) "&aENABLED" else "&cDISABLED"
                                            ChatUtils.modMessage("&b[Loadout] &7Rule &f${rule.name} &7is now $s")
                                        } else {
                                            ChatUtils.modMessage("&c[Loadout] Rule '$ruleId' not found.")
                                        }
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("remove")
                            .then(
                                ClientCommands.argument("rule_id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .suggests { ctx, builder ->
                                        val ids = dev.noemt.client.features.loadout.LoadoutManager.rules.map { it.id }
                                        net.minecraft.commands.SharedSuggestionProvider.suggest(ids, builder)
                                    }
                                    .executes { ctx ->
                                        val ruleId = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "rule_id")
                                        if (dev.noemt.client.features.loadout.LoadoutManager.removeRule(ruleId)) {
                                            ChatUtils.modMessage("&b[Loadout] &aRemoved rule: &e$ruleId")
                                        } else {
                                            ChatUtils.modMessage("&c[Loadout] Rule '$ruleId' not found.")
                                        }
                                        1
                                    }
                            )
                    )
            )
            .executes {
                net.minecraft.client.Minecraft.getInstance().execute {
                    net.minecraft.client.Minecraft.getInstance().setScreen(dev.noemt.client.features.loadout.LoadoutScreen())
                }
                1
            }
    }

    private fun buildSlotsCommandNode(literal: String): com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> {
        return ClientCommands.literal(literal)
            .then(
                ClientCommands.argument("floor", StringArgumentType.word())
                    .suggests { _, builder ->
                        SharedSuggestionProvider.suggest(listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"), builder)
                    }
                    .then(
                        ClientCommands.argument("chest", StringArgumentType.word())
                            .suggests { _, builder ->
                                SharedSuggestionProvider.suggest(listOf("bedrock", "obsidian", "emerald", "diamond", "gold", "wood"), builder)
                            }
                            .executes { ctx ->
                                val floorStr = StringArgumentType.getString(ctx, "floor")
                                val chestStr = StringArgumentType.getString(ctx, "chest")
                                val floor = DungeonFloor.fromString(floorStr) ?: DungeonFloor.M7
                                val chest = DungeonChestType.getByName(chestStr) ?: DungeonChestType.BEDROCK
                                Minecraft.getInstance().execute {
                                    Minecraft.getInstance().setScreen(DungeonSlotMachineScreen(floor, chest))
                                }
                                1
                            }
                    )
                    .executes { ctx ->
                        val floorStr = StringArgumentType.getString(ctx, "floor")
                        val floor = DungeonFloor.fromString(floorStr) ?: DungeonFloor.M7
                        Minecraft.getInstance().execute {
                            Minecraft.getInstance().setScreen(DungeonSlotMachineScreen(floor, DungeonChestType.BEDROCK))
                        }
                        1
                    }
            )
            .executes {
                Minecraft.getInstance().execute {
                    Minecraft.getInstance().setScreen(DungeonSlotMachineScreen(DungeonFloor.M7, DungeonChestType.BEDROCK))
                }
                1
            }
    }
}
