package dev.noemt.client.event.impl

import dev.noemt.client.event.Event
import net.minecraft.world.entity.Entity

class EntityUnloadEvent(val entity: Entity) : Event(cancelable = false)
