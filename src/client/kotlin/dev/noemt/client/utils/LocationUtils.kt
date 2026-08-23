package dev.noemt.client.utils

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.MainThreadPacketReceivedEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.event.priority.EventPriority
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.MathUtils.aabb
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import kotlin.jvm.optionals.getOrNull

object LocationUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    val onHypixel: Boolean
        get() = mc.player?.connection?.serverBrand()?.lowercase()?.contains("hypixel") == true

    var inSkyblock: Boolean = false
    var inDungeon: Boolean = false
    var dungeonFloor: String? = null
    var dungeonFloorNumber: Int? = null
    var inBoss: Boolean = false

    fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGHEST) {
            if (event.packet is ClientboundSetPlayerTeamPacket) {
                val params = event.packet.parameters.getOrNull() ?: return@register
                val text = (params.playerPrefix.string + params.playerSuffix.string).removeFormatting()

                if (!inDungeon && text.contains("The Catacombs (") && !text.contains("Queue")) {
                    inDungeon = true
                    inSkyblock = true
                    dungeonFloor = text.substringAfter("(").substringBefore(")")
                    dungeonFloorNumber = dungeonFloor?.lastOrNull()?.digitToIntOrNull() ?: 0
                }
            } else if (event.packet is ClientboundSetObjectivePacket) {
                if (!inSkyblock) inSkyblock = onHypixel && event.packet.objectiveName == "SBScoreboard"
            }
        }

        EventBus.register<WorldChangeEvent>(EventPriority.HIGH) { reset() }
    }

    private fun reset() {
        inSkyblock = false
        inDungeon = false
        dungeonFloor = null
        dungeonFloorNumber = null
        inBoss = false
    }

    fun updateBossStatus(x: Double, y: Double, z: Double) {
        val floor = dungeonFloorNumber?.takeIf { it in 1..7 } ?: return
        inBoss = bossRoomBounds.getOrNull(floor - 1)?.contains(x, y, z) == true
    }

    private val bossRoomBounds = arrayOf(
        aabb(-14, 55, 49, -72, 146, -40),
        aabb(-40, 99, -40, 24, 54, 59),
        aabb(-40, 118, -40, 42, 64, 37),
        aabb(-40, 112, -40, 50, 53, 47),
        aabb(-40, 112, -8, 50, 53, 118),
        aabb(-40, 51, -8, 22, 110, 134),
        aabb(-8, 0, -8, 134, 254, 147)
    )
}
