package dev.noemt.client.utils

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.MainThreadPacketReceivedEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.event.priority.EventPriority
import dev.noemt.client.utils.MathUtils.aabb
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket

object LocationUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    val onHypixel: Boolean
        get() = mc.player?.connection?.serverBrand()?.lowercase()?.contains("hypixel") == true

    var inSkyblock: Boolean = false

    val inDungeon: Boolean
        get() {
            val area = ScoreboardUtils.getSkyblockArea()
            if (area != null) {
                if (area.contains("Dungeon Hub", ignoreCase = true)) return false
                if (area.contains("Catacombs", ignoreCase = true) || area.contains("The Catacombs", ignoreCase = true)) return true
            }
            val lines = ScoreboardUtils.getSidebarLines()
            for (line in lines) {
                if (line.contains("Dungeon Hub", ignoreCase = true)) return false
                if (line.contains("The Catacombs", ignoreCase = true) || line.contains("Catacombs (", ignoreCase = true)) return true
                if (line.contains("Dungeon Cleared:", ignoreCase = true) || line.contains("Cleared:", ignoreCase = true)) return true
            }
            return false
        }

    var dungeonFloor: String? = null
    var dungeonFloorNumber: Int? = null
    var inBoss: Boolean = false

    private val floorRegex = Regex("""(?:The Catacombs|Catacombs)\s*\(([FME\d]+)\)""", RegexOption.IGNORE_CASE)

    fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGHEST) {
            if (event.packet is ClientboundSetObjectivePacket) {
                if (!inSkyblock) inSkyblock = onHypixel && event.packet.objectiveName == "SBScoreboard"
            }
        }

        EventBus.register<dev.noemt.client.event.impl.TickEvent.Start> {
            updateLocationState()
        }

        EventBus.register<WorldChangeEvent>(EventPriority.HIGH) { reset() }
    }

    fun updateLocationState() {
        val lines = ScoreboardUtils.getSidebarLines()
        if (lines.isNotEmpty()) {
            inSkyblock = onHypixel && (ScoreboardUtils.getSidebarTitle().contains("SKYBLOCK", ignoreCase = true) || inSkyblock)
        }

        var foundFloor: String? = null
        for (line in lines) {
            val match = floorRegex.find(line)
            if (match != null) {
                foundFloor = match.groupValues[1].uppercase()
                break
            }
        }

        if (foundFloor == null) {
            for (entry in TabListUtils.getTabList()) {
                val clean = ChatUtils.run { entry.first.string.removeFormatting().trim() }
                val match = floorRegex.find(clean)
                if (match != null) {
                    foundFloor = match.groupValues[1].uppercase()
                    break
                }
            }
        }

        if (foundFloor != null) {
            dungeonFloor = foundFloor
            dungeonFloorNumber = when {
                foundFloor == "E" -> 0
                else -> foundFloor.filter { it.isDigit() }.toIntOrNull() ?: 1
            }
        }

        val player = mc.player
        if (player != null && inDungeon) {
            updateBossStatus(player.x, player.y, player.z)
        }
    }

    private fun reset() {
        inSkyblock = false
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
