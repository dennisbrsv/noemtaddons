package dev.noemt.client.utils

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.*

object MouseRotationHelper {
    private val mc: Minecraft get() = Minecraft.getInstance()

    var targetVec: Vec3? = null
        private set
    var speedMultiplier: Float = 1.0f

    var isSuppressed: Boolean = false

    // Current camera physics state
    private var currentVelYaw = 0.0
    private var currentVelPitch = 0.0
    private var lastUpdateTime = System.nanoTime()

    // Human curve & micro-noise state
    private var noiseTime = 0.0
    private var curveBiasYaw = 0.0
    private var curveBiasPitch = 0.0
    private var lastTargetYaw = 0f
    private var lastTargetPitch = 0f

    fun normalizeYaw(value: Float): Float = Mth.wrapDegrees(value)
    fun normalizePitch(value: Float): Float = value.coerceIn(-90f, 90f)

    fun calcYawPitch(from: Vec3, to: Vec3): Pair<Float, Float> {
        val delta = to.subtract(from)
        val diffXZ = sqrt(delta.x * delta.x + delta.z * delta.z)
        val yaw = -atan2(delta.x, delta.z) * (180.0 / Math.PI)
        val pitch = -atan2(delta.y, diffXZ) * (180.0 / Math.PI)
        return Pair(yaw.toFloat(), pitch.toFloat())
    }

    fun setTarget(target: Vec3, speed: Float = 1.0f) {
        val player = mc.player
        if (targetVec == null && player != null) {
            val (tYaw, tPitch) = calcYawPitch(player.eyePosition, target)
            val dYaw = normalizeYaw(tYaw - player.yRot)
            val dPitch = normalizePitch(tPitch - player.xRot)
            // Generate a subtle human wrist pivot curve (slight arc rather than laser-straight line)
            val perp = if (abs(dYaw) > 5f) (if (dYaw > 0) 1.0 else -1.0) * (0.8 + Math.random() * 0.6) else 0.0
            curveBiasYaw = -dPitch * 0.04 * perp
            curveBiasPitch = dYaw * 0.03 * perp
        }
        targetVec = target
        speedMultiplier = speed.coerceIn(0.4f, 2.5f)
    }

    fun clearTarget() {
        targetVec = null
        currentVelYaw = 0.0
        currentVelPitch = 0.0
        curveBiasYaw = 0.0
        curveBiasPitch = 0.0
    }

    fun hasTarget(): Boolean = targetVec != null

    fun isAimingAt(target: Vec3, maxAngleDiff: Float = 5.5f): Boolean {
        val player = mc.player ?: return false
        val (targetYaw, targetPitch) = calcYawPitch(player.eyePosition, target)
        val yawDiff = abs(normalizeYaw(targetYaw - player.yRot))
        val pitchDiff = abs(normalizePitch(targetPitch - player.xRot))
        return yawDiff <= maxAngleDiff && pitchDiff <= maxAngleDiff
    }

    fun onRenderFrame() {
        val now = System.nanoTime()
        val dt = ((now - lastUpdateTime) / 1_000_000_000.0).coerceIn(0.001, 0.05)
        lastUpdateTime = now

        if (isSuppressed) return

        updateRotation(dt)
    }

    fun updateRotation(dt: Double) {
        val player = mc.player ?: return
        val target = targetVec ?: return

        val (targetYaw, targetPitch) = calcYawPitch(player.eyePosition, target)
        lastTargetYaw = targetYaw
        lastTargetPitch = targetPitch

        var rawYawDiff = normalizeYaw(targetYaw - player.yRot).toDouble()
        var rawPitchDiff = normalizePitch(targetPitch - player.xRot).toDouble()

        val totalDist = sqrt(rawYawDiff * rawYawDiff + rawPitchDiff * rawPitchDiff)
        if (totalDist < 0.06) {
            currentVelYaw = 0.0
            currentVelPitch = 0.0
            return
        }

        // Minecraft mouse sensitivity GCD (quantized to real mouse steps)
        val sens = mc.options.sensitivity().get().toFloat() * 0.6f + 0.2f
        val gcd = (sens * sens * sens * 1.2f).toDouble().coerceAtLeast(0.0005)

        // Apply wrist arc curve when moving towards target
        val arcFactor = (totalDist / 45.0).coerceIn(0.0, 1.0)
        val curvedYawDiff = rawYawDiff + (curveBiasYaw * arcFactor)
        val curvedPitchDiff = rawPitchDiff + (curveBiasPitch * arcFactor)
        val curvedDist = sqrt(curvedYawDiff * curvedYawDiff + curvedPitchDiff * curvedPitchDiff).coerceAtLeast(0.01)

        // Minimum Jerk & Fitts's Law human speed profile:
        // - High speed during ballistic sweep phase (~260-340 deg/s)
        // - Soft cubic deceleration tail as crosshair nears the target
        val baseMaxSpeed = when {
            totalDist > 90.0 -> 270.0 * speedMultiplier
            totalDist > 30.0 -> 320.0 * speedMultiplier
            else -> 260.0 * speedMultiplier
        }

        // Smooth S-curve deceleration (Fitts's Law correction phase)
        val speedFactor = when {
            totalDist > 35.0 -> 1.0
            totalDist > 8.0 -> {
                val t = (totalDist - 8.0) / 27.0
                // Smooth hermite interpolation: 3t^2 - 2t^3
                0.30 + 0.70 * (t * t * (3.0 - 2.0 * t))
            }
            else -> {
                val t = (totalDist / 8.0).coerceIn(0.05, 1.0)
                0.08 + 0.22 * (t.pow(1.15))
            }
        }

        val targetSpeed = baseMaxSpeed * speedFactor

        val dirYaw = curvedYawDiff / curvedDist
        val dirPitch = (curvedPitchDiff / curvedDist) * 0.92 // Human pitch moves slightly slower than yaw

        val desiredVelYaw = dirYaw * targetSpeed
        val desiredVelPitch = dirPitch * targetSpeed

        // Human neuromuscular response damping (spring-damper acceleration)
        val accel = 16.0 * dt
        currentVelYaw += (desiredVelYaw - currentVelYaw) * accel.coerceIn(0.08, 0.92)
        currentVelPitch += (desiredVelPitch - currentVelPitch) * accel.coerceIn(0.08, 0.92)

        // Physiological micro-tremor (subtle 10Hz human hand micro-noise, <0.04 deg)
        noiseTime += dt * 10.0
        val microTremorYaw = sin(noiseTime * 3.7) * 0.03 * (if (totalDist < 5.0) 0.5 else 1.0)
        val microTremorPitch = cos(noiseTime * 4.3) * 0.02 * (if (totalDist < 5.0) 0.5 else 1.0)

        var stepYaw = (currentVelYaw * dt) + microTremorYaw
        var stepPitch = (currentVelPitch * dt) + microTremorPitch

        // Prevent overshooting beyond target
        if (abs(stepYaw) > abs(rawYawDiff)) stepYaw = rawYawDiff
        if (abs(stepPitch) > abs(rawPitchDiff)) stepPitch = rawPitchDiff

        // Quantize strictly to mouse sensitivity GCD steps (identical to raw hardware mouse packets)
        val mouseStepsX = round(stepYaw / gcd)
        val mouseStepsY = round(stepPitch / gcd)

        val finalDeltaYaw = if (mouseStepsX != 0.0) mouseStepsX * gcd else stepYaw
        val finalDeltaPitch = if (mouseStepsY != 0.0) mouseStepsY * gcd else stepPitch

        val newYaw = normalizeYaw((player.yRot + finalDeltaYaw).toFloat())
        val newPitch = normalizePitch((player.xRot + finalDeltaPitch).toFloat())

        player.yRot = newYaw
        player.xRot = newPitch
        player.yHeadRot = newYaw
        player.yBodyRot = newYaw
        player.forceSetRotation(newYaw, false, newPitch, false)
    }
}
