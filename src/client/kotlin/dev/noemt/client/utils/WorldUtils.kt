package dev.noemt.client.utils

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3

object WorldUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun getStateAt(pos: BlockPos) = mc.level?.getBlockState(pos) ?: Blocks.AIR.defaultBlockState()
    fun getStateAt(x: Int, y: Int, z: Int) = getStateAt(BlockPos(x, y, z))
    fun getBlockAt(pos: BlockPos) = getStateAt(pos).block
    fun getBlockAt(vec3: Vec3) = getBlockAt(BlockPos(vec3.x.toInt(), vec3.y.toInt(), vec3.z.toInt()))
    fun getBlockAt(x: Number, y: Number, z: Number) = getBlockAt(BlockPos(x.toInt(), y.toInt(), z.toInt()))

    fun isChunkLoaded(x: Number, z: Number): Boolean {
        val level = mc.level ?: return false
        val cx = x.toInt() shr 4
        val cz = z.toInt() shr 4
        return level.hasChunk(cx, cz)
    }

    fun getBlockEntityList(): List<BlockPos> {
        val player = mc.player ?: return emptyList()
        val level = mc.level ?: return emptyList()
        val renderDistance = mc.options.renderDistance().get()
        val pX = player.chunkPosition().x
        val pZ = player.chunkPosition().z

        return buildList {
            for (x in (pX - renderDistance)..(pX + renderDistance)) {
                for (z in (pZ - renderDistance)..(pZ + renderDistance)) {
                    if (!level.hasChunk(x, z)) continue
                    val chunk = level.getChunk(x, z)
                    addAll(chunk.blockEntitiesPos)
                }
            }
        }
    }
}
