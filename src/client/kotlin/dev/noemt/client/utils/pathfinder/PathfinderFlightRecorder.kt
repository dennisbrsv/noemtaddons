package dev.noemt.client.utils.pathfinder

import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.NumbersUtils.toFixed
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList

object PathfinderFlightRecorder {
    private val mc: Minecraft get() = Minecraft.getInstance()

    data class LogEntry(
        val timeOffsetMs: Long,
        val tick: Int,
        val state: String,
        val playerPos: Vec3,
        val playerRot: Pair<Float, Float>,
        val message: String
    )

    private var sessionStartTime = 0L
    private var sessionTickCount = 0
    private var startBlock: BlockPos? = null
    private var goalBlock: BlockPos? = null
    private var initialPath: List<PathNode> = emptyList()
    private val entries = CopyOnWriteArrayList<LogEntry>()

    private var lastTargetNode: PathNode? = null
    private var lastTargetPos: BlockPos? = null

    fun setLastTarget(node: PathNode?, pos: BlockPos?) {
        lastTargetNode = node
        lastTargetPos = pos
    }

    fun startSession(start: BlockPos, goal: BlockPos, path: List<PathNode>) {
        sessionStartTime = System.currentTimeMillis()
        sessionTickCount = 0
        startBlock = start
        goalBlock = goal
        initialPath = path
        lastTargetNode = null
        lastTargetPos = null
        entries.clear()

        record("START", "Navigation started to ($goal.x, $goal.y, $goal.z) with ${path.size} waypoints.")
    }

    fun tick() {
        if (sessionStartTime != 0L) {
            sessionTickCount++
        }
    }

    fun record(state: String, message: String) {
        val player = mc.player
        val pos = player?.position() ?: Vec3.ZERO
        val rot = Pair(player?.yRot ?: 0f, player?.xRot ?: 0f)
        val offset = if (sessionStartTime == 0L) 0L else System.currentTimeMillis() - sessionStartTime

        val entry = LogEntry(
            timeOffsetMs = offset,
            tick = sessionTickCount,
            state = state,
            playerPos = pos,
            playerRot = rot,
            message = message
        )
        entries.add(entry)
    }

    private fun renderVoxelGrid(center: BlockPos, title: String, centerChar: Char = 'X'): String {
        val level = mc.level ?: return ""
        val sb = StringBuilder()
        sb.appendLine("--- $title at (${center.x}, ${center.y}, ${center.z}) ---")
        sb.appendLine("Overhead Clearance: ${TeleportPathfinder.getOverheadClearance(center)} blocks")
        sb.appendLine("Center Block: ${level.getBlockState(center).block.descriptionId.removePrefix("block.minecraft.")}")
        sb.appendLine("Above 1: ${level.getBlockState(center.above(1)).block.descriptionId.removePrefix("block.minecraft.")} | Above 2: ${level.getBlockState(center.above(2)).block.descriptionId.removePrefix("block.minecraft.")}")
        sb.appendLine("Below 1 (Floor): ${level.getBlockState(center.below(1)).block.descriptionId.removePrefix("block.minecraft.")} | Is Valid Floor: ${TeleportPathfinder.isValidLandingSpot(center.below())}")
        sb.appendLine()

        val legend = mutableMapOf<String, Char>()
        var nextChar = 'A'

        fun getCharForBlock(name: String): Char {
            if (name == "air" || name == "cave_air") return '.'
            return legend.getOrPut(name) {
                val c = nextChar
                if (nextChar < 'Z') nextChar++
                c
            }
        }

        for (dy in -1..2) {
            val yLevel = center.y + dy
            val label = when (dy) {
                -1 -> "Ground / Floor Layer (Y=${yLevel})"
                0 -> "Center / Feet Level (Y=${yLevel})"
                1 -> "Eye / Head Level (Y=${yLevel})"
                2 -> "Overhead Layer (Y=${yLevel})"
                else -> "Layer Y=${yLevel}"
            }
            sb.appendLine("[$label]")
            sb.append("      ")
            for (dx in -3..3) sb.append("${String.format("%+2d", dx)} ")
            sb.appendLine()

            for (dz in -3..3) {
                sb.append("Z${String.format("%+2d", dz)}: ")
                for (dx in -3..3) {
                    val check = center.offset(dx, dy, dz)
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) {
                        sb.append(" $centerChar ")
                    } else {
                        val state = level.getBlockState(check)
                        val name = state.block.descriptionId.removePrefix("block.minecraft.")
                        val c = getCharForBlock(name)
                        sb.append(" $c ")
                    }
                }
                sb.appendLine()
            }
            sb.appendLine()
        }

        sb.appendLine("--- Block Type Legend ---")
        sb.appendLine("  . : air / passable")
        sb.appendLine("  $centerChar : Target Center")
        for ((name, symbol) in legend) {
            sb.appendLine("  $symbol : $name")
        }
        sb.appendLine()
        return sb.toString()
    }

    fun dumpToClipboard(): String {
        val sb = StringBuilder()
        sb.appendLine("================== PATHFINDER FLIGHT RECORDER DUMP ==================")
        sb.appendLine("Session: Start=(${startBlock?.x}, ${startBlock?.y}, ${startBlock?.z}) | Goal=(${goalBlock?.x}, ${goalBlock?.y}, ${goalBlock?.z})")
        sb.appendLine("Total Session Ticks: $sessionTickCount | Total Duration: ${if (sessionStartTime == 0L) 0 else System.currentTimeMillis() - sessionStartTime}ms")
        sb.appendLine()

        val profile = TeleportAbilityHelper.getBestTeleportItem()
        sb.appendLine("--- Held Teleport Profile ---")
        if (profile != null) {
            sb.appendLine("Item: ${profile.skyblockId} | Slot: ${profile.slot}")
            sb.appendLine("Instant Range: ${profile.instantTransmissionRange}m | Etherwarp: ${profile.hasEtherwarp} (Range: ${profile.etherwarpRange}m)")
        } else {
            sb.appendLine("No valid teleport item detected.")
        }
        sb.appendLine()

        sb.appendLine("--- Initial Planned Path (${initialPath.size} nodes) ---")
        for ((idx, node) in initialPath.withIndex()) {
            sb.appendLine("[$idx] Pos=(${node.pos.x}, ${node.pos.y}, ${node.pos.z}) | Action=${node.action} | gCost=${node.gCost.toFixed(2)} | Data=${node.actionData}")
        }
        sb.appendLine()

        sb.appendLine("--- Execution Timeline (${entries.size} events) ---")
        for (e in entries) {
            sb.appendLine("[+${e.timeOffsetMs}ms | T+${e.tick}] [${e.state}] Pos=(${e.playerPos.x.toFixed(2)}, ${e.playerPos.y.toFixed(2)}, ${e.playerPos.z.toFixed(2)}) Rot=(${e.playerRot.first.toFixed(1)}, ${e.playerRot.second.toFixed(1)}) | ${e.message}")
        }
        sb.appendLine()

        val player = mc.player
        val level = mc.level
        if (player != null && level != null) {
            // 1. Snapshot around Player Position
            val pPos = player.blockPosition()
            sb.append(renderVoxelGrid(pPos, "Player Position Environment Snapshot", 'P'))

            // 2. Snapshot around Last Action / Failed Target Node
            val tPos = lastTargetPos ?: (lastTargetNode?.actionData as? BlockPos) ?: lastTargetNode?.pos
            if (tPos != null && tPos != pPos) {
                sb.append(renderVoxelGrid(tPos, "Failed / Active Target Node Environment (Action=${lastTargetNode?.action})", 'T'))

                // Line of Sight check from Player Eye to Target Center
                val eye = player.eyePosition
                val targetCenter = Vec3(tPos.x + 0.5, tPos.y + 0.5, tPos.z + 0.5)
                val hit = level.clip(net.minecraft.world.level.ClipContext(eye, targetCenter, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player))

                sb.appendLine("--- Line-of-Sight Raycast: Player Eye -> Target (${tPos.x}, ${tPos.y}, ${tPos.z}) ---")
                sb.appendLine("Distance: ${eye.distanceTo(targetCenter).toFixed(2)}m")
                sb.appendLine("Hit Result: ${hit.type}")
                if (hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    val hitPos = (hit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                        ?: BlockPos.containing(hit.location.x, hit.location.y, hit.location.z)
                    val hitState = level.getBlockState(hitPos)
                    sb.appendLine("Hit Block: '${hitState.block.descriptionId.removePrefix("block.minecraft.")}' at (${hitPos.x}, ${hitPos.y}, ${hitPos.z})")
                    sb.appendLine("Target Match: ${hitPos == tPos}")
                }
                sb.appendLine()
            }

            // 3. Raycast to Final Goal
            val goal = goalBlock
            if (goal != null) {
                val goalCenter = Vec3(goal.x + 0.5, goal.y + 0.5, goal.z + 0.5)
                val eye = player.eyePosition
                val hit = level.clip(net.minecraft.world.level.ClipContext(eye, goalCenter, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player))

                sb.appendLine("--- Sightline Raycast to Final Goal (${goal.x}, ${goal.y}, ${goal.z}) ---")
                sb.appendLine("Distance to Goal: ${eye.distanceTo(goalCenter).toFixed(2)}m")
                sb.appendLine("Raycast Hit Type: ${hit.type}")
                if (hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    val hitPos = (hit as? net.minecraft.world.phys.BlockHitResult)?.blockPos
                        ?: BlockPos.containing(hit.location.x, hit.location.y, hit.location.z)
                    val hitState = level.getBlockState(hitPos)
                    sb.appendLine("Obstructing Block: '${hitState.block.descriptionId.removePrefix("block.minecraft.")}' at (${hitPos.x}, ${hitPos.y}, ${hitPos.z})")
                    sb.appendLine("Intersection Point: (${hit.location.x.toFixed(2)}, ${hit.location.y.toFixed(2)}, ${hit.location.z.toFixed(2)})")
                } else {
                    sb.appendLine("Direct Line of Sight to Goal is 100% CLEAR!")
                }
            }
        }

        sb.appendLine("====================================================================")

        val text = sb.toString()
        mc.keyboardHandler.clipboard = text
        ChatUtils.modMessage("&a[Pathfinder] Dumped Flight Recorder & Target Snapshot (${entries.size} events) to clipboard! &e(Paste with Ctrl+V)")
        return text
    }
}
