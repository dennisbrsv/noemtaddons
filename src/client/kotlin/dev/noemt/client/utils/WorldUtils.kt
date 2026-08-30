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

        val minChunkX: Int
        val maxChunkX: Int
        val minChunkZ: Int
        val maxChunkZ: Int

        if (LocationUtils.inDungeon) {
            // Dungeon grid is bounded within startX (-185) to +15 -> chunks -13..2
            minChunkX = -13
            maxChunkX = 2
            minChunkZ = -13
            maxChunkZ = 2
        } else {
            val renderDistance = mc.options.renderDistance().get().coerceAtMost(8)
            val pX = player.chunkPosition().x
            val pZ = player.chunkPosition().z
            minChunkX = pX - renderDistance
            maxChunkX = pX + renderDistance
            minChunkZ = pZ - renderDistance
            maxChunkZ = pZ + renderDistance
        }

        val list = ArrayList<BlockPos>(128)
        for (x in minChunkX..maxChunkX) {
            for (z in minChunkZ..maxChunkZ) {
                if (!level.hasChunk(x, z)) continue
                val chunk = level.getChunk(x, z)
                list.addAll(chunk.blockEntitiesPos)
            }
        }
        return list
    }
}
