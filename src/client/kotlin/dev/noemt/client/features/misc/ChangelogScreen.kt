package dev.noemt.client.features.misc

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.Color

class ChangelogScreen(private val content: String) : Screen(Component.literal("NoemtAddons Changelog")) {
    private var scrollOffset = 0

    override fun init() {
        val btnWidth = 140
        val btnHeight = 20
        val btnX = (width - btnWidth) / 2
        val btnY = (height + (height * 0.75f).toInt()) / 2 - 25

        addRenderableWidget(
            Button.builder(Component.literal("§aGot it! / Close")) {
                onClose()
            }.bounds(btnX, btnY.coerceAtMost(height - 28), btnWidth, btnHeight).build()
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Dark background overlay
        graphics.fill(0, 0, width, height, Color(0, 0, 0, 190).rgb)

        // Card dimensions
        val cardWidth = (width * 0.8f).coerceIn(320f, 620f).toInt()
        val cardHeight = (height * 0.75f).coerceIn(200f, 460f).toInt()
        val cardX = (width - cardWidth) / 2
        val cardY = (height - cardHeight) / 2 - 12

        // Card body & border
        val borderColor = Color(0, 210, 255, 220).rgb
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, Color(18, 22, 34, 240).rgb)
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + 1, borderColor)
        graphics.fill(cardX, cardY + cardHeight - 1, cardX + cardWidth, cardY + cardHeight, borderColor)
        graphics.fill(cardX, cardY, cardX + 1, cardY + cardHeight, borderColor)
        graphics.fill(cardX + cardWidth - 1, cardY, cardX + cardWidth, cardY + cardHeight, borderColor)

        // Title Header
        val titleText = "§b§lNoemtAddons Update & Changelog"
        graphics.centeredText(font, titleText, width / 2, cardY + 12, Color.WHITE.rgb)

        // Header separator
        graphics.fill(cardX + 15, cardY + 28, cardX + cardWidth - 15, cardY + 29, Color(60, 75, 105, 255).rgb)

        // Scrollable changelog text lines
        val lines = content.lineSequence().toList()
        var yPos = cardY + 36 - scrollOffset
        val bottomCutoff = cardY + cardHeight - 35

        for (line in lines) {
            if (yPos in (cardY + 30)..(bottomCutoff - 10)) {
                graphics.text(font, line, cardX + 22, yPos, Color.WHITE.rgb, true)
            }
            yPos += font.lineHeight + 3
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY > 0) {
            scrollOffset = (scrollOffset - 18).coerceAtLeast(0)
            return true
        } else if (scrollY < 0) {
            scrollOffset = (scrollOffset + 18).coerceAtMost(400)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun isPauseScreen(): Boolean = false
}
