package dev.noemt.client.features.gambling.dungeons

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.features.gambling.chest.DungeonChestType
import dev.noemt.client.utils.ItemUtils.skyblockId
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.awt.Color
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.sin

class DungeonSlotMachineEngine(
    val floor: DungeonFloor,
    val chestType: DungeonChestType,
    val winningItemOverride: ItemStack? = null,
    val customDurationSeconds: Float? = null
) {
    private val mc: Minecraft get() = Minecraft.getInstance()

    val CONTAINER_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/container/generic_54.png")
    val SLOT_HEIGHT = 18

    val reels = mutableListOf<List<String>>()
    val lastScrollIndex = IntArray(3) { -1 }
    val reelStopped = BooleanArray(3) { false }

    var startTime = System.currentTimeMillis() + 150L
    val baseDurationMs: Long
        get() = ((customDurationSeconds ?: ConfigManager.config.gambling.spinDuration).coerceIn(1.0f, 10.0f) * 1000f).toLong()
    val reelStaggerMs = 800L

    var isFinished = false
    var isSkipped = false
    var celebrationStartTime = 0L

    val winningDrop: ItemStack
    val isJackpot: Boolean

    init {
        // Determine winning item
        val bestDrop = winningItemOverride ?: run {
            val entry = DungeonItemRegistry.getRandomItem(floor, chestType)
            DungeonItemRegistry.getItemStack(entry.id)
        }
        winningDrop = bestDrop
        isJackpot = DungeonItemRegistry.getItemValue(winningDrop) >= 8_000_000L ||
                winningDrop.hoverName.string.contains("Handle", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Scroll", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Sword", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Claymore", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Star", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Recombobulator", ignoreCase = true)

        buildReels()
    }

    private fun buildReels() {
        val pool = DungeonItemRegistry.getChestPool(floor, chestType)
        fun getRandomId(): String {
            if (pool.isEmpty()) return "item:recombobulator_3000"
            val totalWeight = pool.sumOf { it.weight }
            var r = ThreadLocalRandom.current().nextDouble() * totalWeight
            for (entry in pool) {
                r -= entry.weight
                if (r <= 0) return entry.id
            }
            return pool.last().id
        }

        reels.clear()
        for (i in 0..2) {
            val length = 200 + (i * 25)
            val reelList = MutableList(length) { getRandomId() }

            // Place the winning item at length - 2 on the payline!
            val winId = winningDrop.skyblockId.ifBlank {
                "item:${winningDrop.hoverName.string.lowercase().replace(" ", "_")}"
            }
            reelList[length - 2] = winId
            reels.add(reelList)
        }
    }

    fun skip() {
        if (isFinished) return
        isSkipped = true
        isFinished = true
        celebrationStartTime = System.currentTimeMillis()
        for (i in 0..2) {
            reelStopped[i] = true
        }
        playCelebrationSound()
    }

    private fun easeOutQuad(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return 1f - (1f - clamped) * (1f - clamped)
    }

    fun playSound(soundHolder: Holder<SoundEvent>, pitch: Float = 1.0f, volume: Float = 1.0f) {
        playSound(soundHolder.value(), pitch, volume)
    }

    fun playSound(sound: SoundEvent, pitch: Float = 1.0f, volume: Float = 1.0f) {
        if (!ConfigManager.config.gambling.playSounds) return
        try {
            mc.soundManager.play(SimpleSoundInstance.forUI(sound, pitch, volume))
        } catch (e: Exception) {}
    }

    private fun playCelebrationSound() {
        if (isJackpot) {
            playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f)
            playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 1.0f, 0.8f)
            playSound(SoundEvents.NOTE_BLOCK_PLING, 2.0f, 1.0f)
        } else {
            playSound(SoundEvents.PLAYER_LEVELUP, 1.5f, 0.8f)
            playSound(SoundEvents.NOTE_BLOCK_PLING, 1.5f, 0.7f)
        }
    }

    fun render(
        graphics: GuiGraphicsExtractor,
        font: Font,
        left: Int,
        top: Int,
        imageWidth: Int,
        imageHeight: Int,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float
    ) {
        val now = System.currentTimeMillis()
        val elapsedTime = (now - startTime).coerceAtLeast(0L)
        val animTick = (now / 120L).toInt()

        // 1. Draw 6-Row Chest Container Texture authentically in two passes (top rows + bottom inventory)
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE, left, top, 0f, 0f, imageWidth, 6 * 18 + 17, 256, 256)
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE, left, top + 6 * 18 + 17, 0f, 126.0f, imageWidth, 96, 256, 256)

        // 2. Chest Title
        val floorTag = floor.name
        val title = "§8${chestType.colorCode}${chestType.displayName} Chest §7- §6Slot Machine §8($floorTag)"
        graphics.text(font, title, left + 8, top + 6, 0x404040, false)

        var hoveredStack: ItemStack? = null

        // 3. Render Slots & Borders (54 slots = 6 rows x 9 cols)
        for (idx in 0..53) {
            val row = idx / 9
            val col = idx % 9
            val slotX = left + 8 + (col * 18)
            val slotY = top + 18 + (row * 18)

            // Check if this slot belongs to the 3 slot machine reels (Cols 3, 4, 5; Rows 1, 2, 3)
            val isReelSlot = col in 3..5 && row in 1..3

            if (!isReelSlot) {
                val staticItem = getStaticSlotItem(idx, animTick)
                if (staticItem != null && !staticItem.isEmpty) {
                    graphics.item(staticItem, slotX, slotY)
                    graphics.itemDecorations(font, staticItem, slotX, slotY)

                    if (mouseX in slotX..(slotX + 16) && mouseY in slotY..(slotY + 16)) {
                        graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0x80FFFFFF.toInt())
                        hoveredStack = staticItem
                    }
                }
            }
        }

        // 4. Render the 3 Scrolling Reels inside Scissor Box
        // Reel bounds: Left = col 3 (left + 8 + 3*18 = left + 62), Top = row 1 (top + 18 + 1*18 = top + 36)
        // Width = 3 * 18 = 54, Height = 3 * 18 = 54
        val scissorLeft = left + 8 + 3 * 18
        val scissorTop = top + 18 + 1 * 18
        val scissorWidth = 3 * 18
        val scissorHeight = 3 * 18

        val totalDuration = baseDurationMs + (2 * reelStaggerMs)

        if (!isFinished && !isSkipped && elapsedTime >= totalDuration + 400L) {
            isFinished = true
            celebrationStartTime = now
            playCelebrationSound()
        }

        // Enable scissor clipping for smooth reel scrolling
        graphics.enableScissor(scissorLeft, scissorTop, scissorLeft + scissorWidth, scissorTop + scissorHeight)
        try {
            for (reelIndex in 0..2) {
                renderReel(graphics, font, reelIndex, left, top, elapsedTime, mouseX, mouseY) { stack ->
                    hoveredStack = stack
                }
            }
        } finally {
            graphics.disableScissor()
        }

        // 5. Render Payline Highlight Frame across Row 2 (Slots 21, 22, 23)
        val paylineX = left + 8 + 3 * 18
        val paylineY = top + 18 + 2 * 18
        val paylineW = 3 * 18
        val paylineH = 18

        val paylineBorderColor = if (isFinished && isJackpot) {
            val pulse = ((sin(now / 150.0) + 1.0) * 0.5).toFloat()
            val r = (255 * (0.8f + pulse * 0.2f)).toInt()
            val g = (215 * (0.8f + pulse * 0.2f)).toInt()
            Color(r, g, 0, 220).rgb
        } else if (isFinished) {
            Color(85, 255, 85, 180).rgb
        } else {
            Color(255, 85, 85, 140).rgb
        }

        // Highlight payline border
        graphics.fill(paylineX, paylineY, paylineX + paylineW, paylineY + 1, paylineBorderColor)
        graphics.fill(paylineX, paylineY + paylineH - 1, paylineX + paylineW, paylineY + paylineH, paylineBorderColor)
        graphics.fill(paylineX, paylineY, paylineX + 1, paylineY + paylineH, paylineBorderColor)
        graphics.fill(paylineX + paylineW - 1, paylineY, paylineX + paylineW, paylineY + paylineH, paylineBorderColor)

        // 6. If finished, render floating victory banner
        if (isFinished) {
            renderVictoryBanner(graphics, font, left, top, imageWidth, imageHeight)
        }

        // 7. Render Hover Tooltip
        if (hoveredStack != null && !hoveredStack.isEmpty) {
            graphics.setTooltipForNextFrame(font, hoveredStack, mouseX, mouseY)
        }
    }

    private fun renderReel(
        graphics: GuiGraphicsExtractor,
        font: Font,
        reelIndex: Int,
        left: Int,
        top: Int,
        elapsedTime: Long,
        mouseX: Int,
        mouseY: Int,
        onHover: (ItemStack) -> Unit
    ) {
        val reelDuration = baseDurationMs + (reelIndex * reelStaggerMs)
        val reelX = left + 8 + (3 + reelIndex) * 18
        val slot = reels.getOrNull(reelIndex) ?: return

        val winTargetIndex = slot.size - 2
        val totalPixelDistance = winTargetIndex * SLOT_HEIGHT.toFloat()

        val progress = if (isSkipped || elapsedTime >= reelDuration) {
            if (!reelStopped[reelIndex]) {
                reelStopped[reelIndex] = true
                val pitch = 1.1f + (reelIndex * 0.25f)
                playSound(SoundEvents.CHEST_LOCKED, pitch, 0.9f)
            }
            1.0f
        } else {
            val raw = (elapsedTime.toFloat() / reelDuration.toFloat()).coerceIn(0f, 1f)
            easeOutQuad(raw)
        }

        val currentScrollDistance = totalPixelDistance * progress
        val centerIndex = (currentScrollDistance / SLOT_HEIGHT).toInt()

        // Sound ticks on item change while scrolling
        if (progress < 1.0f && centerIndex > lastScrollIndex[reelIndex]) {
            if (lastScrollIndex[reelIndex] != -1) {
                val tickPitch = 1.6f + (ThreadLocalRandom.current().nextFloat() * 0.4f)
                playSound(SoundEvents.UI_BUTTON_CLICK, tickPitch, 0.5f)
            }
            lastScrollIndex[reelIndex] = centerIndex
        }

        // Payline center is at Row 2: top + 18 + 2 * 18
        val paylineCenterY = top + 18 + 2 * 18

        for (idx in (centerIndex - 2)..(centerIndex + 2)) {
            if (idx !in slot.indices) continue
            val itemId = slot[idx]
            val stack = if (progress >= 1.0f && idx == winTargetIndex) {
                winningDrop
            } else {
                DungeonItemRegistry.getItemStack(itemId)
            }

            val itemY = paylineCenterY + ((idx * SLOT_HEIGHT) - currentScrollDistance).toInt()

            graphics.item(stack, reelX, itemY)
            graphics.itemDecorations(font, stack, reelX, itemY)

            if (mouseX in reelX..(reelX + 16) && mouseY in itemY..(itemY + 16)) {
                graphics.fill(reelX, itemY, reelX + 16, itemY + 16, 0x60FFFFFF)
                onHover(stack)
            }
        }
    }

    private fun renderVictoryBanner(
        graphics: GuiGraphicsExtractor,
        font: Font,
        left: Int,
        top: Int,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val bannerX = left + 8 + 18
        val bannerY = top + 18 + 5 * 18 + 1
        val bannerW = 7 * 18

        val now = System.currentTimeMillis()
        val pulse = ((sin(now / 100.0) + 1.0) * 0.5).toFloat()

        val dropName = DungeonItemRegistry.getDropDisplayName(winningDrop)
        val text = if (isJackpot) {
            "§6§l★ JACKPOT! ★ §d$dropName"
        } else {
            "§a§lREWARD: §f$dropName"
        }

        val textW = font.width(text)
        val textX = left + (imageWidth - textW) / 2
        val textY = top + 18 + 4 * 18 + 5

        // Background banner box behind text
        graphics.fill(textX - 4, textY - 2, textX + textW + 4, textY + 10, 0xCC000000.toInt())
        val borderCol = if (isJackpot) Color(255, 215, 0, (180 + pulse * 75).toInt()).rgb else Color(85, 255, 85, 200).rgb
        graphics.fill(textX - 4, textY - 2, textX + textW + 4, textY - 1, borderCol)
        graphics.fill(textX - 4, textY + 9, textX + textW + 4, textY + 10, borderCol)

        graphics.text(font, text, textX, textY, Color.WHITE.rgb, true)
    }

    private fun getStaticSlotItem(slot: Int, animTick: Int): ItemStack? {
        val row = slot / 9
        val col = slot % 9

        // Slot 49: Skip Animation Button (Bottom Row Center)
        if (slot == 49) {
            val skipStack = ItemStack(if (isFinished) Items.EMERALD else Items.REDSTONE_BLOCK)
            val name = if (isFinished) "§a§l✓ Animation Complete" else "§c§l⏩ SKIP ANIMATION"
            val lore = if (isFinished) {
                listOf(
                    "§7The slot machine has finished spinning!",
                    "§7Your rewards have been revealed.",
                    "",
                    "§eClick 'Open Reward Chest' to claim!"
                )
            } else {
                listOf(
                    "§7Click to instantly skip the spin",
                    "§7and reveal your rewards immediately!",
                    "",
                    "§8Shortcut: Press §f[SPACE] §8or §f[ESC]"
                )
            }
            skipStack.set(DataComponents.CUSTOM_NAME, Component.literal(name))
            skipStack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
            skipStack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            return skipStack
        }

        // Slot 45: Dungeon & Chest Information
        if (slot == 45) {
            val infoStack = ItemStack(Items.BOOK)
            infoStack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lDungeon Chest Info"))
            val lore = listOf(
                "§7Floor: §e${floor.displayName} §8(${floor.name})",
                "§7Chest Type: ${chestType.colorCode}${chestType.displayName} Chest",
                "§7Mode: §f${if (floor.isMasterMode) "Master Mode" else "Normal"}",
                "",
                "§8NoemtAddons Slot Machine"
            )
            infoStack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
            return infoStack
        }

        // Slot 53: Jackpot & Odds Information
        if (slot == 53) {
            val starStack = ItemStack(Items.NETHER_STAR)
            starStack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lJackpot Rewards"))
            val lore = listOf(
                "§7Match §e3 identical items§7 on the",
                "§7center payline to hit the jackpot!",
                "",
                "§7Top possible drops:",
                "§d • Necron's Handle",
                "§5 • Wither / Shadow Scrolls",
                "§6 • Giant's Sword / Master Stars",
                "§6 • Recombobulator 3000"
            )
            starStack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
            starStack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            return starStack
        }

        // Payline pointers on Row 2: Slot 20 (Left) and Slot 24 (Right)
        if (slot == 20) {
            val arrow = ItemStack(Items.SPECTRAL_ARROW)
            arrow.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lPAYLINE ▶"))
            arrow.set(DataComponents.LORE, ItemLore(listOf(Component.literal("§7Match 3 items along this center line!"))))
            return arrow
        }
        if (slot == 24) {
            val arrow = ItemStack(Items.SPECTRAL_ARROW)
            arrow.set(DataComponents.CUSTOM_NAME, Component.literal("§e§l◀ PAYLINE"))
            arrow.set(DataComponents.LORE, ItemLore(listOf(Component.literal("§7Match 3 items along this center line!"))))
            return arrow
        }

        // Casino Glass Panes around the frame
        return getCasinoGlassPane(slot, animTick)
    }

    private fun getCasinoGlassPane(slot: Int, animTick: Int): ItemStack {
        val panes = if (isFinished && isJackpot) {
            listOf(
                Items.YELLOW_STAINED_GLASS_PANE,
                Items.ORANGE_STAINED_GLASS_PANE,
                Items.WHITE_STAINED_GLASS_PANE,
                Items.LIGHT_BLUE_STAINED_GLASS_PANE
            )
        } else if (isFinished) {
            listOf(
                Items.LIME_STAINED_GLASS_PANE,
                Items.GREEN_STAINED_GLASS_PANE,
                Items.CYAN_STAINED_GLASS_PANE
            )
        } else {
            listOf(
                Items.YELLOW_STAINED_GLASS_PANE,
                Items.ORANGE_STAINED_GLASS_PANE,
                Items.LIME_STAINED_GLASS_PANE,
                Items.CYAN_STAINED_GLASS_PANE,
                Items.PURPLE_STAINED_GLASS_PANE,
                Items.MAGENTA_STAINED_GLASS_PANE,
                Items.PINK_STAINED_GLASS_PANE,
                Items.LIGHT_BLUE_STAINED_GLASS_PANE
            )
        }

        val offset = (slot * 3 + animTick) % panes.size
        val paneItem = panes[offset]
        val stack = ItemStack(paneItem)
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7"))
        return stack
    }

    fun handleSlotClick(slot: Int): Boolean {
        if (slot == 49) {
            if (!isFinished) {
                skip()
                playSound(SoundEvents.UI_BUTTON_CLICK, 1.2f, 1.0f)
            }
            return true
        }
        return false
    }
}
