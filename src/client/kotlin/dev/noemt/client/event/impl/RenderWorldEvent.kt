package dev.noemt.client.event.impl

import dev.noemt.client.event.Event
import dev.noemt.client.render.RenderContext

class RenderWorldEvent(val ctx: RenderContext) : Event(cancelable = false)
