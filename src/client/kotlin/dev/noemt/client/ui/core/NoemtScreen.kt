package dev.noemt.client.ui.core

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.Color

abstract class NoemtScreen(
    title: Component,
    private val windowTitle: String = "NoemtAddons",
    private val windowSubtitle: String? = null,
    private val widthRatio: Float = 0.90f,
    private val heightRatio: Float = 0.85f,
    private val minWidth: Float = 500f,
    private val maxWidth: Float = 860f,
    private val minHeight: Float = 320f,
    private val maxHeight: Float = 580f
) : Screen(title) {

    var windowX: Int = 0
        protected set
    var windowY: Int = 0
        protected set
    var windowWidth: Int = 0
        protected set
    var windowHeight: Int = 0
        protected set

    private val queuedTooltips = mutableListOf<String>()

    override fun init() {
        clearWidgets()
        queuedTooltips.clear()

        windowWidth = (width * widthRatio).coerceIn(minWidth, maxWidth).toInt()
        windowHeight = (height * heightRatio).coerceIn(minHeight, maxHeight).toInt()
        windowX = (width - windowWidth) / 2
        windowY = (height - windowHeight) / 2

        // Standard Top Right Close Button
        addRenderableWidget(
            Button.builder(Component.literal("§c✕ Close")) {
                onClose()
            }.bounds(windowX + windowWidth - 72, windowY + 8, 62, 20).build()
        )

        buildUi()
    }

    abstract fun buildUi()

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        queuedTooltips.clear()

        // 1. Dark Screen Backdrop
        graphics.fill(0, 0, width, height, GuiTheme.BG_OVERLAY)

        // 2. Main Window Box
        GuiRenderer.drawCard(
            graphics,
            windowX,
            windowY,
            windowWidth,
            windowHeight,
            bgColor = GuiTheme.CARD_BG,
            borderColor = GuiTheme.BORDER_MAIN,
            borderWidth = 1
        )

        // 3. Header & Subtitle
        GuiRenderer.drawHeader(
            graphics,
            font,
            windowTitle,
            windowSubtitle,
            windowX + 16,
            windowY + 10,
            windowWidth - 32
        )

        // 4. Render Child Components & Custom Content
        renderWindow(graphics, mouseX, mouseY, partialTick)

        // 5. Standard Widget Render (Buttons, EditBoxes)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        // 6. Draw Queued Tooltips on Top
        if (queuedTooltips.isNotEmpty()) {
            GuiRenderer.drawTooltip(
                graphics,
                font,
                queuedTooltips,
                mouseX,
                mouseY,
                width,
                height
            )
        }
    }

    open fun renderWindow(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Override to add custom canvas drawing
    }

    fun tooltip(vararg lines: String) {
        queuedTooltips.addAll(lines)
    }

    fun tooltip(lines: List<String>) {
        queuedTooltips.addAll(lines)
    }

    override fun isPauseScreen(): Boolean = false
}
