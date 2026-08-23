package dev.noemt.client.event.impl

import dev.noemt.client.event.Event
import net.minecraft.network.protocol.Packet

abstract class MainThreadPacketReceivedEvent(val packet: Packet<*>, cancelable: Boolean) : Event(cancelable) {
    class Pre(packet: Packet<*>) : MainThreadPacketReceivedEvent(packet, cancelable = true)
    class Post(packet: Packet<*>) : MainThreadPacketReceivedEvent(packet, cancelable = false)
}
