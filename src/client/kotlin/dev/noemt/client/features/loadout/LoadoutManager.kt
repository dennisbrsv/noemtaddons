package dev.noemt.client.features.loadout

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.remote.RemoteWebSocketClient
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.ItemUtils
import dev.noemt.client.utils.ItemUtils.lore
import dev.noemt.client.utils.ItemUtils.skyblockId
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.PlayerUtils
import dev.noemt.client.utils.ScoreboardUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.io.File
import kotlin.random.Random

object LoadoutManager {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LoadoutCondition::class.java, LoadoutConditionAdapter())
        .setPrettyPrinting()
        .create()

    private val configFile: File by lazy {
        val dir = File(Minecraft.getInstance().gameDirectory, "noemtaddons")
        dir.mkdirs()
        File(dir, "loadouts.json")
    }

    private val rulesFile: File by lazy {
        val dir = File(Minecraft.getInstance().gameDirectory, "noemtaddons")
        dir.mkdirs()
        File(dir, "loadout_rules.json")
    }

    val loadouts = mutableMapOf<String, Loadout>()
    val rules = mutableListOf<LoadoutRule>()

    var currentLoadoutId: String = "loadout_1"
        private set

    var previousLoadoutId: String? = null
        private set

    var loadoutAId: String = "loadout_1"
    var loadoutBId: String = "loadout_2"

    // Live UI reactivity listener
    var onDataChanged: (() -> Unit)? = null

    // Miniboss Memory Tracking
    var inMinibossFight: Boolean = false
        private set
    var trackedMinibossEntityId: Int = -1
        private set
    var trackedMinibossName: String = ""
        private set
    var minibossPreLoadoutId: String? = null
        private set
    var minibossLastSeenPos: Vec3? = null
        private set
    private var minibossDisappearedTicks: Int = 0
    private var minibossEngageTimeMs: Long = 0L

    // Queued Miniboss Revert (Prevents race condition when boss is slain while swap is executing)
    private var pendingMinibossRevert: Boolean = false
    private var pendingMinibossRevertLoadoutId: String? = null
    private var pendingMinibossRevertReason: String = ""

    // Swap Verification & Multi-Attempt Engine (for Dungeon / Blood Room entry)
    var pendingVerificationTargetId: String? = null
        private set
    var pendingVerificationReason: String = ""
        private set
    var isSwapConfirmed: Boolean = false
        private set
    private var verificationAttempts: Int = 0
    private var nextVerificationCheckMs: Long = 0L
    private const val MAX_VERIFICATION_ATTEMPTS = 3

    // Instance / Area tracking
    var lastDetectedInstance: GameInstanceType? = null
        private set
    var lastDetectedArea: String = ""
        private set
    private var instanceCheckCounter = 0

    // In-menu state
    var inLoadoutMenu: Boolean = false
        private set
    private var pendingAutoClose: Boolean = false
    private var lastManualClick: Long = 0L
    private var lastSwapExecutionMs: Long = 0L

    // Automated GUI Swap State Machine
    private enum class SwapStage {
        IDLE,
        PRE_CMD_WAIT,
        WAITING_GUI_OPEN,
        GUI_OPEN_WAIT,
        POST_CLICK_WAIT,
        POST_ACTIONS
    }

    private var currentStage = SwapStage.IDLE
    private var stageTargetTimeMs: Long = 0L
    private var guiWaitTimeoutMs: Long = 0L
    private var pendingLoadout: Loadout? = null
    private var pendingReason: String = "Manual"

    val isExecutingSwap: Boolean get() = currentStage != SwapStage.IDLE

    fun init() {
        loadData()
        if (loadouts.isEmpty()) {
            setupDefaults()
            saveData()
        }
    }

    fun getCurrentLoadout(): Loadout? = loadouts[currentLoadoutId]

    fun toggleAB() {
        val target = if (currentLoadoutId == loadoutAId) loadoutBId else loadoutAId
        swapTo(target, "Toggle Keybind (A ⇄ B)")
    }

    fun swapToPrevious() {
        var target = previousLoadoutId
        if (target == null || target == currentLoadoutId) {
            target = when {
                loadoutAId.isNotBlank() && currentLoadoutId != loadoutAId -> loadoutAId
                loadoutBId.isNotBlank() && currentLoadoutId != loadoutBId -> loadoutBId
                currentLoadoutId != "loadout_1" -> "loadout_1"
                else -> null
            }
        }
        if (target == null || target == currentLoadoutId) {
            ChatUtils.modMessage("&e[Loadout] Already on primary set.")
            return
        }
        val targetName = loadouts[target]?.name ?: target
        swapTo(target, "Revert (Last Equipped: $targetName)")
    }

    // ==========================================
    // MINIBOSS MEMORY TRACKING ENGINE
    // ==========================================

    fun onMinibossEngaged(entity: Entity, targetLoadoutId: String) {
        // Resolve to actual LivingEntity body (rather than floating nametag ArmorStand)
        val trueBody = MobMatcher.getTrueMinibossBody(entity)
        val entityId = trueBody.id
        val entityName = MobMatcher.getAllEntityNames(trueBody).firstOrNull { name ->
            name.contains("Shadow Assassin", ignoreCase = true) ||
            name.contains("Lost Adventurer", ignoreCase = true) ||
            name.contains("Frozen Adventurer", ignoreCase = true) ||
            name.contains("Angry Archaeologist", ignoreCase = true) ||
            name.contains("King Midas", ignoreCase = true)
        } ?: MobMatcher.getAllEntityNames(trueBody).firstOrNull() ?: "Miniboss"

        // If we are already in an active fight, update tracking position/target without overwriting preLoadoutId
        if (inMinibossFight) {
            trackedMinibossEntityId = entityId
            minibossLastSeenPos = trueBody.position()
            minibossDisappearedTicks = 0
            return
        }

        // Fresh encounter: lock preLoadoutId to the player's pre-combat gear
        minibossPreLoadoutId = currentLoadoutId
        trackedMinibossEntityId = entityId
        trackedMinibossName = entityName
        minibossLastSeenPos = trueBody.position()
        minibossDisappearedTicks = 0
        minibossEngageTimeMs = System.currentTimeMillis()
        inMinibossFight = true
        pendingMinibossRevert = false
        pendingMinibossRevertLoadoutId = null
        pendingMinibossRevertReason = ""

        ChatUtils.modMessage("&b[Loadout] &6⚔️ Engaged Miniboss: &e$entityName &7➜ Swapping to combat set...")
        swapTo(targetLoadoutId, "Miniboss Encounter: $entityName")
    }

    fun onEntityRemoved(entityId: Int) {
        if (inMinibossFight && entityId == trackedMinibossEntityId) {
            minibossDisappearedTicks = maxOf(minibossDisappearedTicks, 8)
        }
    }

    private fun checkMinibossTrackingTick() {
        // 1. Process queued miniboss revert if pending (waits for ongoing swap to complete)
        if (pendingMinibossRevert) {
            val revertId = pendingMinibossRevertLoadoutId
            if (revertId != null && !isExecutingSwap) {
                val now = System.currentTimeMillis()
                if (now - lastSwapExecutionMs >= 400L) {
                    pendingMinibossRevert = false
                    pendingMinibossRevertLoadoutId = null
                    val revertName = loadouts[revertId]?.name ?: revertId
                    val reason = pendingMinibossRevertReason
                    ChatUtils.modMessage("&b[Loadout] &a✓ $reason! Auto-swapping back to: &e$revertName")
                    swapTo(revertId, reason, force = true)
                }
            }
        }

        if (!inMinibossFight) return

        val player = mc.player
        val level = mc.level
        if (player == null || level == null || !player.isAlive) {
            resetMinibossState()
            return
        }

        val trackedEntity = level.getEntity(trackedMinibossEntityId)
        val isAliveAndPresent = trackedEntity != null &&
                trackedEntity.isAlive &&
                !trackedEntity.isRemoved &&
                ((trackedEntity as? LivingEntity)?.health ?: 1f) > 0f &&
                !((trackedEntity as? LivingEntity)?.isDeadOrDying ?: false)

        if (isAliveAndPresent && trackedEntity != null) {
            minibossLastSeenPos = trackedEntity.position()
            minibossDisappearedTicks = 0

            // If player moved away (> 45 blocks) for > 5 seconds
            val dist = player.position().distanceTo(trackedEntity.position())
            if (dist > 45.0 && System.currentTimeMillis() - minibossEngageTimeMs > 5000L) {
                onMinibossDisappeared("Player Left Combat Area")
            }
        } else {
            // Check if entity is confirmed dead (0 HP or death animation active)
            val isConfirmedDead = trackedEntity != null && (
                !trackedEntity.isAlive ||
                ((trackedEntity as? LivingEntity)?.health ?: 1f) <= 0f ||
                ((trackedEntity as? LivingEntity)?.isDeadOrDying ?: false) ||
                ((trackedEntity as? LivingEntity)?.deathTime ?: 0) > 0
            )

            if (isConfirmedDead) {
                onMinibossDisappeared("Miniboss Slain")
                return
            }

            // If not found (e.g. Shadow Assassin teleport/cloak), search nearby to re-acquire before counting disappearance
            val lastPos = minibossLastSeenPos
            if (lastPos != null) {
                val nearbyMb = level.getEntities(player, net.minecraft.world.phys.AABB(
                    lastPos.x - 16.0, lastPos.y - 8.0, lastPos.z - 16.0,
                    lastPos.x + 16.0, lastPos.y + 8.0, lastPos.z + 16.0
                )).find { near ->
                    near is LivingEntity && near !is net.minecraft.world.entity.decoration.ArmorStand &&
                    near != player && !MobMatcher.isTeammate(near) && MobMatcher.matches(near, MobCategory.MINIBOSS)
                }
                if (nearbyMb != null) {
                    trackedMinibossEntityId = nearbyMb.id
                    minibossLastSeenPos = nearbyMb.position()
                    minibossDisappearedTicks = 0
                    return
                }
            }

            minibossDisappearedTicks++
            // Disappearance threshold: 15 ticks (750ms) to ensure it's not a brief cloak or network tick lag
            if (minibossDisappearedTicks >= 15) {
                onMinibossDisappeared("Miniboss Disappeared / Slain")
            }
        }
    }

    fun onMinibossDisappeared(reason: String = "Defeated") {
        if (!inMinibossFight) return
        inMinibossFight = false

        val revertId = minibossPreLoadoutId
        val mbName = trackedMinibossName.takeIf { it.isNotBlank() } ?: "Miniboss"

        trackedMinibossEntityId = -1
        trackedMinibossName = ""
        minibossPreLoadoutId = null
        minibossDisappearedTicks = 0

        // If player is in blood room and a blood room rule exists for the current/target loadout, do NOT revert to clear loadout!
        val inBlood = dev.noemt.client.features.blood.AutoBloodCamp.isPlayerInBloodRoom()
        val bloodRule = rules.find { it.enabled && it.condition is LoadoutCondition.BloodRoomCondition }
        if (inBlood && bloodRule != null && currentLoadoutId == bloodRule.targetLoadoutId) {
            return
        }

        if (revertId != null && revertId != currentLoadoutId && !isTargetLoadoutEquipped(revertId)) {
            val revertName = loadouts[revertId]?.name ?: revertId
            if (isExecutingSwap) {
                // Queue the revert if combat swap is still in progress
                pendingMinibossRevert = true
                pendingMinibossRevertLoadoutId = revertId
                pendingMinibossRevertReason = "$mbName $reason"
            } else {
                ChatUtils.modMessage("&b[Loadout] &a✓ $mbName killed/disappeared! Auto-swapping back to: &e$revertName")
                swapTo(revertId, "Miniboss Slain ($reason)", force = true)
            }
        }
    }

    // Respawn Queue Tracking
    var pendingRespawnSwapLoadoutId: String? = null
        private set
    var pendingRespawnReason: String = ""
        private set
    private var wasDeadOrGhost: Boolean = false

    fun isPlayerDeadOrGhost(): Boolean {
        val player = mc.player ?: return true
        if (!player.isAlive) return true
        if (dev.noemt.client.utils.DungeonListener.thePlayer?.isDead == true) return true
        if (PlayerUtils.getHotbarSlot(0)?.skyblockId == "HAUNT_ABILITY") return true
        // If in dungeon and abilities allow flying/spectating, player is a ghost
        if (dev.noemt.client.utils.LocationUtils.inDungeon && (player.abilities.mayfly || player.abilities.flying)) return true
        return false
    }

    fun onPlayerDeath() {
        wasDeadOrGhost = true
        if (inMinibossFight) {
            val revertId = minibossPreLoadoutId ?: previousLoadoutId
            if (revertId != null) {
                pendingRespawnSwapLoadoutId = revertId
                pendingRespawnReason = "Respawned after Miniboss fight death"
            }
            resetMinibossState()
        }
        if (isExecutingSwap) {
            pendingRespawnSwapLoadoutId = pendingLoadout?.id ?: currentLoadoutId
            pendingRespawnReason = pendingReason
            resetSwap()
        }
    }

    fun resetMinibossState() {
        inMinibossFight = false
        trackedMinibossEntityId = -1
        trackedMinibossName = ""
        minibossPreLoadoutId = null
        minibossDisappearedTicks = 0
        pendingMinibossRevert = false
        pendingMinibossRevertLoadoutId = null
        pendingMinibossRevertReason = ""
    }

    private var dungeonRunTriggeredThisFloor: Boolean = false
    private var wasInBloodRoom: Boolean = false
    private var bloodRoomTriggeredThisEntry: Boolean = false
    private var lastOpenContainerId: Int = 0

    fun resetDungeonRunTrigger() {
        dungeonRunTriggeredThisFloor = false
        wasInBloodRoom = false
        bloodRoomTriggeredThisEntry = false
        lastOpenContainerId = 0
    }

    fun onWorldChange() {
        lastDetectedInstance = null
        lastDetectedArea = ""
        instanceCheckCounter = 0
        wasDeadOrGhost = false
        pendingRespawnSwapLoadoutId = null
        pendingRespawnReason = ""
        dungeonRunTriggeredThisFloor = false
        wasInBloodRoom = false
        bloodRoomTriggeredThisEntry = false
        lastOpenContainerId = 0
        pendingVerificationTargetId = null
        pendingVerificationReason = ""
        isSwapConfirmed = false
        verificationAttempts = 0
        nextVerificationCheckMs = 0L
        resetMinibossState()
        resetSwap()
    }

    fun onPlayerManualLoadoutSelect(id: String, source: String = "Manual") {
        if (!loadouts.containsKey(id)) return
        if (id == pendingVerificationTargetId) {
            isSwapConfirmed = true
            pendingVerificationTargetId = null
            verificationAttempts = 0
        }
        if (currentLoadoutId != id) {
            previousLoadoutId = currentLoadoutId
            currentLoadoutId = id
            val name = loadouts[id]?.name ?: id
            ChatUtils.modMessage("&b[Loadout] &aTracked active loadout: &e$name &7($source)")
            notifyDataChanged()
        }
    }

    fun onChatMessage(unformatted: String) {
        val clean = unformatted.removeFormatting().trim()

        // 1. Direct Regex checks for numbered loadouts
        val numRegex = Regex("""(?:Loadout\s+(\d+)\s+is\s+already\s+equipped|Equipped\s+(?:loadout\s+)?(\d+)|Equipping\s+Loadout\s+(\d+)|Swapped\s+to\s+loadout\s+(\d+)|Selected\s+Loadout\s+(\d+)|You\s+equipped\s+Loadout\s+(\d+)|Loadout\s+(\d+)\s+equipped)""", RegexOption.IGNORE_CASE)
        val match = numRegex.find(clean)
        if (match != null) {
            val num = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
            if (num != null && num in 1..12) {
                onPlayerManualLoadoutSelect("loadout_$num", "Chat Sync")
                return
            }
        }

        // 2. Custom Named Loadouts match
        for ((id, loadout) in loadouts) {
            val name = loadout.name.removeFormatting().trim()
            if (name.length < 2) continue
            if (clean.contains("$name is already equipped", ignoreCase = true) ||
                clean.contains("Equipped loadout $name", ignoreCase = true) ||
                clean.contains("Equipped: $name", ignoreCase = true) ||
                clean.contains("Equipped $name", ignoreCase = true) ||
                clean.contains("Swapped to $name", ignoreCase = true) ||
                clean.contains("Selected $name", ignoreCase = true) ||
                clean.contains("You equipped $name", ignoreCase = true)
            ) {
                onPlayerManualLoadoutSelect(id, "Chat Sync")
                return
            }
        }
    }

    // ==========================================
    // SWAP EXECUTION & STATE MACHINE
    // ==========================================

    fun isTargetLoadoutEquipped(id: String): Boolean {
        if (currentLoadoutId == id) return true
        val targetLoadout = loadouts[id] ?: return false
        val player = mc.player ?: return false

        // 1. Check head equipment (Skull texture, Dyed color, Item registry key)
        val headItem = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
        if (!headItem.isEmpty) {
            val headTexture = ItemUtils.getSkullTexture(headItem)
            if (!targetLoadout.skullTexture.isNullOrBlank() && headTexture != null) {
                if (headTexture == targetLoadout.skullTexture) {
                    currentLoadoutId = id
                    return true
                }
            }

            val headDyedColor = headItem.get(net.minecraft.core.component.DataComponents.DYED_COLOR)?.rgb()
            if (targetLoadout.dyedColor != null && headDyedColor != null) {
                if (headDyedColor == targetLoadout.dyedColor) {
                    currentLoadoutId = id
                    return true
                }
            }

            val headKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(headItem.item).toString()
            if (!targetLoadout.itemType.isNullOrBlank() && headKey == targetLoadout.itemType) {
                if (targetLoadout.skullTexture.isNullOrBlank() && targetLoadout.dyedColor == null) {
                    val othersWithSameItem = loadouts.values.count { it.id != id && it.itemType == headKey }
                    if (othersWithSameItem == 0) {
                        currentLoadoutId = id
                        return true
                    }
                }
            }
        }

        // 2. Check chestplate / armor piece names from targetLoadout.loreLines
        val chestItem = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
        if (!chestItem.isEmpty && targetLoadout.loreLines.isNotEmpty()) {
            val chestName = chestItem.hoverName.string.removeFormatting().lowercase().trim()
            if (chestName.isNotBlank() && chestName.length > 3) {
                val matchesInLore = targetLoadout.loreLines.any { line ->
                    val cl = line.removeFormatting().lowercase().trim()
                    cl.contains(chestName) || chestName.contains(cl)
                }
                if (matchesInLore) {
                    val otherMatches = loadouts.values.count { other ->
                        other.id != id && other.loreLines.any { line ->
                            val cl = line.removeFormatting().lowercase().trim()
                            cl.contains(chestName) || chestName.contains(cl)
                        }
                    }
                    if (otherMatches == 0) {
                        currentLoadoutId = id
                        return true
                    }
                }
            }
        }

        return false
    }

    fun swapTo(id: String, reason: String = "Manual", force: Boolean = false) {
        val loadout = loadouts[id] ?: run {
            ChatUtils.modMessage("&c[Loadout] Unknown loadout ID: &e$id")
            return
        }

        // If player is dead or in ghost flight, queue for respawn
        if (isPlayerDeadOrGhost()) {
            ChatUtils.modMessage("&e[Loadout] Player is dead/ghost. Queuing swap to ${loadout.name} upon respawn...")
            pendingRespawnSwapLoadoutId = id
            pendingRespawnReason = reason
            wasDeadOrGhost = true
            return
        }

        if (!force && (currentLoadoutId == id || isTargetLoadoutEquipped(id))) {
            currentLoadoutId = id
            return
        }

        if (!force && isExecutingSwap) {
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastSwapExecutionMs < 1200L) {
            return
        }
        lastSwapExecutionMs = now

        if (currentLoadoutId != id) {
            previousLoadoutId = currentLoadoutId
        }

        currentLoadoutId = id
        pendingLoadout = loadout
        pendingReason = reason

        val isEntryTrigger = reason.contains("Blood", ignoreCase = true) ||
                             reason.contains("Dungeon", ignoreCase = true) ||
                             reason.contains("Rule", ignoreCase = true) ||
                             reason.contains("Entry", ignoreCase = true) ||
                             reason.contains("Fresh", ignoreCase = true)

        if (isEntryTrigger && pendingVerificationTargetId != id) {
            pendingVerificationTargetId = id
            pendingVerificationReason = reason
            isSwapConfirmed = false
            verificationAttempts = 1
            nextVerificationCheckMs = System.currentTimeMillis() + 1200L
        }

        ChatUtils.modMessage("&b[Loadout] &aSwapping to: &e${loadout.name} &7(Slot ${loadout.loadoutSlot} | Trigger: &f$reason&7)")

        if (ConfigManager.config.loadout.playSound) {
            mc.execute {
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f))
            }
        }

        val payload = JsonObject().apply {
            addProperty("loadoutId", id)
            addProperty("loadoutName", loadout.name)
            addProperty("slot", loadout.loadoutSlot)
            addProperty("reason", reason)
            addProperty("timestamp", System.currentTimeMillis())
        }
        RemoteWebSocketClient.sendEvent("LOADOUT_SWAP", payload)

        // Stage 1: Pre /loadouts execution -> 150ms-ish delay (randomized 130ms - 175ms)
        val preDelay = Random.nextLong(130, 175)
        stageTargetTimeMs = System.currentTimeMillis() + preDelay
        currentStage = SwapStage.PRE_CMD_WAIT
    }

    fun onTick() {
        // 0. Safety timeout for ongoing automated swap
        if (isExecutingSwap && System.currentTimeMillis() - lastSwapExecutionMs > 4000L) {
            resetSwap()
        }

        // 1. Check Death / Ghost / Respawn State
        val deadOrGhost = isPlayerDeadOrGhost()
        if (wasDeadOrGhost && !deadOrGhost) {
            // Player has confirmed respawned (no longer flying / ghost mode ended)
            wasDeadOrGhost = false
            val target = pendingRespawnSwapLoadoutId
            val reason = pendingRespawnReason
            pendingRespawnSwapLoadoutId = null
            pendingRespawnReason = ""

            if (target != null && target != currentLoadoutId && !isTargetLoadoutEquipped(target)) {
                ChatUtils.modMessage("&b[Loadout] &aRespawn confirmed (grounded)! Auto-swapping to: &e${loadouts[target]?.name ?: target}")
                swapTo(target, reason)
            }
        } else if (deadOrGhost) {
            wasDeadOrGhost = true
            if (isExecutingSwap) {
                pendingRespawnSwapLoadoutId = pendingLoadout?.id ?: currentLoadoutId
                pendingRespawnReason = pendingReason
                resetSwap()
            }
            return
        }

        // 2. Miniboss Memory Tracking Check
        checkMinibossTrackingTick()

        // 3. Immediate Blood Room Entry Check every tick (strictly inside the room, not at the doorway)
        if (ConfigManager.config.loadout.enabled && (lastDetectedInstance == GameInstanceType.DUNGEONS || LocationUtils.inDungeon)) {
            val inBlood = dev.noemt.client.features.blood.AutoBloodCamp.isPlayerInBloodRoom(strict = true)
            if (inBlood) {
                if (!bloodRoomTriggeredThisEntry) {
                    val bloodRule = rules.find { it.enabled && it.condition is LoadoutCondition.BloodRoomCondition }
                    if (bloodRule != null) {
                        if (currentLoadoutId != bloodRule.targetLoadoutId && !isTargetLoadoutEquipped(bloodRule.targetLoadoutId)) {
                            if (!isExecutingSwap) {
                                bloodRoomTriggeredThisEntry = true
                                wasInBloodRoom = true
                                checkConditions(ConditionContext(inBloodRoom = true, location = "Blood Room DUNGEONS"))
                            }
                        } else {
                            currentLoadoutId = bloodRule.targetLoadoutId
                            bloodRoomTriggeredThisEntry = true
                            wasInBloodRoom = true
                        }
                    } else if (!wasInBloodRoom) {
                        wasInBloodRoom = true
                        bloodRoomTriggeredThisEntry = true
                        checkConditions(ConditionContext(inBloodRoom = true, location = "Blood Room DUNGEONS"))
                    }
                }
            } else {
                wasInBloodRoom = false
                bloodRoomTriggeredThisEntry = false
            }
        }

        // 4. Periodic Scoreboard / Instance Detection Check (every 10 ticks)
        instanceCheckCounter++
        if (instanceCheckCounter % 10 == 0) {
            checkGameInstance()
        }

        // 5. Verification & Multi-Attempt Retry Engine (Ensures loadout actually swapped on entry / rules)
        if (pendingVerificationTargetId != null && !isExecutingSwap) {
            val targetId = pendingVerificationTargetId!!
            val targetLoadout = loadouts[targetId]
            if (targetLoadout != null) {
                if (currentLoadoutId == targetId || isTargetLoadoutEquipped(targetId) || isSwapConfirmed) {
                    pendingVerificationTargetId = null
                    verificationAttempts = 0
                } else {
                    val now = System.currentTimeMillis()
                    if (now >= nextVerificationCheckMs) {
                        if (verificationAttempts < MAX_VERIFICATION_ATTEMPTS) {
                            verificationAttempts++
                            val targetName = targetLoadout.name
                            ChatUtils.modMessage("&b[Loadout] &eSwap unconfirmed for &f$targetName &e(Attempt $verificationAttempts/$MAX_VERIFICATION_ATTEMPTS)... Retrying /loadouts with short cooldown.")
                            nextVerificationCheckMs = now + 950L
                            swapTo(targetId, "$pendingVerificationReason (Retry #$verificationAttempts)", force = true)
                        } else {
                            // Max retries reached
                            pendingVerificationTargetId = null
                            verificationAttempts = 0
                        }
                    }
                }
            } else {
                pendingVerificationTargetId = null
                verificationAttempts = 0
            }
        }

        // 6. Process automated GUI swap state machine
        if (currentStage == SwapStage.IDLE) return
        val player = mc.player ?: run {
            resetSwap()
            return
        }
        val loadout = pendingLoadout ?: run {
            resetSwap()
            return
        }
        val now = System.currentTimeMillis()

        when (currentStage) {
            SwapStage.PRE_CMD_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    sendClientCommand("/loadouts")

                    guiWaitTimeoutMs = now + 2500L
                    currentStage = SwapStage.WAITING_GUI_OPEN
                }
            }

            SwapStage.WAITING_GUI_OPEN -> {
                val isGuiOpen = mc.screen is AbstractContainerScreen<*> && player.containerMenu != player.inventoryMenu
                if (isGuiOpen || inLoadoutMenu || lastOpenContainerId != 0) {
                    val guiOpenDelay = Random.nextLong(60, 95)
                    stageTargetTimeMs = now + guiOpenDelay
                    currentStage = SwapStage.GUI_OPEN_WAIT
                } else if (now >= guiWaitTimeoutMs) {
                    ChatUtils.modMessage("&e[Loadout] /loadouts GUI open timed out, executing direct actions.")
                    currentStage = SwapStage.POST_ACTIONS
                }
            }

            SwapStage.GUI_OPEN_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    val containerId = if (lastOpenContainerId != 0) lastOpenContainerId else player.containerMenu.containerId
                    val targetSlot = loadout.containerSlot

                    // Check if already equipped from GUI slot lore
                    val slotItem = player.containerMenu.slots.getOrNull(targetSlot)?.item ?: ItemStack.EMPTY
                    val isAlreadyEquippedInMenu = if (!slotItem.isEmpty) {
                        val lore = ItemUtils.run { slotItem.lore }
                        lore.any { line ->
                            val cl = line.removeFormatting().lowercase().trim()
                            (cl.contains("currently equipped") || cl.contains("already equipped") || cl == "equipped!" || cl == "equipped" || cl.contains("active loadout")) && !cl.contains("click to equip")
                        }
                    } else false

                    if (isAlreadyEquippedInMenu) {
                        currentLoadoutId = loadout.id
                        pendingVerificationTargetId = null
                        isSwapConfirmed = true
                        player.closeContainer()
                        mc.setScreen(null)
                        pendingAutoClose = true
                        currentStage = SwapStage.POST_ACTIONS
                        return
                    }

                    mc.gameMode?.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player)

                    val postClickDelay = Random.nextLong(60, 95)
                    stageTargetTimeMs = now + postClickDelay
                    currentStage = SwapStage.POST_CLICK_WAIT
                }
            }

            SwapStage.POST_CLICK_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    player.closeContainer()
                    mc.setScreen(null)
                    pendingAutoClose = true
                    currentStage = SwapStage.POST_ACTIONS
                }
            }

            SwapStage.POST_ACTIONS -> {
                loadout.slot?.let { s ->
                    PlayerUtils.swapToSlot(s)
                }

                for (extraCmd in loadout.commands) {
                    if (extraCmd.isNotBlank()) {
                        sendClientCommand(extraCmd)
                    }
                }

                resetSwap()
            }

            SwapStage.IDLE -> {}
        }
    }

    private fun checkGameInstance() {
        if (!ConfigManager.config.loadout.enabled) return
        val detected = ScoreboardUtils.detectGameInstance() ?: return
        val (instanceType, areaName) = detected

        val instanceChanged = instanceType != lastDetectedInstance || areaName != lastDetectedArea
        if (instanceChanged) {
            lastDetectedInstance = instanceType
            lastDetectedArea = areaName
            dungeonRunTriggeredThisFloor = false
            checkConditions(ConditionContext(location = "$areaName ${instanceType.name}"))
        }

        // Fresh dungeon run detection on floor restart, requeue, or Green Room entrance
        if (instanceType == GameInstanceType.DUNGEONS || LocationUtils.inDungeon) {
            val player = mc.player
            val currentRoom = player?.let { dev.noemt.client.utils.map.utils.ScanUtils.currentRoom ?: dev.noemt.client.utils.map.utils.ScanUtils.getRoomFromPos(it.position()) }
            val inGreenRoom = currentRoom?.data?.type == dev.noemt.client.utils.map.core.RoomType.ENTRANCE

            val lines = ScoreboardUtils.getSidebarLines()
            val isFreshDungeonRun = inGreenRoom || lines.any { line ->
                line.contains("Cleared: 0%", ignoreCase = true) ||
                line.contains("Time Elapsed: 00s", ignoreCase = true) ||
                line.contains("Time Elapsed: 01s", ignoreCase = true) ||
                line.contains("Time Elapsed: 02s", ignoreCase = true)
            }
            if (isFreshDungeonRun && !dungeonRunTriggeredThisFloor) {
                dungeonRunTriggeredThisFloor = true
                checkConditions(ConditionContext(location = "$areaName DUNGEONS"))
            } else if (!isFreshDungeonRun && lines.any { it.contains("Cleared:", ignoreCase = true) && !it.contains("Cleared: 0%", ignoreCase = true) }) {
                dungeonRunTriggeredThisFloor = true
            }

            // Blood Room entry transition detection (strictly inside the room)
            val inBlood = dev.noemt.client.features.blood.AutoBloodCamp.isPlayerInBloodRoom(strict = true)
            if (inBlood && !wasInBloodRoom) {
                wasInBloodRoom = true
                checkConditions(ConditionContext(inBloodRoom = true, location = "Blood Room DUNGEONS"))
            } else if (!inBlood && wasInBloodRoom) {
                wasInBloodRoom = false
            }
        }
    }

    fun onPacketOpenScreen(title: String, containerId: Int = 0) {
        val clean = title.removeFormatting().trim()
        val isLoadoutMenu = clean.contains("Loadout", ignoreCase = true) ||
                            clean.contains("Wardrobe", ignoreCase = true) ||
                            SkyblockLoadoutConstants.LOADOUT_MENU_REGEX.containsMatchIn(clean)

        if (isLoadoutMenu) {
            inLoadoutMenu = true
            lastOpenContainerId = containerId
            if (pendingAutoClose) {
                pendingAutoClose = false
                mc.player?.closeContainer()
                mc.setScreen(null)
            }
        }
    }

    fun onPacketCloseScreen() {
        inLoadoutMenu = false
        pendingAutoClose = false
        lastOpenContainerId = 0
    }

    // Auto-sync loadouts from SkyBlock container items (Silent background sync)
    fun syncFromContainerItems(items: List<ItemStack>) {
        var syncedCount = 0
        for ((index, containerSlot) in SkyblockLoadoutConstants.LOADOUT_SLOTS.withIndex()) {
            val loadoutNum = index + 1
            val item = items.getOrNull(containerSlot) ?: continue
            if (item.isEmpty) continue

            val rawName = item.hoverName.string.removeFormatting().trim()
            // Ignore filler glass panes and empty/locked slots
            if (rawName.isBlank() ||
                rawName.contains("glass pane", ignoreCase = true) ||
                rawName.contains("Empty", ignoreCase = true) ||
                rawName.contains("Locked", ignoreCase = true)
            ) continue

            val id = "loadout_$loadoutNum"
            val existing = loadouts[id]

            // Extract item registry key, skull texture, full NBT, dyed color, glint, and lore lines
            val itemKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.item).toString()
            val skull = ItemUtils.getSkullTexture(item)
            val lore = ItemUtils.run { item.lore }
            val rawNbt = ItemUtils.run { item.customData }.takeUnless { it.isEmpty }?.toString()
            val dyedColor = item.get(net.minecraft.core.component.DataComponents.DYED_COLOR)?.rgb()
            val hasGlint = item.hasFoil() || item.isEnchanted
            val petLine = lore.find { it.contains("Pet:", ignoreCase = true) }
            val extractedPet = petLine?.removeFormatting()?.substringAfter("Pet:")?.trim()?.takeIf { it.isNotBlank() }

            // Check if this slot is the currently equipped one in SkyBlock
            val isEquippedInSkyblock = lore.any { line ->
                val cl = line.removeFormatting().lowercase().trim()
                (cl.contains("currently equipped") || cl.contains("already equipped") || cl == "equipped!" || cl == "equipped" || cl.contains("active loadout")) && !cl.contains("click to equip")
            }
            if (isEquippedInSkyblock) {
                onPlayerManualLoadoutSelect(id, "Container Sync")
            }

            if (existing != null) {
                existing.name = rawName
                existing.loadoutSlot = loadoutNum
                existing.openCommand = "/loadouts"
                existing.itemType = itemKey
                existing.skullTexture = skull
                existing.nbtString = rawNbt
                existing.dyedColor = dyedColor
                existing.hasGlint = hasGlint
                existing.loreLines = lore
                if (extractedPet != null) existing.petName = extractedPet
            } else {
                loadouts[id] = Loadout(
                    id = id,
                    name = rawName,
                    loadoutSlot = loadoutNum,
                    openCommand = "/loadouts",
                    itemType = itemKey,
                    skullTexture = skull,
                    nbtString = rawNbt,
                    dyedColor = dyedColor,
                    hasGlint = hasGlint,
                    loreLines = lore,
                    petName = extractedPet,
                    slot = null
                )
            }
            syncedCount++
        }

        if (syncedCount > 0) {
            saveData()
            notifyDataChanged()
        }
    }

    fun clickMenuSlot(index: Int, autoClose: Boolean = true) {
        val player = mc.player ?: return
        if (index !in 0..11) return
        val slot = SkyblockLoadoutConstants.LOADOUT_SLOTS[index]
        val containerId = player.containerMenu.containerId
        mc.gameMode?.handleContainerInput(containerId, slot, 0, ContainerInput.PICKUP, player)
        lastManualClick = System.currentTimeMillis()

        val targetLoadout = loadouts.values.find { it.loadoutSlot == (index + 1) }
        if (targetLoadout != null) {
            if (currentLoadoutId != targetLoadout.id) {
                previousLoadoutId = currentLoadoutId
            }
            currentLoadoutId = targetLoadout.id
        }

        if (autoClose) {
            player.closeContainer()
            pendingAutoClose = true
        }
    }

    private fun resetSwap() {
        currentStage = SwapStage.IDLE
        pendingLoadout = null
        stageTargetTimeMs = 0L
    }

    fun requestSkyblockSync() {
        sendClientCommand("/loadouts")
    }

    private fun sendClientCommand(command: String) {
        val player = mc.player ?: return
        val text = command.trim()
        if (text.startsWith("/")) {
            player.connection.sendCommand(text.removePrefix("/"))
        } else {
            player.connection.sendChat(text)
        }
    }

    fun checkConditions(context: ConditionContext) {
        if (isExecutingSwap) return
        val now = System.currentTimeMillis()

        for (rule in rules) {
            if (!rule.enabled) continue
            val target = rule.targetLoadoutId
            if (rule.onlyIfNotCurrent && (currentLoadoutId == target || isTargetLoadoutEquipped(target))) {
                currentLoadoutId = target
                continue
            }

            val cooldownMs = (rule.cooldownSeconds * 1000).toLong()
            if (now - rule.lastTriggeredMs < cooldownMs) continue

            if (rule.condition.matches(context)) {
                rule.lastTriggeredMs = now

                val cond = rule.condition
                if (cond is LoadoutCondition.MinibossCondition ||
                    (cond is LoadoutCondition.AimCondition && cond.mobCategory == MobCategory.MINIBOSS)) {
                    val aimed = context.aimedEntity
                    if (aimed != null) {
                        onMinibossEngaged(aimed, target)
                    } else {
                        swapTo(target, "Rule: ${rule.name}")
                    }
                } else {
                    swapTo(target, "Rule: ${rule.name}")
                }
                break
            }
        }
    }

    fun addOrUpdateLoadout(loadout: Loadout) {
        loadouts[loadout.id] = loadout
        saveData()
        notifyDataChanged()
    }

    fun removeLoadout(id: String): Boolean {
        val removed = loadouts.remove(id) != null
        if (removed) {
            rules.removeIf { it.targetLoadoutId == id }
            saveData()
            notifyDataChanged()
        }
        return removed
    }

    fun addOrUpdateRule(rule: LoadoutRule) {
        rules.removeIf { it.id == rule.id }
        rules.add(rule)
        saveData()
        notifyDataChanged()
    }

    fun removeRule(id: String): Boolean {
        val removed = rules.removeIf { it.id == id }
        if (removed) {
            saveData()
            notifyDataChanged()
        }
        return removed
    }

    fun notifyDataChanged() {
        mc.execute {
            onDataChanged?.invoke()
        }
    }

    private fun setupDefaults() {
        for (i in 1..12) {
            val defaultName = when (i) {
                1 -> "Loadout 1 (Clear / Speed)"
                2 -> "Loadout 2 (Boss / DPS)"
                3 -> "Loadout 3 (Tank / EHP)"
                4 -> "Loadout 4 (Magic / Utility)"
                else -> "Loadout $i"
            }
            loadouts["loadout_$i"] = Loadout(
                id = "loadout_$i",
                name = defaultName,
                loadoutSlot = i,
                openCommand = "/loadouts",
                slot = null
            )
        }

        // Default Rules
        rules.add(
            LoadoutRule(
                id = "aim_miniboss",
                name = "Miniboss Auto-Swap (Auto-Reverts on Kill)",
                enabled = true,
                targetLoadoutId = "loadout_2",
                condition = LoadoutCondition.MinibossCondition(autoRevertOnKill = true),
                cooldownSeconds = 3.0
            )
        )

        rules.add(
            LoadoutRule(
                id = "aim_blood_mobs",
                name = "Aimed at Blood Mob",
                enabled = true,
                targetLoadoutId = "loadout_2",
                condition = LoadoutCondition.AimCondition(mobCategory = MobCategory.BLOOD_MOB),
                cooldownSeconds = 3.0
            )
        )

        rules.add(
            LoadoutRule(
                id = "aim_watcher",
                name = "Aimed at The Watcher",
                enabled = true,
                targetLoadoutId = "loadout_2",
                condition = LoadoutCondition.AimCondition(mobCategory = MobCategory.WATCHER),
                cooldownSeconds = 3.0
            )
        )

        rules.add(
            LoadoutRule(
                id = "enter_blood_room",
                name = "Enter Blood Room ➜ Loadout 2 (DPS / Boss)",
                enabled = true,
                targetLoadoutId = "loadout_2",
                condition = LoadoutCondition.BloodRoomCondition(),
                cooldownSeconds = 5.0
            )
        )

        rules.add(
            LoadoutRule(
                id = "join_dungeon",
                name = "Join Catacombs ➜ Loadout 1 (Clear)",
                enabled = true,
                targetLoadoutId = "loadout_1",
                condition = LoadoutCondition.GameInstanceCondition(instanceType = GameInstanceType.DUNGEONS),
                cooldownSeconds = 5.0
            )
        )
    }

    private fun loadData() {
        try {
            if (configFile.exists()) {
                val text = configFile.readText()
                val element = com.google.gson.JsonParser.parseString(text)
                if (element.isJsonObject) {
                    val root = element.asJsonObject
                    if (root.has("loadoutAId")) {
                        loadoutAId = root.get("loadoutAId").asString
                    }
                    if (root.has("loadoutBId")) {
                        loadoutBId = root.get("loadoutBId").asString
                    }
                    if (root.has("loadouts")) {
                        val type = object : TypeToken<Map<String, Loadout>>() {}.type
                        val loaded: Map<String, Loadout>? = gson.fromJson(root.get("loadouts"), type)
                        if (loaded != null) {
                            loadouts.clear()
                            for ((k, v) in loaded) {
                                v.openCommand = "/loadouts"
                                if (v.slot == (v.loadoutSlot - 1)) {
                                    v.slot = null
                                }
                                loadouts[k] = v
                            }
                        }
                    } else {
                        val type = object : TypeToken<Map<String, Loadout>>() {}.type
                        val loaded: Map<String, Loadout>? = gson.fromJson(element, type)
                        if (loaded != null) {
                            loadouts.clear()
                            for ((k, v) in loaded) {
                                v.openCommand = "/loadouts"
                                if (v.slot == (v.loadoutSlot - 1)) {
                                    v.slot = null
                                }
                                loadouts[k] = v
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }

        try {
            if (rulesFile.exists()) {
                val type = object : TypeToken<List<LoadoutRule>>() {}.type
                val loaded: List<LoadoutRule>? = gson.fromJson(rulesFile.readText(), type)
                if (loaded != null) {
                    rules.clear()
                    rules.addAll(loaded)
                }
            }
        } catch (e: Exception) {
            // fallback
        }
    }

    fun saveData() {
        try {
            val root = JsonObject()
            root.addProperty("loadoutAId", loadoutAId)
            root.addProperty("loadoutBId", loadoutBId)
            root.add("loadouts", gson.toJsonTree(loadouts))
            configFile.writeText(gson.toJson(root))
            rulesFile.writeText(gson.toJson(rules))
        } catch (e: Exception) {
            ChatUtils.modMessage("&cFailed saving loadouts: ${e.message}")
        }
    }
}
