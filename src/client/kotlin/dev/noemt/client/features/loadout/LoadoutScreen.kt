package dev.noemt.client.features.loadout

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.utils.ChatUtils.removeFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

class LoadoutScreen : Screen(Component.literal("Auto Loadout Swapper")) {
    private val mc: Minecraft get() = Minecraft.getInstance()

    enum class MenuView {
        MAIN_CONFIG,
        SELECT_LOADOUT
    }

    enum class ConfigTarget(
        val displayName: String,
        val defaultItem: Item,
        val description: String,
        val defaultRuleId: String,
        val conditionFactory: () -> LoadoutCondition
    ) {
        CATACOMBS(
            "The Catacombs (Dungeons)",
            Items.BEACON,
            "Auto-swap loadout upon entering The Catacombs or Dungeon Runs.",
            "rule_catacombs",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.DUNGEONS) }
        ),
        MINIBOSS(
            "Miniboss Lock-On (SA/LA/AA/Midas)",
            Items.DIAMOND_SWORD,
            "Auto-swaps when looking at a Miniboss, then auto-reverts upon kill!",
            "rule_miniboss",
            { LoadoutCondition.MinibossCondition(autoRevertOnKill = true) }
        ),
        BLOOD_MOBS(
            "Blood Room Mobs & Watcher",
            Items.REDSTONE_BLOCK,
            "Auto-swap when aiming at Blood Room mobs or The Watcher.",
            "rule_blood",
            { LoadoutCondition.AimCondition(MobCategory.BLOOD_MOB) }
        ),
        KUUDRA(
            "Kuudra Arena",
            Items.MAGMA_CREAM,
            "Auto-swap when entering Kuudra Arena in Crimson Isle.",
            "rule_kuudra",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.KUUDRA) }
        ),
        CRIMSON_ISLE(
            "Crimson Isle",
            Items.BLAZE_POWDER,
            "Auto-swap when on the Crimson Isle nether island.",
            "rule_crimson",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.CRIMSON_ISLE) }
        ),
        MINING(
            "Mining Islands (Dwarven / Hollows)",
            Items.DIAMOND_PICKAXE,
            "Auto-swap when entering Dwarven Mines, Crystal Hollows, or Mineshafts.",
            "rule_mining",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.DWARVEN_MINES) }
        ),
        GARDEN(
            "The Garden (Farming)",
            Items.GOLDEN_HOE,
            "Auto-swap when entering The Garden island.",
            "rule_garden",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.GARDEN) }
        ),
        THE_PARK(
            "The Park (Foraging)",
            Items.OAK_LOG,
            "Auto-swap when entering The Park.",
            "rule_park",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.THE_PARK) }
        ),
        SPIDER_DEN(
            "Spider's Den",
            Items.SPIDER_EYE,
            "Auto-swap when entering Spider's Den.",
            "rule_spider",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.SPIDER_DEN) }
        ),
        THE_END(
            "The End (Dragons & Zealots)",
            Items.ENDER_EYE,
            "Auto-swap when entering The End.",
            "rule_end",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.THE_END) }
        ),
        THE_RIFT(
            "The Rift",
            Items.RECOVERY_COMPASS,
            "Auto-swap when entering The Rift dimension.",
            "rule_rift",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.THE_RIFT) }
        ),
        HUB(
            "Hub / Village",
            Items.EMERALD,
            "Auto-swap when in Hub Island or Village.",
            "rule_hub",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.HUB) }
        ),
        PRIVATE_ISLAND(
            "Private Island",
            Items.GRASS_BLOCK,
            "Auto-swap when on your Private Island.",
            "rule_island",
            { LoadoutCondition.GameInstanceCondition(GameInstanceType.PRIVATE_ISLAND) }
        ),
        SLAYER_BOSS(
            "Slayer Boss Aim",
            Items.ZOMBIE_HEAD,
            "Auto-swap when aiming at an active Slayer Boss.",
            "rule_slayer",
            { LoadoutCondition.AimCondition(MobCategory.SLAYER) }
        ),
        TOGGLE_A(
            "Quick Toggle Set A (Keybind [V])",
            Items.LIME_DYE,
            "Primary quick-swap loadout bound to [V] keybind.",
            "toggle_a",
            { LoadoutCondition.MinibossCondition() }
        ),
        TOGGLE_B(
            "Quick Toggle Set B (Keybind [V])",
            Items.YELLOW_DYE,
            "Secondary quick-swap loadout bound to [V] keybind.",
            "toggle_b",
            { LoadoutCondition.MinibossCondition() }
        )
    }

    private var currentView = MenuView.MAIN_CONFIG
    private var activeConfigTarget: ConfigTarget? = null

    private val CONTAINER_BACKGROUND = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/container/generic_54.png")
    private val imageWidth = 176
    private val imageHeight = 222

    // SkyBlock Loadout slot mapping (Hypixel /loadouts menu layout)
    private val HYPIXEL_LOADOUT_SLOTS = listOf(
        14 to 1, 15 to 2, 16 to 3,
        23 to 4, 24 to 5, 25 to 6,
        32 to 7, 33 to 8, 34 to 9,
        41 to 10, 42 to 11, 43 to 12
    )

    // Main Config Menu Slot Positions
    private val MAIN_SLOT_MAP = mapOf(
        10 to ConfigTarget.CATACOMBS,
        11 to ConfigTarget.MINIBOSS,
        12 to ConfigTarget.BLOOD_MOBS,
        13 to ConfigTarget.KUUDRA,
        14 to ConfigTarget.CRIMSON_ISLE,
        15 to ConfigTarget.MINING,
        16 to ConfigTarget.GARDEN,
        19 to ConfigTarget.THE_PARK,
        20 to ConfigTarget.SPIDER_DEN,
        21 to ConfigTarget.THE_END,
        22 to ConfigTarget.THE_RIFT,
        23 to ConfigTarget.HUB,
        24 to ConfigTarget.PRIVATE_ISLAND,
        25 to ConfigTarget.SLAYER_BOSS,
        30 to ConfigTarget.TOGGLE_A,
        31 to ConfigTarget.TOGGLE_B
    )

    override fun added() {
        super.added()
        LoadoutManager.onDataChanged = {
            mc.execute {
                if (mc.screen == this) {
                    init()
                }
            }
        }
    }

    override fun removed() {
        super.removed()
        LoadoutManager.onDataChanged = null
    }

    private fun createItem(
        item: Item,
        name: String,
        lore: List<String> = emptyList(),
        count: Int = 1,
        glint: Boolean = false
    ): ItemStack {
        val stack = ItemStack(item, count)
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name))
        if (lore.isNotEmpty()) {
            stack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
        }
        if (glint) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        }
        return stack
    }

    private fun getAssignedLoadoutName(target: ConfigTarget): String? {
        return when (target) {
            ConfigTarget.TOGGLE_A -> LoadoutManager.loadoutAId.takeIf { it.isNotBlank() }?.let { LoadoutManager.loadouts[it]?.name ?: it }
            ConfigTarget.TOGGLE_B -> LoadoutManager.loadoutBId.takeIf { it.isNotBlank() }?.let { LoadoutManager.loadouts[it]?.name ?: it }
            else -> {
                val rule = LoadoutManager.rules.find { it.id == target.defaultRuleId }
                rule?.targetLoadoutId?.let { LoadoutManager.loadouts[it]?.name ?: it }
            }
        }
    }

    private fun isRuleEnabled(target: ConfigTarget): Boolean {
        return when (target) {
            ConfigTarget.TOGGLE_A, ConfigTarget.TOGGLE_B -> getAssignedLoadoutName(target) != null
            else -> {
                val rule = LoadoutManager.rules.find { it.id == target.defaultRuleId }
                rule?.enabled == true
            }
        }
    }

    private fun getSlotItem(slot: Int): ItemStack? {
        val pane = createItem(Items.GRAY_STAINED_GLASS_PANE, " ")

        return when (currentView) {
            MenuView.MAIN_CONFIG -> getMainConfigSlotItem(slot, pane)
            MenuView.SELECT_LOADOUT -> getSelectLoadoutSlotItem(slot, pane)
        }
    }

    private fun getMainConfigSlotItem(slot: Int, pane: ItemStack): ItemStack? {
        // Config Targets
        val configTarget = MAIN_SLOT_MAP[slot]
        if (configTarget != null) {
            val assigned = getAssignedLoadoutName(configTarget)
            val enabled = isRuleEnabled(configTarget)
            val assignedDisplay = assigned ?: "§cNone (Unassigned)"
            val statusDisplay = if (assigned != null && enabled) "§aEnabled" else if (assigned != null) "§cDisabled" else "§7Not Configured"

            val lore = mutableListOf(
                "§7${configTarget.description}",
                "",
                "§7Assigned Loadout: §e$assignedDisplay",
                "§7Trigger Status: $statusDisplay",
                ""
            )

            if (assigned != null) {
                lore.add("§eClick to change loadout!")
                lore.add("§bRight-Click to toggle ON/OFF!")
            } else {
                lore.add("§eClick to select loadout!")
            }

            return createItem(
                configTarget.defaultItem,
                "§a${configTarget.displayName}",
                lore,
                glint = enabled && assigned != null
            )
        }

        // Bottom Controls
        val config = ConfigManager.config.loadout
        when (slot) {
            32 -> {
                val prevName = LoadoutManager.previousLoadoutId?.let { LoadoutManager.loadouts[it]?.name ?: it } ?: "None"
                return createItem(
                    Items.COMPASS,
                    "§bRevert to Previous Loadout",
                    listOf(
                        "§7Swaps back to the last equipped loadout.",
                        "§7Keybind: §e[B]",
                        "",
                        "§7Last Loadout: §e$prevName",
                        "",
                        "§eClick to execute swap back now!"
                    )
                )
            }
            45 -> {
                return if (config.enabled) {
                    createItem(
                        Items.LIME_DYE,
                        "§aMaster Swapper: §lENABLED",
                        listOf("§7Global auto-swapping is active.", "", "§eClick to disable!")
                    )
                } else {
                    createItem(
                        Items.GRAY_DYE,
                        "§cMaster Swapper: §lDISABLED",
                        listOf("§7Global auto-swapping is paused.", "", "§eClick to enable!")
                    )
                }
            }
            46 -> {
                return createItem(
                    Items.ITEM_FRAME,
                    if (config.showHud) "§aHUD Display: §lON" else "§7HUD Display: §lOFF",
                    listOf("§7Shows current loadout on screen overlay.", "", "§eClick to toggle!")
                )
            }
            47 -> {
                return createItem(
                    Items.NOTE_BLOCK,
                    if (config.playSound) "§aSound Effects: §lON" else "§7Sound Effects: §lOFF",
                    listOf("§7Plays sound when loadout is equipped.", "", "§eClick to toggle!")
                )
            }
            48 -> {
                return createItem(
                    Items.NETHER_STAR,
                    "§eSync SkyBlock Loadouts",
                    listOf(
                        "§7Syncs all 12 loadouts from",
                        "§7SkyBlock /loadouts container.",
                        "",
                        "§eClick to sync now!"
                    )
                )
            }
            49 -> {
                return createItem(
                    Items.BARRIER,
                    "§cClose Menu",
                    listOf("§7Click to exit menu.")
                )
            }
            50 -> {
                return createItem(
                    Items.CLOCK,
                    "§6Swap Cooldown: §e2.5s",
                    listOf("§7Minimum delay between auto-swaps.", "", "§7Auto-calculated safely.")
                )
            }
        }

        // Filler Panes
        return if (slot in 0..8 || slot in listOf(9, 17, 18, 26, 27, 35, 36, 44, 51, 52, 53)) {
            pane
        } else {
            null
        }
    }

    private fun getSelectLoadoutSlotItem(slot: Int, pane: ItemStack): ItemStack? {
        val target = activeConfigTarget ?: ConfigTarget.CATACOMBS
        val assigned = getAssignedLoadoutName(target)

        // 12 Loadout Slots
        val loadoutEntry = HYPIXEL_LOADOUT_SLOTS.find { it.first == slot }
        if (loadoutEntry != null) {
            val loadoutNum = loadoutEntry.second
            val id = "loadout_$loadoutNum"
            val lo = LoadoutManager.loadouts[id] ?: Loadout(id = id, name = "Loadout $loadoutNum", loadoutSlot = loadoutNum)
            val isSelected = assigned == lo.name || assigned == id

            val lore = mutableListOf(
                "§7Loadout Slot: §fSlot $loadoutNum",
                "§7Pet: §6${lo.petName ?: "None"}",
                ""
            )

            if (isSelected) {
                lore.add("§a✓ Currently Selected for ${target.displayName}")
                lore.add("")
                lore.add("§eClick to re-select!")
            } else {
                lore.add("§eClick to assign this loadout!")
            }

            val iconItem = when (loadoutNum) {
                1 -> Items.NETHERITE_CHESTPLATE
                2 -> Items.DIAMOND_CHESTPLATE
                3 -> Items.GOLDEN_CHESTPLATE
                4 -> Items.IRON_CHESTPLATE
                5 -> Items.CHAINMAIL_CHESTPLATE
                6 -> Items.LEATHER_CHESTPLATE
                7 -> Items.DIAMOND_HELMET
                8 -> Items.GOLDEN_HELMET
                9 -> Items.IRON_HELMET
                10 -> Items.CHAINMAIL_HELMET
                11 -> Items.LEATHER_HELMET
                else -> Items.TURTLE_HELMET
            }

            return createItem(
                iconItem,
                "§aLoadout #$loadoutNum: §f${lo.name}",
                lore,
                glint = isSelected
            )
        }

        // Controls
        when (slot) {
            48 -> {
                return createItem(
                    Items.RED_STAINED_GLASS_PANE,
                    "§cUnassign / None",
                    listOf(
                        "§7Removes the assigned loadout for",
                        "§e${target.displayName}",
                        "",
                        "§eClick to unassign!"
                    )
                )
            }
            49 -> {
                return createItem(
                    Items.ARROW,
                    "§aGo Back",
                    listOf("§7To Auto Loadout Swapper Menu")
                )
            }
        }

        return if (slot in 0..8 || slot in listOf(9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 50, 51, 52, 53)) {
            pane
        } else {
            null
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Dark screen backdrop
        graphics.fill(0, 0, width, height, 0xCC101010.toInt())

        val left = (width - imageWidth) / 2
        val top = (height - imageHeight) / 2

        // Draw Chest Container Texture
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_BACKGROUND, left, top, 0f, 0f, imageWidth, imageHeight, 256, 256, -1)

        // Chest Title
        val menuTitle = when (currentView) {
            MenuView.MAIN_CONFIG -> "Auto Loadout Swapper"
            MenuView.SELECT_LOADOUT -> "Select: ${activeConfigTarget?.displayName?.take(18)}"
        }
        graphics.text(font, menuTitle, left + 8, top + 6, 0x404040, false)

        var hoveredItem: ItemStack? = null

        // Render 54 Slots
        for (idx in 0..53) {
            val row = idx / 9
            val col = idx % 9
            val slotX = left + 8 + (col * 18)
            val slotY = top + 18 + (row * 18)

            val item = getSlotItem(idx)
            if (item != null && !item.isEmpty) {
                graphics.item(item, slotX, slotY)
                graphics.itemDecorations(font, item, slotX, slotY)
            }

            // Hover check
            if (mouseX in slotX..(slotX + 16) && mouseY in slotY..(slotY + 16)) {
                graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0x80FFFFFF.toInt())
                if (item != null && !item.isEmpty) {
                    hoveredItem = item
                }
            }
        }

        // Render Tooltip
        if (hoveredItem != null) {
            graphics.setTooltipForNextFrame(font, hoveredItem, mouseX, mouseY)
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val left = (width - imageWidth) / 2
        val top = (height - imageHeight) / 2
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        val button = event.button()

        for (idx in 0..53) {
            val row = idx / 9
            val col = idx % 9
            val slotX = left + 8 + (col * 18)
            val slotY = top + 18 + (row * 18)

            if (mouseX in slotX..(slotX + 16) && mouseY in slotY..(slotY + 16)) {
                val handled = handleSlotClick(idx, button)
                if (handled) {
                    mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                    return true
                }
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    private fun handleSlotClick(slot: Int, mouseButton: Int): Boolean {
        when (currentView) {
            MenuView.MAIN_CONFIG -> {
                val target = MAIN_SLOT_MAP[slot]
                if (target != null) {
                    if (mouseButton == 1) { // Right Click: Toggle ON/OFF
                        val rule = LoadoutManager.rules.find { it.id == target.defaultRuleId }
                        if (rule != null) {
                            rule.enabled = !rule.enabled
                            LoadoutManager.saveData()
                            return true
                        }
                    }
                    // Left Click: Open Loadout Picker Sub-menu
                    activeConfigTarget = target
                    currentView = MenuView.SELECT_LOADOUT
                    return true
                }

                val config = ConfigManager.config.loadout
                when (slot) {
                    32 -> {
                        LoadoutManager.swapToPrevious()
                        return true
                    }
                    45 -> {
                        config.enabled = !config.enabled
                        return true
                    }
                    46 -> {
                        config.showHud = !config.showHud
                        return true
                    }
                    47 -> {
                        config.playSound = !config.playSound
                        return true
                    }
                    48 -> {
                        LoadoutManager.requestSkyblockSync()
                        return true
                    }
                    49 -> {
                        onClose()
                        return true
                    }
                }
            }

            MenuView.SELECT_LOADOUT -> {
                val target = activeConfigTarget ?: return false

                // Clicked a Loadout Slot
                val loadoutEntry = HYPIXEL_LOADOUT_SLOTS.find { it.first == slot }
                if (loadoutEntry != null) {
                    val loadoutNum = loadoutEntry.second
                    val targetLoadoutId = "loadout_$loadoutNum"

                    when (target) {
                        ConfigTarget.TOGGLE_A -> {
                            LoadoutManager.loadoutAId = targetLoadoutId
                        }
                        ConfigTarget.TOGGLE_B -> {
                            LoadoutManager.loadoutBId = targetLoadoutId
                        }
                        else -> {
                            val newRule = LoadoutRule(
                                id = target.defaultRuleId,
                                name = target.displayName,
                                enabled = true,
                                targetLoadoutId = targetLoadoutId,
                                condition = target.conditionFactory(),
                                cooldownSeconds = 2.5
                            )
                            LoadoutManager.addOrUpdateRule(newRule)
                        }
                    }

                    LoadoutManager.saveData()
                    currentView = MenuView.MAIN_CONFIG
                    return true
                }

                // Unassign Button
                if (slot == 48) {
                    when (target) {
                        ConfigTarget.TOGGLE_A -> LoadoutManager.loadoutAId = ""
                        ConfigTarget.TOGGLE_B -> LoadoutManager.loadoutBId = ""
                        else -> LoadoutManager.removeRule(target.defaultRuleId)
                    }
                    LoadoutManager.saveData()
                    currentView = MenuView.MAIN_CONFIG
                    return true
                }

                // Go Back Button
                if (slot == 49) {
                    currentView = MenuView.MAIN_CONFIG
                    return true
                }
            }
        }

        return false
    }

    override fun isPauseScreen(): Boolean = false
}
