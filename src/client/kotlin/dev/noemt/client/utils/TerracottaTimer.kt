package dev.noemt.client.utils

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.MainThreadPacketReceivedEvent
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.render.Render3D.renderString
import dev.noemt.client.utils.LocationUtils.dungeonFloorNumber
import dev.noemt.client.utils.LocationUtils.inBoss
import dev.noemt.client.utils.NumbersUtils.toFixed
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Displays a timer above terracotta spawn positions in F6/M6 boss fight,
 * showing how long until each terracotta respawns.
 *
 * Ported from NoammAddons (https://github.com/Noamm9/NoammAddons).
 *
 * This is a standalone utility — call [init] during mod initialization.
 * It is NOT registered as a feature/module; that will be done separately.
 */
object TerracottaTimer {
    private val mc: Minecraft get() = Minecraft.getInstance()

    val terracottaSpawns = CopyOnWriteArrayList<Pair<BlockPos, Long>>()

    private val isMasterMode: Boolean
        get() = LocationUtils.dungeonFloor?.startsWith("M") == true

    fun init() {
        // Detect flower pots appearing via single block updates
        EventBus.register<MainThreadPacketReceivedEvent.Post> {
            if (dungeonFloorNumber != 6 || !inBoss) return@register

            when (val packet = event.packet) {
                is ClientboundBlockUpdatePacket -> {
                    handleBlockChange(packet.pos, packet.blockState.block is FlowerPotBlock)
                }

                is ClientboundSectionBlocksUpdatePacket -> {
                    packet.runUpdates { pos, state ->
                        handleBlockChange(pos.immutable(), state.block is FlowerPotBlock)
                    }
                }
            }
        }

        // Sadan says "ENOUGH!" → terracottas stop respawning, clear after brief delay
        EventBus.register<ChatMessageEvent> {
            if (dungeonFloorNumber != 6 || !inBoss) return@register
            if (event.unformattedText == "[BOSS] Sadan: ENOUGH!") {
                ThreadUtils.scheduledTaskServer(10) {
                    terracottaSpawns.clear()
                }
            }
        }

        // Render countdown timers above each tracked position
        EventBus.register<RenderWorldEvent> {
            if (terracottaSpawns.isEmpty()) return@register
            for ((pos, expireTick) in terracottaSpawns) {
                val timeLeft = (expireTick - DungeonListener.currentTime) / 20.0
                event.ctx.renderString(
                    timeLeft.toFixed(1),
                    Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5),
                    scale = 1.35,
                    phase = true
                )
            }
        }

        // Reset on world change
        EventBus.register<WorldChangeEvent> {
            terracottaSpawns.clear()
        }
    }

    private fun handleBlockChange(pos: BlockPos, isFlowerPot: Boolean) {
        if (!isFlowerPot) return
        if (terracottaSpawns.any { it.first == pos }) return

        // Regular F6: 300 ticks (15s), Master Mode M6: 240 ticks (12s)
        val respawnTicks = if (isMasterMode) 240 else 300
        val entry = Pair(pos, DungeonListener.currentTime + respawnTicks)

        terracottaSpawns.add(entry)
        ThreadUtils.scheduledTaskServer(respawnTicks) {
            terracottaSpawns.remove(entry)
        }
    }
}
