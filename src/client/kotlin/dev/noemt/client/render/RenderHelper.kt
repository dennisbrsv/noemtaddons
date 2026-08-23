package dev.noemt.client.render

import dev.noemt.client.utils.ChatUtils.addColor
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

object RenderHelper {
    private val mc: Minecraft get() = Minecraft.getInstance()

    val partialTicks: Float
        get() = mc.deltaTracker.getGameTimeDeltaPartialTick(true)

    val Entity.renderX: Double get() = xo + (x - xo) * partialTicks
    val Entity.renderY: Double get() = yo + (y - yo) * partialTicks
    val Entity.renderZ: Double get() = zo + (z - zo) * partialTicks

    val Entity.renderVec: Vec3 get() = Vec3(renderX, renderY, renderZ)

    val Entity.renderBoundingBox get() = boundingBox.move(renderX - x, renderY - y, renderZ - z)

    fun String.width(): Int = addColor().lineSequence().maxOf(mc.font::width)
    fun String.height(): Int = mc.font.lineHeight * (count { it == '\n' } + 1)
}
