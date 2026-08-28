package dev.noemt.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.noemt.client.utils.ChatUtils.addColor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import org.joml.Vector3f
import java.awt.Color

object Render3D {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun RenderContext.renderBlock(
        pos: BlockPos,
        outlineColor: Color,
        fillColor: Color = outlineColor,
        outline: Boolean = true,
        fill: Boolean = true,
        phase: Boolean = false,
        lineWidth: Number = 2.5
    ) {
        if (!outline && !fill) return

        val level = mc.level ?: return
        val state = level.getBlockState(pos)
        val shape = if (state.block != Blocks.AIR) state.getShape(level, pos) else Shapes.block()

        val outlineR = outlineColor.red / 255f
        val outlineG = outlineColor.green / 255f
        val outlineB = outlineColor.blue / 255f

        val fillR = fillColor.red / 255f
        val fillG = fillColor.green / 255f
        val fillB = fillColor.blue / 255f
        val fillA = fillColor.alpha / 255f

        val minX = pos.x + shape.min(Direction.Axis.X)
        val minY = pos.y + shape.min(Direction.Axis.Y)
        val minZ = pos.z + shape.min(Direction.Axis.Z)
        val maxX = pos.x + shape.max(Direction.Axis.X)
        val maxY = pos.y + shape.max(Direction.Axis.Y)
        val maxZ = pos.z + shape.max(Direction.Axis.Z)

        matrixStack.pushPose()
        matrixStack.translate(camera.position().reverse())

        if (fill) consumers.order(0).submitCustomGeometry(matrixStack, if (phase) NoemtRenderLayers.FILLED_THROUGH_WALLS else NoemtRenderLayers.FILLED) { pose, buffer ->
            buffer.addFilledBoxVertices(pose, minX, minY, minZ, maxX, maxY, maxZ, fillR, fillG, fillB, fillA)
        }

        if (outline) consumers.order(0).submitCustomGeometry(matrixStack, if (phase) NoemtRenderLayers.LINES_THROUGH_WALLS else NoemtRenderLayers.LINES) { pose, buffer ->
            buffer.addLineBoxVertices(pose, minX, minY, minZ, maxX, maxY, maxZ, outlineR, outlineG, outlineB, 1f, lineWidth.toFloat())
        }

        matrixStack.popPose()
    }

    fun RenderContext.renderBox(
        x: Number,
        y: Number,
        z: Number,
        width: Number,
        height: Number,
        outlineColor: Color,
        fillColor: Color = outlineColor,
        outline: Boolean = true,
        fill: Boolean = true,
        phase: Boolean = false,
        lineWidth: Number = 2.5
    ) {
        if (!outline && !fill) return
        val cam = camera.position().reverse()

        val xd = x.toDouble()
        val yd = y.toDouble()
        val zd = z.toDouble()
        val hw = width.toDouble() / 2.0
        val hd = height.toDouble()

        matrixStack.pushPose()
        matrixStack.translate(cam.x, cam.y, cam.z)

        if (fill) consumers.order(0).submitCustomGeometry(matrixStack, if (phase) NoemtRenderLayers.FILLED_THROUGH_WALLS else NoemtRenderLayers.FILLED) { pose, buffer ->
            buffer.addFilledBoxVertices(pose, xd - hw, yd, zd - hw, xd + hw, yd + hd, zd + hw, fillColor.red / 255f, fillColor.green / 255f, fillColor.blue / 255f, fillColor.alpha / 255f)
        }

        if (outline) consumers.order(0).submitCustomGeometry(matrixStack, if (phase) NoemtRenderLayers.LINES_THROUGH_WALLS else NoemtRenderLayers.LINES) { pose, buffer ->
            buffer.addLineBoxVertices(pose, xd - hw, yd, zd - hw, xd + hw, yd + hd, zd + hw, outlineColor.red / 255f, outlineColor.green / 255f, outlineColor.blue / 255f, 1f, lineWidth.toFloat())
        }

        matrixStack.popPose()
    }

    fun RenderContext.renderBoxBounds(
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        outlineColor: Color,
        fillColor: Color = outlineColor,
        outline: Boolean = true,
        fill: Boolean = true,
        phase: Boolean = false,
        lineWidth: Number = 2.5
    ) {
        if (!outline && !fill) return
        val cam = camera.position()

        matrixStack.pushPose()
        matrixStack.translate(-cam.x, -cam.y, -cam.z)

        if (fill) consumers.order(0).submitCustomGeometry(matrixStack, if (phase) NoemtRenderLayers.FILLED_THROUGH_WALLS else NoemtRenderLayers.FILLED) { pose, buffer ->
            buffer.addFilledBoxVertices(pose, minX, minY, minZ, maxX, maxY, maxZ, fillColor.red / 255f, fillColor.green / 255f, fillColor.blue / 255f, fillColor.alpha / 255f)
        }

        if (outline) consumers.order(0).submitCustomGeometry(matrixStack, if (phase) NoemtRenderLayers.LINES_THROUGH_WALLS else NoemtRenderLayers.LINES) { pose, buffer ->
            buffer.addLineBoxVertices(pose, minX, minY, minZ, maxX, maxY, maxZ, outlineColor.red / 255f, outlineColor.green / 255f, outlineColor.blue / 255f, 1f, lineWidth.toFloat())
        }

        matrixStack.popPose()
    }

    fun RenderContext.renderBoxBounds(
        aabb: AABB,
        outlineColor: Color,
        fillColor: Color = outlineColor,
        outline: Boolean = true,
        fill: Boolean = true,
        phase: Boolean = false,
        lineWidth: Number = 2.5
    ) = renderBoxBounds(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ, outlineColor, fillColor, outline, fill, phase, lineWidth)

    fun RenderContext.renderString(
        text: String,
        x: Number, y: Number, z: Number,
        color: Color = Color.WHITE,
        scale: Number = 1f,
        phase: Boolean = false
    ) {
        val camPos = camera.position()
        val dx = (x.toDouble() - camPos.x).toFloat()
        val dy = (y.toDouble() - camPos.y).toFloat()
        val dz = (z.toDouble() - camPos.z).toFloat()
        val toScale = (scale.toFloat() * 0.025f)

        matrixStack.pushPose()
        matrixStack.translate(dx, dy, dz)
        matrixStack.mulPose(camera.rotation())
        matrixStack.scale(toScale, -toScale, toScale)

        val textLayer = if (phase) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL
        val textSubmitter = consumers.order(0)
        val lines = text.addColor().lineSequence()

        for ((i, line) in lines.withIndex()) {
            val seq = Component.literal(line).visualOrderText
            textSubmitter.submitText(matrixStack, -mc.font.width(seq) / 2f, i * 9f, seq, true, textLayer, color.rgb, 0, LightCoordsUtil.FULL_BRIGHT, 0)
        }

        matrixStack.popPose()
    }

    fun RenderContext.renderString(
        text: String,
        pos: Vec3,
        color: Color = Color.WHITE,
        scale: Number = 1f,
        phase: Boolean = false
    ) = renderString(text, pos.x, pos.y, pos.z, color, scale, phase)

    fun RenderContext.renderLine(start: Vec3, finish: Vec3, color: Color, thickness: Number = 2, phase: Boolean = false) {
        val cameraPos = camera.position()
        matrixStack.pushPose()
        matrixStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        val lines = if (phase) NoemtRenderLayers.LINES_THROUGH_WALLS else NoemtRenderLayers.LINES

        consumers.order(0).submitCustomGeometry(matrixStack, lines) { pose, buffer ->
            buffer.addLine(
                pose,
                start.x.toFloat(), start.y.toFloat(), start.z.toFloat(),
                finish.x.toFloat(), finish.y.toFloat(), finish.z.toFloat(),
                color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f,
                thickness.toFloat()
            )
        }

        matrixStack.popPose()
    }

    fun RenderContext.renderTracer(point: Vec3, color: Color, thickness: Number = 2.5) {
        matrixStack.pushPose()
        matrixStack.translate(camera.position().reverse())

        val cameraPoint = camera.position().add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()))

        consumers.order(0).submitCustomGeometry(matrixStack, NoemtRenderLayers.LINES_THROUGH_WALLS) { pose, buffer ->
            buffer.addLine(
                pose,
                cameraPoint.x.toFloat(), cameraPoint.y.toFloat(), cameraPoint.z.toFloat(),
                point.x.toFloat(), point.y.toFloat(), point.z.toFloat(),
                color.red / 255f, color.green / 255f, color.blue / 255f, 1f,
                thickness.toFloat()
            )
        }

        matrixStack.popPose()
    }

    fun RenderContext.render3DBezier2(
        p1: Vec3,
        control: Vec3,
        p3: Vec3,
        color: Color,
        lineWidth: Number = 3f,
        depth: Boolean = true,
        segments: Int = 24
    ) {
        val cameraPos = camera.position()
        matrixStack.pushPose()
        matrixStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        val layer = if (depth) NoemtRenderLayers.LINES else NoemtRenderLayers.LINES_THROUGH_WALLS
        consumers.order(0).submitCustomGeometry(matrixStack, layer) { pose, buffer ->
            var prevX = p1.x.toFloat()
            var prevY = p1.y.toFloat()
            var prevZ = p1.z.toFloat()

            for (i in 1..segments) {
                val t = i.toFloat() / segments
                val u = 1f - t
                val curX = (u * u * p1.x + 2f * u * t * control.x + t * t * p3.x).toFloat()
                val curY = (u * u * p1.y + 2f * u * t * control.y + t * t * p3.y).toFloat()
                val curZ = (u * u * p1.z + 2f * u * t * control.z + t * t * p3.z).toFloat()

                buffer.addLine(
                    pose,
                    prevX, prevY, prevZ,
                    curX, curY, curZ,
                    color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f,
                    lineWidth.toFloat()
                )

                prevX = curX
                prevY = curY
                prevZ = curZ
            }
        }

        matrixStack.popPose()
    }

    fun RenderContext.renderWaypoint(
        pos: Vec3,
        color: Color,
        label: String = "",
        seeThroughBlocks: Boolean = true
    ) {
        val box = AABB(pos.x - 0.3, pos.y, pos.z - 0.3, pos.x + 0.3, pos.y + 0.6, pos.z + 0.3)
        renderBoxBounds(box, color, Color(color.red, color.green, color.blue, 60), outline = true, fill = true, phase = seeThroughBlocks)

        val beamTop = Vec3(pos.x, pos.y + 120.0, pos.z)
        renderLine(pos, beamTop, Color(color.red, color.green, color.blue, 120), thickness = 2f, phase = seeThroughBlocks)

        if (label.isNotEmpty()) {
            renderString(label, pos.x, pos.y + 1.2, pos.z, color, scale = 1.2f, phase = seeThroughBlocks)
        }
    }

    private fun VertexConsumer.addFilledBoxVertices(pose: PoseStack.Pose, x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, r: Float, g: Float, b: Float, a: Float) {
        val minX = x1.toFloat() - 0.002f
        val minY = y1.toFloat() - 0.002f
        val minZ = z1.toFloat() - 0.002f
        val maxX = x2.toFloat() + 0.002f
        val maxY = y2.toFloat() + 0.002f
        val maxZ = z2.toFloat() + 0.002f

        addQuad(pose, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a)
        addQuad(pose, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a)
        addQuad(pose, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, r, g, b, a)
        addQuad(pose, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a)
        addQuad(pose, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a)
        addQuad(pose, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a)
    }

    private fun VertexConsumer.addLineBoxVertices(pose: PoseStack.Pose, x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, r: Float, g: Float, b: Float, a: Float, lineWidth: Float) {
        val minX = x1.toFloat() - 0.002f
        val minY = y1.toFloat() - 0.002f
        val minZ = z1.toFloat() - 0.002f
        val maxX = x2.toFloat() + 0.002f
        val maxY = y2.toFloat() + 0.002f
        val maxZ = z2.toFloat() + 0.002f

        addLine(pose, minX, minY, minZ, maxX, minY, minZ, r, g, b, a, lineWidth)
        addLine(pose, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a, lineWidth)
        addLine(pose, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a, lineWidth)
        addLine(pose, minX, minY, maxZ, minX, minY, minZ, r, g, b, a, lineWidth)

        addLine(pose, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a, lineWidth)
        addLine(pose, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a, lineWidth)
        addLine(pose, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a, lineWidth)
        addLine(pose, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a, lineWidth)

        addLine(pose, minX, minY, minZ, minX, maxY, minZ, r, g, b, a, lineWidth)
        addLine(pose, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a, lineWidth)
        addLine(pose, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a, lineWidth)
        addLine(pose, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a, lineWidth)
    }

    private fun VertexConsumer.addQuad(pose: PoseStack.Pose, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float, x4: Float, y4: Float, z4: Float, r: Float, g: Float, b: Float, a: Float) {
        addVertex(pose, x1, y1, z1).setColor(r, g, b, a)
        addVertex(pose, x2, y2, z2).setColor(r, g, b, a)
        addVertex(pose, x3, y3, z3).setColor(r, g, b, a)
        addVertex(pose, x1, y1, z1).setColor(r, g, b, a)
        addVertex(pose, x3, y3, z3).setColor(r, g, b, a)
        addVertex(pose, x4, y4, z4).setColor(r, g, b, a)
    }

    private fun VertexConsumer.addLine(pose: PoseStack.Pose, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, r: Float, g: Float, b: Float, a: Float, lineWidth: Float) {
        val normal = Vector3f(x2 - x1, y2 - y1, z2 - z1).apply { if (lengthSquared() > 0f) normalize() }
        addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, normal).setLineWidth(lineWidth)
        addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, normal).setLineWidth(lineWidth)
    }
}
