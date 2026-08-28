package dev.noemt.client.features.loadout

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.remote.RemoteWebSocketClient
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.ItemUtils.skyblockId
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
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

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
        val target = previousLoadoutId
        if (target == null || target == currentLoadoutId) {
            ChatUtils.modMessage("&e[Loadout] No previous loadout recorded.")
            return
        }
        val targetName = loadouts[target]?.name ?: target
        swapTo(target, "Revert (Last Equipped: $targetName)")
    }

    // ==========================================
    // MINIBOSS MEMORY TRACKING ENGINE
    // ==========================================

    fun onMinibossEngaged(entity: Entity, targetLoadoutId: String) {
        val entityId = entity.id
        if (inMinibossFight && trackedMinibossEntityId == entityId) return

        val entityName = MobMatcher.getAllEntityNames(entity).firstOrNull() ?: "Miniboss"
        trackedMinibossEntityId = entityId
        trackedMinibossName = entityName
        minibossPreLoadoutId = currentLoadoutId
        minibossLastSeenPos = entity.position()
        minibossDisappearedTicks = 0
        minibossEngageTimeMs = System.currentTimeMillis()
        inMinibossFight = true

        ChatUtils.modMessage("&b[Loadout] &6⚔️ Engaged Miniboss: &e$entityName &7➜ Swapping to combat set...")
        swapTo(targetLoadoutId, "Miniboss Encounter: $entityName")
    }

    fun onEntityRemoved(entityId: Int) {
        if (inMinibossFight && entityId == trackedMinibossEntityId) {
            onMinibossDisappeared("Entity Removed Packet")
        }
    }

    private fun checkMinibossTrackingTick() {
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
                ((trackedEntity as? LivingEntity)?.health ?: 1f) > 0f

        if (isAliveAndPresent && trackedEntity != null) {
            minibossLastSeenPos = trackedEntity.position()
            minibossDisappearedTicks = 0

            // If player ran away far (> 50 blocks)
            val dist = player.position().distanceTo(trackedEntity.position())
            if (dist > 50.0 && System.currentTimeMillis() - minibossEngageTimeMs > 6000L) {
                onMinibossDisappeared("Player Moved Away")
            }
        } else {
            minibossDisappearedTicks++
            // After 2 ticks of disappearance, trigger revert
            if (minibossDisappearedTicks >= 2) {
                onMinibossDisappeared("Entity Disappeared / Slain")
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

        if (revertId != null && revertId != currentLoadoutId) {
            val revertName = loadouts[revertId]?.name ?: revertId
            ChatUtils.modMessage("&b[Loadout] &a✓ $mbName killed/disappeared! Auto-swapping back to: &e$revertName")
            swapTo(revertId, "Miniboss Slain ($reason)")
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
    }

    // ==========================================
    // SWAP EXECUTION & STATE MACHINE
    // ==========================================

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

        if (!force && currentLoadoutId == id && isExecutingSwap) {
            return
        }

        if (currentLoadoutId != id) {
            previousLoadoutId = currentLoadoutId
        }

        currentLoadoutId = id
        pendingLoadout = loadout
        pendingReason = reason

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
        // 1. Check Death / Ghost / Respawn State
        val deadOrGhost = isPlayerDeadOrGhost()
        if (wasDeadOrGhost && !deadOrGhost) {
            // Player has confirmed respawned (no longer flying / ghost mode ended)
            wasDeadOrGhost = false
            val target = pendingRespawnSwapLoadoutId
            val reason = pendingRespawnReason
            pendingRespawnSwapLoadoutId = null
            pendingRespawnReason = ""

            if (target != null && target != currentLoadoutId) {
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

        // 3. Periodic Scoreboard / Instance Detection Check (every 10 ticks)
        instanceCheckCounter++
        if (instanceCheckCounter % 10 == 0) {
            checkGameInstance()
        }

        // 3. Process automated GUI swap state machine
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
                if (isGuiOpen || inLoadoutMenu) {
                    val guiOpenDelay = Random.nextLong(85, 125)
                    stageTargetTimeMs = now + guiOpenDelay
                    currentStage = SwapStage.GUI_OPEN_WAIT
                } else if (now >= guiWaitTimeoutMs) {
                    ChatUtils.modMessage("&e[Loadout] /loadouts GUI open timed out, executing direct actions.")
                    currentStage = SwapStage.POST_ACTIONS
                }
            }

            SwapStage.GUI_OPEN_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    val containerId = player.containerMenu.containerId
                    val targetSlot = loadout.containerSlot

                    mc.gameMode?.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player)

                    val postClickDelay = Random.nextLong(85, 125)
                    stageTargetTimeMs = now + postClickDelay
                    currentStage = SwapStage.POST_CLICK_WAIT
                }
            }

            SwapStage.POST_CLICK_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    player.closeContainer()
                    mc.setScreen(null)
                    currentStage = SwapStage.POST_ACTIONS
                }
            }

            SwapStage.POST_ACTIONS -> {
                loadout.slot?.let { s ->
                    PlayerUtils.swapToSlot(s)
                }

                loadout.petName?.takeIf { it.isNotBlank() }?.let { pet ->
                    sendClientCommand("/pet $pet")
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

        if (instanceType != lastDetectedInstance || areaName != lastDetectedArea) {
            lastDetectedInstance = instanceType
            lastDetectedArea = areaName
            checkConditions(ConditionContext(location = "$areaName ${instanceType.name}"))
        }
    }

    fun onPacketOpenScreen(title: String) {
        inLoadoutMenu = title.matches(SkyblockLoadoutConstants.LOADOUT_MENU_REGEX)
    }

    fun onPacketCloseScreen() {
        inLoadoutMenu = false
        pendingAutoClose = false
    }

    // Auto-sync loadouts from SkyBlock container items
    fun syncFromContainerItems(items: List<ItemStack>) {
        var syncedCount = 0
        for ((index, containerSlot) in SkyblockLoadoutConstants.LOADOUT_SLOTS.withIndex()) {
            val loadoutNum = index + 1
            val item = items.getOrNull(containerSlot) ?: continue
            if (item.isEmpty) continue

            val rawName = item.hoverName.string.replace(Regex("§[0-9a-fk-or]"), "").trim()
            if (rawName.isBlank() || rawName.equals("Empty", ignoreCase = true)) continue

            val id = "loadout_$loadoutNum"
            val existing = loadouts[id]

            if (existing != null) {
                existing.name = rawName
                existing.loadoutSlot = loadoutNum
                existing.openCommand = "/loadouts"
            } else {
                loadouts[id] = Loadout(
                    id = id,
                    name = rawName,
                    loadoutSlot = loadoutNum,
                    openCommand = "/loadouts",
                    slot = if (index < 9) index else null
                )
            }
            syncedCount++
        }

        if (syncedCount > 0) {
            saveData()
            ChatUtils.modMessage("&b[Loadout] &aAuto-synced &e$syncedCount &aloadouts from SkyBlock /loadouts menu!")
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
            if (rule.onlyIfNotCurrent && currentLoadoutId == target) continue

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

    private fun notifyDataChanged() {
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
                slot = if (i <= 9) (i - 1) else null
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
                val type = object : TypeToken<Map<String, Loadout>>() {}.type
                val loaded: Map<String, Loadout>? = gson.fromJson(configFile.readText(), type)
                if (loaded != null) {
                    loadouts.clear()
                    for ((k, v) in loaded) {
                        v.openCommand = "/loadouts"
                        loadouts[k] = v
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
            configFile.writeText(gson.toJson(loadouts))
            rulesFile.writeText(gson.toJson(rules))
        } catch (e: Exception) {
            ChatUtils.modMessage("&cFailed saving loadouts: ${e.message}")
        }
    }
}
