package dev.noemt.client.features.gambling.dungeons

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.features.gambling.chest.DungeonChestType
import dev.noemt.client.utils.ItemUtils.lore
import dev.noemt.client.utils.ItemUtils.skyblockId
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.Holder
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.pow
import kotlin.math.sqrt

class DungeonSlotMachineEngine(
    val floor: DungeonFloor,
    val chestType: DungeonChestType,
    val winningItemOverride: ItemStack? = null,
    val customDurationSeconds: Float? = null
) {
    private val mc: Minecraft get() = Minecraft.getInstance()

    companion object {
        const val ITEM_SIZE = 50
        const val WINNER_INDEX = ITEM_SIZE - 10 // 40
        const val CARD_WIDTH = 96
        const val CARD_HEIGHT = 72
        const val ITEM_GAP = 6
        const val FULL_CARD_WIDTH = CARD_WIDTH + ITEM_GAP // 102
    }

    val items = mutableListOf<ItemStack>()
    val winningDrop: ItemStack
    val isJackpot: Boolean

    var startTime = System.currentTimeMillis() + 80L
    val durationMs: Long
        get() = ((customDurationSeconds ?: ConfigManager.config.gambling.spinDuration).coerceIn(1.0f, 10.0f) * 1000f).toLong()

    private var randomOffset = 0
    private var lastSoundIndex = 0
    private var celebrationPlayed = false

    var isFinished = false
    var isSkipped = false
    var celebrationStartTime = 0L

    init {
        // 1. Determine Winner
        winningDrop = winningItemOverride ?: run {
            val entry = DungeonItemRegistry.getRandomItem(floor, chestType)
            DungeonItemRegistry.getItemStack(entry.id)
        }

        val dropVal = DungeonItemRegistry.getItemValue(winningDrop)
        isJackpot = dropVal >= 8_000_000L ||
                winningDrop.hoverName.string.contains("Handle", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Scroll", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Sword", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Claymore", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Star", ignoreCase = true) ||
                winningDrop.hoverName.string.contains("Recombobulator", ignoreCase = true)

        randomOffset = ThreadLocalRandom.current().nextInt(-14, 15)

        // 2. Generate 50 items for the horizontal CS:GO-style card reel
        val pool = DungeonItemRegistry.getChestPool(floor, chestType)
        fun getRandomStack(): ItemStack {
            if (pool.isEmpty()) return DungeonItemRegistry.getItemStack("item:recombobulator_3000")
            val totalWeight = pool.sumOf { it.weight }
            var r = ThreadLocalRandom.current().nextDouble() * totalWeight
            for (entry in pool) {
                r -= entry.weight
                if (r <= 0) return DungeonItemRegistry.getItemStack(entry.id)
            }
            return DungeonItemRegistry.getItemStack(pool.last().id)
        }

        items.clear()
        repeat(ITEM_SIZE) {
            items.add(getRandomStack())
        }
        items[WINNER_INDEX] = winningDrop
    }

    private fun ease(t: Float): Float {
        return if (t < 0.5f) {
            (1f - sqrt(1f - (2f * t).pow(2f))) / 2f
        } else {
            (sqrt(1f - (-2f * t + 2f).pow(2f)) + 1f) / 2f
        }
    }

    fun skip() {
        if (isFinished) return
        isSkipped = true
        isFinished = true
        celebrationStartTime = System.currentTimeMillis()
        playCelebrationSound()
    }

    fun playSound(soundHolder: Holder<SoundEvent>, pitch: Float = 1.0f, volume: Float = 1.0f) {
        playSound(soundHolder.value(), pitch, volume)
    }

    fun playSound(sound: SoundEvent, pitch: Float = 1.0f, volume: Float = 1.0f) {
        if (!ConfigManager.config.gambling.playSounds) return
        try {
            mc.soundManager.play(SimpleSoundInstance.forUI(sound, pitch, volume))
        } catch (_: Exception) {}
    }

    private fun playCelebrationSound() {
        if (celebrationPlayed) return
        celebrationPlayed = true
        if (isJackpot) {
            playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f)
            playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 1.0f, 0.8f)
            playSound(SoundEvents.NOTE_BLOCK_PLING, 2.0f, 1.0f)
        } else {
            playSound(SoundEvents.PLAYER_LEVELUP, 1.4f, 0.8f)
            playSound(SoundEvents.NOTE_BLOCK_PLING, 1.8f, 0.8f)
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
        val screenW = graphics.guiWidth()
        val screenH = graphics.guiHeight()
        val now = System.currentTimeMillis()
        val elapsed = (now - startTime).coerceAtLeast(0L)

        // 1. Dark Backdrop Overlay
        graphics.fill(0, 0, screenW, screenH, 0xCC0A0A0E.toInt())

        // Calculate ease progress
        val rawProgress = if (isSkipped) 1.0f else (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val progress = if (isSkipped) 1.0f else (rawProgress + 0.25f).coerceIn(0f, 1f)
        val endOffset = (WINNER_INDEX * FULL_CARD_WIDTH) * ease(progress)

        if (rawProgress >= 1.0f && !isFinished) {
            isFinished = true
            celebrationStartTime = now
            playCelebrationSound()
        }

        // 2. Sound ticker
        val soundIndex = endOffset.toInt() / FULL_CARD_WIDTH
        if (soundIndex > lastSoundIndex && !isFinished) {
            playSound(SoundEvents.ITEM_PICKUP, pitch = 2.0f, volume = 0.7f)
            lastSoundIndex = soundIndex
        }

        val centerY = (screenH - CARD_HEIGHT) / 2
        val startX = (screenW / 2) - endOffset - (CARD_WIDTH / 2) - randomOffset

        // 3. Header Banner
        val headerTitle = "§8[ §b§lNoemtAddons §8] §6§l${chestType.displayName} Chest §7- §eSlot Machine §8(${floor.name})"
        val headerWidth = font.width(headerTitle)
        graphics.text(font, headerTitle, (screenW - headerWidth) / 2, centerY - 60, 0xFFFFFF, true)

        val hint = "§7Press §e[SPACE] §7or §e[ESC] §7to skip"
        val hintWidth = font.width(hint)
        graphics.text(font, hint, (screenW - hintWidth) / 2, centerY + CARD_HEIGHT + 24, 0xAAAAAA, true)

        // 4. Render Horizontal Cards
        for (idx in items.indices) {
            val cardX = (startX + (idx * FULL_CARD_WIDTH)).toInt()
            if (cardX + CARD_WIDTH < -50 || cardX > screenW + 50) continue

            val item = items[idx]
            val rarityColor = getRarityColor(item)

            // Card background & border
            graphics.fill(cardX, centerY, cardX + CARD_WIDTH, centerY + CARD_HEIGHT, 0xEE1E1E24.toInt())
            graphics.fill(cardX, centerY, cardX + CARD_WIDTH, centerY + 1, 0x44FFFFFF.toInt())
            graphics.fill(cardX, centerY, cardX + 1, centerY + CARD_HEIGHT, 0x22FFFFFF.toInt())
            graphics.fill(cardX + CARD_WIDTH - 1, centerY, cardX + CARD_WIDTH, centerY + CARD_HEIGHT, 0x22FFFFFF.toInt())

            // Bottom Rarity Line
            graphics.fill(cardX, centerY + CARD_HEIGHT - 3, cardX + CARD_WIDTH, centerY + CARD_HEIGHT, rarityColor)

            // Scaled Item in Card Center
            if (!item.isEmpty) {
                graphics.pose().pushMatrix()
                graphics.pose().translate((cardX + (CARD_WIDTH / 2f)).toFloat(), (centerY + (CARD_HEIGHT / 2f) - 2f).toFloat())
                graphics.pose().scale(2.5f, 2.5f)
                graphics.item(item, -8, -8)
                graphics.pose().popMatrix()
            }
        }

        // 5. Central Red Payline Indicator (Top & Bottom Pointers)
        val midX = screenW / 2
        // Top pointer
        graphics.fill(midX - 2, centerY - 12, midX + 2, centerY - 2, 0xFFFF3333.toInt())
        graphics.fill(midX - 1, centerY - 2, midX + 1, centerY, 0xFFFF3333.toInt())
        // Bottom pointer
        graphics.fill(midX - 2, centerY + CARD_HEIGHT + 2, midX + 2, centerY + CARD_HEIGHT + 12, 0xFFFF3333.toInt())
        graphics.fill(midX - 1, centerY + CARD_HEIGHT, midX + 1, centerY + CARD_HEIGHT + 2, 0xFFFF3333.toInt())

        // 6. Winner Reveal & Celebration
        if (progress >= 0.96f || isFinished) {
            val winnerName = DungeonItemRegistry.getDropDisplayName(winningDrop)
            val dropVal = SkyblockPriceService.getItemValue(winningDrop)
            val profitText = if (dropVal > 0L) " §7(§a+${formatCoins(dropVal)} Coins§7)" else ""

            val titleText = if (isJackpot) "§6§l★ ★ ★ JACKPOT ★ ★ ★" else "§e§l★ WINNER ★"
            val titleWidth = font.width(titleText)
            graphics.text(font, titleText, (screenW - titleWidth) / 2, centerY - 40, 0xFFFFAA00.toInt(), true)

            val fullWinner = "$winnerName$profitText"
            val fullWinnerWidth = font.width(fullWinner)
            graphics.text(font, fullWinner, (screenW - fullWinnerWidth) / 2, centerY - 24, 0xFFFFFFFF.toInt(), true)
        }
    }

    private fun getRarityColor(stack: ItemStack): Int {
        if (stack.isEmpty) return 0xFF555555.toInt()
        val name = stack.hoverName.string.lowercase()
        val lore = stack.lore.joinToString(" ").lowercase()
        return when {
            lore.contains("divine") || name.contains("divine") -> 0xFF55FFFF.toInt()
            lore.contains("special") || name.contains("special") -> 0xFFFF5555.toInt()
            lore.contains("mythic") || name.contains("necron's handle") || name.contains("claymore") -> 0xFFFF55FF.toInt()
            lore.contains("legendary") || name.contains("scroll") || name.contains("recombobulator") || name.contains("giant's sword") -> 0xFFFFAA00.toInt()
            lore.contains("epic") || name.contains("shadow assassin") || name.contains("livid dagger") -> 0xFFAA00AA.toInt()
            lore.contains("rare") -> 0xFF5555FF.toInt()
            lore.contains("uncommon") -> 0xFF55FF55.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    }

    private fun formatCoins(amount: Long): String {
        return NumberFormat.getNumberInstance(Locale.US).format(amount)
    }
}
