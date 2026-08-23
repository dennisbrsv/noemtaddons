package dev.noemt.client.utils.dungeon

import java.awt.Color

enum class DungeonClass(val color: Color) {
    Archer(Color(255, 170, 0)),
    Berserk(Color(255, 85, 85)),
    Healer(Color(255, 85, 255)),
    Mage(Color(85, 255, 255)),
    Tank(Color(85, 85, 85)),
    Empty(Color(255, 255, 255));

    companion object {
        fun fromName(name: String) = entries.find {
            it.name.equals(name, ignoreCase = true)
        } ?: Empty
    }
}
