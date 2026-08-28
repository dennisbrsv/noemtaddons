package dev.noemt.client.features.loadout

import dev.noemt.client.config.ConfigManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.Color

class LoadoutScreen : Screen(Component.literal("NoemtAddons Loadout Builder")) {
    private val mcInstance: Minecraft get() = Minecraft.getInstance()

    private enum class Tab {
        LOADOUTS,
        RULES,
        BUILDER,
        TIMING
    }

    private var activeTab = Tab.LOADOUTS

    // Inputs for Rule Builder Tab
    private var ruleNameBox: EditBox? = null
    private var patternBox: EditBox? = null
    private var customNameBox: EditBox? = null
    private var customSkullBox: EditBox? = null
    private var cooldownBox: EditBox? = null

    private var builderConditionType = "AIM" // "AIM", "CHAT", "PROXIMITY", "LOCATION"
    private var builderMobCategory = MobCategory.BLOOD_MOB
    private var builderTargetLoadout = "loadout_2"
    private var builderMatchType = MatchType.CONTAINS

    private var scrollOffset = 0

    override fun init() {
        clearWidgets()
        val cardWidth = (width * 0.85f).coerceIn(440f, 720f).toInt()
        val cardHeight = (height * 0.8f).coerceIn(280f, 520f).toInt()
        val cardX = (width - cardWidth) / 2
        val cardY = (height - cardHeight) / 2

        // Top Navigation Tab Buttons
        val tabWidth = 95
        val tabHeight = 20
        var tabStartX = cardX + 16
        val tabY = cardY + 34

        for (tab in Tab.values()) {
            val label = when (tab) {
                Tab.LOADOUTS -> "📦 Loadouts"
                Tab.RULES -> "⚡ Active Rules"
                Tab.BUILDER -> "🛠️ New Rule"
                Tab.TIMING -> "⏱️ Settings"
            }
            val isCurrent = tab == activeTab
            addRenderableWidget(
                Button.builder(Component.literal(if (isCurrent) "§b§l$label" else "§7$label")) {
                    activeTab = tab
                    scrollOffset = 0
                    init()
                }.bounds(tabStartX, tabY, tabWidth, tabHeight).build()
            )
            tabStartX += tabWidth + 6
        }

        // Close Button
        addRenderableWidget(
            Button.builder(Component.literal("§c✕ Close")) {
                onClose()
            }.bounds(cardX + cardWidth - 75, cardY + 8, 65, 20).build()
        )

        // Initialize Widgets based on Tab
        when (activeTab) {
            Tab.LOADOUTS -> initLoadoutsTab(cardX, cardY, cardWidth, cardHeight)
            Tab.RULES -> initRulesTab(cardX, cardY, cardWidth, cardHeight)
            Tab.BUILDER -> initBuilderTab(cardX, cardY, cardWidth, cardHeight)
            Tab.TIMING -> initTimingTab(cardX, cardY, cardWidth, cardHeight)
        }
    }

    private fun initLoadoutsTab(cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
        var btnY = cardY + 70 - scrollOffset
        val loadoutList = LoadoutManager.loadouts.values.toList()

        for (lo in loadoutList) {
            if (btnY in (cardY + 60)..(cardY + cardHeight - 50)) {
                val currentLo = lo
                // Equip button
                addRenderableWidget(
                    Button.builder(Component.literal("⚡ Equip")) {
                        LoadoutManager.swapTo(currentLo.id, "GUI Equip")
                        init()
                    }.bounds(cardX + cardWidth - 210, btnY + 4, 60, 18).build()
                )

                // Set as Toggle A
                val isA = LoadoutManager.loadoutAId == currentLo.id
                addRenderableWidget(
                    Button.builder(Component.literal(if (isA) "§a[A Active]" else "Set A")) {
                        LoadoutManager.loadoutAId = currentLo.id
                        init()
                    }.bounds(cardX + cardWidth - 145, btnY + 4, 65, 18).build()
                )

                // Set as Toggle B
                val isB = LoadoutManager.loadoutBId == currentLo.id
                addRenderableWidget(
                    Button.builder(Component.literal(if (isB) "§e[B Active]" else "Set B")) {
                        LoadoutManager.loadoutBId = currentLo.id
                        init()
                    }.bounds(cardX + cardWidth - 75, btnY + 4, 65, 18).build()
                )
            }
            btnY += 46
        }

        // Toggle A/B Test Button
        addRenderableWidget(
            Button.builder(Component.literal("🔄 Test Toggle (A ⇄ B)")) {
                LoadoutManager.toggleAB()
                init()
            }.bounds(cardX + 16, cardY + cardHeight - 32, 160, 22).build()
        )
    }

    private fun initRulesTab(cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
        var rowY = cardY + 70 - scrollOffset
        val rulesList = LoadoutManager.rules.toList()

        for (rule in rulesList) {
            if (rowY in (cardY + 60)..(cardY + cardHeight - 50)) {
                val r = rule
                // Toggle Enable/Disable
                val toggleLabel = if (r.enabled) "§a● ON" else "§c○ OFF"
                addRenderableWidget(
                    Button.builder(Component.literal(toggleLabel)) {
                        r.enabled = !r.enabled
                        LoadoutManager.saveData()
                        init()
                    }.bounds(cardX + cardWidth - 180, rowY + 4, 65, 18).build()
                )

                // Delete rule button
                addRenderableWidget(
                    Button.builder(Component.literal("§c🗑 Delete")) {
                        LoadoutManager.removeRule(r.id)
                        init()
                    }.bounds(cardX + cardWidth - 105, rowY + 4, 90, 18).build()
                )
            }
            rowY += 44
        }

        // Add Rule Shortcut
        addRenderableWidget(
            Button.builder(Component.literal("➕ Create New Rule")) {
                activeTab = Tab.BUILDER
                init()
            }.bounds(cardX + 16, cardY + cardHeight - 32, 140, 22).build()
        )
    }

    private fun initBuilderTab(cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
        val formX = cardX + 120
        var formY = cardY + 66

        // 1. Rule Name
        val nameBox = EditBox(font, formX, formY, 220, 18, Component.literal("Rule Name"))
        nameBox.value = "Auto DPS Rule"
        addRenderableWidget(nameBox)
        ruleNameBox = nameBox
        formY += 26

        // 2. Condition Type Selector Buttons
        val condTypes = listOf("AIM" to "🎯 Mob Aim", "CHAT" to "💬 Chat Msg", "PROXIMITY" to "📍 Proximity", "LOCATION" to "🗺️ Area")
        var cX = formX
        for ((typeKey, typeLabel) in condTypes) {
            val isSelected = builderConditionType == typeKey
            addRenderableWidget(
                Button.builder(Component.literal(if (isSelected) "§b§l$typeLabel" else "§7$typeLabel")) {
                    builderConditionType = typeKey
                    init()
                }.bounds(cX, formY, 78, 18).build()
            )
            cX += 82
        }
        formY += 26

        // 3. Sub-options depending on condition type
        if (builderConditionType in listOf("AIM", "PROXIMITY")) {
            // Category Buttons
            val cats = listOf(
                MobCategory.BLOOD_MOB to "Blood Mob",
                MobCategory.WATCHER to "Watcher",
                MobCategory.MINIBOSS to "Miniboss",
                MobCategory.BOSS to "Boss",
                MobCategory.SLAYER to "Slayer",
                MobCategory.CUSTOM_NAME to "Custom Name"
            )
            var catX = formX
            for ((catEnum, catLabel) in cats) {
                val isSel = builderMobCategory == catEnum
                addRenderableWidget(
                    Button.builder(Component.literal(if (isSel) "§a§l$catLabel" else "§7$catLabel")) {
                        builderMobCategory = catEnum
                        init()
                    }.bounds(catX, formY, 74, 18).build()
                )
                catX += 78
                if (catX > formX + 280) {
                    catX = formX
                    formY += 22
                }
            }
            formY += 26

            // Custom Name filter
            val customBox = EditBox(font, formX, formY, 220, 18, Component.literal("Name Filter (Optional)"))
            customBox.setHint(Component.literal("e.g. Livid, Necron, Shadow Assassin"))
            addRenderableWidget(customBox)
            customNameBox = customBox
            formY += 26
        } else if (builderConditionType == "CHAT") {
            // Chat Match Mode Buttons
            val modes = listOf(MatchType.CONTAINS to "CONTAINS", MatchType.STARTS_WITH to "STARTS_WITH", MatchType.REGEX to "REGEX")
            var mX = formX
            for ((modeEnum, modeLabel) in modes) {
                val isSel = builderMatchType == modeEnum
                addRenderableWidget(
                    Button.builder(Component.literal(if (isSel) "§e§l$modeLabel" else "§7$modeLabel")) {
                        builderMatchType = modeEnum
                        init()
                    }.bounds(mX, formY, 90, 18).build()
                )
                mX += 94
            }
            formY += 26

            // Pattern Box
            val pBox = EditBox(font, formX, formY, 260, 18, Component.literal("Chat Pattern"))
            pBox.value = "[BOSS] "
            addRenderableWidget(pBox)
            patternBox = pBox
            formY += 26
        }

        // 4. Target Loadout Selector
        var loX = formX
        for ((loId, loObj) in LoadoutManager.loadouts) {
            val isTarget = builderTargetLoadout == loId
            addRenderableWidget(
                Button.builder(Component.literal(if (isTarget) "§6§l${loObj.name}" else "§7${loObj.name}")) {
                    builderTargetLoadout = loId
                    init()
                }.bounds(loX, formY, 95, 18).build()
            )
            loX += 100
        }
        formY += 26

        // 5. Cooldown Box
        val cdBox = EditBox(font, formX, formY, 80, 18, Component.literal("Cooldown (s)"))
        cdBox.value = "2.5"
        addRenderableWidget(cdBox)
        cooldownBox = cdBox
        formY += 30

        // Create Button
        addRenderableWidget(
            Button.builder(Component.literal("§a✔ Create & Save Rule")) {
                val rName = ruleNameBox?.value?.ifBlank { "Custom Rule" } ?: "Custom Rule"
                val rId = "rule_" + System.currentTimeMillis()
                val cd = cooldownBox?.value?.toDoubleOrNull() ?: 2.0

                val condition: LoadoutCondition = when (builderConditionType) {
                    "AIM" -> LoadoutCondition.AimCondition(
                        mobCategory = builderMobCategory,
                        nameFilter = customNameBox?.value?.takeIf { it.isNotBlank() }
                    )
                    "PROXIMITY" -> LoadoutCondition.ProximityCondition(
                        mobCategory = builderMobCategory,
                        nameFilter = customNameBox?.value?.takeIf { it.isNotBlank() }
                    )
                    "CHAT" -> LoadoutCondition.ChatCondition(
                        pattern = patternBox?.value ?: "[BOSS]",
                        matchType = builderMatchType
                    )
                    else -> LoadoutCondition.AimCondition(mobCategory = MobCategory.BLOOD_MOB)
                }

                val newRule = LoadoutRule(
                    id = rId,
                    name = rName,
                    enabled = true,
                    targetLoadoutId = builderTargetLoadout,
                    condition = condition,
                    cooldownSeconds = cd
                )

                LoadoutManager.addOrUpdateRule(newRule)
                activeTab = Tab.RULES
                init()
            }.bounds(cardX + 16, cardY + cardHeight - 34, 180, 22).build()
        )
    }

    private fun initTimingTab(cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
        val config = ConfigManager.config.loadout
        var sY = cardY + 70

        // Toggle Master Swapper
        addRenderableWidget(
            Button.builder(Component.literal(if (config.enabled) "§a● Swapper Enabled" else "§c○ Swapper Disabled")) {
                config.enabled = !config.enabled
                init()
            }.bounds(cardX + 20, sY, 170, 20).build()
        )

        // Toggle HUD Display
        addRenderableWidget(
            Button.builder(Component.literal(if (config.showHud) "§a● HUD Display ON" else "§7○ HUD Display OFF")) {
                config.showHud = !config.showHud
                init()
            }.bounds(cardX + 200, sY, 160, 20).build()
        )

        // Toggle Sound
        addRenderableWidget(
            Button.builder(Component.literal(if (config.playSound) "§a● Sound ON" else "§7○ Sound OFF")) {
                config.playSound = !config.playSound
                init()
            }.bounds(cardX + 370, sY, 130, 20).build()
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Dark translucent background
        graphics.fill(0, 0, width, height, Color(0, 0, 0, 195).rgb)

        val cardWidth = (width * 0.85f).coerceIn(440f, 720f).toInt()
        val cardHeight = (height * 0.8f).coerceIn(280f, 520f).toInt()
        val cardX = (width - cardWidth) / 2
        val cardY = (height - cardHeight) / 2

        // Card body & cyan border
        val borderColor = Color(0, 195, 255, 230).rgb
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, Color(16, 20, 30, 245).rgb)
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + 1, borderColor)
        graphics.fill(cardX, cardY + cardHeight - 1, cardX + cardWidth, cardY + cardHeight, borderColor)
        graphics.fill(cardX, cardY, cardX + 1, cardY + cardHeight, borderColor)
        graphics.fill(cardX + cardWidth - 1, cardY, cardX + cardWidth, cardY + cardHeight, borderColor)

        // Header Title
        val currentLo = LoadoutManager.getCurrentLoadout()?.name ?: "None"
        graphics.text(font, "§b§lNoemtAddons §8• §fLoadout & Conditional Swapper §c[CHEAT]", cardX + 16, cardY + 12, Color.WHITE.rgb, true)
        graphics.text(font, "§7Active: §e$currentLo §8| §7Toggle: §b${LoadoutManager.loadoutAId} §7⇄ §b${LoadoutManager.loadoutBId}", cardX + 16, cardY + 22, Color.LIGHT_GRAY.rgb, false)

        // Subheader line
        graphics.fill(cardX + 16, cardY + 58, cardX + cardWidth - 16, cardY + 59, Color(50, 65, 90, 255).rgb)

        // Tab Content Rendering
        when (activeTab) {
            Tab.LOADOUTS -> renderLoadoutsTab(graphics, cardX, cardY, cardWidth, cardHeight)
            Tab.RULES -> renderRulesTab(graphics, cardX, cardY, cardWidth, cardHeight)
            Tab.BUILDER -> renderBuilderTab(graphics, cardX, cardY, cardWidth, cardHeight)
            Tab.TIMING -> renderTimingTab(graphics, cardX, cardY, cardWidth, cardHeight)
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun renderLoadoutsTab(graphics: GuiGraphicsExtractor, cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
        var rowY = cardY + 70 - scrollOffset
        val loadoutList = LoadoutManager.loadouts.values.toList()

        for (lo in loadoutList) {
            if (rowY in (cardY + 60)..(cardY + cardHeight - 55)) {
                // Background row card
                val isActive = LoadoutManager.currentLoadoutId == lo.id
                val rowBg = if (isActive) Color(30, 45, 70, 200).rgb else Color(22, 28, 42, 180).rgb
                graphics.fill(cardX + 16, rowY, cardX + cardWidth - 16, rowY + 38, rowBg)

                val activeTag = if (isActive) " §a[EQUIPPED]" else ""
                graphics.text(font, "§e§l${lo.name} §7(${lo.id})$activeTag", cardX + 24, rowY + 6, Color.WHITE.rgb, true)
                graphics.text(font, "§7Wardrobe Slot: §f${lo.wardrobeSlot ?: "None"} §8| §7Hotbar: §f${lo.slot?.let { it + 1 } ?: "None"} §8| §7Pet: §f${lo.petName ?: "None"}", cardX + 24, rowY + 20, Color.LIGHT_GRAY.rgb, false)
            }
            rowY += 46
        }
    }

    private fun renderRulesTab(graphics: GuiGraphicsExtractor, cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
        var rowY = cardY + 70 - scrollOffset
        val rulesList = LoadoutManager.rules.toList()

        if (rulesList.isEmpty()) {
            graphics.centeredText(font, "§7No conditional rules configured. Click 'Create New Rule' below.", width / 2, cardY + 110, Color.GRAY.rgb)
            return
        }

        for (rule in rulesList) {
            if (rowY in (cardY + 60)..(cardY + cardHeight - 55)) {
                val rowBg = if (rule.enabled) Color(24, 34, 52, 190).rgb else Color(20, 24, 32, 150).rgb
                graphics.fill(cardX + 16, rowY, cardX + cardWidth - 16, rowY + 36, rowBg)

                val stateColor = if (rule.enabled) "§a" else "§c"
                graphics.text(font, "$stateColor${rule.name} §7➜ §6${rule.targetLoadoutId}", cardX + 24, rowY + 6, Color.WHITE.rgb, true)

                val condDesc = when (val c = rule.condition) {
                    is LoadoutCondition.AimCondition -> "Trigger: Aim at §b${c.mobCategory} §7(dist: ${c.maxDistance}m)"
                    is LoadoutCondition.ChatCondition -> "Trigger: Chat matches §e\"${c.pattern}\" §7(${c.matchType})"
                    is LoadoutCondition.ProximityCondition -> "Trigger: Mob §b${c.mobCategory} §7within ${c.radius}m"
                    is LoadoutCondition.LocationCondition -> "Trigger: Area §b${c.areaName}"
                    else -> "Trigger: Composite Condition"
                }
                graphics.text(font, "§7$condDesc §8| §7CD: §f${rule.cooldownSeconds}s", cardX + 24, rowY + 20, Color.LIGHT_GRAY.rgb, false)
            }
            rowY += 44
        }
    }

    private fun renderBuilderTab(graphics: GuiGraphicsExtractor, cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
        var lY = cardY + 70
        graphics.text(font, "§bRule Name:", cardX + 20, lY, Color.WHITE.rgb, false)
        lY += 26
        graphics.text(font, "§bTrigger Type:", cardX + 20, lY, Color.WHITE.rgb, false)
        lY += 26

        if (builderConditionType in listOf("AIM", "PROXIMITY")) {
            graphics.text(font, "§bMob Category:", cardX + 20, lY, Color.WHITE.rgb, false)
            lY += 26
            graphics.text(font, "§bName Filter:", cardX + 20, lY, Color.WHITE.rgb, false)
            lY += 26
        } else if (builderConditionType == "CHAT") {
            graphics.text(font, "§bMatch Mode:", cardX + 20, lY, Color.WHITE.rgb, false)
            lY += 26
            graphics.text(font, "§bChat Pattern:", cardX + 20, lY, Color.WHITE.rgb, false)
            lY += 26
        }

        graphics.text(font, "§bTarget Loadout:", cardX + 20, lY, Color.WHITE.rgb, false)
        lY += 26
        graphics.text(font, "§bCooldown (s):", cardX + 20, lY, Color.WHITE.rgb, false)
    }

    private fun renderTimingTab(graphics: GuiGraphicsExtractor, cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
        var sY = cardY + 110
        graphics.text(font, "§e§lAutomated GUI Swap Pipeline Timing:", cardX + 20, sY, Color.WHITE.rgb, true)
        sY += 16
        graphics.text(font, "§7• Step 1: Pre-Command Delay: §f~150ms §7(randomized 130-175ms) ➜ Sends /wardrobe", cardX + 24, sY, Color.LIGHT_GRAY.rgb, false)
        sY += 14
        graphics.text(font, "§7• Step 2: GUI Container Open: §f~100ms §7(randomized 85-125ms) ➜ Clicks Target Slot", cardX + 24, sY, Color.LIGHT_GRAY.rgb, false)
        sY += 14
        graphics.text(font, "§7• Step 3: Post-Click Close: §f~100ms §7(randomized 85-125ms) ➜ Closes Container & Swaps Slot", cardX + 24, sY, Color.LIGHT_GRAY.rgb, false)
        sY += 22
        graphics.text(font, "§7Keybindings: §bV §7(Toggle A/B) • §bB §7(Swap Back to Last Loadout)", cardX + 20, sY, Color.YELLOW.rgb, false)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY > 0) {
            scrollOffset = (scrollOffset - 24).coerceAtLeast(0)
            init()
            return true
        } else if (scrollY < 0) {
            scrollOffset = (scrollOffset + 24).coerceAtMost(300)
            init()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun isPauseScreen(): Boolean = false
}
