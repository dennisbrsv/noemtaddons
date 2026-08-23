package dev.noemt.client.render

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import dev.noemt.Noemtaddons
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.Optional

object NoemtRenderPipelines {
    val FILLED = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
            withLocation(id("pipeline/filled"))
            withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        }.build()
    )

    val LINES_THROUGH_WALLS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET).apply {
            withLocation(id("pipeline/lines_through_walls"))
            withDepthStencilState(Optional.empty())
        }.build()
    )

    val LINES = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET).apply {
            withLocation(id("pipeline/lines"))
        }.build()
    )

    val FILLED_THROUGH_WALLS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
            withLocation(id("pipeline/filled_through_walls"))
            withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            withDepthStencilState(Optional.empty())
        }.build()
    )

    private fun id(path: String) = Identifier.fromNamespaceAndPath(Noemtaddons.MOD_ID, path)
}
