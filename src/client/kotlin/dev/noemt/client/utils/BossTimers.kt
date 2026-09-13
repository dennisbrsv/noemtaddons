package dev.noemt.client.utils

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.ChatMessageEvent
import dev.noemt.client.event.impl.MainThreadPacketReceivedEvent
import dev.noemt.client.event.impl.RenderOverlayEvent
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.impl.WorldChangeEvent
import dev.noemt.client.render.Render2D.drawCenteredString
import dev.noemt.client.render.RenderHelper.width
import dev.noemt.client.utils.LocationUtils.dungeonFloorNumber
import dev.noemt.client.utils.LocationUtils.inBoss
import dev.noemt.client.utils.LocationUtils.inDungeon
import dev.noemt.client.utils.NumbersUtils.toFixed
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSetTimePacket

/**
 * Tick-based boss timers for all dungeon floors (F1–F7 / M1–M7).
 * Ported and expanded from NoammAddons TickTimers.
 *
 * All countdowns are tick-based (decremented each client tick), NOT millisecond-based,
 * so they stay accurate even when Hypixel's server is laggy.
 *
 * This is a standalone utility — call [init] during mod initialization.
 * It is NOT registered as a feature/module.
 *
 * Tracked timers:
 * - **Phase start timers**: Boss invulnerability / transition delays for every floor
 * - **F7 specific**: Storm pad cycle, Storm PY timer, Goldor death cycle
 * - **General dungeon**: 0-second death tick, secret tick
 */
object BossTimers {
    private val mc: Minecraft get() = Minecraft.getInstance()

    // ── Active timer state ──────────────────────────────────────────────

    /** Generic phase-start countdown (ticks remaining). -1 = inactive. */
    var startTickTime = -1
        private set

    /** Label for the current startTickTime (e.g. "§aMaxor:"). */
    var startLabel = ""
        private set

    /** Max value of the current start timer (for color scaling). */
    var startMax = 0
        private set

    // F7-specific timers
    /** Goldor death-tick cycle (resets to 60 on expiry). -1 = inactive. */
    var goldorTickTime = -1
        private set

    /** Storm pad cycle (resets to 20 on expiry). -1 = inactive. */
    var padTickTime = -1
        private set

    /** Storm PY timer. -1 = inactive. */
    var pyTickTime = -1
        private set

    private var stormActive = false
    private var pyTriggered = false

    // General dungeon timers
    /** 0-second death tick (40-tick cycle synced to game time). -1 = inactive. */
    var deathTickTime = -1
        private set

    /** Secret tick (20-tick cycle synced to game time). -1 = inactive. */
    var secretTickTime = -1
        private set

    private var dungeonStartTick = 0L

    /** Whether to display in ticks (true) or seconds (false). */
    var displayAsTicks = false

    fun init() {
        // ── Chat-triggered timers ───────────────────────────────────────

        EventBus.register<ChatMessageEvent> {
            if (!inDungeon) return@register
            val text = event.unformattedText

            when (text) {
                // ── F1: Bonzo ────────────────────────────────────────────
                "[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable." -> {
                    setStartTimer(60, "§cBonzo:")
                }
                // Bonzo re-shield / phase 2
                "[BOSS] Bonzo: Oh I'm dead!" -> {
                    setStartTimer(80, "§cBonzo P2:")
                }

                // ── F2: Scarf ────────────────────────────────────────────
                "[BOSS] Scarf: This is where the fun begins!" -> {
                    setStartTimer(60, "§6Scarf:")
                }
                "[BOSS] Scarf: THAT'S IT YOU HAVE TO GO NOW!" -> {
                    setStartTimer(60, "§6Scarf P2:")
                }

                // ── F3: The Professor ────────────────────────────────────
                "[BOSS] The Professor: I was bored anyway." -> {
                    setStartTimer(60, "§9Professor:")
                }
                "[BOSS] The Professor: I'll deal with you myself!" -> {
                    setStartTimer(60, "§9Prof P2:")
                }

                // ── F4: Thorn ────────────────────────────────────────────
                "[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And I " +
                        "will be your \"boss\" for this floor!" -> {
                    setStartTimer(80, "§2Thorn:")
                }

                // ── F5: Livid ────────────────────────────────────────────
                "[BOSS] Livid: I respect you for making it to here, but I'll be your " +
                        "undoing." -> {
                    setStartTimer(60, "§5Livid:")
                }

                // ── F6: Sadan ────────────────────────────────────────────
                "[BOSS] Sadan: So you made it all the way here...and " +
                        "you wish to defy me? Pitiful." -> {
                    setStartTimer(100, "§eSadan:")
                }
                "[BOSS] Sadan: ENOUGH!" -> {
                    setStartTimer(60, "§eSadan P2:")
                }
                "[BOSS] Sadan: My giants! Unleashed!" -> {
                    setStartTimer(60, "§eSadan P3:")
                }

                // ── F7/M7: Maxor ─────────────────────────────────────────
                "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> {
                    setStartTimer(167, "§aMaxor:")
                }
                // Maxor dead → Storm starts
                "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!" -> {
                    setStartTimer(120, "§bStorm:")
                }

                // ── F7/M7: Storm PY ──────────────────────────────────────
                "[BOSS] Storm: ENERGY HEED MY CALL!",
                "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!" -> {
                    if (!pyTriggered) {
                        pyTriggered = true
                        pyTickTime = 75
                    }
                }

                // Storm dead → Goldor starts
                "[BOSS] Storm: I should have known that I stood no chance." -> {
                    setStartTimer(104, "§7Goldor:")
                    pyTriggered = false
                    pyTickTime = -1
                    stormActive = false
                    padTickTime = -1
                }

                // Storm alive → pad cycle begins
                "[BOSS] Storm: Pathetic Maxor, just like expected." -> {
                    padTickTime = 20
                    stormActive = true
                }

                // ── F7/M7: Goldor ────────────────────────────────────────
                "[BOSS] Goldor: Who dares trespass into my domain?" -> {
                    goldorTickTime = 60
                }
                "The Core entrance is opening!" -> {
                    goldorTickTime = -1
                }

                // ── F7/M7: Necron ────────────────────────────────────────
                "[BOSS] Necron: I'm afraid, your journey ends now." -> {
                    setStartTimer(60, "§cNecron:")
                }
            }
        }

        // ── Server time sync for death tick / secret tick ────────────────

        EventBus.register<MainThreadPacketReceivedEvent.Pre> {
            if (!inDungeon) return@register
            val packet = event.packet
            if (packet !is ClientboundSetTimePacket) return@register

            val timeSinceStart = DungeonListener.currentTime - dungeonStartTick
            val shouldCheckDeath = timeSinceStart < 120 || !DungeonListener.dungeonStarted

            if (shouldCheckDeath) {
                deathTickTime = 40 - (packet.gameTime % 40).toInt()
            } else if (!inBoss) {
                secretTickTime = 20 - (packet.gameTime % 20).toInt()
                deathTickTime = -1
            }
        }

        // ── Tick-based countdown decrement ───────────────────────────────

        EventBus.register<TickEvent.Start> {
            if (!inDungeon) return@register

            if (startTickTime > 0) startTickTime--
            else if (startTickTime == 0) startTickTime = -1

            if (stormActive && padTickTime != -1) {
                padTickTime--
                if (padTickTime <= 0) padTickTime = 20
            }

            if (pyTickTime >= 0) {
                pyTickTime--
            }

            if (goldorTickTime >= 0) {
                goldorTickTime--
                if (goldorTickTime == 0) goldorTickTime = 60
            }

            if (deathTickTime >= 0) {
                deathTickTime--
                if (deathTickTime == 0) deathTickTime = 40
            }

            if (secretTickTime >= 0) {
                secretTickTime--
                if (secretTickTime == 0 && !inBoss) secretTickTime = 20
            }
        }

        // ── Dungeon start tracking ──────────────────────────────────────

        EventBus.register<dev.noemt.client.event.impl.DungeonEvent.RunStatedEvent> {
            dungeonStartTick = DungeonListener.currentTime
        }

        // ── Reset on world change ───────────────────────────────────────

        EventBus.register<WorldChangeEvent> {
            reset()
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns the currently most relevant timer as a formatted display string,
     * or null if no timer is active.
     */
    fun getActiveTimerText(): String? {
        return when {
            startTickTime != -1 -> formatTimer(startTickTime, startMax, startLabel)
            goldorTickTime != -1 -> formatTimer(goldorTickTime, 60, "§7Goldor:")
            pyTickTime != -1 -> formatTimer(pyTickTime, 75, "§5PY:")
            padTickTime != -1 -> formatTimer(padTickTime, 20, "§bPad:")
            deathTickTime != -1 -> formatTimer(deathTickTime, 40, "§cDeath:")
            secretTickTime != -1 -> formatTimer(secretTickTime, 20, "§dSecret:")
            else -> null
        }
    }

    /**
     * Returns true if any timer is currently active.
     */
    fun hasActiveTimer(): Boolean {
        return startTickTime != -1 ||
                goldorTickTime != -1 ||
                pyTickTime != -1 ||
                padTickTime != -1 ||
                deathTickTime != -1 ||
                secretTickTime != -1
    }

    /**
     * Formats a tick count into a display string.
     * Color is based on remaining percentage (green → yellow → red).
     */
    fun formatTimer(time: Int, max: Int, prefixText: String): String {
        val color = when {
            time >= max * 0.66 -> "§a"
            time >= max * 0.33 -> "§6"
            else -> "§c"
        }

        val timeDisplay = if (displayAsTicks) time.toString()
        else (time / 20f).toFixed(2)

        val suffix = if (displayAsTicks) "t" else "s"

        return "$prefixText $color$timeDisplay$suffix"
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun setStartTimer(ticks: Int, label: String) {
        startTickTime = ticks
        startLabel = label
        startMax = ticks
    }

    private fun reset() {
        startTickTime = -1
        startLabel = ""
        startMax = 0
        goldorTickTime = -1
        padTickTime = -1
        pyTickTime = -1
        stormActive = false
        pyTriggered = false
        deathTickTime = -1
        secretTickTime = -1
        dungeonStartTick = 0L
    }
}
