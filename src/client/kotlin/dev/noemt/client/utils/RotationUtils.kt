package dev.noemt.client.utils

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.*

object RotationUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    data class Rotation(var yaw: Float, var pitch: Float)

    var targetRotation: Rotation? = null
    var aimSpeedMultiplier: Float = 1.0f

    fun normalizeYaw(value: Float): Float = Mth.wrapDegrees(value)
    fun normalizePitch(value: Float): Float = value.coerceIn(-90f, 90f)

    fun calcYawPitch(target: Vec3): Rotation {
        val player = mc.player ?: return Rotation(0f, 0f)
        val eyePos = player.eyePosition
        return calcYawPitch(eyePos, target)
    }

    fun calcYawPitch(from: Vec3, to: Vec3): Rotation {
        val delta = to.subtract(from)
        val diffXZ = sqrt(delta.x * delta.x + delta.z * delta.z)
        val yaw = -atan2(delta.x, delta.z) * (180.0 / Math.PI)
        val pitch = -atan2(delta.y, diffXZ) * (180.0 / Math.PI)
        return Rotation(yaw.toFloat(), pitch.toFloat())
    }

    fun getLookVec(yaw: Float, pitch: Float): Vec3 {
        val f = pitch * (Math.PI.toFloat() / 180f)
        val g = -yaw * (Math.PI.toFloat() / 180f)
        val h = cos(g)
        val i = sin(g)
        val j = cos(f)
        val k = sin(f)
        return Vec3((i * j).toDouble(), (-k).toDouble(), (h * j).toDouble())
    }

    fun fixRot(rot: Rotation, lastRot: Rotation): Rotation {
        val sensitivity = mc.options.sensitivity().get().toFloat() * 0.6f + 0.2f
        val gcd = sensitivity * sensitivity * sensitivity * 1.2f

        val dYaw = rot.yaw - lastRot.yaw
        val dPitch = rot.pitch - lastRot.pitch

        val fixedDYaw = dYaw - (dYaw % gcd)
        val fixedDPitch = dPitch - (dPitch % gcd)

        val fixedYaw = lastRot.yaw + fixedDYaw
        val fixedPitch = (lastRot.pitch + fixedDPitch).coerceIn(-90f, 90f)

        return Rotation(fixedYaw, fixedPitch)
    }

    fun rotate(yaw: Float, pitch: Float) {
        val player = mc.player ?: return
        val currentYaw = player.yRot
        val currentPitch = player.xRot

        val normYaw = currentYaw + normalizeYaw(yaw - currentYaw)
        val normPitch = normalizePitch(pitch)

        val fixed = fixRot(Rotation(normYaw, normPitch), Rotation(currentYaw, currentPitch))

        player.yRot = fixed.yaw
        player.xRot = fixed.pitch
        player.yHeadRot = fixed.yaw

        val bodyYawDiff = Mth.wrapDegrees(fixed.yaw - player.yBodyRot)
        val maxAngle = 45.0f
        if (bodyYawDiff < -maxAngle) {
            player.yBodyRot = fixed.yaw + maxAngle
        } else if (bodyYawDiff > maxAngle) {
            player.yBodyRot = fixed.yaw - maxAngle
        }
        player.forceSetRotation(fixed.yaw, false, fixed.pitch, false)
    }

    fun setTarget(target: Vec3, speedMultiplier: Float = 1.0f) {
        targetRotation = calcYawPitch(target)
        aimSpeedMultiplier = speedMultiplier
    }

    fun clearTarget() {
        targetRotation = null
    }

    fun tickSmoothRotation(): Boolean {
        val player = mc.player ?: return false
        val target = targetRotation ?: return false

        val currentYaw = player.yRot
        val currentPitch = player.xRot

        val yawDiff = normalizeYaw(target.yaw - currentYaw)
        val pitchDiff = normalizePitch(target.pitch - currentPitch)

        val distance = sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff)
        if (distance < 0.3f) {
            rotate(target.yaw, target.pitch)
            return true
        }

        val speed = (0.28f * aimSpeedMultiplier.coerceIn(0.2f, 3.0f)).coerceIn(0.08f, 0.75f)
        val stepYaw = yawDiff * speed
        val stepPitch = pitchDiff * speed

        val nextYaw = currentYaw + stepYaw
        val nextPitch = currentPitch + stepPitch

        rotate(nextYaw, nextPitch)
        return distance < 4.0f
    }

    fun isAimingAt(target: Vec3, maxAngleDiff: Float = 5.0f): Boolean {
        val player = mc.player ?: return false
        val targetRot = calcYawPitch(target)
        val yawDiff = abs(normalizeYaw(targetRot.yaw - player.yRot))
        val pitchDiff = abs(normalizePitch(targetRot.pitch - player.xRot))
        return yawDiff <= maxAngleDiff && pitchDiff <= maxAngleDiff
    }
}
