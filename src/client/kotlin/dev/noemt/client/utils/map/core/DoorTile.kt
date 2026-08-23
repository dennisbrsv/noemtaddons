package dev.noemt.client.utils.map.core

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.utils.MathUtils.aabb
import dev.noemt.client.utils.map.handlers.DungeonScanner.dungeonList
import dev.noemt.client.utils.map.handlers.DungeonTree
import java.awt.Color

class DoorTile(override val x: Int, override val z: Int, var type: DoorType) : Tile {
    override var state = RoomState.UNDISCOVERED
    val aabb = aabb(x - 1, 69, z - 1, x + 2, 73, z + 2)
    var opened = false

    override fun getColor(): Color {
        val config = ConfigManager.config.map
        return when {
            state == RoomState.UNOPENED -> config.colorUnopenedDoor.getEffectiveColour()
            type == DoorType.BLOOD -> config.colorBloodDoor.getEffectiveColour()
            type == DoorType.ENTRANCE -> config.colorEntranceDoor.getEffectiveColour()
            (type == DoorType.WITHER || DungeonTree.isFairy(this)) -> {
                if (opened && state != RoomState.UNDISCOVERED) config.colorOpenWitherDoor.getEffectiveColour()
                else config.colorWitherDoor.getEffectiveColour()
            }
            else -> {
                val coloredRooms = roomTiles.filter { it.data.type != RoomType.NORMAL }
                val roomTile = if (coloredRooms.size == 2) coloredRooms.find { it.data.type != RoomType.FAIRY }
                else coloredRooms.firstOrNull()
                roomTile?.getColor() ?: config.colorRoomDoor.getEffectiveColour()
            }
        }
    }

    val roomTiles get() = roomTileIndices.mapNotNull { dungeonList.getOrNull(it) as? RoomTile }
    val roomTileIndices = buildList {
        val (row, column) = getGridPos()
        val rowEven = row and 1 == 0

        val neighbors = if (rowEven) listOfNotNull(
            (column - 1).takeIf { it >= 0 }?.let { row * 11 + it },
            (column + 1).takeIf { it <= 10 }?.let { row * 11 + it }
        ) else listOfNotNull(
            (row - 1).takeIf { it >= 0 }?.let { it * 11 + column },
            (row + 1).takeIf { it <= 10 }?.let { it * 11 + column }
        )

        addAll(neighbors)
    }
}
