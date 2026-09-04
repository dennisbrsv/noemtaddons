package dev.noemt.client.features.mask

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.features.loadout.LoadoutManager
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.ItemUtils
import dev.noemt.client.utils.ItemUtils.cleanDisplayName
import dev.noemt.client.utils.ItemUtils.cleanLore
import dev.noemt.client.utils.ItemUtils.itemUUID
import dev.noemt.client.utils.ItemUtils.skyblockId
import dev.noemt.client.utils.LocationUtils
import dev.noemt.client.utils.MouseRotationHelper
import dev.noemt.client.utils.PathfindingUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import kotlin.random.Random

object AutoMaskManager {
    private val mc: Minecraft get() = Minecraft.getInstance()

    // State tracking
    var isMaskEquipped: Boolean = false
        private set
    var activeMaskType: MaskType? = null
        private set
    var originalHelmet: OriginalHelmetData? = null
        private set
    var originalLoadoutId: String? = null
        private set

    // Cooldown timestamps (ms)
    var spiritCooldownUntilMs: Long = 0L
        private set
    var bonzoCooldownUntilMs: Long = 0L
        private set
    var spiritCooldownDurationMs: Long = 30_000L
        private set
    var bonzoCooldownDurationMs: Long = 212_000L
        private set

    // Anti-spam and execution timestamps
    private var lastSwapExecutionMs: Long = 0L
    private var swapCooldownUntilMs: Long = 0L
    private var maskEquippedTimeMs: Long = 0L
    private var queuedRevertTimeMs: Long = 0L
    private var pendingRevertAfterChat: Boolean = false
    private var pendingAutoClose: Boolean = false

    // State Machine
    private var currentStage: MaskSwapStage = MaskSwapStage.IDLE
    private var currentMode: MaskSwapMode = MaskSwapMode.EQUIP_MASK
    private var stageTargetTimeMs: Long = 0L
    private var guiWaitTimeoutMs: Long = 0L
    private var targetInventorySlot: Int = -1
    private var pendingReason: String = ""
    private var lastOpenContainerId: Int = 0
    var inEquipmentMenu: Boolean = false
        private set

    // Instance / Area / Server tracking
    var lastDetectedInstance: dev.noemt.client.features.loadout.GameInstanceType? = null
        private set
    var lastDetectedArea: String = ""
        private set
    var lastDetectedServerId: String? = null
        private set
    private var instanceCheckCounter = 0

    val isExecutingSwap: Boolean get() = currentStage != MaskSwapStage.IDLE
    val isSwapping: Boolean get() = isExecutingSwap || inEquipmentMenu || System.currentTimeMillis() < swapCooldownUntilMs

    fun isSpiritOnCooldown(): Boolean = System.currentTimeMillis() < spiritCooldownUntilMs
    fun isBonzoOnCooldown(): Boolean = System.currentTimeMillis() < bonzoCooldownUntilMs

    fun getSpiritCooldownRemainingSeconds(): Float {
        val diff = spiritCooldownUntilMs - System.currentTimeMillis()
        return if (diff > 0) diff / 1000f else 0f
    }

    fun getBonzoCooldownRemainingSeconds(): Float {
        val diff = bonzoCooldownUntilMs - System.currentTimeMillis()
        return if (diff > 0) diff / 1000f else 0f
    }

    // Scans inventory slots (0..35) for Bonzo's and Spirit Masks and parses lore-scaled cooldowns
    fun getTrackedMasks(): List<TrackedMaskItem> {
        val player = mc.player ?: return emptyList()
        val list = mutableListOf<TrackedMaskItem>()
        val now = System.currentTimeMillis()

        for (i in 0 until 36) {
            val stack = player.inventory.getItem(i)
            if (stack.isEmpty) continue

            val maskType = MaskType.fromItemStack(stack) ?: continue
            val parsedDuration = MaskType.parseCooldownMs(stack, maskType.baseCooldownMs)
            when (maskType) {
                MaskType.SPIRIT -> spiritCooldownDurationMs = parsedDuration
                MaskType.BONZO -> bonzoCooldownDurationMs = parsedDuration
            }

            val onCooldown = when (maskType) {
                MaskType.SPIRIT -> now < spiritCooldownUntilMs
                MaskType.BONZO -> now < bonzoCooldownUntilMs
            }
            val remainingMs = when (maskType) {
                MaskType.SPIRIT -> maxOf(0L, spiritCooldownUntilMs - now)
                MaskType.BONZO -> maxOf(0L, bonzoCooldownUntilMs - now)
            }

            list.add(
                TrackedMaskItem(
                    type = maskType,
                    inventorySlot = i,
                    item = stack,
                    displayName = stack.cleanDisplayName,
                    cooldownDurationMs = parsedDuration,
                    isOnCooldown = onCooldown,
                    cooldownRemainingMs = remainingMs
                )
            )
        }
        return list
    }

    fun isWearingMask(): Boolean {
        val player = mc.player ?: return false
        val worn = player.getItemBySlot(EquipmentSlot.HEAD)
        val type = MaskType.fromItemStack(worn)
        if (type != null) {
            val parsedDuration = MaskType.parseCooldownMs(worn, type.baseCooldownMs)
            when (type) {
                MaskType.SPIRIT -> spiritCooldownDurationMs = parsedDuration
                MaskType.BONZO -> bonzoCooldownDurationMs = parsedDuration
            }
        }
        return type != null
    }

    fun getCurrentlyWornMaskType(): MaskType? {
        val player = mc.player ?: return null
        val worn = player.getItemBySlot(EquipmentSlot.HEAD)
        return MaskType.fromItemStack(worn)
    }

    fun onTick() {
        val config = ConfigManager.config.mask
        val player = mc.player ?: run {
            resetFullState("Player is null")
            return
        }

        // 0. Safety timeout for ongoing automated swap (swap failure)
        if (isExecutingSwap && System.currentTimeMillis() - lastSwapExecutionMs > 4000L) {
            ChatUtils.modMessage("&c[AutoMask] Swap timed out (4s safety limit)! Resetting state.")
            resetFullState("Swap Safety Timeout")
        }

        // 1. Check if player is dead or ghost
        if (LoadoutManager.isPlayerDeadOrGhost() || player.isDeadOrDying || !player.isAlive || player.health <= 0f) {
            if (isExecutingSwap || isMaskEquipped || pendingRevertAfterChat) {
                resetFullState("Player Death / Ghost")
            }
            return
        }

        // 2. Periodic Scoreboard / Server / Instance Detection Check (every 10 ticks)
        instanceCheckCounter++
        if (instanceCheckCounter % 10 == 0) {
            val snapshot = dev.noemt.client.utils.ScoreboardUtils.getSnapshot()
            val detected = dev.noemt.client.utils.ScoreboardUtils.detectGameInstance()
            val serverId = snapshot.serverId

            val serverChanged = serverId != null && serverId != lastDetectedServerId && lastDetectedServerId != null
            if (serverChanged) {
                lastDetectedServerId = serverId
                resetFullState("Server Switch ($serverId)", resetCooldowns = true)
            } else if (serverId != null) {
                lastDetectedServerId = serverId
            }

            if (detected != null) {
                val (instanceType, areaName) = detected
                val instanceChanged = instanceType != lastDetectedInstance || areaName != lastDetectedArea
                if (instanceChanged) {
                    lastDetectedInstance = instanceType
                    lastDetectedArea = areaName
                    resetFullState("Instance Switch: $areaName ($instanceType)", resetCooldowns = true)
                }
            }
        }

        // 3. Process queued chat-triggered revert
        if (pendingRevertAfterChat && !isExecutingSwap) {
            val now = System.currentTimeMillis()
            if (now >= queuedRevertTimeMs) {
                pendingRevertAfterChat = false
                swapBackToOriginalHelmet("Mask Ability Triggered")
            }
        }

        // 4. Optional Auto-Revert Timeout check if mask never procced
        if (isMaskEquipped && config.autoRevertTimeout > 0f && !isExecutingSwap && !pendingRevertAfterChat) {
            val activeDurationSec = (System.currentTimeMillis() - maskEquippedTimeMs) / 1000f
            if (activeDurationSec >= config.autoRevertTimeout) {
                ChatUtils.modMessage("&b[AutoMask] &eAuto-revert timeout reached (${config.autoRevertTimeout.toInt()}s). Swapping back to original helmet...")
                swapBackToOriginalHelmet("Timeout")
            }
        }

        // 5. Process active GUI swap state machine
        if (currentStage != MaskSwapStage.IDLE) {
            processSwapStateMachine()
            return
        }

        // 6. Health Monitoring Check
        if (!config.enabled) return
        if (!LocationUtils.inSkyblock && !LocationUtils.inDungeon) return

        // Boss Room Check (unless configured otherwise)
        if (LocationUtils.inBoss && !config.allowInBoss) return

        // If player is normally wearing a Spirit Mask (e.g. during clear), completely ignore the feature
        val wornType = getCurrentlyWornMaskType()
        if (wornType == MaskType.SPIRIT && !isMaskEquipped) return

        // Do not trigger if already wearing a mask
        if (isWearingMask() || isMaskEquipped) return

        // Check vanilla hearts threshold (10.0 full hearts is banned to prevent run-start protocol errors)
        val threshold = config.triggerHearts.coerceIn(1.0f, 9.5f)
        val currentHearts = player.health / 2.0f
        if (player.health > 0f && currentHearts <= threshold && currentHearts < 10.0f) {
            val now = System.currentTimeMillis()
            if (now - lastSwapExecutionMs >= 1500L && now >= swapCooldownUntilMs) {
                triggerLowHealthSwap(currentHearts)
            }
        }
    }

    private fun triggerLowHealthSwap(currentHearts: Float) {
        val tracked = getTrackedMasks()
        if (tracked.isEmpty()) return

        val config = ConfigManager.config.mask

        // Filter masks by availability - MUST NOT BE ON COOLDOWN!
        val offCooldownMasks = tracked.filter { !it.isOnCooldown }
        if (offCooldownMasks.isEmpty()) {
            // All tracked masks are on cooldown - plainly refuse to swap!
            return
        }

        // Prioritize mask selection among off-cooldown masks only
        val chosenMask = when (config.maskPriority) {
            0 -> offCooldownMasks.find { it.type == MaskType.SPIRIT } ?: offCooldownMasks.find { it.type == MaskType.BONZO } ?: offCooldownMasks.first()
            1 -> offCooldownMasks.find { it.type == MaskType.BONZO } ?: offCooldownMasks.find { it.type == MaskType.SPIRIT } ?: offCooldownMasks.first()
            else -> offCooldownMasks.first()
        }

        val reason = "Low Health (${"%.1f".format(currentHearts)} ❤)"
        swapToMask(chosenMask, reason)
    }

    fun swapToMask(target: TrackedMaskItem, reason: String = "Manual") {
        val player = mc.player ?: return
        if (isExecutingSwap) return

        // Refuse to swap if the target mask is on cooldown
        val isOnCooldown = when (target.type) {
            MaskType.SPIRIT -> isSpiritOnCooldown()
            MaskType.BONZO -> isBonzoOnCooldown()
        }
        if (isOnCooldown) {
            val remainingSec = when (target.type) {
                MaskType.SPIRIT -> getSpiritCooldownRemainingSeconds()
                MaskType.BONZO -> getBonzoCooldownRemainingSeconds()
            }
            ChatUtils.modMessage("&b[AutoMask] &cCannot swap to &e${target.displayName}&c because it is on cooldown (&e${"%.1f".format(remainingSec)}s&c remaining)!")
            return
        }

        // Record currently worn helmet before swapping
        val headItem = player.getItemBySlot(EquipmentSlot.HEAD)
        if (!headItem.isEmpty && MaskType.fromItemStack(headItem) == null) {
            originalHelmet = OriginalHelmetData(
                displayName = headItem.cleanDisplayName,
                skyblockId = headItem.skyblockId,
                itemUUID = headItem.itemUUID,
                skullTexture = ItemUtils.getSkullTexture(headItem),
                dyedColor = headItem.get(DataComponents.DYED_COLOR)?.rgb(),
                itemType = BuiltInRegistries.ITEM.getKey(headItem.item).toString(),
                loreLines = ItemUtils.run { headItem.lore },
                inventorySlot = target.inventorySlot
            )
        }
        originalLoadoutId = LoadoutManager.currentLoadoutId

        // Interrupt any ongoing loadout swap since helmet swapping has priority
        if (LoadoutManager.isExecutingSwap) {
            ChatUtils.modMessage("&e[AutoMask] Interrupting regular loadout swap for emergency mask swap.")
            LoadoutManager.abortSwap("Emergency Mask Swap")
        }

        lastSwapExecutionMs = System.currentTimeMillis()
        currentMode = MaskSwapMode.EQUIP_MASK
        targetInventorySlot = target.inventorySlot
        pendingReason = reason
        activeMaskType = target.type

        ChatUtils.modMessage("&b[AutoMask] &c⚔️ $reason! &aEquipping &e${target.displayName}&a...")

        if (ConfigManager.config.mask.playSound) {
            mc.execute {
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.8f))
            }
        }

        // Suppress player rotation & movement
        PathfindingUtils.stopMovement()
        MouseRotationHelper.clearTarget()
        MouseRotationHelper.isSuppressed = true

        val preDelay = Random.nextLong(130, 175)
        stageTargetTimeMs = System.currentTimeMillis() + preDelay
        currentStage = MaskSwapStage.PRE_CMD_WAIT
    }

    fun swapBackToOriginalHelmet(reason: String = "Restoring Helmet") {
        val player = mc.player ?: return
        if (isExecutingSwap) return

        val orig = originalHelmet
        val targetName = orig?.displayName ?: "Original Helmet"

        ChatUtils.modMessage("&b[AutoMask] &a✓ $reason! Swapping back to &e$targetName&a...")

        if (ConfigManager.config.mask.playSound) {
            mc.execute {
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.4f))
            }
        }

        lastSwapExecutionMs = System.currentTimeMillis()
        currentMode = MaskSwapMode.REVERT_HELMET
        targetInventorySlot = orig?.inventorySlot ?: -1
        pendingReason = reason

        PathfindingUtils.stopMovement()
        MouseRotationHelper.clearTarget()
        MouseRotationHelper.isSuppressed = true

        val preDelay = Random.nextLong(130, 175)
        stageTargetTimeMs = System.currentTimeMillis() + preDelay
        currentStage = MaskSwapStage.PRE_CMD_WAIT
    }

    private fun processSwapStateMachine() {
        val player = mc.player ?: run {
            resetSwap()
            return
        }
        val now = System.currentTimeMillis()

        when (currentStage) {
            MaskSwapStage.PRE_CMD_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    sendClientCommand("/stats")
                    guiWaitTimeoutMs = now + 2500L
                    currentStage = MaskSwapStage.WAITING_GUI_OPEN
                }
            }

            MaskSwapStage.WAITING_GUI_OPEN -> {
                val isGuiOpen = mc.screen is AbstractContainerScreen<*> && player.containerMenu != player.inventoryMenu
                if (isGuiOpen || inEquipmentMenu || lastOpenContainerId != 0) {
                    val guiOpenDelay = Random.nextLong(60, 95)
                    stageTargetTimeMs = now + guiOpenDelay
                    currentStage = MaskSwapStage.GUI_OPEN_WAIT
                } else if (now >= guiWaitTimeoutMs) {
                    ChatUtils.modMessage("&c[AutoMask] /stats GUI open timed out! Swap failed, resetting state.")
                    resetFullState("GUI Open Timeout (Swap Failed)")
                    return
                }
            }

            MaskSwapStage.GUI_OPEN_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    val containerId = if (lastOpenContainerId != 0) lastOpenContainerId else player.containerMenu.containerId
                    val targetSlot = findTargetContainerSlot(player)

                    if (targetSlot != null && targetSlot in 0 until player.containerMenu.slots.size) {
                        // Regular click (PICKUP) on the item
                        mc.gameMode?.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player)
                    } else {
                        ChatUtils.modMessage("&c[AutoMask] Could not find target item in /stats! Swap failed, resetting state.")
                        player.closeContainer()
                        mc.setScreen(null)
                        resetFullState("Item Not Found in GUI (Swap Failed)")
                        return
                    }

                    val postClickDelay = Random.nextLong(60, 95)
                    stageTargetTimeMs = now + postClickDelay
                    currentStage = MaskSwapStage.POST_CLICK_WAIT
                }
            }

            MaskSwapStage.POST_CLICK_WAIT -> {
                if (now >= stageTargetTimeMs) {
                    player.closeContainer()
                    mc.setScreen(null)
                    pendingAutoClose = true
                    currentStage = MaskSwapStage.FINALIZE
                }
            }

            MaskSwapStage.FINALIZE -> {
                if (currentMode == MaskSwapMode.EQUIP_MASK) {
                    isMaskEquipped = true
                    maskEquippedTimeMs = System.currentTimeMillis()
                } else {
                    isMaskEquipped = false
                    activeMaskType = null
                    val loadoutId = originalLoadoutId
                    if (loadoutId != null) {
                        LoadoutManager.onPlayerManualLoadoutSelect(loadoutId, "Mask Revert")
                    }
                }
                resetSwap()
            }

            MaskSwapStage.IDLE -> {}
        }
    }

    private fun findTargetContainerSlot(player: net.minecraft.world.entity.player.Player): Int? {
        val slots = player.containerMenu.slots

        if (currentMode == MaskSwapMode.EQUIP_MASK) {
            val targetType = activeMaskType
            // Look through all player inventory slots in container
            for (i in slots.indices) {
                val slot = slots[i]
                val stack = slot.item
                if (stack.isEmpty) continue

                val type = MaskType.fromItemStack(stack)
                if (type != null && (targetType == null || type == targetType)) {
                    return i
                }
            }
        } else {
            val orig = originalHelmet
            if (orig != null) {
                // 1. Match by itemUUID if available
                if (orig.itemUUID.isNotBlank()) {
                    for (i in slots.indices) {
                        val stack = slots[i].item
                        if (!stack.isEmpty && stack.itemUUID == orig.itemUUID) {
                            return i
                        }
                    }
                }

                // 2. Match by skyblockId
                if (orig.skyblockId.isNotBlank()) {
                    for (i in slots.indices) {
                        val stack = slots[i].item
                        if (!stack.isEmpty && stack.skyblockId.equals(orig.skyblockId, ignoreCase = true)) {
                            return i
                        }
                    }
                }

                // 3. Match by cleanDisplayName
                for (i in slots.indices) {
                    val stack = slots[i].item
                    if (!stack.isEmpty && stack.cleanDisplayName.equals(orig.displayName, ignoreCase = true)) {
                        return i
                    }
                }

                // 4. Match by skull texture or dyed color
                if (!orig.skullTexture.isNullOrBlank()) {
                    for (i in slots.indices) {
                        val stack = slots[i].item
                        if (!stack.isEmpty && ItemUtils.getSkullTexture(stack) == orig.skullTexture) {
                            return i
                        }
                    }
                }

                // 5. Fallback: slot index if container has player inventory mapped
                if (orig.inventorySlot in 0..35) {
                    val fallbackIdx = slots.size - 36 + orig.inventorySlot
                    if (fallbackIdx in slots.indices && !slots[fallbackIdx].item.isEmpty) {
                        return fallbackIdx
                    }
                }
            }

            // Fallback: any non-mask helmet in inventory
            for (i in slots.indices) {
                val stack = slots[i].item
                if (!stack.isEmpty && MaskType.fromItemStack(stack) == null) {
                    val name = stack.cleanDisplayName.lowercase()
                    if (name.contains("helmet") || name.contains("head") || name.contains("mask") || name.contains("crown") || name.contains("fedora") || name.contains("goggles")) {
                        return i
                    }
                }
            }
        }

        return null
    }

    val SPIRIT_PROC_REGEX = Regex(
        """(?:Second\s+Wind\s+Activated!?|Your\s+.*Spirit\s+Mask.*(?:saved\s+(?:your\s+life|you)|procced|activated))""",
        RegexOption.IGNORE_CASE
    )

    val BONZO_PROC_REGEX = Regex(
        """(?:Your\s+.*Bonzo(?:'s)?\s+Mask.*(?:saved\s+(?:your\s+life|you)|broke|was\s+used\s+up|procced|activated))""",
        RegexOption.IGNORE_CASE
    )

    fun onChatMessage(unformattedText: String) {
        val clean = unformattedText.removeFormatting().trim()

        // Detect player death in chat to immediately reset state
        if (clean.contains("You died and became a ghost", ignoreCase = true) ||
            clean.contains("You died.", ignoreCase = true) ||
            (clean.startsWith("☠") && clean.contains("You were killed by", ignoreCase = true)) ||
            (clean.startsWith("☠") && clean.contains("You died", ignoreCase = true))) {
            resetFullState("Player Death (Chat)")
            return
        }

        // Detect dungeon run start messages to reset cooldowns
        if (clean.contains("The dungeon run has begun!", ignoreCase = true) ||
            clean.contains("Here, I found this map when I first entered", ignoreCase = true) ||
            clean.contains("Starting in 1 second...", ignoreCase = true)) {
            onDungeonRunStart()
        }

        val isSpiritProc = SPIRIT_PROC_REGEX.containsMatchIn(clean)
        val isBonzoProc = BONZO_PROC_REGEX.containsMatchIn(clean)

        if (isSpiritProc) {
            val cdMs = spiritCooldownDurationMs
            val cdSec = (cdMs / 1000f)
            spiritCooldownUntilMs = System.currentTimeMillis() + cdMs
            ChatUtils.modMessage("&b[AutoMask] &eSpirit Mask Second Wind procced! &7(${"%.0f".format(cdSec)}s Cooldown)")
            handleMaskProcTrigger(MaskType.SPIRIT)
        } else if (isBonzoProc) {
            val cdMs = bonzoCooldownDurationMs
            val cdSec = (cdMs / 1000f)
            bonzoCooldownUntilMs = System.currentTimeMillis() + cdMs
            ChatUtils.modMessage("&b[AutoMask] &eBonzo's Mask saved your life! &7(${"%.0f".format(cdSec)}s Cooldown)")
            handleMaskProcTrigger(MaskType.BONZO)
        }
    }

    private fun handleMaskProcTrigger(maskType: MaskType) {
        // Only revert if AutoMask actively performed a swap to this mask and has an original helmet to restore
        if (isMaskEquipped && originalHelmet != null) {
            val delayMs = ConfigManager.config.mask.swapBackDelayMs.toLong()
            queuedRevertTimeMs = System.currentTimeMillis() + delayMs
            pendingRevertAfterChat = true
        }
    }

    fun onPacketOpenScreen(title: String, containerId: Int = 0) {
        val clean = title.removeFormatting().trim()
        val isStatsMenu = clean.contains("Stats", ignoreCase = true) ||
                          clean.contains("Equipment", ignoreCase = true) ||
                          clean.contains("Profile", ignoreCase = true)

        if (isStatsMenu) {
            inEquipmentMenu = true
            lastOpenContainerId = containerId
            if (pendingAutoClose) {
                pendingAutoClose = false
                mc.player?.closeContainer()
                mc.setScreen(null)
            }
        }
    }

    fun onPacketCloseScreen() {
        inEquipmentMenu = false
        pendingAutoClose = false
        lastOpenContainerId = 0
    }

    fun onPlayerDeath() {
        resetFullState("Player Death Event")
    }

    fun onWorldChange() {
        lastDetectedInstance = null
        lastDetectedArea = ""
        lastDetectedServerId = null
        instanceCheckCounter = 0
        resetFullState("World/Server Change", resetCooldowns = true)
    }

    fun onDungeonRunStart() {
        resetFullState("New Dungeon Run Started", resetCooldowns = true)
    }

    fun onDungeonRunEnd() {
        resetFullState("Dungeon Run Ended", resetCooldowns = true)
    }

    fun resetCooldowns() {
        spiritCooldownUntilMs = 0L
        bonzoCooldownUntilMs = 0L
    }

    private fun resetSwap() {
        currentStage = MaskSwapStage.IDLE
        stageTargetTimeMs = 0L
        swapCooldownUntilMs = System.currentTimeMillis() + 150L
        MouseRotationHelper.isSuppressed = false
    }

    fun resetFullState(reason: String = "", resetCooldowns: Boolean = false) {
        currentStage = MaskSwapStage.IDLE
        currentMode = MaskSwapMode.EQUIP_MASK
        stageTargetTimeMs = 0L
        guiWaitTimeoutMs = 0L
        targetInventorySlot = -1
        pendingReason = ""
        lastOpenContainerId = 0
        inEquipmentMenu = false
        pendingAutoClose = false
        pendingRevertAfterChat = false
        queuedRevertTimeMs = 0L
        isMaskEquipped = false
        activeMaskType = null
        originalHelmet = null
        originalLoadoutId = null
        maskEquippedTimeMs = 0L
        swapCooldownUntilMs = System.currentTimeMillis() + 150L
        MouseRotationHelper.isSuppressed = false

        if (resetCooldowns) {
            spiritCooldownUntilMs = 0L
            bonzoCooldownUntilMs = 0L
        }
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
}
