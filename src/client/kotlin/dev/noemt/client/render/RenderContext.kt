package dev.noemt.client.render

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector

class RenderContext(val matrixStack: PoseStack, val consumers: SubmitNodeCollector, val camera: Camera) {
    companion object {
        fun fromContext(ctx: LevelRenderContext): RenderContext {
            val mc = Minecraft.getInstance()
            return RenderContext(ctx.poseStack(), ctx.submitNodeCollector(), mc.gameRenderer.mainCamera())
        }
    }
}
