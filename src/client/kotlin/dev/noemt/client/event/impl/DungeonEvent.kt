package dev.noemt.client.event.impl

import dev.noemt.client.event.Event
import dev.noemt.client.utils.map.core.RoomState
import dev.noemt.client.utils.map.core.Tile
import dev.noemt.client.utils.map.core.UniqueRoom
import dev.noemt.client.utils.dungeon.DungeonPlayer

abstract class DungeonEvent : Event(false) {
    abstract class RoomEvent(val room: UniqueRoom) : DungeonEvent() {
        class onEnter(room: UniqueRoom) : RoomEvent(room)
        class onExit(room: UniqueRoom) : RoomEvent(room)
        class onStateChange(room: UniqueRoom, val oldState: RoomState, val newState: RoomState, val roomPlayers: List<DungeonPlayer>) : RoomEvent(room)
    }

    class TileScannedEvent(val tile: Tile) : DungeonEvent()
    class PlayerDeathEvent(val name: String, val reason: String) : DungeonEvent()
    class Score(val oldScore: Int, val score: Int) : DungeonEvent()
    object RunStatedEvent : DungeonEvent()
    object RunEndedEvent : DungeonEvent()
}
