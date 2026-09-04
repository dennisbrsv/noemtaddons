package dev.noemt.client.features.map

import dev.noemt.client.BuildConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
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

object DungeonMap : Module {
    override val id = "dungeon_map"
    override val name = "Dungeon Map"
    override val description = "Custom Dungeon Map overlay and tracking"
    override val type = ModuleType.LEGIT

    override fun init() {
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
            if (BuildConstants.isCheatBuild && config.mimicEsp && !ScoreCalculation.mimicKilled && mimicRoom != null) {
                val mimicColor = config.mimicEspColor.getEffectiveColour()
                for (chestPos in mimicRoom.trappedChestPositions) {
                    if (!WorldUtils.getStateAt(chestPos).`is`(Blocks.TRAPPED_CHEST)) continue
                    val rotation = mimicRoom.rotation ?: continue
                    val corner = mimicRoom.clayPos ?: continue
                    val relative = ScanUtils.getRelativeCoord(chestPos, corner, rotation)
                    if (mimicRoom.data.secretCoords.chest.none { it == relative }) continue

                    val cx = chestPos.x.toDouble()
                    val cy = chestPos.y.toDouble()
                    val cz = chestPos.z.toDouble()
                    event.ctx.renderBoxBounds(
                        cx, cy, cz, cx + 1.0, cy + 1.0, cz + 1.0,
                        mimicColor,
                        outline = true,
                        fill = true,
                        phase = true
                    )
                }
            }

            if (!BuildConstants.isCheatBuild || !config.boxDoors) return@register
            val shouldHideUndiscovered = !config.dungeonMapCheater || DungeonListener.dungeonStarted
            val keyColor = config.doorKeyColor.getEffectiveColour()
            val noKeyColor = config.doorNoKeyColor.getEffectiveColour()
            val outlineMode = config.boxDoorsMode == 0 || config.boxDoorsMode == 2
            val fillMode = config.boxDoorsMode == 1 || config.boxDoorsMode == 2

            for (tile in DungeonScanner.dungeonList) {
                if (tile !is DoorTile || tile.opened) continue
                if (tile.type != DoorType.BLOOD && tile.type != DoorType.WITHER) continue
                if (shouldHideUndiscovered && tile.state == RoomState.UNDISCOVERED && !DungeonTree.isFairy(tile)) continue

                val color = if (tile.type.keys > 0) keyColor else noKeyColor
                event.ctx.renderBoxBounds(
                    tile.aabb,
                    color,
                    outline = outlineMode,
                    fill = fillMode,
                    phase = true
                )
            }
        }
    }
}
