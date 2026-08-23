package dev.noemt.client.event

import dev.noemt.client.event.impl.*
import dev.noemt.client.render.RenderContext
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket

object EventDispatcher {
    fun init() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            dev.noemt.client.utils.MouseRotationHelper.onRenderFrame()
            EventBus.post(RenderWorldEvent(RenderContext.fromContext(context)))
        }

        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ -> EventBus.post(WorldChangeEvent) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> EventBus.post(WorldChangeEvent) }

        ClientTickEvents.START_CLIENT_TICK.register { mc -> mc.level?.let { EventBus.post(TickEvent.Start) } }
        ClientTickEvents.END_CLIENT_TICK.register { mc -> mc.level?.let { EventBus.post(TickEvent.End) } }

        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ -> EventBus.post(EntityUnloadEvent(entity)) }

        EventBus.register<MainThreadPacketReceivedEvent.Pre> {
            val packet = event.packet
            if (packet is ClientboundSystemChatPacket) {
                if (packet.overlay) return@register
                if (EventBus.post(ChatMessageEvent(packet.content))) {
                    event.isCanceled = true
                }
            }
        }
    }
}
