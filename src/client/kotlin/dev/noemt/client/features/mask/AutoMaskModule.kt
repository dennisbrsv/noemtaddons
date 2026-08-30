package dev.noemt.client.features.mask

import dev.noemt.client.config.ConfigManager
import dev.noemt.client.event.EventBus.register
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.DungeonEvent
import dev.noemt.client.event.impl.MainThreadPacketReceivedEvent
import dev.noemt.client.event.impl.RenderOverlayEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.render.Render2D.drawString
import dev.noemt.client.utils.NumbersUtils.toFixed
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import java.awt.Color

object AutoMaskModule : Module {
    override val id = "auto_mask_swapper"
    override val name = "Auto Mask Swapper"
    override val description = "Automatically equips Bonzo's Mask or Spirit Mask on low health and restores original helmet on ability trigger"
    override val type = ModuleType.CHEAT

    private val mc: Minecraft get() = Minecraft.getInstance()

    override fun init() {
        // 1. Tick Event for health monitoring & state machine
        register<TickEvent.Start> {
            AutoMaskManager.onTick()
        }

        // 2. Chat trigger detection (Second Wind / Bonzo's Mask procs)
        register<ChatMessageEvent> {
            AutoMaskManager.onChatMessage(event.unformattedText)
        }

        // 3. Screen packet handling for /stats (Stats & Equipment menu)
        register<MainThreadPacketReceivedEvent.Pre> {
            val packet = event.packet
            if (packet is ClientboundOpenScreenPacket) {
                AutoMaskManager.onPacketOpenScreen(packet.title.string, packet.containerId)
            } else if (packet is ClientboundContainerClosePacket) {
                AutoMaskManager.onPacketCloseScreen()
            }
        }

        // 4. World change & Run start/end reset
        register<WorldChangeEvent> {
            AutoMaskManager.onWorldChange()
        }

        register<DungeonEvent.RunEndedEvent> {
            AutoMaskManager.onWorldChange()
        }

        register<DungeonEvent.RunStatedEvent> {
            AutoMaskManager.onWorldChange()
        }

        // 5. Player death reset
        register<DungeonEvent.PlayerDeathEvent> {
            if (event.name == mc.user.name) {
                AutoMaskManager.onPlayerDeath()
            }
        }

        // 6. HUD Status Display
        register<RenderOverlayEvent> {
            val config = ConfigManager.config.mask
            if (!config.enabled || !config.showHud) return@register

            val hudText = buildHudText() ?: return@register
            event.context.text(mc.font, Component.literal(hudText), config.hudX.toInt(), config.hudY.toInt(), -1, true)
        }
    }

    private fun buildHudText(): String? {
        if (AutoMaskManager.isMaskEquipped) {
            val activeName = AutoMaskManager.activeMaskType?.displayName ?: "Mask"
            return "§c[Mask: §eACTIVE ($activeName)§c]"
        }

        val tracked = AutoMaskManager.getTrackedMasks()
        if (tracked.isEmpty()) {
            if (AutoMaskManager.isWearingMask()) {
                val wornType = AutoMaskManager.getCurrentlyWornMaskType()?.displayName ?: "Mask"
                return "§b[Mask: §eWorn ($wornType)§b]"
            }
            return null
        }

        val readyMasks = tracked.filter { !it.isOnCooldown }
        if (readyMasks.isNotEmpty()) {
            val names = readyMasks.joinToString(", ") { it.type.displayName.replace(" Mask", "").replace("'s", "") }
            return "§a[Mask: §fReady ($names)§a]"
        }

        // All tracked masks on cooldown
        val spiritCd = AutoMaskManager.getSpiritCooldownRemainingSeconds()
        val bonzoCd = AutoMaskManager.getBonzoCooldownRemainingSeconds()

        val cdParts = mutableListOf<String>()
        if (spiritCd > 0) cdParts.add("Spirit: ${spiritCd.toFixed(0)}s")
        if (bonzoCd > 0) cdParts.add("Bonzo: ${bonzoCd.toFixed(0)}s")

        val cdStr = if (cdParts.isNotEmpty()) cdParts.joinToString(" | ") else "On CD"
        return "§e[Mask: §c$cdStr§e]"
    }
}
