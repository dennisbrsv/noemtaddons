package dev.noemt.client.features.loadout

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.ui.core.GuiRenderer
import dev.noemt.client.ui.core.GuiTheme
import dev.noemt.client.ui.core.NoemtScreen
import dev.noemt.client.ui.dsl.UiBuilder
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import java.awt.Color

class LoadoutScreen : NoemtScreen(
    title = Component.literal("NoemtAddons Auto Loadout Swapper"),
    windowTitle = "§b§lNoemtAddons §8• §fAuto Loadout Swapper §c[CHEAT]",
    windowSubtitle = "§7Toggle Key: §e[V] §7(A ⇄ B) • Revert: §e[B] §7• Sync: §e/loadouts",
    widthRatio = 0.94f,
    heightRatio = 0.88f,
    minWidth = 540f,
    maxWidth = 880f
) {
    private var builderTriggerType = "INSTANCE" // "INSTANCE", "AIM", "CHAT"
    private var builderGameInstance = GameInstanceType.DUNGEONS
    private var builderMobCategory = MobCategory.BLOOD_MOB
    private var builderTargetSlot = 1
    private var chatPatternBox: EditBox? = null
    private var cooldownBox: EditBox? = null

    private var scrollOffsetLeft = 0
    private var scrollOffsetRight = 0

    override fun buildUi() {
        val ui = UiBuilder(font) { addRenderableWidget(it) }

        val colWidth = (windowWidth - 44) / 2
        val leftX = windowX + 16
        val rightX = leftX + colWidth + 12

        // Top SkyBlock Sync Button
        ui.button("⚡ Sync SkyBlock (/loadouts)", windowX + windowWidth - 255, windowY + 8, 175, 20) {
            LoadoutManager.requestSkyblockSync()
        }

        // Left Column: 12 SkyBlock Loadout Slots
        var rowY = windowY + 56 - scrollOffsetLeft
        for (i in 1..12) {
            val id = "loadout_$i"
            val lo = LoadoutManager.loadouts[id] ?: Loadout(id = id, name = "Loadout $i", loadoutSlot = i)

            if (rowY in (windowY + 50)..(windowY + windowHeight - 65)) {
                val currentLo = lo

                // Equip button
                ui.button("⚡", leftX + colWidth - 110, rowY + 3, 26, 18) {
                    LoadoutManager.swapTo(currentLo.id, "GUI Equip")
                    init()
                }

                // Set Toggle A
                val isA = LoadoutManager.loadoutAId == currentLo.id
                ui.button(if (isA) "§a[A]" else "A", leftX + colWidth - 80, rowY + 3, 36, 18) {
                    LoadoutManager.loadoutAId = currentLo.id
                    init()
                }

                // Set Toggle B
                val isB = LoadoutManager.loadoutBId == currentLo.id
                ui.button(if (isB) "§e[B]" else "B", leftX + colWidth - 40, rowY + 3, 36, 18) {
                    LoadoutManager.loadoutBId = currentLo.id
                    init()
                }
            }
            rowY += 32
        }

        // Right Column: Active Rules List
        var ruleY = windowY + 56 - scrollOffsetRight
        val ruleList = LoadoutManager.rules.toList()

        for (rule in ruleList) {
            if (ruleY in (windowY + 50)..(windowY + windowHeight - 170)) {
                val r = rule
                ui.button(if (r.enabled) "§a● ON" else "§c○ OFF", rightX + colWidth - 95, ruleY + 3, 50, 18) {
                    r.enabled = !r.enabled
                    LoadoutManager.saveData()
                    init()
                }

                ui.dangerButton("🗑", rightX + colWidth - 40, ruleY + 3, 36, 18) {
                    LoadoutManager.removeRule(r.id)
                    init()
                }
            }
            ruleY += 32
        }

        // Right Column Bottom: Rule Builder Form
        val builderY = windowY + windowHeight - 150

        // 1. Trigger Type Selector
        val triggerTypes = listOf("INSTANCE" to "🏰 Game Instance", "AIM" to "🎯 Mob Aim", "CHAT" to "💬 Chat Msg")
        var tX = rightX
        for ((tKey, tLabel) in triggerTypes) {
            val isSel = builderTriggerType == tKey
            ui.button(if (isSel) "§b§l$tLabel" else "§7$tLabel", tX, builderY + 16, 110, 18) {
                builderTriggerType = tKey
                init()
            }
            tX += 114
        }

        // 2. Specific Sub-options
        if (builderTriggerType == "INSTANCE") {
            val instances = listOf(
                GameInstanceType.DUNGEONS to "Dungeons",
                GameInstanceType.DUNGEON_BOSS to "Boss Room",
                GameInstanceType.KUUDRA to "Kuudra",
                GameInstanceType.GARDEN to "Garden",
                GameInstanceType.MINING to "Mining",
                GameInstanceType.THE_END to "The End",
                GameInstanceType.CRIMSON_ISLE to "Crimson Isle"
            )
            var instX = rightX
            for ((instEnum, instLabel) in instances) {
                val isSel = builderGameInstance == instEnum
                ui.button(if (isSel) "§a§l$instLabel" else "§7$instLabel", instX, builderY + 38, 76, 18) {
                    builderGameInstance = instEnum
                    init()
                }
                instX += 79
                if (instX > rightX + colWidth - 76) {
                    instX = rightX
                }
            }
        } else if (builderTriggerType == "AIM") {
            val cats = listOf(
                MobCategory.BLOOD_MOB to "Blood Mobs",
                MobCategory.WATCHER to "Watcher",
                MobCategory.MINIBOSS to "Miniboss",
                MobCategory.BOSS to "Boss",
                MobCategory.SLAYER to "Slayer"
            )
            var catX = rightX
            for ((catEnum, catLabel) in cats) {
                val isSel = builderMobCategory == catEnum
                ui.button(if (isSel) "§a§l$catLabel" else "§7$catLabel", catX, builderY + 38, 72, 18) {
                    builderMobCategory = catEnum
                    init()
                }
                catX += 75
            }
        } else if (builderTriggerType == "CHAT") {
            chatPatternBox = ui.textInput("Pattern", "[BOSS]", rightX + 60, builderY + 38, colWidth - 60, 18)
        }

        // 3. Target Slot Selector (1..12)
        var slotBtnX = rightX
        for (s in 1..6) {
            val isSel = builderTargetSlot == s
            ui.button(if (isSel) "§6§lSlot $s" else "§7S$s", slotBtnX, builderY + 62, 56, 18) {
                builderTargetSlot = s
                init()
            }
            slotBtnX += 59
        }

        slotBtnX = rightX
        for (s in 7..12) {
            val isSel = builderTargetSlot == s
            ui.button(if (isSel) "§6§lSlot $s" else "§7S$s", slotBtnX, builderY + 82, 56, 18) {
                builderTargetSlot = s
                init()
            }
            slotBtnX += 59
        }

        // Cooldown Box
        cooldownBox = ui.textInput("Cooldown", "2.5", rightX + 90, builderY + 104, 45, 18)

        // Add Rule Button
        ui.successButton("§a➕ Save Auto-Swap Rule", rightX + 145, builderY + 104, colWidth - 145, 18) {
            val targetId = "loadout_$builderTargetSlot"
            val cdVal = cooldownBox?.value?.toDoubleOrNull() ?: 2.5

            val (ruleName, condition) = when (builderTriggerType) {
                "INSTANCE" -> {
                    val instName = builderGameInstance.name.replace("_", " ")
                    "Join $instName" to LoadoutCondition.GameInstanceCondition(instanceType = builderGameInstance)
                }
                "AIM" -> {
                    val catName = builderMobCategory.name.replace("_", " ")
                    "Aim at $catName" to LoadoutCondition.AimCondition(mobCategory = builderMobCategory)
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
            init()
        }

        // Bottom Bar Settings
        val bottomY = windowY + windowHeight - 26
        val config = ConfigManager.config.loadout

        ui.toggleButton("Swapper", config.enabled, windowX + 16, bottomY, 130, 18) {
            config.enabled = !config.enabled
            init()
        }

        ui.toggleButton("HUD", config.showHud, windowX + 155, bottomY, 95, 18) {
            config.showHud = !config.showHud
            init()
        }

        ui.toggleButton("Sound", config.playSound, windowX + 258, bottomY, 95, 18) {
            config.playSound = !config.playSound
            init()
        }

        ui.button("🔄 Test Toggle (V)", windowX + windowWidth - 150, bottomY, 134, 18) {
            LoadoutManager.toggleAB()
            init()
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
        var rowY = windowY + 56 - scrollOffsetLeft
        for (i in 1..12) {
            val id = "loadout_$i"
            val lo = LoadoutManager.loadouts[id] ?: Loadout(id = id, name = "Loadout $i", loadoutSlot = i)

            if (rowY in (windowY + 50)..(windowY + windowHeight - 65)) {
                val isActive = LoadoutManager.currentLoadoutId == lo.id
                val rowBg = if (isActive) GuiTheme.CARD_SURFACE_ACTIVE else GuiTheme.CARD_SURFACE
                graphics.fill(leftX, rowY, leftX + colWidth, rowY + 26, rowBg)

                val badge = "§6S$i: "
                val nameDisplay = lo.name.take(24)
                graphics.text(font, "$badge§f$nameDisplay", leftX + 6, rowY + 8, GuiTheme.TEXT_WHITE, true)
            }
            rowY += 32
        }

        // Render Right Column: Active Rules
        var ruleY = windowY + 56 - scrollOffsetRight
        val ruleList = LoadoutManager.rules.toList()

        if (ruleList.isEmpty()) {
            graphics.text(font, "§7No rules yet. Add one below!", rightX + 6, windowY + 65, Color.GRAY.rgb, false)
        } else {
            for (rule in ruleList) {
                if (ruleY in (windowY + 50)..(windowY + windowHeight - 170)) {
                    val rowBg = if (rule.enabled) GuiTheme.CARD_SURFACE else Color(20, 24, 32, 160).rgb
                    graphics.fill(rightX, ruleY, rightX + colWidth, ruleY + 26, rowBg)

                    val targetLo = LoadoutManager.loadouts[rule.targetLoadoutId]?.name ?: rule.targetLoadoutId
                    graphics.text(font, "§f${rule.name} §7➜ §6$targetLo", rightX + 6, ruleY + 8, GuiTheme.TEXT_WHITE, true)
                }
                ruleY += 32
            }
        }

        // Render Rule Builder Section Header
        val builderY = windowY + windowHeight - 150
        graphics.fill(rightX, builderY - 6, rightX + colWidth, builderY - 5, GuiTheme.BORDER_MUTED)
        graphics.text(font, "§e§l➕ Add Rule (Instance / Aim / Chat ➜ Auto-Swap):", rightX, builderY + 2, GuiTheme.COLOR_WARNING, true)
        if (builderTriggerType == "CHAT") {
            graphics.text(font, "§7Pattern:", rightX + 6, builderY + 42, GuiTheme.TEXT_MUTED, false)
        }
        graphics.text(font, "§7Cooldown:", rightX + 20, builderY + 108, GuiTheme.TEXT_MUTED, false)
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
        init()
        return true
    }
}
