package dev.noemt.client.features.blood

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.render.Render3D.renderBox
import dev.noemt.client.render.Render3D.renderTracer
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

object BloodESP : Module {
    override val id = "blood_esp"
    override val name = "Blood Room ESP"
    override val description = "In-world 3D bounding box and tracer to the Blood Room"
    override val type = ModuleType.CHEAT

    private val mc: Minecraft get() = Minecraft.getInstance()
    private var bloodCenter: BlockPos? = null
    private var tracerTarget: Vec3? = null

    private val checkOffsets = arrayOf(
        Triple(-15, -6, 0),
        Triple(-6, 15, 1),
        Triple(15, 6, 3),
        Triple(6, -15, 2)
    )

    override fun init() {
        register<WorldChangeEvent> {
            bloodCenter = null
            tracerTarget = null
        }

        register<TickEvent.Start> {
            if (!LocationUtils.inDungeon) return@register
            if (DungeonListener.dungeonStarted) return@register
            if (bloodCenter != null) return@register
            val found = findBlood()
            if (found != null) {
                val (center, rotation) = found
                bloodCenter = center
                val halfRoom = 15
                val (doorX, doorZ) = when (rotation) {
                    0 -> center.x to (center.z - halfRoom)
                    1 -> (center.x - halfRoom) to center.z
                    2 -> (center.x + halfRoom) to center.z
                    else -> center.x to (center.z + halfRoom)
                }
                tracerTarget = Vec3(doorX + 0.5, center.y.toDouble(), doorZ + 0.5)
            }
        }

        register<RenderWorldEvent> {
            val config = ConfigManager.config.blood
            if (!config.bloodEsp && !config.espTracer) return@register
            if (!LocationUtils.inDungeon) return@register
            if (DungeonListener.dungeonStarted) return@register
            val center = bloodCenter ?: return@register

            if (config.espTracer) {
                val target = tracerTarget
                if (target != null) {
                    event.ctx.renderTracer(target, config.tracerColor.getEffectiveColour())
                }
            }

            if (config.bloodEsp) {
                event.ctx.renderBox(
                    center.x + 0.5, 66, center.z + 0.5,
                    31, 34,
                    config.espColor.getEffectiveColour(),
                    outline = true,
                    fill = false,
                    phase = true
                )
            }
        }
    }

    fun findBlood(): Pair<BlockPos, Int>? {
        val level = mc.level ?: return null
        val playerPos = mc.player?.blockPosition() ?: return null

        val checkOffsets = arrayOf(
            Triple(-15, -6, 0),
            Triple(-6, 15, 1),
            Triple(15, 6, 3),
            Triple(6, -15, 2)
        )

        for (cx in -6..6 step 2) {
            for (cz in -6..6 step 2) {
                val rx = cx * 16 + 8
                val rz = cz * 16 + 8
                val center = BlockPos(rx, 99, rz)
                for ((dx, dz, i) in checkOffsets) {
                    val block = level.getBlockState(BlockPos(center.x + dx, center.y, center.z + dz)).block
                    if (block == Blocks.REDSTONE_BLOCK) {
                        return center to i
                    }
                }
            }
        }

        return null
    }
}
