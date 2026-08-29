package dev.noemt.client.utils

import dev.noemt.client.features.blood.AutoBloodCamp
import dev.noemt.client.features.blood.BloodCamp
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.NumbersUtils.toFixed
import dev.noemt.client.utils.map.core.RoomTile
import dev.noemt.client.utils.map.core.RoomType
import dev.noemt.client.utils.map.handlers.DungeonScanner
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import kotlin.math.abs

object DebugUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun dumpTabList(): String {
        val player = mc.player ?: return "No player loaded."
        val entries = TabListUtils.getTabList()

        val sb = StringBuilder()
        sb.appendLine("=== TabList Dump (${entries.size} entries) ===")
        sb.appendLine("--------------------------------------------------")

        for ((index, pair) in entries.withIndex()) {
            val component = pair.first
            val info = pair.second
            val formatted = component.string
            val clean = formatted.removeFormatting()
            val ping = info.latency
            val profileName = info.profile.name
            sb.appendLine("#${index + 1}: name='$profileName' | text='$clean' | formatted='$formatted' | ping=${ping}ms")
        }

        val text = sb.toString()
        mc.keyboardHandler.clipboard = text
        ChatUtils.modMessage("&a[NoemtAddons] Dumped TabList (${entries.size} entries) to clipboard! &e(Paste with Ctrl+V)")
        return text
    }

    fun dumpScoreboard(): String {
        val level = mc.level ?: return "No level loaded."
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
            ?: return "No active sidebar objective found.".also {
                mc.keyboardHandler.clipboard = it
                ChatUtils.modMessage("&c[NoemtAddons] No active sidebar objective found.")
            }

        val objName = objective.name
        val objDisplay = objective.displayName.string
        val scores = scoreboard.listPlayerScores(objective)
            .sortedByDescending { it.value }

        val sb = StringBuilder()
        sb.appendLine("=== Scoreboard Dump ===")
        sb.appendLine("Objective: name='$objName' | display='$objDisplay' (${objDisplay.removeFormatting()})")
        sb.appendLine("Lines (${scores.size}):")
        sb.appendLine("--------------------------------------------------")

        for (entry in scores) {
            val owner = entry.owner
            val scoreVal = entry.value
            val team = scoreboard.getPlayersTeam(owner)
            val lineFormatted = team?.let { PlayerTeam.formatNameForTeam(it, entry.ownerName()).string } ?: owner
            val lineClean = lineFormatted.removeFormatting()
            sb.appendLine("[$scoreVal] '$lineClean' | raw='$lineFormatted' | owner='$owner'")
        }

        val text = sb.toString()
        mc.keyboardHandler.clipboard = text
        ChatUtils.modMessage("&a[NoemtAddons] Dumped Scoreboard (${scores.size} lines) to clipboard! &e(Paste with Ctrl+V)")
        return text
    }

    fun dumpBloodEntities(): String {
        val level = mc.level ?: return "No world loaded."
        val player = mc.player ?: return "No player loaded."
        val entities = level.entitiesForRendering().toList()

        val bloodRoom = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD }
        val bloodTiles = bloodRoom?.tiles?.filterIsInstance<RoomTile>() ?: emptyList()

        fun isInsideBloodRoom(pos: Vec3): Boolean {
            if (bloodTiles.isEmpty()) return player.position().distanceTo(pos) < 28.0
            return bloodTiles.any { tile ->
                abs(pos.x - tile.x) <= 15.5 && abs(pos.z - tile.z) <= 15.5
            }
        }

        val inRoomEntities = entities.filter { isInsideBloodRoom(it.position()) }

        val sb = StringBuilder()
        sb.appendLine("=== Blood Room Entity Dump (${inRoomEntities.size} entities) ===")
        sb.appendLine("Player Pos: (${player.x.toFixed(1)}, ${player.y.toFixed(1)}, ${player.z.toFixed(1)})")
        sb.appendLine("Watcher Messages: ${AutoBloodCamp.watcherMessageCount}")
        sb.appendLine("Blood Cleared: ${AutoBloodCamp.bloodRoomCleared}")
        sb.appendLine("Blood Tiles: ${bloodTiles.map { "(${it.x}, ${it.z})" }}")
        sb.appendLine("--------------------------------------------------")

        for ((index, entity) in inRoomEntities.withIndex()) {
            val type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
            val customName = entity.customName?.string ?: (entity as? LivingEntity)?.displayName?.string ?: (entity as? Player)?.gameProfile?.name ?: "None"
            val health = (entity as? LivingEntity)?.health ?: -1f
            val maxHealth = (entity as? LivingEntity)?.maxHealth ?: -1f
            val isAlive = entity.isAlive
            val isRemoved = entity.isRemoved
            val dist = player.distanceTo(entity).toFixed(1)
            val pos = "(${entity.x.toFixed(1)}, ${entity.y.toFixed(1)}, ${entity.z.toFixed(1)})"

            sb.appendLine("#${index + 1}: ${entity::class.simpleName} | type=$type | name='$customName' | hp=$health/$maxHealth | alive=$isAlive, removed=$isRemoved | pos=$pos | dist=${dist}m")
        }

        val text = sb.toString()
        mc.keyboardHandler.clipboard = text
        ChatUtils.modMessage("&a[NoemtAddons] Dumped ${inRoomEntities.size} entities in Blood Room to clipboard!")
        return text
    }

    fun dumpHeldItem(): String {
        return dumpHoveredOrHeldItem()
    }

    fun dumpHoveredOrHeldItem(): String {
        val screen = mc.screen
        var targetStack: net.minecraft.world.item.ItemStack? = null
        var sourceDescription = "Held Main Hand"

        if (screen is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>) {
            val slot = (screen as? dev.noemt.client.mixin.IContainerScreenAccessor)?.hoveredSlot
            if (slot != null && slot.hasItem()) {
                targetStack = slot.item
                sourceDescription = "Hovered Slot #${slot.index} in Container (${screen.title.string.removeFormatting()})"
            }
        }

        if (targetStack == null || targetStack.isEmpty) {
            targetStack = mc.player?.mainHandItem?.takeUnless { it.isEmpty }
        }

        if (targetStack == null || targetStack.isEmpty) {
            ChatUtils.modMessage("&c[Debug] No item hovered in container or held in main hand!")
            return "No item found."
        }

        val stack = targetStack
        val sb = StringBuilder()
        val rawLore = ItemUtils.run { stack.lore }

        sb.appendLine("=== Item Lore Dump ($sourceDescription: ${stack.hoverName.string.removeFormatting()}) ===")
        for (line in rawLore) {
            sb.appendLine(line.removeFormatting())
        }
        sb.appendLine()
        sb.appendLine("=== Full Data Dump ===")
        sb.appendLine("Display Name: '${stack.hoverName.string}'")
        sb.appendLine("Clean Name: '${stack.hoverName.string.removeFormatting()}'")
        sb.appendLine("Registry ID: ${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)}")
        sb.appendLine("Count: ${stack.count}")

        val customData = ItemUtils.run { stack.customData }
        val sbId = ItemUtils.run { stack.skyblockId }
        val uuid = ItemUtils.run { stack.itemUUID }
        val skull = ItemUtils.getSkullTexture(stack)

        sb.appendLine("SkyBlock ID: '$sbId'")
        sb.appendLine("Item UUID: '$uuid'")
        if (skull != null) sb.appendLine("Skull Texture: '$skull'")

        sb.appendLine("--- Formatted Lore Lines ---")
        for ((idx, line) in rawLore.withIndex()) {
            sb.appendLine("[$idx] '${line.removeFormatting()}' | raw='$line'")
        }

        sb.appendLine("--- Custom Data (NBT / Components) ---")
        sb.appendLine(customData.toString())
        sb.appendLine("================================================")

        val text = sb.toString()
        mc.keyboardHandler.clipboard = text
        ChatUtils.modMessage("&a[Debug] Copied lore & data for &e${stack.hoverName.string} &7($sourceDescription) &ato clipboard! &e(Ctrl+V)")
        return text
    }

    fun dumpAll(): String {
        val sb = StringBuilder()
        sb.appendLine("================== NOEMTADDONS FULL DEBUG DUMP ==================")
        sb.appendLine("Location: inSkyblock=${LocationUtils.inSkyblock}, inDungeon=${LocationUtils.inDungeon}, floor=${LocationUtils.dungeonFloor}, floorNumber=${LocationUtils.dungeonFloorNumber}, inBoss=${LocationUtils.inBoss}")
        sb.appendLine("AutoBloodCamp: cleared=${AutoBloodCamp.bloodRoomCleared}, watcherMessages=${AutoBloodCamp.watcherMessageCount}")
        sb.appendLine()
        sb.appendLine(dumpScoreboard())
        sb.appendLine()
        sb.appendLine(dumpTabList())
        sb.appendLine()
        sb.appendLine(dumpBloodEntities())
        sb.appendLine()
        sb.appendLine(dumpHoveredOrHeldItem())
        sb.appendLine("=================================================================")

        val text = sb.toString()
        mc.keyboardHandler.clipboard = text
        ChatUtils.modMessage("&a[NoemtAddons] Dumped FULL Debug Info to clipboard!")
        return text
    }

    fun dumpCurrentChest(): String {
        val screen = mc.screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
            ?: return "No container screen is currently open!".also {
                ChatUtils.modMessage("&c[Debug] No chest or container screen is currently open!")
            }

        val sb = StringBuilder()
        val rawTitle = screen.title.string
        val cleanTitle = rawTitle.removeFormatting().trim()
        val containerId = screen.menu.containerId
        val totalSlots = screen.menu.slots.size

        sb.appendLine("=== Dungeon Chest / Container Dump ===")
        sb.appendLine("Screen Class: ${screen::class.qualifiedName}")
        sb.appendLine("Menu Class: ${screen.menu::class.qualifiedName} | containerId: $containerId | totalSlots: $totalSlots")
        sb.appendLine("Title Raw: '$rawTitle'")
        sb.appendLine("Title Clean: '$cleanTitle'")
        sb.appendLine("--------------------------------------------------")

        val slots = screen.menu.slots
        val items = slots.take(54).map { it.item }
        val nonEmpty = slots.filter { it.hasItem() }

        val parsedChestType = dev.noemt.client.features.gambling.chest.DungeonChestType.findInText(cleanTitle)
        val config = dev.noemt.client.config.ConfigManager.config.gambling
        val activeSession = dev.noemt.client.features.gambling.dungeons.DungeonChestGambling.activeSession

        sb.appendLine("Config State: enabled=${config.enabled}, croesusEnabled=${config.croesusEnabled}, chestTypes=${config.chestTypes}")
        sb.appendLine("Active Session: ${activeSession != null} (isFinished=${activeSession?.engine?.isFinished})")
        sb.appendLine("Title Chest Type Match: $parsedChestType")
        sb.appendLine("Location State: inSkyblock=${LocationUtils.inSkyblock}, inDungeon=${LocationUtils.inDungeon}, floor=${LocationUtils.dungeonFloor}, floorNumber=${LocationUtils.dungeonFloorNumber}")

        var isCroesus = false
        var croesusTarget: String? = null
        var hasBarrier = false
        val claimButtons = mutableListOf<String>()

        for (slot in nonEmpty) {
            val item = slot.item
            if (item.`is`(net.minecraft.world.item.Items.ARROW)) {
                for (line in ItemUtils.run { item.lore }) {
                    val cleanLine = line.removeFormatting().trim()
                    if (cleanLine.startsWith("To Catacombs", ignoreCase = true) || cleanLine.startsWith("To Master", ignoreCase = true)) {
                        isCroesus = true
                        croesusTarget = cleanLine.removePrefix("To ").trim()
                    }
                }
            }
            if (item.`is`(net.minecraft.world.item.Items.BARRIER)) {
                hasBarrier = true
            }
            val name = item.hoverName.string.removeFormatting().trim()
            if (name.contains("Reward Chest", ignoreCase = true) || name.contains("Open Chest", ignoreCase = true) || name.contains("Claim", ignoreCase = true) || name.contains("Chest", ignoreCase = true)) {
                claimButtons.add("Slot #${slot.index}: '$name'")
            }
        }

        sb.appendLine("Croesus Detected: $isCroesus (Arrow Target: '$croesusTarget')")
        sb.appendLine("Barrier Detected: $hasBarrier")
        sb.appendLine("Claim/Chest Buttons Found (${claimButtons.size}): $claimButtons")

        val bestWinner = dev.noemt.client.features.gambling.dungeons.DungeonItemRegistry.findBestWinner(items)
        sb.appendLine("Best Winner Evaluated: '${bestWinner?.hoverName?.string}' | Display: '${bestWinner?.let { dev.noemt.client.features.gambling.dungeons.DungeonItemRegistry.getDropDisplayName(it) }}' | Value: ${bestWinner?.let { dev.noemt.client.features.gambling.dungeons.DungeonItemRegistry.getItemValue(it) }}")
        sb.appendLine("---------------- Non-Empty Slots (${nonEmpty.size}) ----------------")

        for (slot in nonEmpty) {
            val item = slot.item
            val regId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.item)
            val name = item.hoverName.string
            val sbId = ItemUtils.run { item.skyblockId }
            val lore = ItemUtils.run { item.lore }
            sb.appendLine("Slot #${slot.index} (x=${slot.x}, y=${slot.y}): '$name' ($regId x${item.count}) | sbId='$sbId'")
            for ((lIdx, line) in lore.withIndex()) {
                sb.appendLine("   [$lIdx] '${line.removeFormatting()}'")
            }
        }
        sb.appendLine("==================================================")

        val text = sb.toString()
        mc.keyboardHandler.clipboard = text
        ChatUtils.modMessage("&a[Debug] Dumped open chest ($cleanTitle, ${nonEmpty.size} items) to clipboard! &e(Paste with Ctrl+V)")
        return text
    }
}
