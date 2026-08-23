package dev.noemt.client.event.priority

import dev.noemt.client.event.EventListener

object PriorityComparator : Comparator<EventListener<*>> {
    override fun compare(o1: EventListener<*>, o2: EventListener<*>): Int =
        o1.priority.ordinal - o2.priority.ordinal
}
