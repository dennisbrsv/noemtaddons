package dev.noemt.client.event

import dev.noemt.client.event.priority.EventPriority
import dev.noemt.client.event.priority.PriorityComparator
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object EventBus {
    private val logger = LoggerFactory.getLogger("noemtaddons-eventbus")
    val listeners = ConcurrentHashMap<Class<out Event>, List<EventListener<*>>>()

    fun _registerListener(listener: EventListener<*>) {
        listeners.compute(listener.eventClass) { _, old ->
            (old.orEmpty() + listener).sortedWith(PriorityComparator)
        }
    }

    fun _unregisterListener(listener: EventListener<*>) {
        listeners.compute(listener.eventClass) { _, old ->
            old?.filter { it !== listener }?.takeIf(Collection<*>::isNotEmpty)
        }
    }

    @JvmStatic
    fun <T : Event> post(event: T): Boolean {
        val eventListeners = listeners[event.javaClass] ?: return event.isCanceled
        var context: EventContext<T>? = null

        @Suppress("UNCHECKED_CAST")
        for (listener in eventListeners) {
            try {
                val typedListener = listener as EventListener<T>
                if (event.isCanceled && !typedListener.receiveCancelled) continue
                val currentContext = context ?: EventContext(event, typedListener).also { context = it }
                currentContext.listener = typedListener
                typedListener.callback.invoke(currentContext)
            } catch (exception: Exception) {
                logger.error("Error dispatching event ${event.javaClass.simpleName}", exception)
            }
        }

        return event.isCanceled
    }

    inline fun <reified T : Event> listener(
        priority: EventPriority = EventPriority.NORMAL,
        receiveCancelled: Boolean = false,
        noinline callback: EventContext<T>.() -> Unit
    ) = EventListener(T::class.java, priority, receiveCancelled, callback)

    inline fun <reified T : Event> register(
        priority: EventPriority = EventPriority.NORMAL,
        receiveCancelled: Boolean = false,
        noinline callback: EventContext<T>.() -> Unit
    ) = listener<T>(priority, receiveCancelled, callback).register()
}
