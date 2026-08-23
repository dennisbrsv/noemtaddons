package dev.noemt.client.utils.pathfinder

import dev.noemt.client.render.Render3D.renderBoxBounds
import dev.noemt.client.render.Render3D.renderLine
import dev.noemt.client.render.Render3D.renderString
import dev.noemt.client.render.RenderContext
import dev.noemt.client.utils.MathUtils.aabb
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.awt.Color

object PathRenderer {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun render(ctx: RenderContext) {
        val path = PathExecutor.currentPath
        if (path.isEmpty()) return

        val player = mc.player ?: return
        val boxOffset = Vec3(-0.35, 0.0, -0.35)

        // 1. Draw connecting path lines
        for (i in 0 until path.size - 1) {
            val from = path[i].standingVec
            val to = path[i + 1].standingVec
            val action = path[i + 1].action

            ctx.renderLine(from, to, action.color, thickness = 3.5, phase = true)
        }

        // 2. Draw waypoint boxes and billboard text
        for ((index, node) in path.withIndex()) {
            val vec = node.standingVec
            val isGoal = index == path.size - 1
            val color = if (isGoal) Color.RED else node.action.color

            val nodeAABB = aabb(0.7, 0.25, 0.7, 0, 0, 0).move(boxOffset.add(node.pos.x.toDouble(), node.pos.y.toDouble() + 1.0, node.pos.z.toDouble()))
            ctx.renderBoxBounds(nodeAABB, color, fill = true, outline = true, phase = true)

            // Render action label above specialized nodes
            if (node.action == PathAction.ETHERWARP || node.action == PathAction.INSTANT_TRANSMISSION || isGoal) {
                val label = if (isGoal) "§c§lGOAL" else "§d§l${node.action.displayName}"
                ctx.renderString(label, vec.add(0.0, 0.6, 0.0), color = color, scale = 1.3f, phase = true)
            }
        }
    }
}
