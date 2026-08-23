package dev.noemt.client.render

import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType

object NoemtRenderLayers {
    val FILLED = RenderType.create("noemt_filled", RenderSetup.builder(NoemtRenderPipelines.FILLED).createRenderSetup())
    val FILLED_THROUGH_WALLS = RenderType.create("noemt_filled_through_walls", RenderSetup.builder(NoemtRenderPipelines.FILLED_THROUGH_WALLS).createRenderSetup())
    val LINES = RenderType.create("noemt_lines", RenderSetup.builder(NoemtRenderPipelines.LINES).createRenderSetup())
    val LINES_THROUGH_WALLS = RenderType.create("noemt_lines_through_walls", RenderSetup.builder(NoemtRenderPipelines.LINES_THROUGH_WALLS).createRenderSetup())
}
