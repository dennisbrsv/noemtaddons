package dev.noemt.client.features.blood

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.DungeonEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.utils.*
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.map.core.RoomTile
import dev.noemt.client.utils.map.core.RoomType
import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.handlers.HotbarMapScanner
import dev.noemt.client.utils.map.utils.ScanUtils
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
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

object AutoBloodCamp : Module {
    override val id = "auto_blood_camp"
    override val name = "Auto Blood Camp"
    override val description = "Automated aimbot and mob combat in Blood Room"
    override val type = ModuleType.CHEAT

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

    override fun init() {
        register<WorldChangeEvent> {
            resetCampState()
        }

        register<DungeonEvent.RunStatedEvent> {
            resetCampState()
        }

        register<DungeonEvent.RunEndedEvent> {
            resetCampState()
        }

        register<DungeonEvent.RoomEvent.onEnter> {
            if (event.room.data.type == RoomType.BLOOD) {
                bloodRoomCleared = false
            } else {
                // Stepped into any room other than Blood Room -> instantly disable and restore state
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                savedWeaponSlot?.let { PlayerUtils.swapToSlot(it) }
                savedWeaponSlot = null
                if (event.room.data.type == RoomType.ENTRANCE) {
                    resetCampState()
                }
            }
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
                if (savedWeaponSlot != null) {
                    PlayerUtils.swapToSlot(savedWeaponSlot!!)
                    savedWeaponSlot = null
                }
                return@register
            }

            if (DungeonListener.thePlayer?.isDead == true || !player.isAlive) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                return@register
            }

            // 2. Strict Blood Room check: Feature ONLY enables when inside the Blood Room!
            if (!isPlayerInBloodRoom()) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                if (savedWeaponSlot != null) {
                    PlayerUtils.swapToSlot(savedWeaponSlot!!)
                    savedWeaponSlot = null
                }
                return@register
            }

            // Activate as soon as Watcher is active or blood mobs/spawns are present
            val isWatcherActive = watcherMessageCount >= 1 ||
                    BloodCamp.bloodMobs.isNotEmpty() ||
                    DungeonListener.bloodOpenTime != null ||
                    BloodCamp.watcherEntity != null
            if (!isWatcherActive) {
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

            val preferredSlot = if (config.bloodWeaponSlot in 1..9) {
                config.bloodWeaponSlot - 1
            } else {
                savedWeaponSlot ?: player.inventory.selectedSlot
            }

            if (player.inventory.selectedSlot != preferredSlot) {
                PlayerUtils.swapToSlot(preferredSlot)
            }

            val bloodRoom = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD }
                ?: DungeonScanner.dungeonList.filterIsInstance<RoomTile>().find { it.data.type == RoomType.BLOOD }?.uniqueRoom

            fun isWatcher(entity: LivingEntity): Boolean {
                if (entity == BloodCamp.watcherEntity) return true
                if (entity is Zombie && entity.y > 72.0) return true
                val name = entity.customName?.string ?: (entity as? Player)?.gameProfile?.name ?: ""
                if (name.contains("Watcher", ignoreCase = true)) return true
                return false
            }

            val roomCenter = bloodRoom?.let { Vec3(it.centerPos.x.toDouble(), 69.0, it.centerPos.z.toDouble()) }
                ?: BloodCamp.watcherEntity?.position()
                ?: player.position()

            val entities = mc.level?.entitiesForRendering() ?: return@register
            val tntEntities = entities.filterIsInstance<PrimedTnt>().filter { tnt ->
                player.distanceTo(tnt) < 14.0
            }
            val tntPositions = tntEntities.map { it.position() }

            // 2. Natural TNT Evasion (Walking for short distances, AOTV for emergency / long distances)
            if (config.autoBloodTntEvade && tntEntities.isNotEmpty()) {
                val dangerousTnts = tntEntities.filter { tnt ->
                    player.distanceTo(tnt) < 7.5
                }

                if (dangerousTnts.isNotEmpty()) {
                    val minTntDist: Double = dangerousTnts.minOf { player.distanceTo(it).toDouble() }
                    val now = System.currentTimeMillis()

                    val safePos = PathfindingUtils.findAotvSafePositionFromTnts(tntPositions, 7.5)
                    val walkPos = PathfindingUtils.findSafePositionFromTnts(tntPositions, 7.5)

                    val walkDist = walkPos?.let { player.position().distanceTo(Vec3(it.x + 0.5, it.y + 1.0, it.z + 0.5)) } ?: 99.0
                    val shouldUseAotv = config.autoBloodAotv && AOTVHelper.hasAotv() && (minTntDist < 4.5 || walkDist > 5.0) && (now - lastAotvTime > 350)

                    if (shouldUseAotv && safePos != null) {
                        val targetPoint = Vec3(safePos.x + 0.5, safePos.y + 0.95, safePos.z + 0.5)
                        MouseRotationHelper.setTarget(targetPoint, config.autoBloodAimSpeed)

                        if (MouseRotationHelper.isAimingAt(targetPoint, 5.5f) || (now - lastAotvTime > 650)) {
                            lastAotvTime = now
                            teleportPauseTicks = 10
                            AOTVHelper.castTeleport(preferredSlot)
                        }
                        return@register
                    }

                    // Otherwise walk smoothly away to safety
                    if (walkPos != null) {
                        val safeVec = Vec3(walkPos.x + 0.5, walkPos.y + 1.0, walkPos.z + 0.5)
                        PathfindingUtils.moveTo(safeVec, sprint = true)
                        return@register
                    }
                }
            }
            if (PathfindingUtils.isControllingMovement) {
                PathfindingUtils.stopMovement()
            }

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
                if (player.distanceTo(entity) > maxRange) return@filter false
                isInsideBloodRoom(entity.position()) || (isPlayerInBloodRoom() && player.distanceTo(entity) < 30.0)
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

                    if (recordedSpawnLocations.none { it.distanceTo(endVec) < 1.0 }) {
                        recordedSpawnLocations.add(endVec)
                    }

                    if (data.deltaHistory.size < 2) return@mapNotNull null
                    val timeTook = currentTime - data.started
                    if (timeTook < 2) return@mapNotNull null

                    val time = (if (data.firstSpawn) 40 else 0) + 38 - timeTook + 0.8

                    if (time < -3.0 || time > 45.0) return@mapNotNull null

                    Triple(stand, data, time)
                }
                .sortedBy { it.third }

            val nextSpawning = spawningCandidates.firstOrNull()

            // 4. Target & Attack Alive Ground Mobs in Blood Room (Highest Priority when mobs are alive)
            if (livingMobs.isNotEmpty()) {
                val targetEntity = livingMobs.first()
                val targetVec = targetEntity.eyePosition
                val dist = player.distanceTo(targetEntity)
                val hasLos = player.hasLineOfSight(targetEntity) || PathfindingUtils.hasLineOfSight(player.eyePosition, targetVec)

                if (dist <= maxRange && hasLos) {
                    PathfindingUtils.stopMovement()
                    MouseRotationHelper.setTarget(targetVec, config.autoBloodAimSpeed)

                    if (MouseRotationHelper.isAimingAt(targetVec, 6.0f) || dist < 4.0) {
                        tryAttack(targetEntity)
                    }
                } else {
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

            // 5. No Ground Mobs: Check if an airborne mob is landing VERY soon (within 0.4s / 8 ticks)
            if (nextSpawning != null && nextSpawning.third in -2.0..8.0) {
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

                if (time <= -0.5) {
                    tryAttack(null)
                }
                return@register
            }

            // 6. Default Top Aiming: Always aim towards the top where the mobs spawn unless killing ground mobs
            PathfindingUtils.stopMovement()

            val topSpawnTarget: Vec3 = if (nextSpawning != null && nextSpawning.first.isAlive) {
                nextSpawning.first.position().add(0.0, 0.5, 0.0)
            } else {
                BloodCamp.watcherEntity?.eyePosition
                    ?: Vec3(roomCenter.x, 76.0, roomCenter.z)
            }

            MouseRotationHelper.setTarget(topSpawnTarget, config.autoBloodAimSpeed)
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

    fun isDungeonTeammate(p: Player): Boolean {
        val player = mc.player
        if (p == player) return true
        val name = p.gameProfile.name.trim()
        val customName = p.customName?.string?.removeFormatting()?.trim() ?: ""
        val displayName = p.displayName?.string?.removeFormatting()?.trim() ?: ""

        if (mc.user.name.equals(name, ignoreCase = true)) return true
        if (DungeonListener.thePlayer?.name.equals(name, ignoreCase = true)) return true

        if (DungeonListener.dungeonTeammates.any {
            it.name.equals(name, ignoreCase = true) ||
            customName.contains(it.name, ignoreCase = true) ||
            displayName.contains(it.name, ignoreCase = true)
        }) return true

        val info = mc.connection?.getPlayerInfo(p.uuid) ?: mc.connection?.getPlayerInfo(p.gameProfile.name)
        if (info != null && DungeonListener.dungeonTeammates.any { it.name.equals(info.profile.name, ignoreCase = true) }) {
            return true
        }

        return false
    }

    private fun tryAttack(entity: LivingEntity?) {
        val player = mc.player ?: return
        val config = ConfigManager.config.blood

        // STRICT TEAMMATE PROTECTION: Never attack or swing at a dungeon teammate
        if (entity is Player && isDungeonTeammate(entity)) {
            return
        }

        if (config.bloodWeaponSlot in 1..9) {
            val weaponSlot = config.bloodWeaponSlot - 1
            if (player.inventory.selectedSlot != weaponSlot) {
                PlayerUtils.swapToSlot(weaponSlot)
            }
        }

        if (attackCooldownTicks > 0) return

        val cps = config.autoBloodCps.coerceIn(1, 20)
        attackCooldownTicks = (20 / cps).coerceAtLeast(1)

        PlayerUtils.attackEntity(entity)
    }

    fun isPlayerInBloodRoom(): Boolean {
        val player = mc.player ?: return false
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) return false
        val playerPos = player.position()

        // 1. ScanUtils / Map scanner check
        val room = ScanUtils.getRoomFromPos(playerPos)
        if (room != null && room.data.type == RoomType.BLOOD) return true

        // 2. DungeonScanner / HotbarMapScanner grid check
        val (gx, gz) = ScanUtils.getRoomGraf(playerPos)
        val dungeonTile = DungeonScanner.dungeonList.getOrNull(gz * 11 + gx)
        if (dungeonTile is RoomTile && dungeonTile.data.type == RoomType.BLOOD) return true

        val mapTile = HotbarMapScanner.getTile(gx, gz)
        if (mapTile is RoomTile && mapTile.data.type == RoomType.BLOOD) return true

        // 3. DungeonScanner uniqueRooms check
        val bloodUnique = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD }
            ?: DungeonScanner.dungeonList.filterIsInstance<RoomTile>().find { it.data.type == RoomType.BLOOD }?.uniqueRoom

        if (bloodUnique != null) {
            val tiles = bloodUnique.tiles.filterIsInstance<RoomTile>()
            if (tiles.isNotEmpty()) {
                if (tiles.any { abs(playerPos.x - it.x) <= 16.0 && abs(playerPos.z - it.z) <= 16.0 }) return true
            } else if (abs(playerPos.x - bloodUnique.centerPos.x) <= 16.0 && abs(playerPos.z - bloodUnique.centerPos.z) <= 16.0) {
                return true
            }
        }

        // 4. Proximity to The Watcher ArmorStand or Watcher Zombie
        val level = mc.level
        if (level != null) {
            val watcherStand = level.entitiesForRendering().find {
                it is ArmorStand && it.customName?.string?.contains("The Watcher", ignoreCase = true) == true
            }
            if (watcherStand != null && playerPos.distanceTo(watcherStand.position()) < 32.0) {
                return true
            }

            val watcherZombie = BloodCamp.watcherEntity
            if (watcherZombie != null && playerPos.distanceTo(watcherZombie.position()) < 32.0) {
                return true
            }

            // 5. Blood mob / Watchful eye proximity check
            if (watcherMessageCount >= 1) {
                val bloodStand = level.entitiesForRendering().find {
                    it is ArmorStand && it.customName?.string?.let { name ->
                        name.contains("Healthy", ignoreCase = true) || name.contains("Watchful Eye", ignoreCase = true)
                    } == true
                }
                if (bloodStand != null && playerPos.distanceTo(bloodStand.position()) < 25.0) {
                    return true
                }
            }
        }

        return false
    }

    fun isInsideBloodRoom(vec: Vec3): Boolean {
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) return false

        // 1. ScanUtils check
        val room = ScanUtils.getRoomFromPos(vec)
        if (room != null && room.data.type == RoomType.BLOOD) return true

        // 2. DungeonScanner / HotbarMapScanner grid check
        val (gx, gz) = ScanUtils.getRoomGraf(vec)
        val dungeonTile = DungeonScanner.dungeonList.getOrNull(gz * 11 + gx)
        if (dungeonTile is RoomTile && dungeonTile.data.type == RoomType.BLOOD) return true

        val mapTile = HotbarMapScanner.getTile(gx, gz)
        if (mapTile is RoomTile && mapTile.data.type == RoomType.BLOOD) return true

        // 3. DungeonScanner uniqueRooms check
        val bloodUnique = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD }
            ?: DungeonScanner.dungeonList.filterIsInstance<RoomTile>().find { it.data.type == RoomType.BLOOD }?.uniqueRoom

        if (bloodUnique != null) {
            val tiles = bloodUnique.tiles.filterIsInstance<RoomTile>()
            if (tiles.isNotEmpty()) {
                if (tiles.any { abs(vec.x - it.x) <= 16.0 && abs(vec.z - it.z) <= 16.0 }) return true
            } else if (abs(vec.x - bloodUnique.centerPos.x) <= 16.0 && abs(vec.z - bloodUnique.centerPos.z) <= 16.0) {
                return true
            }
        }

        // 4. Proximity to Watcher
        val level = mc.level
        if (level != null) {
            val watcherStand = level.entitiesForRendering().find {
                it is ArmorStand && it.customName?.string?.contains("The Watcher", ignoreCase = true) == true
            }
            if (watcherStand != null && vec.distanceTo(watcherStand.position()) < 32.0) {
                return true
            }

            val watcherZombie = BloodCamp.watcherEntity
            if (watcherZombie != null && vec.distanceTo(watcherZombie.position()) < 32.0) {
                return true
            }
        }

        return false
    }

    fun dumpBloodRoomEntities() {
        DebugUtils.dumpBloodEntities()
    }
}
