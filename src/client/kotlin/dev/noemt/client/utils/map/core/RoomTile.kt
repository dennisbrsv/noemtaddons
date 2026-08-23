package dev.noemt.client.utils.map.core

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.DungeonEvent
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.map.core.RoomType.*
import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.utils.ScanUtils
import net.minecraft.client.Minecraft
import java.awt.Color
import kotlin.properties.Delegates

class RoomTile(override val x: Int, override val z: Int, var data: RoomData) : Tile {
    var uniqueRoom: UniqueRoom? = null
    var isSeparator = false

    override var state by Delegates.observable(RoomState.UNDISCOVERED) { _, oldValue, newValue ->
        if (uniqueRoom?.mainRoom != this) return@observable
        if (oldValue == newValue) return@observable
        if (data.name == "Unknown") return@observable
        val config = ConfigManager.config.map
        if (config.dungeonMapCheater && oldValue == RoomState.UNOPENED && newValue == RoomState.UNDISCOVERED) return@observable
        if (config.dungeonMapCheater && newValue == RoomState.UNOPENED && oldValue == RoomState.UNDISCOVERED) return@observable

        val mc = Minecraft.getInstance()
        val roomPlayers = DungeonListener.dungeonTeammates.filter {
            val pos = if (it.entity == mc.player) mc.player?.position() else it.getRealPos()
            pos != null && ScanUtils.getRoomFromPos(pos)?.data?.name == data.name
        }

        if (newValue == RoomState.GREEN) uniqueRoom?.foundSecrets = uniqueRoom?.data?.secrets ?: 0
        uniqueRoom?.let { EventBus.post(DungeonEvent.RoomEvent.onStateChange(it, oldValue, newValue, roomPlayers)) }
    }

    override fun getColor(): Color {
        val config = ConfigManager.config.map
        return when {
            state == RoomState.UNOPENED -> config.colorUnopened.getEffectiveColour()
            data.type == BLOOD -> config.colorBlood.getEffectiveColour()
            data.type == FAIRY -> config.colorFairy.getEffectiveColour()
            data.type == RARE -> config.colorRare.getEffectiveColour()
            data.type == CHAMPION -> config.colorMiniboss.getEffectiveColour()
            data.type == PUZZLE -> config.colorPuzzle.getEffectiveColour()
            data.type == TRAP -> config.colorTrap.getEffectiveColour()
            data.type == NORMAL -> config.colorRoom.getEffectiveColour()
            data.type == ENTRANCE -> config.colorEntrance.getEffectiveColour()
            else -> config.colorRoom.getEffectiveColour()
        }
    }

    fun addToUnique(row: Int, column: Int, roomName: String = data.name) {
        val unique = DungeonScanner.uniqueRooms[roomName]
        if (unique == null) {
            UniqueRoom(column, row, this).let {
                DungeonScanner.uniqueRooms[data.name] = it
                uniqueRoom = it
            }
        } else {
            unique.addTile(column, row, this)
            uniqueRoom = unique
        }
    }
}
