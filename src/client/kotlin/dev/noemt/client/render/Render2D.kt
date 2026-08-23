package dev.noemt.client.render

import dev.noemt.client.render.RenderHelper.width
import dev.noemt.client.utils.ChatUtils.addColor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.awt.Color
import kotlin.math.atan2
import kotlin.math.sqrt

object Render2D {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun GuiGraphicsExtractor.drawTexture(texture: Identifier, x: Number, y: Number, width: Number, height: Number, color: Color = Color.WHITE) {
        blit(RenderPipelines.GUI_TEXTURED, texture, x.toInt(), y.toInt(), 0f, 0f, width.toInt(), height.toInt(), width.toInt(), height.toInt(), color.rgb)
    }

    fun GuiGraphicsExtractor.drawRect(x: Number, y: Number, width: Number, height: Number, color: Color = Color.WHITE) {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fw = width.toFloat()
        val fh = height.toFloat()

        pose().pushMatrix()
        pose().translate(fx, fy)
        pose().scale(fw, fh)
        fill(0, 0, 1, 1, color.rgb)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.drawBorder(x: Number, y: Number, width: Number, height: Number, color: Color = Color.WHITE, thickness: Number = 1) {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fw = width.toFloat()
        val fh = height.toFloat()
        val ft = thickness.toFloat()

        drawRect(fx, fy, fw, ft, color)
        drawRect(fx, fy + fh - ft, fw, ft, color)
        drawRect(fx, fy + ft, ft, fh - ft * 2f, color)
        drawRect(fx + fw - ft, fy + ft, ft, fh - ft * 2f, color)
    }

    fun GuiGraphicsExtractor.drawString(str: String, x: Number, y: Number, color: Color = Color.WHITE, scale: Number = 1, shadow: Boolean = true) {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fScale = scale.toFloat()

        pose().pushMatrix()
        pose().translate(fx, fy)
        if (fScale != 1f) pose().scale(fScale, fScale)
        text(mc.font, str.addColor(), 0, 0, color.rgb, shadow)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.drawCenteredString(str: String, x: Number, y: Number, color: Color = Color.WHITE, scale: Number = 1, shadow: Boolean = true) {
        val fScale = scale.toFloat()
        val totalScaledWidth = str.width() * fScale
        val centerX = x.toFloat() - (totalScaledWidth / 2f)
        drawString(str, centerX, y, color, scale, shadow)
    }

    fun GuiGraphicsExtractor.drawPlayerHead(x: Int, y: Int, size: Int, skin: Identifier) {
        blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 8f, 8f, size, size, 8, 8, 64, 64, -1)
        blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 40f, 8f, size, size, 8, 8, 64, 64, -1)
    }
}
