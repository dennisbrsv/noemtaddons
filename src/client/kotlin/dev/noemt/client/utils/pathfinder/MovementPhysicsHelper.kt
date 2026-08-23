package dev.noemt.client.utils.pathfinder

import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import kotlin.math.max

object MovementPhysicsHelper {
    private val mc: Minecraft get() = Minecraft.getInstance()

    val jumpBoostLevel: Int
        get() {
            val player = mc.player ?: return 0
            val effect = player.getEffect(MobEffects.JUMP_BOOST) ?: return 0
            return effect.amplifier + 1
        }

    val speedLevel: Int
        get() {
            val player = mc.player ?: return 0
            val effect = player.getEffect(MobEffects.SPEED) ?: return 0
            return effect.amplifier + 1
        }

    val maxStepUpHeight: Int
        get() {
            val jb = jumpBoostLevel
            return when {
                jb >= 3 -> 3
                jb >= 2 -> 2
                else -> 1
            }
        }

    val maxHorizontalGapJump: Int
        get() {
            val player = mc.player ?: return 2
            val speedAttr = player.getAttributeValue(Attributes.MOVEMENT_SPEED)
            val speedFactor = speedAttr / 0.1 // 1.0 = base, 4.0 = speed 400

            val baseReach = when {
                speedFactor >= 3.5 -> 4
                speedFactor >= 2.0 -> 3
                else -> 2
            }

            return if (jumpBoostLevel > 0) baseReach + 1 else baseReach
        }

    val horizontalSpeedBlocksPerTick: Double
        get() {
            val player = mc.player ?: return 0.28
            val baseSprint = 0.28
            val speedAttr = player.getAttributeValue(Attributes.MOVEMENT_SPEED)
            val multiplier = speedAttr / 0.1
            return max(baseSprint, baseSprint * multiplier)
        }

    const val maxSafeDropHeight: Int = 16
}
