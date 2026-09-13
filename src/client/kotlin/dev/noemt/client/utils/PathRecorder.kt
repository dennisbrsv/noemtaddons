package dev.noemt.client.utils

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.RenderWorldEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.render.Render3D.renderBlock
import dev.noemt.client.render.Render3D.renderLine
import dev.noemt.client.utils.ChatUtils.modMessage
import dev.noemt.client.utils.MathUtils.toVec
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.awt.Color

/**
 * Utility for recording paths as a sequence of block positions.
 *
 * Usage (from any feature or command):
 *   PathRecorder.startRecording("myPath")  — begins a new recording session
 *   PathRecorder.addPoint()                — adds the player's current block position
 *   PathRecorder.stopRecording()           — ends the session and returns the recorded list
 *   PathRecorder.getRecording("myPath")    — retrieves a saved recording by name
 *   PathRecorder.clearRecording("myPath")  — deletes a saved recording
 *   PathRecorder.clearAll()                — deletes all saved recordings
 *
 * Rendering is always active for the current session so you can see what you've recorded.
 */
object PathRecorder {
    private val mc: Minecraft get() = Minecraft.getInstance()

    private var currentName: String? = null
    private var currentPoints = mutableListOf<BlockPos>()
    private val savedRecordings = LinkedHashMap<String, List<BlockPos>>()

    val isRecording: Boolean get() = currentName != null
    val currentSessionName: String? get() = currentName
    val currentSessionPoints: List<BlockPos> get() = currentPoints

    // Pre-cached colors to avoid allocation in render loops
    private val POINT_OUTLINE = Color(0, 255, 170)
    private val POINT_FILL = Color(0, 255, 170, 40)
    private val LINE_COLOR = Color(0, 255, 170, 160)

    fun init() {
        EventBus.register<RenderWorldEvent> {
            if (!isRecording || currentPoints.isEmpty()) return@register
            val points = currentPoints

            for (pos in points) {
                event.ctx.renderBlock(pos, POINT_OUTLINE, POINT_FILL, outline = true, fill = true, phase = true)
            }

            if (points.size >= 2) {
                for (i in 0 until points.size - 1) {
                    val from = points[i]
                    val to = points[i + 1]
                    val fromCenter = Vec3(from.x + 0.5, from.y + 0.5, from.z + 0.5)
                    val toCenter = Vec3(to.x + 0.5, to.y + 0.5, to.z + 0.5)
                    event.ctx.renderLine(fromCenter, toCenter, LINE_COLOR, thickness = 2, phase = true)
                }
            }
        }

        EventBus.register<WorldChangeEvent> {
            if (isRecording) {
                modMessage("§cPath recording '§e${currentName}§c' was stopped due to world change.")
                stopRecording()
            }
        }
    }

    /**
     * Starts a new recording session with the given name.
     * If a recording with that name already exists, it will be overwritten when stopped.
     */
    fun startRecording(name: String) {
        if (isRecording) {
            modMessage("§cAlready recording '§e${currentName}§c'. Stop it first.")
            return
        }
        currentName = name
        currentPoints = mutableListOf()
        modMessage("§aStarted recording path '§e$name§a'. Use addPoint to record positions.")
    }

    /**
     * Adds the player's current block position to the recording.
     * Returns the position that was added, or null if not recording.
     */
    fun addPoint(): BlockPos? {
        if (!isRecording) {
            modMessage("§cNot currently recording. Use startRecording first.")
            return null
        }
        val player = mc.player ?: return null
        val pos = player.blockPosition()

        // Don't add duplicate consecutive positions
        if (currentPoints.isNotEmpty() && currentPoints.last() == pos) {
            modMessage("§eSame position as last point, skipped.")
            return null
        }

        currentPoints.add(pos)
        modMessage("§aPoint §e#${currentPoints.size}§a added: §f(${pos.x}, ${pos.y}, ${pos.z})")
        return pos
    }

    /**
     * Adds a specific block position to the recording.
     */
    fun addPoint(pos: BlockPos): BlockPos? {
        if (!isRecording) {
            modMessage("§cNot currently recording. Use startRecording first.")
            return null
        }

        if (currentPoints.isNotEmpty() && currentPoints.last() == pos) {
            modMessage("§eSame position as last point, skipped.")
            return null
        }

        currentPoints.add(pos)
        modMessage("§aPoint §e#${currentPoints.size}§a added: §f(${pos.x}, ${pos.y}, ${pos.z})")
        return pos
    }

    /**
     * Removes the last recorded point. Returns the removed position or null.
     */
    fun undoPoint(): BlockPos? {
        if (!isRecording || currentPoints.isEmpty()) {
            modMessage("§cNothing to undo.")
            return null
        }
        val removed = currentPoints.removeAt(currentPoints.lastIndex)
        modMessage("§aRemoved last point: §f(${removed.x}, ${removed.y}, ${removed.z})§a. ${currentPoints.size} points remaining.")
        return removed
    }

    /**
     * Stops the current recording session, saves it, and returns the list of points.
     */
    fun stopRecording(): List<BlockPos> {
        val name = currentName
        if (name == null) {
            modMessage("§cNot currently recording.")
            return emptyList()
        }
        val points = currentPoints.toList()
        if (points.isNotEmpty()) {
            savedRecordings[name] = points
        }
        modMessage("§aStopped recording '§e$name§a' with §e${points.size}§a points.")
        currentName = null
        currentPoints = mutableListOf()
        return points
    }

    /**
     * Retrieves a saved recording by name.
     */
    fun getRecording(name: String): List<BlockPos>? = savedRecordings[name]

    /**
     * Returns all saved recording names.
     */
    fun getRecordingNames(): Set<String> = savedRecordings.keys.toSet()

    /**
     * Deletes a saved recording.
     */
    fun clearRecording(name: String): Boolean {
        val removed = savedRecordings.remove(name) != null
        if (removed) modMessage("§aCleared recording '§e$name§a'.")
        else modMessage("§cNo recording found with name '§e$name§c'.")
        return removed
    }

    /**
     * Deletes all saved recordings.
     */
    fun clearAll() {
        savedRecordings.clear()
        modMessage("§aAll recordings cleared.")
    }

    /**
     * Returns a printable summary of a recording as a list of coordinate strings.
     */
    fun formatRecording(name: String): String? {
        val points = savedRecordings[name] ?: return null
        return points.joinToString("\n") { "(${it.x}, ${it.y}, ${it.z})" }
    }

    /**
     * Returns all recorded points as a Kotlin-style list literal for easy copy-paste into code.
     */
    fun formatAsCode(name: String): String? {
        val points = savedRecordings[name] ?: return null
        return buildString {
            appendLine("listOf(")
            for ((i, pos) in points.withIndex()) {
                append("    BlockPos(${pos.x}, ${pos.y}, ${pos.z})")
                if (i < points.size - 1) appendLine(",") else appendLine()
            }
            append(")")
        }
    }
}
