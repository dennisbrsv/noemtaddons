package dev.noemt.client.features.blood

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.DungeonEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.utils.*
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.map.core.RoomTile
import dev.noemt.client.utils.map.core.RoomType
import dev.noemt.client.utils.map.handlers.DungeonScanner
import dev.noemt.client.utils.map.handlers.HotbarMapScanner
import dev.noemt.client.utils.map.utils.ScanUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

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

    var hasEnteredBloodRoom = false
        private set

    private var attackCooldownTicks = 0
    private var teleportPauseTicks = 0
    private var tntReactionDelayTicks = 0
    private var lastAotvTime = 0L
    private var savedWeaponSlot: Int? = null
    private var kp6WasDown = false
    private var kp7WasDown = false

    private val recordedSpawnLocations = mutableListOf<Vec3>()
    private val recordedBoxPositions = mutableListOf<Vec3>()
    private val recordedGroundMobPositions = mutableListOf<Vec3>()
    private var lastSpawnedMobPosition: Vec3? = null
    private var hasKilledGroundMobs = false

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
                hasEnteredBloodRoom = false
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                savedWeaponSlot?.let { PlayerUtils.swapToSlot(it) }
                savedWeaponSlot = null
                if (event.room.data.type == RoomType.ENTRANCE) {
                    resetCampState()
                }
            }
        }

        register<DungeonEvent.RoomEvent.onExit> {
            if (event.room.data.type == RoomType.BLOOD) {
                hasEnteredBloodRoom = false
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                savedWeaponSlot?.let { PlayerUtils.swapToSlot(it) }
                savedWeaponSlot = null
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
                hasEnteredBloodRoom = false
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
                hasEnteredBloodRoom = false
                return@register
            }

            if (DungeonListener.thePlayer?.isDead == true || !player.isAlive) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                return@register
            }

            // 2. Strict Blood Room Entry Check:
            // The module MUST NOT start when outside the blood room! The player MUST enter the blood room first.
            val currentlyInRoom = isPlayerInBloodRoom()
            if (currentlyInRoom) {
                hasEnteredBloodRoom = true
            }

            if (!hasEnteredBloodRoom || !currentlyInRoom) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                if (savedWeaponSlot != null) {
                    PlayerUtils.swapToSlot(savedWeaponSlot!!)
                    savedWeaponSlot = null
                }
                return@register
            }

            // Activate as soon as Watcher is active, blood mobs/spawns are present, or player is in the blood room
            val isWatcherActive = watcherMessageCount >= 1 ||
                    BloodCamp.bloodMobs.isNotEmpty() ||
                    DungeonListener.bloodOpenTime != null ||
                    BloodCamp.watcherEntity != null ||
                    currentlyInRoom
            if (!isWatcherActive) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                return@register
            }

            // If a teleport is currently happening or within the pause window, pause actions
            if (AOTVHelper.isTeleporting || teleportPauseTicks > 0) {
                if (PathfindingUtils.isControllingMovement) PathfindingUtils.stopMovement()
                MouseRotationHelper.clearTarget()
                return@register
            }

            // Save weapon slot when in blood room
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

            val roomCenterPos = getBloodRoomCenter() ?: player.blockPosition()
            val roomCenterFloor = Vec3(roomCenterPos.x + 0.5, 69.5, roomCenterPos.z + 0.5)

            // 3. Primed TNT Detection & Evasion (Fabric 26.1.2 entity query & 2D/3D collision avoidance)
            val level = mc.level ?: return@register
            val entities = level.entitiesForRendering()
            val tntEntities = entities.filter { entity ->
                (entity is PrimedTnt || entity.type == EntityType.TNT) &&
                entity.isAlive &&
                !entity.isRemoved &&
                isInsideBloodRoom(entity.position())
            }
            val tntPositions = tntEntities.map { it.position() }

            var isEvadingTnt = false
            if (config.autoBloodTntEvade && tntEntities.isNotEmpty()) {
                val dangerousTnts = tntEntities.filter { tnt ->
                    val dist2D = hypot(player.x - tnt.x, player.z - tnt.z)
                    val dist3D = player.distanceTo(tnt)
                    dist2D < 6.0 || dist3D < 6.0
                }

                if (dangerousTnts.isNotEmpty()) {
                    val minTntDist = dangerousTnts.minOf { tnt ->
                        min(hypot(player.x - tnt.x, player.z - tnt.z), player.distanceTo(tnt).toDouble())
                    }
                    val now = System.currentTimeMillis()

                    val safeWalkPos = PathfindingUtils.findSafePositionFromTnts(tntPositions, 6.0)
                    val safeAotvPos = PathfindingUtils.findAotvSafePositionFromTnts(tntPositions, 6.0)

                    val walkDist = safeWalkPos?.let { player.position().distanceTo(Vec3(it.x + 0.5, it.y + 1.0, it.z + 0.5)) } ?: 99.0

                    // Pace evasion calmly: TNT fuse gives plenty of time.
                    // Smoothly walk away first. Only Etherwarp if trapped close (< 1.8m) or safe spot is far (> 6.5m)
                    // with a steady 1400ms cooldown.
                    val shouldUseEtherwarp = config.autoBloodAotv &&
                            AOTVHelper.hasAotv() &&
                            (minTntDist < 1.8 || walkDist > 6.5) &&
                            (now - lastAotvTime > 1400L) &&
                            safeAotvPos != null

                    if (shouldUseEtherwarp && safeAotvPos != null) {
                        // Target the solid floor block top (never into the air) for Etherwarp
                        val targetBlockTop = Vec3(safeAotvPos.x + 0.5, safeAotvPos.y + 1.0, safeAotvPos.z + 0.5)
                        MouseRotationHelper.setTarget(targetBlockTop, config.autoBloodAimSpeed)

                        // Walk smoothly in the direction while aiming
                        PathfindingUtils.moveTo(targetBlockTop, sprint = true)
                        isEvadingTnt = true

                        // Only cast Etherwarp once the crosshair is securely locked on the floor block
                        if (MouseRotationHelper.isAimingAt(targetBlockTop, 4.5f)) {
                            lastAotvTime = now
                            teleportPauseTicks = 8
                            AOTVHelper.castTeleport(preferredSlot)
                        }
                    } else if (safeWalkPos != null) {
                        // Smoothly walk to safe spot not blocked by pillars
                        val safeVec = Vec3(safeWalkPos.x + 0.5, safeWalkPos.y + 1.0, safeWalkPos.z + 0.5)
                        PathfindingUtils.moveTo(safeVec, sprint = true)
                        isEvadingTnt = true
                    }
                }
            }

            if (!isEvadingTnt && PathfindingUtils.isControllingMovement && !config.autoBloodHumanMovement) {
                PathfindingUtils.stopMovement()
            }

            // 4. Find all living mobs strictly inside the blood room
            // Exclude WitherBoss (player Wither pet circling), tamed pets, golems, and bats
            val maxRange = config.autoBloodAttackRange.toDouble().coerceIn(5.0, 30.0)
            val livingMobs = entities.filterIsInstance<LivingEntity>().filter { entity ->
                if (entity == player) return@filter false
                if (entity is ArmorStand) return@filter false
                if (entity is Sheep) return@filter false
                if (entity is Player && isDungeonTeammate(entity)) return@filter false
                if (isWatcherOrEye(entity)) return@filter false
                if (entity is WitherBoss || entity.type == EntityType.WITHER) return@filter false
                if (entity is Bat || entity.type == EntityType.BAT) return@filter false
                if (entity.type == EntityType.IRON_GOLEM || entity.type == EntityType.SNOW_GOLEM) return@filter false
                if (entity is TamableAnimal && entity.isTame) return@filter false
                val name = entity.customName?.string?.removeFormatting() ?: (entity as? Player)?.gameProfile?.name ?: ""
                if (name.contains("Blessing", ignoreCase = true) ||
                    name.contains("Decoy", ignoreCase = true) ||
                    name.contains("Pet", ignoreCase = true)) return@filter false
                if (!entity.isAlive || entity.isRemoved) return@filter false
                if (entity.health <= 0f) return@filter false
                if (player.distanceTo(entity) > (maxRange + 15.0)) return@filter false
                isInsideBloodRoom(entity.position())
            }.sortedWith(
                compareByDescending<LivingEntity> {
                    player.hasLineOfSight(it) || PathfindingUtils.hasLineOfSight(player.eyePosition, it.boundingBox.center)
                }.thenBy {
                    player.distanceToSqr(it)
                }
            )

            // Record mob positions
            for (mob in livingMobs) {
                val pos = mob.position()
                if (recordedSpawnLocations.none { it.distanceTo(pos) < 1.5 }) {
                    recordedSpawnLocations.add(pos)
                }
            }

            // 5. Target & Attack Alive Ground Mobs in Blood Room (Highest Priority - Always First in Queue)
            if (livingMobs.isNotEmpty()) {
                val targetEntity = livingMobs.first()
                val targetVec = targetEntity.boundingBox.center
                val dist = player.distanceTo(targetEntity)
                val eyePos = player.eyePosition
                val hasLos = player.hasLineOfSight(targetEntity) || PathfindingUtils.hasLineOfSight(eyePos, targetVec)

                hasKilledGroundMobs = true
                val mobPos = targetEntity.position()
                if (recordedGroundMobPositions.none { it.distanceTo(mobPos) < 1.5 }) {
                    recordedGroundMobPositions.add(mobPos)
                }

                if (!hasLos || dist > maxRange) {
                    if (!isEvadingTnt) {
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
                } else {
                    if (!isEvadingTnt && PathfindingUtils.isControllingMovement) {
                        PathfindingUtils.stopMovement()
                    }
                    // Aim directly at the mob's center
                    MouseRotationHelper.setTarget(targetVec, config.autoBloodAimSpeed)
                }

                // Attack at full CPS whenever aimed towards mob (14 degree tolerance) or in close range
                if (MouseRotationHelper.isAimingAt(targetVec, 14.0f) || dist < 6.0) {
                    tryAttack(targetEntity)
                }
                return@register
            }

            // 6. Find imminent / airborne spawning mobs (Bounding boxes in the air)
            val currentTime = DungeonListener.currentTime
            val spawningCandidates = BloodCamp.bloodMobs.entries
                .mapNotNull { (stand, data) ->
                    val endVec = data.endVector ?: return@mapNotNull null
                    if (!isInsideBloodRoom(endVec)) return@mapNotNull null

                    val boxPos = endVec.add(0.0, 2.0, 0.0)
                    lastSpawnedMobPosition = boxPos

                    if (recordedBoxPositions.none { it.distanceTo(boxPos) < 1.0 }) {
                        recordedBoxPositions.add(boxPos)
                    }

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

            // 7. Aim at the predicted box in the air for spawning mob
            if (nextSpawning != null) {
                val (_, data, time) = nextSpawning
                val endVec = data.endVector!!
                // Center of the bounding box in the air (Y+2.0)
                val boxTargetVec = endVec.add(0.0, 2.0, 0.0)
                lastSpawnedMobPosition = boxTargetVec

                val eyePos = player.eyePosition
                val dist = eyePos.distanceTo(boxTargetVec)
                val hasLos = PathfindingUtils.hasLineOfSight(eyePos, boxTargetVec)

                if (!hasLos || dist > maxRange) {
                    if (!isEvadingTnt) {
                        val now = System.currentTimeMillis()
                        val walkShootPos = PathfindingUtils.findBestShootingPosition(boxTargetVec, tntPositions)
                        val walkDist = walkShootPos?.let { player.position().distanceTo(Vec3(it.x + 0.5, it.y + 1.0, it.z + 0.5)) } ?: 99.0

                        if (config.autoBloodAotv && AOTVHelper.hasAotv() && walkDist > 6.0 && now - lastAotvTime > 500) {
                            val shootPos = PathfindingUtils.findAotvShootingPosition(boxTargetVec, tntPositions)
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
                } else {
                    if (!isEvadingTnt && PathfindingUtils.isControllingMovement) {
                        PathfindingUtils.stopMovement()
                    }
                    MouseRotationHelper.setTarget(boxTargetVec, config.autoBloodAimSpeed)
                }

                if (time <= 0.0) {
                    tryAttack(null)
                }
                return@register
            }

            // 8. Resting Position:
            // If no blood mob in queue:
            // - If you killed mobs on the ground, aim somewhere in the area (the average position)
            // - Otherwise, set the resting position to the last spawned blood mob position
            val restingTarget: Vec3 = if (hasKilledGroundMobs) {
                val allPositions = (recordedGroundMobPositions + recordedBoxPositions)
                if (allPositions.isNotEmpty()) {
                    val avgX = allPositions.sumOf { it.x } / allPositions.size
                    val avgY = allPositions.sumOf { it.y } / allPositions.size
                    val avgZ = allPositions.sumOf { it.z } / allPositions.size
                    Vec3(avgX, avgY, avgZ)
                } else {
                    Vec3(roomCenterPos.x + 0.5, 71.0, roomCenterPos.z + 0.5)
                }
            } else if (lastSpawnedMobPosition != null) {
                lastSpawnedMobPosition!!
            } else if (recordedBoxPositions.isNotEmpty()) {
                recordedBoxPositions.last()
            } else {
                Vec3(roomCenterPos.x + 0.5, 71.0, roomCenterPos.z + 0.5)
            }

            if (!isEvadingTnt && PathfindingUtils.isControllingMovement) {
                PathfindingUtils.stopMovement()
            }
            MouseRotationHelper.setTarget(restingTarget, config.autoBloodAimSpeed)
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
        hasEnteredBloodRoom = false
        watcherMessageCount = 0
        attackCooldownTicks = 0
        teleportPauseTicks = 0
        tntReactionDelayTicks = 0
        lastAotvTime = 0L
        savedWeaponSlot = null
        recordedSpawnLocations.clear()
        recordedBoxPositions.clear()
        recordedGroundMobPositions.clear()
        lastSpawnedMobPosition = null
        hasKilledGroundMobs = false
        PathfindingUtils.stopMovement()
        MouseRotationHelper.clearTarget()
    }

    fun isDungeonTeammate(p: Player): Boolean {
        val player = mc.player
        if (p == player) return true
        val name = p.gameProfile.name.trim()
        val customName = p.customName?.string?.removeFormatting()?.trim() ?: ""
        val displayName = p.displayName.string.removeFormatting().trim()

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

    fun isWatcherOrEyeName(name: String): Boolean {
        if (name.isBlank()) return false
        val clean = name.removeFormatting().trim()
        return clean.contains("The Watcher", ignoreCase = true) ||
               clean.contains("Watcher", ignoreCase = true) ||
               clean.contains("Watchful Eye", ignoreCase = true) ||
               clean.contains("Beating Heart", ignoreCase = true) ||
               clean.contains("Not bad.", ignoreCase = true) ||
               clean.contains("Let's see how you can handle this", ignoreCase = true) ||
               clean.contains("You have proven yourself", ignoreCase = true) ||
               clean.contains("I have no use for you anymore", ignoreCase = true)
    }

    fun isWatcherOrEye(entity: net.minecraft.world.entity.Entity?): Boolean {
        if (entity == null) return false
        if (entity == BloodCamp.watcherEntity) return true
        if (BloodCamp.watcherEntity != null && entity.id == BloodCamp.watcherEntity?.id) return true

        // 1. Direct names on the entity itself
        val directName = entity.customName?.string?.removeFormatting()
            ?: (entity as? Player)?.gameProfile?.name
            ?: (entity as? ArmorStand)?.name?.string?.removeFormatting()
            ?: entity.name.string.removeFormatting()
        if (isWatcherOrEyeName(directName)) return true

        // 2. Head item skull texture against Watcher skulls
        val headItem = when (entity) {
            is LivingEntity -> entity.getItemBySlot(EquipmentSlot.HEAD)
            is ArmorStand -> entity.getItemBySlot(EquipmentSlot.HEAD)
            else -> null
        }
        if (headItem != null && !headItem.isEmpty) {
            val skull = ItemUtils.getSkullTexture(headItem)
            if (skull != null && skull in BloodCamp.watcherSkulls) return true
        }

        // 3. Floating ArmorStand nametags directly above the entity (Hypixel SkyBlock nametags)
        val level = mc.level
        if (level != null) {
            val checkArea = net.minecraft.world.phys.AABB(
                entity.x - 1.5, entity.y - 0.5, entity.z - 1.5,
                entity.x + 1.5, entity.y + 4.0, entity.z + 1.5
            )
            for (near in level.getEntities(entity, checkArea)) {
                if (near is ArmorStand) {
                    val standName = near.customName?.string?.removeFormatting()
                        ?: near.name.string.removeFormatting()
                    if (isWatcherOrEyeName(standName)) return true
                    val standHead = near.getItemBySlot(EquipmentSlot.HEAD)
                    if (!standHead.isEmpty) {
                        val skull = ItemUtils.getSkullTexture(standHead)
                        if (skull != null && skull in BloodCamp.watcherSkulls) return true
                    }
                }
            }
        }

        // 4. In Blood room, any Zombie perched high on the altar (Y >= 73.0) is the Watcher or Watchful Eye
        if (entity is Zombie && isInsideBloodRoom(entity.position()) && entity.y >= 73.0) {
            return true
        }

        return false
    }

    fun isWatcher(entity: net.minecraft.world.entity.Entity?): Boolean = isWatcherOrEye(entity)

    private fun tryAttack(entity: LivingEntity?) {
        val player = mc.player ?: return
        val config = ConfigManager.config.blood

        // STRICT WATCHER & WATCHFUL EYE PROTECTION: Never attack or swing at the Watcher or Watchful Eyes
        if (entity != null && isWatcherOrEye(entity)) {
            return
        }

        // STRICT TEAMMATE PROTECTION: Never attack or swing at a dungeon teammate
        if (entity is Player && isDungeonTeammate(entity)) {
            return
        }

        // If crosshair or raycast hit is on the Watcher, Watchful Eye, or a teammate, do not swing / left click
        val hovered = mc.crosshairPickEntity
        if (hovered != null) {
            if (isWatcherOrEye(hovered)) return
            if (hovered is Player && isDungeonTeammate(hovered)) return
        }

        val hit = mc.hitResult
        if (hit is net.minecraft.world.phys.EntityHitResult) {
            val hitEntity = hit.entity
            if (isWatcherOrEye(hitEntity)) return
            if (hitEntity is Player && isDungeonTeammate(hitEntity)) return
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

    fun getBloodRoomCenter(): BlockPos? {
        val cur = ScanUtils.currentRoom
        if (cur?.data?.type == RoomType.BLOOD) {
            return BlockPos(cur.centerPos.x, 69, cur.centerPos.z)
        }

        val pPos = mc.player?.position()
        if (pPos != null) {
            val r = ScanUtils.getRoomFromPos(pPos)
            if (r?.data?.type == RoomType.BLOOD) {
                return BlockPos(r.centerPos.x, 69, r.centerPos.z)
            }
        }

        val bloodUnique = DungeonScanner.uniqueRooms.values.find { it.data.type == RoomType.BLOOD }
        if (bloodUnique != null) {
            return BlockPos(bloodUnique.centerPos.x, 69, bloodUnique.centerPos.z)
        }

        val tile = DungeonScanner.dungeonList.filterIsInstance<RoomTile>().find { it.data.type == RoomType.BLOOD }
        if (tile != null) {
            return BlockPos(tile.x, 69, tile.z)
        }

        val espBlood = BloodESP.findBlood()
        if (espBlood != null) {
            return BlockPos(espBlood.first.x, 69, espBlood.first.z)
        }

        val watcherZombie = BloodCamp.watcherEntity
        if (watcherZombie != null) {
            val (gx, gz) = ScanUtils.getRoomGraf(watcherZombie.position())
            val rx = DungeonScanner.startX + (gx / 2) * DungeonScanner.roomSize
            val rz = DungeonScanner.startZ + (gz / 2) * DungeonScanner.roomSize
            return BlockPos(rx, 69, rz)
        }

        val level = mc.level
        if (level != null) {
            val watcherStand = level.entitiesForRendering().find {
                it is ArmorStand && it.customName?.string?.contains("The Watcher", ignoreCase = true) == true
            }
            if (watcherStand != null) {
                val (gx, gz) = ScanUtils.getRoomGraf(watcherStand.position())
                val rx = DungeonScanner.startX + (gx / 2) * DungeonScanner.roomSize
                val rz = DungeonScanner.startZ + (gz / 2) * DungeonScanner.roomSize
                return BlockPos(rx, 69, rz)
            }
        }

        return null
    }

    fun isPlayerInBloodRoom(): Boolean {
        val player = mc.player ?: return false
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) return false
        val playerPos = player.position()

        val currentRoom = ScanUtils.currentRoom ?: ScanUtils.getRoomFromPos(playerPos)
        if (currentRoom?.data?.type == RoomType.BLOOD) return true

        val center = getBloodRoomCenter() ?: return false
        val dx = abs(playerPos.x - (center.x + 0.5))
        val dz = abs(playerPos.z - (center.z + 0.5))

        // Generous room boundary: inside the blood room floor area and doorways
        return dx <= 18.0 && dz <= 18.0 && playerPos.y in 55.0..95.0
    }

    fun isInsideBloodRoom(vec: Vec3): Boolean {
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) return false
        val room = ScanUtils.getRoomFromPos(vec)
        if (room?.data?.type == RoomType.BLOOD) return true

        val center = getBloodRoomCenter() ?: return false
        val dx = abs(vec.x - (center.x + 0.5))
        val dz = abs(vec.z - (center.z + 0.5))
        return dx <= 18.0 && dz <= 18.0 && vec.y in 55.0..95.0
    }

    fun dumpBloodRoomEntities() {
        DebugUtils.dumpBloodEntities()
    }
}
