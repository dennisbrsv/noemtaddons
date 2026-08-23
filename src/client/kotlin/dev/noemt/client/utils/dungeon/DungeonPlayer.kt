package dev.noemt.client.utils.dungeon

import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.utils.MapUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

data class DungeonPlayer(
    var name: String,
    var clazz: DungeonClass,
    var clazzLvl: Int,
    var skin: Identifier = Minecraft.getInstance().player?.skin?.body?.texturePath() ?: Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png"),
    var isDead: Boolean = false,
) {
    var entity: AbstractClientPlayer? = null
        set(value) {
            skin = value?.skin?.body?.texturePath() ?: skin
            field = value
        }

    var mapX = 0f
    var mapZ = 0f
    var yaw = 0f
    var icon = ""

    fun getRealPos() = Vec3(
        (mapX - MapUtils.startCorner.first) / MapUtils.coordMultiplier + DungeonScanner.startX - 15,
        entity?.y ?: 0.0,
        (mapZ - MapUtils.startCorner.second) / MapUtils.coordMultiplier + DungeonScanner.startZ - 15
    )
}
