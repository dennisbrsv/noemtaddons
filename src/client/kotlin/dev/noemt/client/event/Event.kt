package dev.noemt.client.event

abstract class Event(val cancelable: Boolean = false) {
    @Volatile
    open var isCanceled = false
        set(value) {
            if (!cancelable && value) throw RuntimeException("Tried to cancel an uncancelable event")
            field = value
        }

    open fun cancel() {
        isCanceled = true
    }
}
