package dev.noemt.client.utils.map.handlers

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.MainThreadPacketReceivedEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.mixin.IMapState
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.DungeonListener.dungeonTeammatesNoSelf
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.MathUtils.lerp
import dev.noemt.client.utils.PlayerUtils
import dev.noemt.client.utils.WorldUtils
import dev.noemt.client.utils.dungeon.DungeonPlayer
import dev.noemt.client.utils.map.core.*
import dev.noemt.client.utils.map.utils.LegacyRegistry
import dev.noemt.client.utils.map.utils.MapUtils
import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import java.util.concurrent.ConcurrentHashMap

object MapUpdater {
    private val playerHeadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playerJobs = ConcurrentHashMap<String, Job>()

    fun init() {
        EventBus.register<WorldChangeEvent> {
            playerJobs.forEach { it.value.cancel() }
            playerJobs.clear()
        }

        EventBus.register<MainThreadPacketReceivedEvent.Post> {
            if (!LocationUtils.inDungeon) return@register
            if (!DungeonListener.dungeonStarted) return@register
            val packet = event.packet as? ClientboundMapItemDataPacket ?: return@register
            val mc = Minecraft.getInstance()
            val hotbar8 = PlayerUtils.getHotbarSlot(8)
            val mapId = hotbar8?.get(DataComponents.MAP_ID) ?: packet.mapId
            val mapData = mc.level?.getMapData(mapId) ?: return@register

            MapUtils.calibrated = MapUtils.calibrateMap(mapData)
            if (MapUtils.calibrated) {
                updateRooms(mapData)
                updatePlayers(mapData)
            }
        }
    }

    fun updatePlayers(mapData: MapItemSavedData) {
        val mapDataState = mapData as? IMapState ?: return
        val decorations = mapDataState.decorations ?: return
        val livingTeammates = dungeonTeammatesNoSelf.filter { !it.isDead }

        decorations.forEach { (key, decoration) ->
            if (decoration.type.value() == MapDecorationTypes.FRAME.value()) {
                DungeonListener.thePlayer?.icon = key
            } else {
                val index = key.lastOrNull()?.digitToIntOrNull()
                if (index != null && index in livingTeammates.indices) {
                    livingTeammates[index].icon = key
                }
            }
        }

        DungeonListener.dungeonTeammates.forEach { teammate ->
            if (teammate.isDead) return@forEach
            val decoration = decorations[teammate.icon] ?: return@forEach
            smoothUpdatePlayer(teammate, decoration.mapX.toFloat(), decoration.mapZ.toFloat(), decoration.yaw)
        }
    }

    private fun smoothUpdatePlayer(player: DungeonPlayer, targetX: Float, targetZ: Float, targetYaw: Float) {
        if (player.mapX == targetX && player.mapZ == targetZ && player.yaw == targetYaw) return

        playerHeadScope.launch {
            playerJobs.put(player.name, coroutineContext.job)?.cancel()

            val startX = player.mapX
            val startZ = player.mapZ
            val startYaw = player.yaw

            val animationDuration = 350L
            val startTime = System.currentTimeMillis()
            var progress = 0f

            while (progress < 1f && isActive) {
                val elapsedTime = System.currentTimeMillis() - startTime
                progress = (elapsedTime.toFloat() / animationDuration).coerceAtMost(1f)

                player.mapX = lerp(startX, targetX, progress).toFloat()
                player.mapZ = lerp(startZ, targetZ, progress).toFloat()
                player.yaw = interpolateYaw(startYaw, targetYaw, progress)

                delay(10)
            }
        }
    }

    private fun interpolateYaw(start: Float, target: Float, progress: Float): Float {
        var diff = (target - start) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return start + diff * progress
    }

    fun updateRooms(mapData: MapItemSavedData) {
        if (LocationUtils.inBoss) return
        if (DungeonListener.dungeonEnded) return
        if (DungeonListener.thePlayer?.isDead == true) return
        HotbarMapScanner.updateMap(mapData)

        for (x in 0..10) for (z in 0..10) {
            val idx = z * 11 + x
            val room = DungeonScanner.dungeonList[idx]
            val mapTile = HotbarMapScanner.getTile(x, z)

            if (room is Unknown) {
                DungeonScanner.dungeonList[idx] = mapTile
                DungeonTree.clearCache()
                if (mapTile is RoomTile) {
                    val connected = HotbarMapScanner.getConnected(x, z)
                    connected.firstOrNull { it.data.name != "Unknown" }?.let {
                        mapTile.addToUnique(z, x, it.data.name)
                    }
                }
                continue
            }

            if (mapTile.state.ordinal < room.state.ordinal || mapTile is RoomTile && room is RoomTile && mapTile.data.type == RoomType.PUZZLE) {
                room.state = mapTile.state
            }

            if (mapTile is RoomTile && room is RoomTile && mapTile.data.type != room.data.type) {
                if (room.data.name == mapTile.data.name) room.data = mapTile.data
            }

            if (mapTile is DoorTile && room is DoorTile) {
                if (mapTile.type == DoorType.WITHER && room.type != DoorType.WITHER) {
                    room.type = mapTile.type
                }
            }

            if (room is DoorTile && (room.type == DoorType.ENTRANCE || room.type == DoorType.WITHER || room.type == DoorType.BLOOD)) {
                if (mapTile is DoorTile && mapTile.type == DoorType.WITHER) room.opened = false
                else if (!room.opened) {
                    if (WorldUtils.isChunkLoaded(room.x, room.z)) {
                        val id = LegacyRegistry.getLegacyId(WorldUtils.getStateAt(room.x, 69, room.z))
                        if (id == 0 || id == 166) room.opened = true
                    } else if (mapTile is DoorTile && mapTile.state == RoomState.DISCOVERED) {
                        if (room.type == DoorType.BLOOD) {
                            val bloodRoomTile = DungeonScanner.dungeonList.filterIsInstance<RoomTile>().find { it.data.type == RoomType.BLOOD }
                            if (bloodRoomTile != null && bloodRoomTile.state != RoomState.UNOPENED) room.opened = true
                        } else room.opened = true
                    }
                }
            }
        }
    }

    private val MapDecoration.mapX get() = (this.x + 128) shr 1
    private val MapDecoration.mapZ get() = (this.y + 128) shr 1
    private val MapDecoration.yaw get() = this.rot * 22.5f
}
