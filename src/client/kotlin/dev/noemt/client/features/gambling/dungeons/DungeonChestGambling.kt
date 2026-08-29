package dev.noemt.client.features.gambling.dungeons

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.features.gambling.chest.DungeonChestType
import dev.noemt.client.mixin.IContainerScreenAccessor
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.ItemUtils.lore
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

    fun init() {}

    private val CHEST_REGEX = Regex("""(?<type>Wood|Wooden|Gold|Golden|Diamond|Emerald|Obsidian|Bedrock)(?:\s+Chest)?""", RegexOption.IGNORE_CASE)

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
        return session.screen === screen && !session.engine.isFinished
    }

    private fun checkContainer(screen: AbstractContainerScreen<*>) {
        val containerId = screen.menu.containerId
        if (containerId == lastHandledContainerId) return

        val rawTitle = screen.title.string
        val cleanTitle = rawTitle.removeFormatting().trim()

        val match = CHEST_REGEX.find(cleanTitle) ?: return
        val typeName = match.groups["type"]?.value ?: return
        val chestType = DungeonChestType.getByName(typeName) ?: return

        val allowedChests = if (ConfigManager.config.gambling.chestTypes == 0) {
            listOf(DungeonChestType.OBSIDIAN, DungeonChestType.BEDROCK)
        } else {
            DungeonChestType.entries
        }

        if (chestType !in allowedChests) return

        val slots = screen.menu.slots
        val items = slots.map { it.item }
        val nonEmptyItems = items.filter { !it.isEmpty }
        if (nonEmptyItems.isEmpty()) return

        // Check if Croesus or regular dungeon
        var isCroesus = false
        var detectedFloor: DungeonFloor? = null

        for (item in nonEmptyItems) {
            if (item.`is`(Items.ARROW)) {
                for (line in item.lore) {
                    val cleanLine = line.removeFormatting().trim()
                    if (cleanLine.startsWith("To Catacombs", ignoreCase = true) || cleanLine.startsWith("To Master", ignoreCase = true)) {
                        isCroesus = true
                        val target = cleanLine.removePrefix("To ").trim()
                        detectedFloor = croesusLoreToFloor[target] ?: DungeonFloor.fromString(target)
                        break
                    }
                }
            }
        }

        if (isCroesus && !ConfigManager.config.gambling.croesusEnabled) {
            return
        }

        if (detectedFloor == null) {
            detectedFloor = LocationUtils.dungeonFloor?.let { DungeonFloor.fromString(it) }
                ?: LocationUtils.dungeonFloorNumber?.let { floorNum ->
                    val isMaster = LocationUtils.dungeonFloor?.startsWith("M", ignoreCase = true) == true
                    DungeonFloor.fromFloorNumber(floorNum, isMaster)
                }
                ?: DungeonFloor.fromString(cleanTitle)
                ?: DungeonFloor.M7
        }

        val winner = DungeonItemRegistry.findBestWinner(items)
            ?: DungeonItemRegistry.getItemStack(DungeonItemRegistry.getRandomItem(detectedFloor, chestType).id)

        val duration = ConfigManager.config.gambling.spinDuration
        val engine = DungeonSlotMachineEngine(detectedFloor, chestType, winner, customDurationSeconds = duration)

        lastHandledContainerId = containerId
        activeSession = ActiveSession(screen, engine, containerId, System.currentTimeMillis())
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

        // Block other clicks while slot machine is actively spinning so player doesn't accidentally purchase before seeing loot
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
}
