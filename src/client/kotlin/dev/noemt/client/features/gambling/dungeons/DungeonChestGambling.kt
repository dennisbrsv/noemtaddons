package dev.noemt.client.features.gambling.dungeons

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.features.gambling.chest.DungeonChestType
import dev.noemt.client.mixin.IContainerScreenAccessor
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.ItemUtils.lore
import dev.noemt.client.utils.ItemUtils.skyblockId
import dev.noemt.client.utils.LocationUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object DungeonChestGambling {

    data class ActiveSession(
        val screen: AbstractContainerScreen<*>,
        val engine: DungeonSlotMachineEngine,
        val containerId: Int,
        val startTime: Long
    )

    var activeSession: ActiveSession? = null
        private set

    private var lastHandledContainerId: Int? = null
    var debugMode: Boolean = false

    fun init() {
        dev.noemt.client.event.EventBus.register<dev.noemt.client.event.impl.MainThreadPacketReceivedEvent.Post> {
            val mc = net.minecraft.client.Minecraft.getInstance()
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@register
            val packet = event.packet
            if (packet is net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket ||
                packet is net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket ||
                packet is net.minecraft.network.protocol.game.ClientboundOpenScreenPacket) {
                checkContainer(screen)
            }
        }
    }

    val croesusLoreToFloor = mapOf(
        "Catacombs - Floor I" to DungeonFloor.F1,
        "Catacombs - Floor II" to DungeonFloor.F2,
        "Catacombs - Floor III" to DungeonFloor.F3,
        "Catacombs - Floor IV" to DungeonFloor.F4,
        "Catacombs - Floor V" to DungeonFloor.F5,
        "Catacombs - Floor VI" to DungeonFloor.F6,
        "Catacombs - Floor VII" to DungeonFloor.F7,
        "Master Catacombs - Floor I" to DungeonFloor.M1,
        "Master Catacombs - Floor II" to DungeonFloor.M2,
        "Master Catacombs - Floor III" to DungeonFloor.M3,
        "Master Catacombs - Floor IV" to DungeonFloor.M4,
        "Master Catacombs - Floor V" to DungeonFloor.M5,
        "Master Catacombs - Floor VI" to DungeonFloor.M6,
        "Master Catacombs - Floor VII" to DungeonFloor.M7,
    )

    fun handleRender(
        screen: AbstractContainerScreen<*>,
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float
    ): Boolean {
        if (!ConfigManager.config.gambling.enabled) return false

        val containerId = screen.menu.containerId

        // Try initializing session if not present or container changed
        if (activeSession == null || activeSession?.containerId != containerId) {
            checkContainer(screen)
        }

        val session = activeSession ?: return false
        if (session.screen !== screen || session.containerId != containerId) return false

        // Check if celebration animation finished (2s post-celebration)
        if (session.engine.isFinished && System.currentTimeMillis() - session.engine.celebrationStartTime >= 2000L) {
            activeSession = null
            return false
        }

        val accessor = screen as? IContainerScreenAccessor
        val left = accessor?.leftPos ?: ((screen.width - 176) / 2)
        val top = accessor?.topPos ?: ((screen.height - 222) / 2)
        val imageWidth = accessor?.imageWidth ?: 176
        val imageHeight = accessor?.imageHeight ?: 222

        // Dark background overlay behind chest
        graphics.fill(0, 0, screen.width, screen.height, 0xCC101010.toInt())

        // Render slot machine inside chest bounds
        session.engine.render(
            graphics,
            screen.font,
            left,
            top,
            imageWidth,
            imageHeight,
            mouseX,
            mouseY,
            partialTick
        )

        return true
    }

    fun isSessionActive(screen: AbstractContainerScreen<*>): Boolean {
        if (!ConfigManager.config.gambling.enabled) return false
        val session = activeSession ?: return false
        return session.screen === screen
    }

    private val CROESUS_RUN_REGEX = Regex("""^(?:Master\s+)?Catacombs\s*-\s*Floor\s+([IVXLCDM\d]+)""", RegexOption.IGNORE_CASE)

    private fun checkContainer(screen: AbstractContainerScreen<*>) {
        val containerId = screen.menu.containerId
        if (containerId == lastHandledContainerId) return

        val rawTitle = screen.title.string
        val cleanTitle = rawTitle.removeFormatting().trim()

        // 1. If Croesus page overview (e.g. "(1/3) Croesus" or "Croesus"), ignore quietly and remember container ID
        if (cleanTitle.contains("Croesus", ignoreCase = true)) {
            lastHandledContainerId = containerId
            return
        }

        // STRICTLY container slots (exclude player's 36 inventory + hotbar slots)
        val containerSlotCount = (screen.menu.slots.size - 36).coerceAtLeast(0)
        val items = screen.menu.slots.take(containerSlotCount).map { it.item }
        val nonEmptyItems = items.filter { !it.isEmpty }
        if (nonEmptyItems.isEmpty()) return // Slot packets haven't arrived yet

        // Set container as handled so we don't re-evaluate/spam every render frame
        lastHandledContainerId = containerId

        // Case A: Croesus Run Screen: "(Master) Catacombs - Floor {x}"
        val croesusMatch = CROESUS_RUN_REGEX.find(cleanTitle)
        if (croesusMatch != null) {
            if (!ConfigManager.config.gambling.croesusEnabled) {
                if (debugMode) dev.noemt.client.utils.ChatUtils.modMessage("&c[Gamba Debug] Croesus gambling is disabled in config.")
                return
            }

            val floor = DungeonFloor.fromString(cleanTitle) ?: DungeonFloor.F1

            // Extract all drops from all chest heads in this Croesus run
            val allCroesusDrops = DungeonItemRegistry.extractCroesusDrops(items)
            val winner = if (allCroesusDrops.isNotEmpty()) {
                DungeonItemRegistry.findBestWinner(allCroesusDrops)
            } else {
                val validDrops = items.filter { !it.isEmpty && it.skyblockId.isNotBlank() }
                if (validDrops.isNotEmpty()) DungeonItemRegistry.findBestWinner(validDrops) else null
            } ?: DungeonItemRegistry.getItemStack(DungeonItemRegistry.getRandomItem(floor, DungeonChestType.BEDROCK).id)

            // Determine highest chest tier available in this run for slot machine theme
            var bestChestTier = DungeonChestType.WOODEN
            for (head in items) {
                if (head.isEmpty) continue
                val name = head.hoverName.string.removeFormatting()
                val parsed = DungeonChestType.findInText(name)
                if (parsed != null && parsed.ordinal > bestChestTier.ordinal) {
                    bestChestTier = parsed
                }
            }

            val duration = ConfigManager.config.gambling.spinDuration
            val engine = DungeonSlotMachineEngine(floor, bestChestTier, winner, customDurationSeconds = duration)

            if (debugMode) {
                dev.noemt.client.utils.ChatUtils.modMessage("&a[Gamba Debug] ACTIVATED CROESUS slot machine! Floor: $floor | Theme: $bestChestTier | Winner: ${DungeonItemRegistry.getDropDisplayName(winner)} | Duration: ${duration}s")
            }

            activeSession = ActiveSession(screen, engine, containerId, System.currentTimeMillis())
            return
        }

        // Case B: Live Dungeon Run Chest: "Bedrock Chest", "Obsidian Chest", etc.
        val chestType = DungeonChestType.findInText(cleanTitle)
            ?: run {
                for (item in nonEmptyItems) {
                    val name = item.hoverName.string.removeFormatting().trim()
                    if (name.contains("Reward Chest", ignoreCase = true) || name.contains("Open Chest", ignoreCase = true) || name.contains("Claim", ignoreCase = true)) {
                        val parsed = DungeonChestType.findInText(name)
                        if (parsed != null) return@run parsed
                    }
                }
                null
            }

        if (chestType == null) {
            if (debugMode) dev.noemt.client.utils.ChatUtils.modMessage("&c[Gamba Debug] Ignored: '$cleanTitle' is not a dungeon chest or Croesus run.")
            return
        }

        val allowedChests = if (ConfigManager.config.gambling.chestTypes == 0) {
            listOf(DungeonChestType.OBSIDIAN, DungeonChestType.BEDROCK)
        } else {
            DungeonChestType.entries
        }

        if (chestType !in allowedChests) {
            if (debugMode) dev.noemt.client.utils.ChatUtils.modMessage("&c[Gamba Debug] Rejected: Chest type $chestType not in allowedChests ($allowedChests).")
            return
        }

        val floor = LocationUtils.dungeonFloor?.let { DungeonFloor.fromString(it) }
            ?: LocationUtils.dungeonFloorNumber?.let { floorNum ->
                val isMaster = LocationUtils.dungeonFloor?.startsWith("M", ignoreCase = true) == true
                DungeonFloor.fromFloorNumber(floorNum, isMaster)
            }
            ?: DungeonFloor.M7

        val winner = DungeonItemRegistry.findBestWinner(items)
            ?: DungeonItemRegistry.getItemStack(DungeonItemRegistry.getRandomItem(floor, chestType).id)

        val duration = ConfigManager.config.gambling.spinDuration
        val engine = DungeonSlotMachineEngine(floor, chestType, winner, customDurationSeconds = duration)

        if (debugMode) {
            dev.noemt.client.utils.ChatUtils.modMessage("&a[Gamba Debug] ACTIVATED LIVE CHEST slot machine! Type: $chestType | Floor: $floor | Winner: ${DungeonItemRegistry.getDropDisplayName(winner)} | Duration: ${duration}s")
        }

        activeSession = ActiveSession(screen, engine, containerId, System.currentTimeMillis())
    }

    fun onMouseClicked(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val session = activeSession ?: return false
        if (session.screen !== screen) return false

        session.engine.skip()
        return true
    }

    fun onKeyPressed(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        val session = activeSession ?: return false
        if (session.screen !== screen) return false

        val key = event.key()
        if (key == InputConstants.KEY_SPACE || key == InputConstants.KEY_ESCAPE) {
            session.engine.skip()
            return true
        }

        return false
    }

    fun onContainerClosed(screen: AbstractContainerScreen<*>) {
        if (activeSession?.screen === screen) {
            activeSession = null
        }
    }
}
