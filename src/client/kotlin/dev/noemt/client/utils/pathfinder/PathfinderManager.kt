package dev.noemt.client.utils.pathfinder

import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.utils.ChatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

object PathfinderManager {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val pathScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init() {
        register<TickEvent.Start> {
            PathExecutor.tick()
        }

        register<RenderWorldEvent> {
            PathRenderer.render(event.ctx)
        }

        register<WorldChangeEvent> {
            PathExecutor.stop()
        }
    }

    fun navigateTo(
        targetPos: BlockPos,
        allowAotv: Boolean = true,
        allowEtherwarp: Boolean = true,
        smooth: Boolean = true
    ) {
        val player = mc.player ?: return
        val startPos = BlockPos.containing(player.x, player.y, player.z)

        pathScope.launch {
            val startTime = System.currentTimeMillis()
            val rawPath = if (allowEtherwarp || allowAotv) {
                TeleportPathfinder.findTeleportPath(startPos, targetPos)
            } else {
                AStarPathfinder(startPos, targetPos, allowAotv = false, allowEtherwarp = false).findPath()
            }
            val tookMs = System.currentTimeMillis() - startTime

            if (rawPath == null || rawPath.isEmpty()) {
                ChatUtils.modMessage("&c[Pathfinder] No path found to (${targetPos.x}, ${targetPos.y}, ${targetPos.z}) after ${tookMs}ms.")
                return@launch
            }

            val finalPath = if (smooth) PathSmoother.smoothPath(rawPath) else rawPath

            mc.execute {
                PathExecutor.startPath(finalPath, targetPos)
                val teleports = finalPath.count { it.action == PathAction.ETHERWARP || it.action == PathAction.INSTANT_TRANSMISSION }
                ChatUtils.modMessage("&a[Pathfinder] Teleport path found in ${tookMs}ms (${finalPath.size} waypoints, $teleports teleports). Starting navigation...")
            }
        }
    }

    fun navigateTo(targetVec: Vec3, allowAotv: Boolean = true, allowEtherwarp: Boolean = true) {
        navigateTo(BlockPos.containing(targetVec.x, targetVec.y, targetVec.z), allowAotv, allowEtherwarp)
    }

    fun cancel() {
        PathExecutor.stop()
        ChatUtils.modMessage("&e[Pathfinder] Navigation stopped.")
    }

    fun isNavigating(): Boolean = PathExecutor.currentState == PathExecutor.State.FOLLOWING ||
            PathExecutor.currentState == PathExecutor.State.TELEPORTING_ETHERWARP ||
            PathExecutor.currentState == PathExecutor.State.TELEPORTING_INSTANT
}
