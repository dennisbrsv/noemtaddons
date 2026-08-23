package dev.noemt.client.utils.pathfinder

import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.MouseRotationHelper
import dev.noemt.client.utils.NumbersUtils.toFixed
import dev.noemt.client.utils.PlayerUtils
import dev.noemt.client.utils.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.*

object PathExecutor {
    private val mc: Minecraft get() = Minecraft.getInstance()

    enum class State {
        IDLE,
        FOLLOWING,
        TELEPORTING_ETHERWARP,
        TELEPORTING_INSTANT,
        REPOSITIONING,
        ARRIVED,
        STUCK
    }

    var currentState: State = State.IDLE
        private set

    var currentPath: List<PathNode> = emptyList()
        private set

    var targetGoal: BlockPos? = null
        private set

    private var currentWaypointIndex = 0
    private var stuckTicks = 0
    private var actionDelayTicks = 0
    private var lastPlayerPos: Vec3 = Vec3.ZERO
    private var teleportWaitTicks = 0
    private var retryCount = 0
    private var repositionTicks = 0
    private var consecutiveRepositionCount = 0
    private var waypointProgressTicks = 0
    private var totalReplanAttempts = 0
    private var activeTeleportTarget: BlockPos? = null
    private var activeBlinkTarget: Vec3? = null
    private var preBlinkPos: Vec3 = Vec3.ZERO

    fun startPath(path: List<PathNode>, goal: BlockPos) {
        if (path.isEmpty()) {
            stop()
            return
        }
        currentPath = path
        targetGoal = goal
        currentWaypointIndex = 0
        stuckTicks = 0
        actionDelayTicks = 0
        teleportWaitTicks = 0
        retryCount = 0
        repositionTicks = 0
        consecutiveRepositionCount = 0
        waypointProgressTicks = 0
        totalReplanAttempts = 0
        activeTeleportTarget = null
        activeBlinkTarget = null
        lastPlayerPos = mc.player?.position() ?: Vec3.ZERO
        preBlinkPos = lastPlayerPos
        currentState = State.FOLLOWING

        val startPos = BlockPos.containing(lastPlayerPos.x, lastPlayerPos.y, lastPlayerPos.z)
        PathfinderFlightRecorder.startSession(startPos, goal, path)
    }

    fun stop() {
        if (currentState != State.IDLE) {
            PathfinderFlightRecorder.record("STOP", "Navigation stopped/cleared.")
        }
        currentState = State.IDLE
        currentPath = emptyList()
        targetGoal = null
        currentWaypointIndex = 0
        stuckTicks = 0
        actionDelayTicks = 0
        teleportWaitTicks = 0
        retryCount = 0
        repositionTicks = 0
        consecutiveRepositionCount = 0
        waypointProgressTicks = 0
        totalReplanAttempts = 0
        activeTeleportTarget = null
        activeBlinkTarget = null
        releaseMovementKeys()
        MouseRotationHelper.clearTarget()
    }

    fun tick() {
        val player = mc.player
        if (player == null || !player.isAlive || currentState == State.IDLE || currentState == State.ARRIVED) {
            if (currentState != State.IDLE) stop()
            return
        }

        PathfinderFlightRecorder.tick()

        // Pacing delay between actions
        if (actionDelayTicks > 0) {
            actionDelayTicks--
            releaseMovementKeys()
            return
        }

        val playerPos = player.position()

        // Check if final destination has been reached
        val goal = targetGoal
        if (goal != null) {
            val goalCenter = Vec3(goal.x + 0.5, goal.y + 1.0, goal.z + 0.5)
            val distToGoal = playerPos.distanceTo(goalCenter)
            if (distToGoal <= 2.2) {
                PathfinderFlightRecorder.record("ARRIVED", "Reached final destination (${distToGoal.toFixed(2)}m away).")
                currentState = State.ARRIVED
                stop()
                return
            }
        }

        // Validate index bounds
        if (currentWaypointIndex >= currentPath.size) {
            val lastNode = currentPath.lastOrNull()
            if (lastNode != null) {
                val distToLast = playerPos.distanceTo(lastNode.standingVec)
                if (distToLast <= 2.2) {
                    PathfinderFlightRecorder.record("ARRIVED", "All waypoints completed.")
                    currentState = State.ARRIVED
                    stop()
                    return
                }
            } else {
                currentState = State.ARRIVED
                stop()
                return
            }
        }

        // Active Repositioning / Unstick Maneuver
        if (currentState == State.REPOSITIONING) {
            handleRepositioning()
            return
        }

        // Fail Detection: Horizontal (X/Z only) Stationary Stuck Counter
        val dx = playerPos.x - lastPlayerPos.x
        val dz = playerPos.z - lastPlayerPos.z
        val horizontalMovedDistSq = dx * dx + dz * dz

        if (horizontalMovedDistSq < 0.0015 && currentState == State.FOLLOWING) {
            stuckTicks++

            // Tick 4 (200ms): Attempt active repositioning (up to 2 times max)
            if (stuckTicks == 4) {
                if (consecutiveRepositionCount < 2) {
                    consecutiveRepositionCount++
                    PathfinderFlightRecorder.record("STUCK_DETECTED", "Horizontal progress stuck for 4 ticks (Attempt #$consecutiveRepositionCount). Initiating unstick.")
                    startRepositioning()
                    return
                } else {
                    // Reposition failed 2 times! Trigger fast dynamic replan or abort immediately
                    handleStuckFailure("Stuck on obstacle after 2 reposition attempts.")
                    return
                }
            }
        } else if (horizontalMovedDistSq >= 0.01) {
            stuckTicks = 0
        }
        lastPlayerPos = playerPos

        // Waypoint Stagnation Timeout: 35 ticks (1.75 seconds max) on the same waypoint
        if (currentState == State.FOLLOWING) {
            waypointProgressTicks++
            if (waypointProgressTicks >= 35) {
                handleStuckFailure("Timed out on waypoint #$currentWaypointIndex after ${waypointProgressTicks} ticks.")
                return
            }
        }

        // Handle Active Etherwarp Teleportation Lifecycle
        if (currentState == State.TELEPORTING_ETHERWARP) {
            handleActiveEtherwarp()
            return
        }

        // Handle Active Instant Transmission Teleportation Lifecycle
        if (currentState == State.TELEPORTING_INSTANT) {
            handleActiveInstantTransmission()
            return
        }

        // Process current waypoint checkpoint
        val currentTargetNode = currentPath[currentWaypointIndex]
        val targetStandingVec = currentTargetNode.standingVec
        val distToTarget = playerPos.distanceTo(targetStandingVec)

        // Checkpoint arrival validation: If close enough to waypoint, advance!
        if (distToTarget <= 2.0 && abs(player.y - targetStandingVec.y) <= 1.6) {
            PathfinderFlightRecorder.record("CHECKPOINT_PASSED", "Reached waypoint #$currentWaypointIndex (${distToTarget.toFixed(2)}m away).")
            currentWaypointIndex++
            waypointProgressTicks = 0
            consecutiveRepositionCount = 0
            stuckTicks = 0
            actionDelayTicks = 2
            if (currentWaypointIndex >= currentPath.size) {
                currentState = State.ARRIVED
                stop()
                return
            }
            return
        }

        val profile = TeleportAbilityHelper.getBestTeleportItem()

        when (currentTargetNode.action) {
            PathAction.ETHERWARP -> {
                val etherTarget = (currentTargetNode.actionData as? BlockPos) ?: currentTargetNode.pos
                PathfinderFlightRecorder.setLastTarget(currentTargetNode, etherTarget)
                if (profile != null && profile.hasEtherwarp) {
                    initiateEtherwarp(etherTarget, profile.slot)
                } else {
                    handlePhysicalMovement(currentTargetNode)
                }
            }
            PathAction.INSTANT_TRANSMISSION -> {
                val blinkTarget = (currentTargetNode.actionData as? Vec3) ?: currentTargetNode.standingVec
                val blinkPos = BlockPos.containing(blinkTarget.x, blinkTarget.y, blinkTarget.z)
                PathfinderFlightRecorder.setLastTarget(currentTargetNode, blinkPos)
                if (profile != null) {
                    initiateInstantTransmission(blinkTarget, profile.slot)
                } else {
                    handlePhysicalMovement(currentTargetNode)
                }
            }
            else -> {
                PathfinderFlightRecorder.setLastTarget(currentTargetNode, currentTargetNode.pos)
                handlePhysicalMovement(currentTargetNode)
            }
        }
    }

    private fun handleStuckFailure(reason: String) {
        val goal = targetGoal
        val player = mc.player
        if (goal != null && player != null && totalReplanAttempts < 1) {
            totalReplanAttempts++
            PathfinderFlightRecorder.record("REPLAN_TRIGGERED", "$reason Attempting live dynamic replan from (${player.blockPosition().x}, ${player.blockPosition().y}, ${player.blockPosition().z})...")
            val newStart = player.blockPosition()
            val newPath = TeleportPathfinder.findTeleportPath(newStart, goal)
            if (newPath != null && newPath.isNotEmpty()) {
                PathfinderFlightRecorder.record("REPLAN_SUCCESS", "Dynamic replan found new path with ${newPath.size} nodes.")
                ChatUtils.modMessage("&e[Pathfinder] Path blocked. Recalculated new route!")
                val preservedAttempts = totalReplanAttempts
                startPath(newPath, goal)
                totalReplanAttempts = preservedAttempts
                return
            }
        }

        PathfinderFlightRecorder.record("ABORT_FAIL", "Fatal Navigation Failure: $reason")
        ChatUtils.modMessage("&c[Pathfinder] Navigation stopped: $reason")
        stop()
    }

    private fun startRepositioning() {
        currentState = State.REPOSITIONING
        repositionTicks = 0
        releaseMovementKeys()
        mc.options.keyJump.isDown = true
        mc.options.keyDown.isDown = true // Step back from wall
        PathfinderFlightRecorder.record("REPOSITION_START", "Stuck detected. Starting 10-tick unstick maneuver.")
    }

    private fun handleRepositioning() {
        repositionTicks++

        // Ticks 1..3: Jump and step back away from wall/corner
        if (repositionTicks in 1..3) {
            mc.options.keyJump.isDown = true
            mc.options.keyDown.isDown = true
            mc.options.keyLeft.isDown = false
            mc.options.keyRight.isDown = false
            mc.options.keyUp.isDown = false
        }
        // Ticks 4..7: Strafe sideways with jump to clear door frames / cauldrons
        else if (repositionTicks in 4..7) {
            mc.options.keyDown.isDown = false
            mc.options.keyLeft.isDown = (repositionTicks % 2 == 0)
            mc.options.keyRight.isDown = (repositionTicks % 2 != 0)
            mc.options.keyJump.isDown = true
        }
        // Ticks 8..10: Step forward towards target
        else if (repositionTicks in 8..10) {
            mc.options.keyLeft.isDown = false
            mc.options.keyRight.isDown = false
            mc.options.keyDown.isDown = false
            mc.options.keyUp.isDown = true
            mc.options.keyJump.isDown = false
        }

        if (repositionTicks >= 10) {
            releaseMovementKeys()
            currentState = State.FOLLOWING
            stuckTicks = 0
            actionDelayTicks = 2
            PathfinderFlightRecorder.record("REPOSITION_DONE", "Repositioning finished after 10 ticks. Resuming navigation.")
        }
    }

    private fun initiateEtherwarp(targetBlock: BlockPos, slot: Int) {
        val player = mc.player ?: return
        currentState = State.TELEPORTING_ETHERWARP
        activeTeleportTarget = targetBlock
        teleportWaitTicks = 0
        retryCount = 0

        if (player.inventory.selectedSlot != slot) {
            PlayerUtils.swapToSlot(slot)
        }

        // Release directional movement keys, but ENGAGE and HOLD sneak
        mc.options.keyUp.isDown = false
        mc.options.keyDown.isDown = false
        mc.options.keyLeft.isDown = false
        mc.options.keyRight.isDown = false
        mc.options.keyJump.isDown = false
        mc.options.keySprint.isDown = false
        mc.options.keyShift.isDown = true
        player.isShiftKeyDown = true

        // Lock rotation directly to center of target block
        val aimPos = Vec3(targetBlock.x + 0.5, targetBlock.y + 0.5, targetBlock.z + 0.5)
        val rot = RotationUtils.calcYawPitch(player.eyePosition, aimPos)
        RotationUtils.rotate(rot.yaw, rot.pitch)
        MouseRotationHelper.setTarget(aimPos, speed = 3.5f)

        PathfinderFlightRecorder.record("ETHERWARP_INIT", "Target=(${targetBlock.x}, ${targetBlock.y}, ${targetBlock.z}) | AimYaw=${rot.yaw.toFixed(1)}, AimPitch=${rot.pitch.toFixed(1)}")
    }

    private fun handleActiveEtherwarp() {
        val player = mc.player ?: return
        val targetBlock = activeTeleportTarget ?: run {
            currentState = State.FOLLOWING
            mc.options.keyShift.isDown = false
            player.isShiftKeyDown = false
            return
        }

        val aimPos = Vec3(targetBlock.x + 0.5, targetBlock.y + 0.5, targetBlock.z + 0.5)
        val rot = RotationUtils.calcYawPitch(player.eyePosition, aimPos)
        RotationUtils.rotate(rot.yaw, rot.pitch)

        // Continuously hold sneak throughout the entire Etherwarp transaction
        mc.options.keyShift.isDown = true
        player.isShiftKeyDown = true
        mc.options.keyUp.isDown = false
        mc.options.keyDown.isDown = false
        mc.options.keyLeft.isDown = false
        mc.options.keyRight.isDown = false

        teleportWaitTicks++

        val targetStanding = Vec3(targetBlock.x + 0.5, targetBlock.y + 1.0, targetBlock.z + 0.5)
        val distToTarget = player.position().distanceTo(targetStanding)

        // Check if player has physically arrived at the checkpoint
        if (distToTarget <= 2.5 && abs(player.y - targetStanding.y) <= 1.8) {
            PathfinderFlightRecorder.record("ETHERWARP_SUCCESS", "Arrived at target block after $teleportWaitTicks ticks (${distToTarget.toFixed(2)}m).")
            mc.options.keyShift.isDown = false
            player.isShiftKeyDown = false
            activeTeleportTarget = null
            teleportWaitTicks = 0
            retryCount = 0
            currentState = State.FOLLOWING
            currentWaypointIndex++
            actionDelayTicks = 6 // Human delay after landing
            return
        }

        // Hold shift for 4 ticks to ensure server registers sneak state, then fire right-click
        if (teleportWaitTicks == 4) {
            val profile = TeleportAbilityHelper.getBestTeleportItem()
            if (profile != null && player.inventory.selectedSlot != profile.slot) {
                PlayerUtils.swapToSlot(profile.slot)
            }
            PathfinderFlightRecorder.record("ETHERWARP_CLICK", "Fired useItem with AOTV.")
            TeleportAbilityHelper.useHeldItem()
        }

        // If not arrived after 14 ticks, handle retry / fail detection
        if (teleportWaitTicks >= 14) {
            if (retryCount < 1) {
                retryCount++
                teleportWaitTicks = 2
                PathfinderFlightRecorder.record("ETHERWARP_RETRY", "No position update after 14 ticks. Retrying cast (Attempt #2).")
                val profile = TeleportAbilityHelper.getBestTeleportItem()
                if (profile != null && player.inventory.selectedSlot != profile.slot) {
                    PlayerUtils.swapToSlot(profile.slot)
                }
                TeleportAbilityHelper.useHeldItem()
            } else {
                // Fail detection: Etherwarp failed after 2 attempts
                PathfinderFlightRecorder.record("ETHERWARP_FAIL", "Etherwarp failed/blocked after 2 attempts. Auto-aborting.")
                ChatUtils.modMessage("&c[Pathfinder] Etherwarp failed (blocked sightline or server reject). Aborted.")
                stop()
            }
        }
    }

    private fun initiateInstantTransmission(blinkVec: Vec3, slot: Int) {
        val player = mc.player ?: return
        currentState = State.TELEPORTING_INSTANT
        activeBlinkTarget = blinkVec
        teleportWaitTicks = 0
        retryCount = 0
        preBlinkPos = player.position()

        if (player.inventory.selectedSlot != slot) {
            PlayerUtils.swapToSlot(slot)
        }

        releaseMovementKeys()
        val aimPos = blinkVec
        val rot = RotationUtils.calcYawPitch(player.eyePosition, aimPos)
        RotationUtils.rotate(rot.yaw, rot.pitch)
        MouseRotationHelper.setTarget(aimPos, speed = 3.5f)

        PathfinderFlightRecorder.record("BLINK_INIT", "Blink Target=(${blinkVec.x.toFixed(1)}, ${blinkVec.y.toFixed(1)}, ${blinkVec.z.toFixed(1)}) | AimYaw=${rot.yaw.toFixed(1)}, AimPitch=${rot.pitch.toFixed(1)}")
    }

    private fun handleActiveInstantTransmission() {
        val player = mc.player ?: return
        val blinkVec = activeBlinkTarget ?: run {
            currentState = State.FOLLOWING
            return
        }

        val aimPos = blinkVec
        val rot = RotationUtils.calcYawPitch(player.eyePosition, aimPos)
        RotationUtils.rotate(rot.yaw, rot.pitch)
        releaseMovementKeys()

        teleportWaitTicks++

        if (teleportWaitTicks == 1) {
            PathfinderFlightRecorder.record("BLINK_CLICK", "Fired Instant Transmission right-click.")
            TeleportAbilityHelper.useHeldItem()
        }

        val movedDist = player.position().distanceTo(preBlinkPos)
        if (movedDist >= 1.5) {
            PathfinderFlightRecorder.record("BLINK_SUCCESS", "Blink successful after $teleportWaitTicks ticks (moved ${movedDist.toFixed(2)}m).")
            activeBlinkTarget = null
            teleportWaitTicks = 0
            retryCount = 0
            currentState = State.FOLLOWING
            currentWaypointIndex++

            val nextNode = currentPath.getOrNull(currentWaypointIndex)
            actionDelayTicks = if (nextNode?.action == PathAction.INSTANT_TRANSMISSION) 1 else 3
            return
        }

        if (teleportWaitTicks >= 8) {
            if (retryCount < 1) {
                retryCount++
                teleportWaitTicks = 0 // Will fire again next tick
                PathfinderFlightRecorder.record("BLINK_RETRY", "No position update after 8 ticks. Retrying blink cast (Attempt #2).")
            } else {
                PathfinderFlightRecorder.record("BLINK_BLOCKED", "Blink moved only ${movedDist.toFixed(2)}m after 2 attempts. Aborting.")
                ChatUtils.modMessage("&c[Pathfinder] Blink failed (blocked by wall/ceiling or server lag). Aborted.")
                stop()
            }
        }
    }

    private fun handlePhysicalMovement(targetNode: PathNode) {
        val player = mc.player ?: return
        val targetVec = targetNode.standingVec
        val dx = targetVec.x - player.x
        val dz = targetVec.z - player.z
        val dy = targetVec.y - player.y
        val distHorizontalSq = dx * dx + dz * dz
        val totalDist = player.position().distanceTo(targetVec)

        val speedFactor = MovementPhysicsHelper.horizontalSpeedBlocksPerTick / 0.28
        val waypointRadius = (0.85 * speedFactor).coerceIn(0.75, 2.0)

        // Checkpoint arrival check
        if (distHorizontalSq <= waypointRadius * waypointRadius && abs(dy) <= 1.6) {
            currentWaypointIndex++
            if (currentWaypointIndex >= currentPath.size) {
                currentState = State.ARRIVED
                stop()
                return
            }
            return
        }

        val aimPoint = Vec3(targetVec.x, player.eyeY, targetVec.z)
        MouseRotationHelper.setTarget(aimPoint, speed = 2.5f)

        // Directly move forward toward the target waypoint without lateral strafing
        mc.options.keyUp.isDown = true
        mc.options.keyDown.isDown = false
        mc.options.keyLeft.isDown = false
        mc.options.keyRight.isDown = false
        mc.options.keySprint.isDown = totalDist > 0.8

        val isStepUp = dy > 0.35 || targetNode.action == PathAction.JUMP_UP
        val isGapJump = targetNode.action == PathAction.SPRINT_JUMP
        val isBlocked = player.horizontalCollision || isBlockDirectlyInFront()
        val shouldJump = isStepUp || isGapJump || isBlocked

        mc.options.keyJump.isDown = shouldJump

        // Force forward momentum and sprint whenever jumping
        if (shouldJump) {
            mc.options.keyUp.isDown = true
            mc.options.keySprint.isDown = true
            mc.options.keyDown.isDown = false
        }
    }

    private fun isBlockDirectlyInFront(): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val look = RotationUtils.getLookVec(player.yRot, 0f).scale(0.65)
        val checkPos = BlockPos.containing(player.x + look.x, player.y + 0.3, player.z + look.z)
        val state = level.getBlockState(checkPos)
        return !state.isAir && !state.getCollisionShape(level, checkPos).isEmpty
    }

    private fun releaseMovementKeys() {
        mc.options.keyUp.isDown = false
        mc.options.keyDown.isDown = false
        mc.options.keyLeft.isDown = false
        mc.options.keyRight.isDown = false
        mc.options.keyJump.isDown = false
        mc.options.keySprint.isDown = false
        mc.options.keyShift.isDown = false
    }
}
