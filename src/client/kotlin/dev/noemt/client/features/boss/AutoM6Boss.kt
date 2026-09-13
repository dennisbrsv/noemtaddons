package dev.noemt.client.features.boss

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.DungeonEvent
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.render.Render3D.renderBlock
import dev.noemt.client.render.Render3D.renderString
import dev.noemt.client.utils.AOTVHelper
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.ItemUtils.skyblockId
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.MouseRotationHelper
import dev.noemt.client.utils.NumbersUtils.toFixed
import dev.noemt.client.utils.PathfindingUtils
import dev.noemt.client.utils.PlayerUtils
import dev.noemt.client.utils.TerracottaTimer
import dev.noemt.client.utils.ThreadUtils
import dev.noemt.client.utils.WorldUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Automated M6/F6 Boss Fight Module:
 *
 * Coordinates:
 *   - SPAWN:       BlockPos(-9, 69, 1)   — Where player lands inside boss room
 *   - TERRACOTTA:  BlockPos(-12, 69, 35) — Position for Terracotta phase
 *   - GIANT:       BlockPos(-17, 84, 75) — Position for Giants phase (Etherwarp target)
 *   - SADAN:       BlockPos(-9, 69, 80)  — Position for Sadan final phase
 *
 * Sequence of Events:
 * 1. Entry / Spawn:
 *    - Walk to TERRACOTTA position (-12, 69, 35).
 * 2. Terracotta Phase:
 *    - Timer begins on Sadan dialogue:
 *      "[BOSS] Sadan: So you made it all the way here...and you wish to defy me? Pitiful."
 *    - Cast 1 (1st wave): Exactly 13.0 seconds (260 ticks) after start, look 90° down and left-click Gyrokinetic Wand.
 *    - Cast 2 & 3: Subsequent waves when multiple terracottas are about to spawn in near proximity (<= 15 blocks, timer <= 2.0s / 40 ticks).
 *    - Repeats until Giants phase begins.
 * 3. Giants Phase:
 *    - Triggered by "[BOSS] Sadan: My giants! Unleashed!" or "[BOSS] Sadan: ENOUGH!"
 *    - Walk forward towards GIANT (-17, 84, 75) and Etherwarp onto it as soon as in range.
 *    - At exactly 8.2s (164 ticks) from Giants dialogue, start swinging with configured blood weapon.
 *    - Target primary giant until it despawns/dies, then aim and swing at any remaining giants.
 * 4. Sadan Phase:
 *    - Triggered when Giants are over and Sadan cleared split starts.
 *    - Walk/pathfind to SADAN position (-9, 69, 80).
 *    - At 11.5s (230 ticks) from phase start: Look 90° down and left-click Gyrokinetic Wand to stun Sadan.
 *    - Immediately look up towards Sadan and swing left-click weapon until defeated.
 */
object AutoM6Boss : Module {
    override val id = "auto_m6_boss"
    override val name = "Auto M6 Boss"
    override val description = "Automated M6/F6 boss fight positioning, Gyro wand, and giant/Sadan combat"
    override val type = ModuleType.CHEAT

    private val mc: Minecraft get() = Minecraft.getInstance()

    val POS_SPAWN = BlockPos(-9, 69, 1)
    val POS_TERRACOTTA = BlockPos(-12, 69, 35)
    val POS_GIANT = BlockPos(-17, 84, 75)
    val POS_SADAN = BlockPos(-9, 69, 80)

    enum class BossPhase {
        IDLE,
        WALK_TO_TERRACOTTA,
        TERRACOTTA,
        MOVING_TO_GIANT,
        GIANTS_COMBAT,
        WALK_TO_SADAN,
        SADAN_COMBAT,
        FINISHED
    }

    var currentPhase = BossPhase.IDLE
        private set

    // Tick markers
    private var terracottaStartTick = -1L
    private var giantsStartTick = -1L
    private var sadanStartTick = -1L

    // Gyro tracking
    private var terracottaGyroCount = 0
    private var lastGyroTick = -1L
    private var sadanGyroDone = false

    // Giants combat tracking
    private var lastAotvTick = -1L
    private var attackCooldownTicks = 0

    // Pathfinding state
    private var currentPath = mutableListOf<BlockPos>()
    private var pathIndex = 0
    private var targetBlockPos: BlockPos? = null
    private var pathJob: Job? = null

    override fun init() {
        register<WorldChangeEvent> {
            reset()
        }

        register<DungeonEvent.RunStatedEvent> {
            reset()
        }

        register<DungeonEvent.RunEndedEvent> {
            reset()
        }

        register<ChatMessageEvent> {
            if (!isEnabled() || !LocationUtils.inDungeon) return@register
            val text = event.unformattedText.removeFormatting().trim()

            when {
                // Terracotta Phase Trigger
                text.contains("[BOSS] Sadan: So you made it all the way here...and you wish to defy me? Pitiful.", ignoreCase = true) ||
                text.contains("you wish to defy me? Pitiful.", ignoreCase = true) -> {
                    if (currentPhase == BossPhase.IDLE || currentPhase == BossPhase.WALK_TO_TERRACOTTA) {
                        terracottaStartTick = DungeonListener.currentTime
                        terracottaGyroCount = 0
                        currentPhase = BossPhase.TERRACOTTA
                        ChatUtils.modMessage("&6[Auto M6 Boss] &aTerracotta phase started! First Gyro in exactly 13.0s.")
                    }
                }

                // Giants Phase Trigger
                text.contains("[BOSS] Sadan: My giants! Unleashed!", ignoreCase = true) ||
                text.contains("[BOSS] Sadan: ENOUGH!", ignoreCase = true) ||
                text.contains("My giants! Unleashed!", ignoreCase = true) -> {
                    if (currentPhase == BossPhase.TERRACOTTA || currentPhase == BossPhase.IDLE) {
                        giantsStartTick = DungeonListener.currentTime
                        currentPhase = BossPhase.MOVING_TO_GIANT
                        ChatUtils.modMessage("&6[Auto M6 Boss] &eGiants phase started! Moving & Etherwarping to (-17, 84, 75).")
                    }
                }

                // Sadan Phase Trigger (Giants dead / Cleared split)
                text.contains("[BOSS] Sadan: You did it. You pushed me to this point.", ignoreCase = true) ||
                text.contains("[BOSS] Sadan: NOOOOO!", ignoreCase = true) ||
                text.contains("[BOSS] Sadan: I would be lying if I said I didn't see this coming.", ignoreCase = true) ||
                text.contains("NOOOOO! I will not be defeated!", ignoreCase = true) -> {
                    if (currentPhase == BossPhase.GIANTS_COMBAT || currentPhase == BossPhase.MOVING_TO_GIANT) {
                        startSadanPhase()
                    }
                }

                // Sadan Defeated
                text.contains("[BOSS] Sadan: FATHER, FORGIVE ME!!!", ignoreCase = true) ||
                text.contains("[BOSS] Sadan: FATHER", ignoreCase = true) -> {
                    ChatUtils.modMessage("&6[Auto M6 Boss] &aM6 Boss defeated! Resetting state.")
                    currentPhase = BossPhase.FINISHED
                    PathfindingUtils.stopMovement()
                    MouseRotationHelper.clearTarget()
                }
            }
        }

        register<TickEvent.Start> {
            if (!isEnabled()) return@register
            val player = mc.player ?: return@register

            // Only run on Floor 6 / Master 6 and while inside the boss room
            if (LocationUtils.dungeonFloorNumber != 6 || !LocationUtils.inBoss) {
                if (currentPhase != BossPhase.IDLE) {
                    reset()
                }
                return@register
            }

            if (DungeonListener.thePlayer?.isDead == true || !player.isAlive) {
                PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                return@register
            }

            if (attackCooldownTicks > 0) attackCooldownTicks--

            // Initial detection: player spawned inside boss room
            if (currentPhase == BossPhase.IDLE) {
                val distToSpawn = player.blockPosition().distManhattan(POS_SPAWN)
                val distToTerra = player.blockPosition().distManhattan(POS_TERRACOTTA)
                if (distToSpawn <= 16 || distToTerra > 3) {
                    currentPhase = BossPhase.WALK_TO_TERRACOTTA
                    navigateTo(POS_TERRACOTTA)
                    ChatUtils.modMessage("&6[Auto M6 Boss] &aBoss room entered! Walking to Terracotta position (-12, 69, 35)...")
                }
            }

            val currentTick = DungeonListener.currentTime

            when (currentPhase) {
                BossPhase.WALK_TO_TERRACOTTA -> {
                    tickPathMovement()
                    val dist = player.position().distanceTo(Vec3(POS_TERRACOTTA.x + 0.5, POS_TERRACOTTA.y.toDouble(), POS_TERRACOTTA.z + 0.5))
                    if (dist < 1.2) {
                        PathfindingUtils.stopMovement()
                    }
                }

                BossPhase.TERRACOTTA -> {
                    val terraVec = Vec3(POS_TERRACOTTA.x + 0.5, POS_TERRACOTTA.y.toDouble(), POS_TERRACOTTA.z + 0.5)
                    val dist = player.position().distanceTo(terraVec)
                    if (dist > 1.4) {
                        PathfindingUtils.moveTo(terraVec, sprint = false)
                    } else {
                        PathfindingUtils.stopMovement()
                    }

                    // Gyro Cast Logic:
                    // 1st Gyro: Exactly 13.0s (260 ticks) after Terracotta start
                    if (terracottaGyroCount == 0 && terracottaStartTick > 0) {
                        val elapsed = currentTick - terracottaStartTick
                        if (elapsed >= 260) {
                            castGyroDown {
                                terracottaGyroCount = 1
                                lastGyroTick = currentTick
                                ChatUtils.modMessage("&6[Auto M6 Boss] &bCast 1st Gyro at 13.0s!")
                            }
                        }
                    } else if (terracottaGyroCount in 1..2 && currentTick - lastGyroTick > 80) {
                        // 2nd & 3rd Gyro: When near-proximity terracottas are about to spawn (<= 6 blocks, timer <= 40 ticks / 2.0s)
                        val imminentNearbyTerras = TerracottaTimer.terracottaSpawns.count { (pos, expireTick) ->
                            val pDist = hypot(player.x - (pos.x + 0.5), player.z - (pos.z + 0.5))
                            val remaining = expireTick - currentTick
                            pDist <= 6.0 && remaining in 0..40
                        }

                        if (imminentNearbyTerras >= 3) {
                            val nextCount = terracottaGyroCount + 1
                            castGyroDown {
                                terracottaGyroCount = nextCount
                                lastGyroTick = currentTick
                                ChatUtils.modMessage("&6[Auto M6 Boss] &bCast Gyro #$nextCount for wave of $imminentNearbyTerras terracottas!")
                            }
                        }
                    }
                }

                BossPhase.MOVING_TO_GIANT -> {
                    // Etherwarp onto POS_GIANT (-17, 84, 75)
                    val giantTop = Vec3(POS_GIANT.x + 0.5, POS_GIANT.y + 1.0, POS_GIANT.z + 0.5)
                    val eyePos = player.eyePosition
                    val dist = eyePos.distanceTo(giantTop)

                    if (dist in 4.0..14.5 && PathfindingUtils.hasLineOfSight(eyePos, giantTop)) {
                        MouseRotationHelper.setTarget(giantTop, 1.8f)
                        if (MouseRotationHelper.isAimingAt(giantTop, 4.5f) && currentTick - lastAotvTick > 15) {
                            lastAotvTick = currentTick
                            PathfindingUtils.stopMovement()
                            AOTVHelper.castTeleport {
                                currentPhase = BossPhase.GIANTS_COMBAT
                                ChatUtils.modMessage("&6[Auto M6 Boss] &aEtherwarped onto Giant platform! Preparing attack.")
                            }
                        }
                    } else {
                        // If Etherwarp cannot reach, walk closer towards the platform
                        PathfindingUtils.moveTo(giantTop, sprint = true)
                    }
                }

                BossPhase.GIANTS_COMBAT -> {
                    // Hold ground on platform
                    val giantTop = Vec3(POS_GIANT.x + 0.5, POS_GIANT.y.toDouble(), POS_GIANT.z + 0.5)
                    if (player.position().distanceTo(giantTop) > 2.5) {
                        PathfindingUtils.moveTo(giantTop, sprint = false)
                    } else {
                        PathfindingUtils.stopMovement()
                    }

                    // Attack at exactly 8.2 seconds (164 ticks) from Giants start
                    val elapsedSinceGiants = currentTick - giantsStartTick
                    if (elapsedSinceGiants >= 164) {
                        val giants = findAliveGiants()

                        if (giants.isEmpty() && elapsedSinceGiants > 220) {
                            // Giants are dead, advance to Sadan phase
                            startSadanPhase()
                        } else if (giants.isNotEmpty()) {
                            val target = giants.first()
                            val aimPos = target.boundingBox.center
                            MouseRotationHelper.setTarget(aimPos, 1.4f)

                            if (MouseRotationHelper.isAimingAt(aimPos, 18.0f) || player.distanceTo(target) < 6.0) {
                                executeWeaponAttack(target)
                            }
                        }
                    }
                }

                BossPhase.WALK_TO_SADAN -> {
                    val sadanPos = Vec3(POS_SADAN.x + 0.5, POS_SADAN.y.toDouble(), POS_SADAN.z + 0.5)
                    val dist = player.position().distanceTo(sadanPos)

                    if (dist > 1.2) {
                        PathfindingUtils.moveTo(sadanPos, sprint = true)
                    } else {
                        PathfindingUtils.stopMovement()
                    }

                    // At 11.5s (230 ticks) from Sadan phase start, look down and gyro to stun him
                    val elapsed = currentTick - sadanStartTick
                    if (elapsed >= 230 && !sadanGyroDone) {
                        castGyroDown {
                            sadanGyroDone = true
                            currentPhase = BossPhase.SADAN_COMBAT
                            ChatUtils.modMessage("&6[Auto M6 Boss] &bCast Sadan stun Gyro at 11.5s! Looking up and attacking.")
                        }
                    }
                }

                BossPhase.SADAN_COMBAT -> {
                    val sadanEntity = findSadanEntity()
                    if (sadanEntity != null && sadanEntity.isAlive) {
                        val targetPoint = Vec3(sadanEntity.x, sadanEntity.eyeY, sadanEntity.z)
                        MouseRotationHelper.setTarget(targetPoint, 1.6f)

                        if (MouseRotationHelper.isAimingAt(targetPoint, 20.0f) || player.distanceTo(sadanEntity) < 6.0) {
                            executeWeaponAttack(sadanEntity)
                        }
                    } else {
                        // Look straight ahead/up if Sadan hasn't been targeted yet
                        val upLook = Vec3(player.x, player.eyeY + 2.0, player.z + 5.0)
                        MouseRotationHelper.setTarget(upLook, 1.2f)
                        executeWeaponAttack(null)
                    }
                }

                BossPhase.IDLE -> {}
                BossPhase.FINISHED -> {}
            }
        }

        // Render current target waypoint and status overlay
        register<RenderWorldEvent> {
            if (!isEnabled() || currentPhase == BossPhase.IDLE || currentPhase == BossPhase.FINISHED) return@register

            val targetPos = when (currentPhase) {
                BossPhase.WALK_TO_TERRACOTTA, BossPhase.TERRACOTTA -> POS_TERRACOTTA
                BossPhase.MOVING_TO_GIANT, BossPhase.GIANTS_COMBAT -> POS_GIANT
                BossPhase.WALK_TO_SADAN, BossPhase.SADAN_COMBAT -> POS_SADAN
                else -> null
            }

            if (targetPos != null) {
                val color = Color(255, 140, 0)
                event.ctx.renderBlock(targetPos, color, Color(color.red, color.green, color.blue, 50), outline = true, fill = true, phase = true)
                event.ctx.renderString(
                    "§6[M6: ${currentPhase.name}]",
                    Vec3(targetPos.x + 0.5, targetPos.y + 1.5, targetPos.z + 0.5),
                    scale = 1.3f,
                    phase = true
                )
            }
        }
    }

    override fun isEnabled(): Boolean {
        return ConfigManager.config.blood.autoM6Boss
    }

    private fun startSadanPhase() {
        sadanStartTick = DungeonListener.currentTime
        sadanGyroDone = false
        currentPhase = BossPhase.WALK_TO_SADAN
        navigateTo(POS_SADAN)
        ChatUtils.modMessage("&6[Auto M6 Boss] &aGiants cleared! Moving to Sadan position (-9, 69, 80). Stun Gyro at 11.5s.")
    }

    private fun reset() {
        currentPhase = BossPhase.IDLE
        terracottaStartTick = -1L
        giantsStartTick = -1L
        sadanStartTick = -1L
        terracottaGyroCount = 0
        lastGyroTick = -1L
        sadanGyroDone = false
        lastAotvTick = -1L
        attackCooldownTicks = 0
        currentPath.clear()
        pathIndex = 0
        targetBlockPos = null
        pathJob?.cancel()
        pathJob = null
        PathfindingUtils.stopMovement()
        MouseRotationHelper.clearTarget()
    }

    /**
     * Look 90° straight down and perform Gyrokinetic Wand Gravity Storm (LEFT CLICK).
     * Does not restore original weapon to avoid instant weapon swapping; weapon is only equipped when swinging (Giants/Sadan).
     */
    private fun castGyroDown(onFinished: () -> Unit) {
        val player = mc.player ?: return
        val gyroSlot = PlayerUtils.findHotbarSlot { it.skyblockId == "GYROKINETIC_WAND" }
        if (gyroSlot == null) {
            ChatUtils.modMessage("&c[Auto M6 Boss] Gyrokinetic Wand not found in hotbar!")
            onFinished()
            return
        }

        val downVec = Vec3(player.x, player.eyeY - 10.0, player.z)
        MouseRotationHelper.setTarget(downVec, 2.5f)

        ThreadUtils.scheduledTask(2) {
            if (player.inventory.selectedSlot != gyroSlot) {
                PlayerUtils.swapToSlot(gyroSlot)
            }
            ThreadUtils.scheduledTask(1) {
                PlayerUtils.leftClick()
                PlayerUtils.swingArm()
                ThreadUtils.scheduledTask(2) {
                    onFinished()
                }
            }
        }
    }

    private fun getPreferredWeaponSlot(): Int {
        val cfgSlot = ConfigManager.config.blood.bloodWeaponSlot
        return if (cfgSlot in 1..9) cfgSlot - 1 else mc.player?.inventory?.selectedSlot ?: 0
    }

    private fun executeWeaponAttack(target: Entity?) {
        val player = mc.player ?: return
        val weaponSlot = getPreferredWeaponSlot()
        if (player.inventory.selectedSlot != weaponSlot) {
            PlayerUtils.swapToSlot(weaponSlot)
        }

        if (attackCooldownTicks > 0) return

        val cps = ConfigManager.config.blood.autoBloodCps.coerceIn(1, 20)
        attackCooldownTicks = (20 / cps).coerceAtLeast(1)

        PlayerUtils.leftClick()
        PlayerUtils.swingArm()
    }

    private fun findAliveGiants(): List<LivingEntity> {
        val level = mc.level ?: return emptyList()
        val player = mc.player ?: return emptyList()

        return level.entitiesForRendering()
            .filterIsInstance<LivingEntity>()
            .filter { entity ->
                if (entity == player || entity is ArmorStand || entity is Player) return@filter false
                if (!entity.isAlive || entity.isRemoved) return@filter false
                val name = entity.name.string.removeFormatting().trim()
                val customName = entity.customName?.string?.removeFormatting()?.trim() ?: ""

                val isGiant = name.contains("Giant", ignoreCase = true) ||
                        customName.contains("Giant", ignoreCase = true) ||
                        name.contains("The Reaper", ignoreCase = true) ||
                        name.contains("The Diamante", ignoreCase = true) ||
                        name.contains("The Laser", ignoreCase = true) ||
                        name.contains("The Bigfoot", ignoreCase = true) ||
                        entity.boundingBox.ysize > 5.0

                isGiant && player.distanceTo(entity) < 35.0
            }
            .sortedBy { player.distanceToSqr(it) }
    }

    private fun findSadanEntity(): LivingEntity? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null

        return level.entitiesForRendering()
            .filterIsInstance<LivingEntity>()
            .filter { entity ->
                if (entity == player || entity is ArmorStand || entity is Player) return@filter false
                if (!entity.isAlive || entity.isRemoved) return@filter false
                val name = entity.name.string.removeFormatting().trim()
                val customName = entity.customName?.string?.removeFormatting()?.trim() ?: ""
                (name.contains("Sadan", ignoreCase = true) || customName.contains("Sadan", ignoreCase = true)) &&
                        player.distanceTo(entity) < 35.0
            }
            .minByOrNull { player.distanceToSqr(it) }
    }

    // ── Simple A* Walking Pathfinder ─────────────────────────────────────

    private fun navigateTo(dest: BlockPos) {
        targetBlockPos = dest
        pathJob?.cancel()
        pathJob = ThreadUtils.coroutineScope.launch {
            val player = mc.player ?: return@launch
            val start = player.blockPosition()
            val path = runAStar(start, dest)
            mc.execute {
                currentPath = path.toMutableList()
                pathIndex = 0
            }
        }
    }

    private fun tickPathMovement() {
        val player = mc.player ?: return
        if (currentPath.isEmpty() || pathIndex >= currentPath.size) {
            val finalTarget = targetBlockPos
            if (finalTarget != null) {
                val vec = Vec3(finalTarget.x + 0.5, finalTarget.y.toDouble(), finalTarget.z + 0.5)
                if (player.position().distanceTo(vec) > 1.2) {
                    PathfindingUtils.moveTo(vec, sprint = true)
                } else {
                    PathfindingUtils.stopMovement()
                }
            }
            return
        }

        val target = currentPath[pathIndex]
        val targetVec = Vec3(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
        val dist = player.position().distanceTo(targetVec)

        if (dist < 1.0) {
            pathIndex++
            if (pathIndex >= currentPath.size) {
                PathfindingUtils.stopMovement()
                return
            }
        }

        PathfindingUtils.moveTo(targetVec, sprint = true)
    }

    private class PathNode(val pos: BlockPos, val parent: PathNode?, val g: Double, val h: Double) : Comparable<PathNode> {
        val f: Double get() = g + h
        override fun compareTo(other: PathNode): Int = f.compareTo(other.f)
    }

    private fun runAStar(start: BlockPos, goal: BlockPos, maxNodes: Int = 4000): List<BlockPos> {
        val open = PriorityQueue<PathNode>()
        val gScores = HashMap<BlockPos, Double>()
        val closed = HashSet<BlockPos>()

        val startNode = PathNode(start, null, 0.0, dist3D(start, goal))
        open.add(startNode)
        gScores[start] = 0.0

        var count = 0
        var closest = startNode

        val dirs = arrayOf(
            BlockPos(1, 0, 0), BlockPos(-1, 0, 0),
            BlockPos(0, 0, 1), BlockPos(0, 0, -1),
            BlockPos(1, 0, 1), BlockPos(-1, 0, 1),
            BlockPos(1, 0, -1), BlockPos(-1, 0, -1)
        )

        while (open.isNotEmpty() && count++ < maxNodes) {
            val current = open.poll() ?: break
            if (current.h < closest.h) closest = current

            if (current.pos == goal || current.pos.distManhattan(goal) <= 1) {
                val list = mutableListOf<BlockPos>()
                var curr: PathNode? = current
                while (curr != null) {
                    list.add(curr.pos)
                    curr = curr.parent
                }
                return list.reversed()
            }

            closed.add(current.pos)

            for (dir in dirs) {
                val isDiag = dir.x != 0 && dir.z != 0
                val hCost = if (isDiag) 1.414 else 1.0

                for (dy in intArrayOf(0, 1, -1, -2)) {
                    val next = current.pos.offset(dir.x, dy, dir.z)
                    if (closed.contains(next)) continue

                    val ground = WorldUtils.getBlockAt(next.below())
                    val feet = WorldUtils.getBlockAt(next)
                    val head = WorldUtils.getBlockAt(next.above())

                    if (ground == Blocks.AIR || ground == Blocks.LAVA || ground == Blocks.FIRE) continue
                    if (feet != Blocks.AIR && feet != Blocks.WATER && feet != Blocks.SHORT_GRASS) continue
                    if (head != Blocks.AIR && head != Blocks.WATER) continue

                    val moveCost = hCost + if (dy > 0) 1.2 else abs(dy) * 0.5
                    val tentativeG = current.g + moveCost

                    if (tentativeG < (gScores[next] ?: Double.MAX_VALUE)) {
                        gScores[next] = tentativeG
                        open.add(PathNode(next, current, tentativeG, dist3D(next, goal)))
                    }
                }
            }
        }

        val fallback = mutableListOf<BlockPos>()
        var curr: PathNode? = closest
        while (curr != null) {
            fallback.add(curr.pos)
            curr = curr.parent
        }
        return fallback.reversed()
    }

    private fun dist3D(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
