package dev.noemt.client.utils.map.handlers

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.DungeonEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.utils.map.core.DoorTile
import dev.noemt.client.utils.map.core.RoomType
import dev.noemt.client.utils.map.core.UniqueRoom
import dev.noemt.client.utils.map.utils.ScanUtils

object DungeonTree {
    private var splitsCache: Map<UniqueRoom, Set<UniqueRoom>>? = null
    private var bloodRushCache: List<UniqueRoom>? = null
    private var fairyRoom: UniqueRoom? = null
    private var nextRoomAfterFairy: UniqueRoom? = null
    private var roomBeforeFairy: UniqueRoom? = null

    fun init() {
        EventBus.register<WorldChangeEvent> { clearCache() }
        EventBus.register<DungeonEvent.TileScannedEvent> { clearCache() }
    }

    fun clearCache() {
        splitsCache = null
        bloodRushCache = null
        fairyRoom = null
        nextRoomAfterFairy = null
        roomBeforeFairy = null
    }

    fun isFairy(door: DoorTile): Boolean {
        val currentRoom = ScanUtils.currentRoom ?: return false
        val lastRoom = ScanUtils.lastKnownRoom ?: return false

        getBloodRush()

        val fRoom = fairyRoom ?: return false
        val prevRoom = roomBeforeFairy ?: return false
        val nextRoom = nextRoomAfterFairy ?: return false
        if (prevRoom != currentRoom && prevRoom != lastRoom) return false

        val rooms = door.roomTiles.mapNotNull { it.uniqueRoom }
        if (rooms.size != 2) return false
        val (r1, r2) = rooms

        return (r1 == fRoom && r2 == nextRoom) || (r1 == nextRoom && r2 == fRoom)
    }

    fun getBloodRush(): List<UniqueRoom> = bloodRushCache ?: run {
        val graph = getSplits()
        val start = graph.keys.find { it.data.type == RoomType.ENTRANCE } ?: return emptyList()
        val target = graph.keys.find { it.data.type == RoomType.BLOOD } ?: return emptyList()

        val queue = ArrayDeque<UniqueRoom>()
        val previous = mutableMapOf<UniqueRoom, UniqueRoom?>()

        queue.add(start)
        previous[start] = null

        while (queue.isNotEmpty()) {
            val room = queue.removeFirst()
            if (room == target) break

            graph[room]?.forEach { neighbor ->
                if (neighbor !in previous) {
                    previous[neighbor] = room
                    queue.add(neighbor)
                }
            }
        }

        if (target !in previous) return emptyList()

        val path = mutableListOf<UniqueRoom>()
        var room: UniqueRoom? = target
        while (room != null) {
            path.add(room)
            room = previous[room]
        }

        val reversedPath = path.asReversed()
        bloodRushCache = reversedPath

        val fairyIndex = reversedPath.indexOfFirst { it.data.type == RoomType.FAIRY }
        if (fairyIndex > 0 && fairyIndex < reversedPath.size - 1) {
            roomBeforeFairy = reversedPath[fairyIndex - 1]
            fairyRoom = reversedPath[fairyIndex]
            nextRoomAfterFairy = reversedPath[fairyIndex + 1]
        }

        return reversedPath
    }

    private fun getSplits(): Map<UniqueRoom, Set<UniqueRoom>> = splitsCache ?: run {
        val graph = mutableMapOf<UniqueRoom, MutableSet<UniqueRoom>>()

        for (tile in DungeonScanner.dungeonList) {
            val door = tile as? DoorTile ?: continue
            val rooms = door.roomTiles.mapNotNull { it.uniqueRoom }
            if (rooms.size != 2 || rooms[0] == rooms[1]) continue

            graph.getOrPut(rooms[0], ::mutableSetOf).add(rooms[1])
            graph.getOrPut(rooms[1], ::mutableSetOf).add(rooms[0])
        }

        return graph.also { splitsCache = it }
    }
}
