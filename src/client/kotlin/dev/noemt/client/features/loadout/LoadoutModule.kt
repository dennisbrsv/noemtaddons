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

        // 1. Chat Message Trigger
        register<ChatMessageEvent> {
            if (!ConfigManager.config.loadout.enabled) return@register
            val text = event.unformattedText
            LoadoutManager.checkConditions(ConditionContext(chatMessage = text))
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
