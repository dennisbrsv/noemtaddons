package dev.noemt.client.features.gambling.dungeons

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.features.gambling.chest.DungeonChestType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.awt.Color

class DungeonSlotMachineScreen(
    val floor: DungeonFloor = DungeonFloor.M7,
    val chestType: DungeonChestType = DungeonChestType.BEDROCK,
    val initialWinner: ItemStack? = null
) : Screen(Component.literal("Dungeon Slot Machine")) {

    private val mc: Minecraft get() = Minecraft.getInstance()
    private val imageWidth = 176
    private val imageHeight = 222

    private var engine: DungeonSlotMachineEngine = DungeonSlotMachineEngine(floor, chestType, initialWinner)

    override fun init() {
        super.init()
    }

    fun restart() {
        engine = DungeonSlotMachineEngine(floor, chestType, initialWinner)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Dark backdrop
        graphics.fill(0, 0, width, height, 0xCC101010.toInt())

        val left = (width - imageWidth) / 2
        val top = (height - imageHeight) / 2

        // Render chest slot machine
        engine.render(graphics, font, left, top, imageWidth, imageHeight, mouseX, mouseY, partialTick)

        // Bottom control hints
        val hint = "§e§l[R] §7Re-Spin  §c§l[SPACE] §7Skip Animation  §f§l[ESC] §7Close"
        val hintW = font.width(hint)
        val hintX = (width - hintW) / 2
        val hintY = top + imageHeight + 8
        if (hintY + 12 <= height) {
            graphics.fill(hintX - 6, hintY - 3, hintX + hintW + 6, hintY + 11, 0xAA000000.toInt())
            graphics.text(font, hint, hintX, hintY, Color.WHITE.rgb, true)
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val left = (width - imageWidth) / 2
        val top = (height - imageHeight) / 2
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()

        for (idx in 0..53) {
            val row = idx / 9
            val col = idx % 9
            val slotX = left + 8 + (col * 18)
            val slotY = top + 18 + (row * 18)

            if (mouseX in slotX..(slotX + 16) && mouseY in slotY..(slotY + 16)) {
                if (engine.handleSlotClick(idx)) {
                    return true
                }
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            InputConstants.KEY_R -> {
                restart()
                return true
            }
            InputConstants.KEY_SPACE -> {
                if (!engine.isFinished) {
                    engine.skip()
                    return true
                }
            }
        }
        return super.keyPressed(event)
    }

    override fun isPauseScreen(): Boolean = false
}
