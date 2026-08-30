package dev.noemt.client.utils

import dev.noemt.client.features.loadout.GameInstanceType
import dev.noemt.client.utils.ChatUtils.removeFormatting
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

object ScoreboardUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    // 50ms tick-level cache for zero-redundancy across multiple features
    private var cachedSnapshot: ScoreboardSnapshot? = null
    private var lastFetchMs: Long = 0L

    private val floorRegex = Regex("""(?:The Catacombs|Catacombs)\s*\(([FME\d]+)\)""", RegexOption.IGNORE_CASE)
    private val serverIdRegex = Regex("""\d{2}/\d{2}/\d{2,4}\s+([a-zA-Z0-9]+)""")
    private val clearedRegex = Regex("""Cleared:\s*(\d+)%""", RegexOption.IGNORE_CASE)
    private val timeElapsedRegex = Regex("""Time Elapsed:\s*(?:(\d+)m\s*)?(\d+)s""", RegexOption.IGNORE_CASE)
    private val purseRegex = Regex("""(?:Purse|Piggy):\s*([\d,.]+[kKmMbBtT]?)""")
    private val bitsRegex = Regex("""Bits:\s*([\d,.]+)""")
    private val motesRegex = Regex("""Motes:\s*([\d,.]+)""")
    private val slayerRegex = Regex("""Slayer:\s*(.+)""")

    /**
     * Complete immutable parsed snapshot of the scoreboard for the current tick.
     * Evaluated once per tick on demand and shared by all callers.
     */
    data class ScoreboardSnapshot(
        val title: String,
        val cleanTitle: String,
        val rawLines: List<String>,
        val cleanLines: List<String>,
        val isSkyblock: Boolean,
        val area: String?,
        val instanceType: GameInstanceType?,
        val serverId: String?,
        val date: String?,
        val time: String?,
        val purseCoins: Double?,
        val bits: Long?,
        val motes: Long?,
        val inDungeon: Boolean,
        val dungeonFloor: String?,
        val dungeonFloorNumber: Int?,
        val dungeonClearedPercent: Int?,
        val dungeonTimeElapsedSeconds: Int?,
        val isFreshDungeonRun: Boolean,
        val slayerProgress: String?,
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

    /**
     * Returns the current tick's pre-parsed scoreboard snapshot.
     */
    fun getSnapshot(): ScoreboardSnapshot {
        val now = System.currentTimeMillis()
        val cached = cachedSnapshot
        if (cached != null && now - lastFetchMs < 50L) {
            return cached
        }

        val snapshot = computeSnapshot(now)
        cachedSnapshot = snapshot
        lastFetchMs = now
        return snapshot
    }

    private fun computeSnapshot(now: Long): ScoreboardSnapshot {
        val level = mc.level
        if (level == null) {
            return emptySnapshot(now)
        }
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptySnapshot(now)

        val rawTitle = objective.displayName.string
        val cleanTitle = rawTitle.removeFormatting().trim()

        val scores = scoreboard.listPlayerScores(objective).sortedByDescending { it.value }
        val rawLines = ArrayList<String>(scores.size)
        val cleanLines = ArrayList<String>(scores.size)

        for (entry in scores) {
            val owner = entry.owner
            val team = scoreboard.getPlayersTeam(owner)
            val lineFormatted = team?.let { PlayerTeam.formatNameForTeam(it, entry.ownerName()).string } ?: owner
            val lineClean = lineFormatted.removeFormatting().trim()
            if (lineClean.isNotBlank()) {
                rawLines.add(lineFormatted)
                cleanLines.add(lineClean)
            }
        }

        val isSkyblock = cleanTitle.contains("SKYBLOCK", ignoreCase = true) ||
                         cleanLines.any { it.contains("www.hypixel.net", ignoreCase = true) || it.contains("hypixel.net", ignoreCase = true) }

        // Area & Instance
        var area: String? = null
        for (line in cleanLines) {
            if (line.contains("⏣") || line.startsWith("Area:", ignoreCase = true)) {
                val cleaned = line.replace("⏣", "").replace("Area:", "", ignoreCase = true).trim()
                if (cleaned.isNotBlank()) {
                    area = cleaned
                    break
                }
            }
        }
        if (area == null) {
            for (line in cleanLines) {
                if (line.contains("Dungeon Hub", ignoreCase = true)) {
                    area = "Dungeon Hub"
                    break
                }
                if (line.contains("The Catacombs", ignoreCase = true) || line.contains("Catacombs (", ignoreCase = true)) {
                    area = line.trim()
                    break
                }
            }
        }

        var detectedInstance: GameInstanceType? = null
        if (area != null) {
            val areaUpper = area.uppercase()
            for (instance in GameInstanceType.entries) {
                if (instance.negativeKeywords.any { areaUpper.contains(it) }) continue
                if (instance.keywords.any { areaUpper.contains(it) }) {
                    detectedInstance = instance
                    break
                }
            }
        }

        // Server / Lobby ID (e.g. 08/30/26 m123B)
        var serverId: String? = null
        var dateStr: String? = null
        var timeStr: String? = null
        for (line in cleanLines) {
            if (serverId == null) {
                val match = serverIdRegex.find(line)
                if (match != null) {
                    serverId = match.groupValues[1]
                }
            }
            if (dateStr == null && (line.contains("Spring") || line.contains("Summer") || line.contains("Autumn") || line.contains("Winter"))) {
                dateStr = line
            }
            if (timeStr == null && (line.contains("am", ignoreCase = true) || line.contains("pm", ignoreCase = true)) && line.contains(":")) {
                timeStr = line
            }
        }

        // Economy
        var purseCoins: Double? = null
        var bits: Long? = null
        var motes: Long? = null
        for (line in cleanLines) {
            if (purseCoins == null) {
                val pMatch = purseRegex.find(line)
                if (pMatch != null) {
                    purseCoins = parseShortenedNumber(pMatch.groupValues[1])
                }
            }
            if (bits == null) {
                val bMatch = bitsRegex.find(line)
                if (bMatch != null) {
                    bits = bMatch.groupValues[1].replace(",", "").toLongOrNull()
                }
            }
            if (motes == null) {
                val mMatch = motesRegex.find(line)
                if (mMatch != null) {
                    motes = mMatch.groupValues[1].replace(",", "").toLongOrNull()
                }
            }
        }

        // Dungeon parsing
        var inDungeon = false
        var floorStr: String? = null
        var floorNum: Int? = null
        var clearedPercent: Int? = null
        var timeElapsedSec: Int? = null
        var isFreshRun = false

        if (area != null && (area.contains("Catacombs", ignoreCase = true) || area.contains("The Catacombs", ignoreCase = true)) && !area.contains("Dungeon Hub", ignoreCase = true)) {
            inDungeon = true
        }

        for (line in cleanLines) {
            if (line.contains("Dungeon Hub", ignoreCase = true)) {
                inDungeon = false
            } else if (!inDungeon && (line.contains("The Catacombs", ignoreCase = true) || line.contains("Catacombs (", ignoreCase = true) || line.contains("Cleared:", ignoreCase = true))) {
                inDungeon = true
            }

            if (floorStr == null) {
                val fMatch = floorRegex.find(line)
                if (fMatch != null) {
                    floorStr = fMatch.groupValues[1].uppercase()
                    floorNum = if (floorStr == "E") 0 else floorStr.filter { it.isDigit() }.toIntOrNull() ?: 1
                }
            }

            if (clearedPercent == null) {
                val cMatch = clearedRegex.find(line)
                if (cMatch != null) {
                    clearedPercent = cMatch.groupValues[1].toIntOrNull()
                }
            }

            if (timeElapsedSec == null) {
                val tMatch = timeElapsedRegex.find(line)
                if (tMatch != null) {
                    val m = tMatch.groupValues[1].toIntOrNull() ?: 0
                    val s = tMatch.groupValues[2].toIntOrNull() ?: 0
                    timeElapsedSec = m * 60 + s
                }
            }
        }

        if (inDungeon) {
            isFreshRun = (clearedPercent == 0) || (timeElapsedSec != null && timeElapsedSec <= 2)
        }

        // Slayer
        var slayerProgress: String? = null
        for (line in cleanLines) {
            val sMatch = slayerRegex.find(line)
            if (sMatch != null) {
                slayerProgress = sMatch.groupValues[1].trim()
                break
            }
        }

        return ScoreboardSnapshot(
            title = rawTitle,
            cleanTitle = cleanTitle,
            rawLines = rawLines,
            cleanLines = cleanLines,
            isSkyblock = isSkyblock,
            area = area,
            instanceType = detectedInstance,
            serverId = serverId,
            date = dateStr,
            time = timeStr,
            purseCoins = purseCoins,
            bits = bits,
            motes = motes,
            inDungeon = inDungeon,
            dungeonFloor = floorStr,
            dungeonFloorNumber = floorNum,
            dungeonClearedPercent = clearedPercent,
            dungeonTimeElapsedSeconds = timeElapsedSec,
            isFreshDungeonRun = isFreshRun,
            slayerProgress = slayerProgress,
            timestampMs = now
        )
    }

    private fun emptySnapshot(now: Long) = ScoreboardSnapshot(
        title = "",
        cleanTitle = "",
        rawLines = emptyList(),
        cleanLines = emptyList(),
        isSkyblock = false,
        area = null,
        instanceType = null,
        serverId = null,
        date = null,
        time = null,
        purseCoins = null,
        bits = null,
        motes = null,
        inDungeon = false,
        dungeonFloor = null,
        dungeonFloorNumber = null,
        dungeonClearedPercent = null,
        dungeonTimeElapsedSeconds = null,
        isFreshDungeonRun = false,
        slayerProgress = null,
        timestampMs = now
    )

    private fun parseShortenedNumber(str: String): Double? {
        val clean = str.replace(",", "").trim()
        val multiplier = when (clean.lastOrNull()?.lowercaseChar()) {
            'k' -> 1_000.0
            'm' -> 1_000_000.0
            'b' -> 1_000_000_000.0
            't' -> 1_000_000_000_000.0
            else -> 1.0
        }
        val numberPart = if (multiplier != 1.0) clean.dropLast(1) else clean
        return numberPart.toDoubleOrNull()?.let { it * multiplier }
    }

    // ==========================================
    // Public Query API for Features & Services
    // ==========================================

    fun getSidebarLines(): List<String> = getSnapshot().cleanLines
    fun getRawSidebarLines(): List<String> = getSnapshot().rawLines
    fun getSidebarTitle(): String = getSnapshot().cleanTitle
    fun getSkyblockArea(): String? = getSnapshot().area
    fun getServerId(): String? = getSnapshot().serverId
    fun getPurseCoins(): Double? = getSnapshot().purseCoins
    fun getBits(): Long? = getSnapshot().bits
    fun getMotes(): Long? = getSnapshot().motes
    fun getDungeonClearedPercent(): Int? = getSnapshot().dungeonClearedPercent
    fun getDungeonTimeElapsed(): Int? = getSnapshot().dungeonTimeElapsedSeconds
    fun isFreshDungeonRun(): Boolean = getSnapshot().isFreshDungeonRun
    fun isSkyblock(): Boolean = getSnapshot().isSkyblock

    fun detectGameInstance(): Pair<GameInstanceType, String>? {
        val snap = getSnapshot()
        val instance = snap.instanceType ?: return null
        val area = snap.area ?: return null
        return instance to area
    }

    fun findLine(predicate: (String) -> Boolean): String? = getSnapshot().findLine(predicate)
    fun findLineContaining(substring: String, ignoreCase: Boolean = true): String? =
        getSnapshot().findLineContaining(substring, ignoreCase)
    fun findLineStartingWith(prefix: String, ignoreCase: Boolean = true): String? =
        getSnapshot().findLineStartingWith(prefix, ignoreCase)
    fun findLineMatching(regex: Regex): MatchResult? = getSnapshot().findLineMatching(regex)
    fun hasLine(predicate: (String) -> Boolean): Boolean = getSnapshot().hasLine(predicate)
    fun hasLineContaining(substring: String, ignoreCase: Boolean = true): Boolean =
        getSnapshot().hasLineContaining(substring, ignoreCase)
}
