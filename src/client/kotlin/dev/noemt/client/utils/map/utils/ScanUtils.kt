package dev.noemt.client.utils.map.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.DungeonEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.utils.GsonUtils
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.WorldUtils
import dev.noemt.client.utils.map.core.RoomData
import dev.noemt.client.utils.map.core.RoomTile
import dev.noemt.client.utils.map.core.UniqueRoom
import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.handlers.DungeonScanner.startX
import dev.noemt.client.utils.map.handlers.DungeonScanner.startZ
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.io.InputStreamReader
import kotlin.math.round

object ScanUtils {
    val roomList: List<RoomData> by lazy {
        try {
            val stream = ScanUtils::class.java.getResourceAsStream("/assets/noemtaddons/data/rooms.json")
                ?: error("Could not load rooms.json from assets!")
            val jsonText = InputStreamReader(stream).use { it.readText() }
            GsonUtils.decode<List<RoomData>>(jsonText)
        } catch (e: Throwable) {
            e.printStackTrace()
            emptyList()
        }
    }

    var currentRoom: UniqueRoom? = null
    var lastKnownRoom: UniqueRoom? = null

    fun init() {
        EventBus.register<WorldChangeEvent> {
            currentRoom = null
            lastKnownRoom = null
        }

        EventBus.register<TickEvent.End> {
            if (!LocationUtils.inDungeon) return@register
            val player = Minecraft.getInstance().player ?: return@register
            val room = getRoomFromPos(player.position())
            if (currentRoom == room) return@register

            lastKnownRoom = currentRoom
            currentRoom = room

            lastKnownRoom?.let { EventBus.post(DungeonEvent.RoomEvent.onExit(it)) }
            currentRoom?.let { EventBus.post(DungeonEvent.RoomEvent.onEnter(it)) }
        }
    }

    fun getRoomData(hash: Int) = roomList.find { hash in it.cores }
    fun getRoomData(name: String) = roomList.find { it.name == name }

    fun getRoomGraf(pos: Vec3): Pair<Int, Int> {
        val roomIndexX = round((pos.x - startX) / DungeonScanner.roomSize).toInt()
        val roomIndexZ = round((pos.z - startZ) / DungeonScanner.roomSize).toInt()
        val gridX = roomIndexX * 2
        val gridZ = roomIndexZ * 2
        return gridX.coerceIn(0, 10) to gridZ.coerceIn(0, 10)
    }

    fun getRoomFromPos(vec: Vec3): UniqueRoom? {
        val (gx, gz) = getRoomGraf(vec)
        val unq = (DungeonScanner.dungeonList.getOrNull(gz * 11 + gx) as? RoomTile)?.uniqueRoom
        return unq
    }

    fun getCore(x: Int, z: Int): Int {
        val sb = StringBuilder(150)
        val pos = BlockPos.MutableBlockPos(x, 0, z)

        for (y in 140 downTo 12) {
            val id = LegacyRegistry.getLegacyId(WorldUtils.getStateAt(pos.setY(y)))
            if (id == 5 || id == 54 || id == 146) continue
            sb.append(id)
        }
        return sb.toString().hashCode()
    }

    fun getHighestY(x: Int, z: Int): Int {
        val pos = BlockPos.MutableBlockPos(x, 0, z)
        var height = 0

        for (y in 256 downTo 0) {
            val blockState = WorldUtils.getStateAt(pos.setY(y))
            if (blockState.isAir || blockState.block == Blocks.GOLD_BLOCK) continue
            height = y
            break
        }

        return height
    }

    fun BlockPos.rotate(degree: Int): BlockPos {
        return when ((degree % 360 + 360) % 360) {
            0 -> BlockPos(x, y, z)
            90 -> BlockPos(z, y, -x)
            180 -> BlockPos(-x, y, -z)
            270 -> BlockPos(-z, y, x)
            else -> BlockPos(x, y, z)
        }
    }

    fun getRelativeCoord(realPos: BlockPos, roomCorner: BlockPos, rotation: Int): BlockPos {
        val centeredPos = realPos.offset(-roomCorner.x, 0, -roomCorner.z)
        return centeredPos.rotate(-rotation)
    }
}
