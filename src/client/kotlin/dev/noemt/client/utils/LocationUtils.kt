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

    private var cachedOnHypixel: Boolean? = null
    val onHypixel: Boolean
        get() {
            if (cachedOnHypixel == null) {
                cachedOnHypixel = mc.player?.connection?.serverBrand()?.lowercase()?.contains("hypixel") == true
            }
            return cachedOnHypixel == true
        }

    var inSkyblock: Boolean = false
    var inDungeon: Boolean = false
        private set

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
        val snap = ScoreboardUtils.getSnapshot()
        if (snap.cleanLines.isNotEmpty()) {
            inSkyblock = onHypixel && (snap.isSkyblock || inSkyblock)
        }
        inDungeon = snap.inDungeon

        var foundFloor = snap.dungeonFloor
        var foundFloorNum = snap.dungeonFloorNumber

        if (foundFloor == null && inDungeon) {
            for (entry in TabListUtils.getTabList()) {
                val clean = ChatUtils.run { entry.first.string.removeFormatting().trim() }
                val match = floorRegex.find(clean)
                if (match != null) {
                    foundFloor = match.groupValues[1].uppercase()
                    foundFloorNum = if (foundFloor == "E") 0 else foundFloor.filter { it.isDigit() }.toIntOrNull() ?: 1
                    break
                }
            }
        }

        if (foundFloor != null) {
            dungeonFloor = foundFloor
            dungeonFloorNumber = foundFloorNum ?: 1
        }

        val player = mc.player
        if (player != null && inDungeon) {
            updateBossStatus(player.x, player.y, player.z)
        } else {
            inBoss = false
        }
    }

    private fun reset() {
        cachedOnHypixel = null
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
