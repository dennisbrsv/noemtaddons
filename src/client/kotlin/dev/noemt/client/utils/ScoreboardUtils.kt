package dev.noemt.client.utils

import dev.noemt.client.features.loadout.GameInstanceType
import dev.noemt.client.utils.ChatUtils.removeFormatting
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

object ScoreboardUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun getSidebarLines(): List<String> {
        val level = mc.level ?: return emptyList()
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()
        val scores = scoreboard.listPlayerScores(objective).sortedByDescending { it.value }

        val lines = mutableListOf<String>()
        for (entry in scores) {
            val owner = entry.owner
            val team = scoreboard.getPlayersTeam(owner)
            val lineFormatted = team?.let { PlayerTeam.formatNameForTeam(it, entry.ownerName()).string } ?: owner
            val lineClean = lineFormatted.removeFormatting().trim()
            if (lineClean.isNotBlank()) {
                lines.add(lineClean)
            }
        }
        return lines
    }

    fun getSidebarTitle(): String {
        val level = mc.level ?: return ""
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return ""
        return objective.displayName.string.removeFormatting().trim()
    }

    fun getSkyblockArea(): String? {
        val lines = getSidebarLines()
        for (line in lines) {
            if (line.contains("⏣") || line.startsWith("Area:", ignoreCase = true)) {
                val cleaned = line.replace("⏣", "").replace("Area:", "", ignoreCase = true).trim()
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        // Fallback checks on sidebar lines
        for (line in lines) {
            if (line.contains("Dungeon Hub", ignoreCase = true)) return "Dungeon Hub"
            if (line.contains("The Catacombs", ignoreCase = true) || line.contains("Catacombs (", ignoreCase = true)) {
                return line.trim()
            }
        }
        return null
    }

    fun detectGameInstance(): Pair<GameInstanceType, String>? {
        val area = getSkyblockArea() ?: return null
        val areaUpper = area.uppercase()

        for (instance in GameInstanceType.values()) {
            if (instance.negativeKeywords.any { areaUpper.contains(it) }) continue
            if (instance.keywords.any { areaUpper.contains(it) }) {
                return instance to area
            }
        }
        return null
    }
}
