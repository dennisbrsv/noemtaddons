package dev.noemt.client.event.impl

import dev.noemt.client.event.Event
import net.minecraft.client.gui.GuiGraphicsExtractor

class RenderOverlayEvent(val context: GuiGraphicsExtractor) : Event(cancelable = false)
