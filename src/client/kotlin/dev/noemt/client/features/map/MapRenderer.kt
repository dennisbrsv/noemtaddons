package dev.noemt.client.features.map

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.RenderOverlayEvent
import dev.noemt.client.render.Render2D.drawBorder
import dev.noemt.client.render.Render2D.drawCenteredString
import dev.noemt.client.render.Render2D.drawPlayerHead
import dev.noemt.client.render.Render2D.drawRect
import dev.noemt.client.render.Render2D.drawTexture
import dev.noemt.client.render.RenderHelper.renderVec
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.ItemUtils.skyblockId
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.MathUtils.lerpColor
import dev.noemt.client.utils.MathUtils.normalizeYaw
import dev.noemt.client.utils.dungeon.DungeonClass
import dev.noemt.client.utils.dungeon.DungeonPlayer
import dev.noemt.client.utils.map.core.*
import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.handlers.HotbarMapScanner
import dev.noemt.client.utils.map.handlers.ScoreCalculation
import dev.noemt.client.utils.map.utils.MapUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import java.awt.Color

object MapRenderer {
    private const val MOD_ID = "noemtaddons"
    private val checkmarkGreen = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/checkmarks/green_check.png")
    private val checkmarkWhite = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/checkmarks/white_check.png")
    private val checkmarkUnknown = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/checkmarks/question.png")
    private val checkmarkFail = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/checkmarks/cross.png")
    private val ownPlayerMarker = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/marker.png")

    fun init() {
        EventBus.register<RenderOverlayEvent> {
            val config = ConfigManager.config.map
            if (!config.mapEnabled) return@register
            if (!LocationUtils.inDungeon) return@register
            if (LocationUtils.inBoss && config.mapHideInBoss) return@register

            val ctx = event.context
            ctx.pose().pushMatrix()
            ctx.pose().translate(config.mapX, config.mapY)
            ctx.pose().scale(config.mapScale)

            draw(ctx)

            ctx.pose().popMatrix()
        }
    }

    private fun draw(ctx: GuiGraphicsExtractor) {
        val config = ConfigManager.config.map
        renderBackground(ctx)
        ctx.pose().translate(MapUtils.startCorner.first.toFloat(), MapUtils.startCorner.second.toFloat())
        applyCheater()
        renderRooms(ctx)
        renderText(ctx)
        ctx.pose().translate(-MapUtils.startCorner.first.toFloat(), -MapUtils.startCorner.second.toFloat())
        renderPlayerHeads(ctx)
        if (config.mapExtraInfo) renderExtraInfo(ctx)
    }

    private fun renderBackground(ctx: GuiGraphicsExtractor) {
        val config = ConfigManager.config.map
        val width = 128
        val height = if (config.mapExtraInfo) 140f else 128f

        ctx.drawRect(0, 0, width, height, config.mapBackground.getEffectiveColour())
        ctx.drawBorder(0, 0, width, height, config.mapBorderColor.getEffectiveColour(), config.mapBorderWidth)
    }

    private fun renderExtraInfo(ctx: GuiGraphicsExtractor) {
        val config = ConfigManager.config.map
        if (!config.mapExtraInfo) return
        if (!config.dungeonMapCheater && !DungeonListener.dungeonStarted) return

        val secretsStr = "§6Secrets: §b${ScoreCalculation.foundSecrets}§f/§e${DungeonScanner.secretCount}"
        val cryptsStr = "§6Crypts: ${ScoreCalculation.cryptsCount}"
        val scoreColor = if (ScoreCalculation.score >= 300) "§a" else if (ScoreCalculation.score >= 270) "§e" else "§c"
        val scoreStr = "§eScore: $scoreColor${ScoreCalculation.score}§r"
        val deathsStr = "§cDeaths: ${ScoreCalculation.deathCount}§r"
        val bonusStr = buildString {
            append(if (ScoreCalculation.mimicKilled) "§aM §f| " else "§cM §f| ")
            append(if (ScoreCalculation.princeKilled) "§aP §f| " else "§cP §f| ")
            append(if (ScoreCalculation.batKilled) "§aB" else "§cB")
        }

        val line1 = "$secretsStr    $cryptsStr"
        val line2 = "$scoreStr   $deathsStr   $bonusStr"

        ctx.pose().pushMatrix()
        ctx.pose().translate(64f, 128f)
        ctx.drawCenteredString(line1, 0f, -4f, scale = 0.7f)
        ctx.drawCenteredString(line2, 0f, 4f, scale = 0.7f)
        ctx.pose().popMatrix()
    }

    private fun applyCheater() {
        val config = ConfigManager.config.map
        if (!config.dungeonMapCheater) return
        DungeonScanner.dungeonList.forEach { tile ->
            if (tile.state == RoomState.UNOPENED) tile.state = RoomState.UNDISCOVERED
        }
    }

    private fun getDoorState(door: DoorTile): RoomState {
        if (door.roomTileIndices.size != 2) return RoomState.UNDISCOVERED
        if (door.roomTiles.any { it.state == RoomState.UNDISCOVERED }) return RoomState.UNDISCOVERED
        return RoomState.UNOPENED
    }

    private fun renderRooms(ctx: GuiGraphicsExtractor) {
        val config = ConfigManager.config.map
        val connectorSize = (HotbarMapScanner.quarterRoom.takeUnless { it == -1 } ?: 4)

        for (y in 0..10) for (x in 0..10) {
            val tile = DungeonScanner.dungeonList[y * 11 + x].takeUnless { it is Unknown } ?: continue
            if (tile.state == RoomState.UNDISCOVERED && !config.dungeonMapCheater) continue
            if (tile is DoorTile && getDoorState(tile) == RoomState.UNDISCOVERED && !config.dungeonMapCheater) continue

            var color = tile.getColor()
            if (config.dungeonMapCheater && tile.state == RoomState.UNDISCOVERED) {
                color = color.darker().darker()
            }

            if (tile is RoomTile && tile.uniqueRoom?.hasMimic == true && config.highlightMimicRoom) {
                color = lerpColor(color, config.colorMimic.getEffectiveColour(), 0.2)
            }

            val xOffset = (x shr 1) * (MapUtils.mapRoomSize + connectorSize)
            val yOffset = (y shr 1) * (MapUtils.mapRoomSize + connectorSize)

            val xEven = x and 1 == 0
            val yEven = y and 1 == 0

            when {
                xEven && yEven -> if (tile is RoomTile) {
                    ctx.drawRect(
                        xOffset,
                        yOffset,
                        MapUtils.mapRoomSize,
                        MapUtils.mapRoomSize,
                        color
                    )
                }

                !xEven && !yEven -> {
                    ctx.drawRect(
                        xOffset,
                        yOffset,
                        MapUtils.mapRoomSize + connectorSize,
                        MapUtils.mapRoomSize + connectorSize,
                        color
                    )
                }

                else -> drawRoomConnector(
                    ctx, xOffset, yOffset, connectorSize, tile is DoorTile, !xEven, color
                )
            }

            if (tile is RoomTile && tile.data.isUnknown()) {
                val checkmarkSize = config.checkmarkSize * 10
                drawCheckmark(
                    ctx, tile,
                    xOffset + MapUtils.mapRoomSize / 2 - checkmarkSize / 2,
                    yOffset + MapUtils.mapRoomSize / 2 - checkmarkSize / 2,
                    checkmarkSize,
                )
            }
        }
    }

    private fun renderText(ctx: GuiGraphicsExtractor) {
        val config = ConfigManager.config.map
        val mc = Minecraft.getInstance()
        val roomSize = MapUtils.mapRoomSize.toFloat()
        val gapSize = HotbarMapScanner.quarterRoom.toFloat()
        val halfRoom = HotbarMapScanner.halfRoom.toFloat()
        val fullCellSize = roomSize + gapSize

        DungeonScanner.uniqueRooms.values.forEach { unq ->
            val roomTile = unq.mainRoom

            if (unq.data.isUnknown()) return@forEach
            if (unq.data.type == RoomType.ENTRANCE) return@forEach
            if (!config.dungeonMapCheater && (roomTile.state == RoomState.UNDISCOVERED || roomTile.state == RoomState.UNOPENED)) return@forEach

            val checkPos = unq.getCheckmarkPosition()
            val cX = (checkPos.first / 2f) * fullCellSize + halfRoom
            val cY = (checkPos.second / 2f) * fullCellSize + halfRoom

            val color = when (roomTile.state) {
                RoomState.GREEN -> Color(85, 255, 85)
                RoomState.FAILED -> Color(255, 0, 0)
                RoomState.CLEARED -> Color(255, 255, 255)
                else -> Color(170, 170, 170)
            }

            val showName = config.showRoomNames || config.checkmarkStyle in listOf(2, 3)
            val showSecrets = (config.showSecretsOnMap || config.checkmarkStyle in listOf(1, 3)) && roomTile.data.secrets > 0

            if (showName) {
                var scale = config.textScale

                if (config.limitRoomNameSize) {
                    unq.updateBounds(roomSize, gapSize)
                    val secretsText = if (showSecrets) "${unq.foundSecrets}/${roomTile.data.secrets}" else ""
                    val maxLineW = unq.updateTextScale(1f, showSecrets, secretsText)
                    var totalH = unq.cacheSplitName.size * mc.font.lineHeight.toFloat()
                    if (showSecrets) totalH += mc.font.lineHeight

                    if (maxLineW > 0 && totalH > 0) {
                        val sW = unq.cachedMaxWidth / maxLineW
                        val sH = unq.cachedMaxHeight / totalH
                        scale = sW.coerceAtMost(sH).coerceIn(0.39f, config.textScale)
                    }
                }

                val totalLines = unq.cacheSplitName.size + (if (showSecrets) 1 else 0)
                val totalH = totalLines * mc.font.lineHeight * scale

                var currentY = cY - totalH / 2

                for (line in unq.cacheSplitName) {
                    ctx.drawCenteredString(line, cX, currentY, color, scale)
                    currentY += totalH / totalLines
                }

                if (showSecrets) {
                    val secStr = "${unq.foundSecrets}/${roomTile.data.secrets}"
                    ctx.drawCenteredString(secStr, cX, currentY, color, scale)
                }
            } else if (showSecrets) {
                ctx.drawCenteredString(
                    if (roomTile.data.secrets == 0) "0" else "${unq.foundSecrets}/${roomTile.data.secrets}",
                    cX,
                    cY - mc.font.lineHeight / 2,
                    color,
                    config.textScale
                )
            } else {
                val checkmarkSize = config.checkmarkSize * 10
                val halfCheckmark = checkmarkSize / 2
                drawCheckmark(ctx, unq.mainRoom, cX - halfCheckmark, cY - halfCheckmark, checkmarkSize)
            }
        }
    }

    private fun renderPlayerHeads(ctx: GuiGraphicsExtractor) {
        if (LocationUtils.inBoss) return

        DungeonListener.dungeonTeammatesNoSelf.forEach { player ->
            if (player.isDead) return@forEach
            drawPlayerHead(ctx, player)
        }

        drawPlayerHead(ctx, DungeonListener.thePlayer ?: return)
    }

    private fun drawCheckmark(ctx: GuiGraphicsExtractor, tile: Tile, x: Number, y: Number, size: Number) {
        val config = ConfigManager.config.map
        val checkmark = when (tile.state) {
            RoomState.CLEARED -> checkmarkWhite
            RoomState.GREEN -> checkmarkGreen
            RoomState.FAILED -> checkmarkFail
            RoomState.UNOPENED -> if (!config.hideQuestionCheckmarks) checkmarkUnknown else return
            else -> return
        }

        ctx.drawTexture(checkmark, x, y, size, size)
    }

    private fun drawPlayerHead(ctx: GuiGraphicsExtractor, teammate: DungeonPlayer) {
        val config = ConfigManager.config.map
        val mc = Minecraft.getInstance()
        val entity = teammate.entity

        val (x, z, yaw) = if (entity == null || !entity.isAlive) {
            Triple(teammate.mapX, teammate.mapZ, teammate.yaw)
        } else {
            val (mx, mz) = MapUtils.coordsToMap(entity.renderVec)
            Triple(mx, mz, entity.yRot)
        }

        val borderColor = if (config.mapPlayerHeadColorClassBased) teammate.clazz.color
        else config.mapPlayerHeadColor.getEffectiveColour()

        val nameColor = if (config.mapPlayerNameClassColorBased && teammate.clazz != DungeonClass.Empty) teammate.clazz.color
        else Color.WHITE

        ctx.pose().pushMatrix()
        ctx.pose().translate(x, z)
        val currentYaw = normalizeYaw(yaw)
        val headYaw = Math.toRadians((currentYaw + 180).toDouble()).toFloat()

        ctx.pose().rotate(headYaw)
        ctx.pose().scale(config.playerHeadScale)

        if (config.mapVanillaMarker && teammate == DungeonListener.thePlayer) {
            ctx.drawTexture(ownPlayerMarker, -6, -6, 12, 12, config.mapVanillaMarkerColor.getEffectiveColour())
        } else {
            ctx.drawBorder(-7, -7, 14, 14, borderColor)
            ctx.drawPlayerHead(-6, -6, 12, teammate.skin)
        }

        val heldItem = mc.player?.mainHandItem
        val shouldDrawName = config.playerNames == 2 || (config.playerNames == 1
            && (heldItem != null && (heldItem.skyblockId == "SPIRIT_LEAP" || heldItem.skyblockId == "INFINITE_SPIRIT_LEAP"
            || heldItem.skyblockId == "HAUNT_ABILITY")))

        if (shouldDrawName) {
            ctx.pose().rotate(-headYaw)
            ctx.pose().translate(0f, 8f)
            ctx.pose().scale(config.playerNameScale)
            ctx.drawCenteredString(teammate.name, 0, 0, nameColor)
        }

        ctx.pose().popMatrix()
    }

    private fun drawRoomConnector(
        matrices: GuiGraphicsExtractor, x: Int, y: Int, doorWidth: Int, doorway: Boolean, vertical: Boolean, color: Color
    ) {
        val doorwayOffset = if (MapUtils.mapRoomSize == 16) 5 else 6
        val doorHeight = if (doorway) 6 else MapUtils.mapRoomSize
        var x1 = if (vertical) x + MapUtils.mapRoomSize else x
        var y1 = if (vertical) y else y + MapUtils.mapRoomSize
        if (doorway) {
            if (vertical) y1 += doorwayOffset else x1 += doorwayOffset
        }

        matrices.drawRect(
            x1,
            y1,
            if (vertical) doorWidth else doorHeight,
            if (vertical) doorHeight else doorWidth,
            color
        )
    }
}
