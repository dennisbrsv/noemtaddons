package dev.noemt.client.features.misc

import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.render.Render3D.renderBoxBounds
import dev.noemt.client.render.Render3D.renderLine
import dev.noemt.client.render.Render3D.renderString
import dev.noemt.client.render.RenderHelper.renderBoundingBox
import dev.noemt.client.render.RenderHelper.renderVec
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.NumbersUtils.toFixed
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.awt.Color

object StalkFeature : Module {
    override val id = "stalk"
    override val name = "Player Stalker"
    override val description = "Tracks a player in real-time and draws a tracking line and highlight towards them"
    override val type = ModuleType.LEGIT

    private val mc: Minecraft get() = Minecraft.getInstance()

    var targetName: String? = null
        private set
    var targetEntity: Player? = null
        private set
    var lastKnownPos: Vec3? = null
        private set

    val lineColor = Color(0, 255, 255, 220)
    val boxColor = Color(0, 255, 255, 180)
    val boxFillColor = Color(0, 255, 255, 50)

    override fun init() {
        register<WorldChangeEvent> {
            targetEntity = null
            lastKnownPos = null
        }

        register<TickEvent.Start> {
            val name = targetName ?: return@register
            val level = mc.level ?: return@register

            val found = level.players().find { it.name.string.equals(name, ignoreCase = true) }
            if (found != null && found != mc.player) {
                targetEntity = found
                lastKnownPos = found.position()
            } else {
                targetEntity = null
            }
        }

        register<RenderWorldEvent> {
            val name = targetName ?: return@register
            val player = mc.player ?: return@register

            val entity = targetEntity
            val pos = if (entity != null && entity.isAlive) {
                entity.renderVec
            } else {
                lastKnownPos
            } ?: return@register

            val cam = event.ctx.camera
            val camPos = cam.position()
            val lookDir = Vec3.directionFromRotation(cam.xRot(), cam.yRot())
            val lineStart = camPos.add(lookDir.scale(0.3))

            val targetCenter = if (entity != null) {
                pos.add(0.0, entity.bbHeight / 2.0, 0.0)
            } else {
                pos.add(0.0, 0.9, 0.0)
            }

            // Draw line towards target
            event.ctx.renderLine(lineStart, targetCenter, lineColor, thickness = 2.5f, phase = true)

            // Draw box and label
            val dist = player.distanceTo(entity ?: player)
            if (entity != null) {
                val box = entity.renderBoundingBox
                event.ctx.renderBoxBounds(box, boxColor, boxFillColor, outline = true, fill = true, phase = true)
                event.ctx.renderString("§b[Stalking: §f${entity.name.string}§b] §e(${dist.toFixed(1)}m)", pos.x, pos.y + entity.bbHeight + 0.4, pos.z, scale = 1.0f, phase = true)
            } else {
                event.ctx.renderString("§c[Last Known: §f$name§c] §e(${dist.toFixed(1)}m)", pos.x, pos.y + 1.8, pos.z, scale = 1.0f, phase = true)
            }
        }
    }

    fun stalk(ign: String) {
        if (ign.equals("stop", ignoreCase = true) || ign.equals("clear", ignoreCase = true) || ign.equals("off", ignoreCase = true)) {
            stop()
            return
        }

        targetName = ign
        val level = mc.level
        val found = level?.players()?.find { it.name.string.equals(ign, ignoreCase = true) }
        if (found != null && found != mc.player) {
            targetEntity = found
            lastKnownPos = found.position()
            ChatUtils.modMessage("&aNow stalking &b${found.name.string}&a!")
        } else {
            targetEntity = null
            lastKnownPos = null
            ChatUtils.modMessage("&eNow stalking &b$ign&e. Waiting for player to come within render range...")
        }
    }

    fun stop() {
        if (targetName != null) {
            val prev = targetName
            targetName = null
            targetEntity = null
            lastKnownPos = null
            ChatUtils.modMessage("&cStopped stalking &b$prev&c.")
        } else {
            ChatUtils.modMessage("&eNot currently stalking anyone.")
        }
    }
}
