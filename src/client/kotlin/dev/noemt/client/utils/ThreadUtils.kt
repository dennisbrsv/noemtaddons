package dev.noemt.client.utils

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.TickEvent
import dev.noemt.client.event.priority.EventPriority
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

object ThreadUtils {
    private val logger = LoggerFactory.getLogger("noemtaddons-threads")
    private val clientScheduler = TickScheduler()
    private val serverScheduler = TickScheduler()

    fun init() {
        EventBus.register<TickEvent.Start>(EventPriority.HIGHEST) { clientScheduler.tick() }
        EventBus.register<TickEvent.Server>(EventPriority.HIGHEST) { serverScheduler.tick() }
    }

    fun runOnMcThread(block: Runnable) {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) safeRun(block) else mc.execute { safeRun(block) }
    }

    fun scheduledTask(ticks: Number = 0, block: Runnable) = clientScheduler.schedule(ticks, block)
    fun scheduledTaskServer(ticks: Number = 0, block: Runnable) = serverScheduler.schedule(ticks, block)

    private fun safeRun(action: Runnable) {
        runCatching { action.run() }.onFailure { logger.error("Error in scheduled task", it) }
    }

    private class TickScheduler {
        private val queue = PriorityBlockingQueue<TickTask>()
        private val tickCounter = AtomicLong()

        fun schedule(ticks: Number, action: Runnable) {
            val scheduledTick = tickCounter.get() + ticks.toLong().coerceAtLeast(0L) + 1L
            queue.add(TickTask(scheduledTick, action))
        }

        fun tick() {
            val currentTick = tickCounter.incrementAndGet()
            while (true) {
                val next = queue.peek() ?: return
                if (next.executeAtTick > currentTick) return
                queue.poll()?.run { safeRun(action) }
            }
        }
    }

    private class TickTask(val executeAtTick: Long, val action: Runnable) : Comparable<TickTask> {
        override fun compareTo(other: TickTask) = executeAtTick.compareTo(other.executeAtTick)
    }
}
