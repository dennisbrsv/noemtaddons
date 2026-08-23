package dev.noemt.client.utils.pathfinder

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

class PathNode(
    val pos: BlockPos,
    var parent: PathNode? = null,
    var action: PathAction = PathAction.WALK,
    var gCost: Double = 0.0,
    var hCost: Double = 0.0,
    var actionData: Any? = null
) : Comparable<PathNode> {
    val fCost: Double get() = gCost + hCost
    val packedPos: Long get() = pos.asLong()

    val centerVec: Vec3 get() = Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
    val standingVec: Vec3 get() = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
    val eyeVec: Vec3 get() = Vec3(pos.x + 0.5, pos.y + 1.62, pos.z + 0.5)

    override fun compareTo(other: PathNode): Int {
        val cmp = this.fCost.compareTo(other.fCost)
        return if (cmp != 0) cmp else this.hCost.compareTo(other.hCost)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathNode) return false
        return pos == other.pos
    }

    override fun hashCode(): Int = pos.hashCode()

    override fun toString(): String = "PathNode(pos=(${pos.x}, ${pos.y}, ${pos.z}), action=$action, f=${"%.2f".format(fCost)})"
}
