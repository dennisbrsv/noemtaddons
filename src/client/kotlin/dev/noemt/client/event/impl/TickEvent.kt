package dev.noemt.client.event.impl

import dev.noemt.client.event.Event

abstract class TickEvent : Event(cancelable = false) {
    object Start : TickEvent()
    object End : TickEvent()
    object Server : TickEvent()
}
