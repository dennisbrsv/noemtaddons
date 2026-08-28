package dev.noemt.client.features.loadout

import com.mojang.blaze3d.platform.InputConstants
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.RenderOverlayEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
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
    val keyLoadout1 = KeyMapping("key.noemtaddons.loadout_1", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC)
    val keyLoadout2 = KeyMapping("key.noemtaddons.loadout_2", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC)
    val keyLoadout3 = KeyMapping("key.noemtaddons.loadout_3", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC)
    val keyLoadout4 = KeyMapping("key.noemtaddons.loadout_4", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC)

    private var tickCounter = 0

    override fun init() {
        LoadoutManager.init()

        // 1. Screen Packet Detection & Auto-Sync for /loadouts menu
        register<dev.noemt.client.event.impl.MainThreadPacketReceivedEvent.Pre> {
            val packet = event.packet
            if (packet is net.minecraft.network.protocol.game.ClientboundOpenScreenPacket) {
                LoadoutManager.onPacketOpenScreen(packet.title.string)
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

        // World Change Reset
        register<dev.noemt.client.event.impl.WorldChangeEvent> {
            LoadoutManager.resetMinibossState()
        }

        // 2. Chat Message Trigger & Miniboss Kill Detection
        register<ChatMessageEvent> {
            if (!ConfigManager.config.loadout.enabled) return@register
            val text = event.unformattedText

            // Check Miniboss Kill to Auto-Revert Loadout
            if (LoadoutManager.inMinibossFight) {
                if (text.contains("was slain", ignoreCase = true) ||
                    text.contains("was defeated", ignoreCase = true) ||
                    text.contains("was killed", ignoreCase = true) ||
                    text.contains("You killed", ignoreCase = true)
                ) {
                    val activeName = LoadoutManager.trackedMinibossName
                    if (activeName.isBlank() || text.contains(activeName, ignoreCase = true) ||
                        text.contains("Shadow Assassin", ignoreCase = true) ||
                        text.contains("Lost Adventurer", ignoreCase = true) ||
                        text.contains("Frozen Adventurer", ignoreCase = true) ||
                        text.contains("Angry Archaeologist", ignoreCase = true) ||
                        text.contains("King Midas", ignoreCase = true)
                    ) {
                        LoadoutManager.onMinibossDisappeared("Chat: $text")
                    }
                }
            }

            LoadoutManager.checkConditions(ConditionContext(chatMessage = text))
        }

        // 3. Player Death Reset
        register<dev.noemt.client.event.impl.DungeonEvent.PlayerDeathEvent> {
            if (event.name == mc.user.name) {
                LoadoutManager.resetMinibossState()
            }
        }

        // 4. Dungeon Entry & State Triggers
        register<dev.noemt.client.event.impl.DungeonEvent.RunStatedEvent> {
            if (!ConfigManager.config.loadout.enabled) return@register
            LoadoutManager.checkConditions(ConditionContext(location = "The Catacombs"))
        }

        // 4. Area / Scoreboard Instance Detection
        register<dev.noemt.client.event.impl.MainThreadPacketReceivedEvent.Post> {
            if (!ConfigManager.config.loadout.enabled) return@register
            val packet = event.packet
            if (packet is net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket) {
                val params = packet.parameters.orElse(null) ?: return@register
                val text = (params.playerPrefix.string + params.playerSuffix.string)
                if (text.isNotBlank()) {
                    LoadoutManager.checkConditions(ConditionContext(location = text))
                }
            }
        }

        // 2. Tick Event for Keybinds & Aim Triggers & Swap State Machine
        register<TickEvent.Start> {
            val player = mc.player ?: return@register

            // Process automated GUI swap state machine
            LoadoutManager.onTick()

            // Check Keybinds (always active)
            while (keyToggleAB.consumeClick()) {
                LoadoutManager.toggleAB()
            }
            while (keySwapPrevious.consumeClick()) {
                LoadoutManager.swapToPrevious()
            }
            while (keyLoadout1.consumeClick()) {
                LoadoutManager.swapTo("loadout_1", "Direct Keybind (1)")
            }
            while (keyLoadout2.consumeClick()) {
                LoadoutManager.swapTo("loadout_2", "Direct Keybind (2)")
            }
            while (keyLoadout3.consumeClick()) {
                LoadoutManager.swapTo("loadout_3", "Direct Keybind (3)")
            }
            while (keyLoadout4.consumeClick()) {
                LoadoutManager.swapTo("loadout_4", "Direct Keybind (4)")
            }

            if (!ConfigManager.config.loadout.enabled) return@register

            // Raycast Aim Check (every 2 ticks for peak performance)
            tickCounter++
            if (tickCounter % 2 == 0) {
                val aimedEntity = MobMatcher.getAimedEntity(maxDistance = 16.0)
                if (aimedEntity != null) {
                    LoadoutManager.checkConditions(ConditionContext(aimedEntity = aimedEntity))
                }
            }
        }

        // 3. HUD Display
        register<RenderOverlayEvent> {
            val config = ConfigManager.config.loadout
            if (!config.enabled || !config.showHud) return@register

            val current = LoadoutManager.getCurrentLoadout() ?: return@register
            val text = Component.literal("§6[Loadout: §e${current.name}§6]")

            event.context.textRenderer().accept(10, 10, text)
        }
    }
}
