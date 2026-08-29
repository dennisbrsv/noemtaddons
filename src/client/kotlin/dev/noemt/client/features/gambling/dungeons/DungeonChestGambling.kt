package dev.noemt.client.features.gambling.dungeons

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.features.gambling.chest.DungeonChestType
import dev.noemt.client.mixin.IContainerScreenAccessor
import dev.noemt.client.utils.ItemUtils.lore
import dev.noemt.client.utils.LocationUtils
import net.minecraft.client.Minecraft
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

    fun init() {
        EventBus.register<TickEvent.Start> {
            checkCurrentScreen()
        }
    }

    private fun checkCurrentScreen() {
        if (!ConfigManager.config.gambling.enabled) return
        val mc = Minecraft.getInstance()
        val screen = mc.screen as? AbstractContainerScreen<*> ?: run {
            if (activeSession != null) {
                activeSession = null
            }
            return
        }

        val containerId = screen.menu.containerId
        if (activeSession != null && activeSession?.screen === screen) {
            // Check if celebration finished
            val engine = activeSession!!.engine
            if (engine.isFinished && System.currentTimeMillis() - engine.celebrationStartTime >= 2000L) {
                activeSession = null
            }
            return
        }

        if (containerId == lastHandledContainerId) return

        // Inspect screen title
        val title = screen.title.string.trim()
        val chestType = findChestType(title) ?: return

        // Check allowed chest types from config
        val allowedChests = if (ConfigManager.config.gambling.chestTypes == 0) {
            listOf(DungeonChestType.OBSIDIAN, DungeonChestType.BEDROCK)
        } else {
            DungeonChestType.entries
        }

        if (chestType !in allowedChests) return

        // Check if items are loaded in container
        val slots = screen.menu.slots
        val items = slots.map { it.item }.filter { !it.isEmpty }
        if (items.isEmpty()) return

        // Determine floor
        val floor = determineFloor(title, items)

        // Find winning item from container loot
        val winner = DungeonItemRegistry.findBestWinner(items)

        lastHandledContainerId = containerId
        val engine = DungeonSlotMachineEngine(floor, chestType, winner)
        activeSession = ActiveSession(screen, engine, containerId, System.currentTimeMillis())
    }

    fun isSessionActive(screen: AbstractContainerScreen<*>): Boolean {
        if (!ConfigManager.config.gambling.enabled) return false
        val session = activeSession ?: return false
        return session.screen === screen && !session.engine.isFinished
    }

    fun renderChestSlotMachine(
        screen: AbstractContainerScreen<*>,
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float
    ) {
        val session = activeSession ?: return
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

        // Check auto-finish expiration
        if (session.engine.isFinished && System.currentTimeMillis() - session.engine.celebrationStartTime >= 2000L) {
            activeSession = null
        }
    }

    fun onMouseClicked(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val session = activeSession ?: return false
        if (session.screen !== screen) return false

        val accessor = screen as? IContainerScreenAccessor
        val left = accessor?.leftPos ?: ((screen.width - 176) / 2)
        val top = accessor?.topPos ?: ((screen.height - 222) / 2)
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()

        for (idx in 0..53) {
            val row = idx / 9
            val col = idx % 9
            val slotX = left + 8 + (col * 18)
            val slotY = top + 18 + (row * 18)

            if (mouseX in slotX..(slotX + 16) && mouseY in slotY..(slotY + 16)) {
                if (idx == 49) {
                    session.engine.skip()
                    activeSession = null
                    return true
                }
            }
        }

        // Block other clicks while slot machine is actively spinning so player doesn't accidentally buy without seeing loot
        return true
    }

    fun onKeyPressed(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        val session = activeSession ?: return false
        if (session.screen !== screen) return false

        val key = event.key()
        if (key == InputConstants.KEY_SPACE && ConfigManager.config.gambling.allowSpaceSkip) {
            session.engine.skip()
            activeSession = null
            return true
        }

        if (key == InputConstants.KEY_ESCAPE) {
            session.engine.skip()
            activeSession = null
            return true
        }

        return false
    }

    fun onContainerClosed(screen: AbstractContainerScreen<*>) {
        if (activeSession?.screen === screen) {
            activeSession = null
        }
    }

    private fun findChestType(title: String): DungeonChestType? {
        val clean = title.replace("§[0-9a-fk-or]".toRegex(), "").trim()
        for (type in DungeonChestType.entries) {
            if (clean.contains("${type.name} Chest", ignoreCase = true) ||
                clean.contains("${type.displayName} Chest", ignoreCase = true) ||
                clean.equals(type.name, ignoreCase = true) ||
                clean.equals(type.displayName, ignoreCase = true)
            ) {
                return type
            }
        }
        return null
    }

    private fun determineFloor(title: String, items: List<ItemStack>): DungeonFloor {
        // 1. Try from screen title
        DungeonFloor.fromString(title)?.let { return it }

        // 2. Try from LocationUtils
        LocationUtils.dungeonFloorNumber?.let { floorNum ->
            val isMaster = LocationUtils.dungeonFloor?.startsWith("M", ignoreCase = true) == true
            return DungeonFloor.fromFloorNumber(floorNum, isMaster)
        }

        // 3. Try from item lore (e.g. Croesus arrow "To Catacombs - Floor VII")
        for (item in items) {
            for (line in item.lore) {
                if (line.contains("Catacombs - Floor", ignoreCase = true) || line.contains("Master Catacombs", ignoreCase = true)) {
                    DungeonFloor.fromString(line)?.let { return it }
                }
            }
        }

        return DungeonFloor.M7
    }
}
