package dev.noemt.client.features.blood

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.DungeonEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.utils.*
import dev.noemt.client.utils.map.core.RoomTile
import dev.noemt.client.utils.map.core.RoomType
import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.utils.ScanUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import kotlin.math.abs

object AutoBloodCamp {
    private val mc: Minecraft get() = Minecraft.getInstance()

    var watcherMessageCount = 0
        private set

    var bloodRoomCleared = false
        private set

    private var attackCooldownTicks = 0
    private var teleportPauseTicks = 0
    private var tntReactionDelayTicks = 0
    private var lastAotvTime = 0L
    private var savedWeaponSlot: Int? = null
    private var kp6WasDown = false
    private var kp7WasDown = false

    private val recordedSpawnLocations = mutableListOf<Vec3>()

    private val lividDisablerRegex = Regex("""\[BOSS\] .+ Livid: My shadows are everywhere, THEY WILL FIND YOU!!""", RegexOption.IGNORE_CASE)

    fun init() {
        register<WorldChangeEvent> {
            resetCampState()
        }

        register<DungeonEvent.RunStatedEvent> {
            resetCampState()
        }

        register<ChatMessageEvent> {
            val text = event.unformattedText

            if (text.contains("[BOSS] The Watcher:", ignoreCase = true)) {
                watcherMessageCount++
            }

            // Check disabler conditions
            if (isDisablerMessage(text)) {
                bloodRoomCleared = true
                PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                savedWeaponSlot?.let { PlayerUtils.swapToSlot(it) }
                savedWeaponSlot = null
            }

            // Check reset / re-enable conditions
            if (isResetMessage(text)) {
                resetCampState()
            }
        }

        register<TickEvent.Start> {
            val player = mc.player ?: return@register

            // Handle NUMPAD 6 full debug dump keybind & NUMPAD 7 held item lore dump keybind
            val isKp6Down = InputConstants.isKeyDown(mc.window, GLFW.GLFW_KEY_KP_6)
            if (isKp6Down && !kp6WasDown) {
                DebugUtils.dumpAll()
            }
            kp6WasDown = isKp6Down

            val isKp7Down = InputConstants.isKeyDown(mc.window, GLFW.GLFW_KEY_KP_7)
            if (isKp7Down && !kp7WasDown) {
                DebugUtils.dumpHeldItem()
            }
            kp7WasDown = isKp7Down

            if (attackCooldownTicks > 0) attackCooldownTicks--
            if (teleportPauseTicks > 0) teleportPauseTicks--

            val config = ConfigManager.config.blood

            // 1. Strict Dungeon, Blood Room, Watcher Dialog & Cleared check
            if (!config.autoBloodCamp || bloodRoomCleared || !LocationUtils.inDungeon || LocationUtils.inBoss) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                savedWeaponSlot = null
                return@register
            }

            if (DungeonListener.thePlayer?.isDead == true || !player.isAlive) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                return@register
            }

            // 2. Strict Blood Room check: Feature only works when the player is physically inside the Blood Room!
            if (!isPlayerInBloodRoom()) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                if (savedWeaponSlot != null) {
                    PlayerUtils.swapToSlot(savedWeaponSlot!!)
                    savedWeaponSlot = null
                }
                return@register
            }

            // Only activate once Watcher has sent at least 2 dialogue messages
            if (watcherMessageCount < 2) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                return@register
            }

            // If a teleport is currently happening or within the 14-tick pause window, pause all actions!
            if (AOTVHelper.isTeleporting || teleportPauseTicks > 0) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                return@register
            }

            // Save weapon slot when entering blood room
            if (savedWeaponSlot == null) {
                savedWeaponSlot = player.inventory.selectedSlot
            }

            val preferredSlot = if (config.bloodWeaponSlot in 1..8) {
                config.bloodWeaponSlot - 1
            } else {
                savedWeaponSlot ?: player.inventory.selectedSlot
            }

            if (player.inventory.selectedSlot != preferredSlot) {
                PlayerUtils.swapToSlot(preferredSlot)
            }

            val bloodRoom = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD }
                ?: DungeonScanner.dungeonList.filterIsInstance<RoomTile>().find { it.data.type == RoomType.BLOOD }?.uniqueRoom
                ?: return@register

            fun isDungeonTeammate(p: Player): Boolean {
                if (p == player) return true
                val name = p.gameProfile.name.trim()
                if (mc.user.name.equals(name, ignoreCase = true)) return true
                if (DungeonListener.thePlayer?.name.equals(name, ignoreCase = true)) return true
                return DungeonListener.dungeonTeammates.any { it.name.equals(name, ignoreCase = true) }
            }

            fun isWatcher(entity: LivingEntity): Boolean {
                if (entity == BloodCamp.watcherEntity) return true
                if (entity is Zombie && entity.y > 72.0) return true
                val name = entity.customName?.string ?: (entity as? Player)?.gameProfile?.name ?: ""
                if (name.contains("Watcher", ignoreCase = true)) return true
                return false
            }

            val roomCenter = Vec3(bloodRoom.centerPos.x.toDouble(), 69.0, bloodRoom.centerPos.z.toDouble())

            val entities = mc.level?.entitiesForRendering() ?: return@register
            val tntEntities = entities.filterIsInstance<PrimedTnt>().filter { tnt ->
                isInsideBloodRoom(tnt.position())
            }
            val tntPositions = tntEntities.map { it.position() }

            // 2. Natural TNT Evasion (Walking for short distances, AOTV for emergency / long distances)
            if (config.autoBloodTntEvade && tntEntities.isNotEmpty()) {
                val dangerousTnts = tntEntities.filter { tnt ->
                    player.distanceTo(tnt) < 6.5 && (tnt.onGround() || tnt.deltaMovement.y < 0.1 || tnt.y < 76.0)
                }

                if (dangerousTnts.isNotEmpty()) {
                    val minTntDist: Double = dangerousTnts.minOfOrNull { player.distanceTo(it).toDouble() } ?: 10.0

                    if (tntReactionDelayTicks < 3) {
                        tntReactionDelayTicks++
                        return@register
                    }

                    val now = System.currentTimeMillis()
                    val walkPos = PathfindingUtils.findSafePositionFromTnts(tntPositions, 8.0)

                    val walkDist = walkPos?.let { player.position().distanceTo(Vec3(it.x + 0.5, it.y + 1.0, it.z + 0.5)) } ?: 99.0
                    val shouldUseAotv = config.autoBloodAotv && AOTVHelper.hasAotv() && (minTntDist < 4.0 || walkDist > 5.5) && now - lastAotvTime > 500

                    if (shouldUseAotv) {
                        val safePos = PathfindingUtils.findAotvSafePositionFromTnts(tntPositions, 8.0)
                        if (safePos != null) {
                            val targetPoint = Vec3(safePos.x + 0.5, safePos.y + 0.95, safePos.z + 0.5)
                            MouseRotationHelper.setTarget(targetPoint, config.autoBloodAimSpeed)

                            if (MouseRotationHelper.isAimingAt(targetPoint, 4.5f)) {
                                lastAotvTime = now
                                teleportPauseTicks = 14
                                tntReactionDelayTicks = 0
                                AOTVHelper.castTeleport(preferredSlot)
                            }
                            return@register
                        }
                    }

                    // Otherwise walk smoothly away to safety
                    if (walkPos != null) {
                        val safeVec = Vec3(walkPos.x + 0.5, walkPos.y + 1.0, walkPos.z + 0.5)
                        PathfindingUtils.moveTo(safeVec, sprint = false)
                    }
                    return@register
                }
            }
            tntReactionDelayTicks = 0

            // 3. Find all living mobs strictly inside the blood room
            val maxRange = config.autoBloodAttackRange.toDouble().coerceIn(5.0, 30.0)
            val livingMobs = entities.filterIsInstance<LivingEntity>().filter { entity ->
                if (entity == player) return@filter false
                if (entity is ArmorStand) return@filter false
                if (entity is Sheep) return@filter false
                if (entity is Player && isDungeonTeammate(entity)) return@filter false
                if (isWatcher(entity)) return@filter false
                if (!entity.isAlive || entity.isRemoved) return@filter false
                if (entity.health <= 0f) return@filter false
                isInsideBloodRoom(entity.position())
            }.sortedBy { entity ->
                player.distanceToSqr(entity)
            }

            // Record mob positions for the average center calculation
            for (mob in livingMobs) {
                val pos = mob.position()
                if (recordedSpawnLocations.none { it.distanceTo(pos) < 1.5 }) {
                    recordedSpawnLocations.add(pos)
                }
            }

            // 4. Find imminent / airborne spawning mobs
            val currentTime = DungeonListener.currentTime
            val spawningCandidates = BloodCamp.bloodMobs.entries
                .mapNotNull { (stand, data) ->
                    val endVec = data.endVector ?: return@mapNotNull null

                    // Record spawn landing positions
                    if (recordedSpawnLocations.none { it.distanceTo(endVec) < 1.0 }) {
                        recordedSpawnLocations.add(endVec)
                    }

                    // Delay: wait at least 4 packets / 4 ticks for trajectory to settle away from the wall
                    if (data.deltaHistory.size < 4) return@mapNotNull null
                    val timeTook = currentTime - data.started
                    if (timeTook < 4) return@mapNotNull null

                    val time = (if (data.firstSpawn) 40 else 0) + 38 - timeTook + 0.8

                    if (!isInsideBloodRoom(endVec) || endVec.distanceTo(roomCenter) > 22.0) return@mapNotNull null
                    if (time < -3.0 || time > 40.0) return@mapNotNull null

                    Triple(stand, data, time)
                }
                .sortedBy { it.third }

            val nextSpawning = spawningCandidates.firstOrNull()

            // If an airborne mob is landing VERY soon (time in -2..6 ticks / 0.3s), pre-aim at its landing box.
            // Otherwise, if there are living mobs on the ground, prioritize killing the ground mobs!
            val shouldPreAimSpawn = nextSpawning != null && (livingMobs.isEmpty() || nextSpawning.third in -2.0..6.0)

            if (shouldPreAimSpawn && nextSpawning != null) {
                val (_, data, time) = nextSpawning
                val endVec = data.endVector!!
                val targetVec = endVec.add(0.0, 1.6, 0.0)

                val eyePos = player.eyePosition
                val dist = eyePos.distanceTo(targetVec)
                val hasLos = PathfindingUtils.hasLineOfSight(eyePos, targetVec)

                if (!hasLos || dist > maxRange) {
                    val now = System.currentTimeMillis()
                    val walkShootPos = PathfindingUtils.findBestShootingPosition(targetVec, tntPositions)
                    val walkDist = walkShootPos?.let { player.position().distanceTo(Vec3(it.x + 0.5, it.y + 1.0, it.z + 0.5)) } ?: 99.0

                    if (config.autoBloodAotv && AOTVHelper.hasAotv() && walkDist > 6.0 && now - lastAotvTime > 500) {
                        val shootPos = PathfindingUtils.findAotvShootingPosition(targetVec, tntPositions)
                        if (shootPos != null) {
                            val targetPoint = Vec3(shootPos.x + 0.5, shootPos.y + 0.95, shootPos.z + 0.5)
                            MouseRotationHelper.setTarget(targetPoint, config.autoBloodAimSpeed)
                            if (MouseRotationHelper.isAimingAt(targetPoint, 4.5f)) {
                                lastAotvTime = now
                                teleportPauseTicks = 14
                                AOTVHelper.castTeleport(preferredSlot)
                            }
                            return@register
                        }
                    }

                    if (walkShootPos != null) {
                        val shootVec = Vec3(walkShootPos.x + 0.5, walkShootPos.y + 1.0, walkShootPos.z + 0.5)
                        PathfindingUtils.moveTo(shootVec, sprint = false)
                    }
                } else {
                    PathfindingUtils.stopMovement()
                    MouseRotationHelper.setTarget(targetVec, config.autoBloodAimSpeed)
                }

                val spawnedEntity = livingMobs.find { it.position().distanceTo(endVec) < 2.5 }
                if (spawnedEntity != null) {
                    tryAttack(spawnedEntity)
                } else if (time <= -0.5) {
                    tryAttack(null)
                }
                return@register
            }

            // 5. Target & Attack Alive Ground Mobs in Blood Room (Mage Beam Range)
            val targetEntity = livingMobs.firstOrNull()
            if (targetEntity != null) {
                val targetVec = targetEntity.eyePosition
                val dist = player.distanceTo(targetEntity)
                val hasLos = player.hasLineOfSight(targetEntity) || PathfindingUtils.hasLineOfSight(player.eyePosition, targetVec)

                if (dist <= maxRange && hasLos) {
                    // In range and direct line of sight: stand ground, smoothly aim and fire Mage Beam!
                    PathfindingUtils.stopMovement()
                    MouseRotationHelper.setTarget(targetVec, config.autoBloodAimSpeed)

                    if (MouseRotationHelper.isAimingAt(targetVec, 6.0f) || dist < 4.0) {
                        tryAttack(targetEntity)
                    }
                } else {
                    // Blocked by a pillar or out of range: reposition naturally
                    val now = System.currentTimeMillis()
                    val walkShootPos = PathfindingUtils.findBestShootingPosition(targetVec, tntPositions)
                    val walkDist = walkShootPos?.let { player.position().distanceTo(Vec3(it.x + 0.5, it.y + 1.0, it.z + 0.5)) } ?: 99.0

                    if (config.autoBloodAotv && AOTVHelper.hasAotv() && walkDist > 6.0 && now - lastAotvTime > 500) {
                        val shootPos = PathfindingUtils.findAotvShootingPosition(targetVec, tntPositions)
                        if (shootPos != null) {
                            val targetPoint = Vec3(shootPos.x + 0.5, shootPos.y + 0.95, shootPos.z + 0.5)
                            MouseRotationHelper.setTarget(targetPoint, config.autoBloodAimSpeed)
                            if (MouseRotationHelper.isAimingAt(targetPoint, 4.5f)) {
                                lastAotvTime = now
                                teleportPauseTicks = 14
                                AOTVHelper.castTeleport(preferredSlot)
                            }
                            return@register
                        }
                    }

                    if (walkShootPos != null) {
                        val shootVec = Vec3(walkShootPos.x + 0.5, walkShootPos.y + 1.0, walkShootPos.z + 0.5)
                        PathfindingUtils.moveTo(shootVec, sprint = false)
                    }
                }
                return@register
            }

            // 6. Idle: Aim smoothly back towards the average / general spawning area in the central arena
            PathfindingUtils.stopMovement()

            val idleTarget: Vec3 = if (recordedSpawnLocations.isNotEmpty()) {
                val count = recordedSpawnLocations.size.toDouble()
                val sumX = recordedSpawnLocations.sumOf { it.x }
                val sumY = recordedSpawnLocations.sumOf { it.y }
                val sumZ = recordedSpawnLocations.sumOf { it.z }
                val avgX = sumX / count
                val avgY = (sumY / count) + 1.6
                val avgZ = sumZ / count
                Vec3(avgX, avgY, avgZ)
            } else {
                roomCenter.add(0.0, 1.6, 0.0)
            }

            MouseRotationHelper.setTarget(idleTarget, config.autoBloodAimSpeed)
        }
    }

    private fun isDisablerMessage(text: String): Boolean {
        return text.contains("[BOSS] The Watcher: You have proven yourself. You may pass.", ignoreCase = true)
    }

    private fun isResetMessage(text: String): Boolean {
        // Dungeon start / entrance messages
        if (text.contains("[NPC] Mort: Here, I found this map when I first entered the dungeon.", ignoreCase = true)) return true
        if (text.contains("Dungeon starts in 1 second.", ignoreCase = true)) return true

        // Run completed / boss defeated triggers that re-enable/reset features for the next run
        if (text.contains("[BOSS] Bonzo: Just you wait...", ignoreCase = true)) return true
        if (text.contains("[BOSS] Scarf: His technique.. is too advanced..", ignoreCase = true)) return true
        if (text.contains("[BOSS] Necron: Before I have to deal with you myself.", ignoreCase = true)) return true
        if (text.contains("[BOSS] Thorn: Congratulations humans, you may pass.", ignoreCase = true)) return true
        if (lividDisablerRegex.containsMatchIn(text)) return true
        if (text.contains("[BOSS] Sadan: FATHER, FORGIVE ME!!!", ignoreCase = true)) return true
        if (text.contains("[BOSS] The Wither King: My strengths are depleting, this… this is it…", ignoreCase = true)) return true

        // Necron end dialogue: only trigger when NOT in Master Mode Floor 7 (M7)
        if (text.contains("[BOSS] Necron: The Catacombs... are no more.", ignoreCase = true)) {
            val isM7 = LocationUtils.dungeonFloor?.equals("M7", ignoreCase = true) == true
            if (!isM7) return true
        }

        return false
    }

    private fun resetCampState() {
        bloodRoomCleared = false
        watcherMessageCount = 0
        attackCooldownTicks = 0
        teleportPauseTicks = 0
        tntReactionDelayTicks = 0
        lastAotvTime = 0L
        savedWeaponSlot = null
        recordedSpawnLocations.clear()
        PathfindingUtils.stopMovement()
        MouseRotationHelper.clearTarget()
    }

    private fun tryAttack(entity: LivingEntity?) {
        if (attackCooldownTicks > 0) return

        val cps = ConfigManager.config.blood.autoBloodCps.coerceIn(1, 20)
        attackCooldownTicks = (20 / cps).coerceAtLeast(1)

        PlayerUtils.attackEntity(entity)
    }

    fun isPlayerInBloodRoom(): Boolean {
        val player = mc.player ?: return false
        val playerPos = player.position()

        // 1. ScanUtils current room
        val current = ScanUtils.currentRoom
        if (current?.data?.type == RoomType.BLOOD) return true

        // 2. ScanUtils pos room
        val atPos = ScanUtils.getRoomFromPos(playerPos)
        if (atPos?.data?.type == RoomType.BLOOD) return true

        // 3. Tile proximity check
        return isInsideBloodRoom(playerPos)
    }

    fun isInsideBloodRoom(vec: Vec3): Boolean {
        val bloodRoom = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD }
            ?: DungeonScanner.dungeonList.filterIsInstance<RoomTile>().find { it.data.type == RoomType.BLOOD }?.uniqueRoom

        if (bloodRoom != null) {
            val tiles = bloodRoom.tiles.filterIsInstance<RoomTile>()
            if (tiles.isNotEmpty()) {
                return tiles.any { tile ->
                    abs(vec.x - tile.x) <= 15.5 && abs(vec.z - tile.z) <= 15.5
                }
            }
            return abs(vec.x - bloodRoom.centerPos.x) <= 15.5 && abs(vec.z - bloodRoom.centerPos.z) <= 15.5
        }

        return ScanUtils.getRoomFromPos(vec)?.data?.type == RoomType.BLOOD
    }

    fun dumpBloodRoomEntities() {
        DebugUtils.dumpBloodEntities()
    }
}
