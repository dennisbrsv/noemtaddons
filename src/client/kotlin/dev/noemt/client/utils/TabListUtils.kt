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
    private var cachedSnapshot: TabListSnapshot? = null
    private var listDirty = true

    private val floorRegex = Regex("""(?:The Catacombs|Catacombs)\s*\(([FME\d]+)\)""", RegexOption.IGNORE_CASE)

    data class TabListSnapshot(
        val rawEntries: List<Pair<Component, PlayerInfo>>,
        val rawLines: List<String>,
        val cleanLines: List<String>,
        val header: String?,
        val footer: String?,
        val dungeonFloor: String?,
        val dungeonFloorNumber: Int?,
        val area: String?,
        val profile: String?,
        val timestampMs: Long
    ) {
        fun findLine(predicate: (String) -> Boolean): String? = cleanLines.find(predicate)
        fun findLineContaining(substring: String, ignoreCase: Boolean = true): String? =
            cleanLines.find { it.contains(substring, ignoreCase) }
        fun findLineStartingWith(prefix: String, ignoreCase: Boolean = true): String? =
            cleanLines.find { it.startsWith(prefix, ignoreCase) }
        fun findLineMatching(regex: Regex): MatchResult? {
            for (line in cleanLines) {
                val match = regex.find(line)
                if (match != null) return match
            }
            return null
        }
        fun hasLine(predicate: (String) -> Boolean): Boolean = cleanLines.any(predicate)
        fun hasLineContaining(substring: String, ignoreCase: Boolean = true): Boolean =
            cleanLines.any { it.contains(substring, ignoreCase) }
    }

    fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGHEST) {
            val p = event.packet
            if (p is ClientboundPlayerInfoUpdatePacket || p is net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket) {
                listDirty = true
            }
        }
    }

    fun getSnapshot(): TabListSnapshot {
        val cached = cachedSnapshot
        if (!listDirty && cached != null) {
            return cached
        }

        val entries = getTabList()
        val rawLines = ArrayList<String>(entries.size)
        val cleanLines = ArrayList<String>(entries.size)

        for ((comp, _) in entries) {
            val raw = ChatUtils.run { comp.formattedText }
            val clean = ChatUtils.run { comp.string.removeFormatting().trim() }
            if (clean.isNotEmpty()) {
                rawLines.add(raw)
                cleanLines.add(clean)
            }
        }

        var foundFloor: String? = null
        var foundFloorNum: Int? = null
        var foundArea: String? = null
        var foundProfile: String? = null

        for (line in cleanLines) {
            if (foundFloor == null) {
                val match = floorRegex.find(line)
                if (match != null) {
                    foundFloor = match.groupValues[1].uppercase()
                    foundFloorNum = if (foundFloor == "E") 0 else foundFloor.filter { it.isDigit() }.toIntOrNull() ?: 1
                }
            }
            if (foundArea == null && (line.startsWith("Area:", ignoreCase = true) || line.contains("⏣"))) {
                foundArea = line.replace("Area:", "", ignoreCase = true).replace("⏣", "").trim()
            }
            if (foundProfile == null && line.startsWith("Profile:", ignoreCase = true)) {
                foundProfile = line.replace("Profile:", "", ignoreCase = true).trim()
            }
        }

        val snapshot = TabListSnapshot(
            rawEntries = entries,
            rawLines = rawLines,
            cleanLines = cleanLines,
            header = null,
            footer = null,
            dungeonFloor = foundFloor,
            dungeonFloorNumber = foundFloorNum,
            area = foundArea,
            profile = foundProfile,
            timestampMs = System.currentTimeMillis()
        )
        cachedSnapshot = snapshot
        return snapshot
    }

    fun getTabList(): List<Pair<Component, PlayerInfo>> {
        if (listDirty) {
            cachedLines = fetchTabList()
            cachedSnapshot = null
            listDirty = false
        }
        return cachedLines
    }

    fun getCleanTabLines(): List<String> = getSnapshot().cleanLines
    fun getRawTabLines(): List<String> = getSnapshot().rawLines

    fun findLine(predicate: (String) -> Boolean): String? = getSnapshot().findLine(predicate)
    fun findLineContaining(substring: String, ignoreCase: Boolean = true): String? =
        getSnapshot().findLineContaining(substring, ignoreCase)
    fun findLineStartingWith(prefix: String, ignoreCase: Boolean = true): String? =
        getSnapshot().findLineStartingWith(prefix, ignoreCase)
    fun findLineMatching(regex: Regex): MatchResult? = getSnapshot().findLineMatching(regex)
    fun hasLine(predicate: (String) -> Boolean): Boolean = getSnapshot().hasLine(predicate)
    fun hasLineContaining(substring: String, ignoreCase: Boolean = true): Boolean =
        getSnapshot().hasLineContaining(substring, ignoreCase)

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
