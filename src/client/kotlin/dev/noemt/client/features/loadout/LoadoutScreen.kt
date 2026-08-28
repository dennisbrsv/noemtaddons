package dev.noemt.client.features.loadout

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.ui.core.GuiTheme
import dev.noemt.client.ui.core.NoemtScreen
import dev.noemt.client.ui.dsl.UiBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import java.awt.Color

class LoadoutScreen : NoemtScreen(
    title = Component.literal("NoemtAddons Auto Loadout Swapper"),
    windowTitle = "§b§lNoemtAddons §8• §fAuto Loadout Swapper §c[CHEAT]",
    windowSubtitle = "§7Toggle: §e[V] §7• Revert: §e[B] §7(Last Set) • Auto-Reverts on Miniboss Kill",
    widthRatio = 0.94f,
    heightRatio = 0.92f,
    minWidth = 640f,
    maxWidth = 980f
) {
    // Builder State
    private var builderTriggerType = "INSTANCE" // "INSTANCE", "MINIBOSS", "AIM", "CHAT"
    private var selectedInstanceCategory = "DUNGEONS" // "DUNGEONS", "NETHER", "MINING", "OVERWORLD", "SPECIAL"
    private var selectedGameInstance = GameInstanceType.DUNGEONS
    private var selectedMobCategory = MobCategory.BLOOD_MOB
    private var builderTargetSlot = 1
    private var chatPatternBox: EditBox? = null
    private var cooldownBox: EditBox? = null

    // Instance Categories
    private val instanceCategories = listOf(
        "DUNGEONS" to "🏰 Dungeons",
        "NETHER" to "🔥 Nether",
        "MINING" to "⛏️ Mining",
        "OVERWORLD" to "🌲 Overworld",
        "SPECIAL" to "🌌 Special"
    )

    private val categoryInstances = mapOf(
        "DUNGEONS" to listOf(
            GameInstanceType.DUNGEONS to "The Catacombs",
            GameInstanceType.DUNGEON_BOSS to "Dungeon Boss",
            GameInstanceType.DUNGEON_HUB to "Dungeon Hub"
        ),
        "NETHER" to listOf(
            GameInstanceType.CRIMSON_ISLE to "Crimson Isle",
            GameInstanceType.KUUDRA to "Kuudra Arena"
        ),
        "MINING" to listOf(
            GameInstanceType.DWARVEN_MINES to "Dwarven Mines",
            GameInstanceType.CRYSTAL_HOLLOWS to "Crystal Hollows",
            GameInstanceType.MINESHAFT to "Glacite Mineshafts"
        ),
        "OVERWORLD" to listOf(
            GameInstanceType.HUB to "Hub / Village",
            GameInstanceType.GARDEN to "The Garden",
            GameInstanceType.THE_PARK to "The Park",
            GameInstanceType.SPIDER_DEN to "Spider's Den",
            GameInstanceType.THE_END to "The End / Dragons"
        ),
        "SPECIAL" to listOf(
            GameInstanceType.THE_RIFT to "The Rift",
            GameInstanceType.DARK_AUCTION to "Dark Auction",
            GameInstanceType.WINTER to "Jerry's Workshop",
            GameInstanceType.PRIVATE_ISLAND to "Private Island"
        )
    )

    private val mobCategories = listOf(
        MobCategory.BLOOD_MOB to "🩸 Blood Mobs",
        MobCategory.WATCHER to "👁️ The Watcher",
        MobCategory.MINIBOSS to "⚔️ Miniboss",
        MobCategory.BOSS to "👑 Floor Boss",
        MobCategory.SLAYER to "💀 Slayer Boss"
    )

    // Scroll Offsets
    private var scrollOffsetLeft = 0
    private var scrollOffsetRight = 0

    override fun added() {
        super.added()
        // Register Live Auto-Refresh Listener safely deferred to next tick
        LoadoutManager.onDataChanged = {
            Minecraft.getInstance().execute {
                if (Minecraft.getInstance().screen == this) {
                    init()
                }
            }
        }
    }

    override fun removed() {
        super.removed()
        LoadoutManager.onDataChanged = null
    }

    override fun buildUi() {
        val ui = UiBuilder(font) { addRenderableWidget(it) }

        val colWidth = (windowWidth - 44) / 2
        val leftX = windowX + 16
        val rightX = leftX + colWidth + 12

        // Top SkyBlock Sync Button
        ui.button("⚡ Sync SkyBlock (/loadouts)", windowX + windowWidth - 235, windowY + 8, 175, 20) {
            LoadoutManager.requestSkyblockSync()
        }

        // ==========================================
        // LEFT COLUMN: 12 SkyBlock Loadout Slots
        // ==========================================
        var rowY = windowY + 58 - scrollOffsetLeft
        for (i in 1..12) {
            val id = "loadout_$i"
            val lo = LoadoutManager.loadouts[id] ?: Loadout(id = id, name = "Loadout $i", loadoutSlot = i)

            if (rowY in (windowY + 45)..(windowY + windowHeight - 65)) {
                val currentLo = lo

                // Equip button
                ui.button("⚡", leftX + colWidth - 96, rowY + 3, 24, 18) {
                    LoadoutManager.swapTo(currentLo.id, "GUI Equip")
                }

                // Set Toggle A
                val isA = LoadoutManager.loadoutAId == currentLo.id
                ui.button(if (isA) "§a[A]" else "A", leftX + colWidth - 68, rowY + 3, 30, 18) {
                    LoadoutManager.loadoutAId = currentLo.id
                    Minecraft.getInstance().execute { init() }
                }

                // Set Toggle B
                val isB = LoadoutManager.loadoutBId == currentLo.id
                ui.button(if (isB) "§e[B]" else "B", leftX + colWidth - 34, rowY + 3, 30, 18) {
                    LoadoutManager.loadoutBId = currentLo.id
                    Minecraft.getInstance().execute { init() }
                }
            }
            rowY += 30
        }

        // ==========================================
        // RIGHT COLUMN TOP: Active Rules List
        // ==========================================
        val builderHeight = 195
        val rulesMaxY = windowY + windowHeight - builderHeight - 35
        var ruleY = windowY + 58 - scrollOffsetRight
        val ruleList = LoadoutManager.rules.toList()

        for (rule in ruleList) {
            if (ruleY in (windowY + 45)..rulesMaxY) {
                val r = rule
                ui.button(if (r.enabled) "§a● ON" else "§c○ OFF", rightX + colWidth - 85, ruleY + 3, 46, 18) {
                    r.enabled = !r.enabled
                    LoadoutManager.saveData()
                    Minecraft.getInstance().execute { init() }
                }

                ui.dangerButton("🗑", rightX + colWidth - 34, ruleY + 3, 30, 18) {
                    LoadoutManager.removeRule(r.id)
                }
            }
            ruleY += 30
        }

        // ==========================================
        // RIGHT COLUMN BOTTOM: Rule Studio Builder
        // ==========================================
        val builderY = windowY + windowHeight - builderHeight - 20

        // Step 1: Trigger Type Tabs (y + 16)
        val triggerTypes = listOf(
            "INSTANCE" to "🏰 Zone / Island",
            "MINIBOSS" to "⚔️ Miniboss",
            "AIM" to "🎯 Aim",
            "CHAT" to "💬 Chat"
        )
        val tabWidth = (colWidth - (triggerTypes.size - 1) * 4) / triggerTypes.size
        var tX = rightX
        for ((tKey, tLabel) in triggerTypes) {
            val isSel = builderTriggerType == tKey
            ui.button(if (isSel) "§b§l$tLabel" else "§7$tLabel", tX, builderY + 16, tabWidth, 18) {
                builderTriggerType = tKey
                Minecraft.getInstance().execute { init() }
            }
            tX += tabWidth + 4
        }

        // Step 2: Categorized Condition Picker (y + 38 to y + 62)
        when (builderTriggerType) {
            "INSTANCE" -> {
                // Sub-category tabs (y + 38)
                val catTabWidth = (colWidth - (instanceCategories.size - 1) * 3) / instanceCategories.size
                var cX = rightX
                for ((cKey, cLabel) in instanceCategories) {
                    val isCatSel = selectedInstanceCategory == cKey
                    ui.button(if (isCatSel) "§e§l$cLabel" else "§8$cLabel", cX, builderY + 38, catTabWidth, 16) {
                        selectedInstanceCategory = cKey
                        Minecraft.getInstance().execute { init() }
                    }
                    cX += catTabWidth + 3
                }

                // Direct Zone Buttons for selected category (y + 57)
                val zoneList = categoryInstances[selectedInstanceCategory] ?: emptyList()
                val zWidth = (colWidth - (zoneList.size.coerceAtLeast(1) - 1) * 4) / zoneList.size.coerceAtLeast(1)
                var zX = rightX
                for ((instEnum, instName) in zoneList) {
                    val isInstSel = selectedGameInstance == instEnum
                    ui.button(if (isInstSel) "§a§l✓ $instName" else "§7$instName", zX, builderY + 57, zWidth, 18) {
                        selectedGameInstance = instEnum
                        Minecraft.getInstance().execute { init() }
                    }
                    zX += zWidth + 4
                }
            }

            "AIM" -> {
                val mobTabWidth = (colWidth - (mobCategories.size - 1) * 3) / mobCategories.size
                var mX = rightX
                for ((mobEnum, mobLabel) in mobCategories) {
                    val isMobSel = selectedMobCategory == mobEnum
                    ui.button(if (isMobSel) "§a§l✓ $mobLabel" else "§7$mobLabel", mX, builderY + 44, mobTabWidth, 18) {
                        selectedMobCategory = mobEnum
                        Minecraft.getInstance().execute { init() }
                    }
                    mX += mobTabWidth + 3
                }
            }

            "CHAT" -> {
                chatPatternBox = ui.textInput("Pattern", "[BOSS]", rightX + 55, builderY + 44, colWidth - 180, 18)
                ui.button("Blood", rightX + colWidth - 120, builderY + 44, 55, 18) {
                    chatPatternBox?.value = "BLOOD DOOR"
                }
                ui.button("Watcher", rightX + colWidth - 60, builderY + 44, 60, 18) {
                    chatPatternBox?.value = "The Watcher: You have proven"
                }
            }

            "MINIBOSS" -> {
                // Info drawn in renderWindow
            }
        }

        // Step 3: Target Loadout Slot Grid (Rows: y + 80, y + 100)
        // Render 2 rows of 6 with actual synced loadout names!
        val sBtnWidth = (colWidth - 5 * 4) / 6
        var sX = rightX
        for (s in 1..6) {
            val id = "loadout_$s"
            val loName = LoadoutManager.loadouts[id]?.name?.take(7) ?: "Slot $s"
            val isSel = builderTargetSlot == s
            ui.button(if (isSel) "§6§lS$s: $loName" else "§7S$s: $loName", sX, builderY + 80, sBtnWidth, 18) {
                builderTargetSlot = s
                Minecraft.getInstance().execute { init() }
            }
            sX += sBtnWidth + 4
        }

        sX = rightX
        for (s in 7..12) {
            val id = "loadout_$s"
            val loName = LoadoutManager.loadouts[id]?.name?.take(7) ?: "Slot $s"
            val isSel = builderTargetSlot == s
            ui.button(if (isSel) "§6§lS$s: $loName" else "§7S$s: $loName", sX, builderY + 100, sBtnWidth, 18) {
                builderTargetSlot = s
                Minecraft.getInstance().execute { init() }
            }
            sX += sBtnWidth + 4
        }

        // Step 4: Cooldown & Save Rule Button (Row: y + 126)
        val actionY = builderY + 126
        cooldownBox = ui.textInput("Cooldown", "2.5", rightX + 75, actionY, 44, 18)

        ui.successButton("§a➕ Save Auto-Swap Rule", rightX + 126, actionY, colWidth - 126, 18) {
            val targetId = "loadout_$builderTargetSlot"
            val cdVal = cooldownBox?.value?.toDoubleOrNull() ?: 2.5

            val (ruleName, condition) = when (builderTriggerType) {
                "INSTANCE" -> {
                    "Join ${selectedGameInstance.displayName}" to LoadoutCondition.GameInstanceCondition(instanceType = selectedGameInstance)
                }
                "MINIBOSS" -> {
                    "Miniboss Encounter (Auto-Reverts on Kill)" to LoadoutCondition.MinibossCondition(autoRevertOnKill = true)
                }
                "AIM" -> {
                    val mobName = selectedMobCategory.name.replace("_", " ")
                    "Aim at $mobName" to LoadoutCondition.AimCondition(mobCategory = selectedMobCategory)
                }
                "CHAT" -> {
                    val pat = chatPatternBox?.value ?: "[BOSS]"
                    "Chat: $pat" to LoadoutCondition.ChatCondition(pattern = pat, matchType = MatchType.CONTAINS)
                }
                else -> "Rule" to LoadoutCondition.AimCondition(mobCategory = MobCategory.BLOOD_MOB)
            }

            val newRule = LoadoutRule(
                id = "rule_${System.currentTimeMillis()}",
                name = ruleName,
                enabled = true,
                targetLoadoutId = targetId,
                condition = condition,
                cooldownSeconds = cdVal
            )

            LoadoutManager.addOrUpdateRule(newRule)
        }

        // ==========================================
        // BOTTOM BAR CONTROLS
        // ==========================================
        val bottomY = windowY + windowHeight - 26
        val config = ConfigManager.config.loadout

        ui.toggleButton("Swapper", config.enabled, windowX + 16, bottomY, 110, 18) {
            config.enabled = !config.enabled
            Minecraft.getInstance().execute { init() }
        }

        ui.toggleButton("HUD", config.showHud, windowX + 132, bottomY, 80, 18) {
            config.showHud = !config.showHud
            Minecraft.getInstance().execute { init() }
        }

        ui.toggleButton("Sound", config.playSound, windowX + 218, bottomY, 80, 18) {
            config.playSound = !config.playSound
            Minecraft.getInstance().execute { init() }
        }

        val prevLoName = LoadoutManager.previousLoadoutId?.let { LoadoutManager.loadouts[it]?.name ?: it } ?: "None"
        ui.button("🔄 Revert to Previous (Keybind [B])", windowX + windowWidth - 210, bottomY, 194, 18) {
            LoadoutManager.swapToPrevious()
        }
    }

    override fun renderWindow(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val colWidth = (windowWidth - 44) / 2
        val leftX = windowX + 16
        val rightX = leftX + colWidth + 12

        // Separator lines
        graphics.fill(leftX + colWidth + 5, windowY + 40, leftX + colWidth + 6, windowY + windowHeight - 34, GuiTheme.BORDER_MUTED)
        graphics.fill(windowX + 16, windowY + windowHeight - 32, windowX + windowWidth - 16, windowY + windowHeight - 31, GuiTheme.BORDER_MUTED)

        // Column Titles
        graphics.text(font, "§b§l1. SkyBlock Loadouts (Auto-Synced)", leftX, windowY + 42, GuiTheme.TEXT_WHITE, true)
        graphics.text(font, "§b§l2. Conditional Auto-Swap Rules", rightX, windowY + 42, GuiTheme.TEXT_WHITE, true)

        // Render Left Column: Loadouts
        var rowY = windowY + 58 - scrollOffsetLeft
        for (i in 1..12) {
            val id = "loadout_$i"
            val lo = LoadoutManager.loadouts[id] ?: Loadout(id = id, name = "Loadout $i", loadoutSlot = i)

            if (rowY in (windowY + 45)..(windowY + windowHeight - 65)) {
                val isActive = LoadoutManager.currentLoadoutId == lo.id
                val rowBg = if (isActive) GuiTheme.CARD_SURFACE_ACTIVE else GuiTheme.CARD_SURFACE
                graphics.fill(leftX, rowY, leftX + colWidth, rowY + 24, rowBg)

                val badge = "§6S$i: "
                val nameDisplay = lo.name.take(22)
                graphics.text(font, "$badge§f$nameDisplay", leftX + 6, rowY + 7, GuiTheme.TEXT_WHITE, true)
            }
            rowY += 30
        }

        // Render Right Column: Active Rules
        val builderHeight = 195
        val rulesMaxY = windowY + windowHeight - builderHeight - 35
        var ruleY = windowY + 58 - scrollOffsetRight
        val ruleList = LoadoutManager.rules.toList()

        if (ruleList.isEmpty()) {
            graphics.text(font, "§7No active rules. Create one below!", rightX + 6, windowY + 65, Color.GRAY.rgb, false)
        } else {
            for (rule in ruleList) {
                if (ruleY in (windowY + 45)..rulesMaxY) {
                    val rowBg = if (rule.enabled) GuiTheme.CARD_SURFACE else Color(20, 24, 32, 160).rgb
                    graphics.fill(rightX, ruleY, rightX + colWidth, ruleY + 24, rowBg)

                    val targetLo = LoadoutManager.loadouts[rule.targetLoadoutId]?.name ?: rule.targetLoadoutId
                    graphics.text(font, "§f${rule.name.take(20)} §7➜ §6$targetLo", rightX + 6, ruleY + 7, GuiTheme.TEXT_WHITE, true)
                }
                ruleY += 30
            }
        }

        // Render Rule Builder Section Header
        val builderY = windowY + windowHeight - builderHeight - 20
        graphics.fill(rightX, builderY - 6, rightX + colWidth, builderY - 5, GuiTheme.BORDER_MUTED)
        graphics.text(font, "§e§l➕ Rule Studio (Trigger ➜ Equip Target Slot):", rightX, builderY + 2, GuiTheme.COLOR_WARNING, true)

        if (builderTriggerType == "MINIBOSS") {
            graphics.text(font, "§a⚡ Locks onto SA/LA/AA/Midas when aimed at & auto-reverts on kill!", rightX + 4, builderY + 48, GuiTheme.COLOR_SUCCESS, false)
        } else if (builderTriggerType == "CHAT") {
            graphics.text(font, "§7Pattern:", rightX + 4, builderY + 48, GuiTheme.TEXT_MUTED, false)
        }
        graphics.text(font, "§7Cooldown (s):", rightX + 4, builderY + 130, GuiTheme.TEXT_MUTED, false)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val colWidth = (windowWidth - 44) / 2

        if (mouseX < windowX + colWidth + 16) {
            if (scrollY > 0) scrollOffsetLeft = (scrollOffsetLeft - 24).coerceAtLeast(0)
            else if (scrollY < 0) scrollOffsetLeft = (scrollOffsetLeft + 24).coerceAtMost(220)
        } else {
            if (scrollY > 0) scrollOffsetRight = (scrollOffsetRight - 24).coerceAtLeast(0)
            else if (scrollY < 0) scrollOffsetRight = (scrollOffsetRight + 24).coerceAtMost(180)
        }
        Minecraft.getInstance().execute { init() }
        return true
    }
}
