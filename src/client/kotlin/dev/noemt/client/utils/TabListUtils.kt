package dev.noemt.client.utils

import com.google.common.collect.ComparisonChain
import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.MainThreadPacketReceivedEvent
import dev.noemt.client.event.priority.EventPriority
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.level.GameType

object TabListUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private var cachedLines: List<Pair<Component, PlayerInfo>> = emptyList()
    private var listDirty = true

    fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGHEST) {
            if (event.packet is ClientboundPlayerInfoUpdatePacket) {
                listDirty = true
            }
        }
    }

    fun getTabList(): List<Pair<Component, PlayerInfo>> {
        if (listDirty) {
            cachedLines = fetchTabList()
            listDirty = false
        }
        return cachedLines
    }

    private fun fetchTabList(): List<Pair<Component, PlayerInfo>> {
        val player = mc.player ?: return emptyList()
        val onlinePlayers = player.connection.onlinePlayers
        val sortedPlayers = onlinePlayers.sortedWith(PlayerComparator)
        val result = mutableListOf<Pair<Component, PlayerInfo>>()
        for (info in sortedPlayers) result.add(mc.gui.tabList.getNameForDisplay(info) to info)
        return if (result.size > 80) result.subList(0, 80) else result
    }

    private object PlayerComparator : Comparator<PlayerInfo> {
        override fun compare(o1: PlayerInfo, o2: PlayerInfo): Int {
            return ComparisonChain.start()
                .compareTrueFirst(o1.gameMode != GameType.SPECTATOR, o2.gameMode != GameType.SPECTATOR)
                .compare(o1.team?.name.orEmpty(), o2.team?.name.orEmpty())
                .compare(o1.profile.name, o2.profile.name)
                .result()
        }
    }
}
