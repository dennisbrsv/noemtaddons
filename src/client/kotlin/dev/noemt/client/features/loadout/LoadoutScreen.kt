package dev.noemt.client.features.loadout

import dev.noemt.client.config.ConfigManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.Color

class LoadoutScreen : Screen(Component.literal("NoemtAddons Auto Loadout Swapper")) {
    private val mcInstance: Minecraft get() = Minecraft.getInstance()

    // Rule Builder Inputs
    private var builderTriggerType = "INSTANCE" // "INSTANCE", "AIM", "CHAT"
    private var builderGameInstance = GameInstanceType.DUNGEONS
    private var builderMobCategory = MobCategory.BLOOD_MOB
    private var builderTargetSlot = 1
    private var chatPatternBox: EditBox? = null
    private var cooldownBox: EditBox? = null

    private var scrollOffsetLeft = 0
    private var scrollOffsetRight = 0

    override fun init() {
        clearWidgets()
        val cardWidth = (width * 0.94f).coerceIn(540f, 880f).toInt()
        val cardHeight = (height * 0.88f).coerceIn(340f, 580f).toInt()
        val cardX = (width - cardWidth) / 2
        val cardY = (height - cardHeight) / 2

        val colWidth = (cardWidth - 44) / 2
        val leftX = cardX + 16
        val rightX = leftX + colWidth + 12

        // Top Actions
        addRenderableWidget(
            Button.builder(Component.literal("⚡ Sync SkyBlock (/loadouts)")) {
                LoadoutManager.requestSkyblockSync()
            }.bounds(cardX + cardWidth - 260, cardY + 8, 175, 20).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("§c✕ Close")) {
                onClose()
            }.bounds(cardX + cardWidth - 75, cardY + 8, 65, 20).build()
        )

        // Left Column: 12 SkyBlock Loadout Slots
        var rowY = cardY + 56 - scrollOffsetLeft
        for (i in 1..12) {
            val id = "loadout_$i"
            val lo = LoadoutManager.loadouts[id] ?: Loadout(id = id, name = "Loadout $i", loadoutSlot = i)

            if (rowY in (cardY + 50)..(cardY + cardHeight - 65)) {
                val currentLo = lo
                // Equip button
                addRenderableWidget(
                    Button.builder(Component.literal("⚡")) {
                        LoadoutManager.swapTo(currentLo.id, "GUI Equip")
                        init()
                    }.bounds(leftX + colWidth - 110, rowY + 3, 26, 18).build()
                )

                // Set Toggle A
                val isA = LoadoutManager.loadoutAId == currentLo.id
                addRenderableWidget(
                    Button.builder(Component.literal(if (isA) "§a[A]" else "A")) {
                        LoadoutManager.loadoutAId = currentLo.id
                        init()
                    }.bounds(leftX + colWidth - 80, rowY + 3, 36, 18).build()
                )

                // Set Toggle B
                val isB = LoadoutManager.loadoutBId == currentLo.id
                addRenderableWidget(
                    Button.builder(Component.literal(if (isB) "§e[B]" else "B")) {
                        LoadoutManager.loadoutBId = currentLo.id
                        init()
                    }.bounds(leftX + colWidth - 40, rowY + 3, 36, 18).build()
                )
            }
            rowY += 32
        }

        // Right Column: Active Rules List & Rule Builder
        var ruleY = cardY + 56 - scrollOffsetRight
        val ruleList = LoadoutManager.rules.toList()

        for (rule in ruleList) {
            if (ruleY in (cardY + 50)..(cardY + cardHeight - 170)) {
                val r = rule
                val toggleLabel = if (r.enabled) "§a● ON" else "§c○ OFF"
                addRenderableWidget(
                    Button.builder(Component.literal(toggleLabel)) {
                        r.enabled = !r.enabled
                        LoadoutManager.saveData()
                        init()
                    }.bounds(rightX + colWidth - 95, ruleY + 3, 50, 18).build()
                )

                addRenderableWidget(
                    Button.builder(Component.literal("§c🗑")) {
                        LoadoutManager.removeRule(r.id)
                        init()
                    }.bounds(rightX + colWidth - 40, ruleY + 3, 36, 18).build()
                )
            }
            ruleY += 32
        }

        // Right Column Bottom: Rule Builder Form
        val builderY = cardY + cardHeight - 150

        // 1. Trigger Type Selector
        val triggerTypes = listOf(
            "INSTANCE" to "🏰 Game Instance",
            "AIM" to "🎯 Mob Aim",
            "CHAT" to "💬 Chat Msg"
        )
        var tX = rightX
        for ((tKey, tLabel) in triggerTypes) {
            val isSel = builderTriggerType == tKey
            addRenderableWidget(
                Button.builder(Component.literal(if (isSel) "§b§l$tLabel" else "§7$tLabel")) {
                    builderTriggerType = tKey
                    init()
                }.bounds(tX, builderY + 16, 110, 18).build()
            )
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
                addRenderableWidget(
                    Button.builder(Component.literal(if (isSel) "§a§l$instLabel" else "§7$instLabel")) {
                        builderGameInstance = instEnum
                        init()
                    }.bounds(instX, builderY + 38, 76, 18).build()
                )
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
                addRenderableWidget(
                    Button.builder(Component.literal(if (isSel) "§a§l$catLabel" else "§7$catLabel")) {
                        builderMobCategory = catEnum
                        init()
                    }.bounds(catX, builderY + 38, 72, 18).build()
                )
                catX += 75
            }
        } else if (builderTriggerType == "CHAT") {
            val pBox = EditBox(font, rightX + 60, builderY + 38, colWidth - 60, 18, Component.literal("Pattern"))
            pBox.value = "[BOSS]"
            addRenderableWidget(pBox)
            chatPatternBox = pBox
        }

        // 3. Target Slot Selector (1..12)
        var slotBtnX = rightX
        for (s in 1..6) {
            val isSel = builderTargetSlot == s
            addRenderableWidget(
                Button.builder(Component.literal(if (isSel) "§6§lSlot $s" else "§7S$s")) {
                    builderTargetSlot = s
                    init()
                }.bounds(slotBtnX, builderY + 62, 56, 18).build()
            )
            slotBtnX += 59
        }

        slotBtnX = rightX
        for (s in 7..12) {
            val isSel = builderTargetSlot == s
            addRenderableWidget(
                Button.builder(Component.literal(if (isSel) "§6§lSlot $s" else "§7S$s")) {
                    builderTargetSlot = s
                    init()
                }.bounds(slotBtnX, builderY + 82, 56, 18).build()
            )
            slotBtnX += 59
        }

        // Cooldown Box
        val cd = EditBox(font, rightX + 90, builderY + 104, 45, 18, Component.literal("Cooldown"))
        cd.value = "2.5"
        addRenderableWidget(cd)
        cooldownBox = cd

        // Add Rule Button
        addRenderableWidget(
            Button.builder(Component.literal("§a➕ Save Auto-Swap Rule")) {
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
            }.bounds(rightX + 145, builderY + 104, colWidth - 145, 18).build()
        )

        // Bottom Bar Settings
        val bottomY = cardY + cardHeight - 26
        val config = ConfigManager.config.loadout

        addRenderableWidget(
            Button.builder(Component.literal(if (config.enabled) "§a● Swapper ENABLED" else "§c○ Swapper DISABLED")) {
                config.enabled = !config.enabled
                init()
            }.bounds(cardX + 16, bottomY, 150, 18).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal(if (config.showHud) "§a● HUD ON" else "§7○ HUD OFF")) {
                config.showHud = !config.showHud
                init()
            }.bounds(cardX + 175, bottomY, 95, 18).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal(if (config.playSound) "§a● Sound ON" else "§7○ Sound OFF")) {
                config.playSound = !config.playSound
                init()
            }.bounds(cardX + 278, bottomY, 95, 18).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("🔄 Test Toggle (V)")) {
                LoadoutManager.toggleAB()
                init()
            }.bounds(cardX + cardWidth - 150, bottomY, 134, 18).build()
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fill(0, 0, width, height, Color(0, 0, 0, 195).rgb)

        val cardWidth = (width * 0.94f).coerceIn(540f, 880f).toInt()
        val cardHeight = (height * 0.88f).coerceIn(340f, 580f).toInt()
        val cardX = (width - cardWidth) / 2
        val cardY = (height - cardHeight) / 2

        val colWidth = (cardWidth - 44) / 2
        val leftX = cardX + 16
        val rightX = leftX + colWidth + 12

        // Card Border & Background
        val borderColor = Color(0, 195, 255, 230).rgb
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, Color(16, 20, 30, 245).rgb)
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + 1, borderColor)
        graphics.fill(cardX, cardY + cardHeight - 1, cardX + cardWidth, cardY + cardHeight, borderColor)
        graphics.fill(cardX, cardY, cardX + 1, cardY + cardHeight, borderColor)
        graphics.fill(cardX + cardWidth - 1, cardY, cardX + cardWidth, cardY + cardHeight, borderColor)

        // Header Title
        val currentLo = LoadoutManager.getCurrentLoadout()?.name ?: "None"
        val loAName = LoadoutManager.loadouts[LoadoutManager.loadoutAId]?.name ?: LoadoutManager.loadoutAId
        val loBName = LoadoutManager.loadouts[LoadoutManager.loadoutBId]?.name ?: LoadoutManager.loadoutBId

        graphics.text(font, "§b§lNoemtAddons §8• §fAuto Loadout Swapper §c[CHEAT]", cardX + 16, cardY + 10, Color.WHITE.rgb, true)
        graphics.text(font, "§7Active: §e$currentLo §8| §7Toggle Pair: §a[A: $loAName] §7⇄ §e[B: $loBName]", cardX + 16, cardY + 22, Color.LIGHT_GRAY.rgb, false)

        // Separators
        graphics.fill(cardX + 16, cardY + 36, cardX + cardWidth - 16, cardY + 37, Color(50, 65, 90, 255).rgb)
        graphics.fill(leftX + colWidth + 5, cardY + 40, leftX + colWidth + 6, cardY + cardHeight - 34, Color(40, 52, 72, 200).rgb)
        graphics.fill(cardX + 16, cardY + cardHeight - 32, cardX + cardWidth - 16, cardY + cardHeight - 31, Color(50, 65, 90, 255).rgb)

        // Column Titles
        graphics.text(font, "§b§l1. SkyBlock Loadouts (Auto-Synced)", leftX, cardY + 42, Color.WHITE.rgb, true)
        graphics.text(font, "§b§l2. Conditional Auto-Swap Rules", rightX, cardY + 42, Color.WHITE.rgb, true)

        // Render Left Column: Loadouts
        var rowY = cardY + 56 - scrollOffsetLeft
        for (i in 1..12) {
            val id = "loadout_$i"
            val lo = LoadoutManager.loadouts[id] ?: Loadout(id = id, name = "Loadout $i", loadoutSlot = i)

            if (rowY in (cardY + 50)..(cardY + cardHeight - 65)) {
                val isActive = LoadoutManager.currentLoadoutId == lo.id
                val rowBg = if (isActive) Color(30, 48, 75, 220).rgb else Color(22, 28, 40, 180).rgb
                graphics.fill(leftX, rowY, leftX + colWidth, rowY + 26, rowBg)

                val badge = "§6S$i: "
                val nameDisplay = lo.name.take(24)
                graphics.text(font, "$badge§f$nameDisplay", leftX + 6, rowY + 8, Color.WHITE.rgb, true)
            }
            rowY += 32
        }

        // Render Right Column: Active Rules
        var ruleY = cardY + 56 - scrollOffsetRight
        val ruleList = LoadoutManager.rules.toList()

        if (ruleList.isEmpty()) {
            graphics.text(font, "§7No rules yet. Add one below!", rightX + 6, cardY + 65, Color.GRAY.rgb, false)
        } else {
            for (rule in ruleList) {
                if (ruleY in (cardY + 50)..(cardY + cardHeight - 170)) {
                    val rowBg = if (rule.enabled) Color(24, 36, 54, 200).rgb else Color(20, 24, 32, 160).rgb
                    graphics.fill(rightX, ruleY, rightX + colWidth, ruleY + 26, rowBg)

                    val targetLo = LoadoutManager.loadouts[rule.targetLoadoutId]?.name ?: rule.targetLoadoutId
                    graphics.text(font, "§f${rule.name} §7➜ §6$targetLo", rightX + 6, ruleY + 8, Color.WHITE.rgb, true)
                }
                ruleY += 32
            }
        }

        // Render Rule Builder Section Header
        val builderY = cardY + cardHeight - 150
        graphics.fill(rightX, builderY - 6, rightX + colWidth, builderY - 5, Color(50, 65, 90, 200).rgb)
        graphics.text(font, "§e§l➕ Add Rule (Instance / Aim / Chat ➜ Auto-Swap):", rightX, builderY + 2, Color.YELLOW.rgb, true)
        if (builderTriggerType == "CHAT") {
            graphics.text(font, "§7Pattern:", rightX + 6, builderY + 42, Color.LIGHT_GRAY.rgb, false)
        }
        graphics.text(font, "§7Cooldown:", rightX + 20, builderY + 108, Color.LIGHT_GRAY.rgb, false)

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val cardWidth = (width * 0.94f).coerceIn(540f, 880f).toInt()
        val cardX = (width - cardWidth) / 2
        val colWidth = (cardWidth - 44) / 2

        if (mouseX < cardX + colWidth + 16) {
            if (scrollY > 0) scrollOffsetLeft = (scrollOffsetLeft - 24).coerceAtLeast(0)
            else if (scrollY < 0) scrollOffsetLeft = (scrollOffsetLeft + 24).coerceAtMost(220)
        } else {
            if (scrollY > 0) scrollOffsetRight = (scrollOffsetRight - 24).coerceAtLeast(0)
            else if (scrollY < 0) scrollOffsetRight = (scrollOffsetRight + 24).coerceAtMost(180)
        }
        init()
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
