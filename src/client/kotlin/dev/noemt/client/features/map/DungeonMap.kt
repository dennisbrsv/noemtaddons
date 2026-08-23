package dev.noemt.client.features.map

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.render.Render3D.renderBoxBounds
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.WorldUtils
import dev.noemt.client.utils.map.core.DoorTile
import dev.noemt.client.utils.map.core.DoorType
import dev.noemt.client.utils.map.core.RoomState
import dev.noemt.client.utils.map.handlers.*
import dev.noemt.client.utils.map.utils.MapUtils
import dev.noemt.client.utils.map.utils.ScanUtils
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

object DungeonMap {
    fun init() {
        MapUtils.init()
        ScanUtils.init()
        DungeonScanner.init()
        DungeonTree.init()
        ScoreCalculation.init()
        MapUpdater.init()
        MapRenderer.init()

        EventBus.register<RenderWorldEvent> {
            val config = ConfigManager.config.map
            if (!config.mapEnabled || !LocationUtils.inDungeon || LocationUtils.inBoss) return@register

            val mimicRoom = DungeonScanner.mimicRoom
            if (config.mimicEsp && !ScoreCalculation.mimicKilled && mimicRoom != null) {
                for (chestPos in mimicRoom.trappedChestPositions) {
                    if (!WorldUtils.getStateAt(chestPos).`is`(Blocks.TRAPPED_CHEST)) continue
                    val rotation = mimicRoom.rotation ?: continue
                    val corner = mimicRoom.clayPos ?: continue
                    val relative = ScanUtils.getRelativeCoord(chestPos, corner, rotation)
                    if (mimicRoom.data.secretCoords.chest.none { it == relative }) continue

                    val box = AABB(chestPos)
                    event.ctx.renderBoxBounds(
                        box,
                        config.mimicEspColor.getEffectiveColour(),
                        outline = true,
                        fill = true,
                        phase = true
                    )
                }
            }

            if (!config.boxDoors) return@register
            val shouldHideUndiscovered = !config.dungeonMapCheater || DungeonListener.dungeonStarted

            for (tile in DungeonScanner.dungeonList) {
                if (tile !is DoorTile || tile.opened) continue
                if (tile.type != DoorType.BLOOD && tile.type != DoorType.WITHER) continue
                if (shouldHideUndiscovered && tile.state == RoomState.UNDISCOVERED && !DungeonTree.isFairy(tile)) continue

                val color = if (tile.type.keys > 0) config.doorKeyColor.getEffectiveColour() else config.doorNoKeyColor.getEffectiveColour()
                event.ctx.renderBoxBounds(
                    tile.aabb,
                    color,
                    outline = config.boxDoorsMode == 0 || config.boxDoorsMode == 2,
                    fill = config.boxDoorsMode == 1 || config.boxDoorsMode == 2,
                    phase = true
                )
            }
        }
    }
}
