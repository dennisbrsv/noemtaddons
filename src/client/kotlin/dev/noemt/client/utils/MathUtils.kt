package dev.noemt.client.utils

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

object MathUtils {
    fun normalizeYaw(value: Float): Float = Mth.wrapDegrees(value)
    fun normalizePitch(num: Float): Float = num.coerceIn(-90f, 90f)

    fun lerp(prev: Number, newPos: Number, partialTicks: Number): Double {
        return prev.toDouble() + (newPos.toDouble() - prev.toDouble()) * partialTicks.toDouble()
    }

    fun lerpColor(color1: Color, color2: Color, value: Number) = Color(
        lerp(color1.red, color2.red, value).toInt().coerceIn(0, 255),
        lerp(color1.green, color2.green, value).toInt().coerceIn(0, 255),
        lerp(color1.blue, color2.blue, value).toInt().coerceIn(0, 255),
        lerp(color1.alpha, color2.alpha, value).toInt().coerceIn(0, 255)
    )

    fun interpolateYaw(startYaw: Float, targetYaw: Float, progress: Float): Float {
        var delta = (targetYaw - startYaw) % 360
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return startYaw + delta * progress
    }

    fun BlockPos.add(x: Number = 0, y: Number = 0, z: Number = 0) = this.offset(x.toInt(), y.toInt(), z.toInt())
    fun BlockPos.toVec() = vec(x, y, z)

    fun Vec3.toPos() = BlockPos(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
    fun Vec3.add(x: Number = 0.0, y: Number = 0.0, z: Number = 0.0) = add(vec(x, y, z))
    fun Vec3i.destructured() = Triple(x, y, z)
    fun Vec3.destructured() = Triple(x, y, z)

    fun vec(x: Number, y: Number, z: Number) = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
    fun aabb(x1: Number, y1: Number, z1: Number, x2: Number, y2: Number, z2: Number): AABB {
        return AABB(x1.toDouble(), y1.toDouble(), z1.toDouble(), x2.toDouble(), y2.toDouble(), z2.toDouble())
    }

    fun Color.invert() = Color(255 - red, 255 - green, 255 - blue, alpha)
}
