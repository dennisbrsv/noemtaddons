package dev.noemt.client.ui.core

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.awt.Color

object GuiTheme {
    val BG_OVERLAY = Color(0, 0, 0, 195).rgb
    val CARD_BG = Color(16, 20, 30, 245).rgb
    val CARD_SURFACE = Color(22, 28, 42, 200).rgb
    val CARD_SURFACE_ACTIVE = Color(32, 46, 72, 220).rgb
    val CARD_SURFACE_HOVER = Color(36, 44, 62, 220).rgb

    val BORDER_MAIN = Color(0, 195, 255, 230).rgb
    val BORDER_MUTED = Color(50, 65, 90, 200).rgb
    val BORDER_ACCENT = Color(80, 140, 220, 240).rgb

    val TEXT_WHITE = Color.WHITE.rgb
    val TEXT_MUTED = Color(175, 185, 200).rgb
    val TEXT_DIM = Color(115, 125, 140).rgb

    val COLOR_PRIMARY = Color(0, 200, 255).rgb
    val COLOR_SUCCESS = Color(80, 225, 125).rgb
    val COLOR_WARNING = Color(255, 205, 75).rgb
    val COLOR_DANGER = Color(255, 90, 95).rgb
    val COLOR_ACCENT = Color(140, 120, 255).rgb
}

object GuiRenderer {
    fun drawCard(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        bgColor: Int = GuiTheme.CARD_BG,
        borderColor: Int = GuiTheme.BORDER_MAIN,
        borderWidth: Int = 1
    ) {
        // Fill Card Body
        graphics.fill(x, y, x + width, y + height, bgColor)

        if (borderWidth > 0) {
            // Top, Bottom, Left, Right borders
            graphics.fill(x, y, x + width, y + borderWidth, borderColor)
            graphics.fill(x, y + height - borderWidth, x + width, y + height, borderColor)
            graphics.fill(x, y, x + borderWidth, y + height, borderColor)
            graphics.fill(x + width - borderWidth, y, x + width, y + height, borderColor)
        }
    }

    fun drawHeader(
        graphics: GuiGraphicsExtractor,
        font: Font,
        title: String,
        subtitle: String?,
        x: Int,
        y: Int,
        width: Int
    ) {
        graphics.text(font, title, x, y, GuiTheme.TEXT_WHITE, true)
        if (subtitle != null) {
            graphics.text(font, subtitle, x, y + 12, GuiTheme.TEXT_MUTED, false)
        }
        graphics.fill(x, y + 26, x + width, y + 27, GuiTheme.BORDER_MUTED)
    }

    fun drawBadge(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Int,
        y: Int,
        bgColor: Int = GuiTheme.CARD_SURFACE_ACTIVE,
        textColor: Int = GuiTheme.TEXT_WHITE
    ): Int {
        val textWidth = font.width(text)
        val padX = 5
        val badgeHeight = 12

        graphics.fill(x, y, x + textWidth + (padX * 2), y + badgeHeight, bgColor)
        graphics.text(font, text, x + padX, y + 2, textColor, true)

        return textWidth + (padX * 2)
    }

    fun drawPill(
        graphics: GuiGraphicsExtractor,
        font: Font,
        label: String,
        x: Int,
        y: Int,
        isActive: Boolean,
        activeColor: Int = GuiTheme.COLOR_SUCCESS,
        inactiveColor: Int = GuiTheme.COLOR_DANGER
    ): Int {
        val text = if (isActive) "● $label" else "○ $label"
        val col = if (isActive) activeColor else inactiveColor
        val bg = if (isActive) Color(30, 70, 50, 180).rgb else Color(60, 30, 35, 180).rgb

        val textWidth = font.width(text)
        val pad = 6
        val h = 14

        graphics.fill(x, y, x + textWidth + (pad * 2), y + h, bg)
        graphics.text(font, text, x + pad, y + 3, col, true)

        return textWidth + (pad * 2)
    }

    fun drawStatusDot(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        radius: Int = 3,
        color: Int = GuiTheme.COLOR_SUCCESS,
        isPulsing: Boolean = false
    ) {
        val glow = if (isPulsing) {
            val pulse = (System.currentTimeMillis() % 1500) / 1500.0
            val alpha = (120 + (135 * Math.sin(pulse * Math.PI))).toInt().coerceIn(0, 255)
            Color((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, alpha).rgb
        } else {
            color
        }

        graphics.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, glow)
    }

    fun drawTooltip(
        graphics: GuiGraphicsExtractor,
        font: Font,
        lines: List<String>,
        mouseX: Int,
        mouseY: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (lines.isEmpty()) return

        var maxLineWidth = 0
        for (line in lines) {
            val w = font.width(line)
            if (w > maxLineWidth) maxLineWidth = w
        }

        val boxWidth = maxLineWidth + 12
        val boxHeight = (lines.size * (font.lineHeight + 2)) + 8

        var tipX = mouseX + 12
        var tipY = mouseY + 12

        if (tipX + boxWidth > screenWidth) {
            tipX = mouseX - boxWidth - 4
        }
        if (tipY + boxHeight > screenHeight) {
            tipY = screenHeight - boxHeight - 4
        }

        // Draw Tooltip Card
        drawCard(
            graphics,
            tipX,
            tipY,
            boxWidth,
            boxHeight,
            bgColor = Color(12, 14, 22, 245).rgb,
            borderColor = Color(0, 195, 255, 180).rgb,
            borderWidth = 1
        )

        var textY = tipY + 4
        for (line in lines) {
            graphics.text(font, line, tipX + 6, textY, GuiTheme.TEXT_WHITE, true)
            textY += font.lineHeight + 2
        }
    }
}
