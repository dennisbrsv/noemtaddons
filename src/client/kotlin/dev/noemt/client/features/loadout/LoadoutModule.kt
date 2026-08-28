package dev.noemt.client.features.loadout

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.RenderOverlayEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.LocationUtils
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

object LoadoutModule : Module {
    override val id = "loadout_swapper"
    override val name = "Loadout Swapper"
    override val description = "Conditional and keybind-based loadout manager"
    override val type = ModuleType.CHEAT

    private val mc: Minecraft get() = Minecraft.getInstance()

    // Keybindings (Registered into Minecraft KeyMapping registry)
    val keyToggleAB = KeyMapping("key.noemtaddons.loadout_toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KeyMapping.Category.MISC)
    val keySwapPrevious = KeyMapping("key.noemtaddons.loadout_previous", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KeyMapping.Category.MISC)
    val keyCopyItemData = KeyMapping("key.noemtaddons.copy_item_data", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F4, KeyMapping.Category.MISC)

    override fun init() {
        LoadoutManager.init()

        // Register keybindings into Fabric
        for (km in listOf(keyToggleAB, keySwapPrevious, keyCopyItemData)) {
            net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(km)
        }

        // 1. Screen Packet Detection & Auto-Sync for /loadouts menu
        register<dev.noemt.client.event.impl.MainThreadPacketReceivedEvent.Pre> {
            val packet = event.packet
            if (packet is net.minecraft.network.protocol.game.ClientboundOpenScreenPacket) {
                LoadoutManager.onPacketOpenScreen(packet.title.string, packet.containerId)
            } else if (packet is net.minecraft.network.protocol.game.ClientboundContainerClosePacket) {
                LoadoutManager.onPacketCloseScreen()
            } else if (packet is net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket) {
                if (LoadoutManager.inLoadoutMenu) {
                    LoadoutManager.syncFromContainerItems(packet.items())
                }
            } else if (packet is net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket) {
                for (id in packet.entityIds) {
                    LoadoutManager.onEntityRemoved(id)
                }
            }
        }

        // World Change & Run End Reset
        register<dev.noemt.client.event.impl.WorldChangeEvent> {
            LoadoutManager.onWorldChange()
        }

        register<dev.noemt.client.event.impl.DungeonEvent.RunEndedEvent> {
            LoadoutManager.onWorldChange()
        }

        // 2. Chat Message Trigger & Miniboss Kill Detection & Player Manual Swaps
        register<ChatMessageEvent> {
            val text = event.unformattedText

            // Sync Player-Made Loadout Swaps from Chat (e.g. "Loadout 1 is already equipped!", "Equipped loadout 2!")
            LoadoutManager.onChatMessage(text)

            if (!ConfigManager.config.loadout.enabled) return@register

            // Check Miniboss Kill to Auto-Revert Loadout
            if (LoadoutManager.inMinibossFight) {
                val clean = text.removeFormatting().trim()
                val isPlayerDeathToMob = clean.contains("was killed by", ignoreCase = true) ||
                                         clean.contains("was slain by", ignoreCase = true) ||
                                         clean.contains("was struck down by", ignoreCase = true) ||
                                         clean.contains("fell to", ignoreCase = true)

                if (!isPlayerDeathToMob) {
                    val isMinibossSlain = clean.contains("was slain", ignoreCase = true) ||
                                          clean.contains("was defeated", ignoreCase = true) ||
                                          clean.contains("was killed", ignoreCase = true) ||
                                          clean.contains("You killed", ignoreCase = true) ||
                                          clean.contains("Defeated Shadow Assassin", ignoreCase = true) ||
                                          clean.contains("Defeated Lost Adventurer", ignoreCase = true) ||
                                          clean.contains("Defeated Frozen Adventurer", ignoreCase = true) ||
                                          clean.contains("Defeated Angry Archaeologist", ignoreCase = true) ||
                                          clean.contains("Defeated King Midas", ignoreCase = true)

                    if (isMinibossSlain) {
                        val activeName = LoadoutManager.trackedMinibossName
                        if (activeName.isBlank() || clean.contains(activeName, ignoreCase = true) ||
                            clean.contains("Shadow Assassin", ignoreCase = true) ||
                            clean.contains("Lost Adventurer", ignoreCase = true) ||
                            clean.contains("Frozen Adventurer", ignoreCase = true) ||
                            clean.contains("Angry Archaeologist", ignoreCase = true) ||
                            clean.contains("King Midas", ignoreCase = true)
                        ) {
                            LoadoutManager.onMinibossDisappeared("Chat: $clean")
                        }
                    }
                }
            }

            LoadoutManager.checkConditions(ConditionContext(chatMessage = text))
        }

        // 3. Player Death Reset
        register<dev.noemt.client.event.impl.DungeonEvent.PlayerDeathEvent> {
            if (event.name == mc.user.name) {
                LoadoutManager.onPlayerDeath()
            }
        }

        // 4. Dungeon Entry & State Triggers
        register<dev.noemt.client.event.impl.DungeonEvent.RunStatedEvent> {
            if (!ConfigManager.config.loadout.enabled) return@register
            LoadoutManager.checkConditions(ConditionContext(location = "The Catacombs DUNGEONS"))
        }

        register<dev.noemt.client.event.impl.DungeonEvent.RoomEvent.onEnter> {
            if (!ConfigManager.config.loadout.enabled) return@register
            when (event.room.data.type) {
                dev.noemt.client.utils.map.core.RoomType.ENTRANCE -> {
                    LoadoutManager.checkConditions(ConditionContext(location = "The Catacombs DUNGEONS", dungeonRoomType = dev.noemt.client.utils.map.core.RoomType.ENTRANCE))
                }
                dev.noemt.client.utils.map.core.RoomType.BLOOD -> {
                    LoadoutManager.checkConditions(ConditionContext(inBloodRoom = true, location = "Blood Room DUNGEONS", dungeonRoomType = dev.noemt.client.utils.map.core.RoomType.BLOOD))
                }
                else -> {}
            }
        }

        // 5. Tick Event for Keybinds & Aim Triggers & Swap State Machine
        register<TickEvent.Start> {
            val player = mc.player ?: return@register

            // Process automated GUI swap state machine & instance checking
            LoadoutManager.onTick()

            // Check Keybinds (always active)
            while (keyToggleAB.consumeClick()) {
                LoadoutManager.toggleAB()
            }
            while (keySwapPrevious.consumeClick()) {
                LoadoutManager.swapToPrevious()
            }
            while (keyCopyItemData.consumeClick()) {
                dev.noemt.client.utils.DebugUtils.dumpHoveredOrHeldItem()
            }

            if (!ConfigManager.config.loadout.enabled) return@register

            // Raycast Aim Check (runs every tick in dungeons for instant lock-on)
            if (LocationUtils.inDungeon) {
                val aimedEntity = MobMatcher.getAimedEntity(maxDistance = 18.0)
                if (aimedEntity != null) {
                    LoadoutManager.checkConditions(ConditionContext(aimedEntity = aimedEntity))
                }
            }
        }

        // 6. HUD Display
        register<RenderOverlayEvent> {
            val config = ConfigManager.config.loadout
            if (!config.enabled || !config.showHud) return@register

            val current = LoadoutManager.getCurrentLoadout() ?: return@register
            val text = Component.literal("§6[Loadout: §e${current.name}§6]")
            event.context.text(mc.font, text, 10, 10, -1, true)
        }
    }
}
