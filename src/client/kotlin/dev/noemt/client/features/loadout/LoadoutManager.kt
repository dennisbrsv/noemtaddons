package dev.noemt.client.features.loadout

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.remote.RemoteWebSocketClient
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.PlayerUtils
import dev.noemt.client.utils.ThreadUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.inventory.ContainerInput
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
        val target = previousLoadoutId ?: loadoutAId
        swapTo(target, "Swap Back Keybind (Last Loadout)")
    }

    fun swapTo(id: String, reason: String = "Manual", force: Boolean = false) {
        val loadout = loadouts[id] ?: run {
            ChatUtils.modMessage("&c[Loadout] Unknown loadout ID: &e$id")
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

        ChatUtils.modMessage("&b[Loadout] &aSwapping to: &e${loadout.name} &7(Trigger: &f$reason&7)")

        // Play feedback sound if enabled
        if (ConfigManager.config.loadout.playSound) {
            mc.execute {
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f))
            }
        }

        // Notify Remote WebSocket Server
        val payload = JsonObject().apply {
            addProperty("loadoutId", id)
            addProperty("loadoutName", loadout.name)
            addProperty("reason", reason)
            addProperty("timestamp", System.currentTimeMillis())
        }
        RemoteWebSocketClient.sendEvent("LOADOUT_SWAP", payload)

        // Stage 1: Pre /loadout execution -> 150ms-ish delay (randomized 130ms - 170ms)
        val preDelay = Random.nextLong(130, 175)
        stageTargetTimeMs = System.currentTimeMillis() + preDelay
        currentStage = SwapStage.PRE_CMD_WAIT
    }

    fun onTick() {
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
                    // Send open command (e.g. /wardrobe or /loadout)
                    val cmd = loadout.openCommand.takeIf { it.isNotBlank() } ?: "/wardrobe"
                    sendClientCommand(cmd)

                    // Stage 2: Wait for GUI to open (with 2500ms fallback timeout)
                    guiWaitTimeoutMs = now + 2500L
                    currentStage = SwapStage.WAITING_GUI_OPEN
                }
            }

            SwapStage.WAITING_GUI_OPEN -> {
                val isGuiOpen = mc.screen is AbstractContainerScreen<*> && player.containerMenu != player.inventoryMenu
                if (isGuiOpen) {
                    // Stage 3: GUI open -> 100ms-ish delay (randomized 85ms - 125ms)
                    val guiOpenDelay = Random.nextLong(85, 125)
                    stageTargetTimeMs = now + guiOpenDelay
                    currentStage = SwapStage.GUI_OPEN_WAIT
                } else if (now >= guiWaitTimeoutMs) {
                    // Timeout fallback: Skip GUI click and run fallback actions
                    ChatUtils.modMessage("&e[Loadout] GUI open timed out, executing direct actions.")
                    currentStage = SwapStage.POST_ACTIONS
                }
            }

            SwapStage.GUI_OPEN_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    val containerId = player.containerMenu.containerId

                    // Calculate target slot: Custom guiSlot or Hypixel wardrobe slot calculation
                    val targetSlot = loadout.guiSlot
                        ?: if (loadout.wardrobeSlot != null) (35 + loadout.wardrobeSlot!!)
                        else 36

                    // Click the new loadout slot
                    mc.gameMode?.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player)

                    // Stage 4: Post-click delay -> 100ms-ish delay (randomized 85ms - 125ms)
                    val postClickDelay = Random.nextLong(85, 125)
                    stageTargetTimeMs = now + postClickDelay
                    currentStage = SwapStage.POST_CLICK_WAIT
                }
            }

            SwapStage.POST_CLICK_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    // Stage 5: Close the GUI
                    player.closeContainer()
                    mc.setScreen(null)

                    // Stage 6: Post-swap actions (Hotbar slot, Pet, custom commands)
                    currentStage = SwapStage.POST_ACTIONS
                }
            }

            SwapStage.POST_ACTIONS -> {
                // Hotbar slot swap
                loadout.slot?.let { s ->
                    PlayerUtils.swapToSlot(s)
                }

                // Pet command if specified
                loadout.petName?.takeIf { it.isNotBlank() }?.let { pet ->
                    sendClientCommand("/pet $pet")
                }

                // Additional custom commands
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

    private fun resetSwap() {
        currentStage = SwapStage.IDLE
        pendingLoadout = null
        stageTargetTimeMs = 0L
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
                swapTo(target, "Rule: ${rule.name}")
                break
            }
        }
    }

    fun addOrUpdateLoadout(loadout: Loadout) {
        loadouts[loadout.id] = loadout
        saveData()
    }

    fun removeLoadout(id: String): Boolean {
        val removed = loadouts.remove(id) != null
        if (removed) {
            rules.removeIf { it.targetLoadoutId == id }
            saveData()
        }
        return removed
    }

    fun addOrUpdateRule(rule: LoadoutRule) {
        rules.removeIf { it.id == rule.id }
        rules.add(rule)
        saveData()
    }

    fun removeRule(id: String): Boolean {
        val removed = rules.removeIf { it.id == id }
        if (removed) saveData()
        return removed
    }

    private fun setupDefaults() {
        loadouts["loadout_1"] = Loadout(
            id = "loadout_1",
            name = "Clear / Speed",
            openCommand = "/wardrobe",
            wardrobeSlot = 1,
            slot = 0,
            delayMs = 100L
        )

        loadouts["loadout_2"] = Loadout(
            id = "loadout_2",
            name = "Boss / DPS",
            openCommand = "/wardrobe",
            wardrobeSlot = 2,
            slot = 1,
            delayMs = 100L
        )

        loadouts["loadout_3"] = Loadout(
            id = "loadout_3",
            name = "Tank / Survivability",
            openCommand = "/wardrobe",
            wardrobeSlot = 3,
            slot = 2,
            delayMs = 100L
        )

        // Default Rules
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
                id = "aim_miniboss",
                name = "Aimed at Miniboss",
                enabled = true,
                targetLoadoutId = "loadout_2",
                condition = LoadoutCondition.AimCondition(mobCategory = MobCategory.MINIBOSS),
                cooldownSeconds = 3.0
            )
        )

        rules.add(
            LoadoutRule(
                id = "boss_chat_alert",
                name = "Boss Dialogue Trigger",
                enabled = true,
                targetLoadoutId = "loadout_2",
                condition = LoadoutCondition.ChatCondition(pattern = "[BOSS] ", matchType = MatchType.CONTAINS),
                cooldownSeconds = 4.0
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
                    loadouts.putAll(loaded)
                }
            }
        } catch (e: Exception) {
            ChatUtils.modMessage("&cFailed loading loadouts.json: ${e.message}")
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
            // Use defaults if rules format changed
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
