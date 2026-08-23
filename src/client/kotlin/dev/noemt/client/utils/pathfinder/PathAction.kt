package dev.noemt.client.utils.pathfinder

import java.awt.Color

enum class PathAction(val displayName: String, val color: Color) {
    WALK("Walk", Color(85, 255, 85)),
    SPRINT_JUMP("Sprint Jump", Color(255, 255, 85)),
    JUMP_UP("Jump Up", Color(255, 170, 0)),
    DROP("Drop", Color(170, 170, 255)),
    INSTANT_TRANSMISSION("Instant Transmission", Color(85, 255, 255)),
    ETHERWARP("Etherwarp", Color(255, 85, 255))
}
