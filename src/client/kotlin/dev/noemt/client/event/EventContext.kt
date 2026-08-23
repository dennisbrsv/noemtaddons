package dev.noemt.client.event

class EventContext<T : Event>(val event: T, var listener: EventListener<T>)
