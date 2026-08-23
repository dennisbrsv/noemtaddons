package dev.noemt.client.event.impl

import dev.noemt.client.event.Event
import dev.noemt.client.utils.ChatUtils.formattedText
import dev.noemt.client.utils.ChatUtils.unformattedText
import net.minecraft.network.chat.Component

class ChatMessageEvent(val component: Component) : Event(cancelable = true) {
    val formattedText by lazy { component.formattedText }
    val unformattedText by lazy { component.unformattedText }
}
