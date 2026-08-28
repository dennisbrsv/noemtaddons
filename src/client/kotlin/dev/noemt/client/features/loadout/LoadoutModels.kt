package dev.noemt.client.features.loadout

import net.minecraft.world.entity.Entity

enum class MatchType {
    CONTAINS,
    EQUALS,
    STARTS_WITH,
    ENDS_WITH,
    REGEX
}

enum class MobCategory {
    ANY,
    BLOOD_MOB,
    WATCHER,
    MINIBOSS,
    BOSS,
    SLAYER,
    CUSTOM_NAME,
    CUSTOM_SKULL
}

enum class CompositeMode {
    AND,
    OR
}

sealed class LoadoutCondition {
    abstract fun matches(context: ConditionContext): Boolean

    data class ChatCondition(
        val pattern: String,
        val matchType: MatchType = MatchType.CONTAINS
    ) : LoadoutCondition() {
        override fun matches(context: ConditionContext): Boolean {
            val msg = context.chatMessage ?: return false
            return when (matchType) {
                MatchType.CONTAINS -> msg.contains(pattern, ignoreCase = true)
                MatchType.EQUALS -> msg.equals(pattern, ignoreCase = true)
                MatchType.STARTS_WITH -> msg.startsWith(pattern, ignoreCase = true)
                MatchType.ENDS_WITH -> msg.endsWith(pattern, ignoreCase = true)
                MatchType.REGEX -> runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(msg) }.getOrDefault(false)
            }
        }
    }

    data class AimCondition(
        val mobCategory: MobCategory = MobCategory.ANY,
        val nameFilter: String? = null,
        val skullTexture: String? = null,
        val maxDistance: Double = 15.0
    ) : LoadoutCondition() {
        override fun matches(context: ConditionContext): Boolean {
            val target = context.aimedEntity ?: return false
            return MobMatcher.matches(target, mobCategory, nameFilter, skullTexture)
        }
    }

    data class ProximityCondition(
        val mobCategory: MobCategory = MobCategory.ANY,
        val nameFilter: String? = null,
        val radius: Double = 8.0
    ) : LoadoutCondition() {
        override fun matches(context: ConditionContext): Boolean {
            val nearby = context.nearbyEntities ?: return false
            return nearby.any { (entity, dist) ->
                dist <= radius && MobMatcher.matches(entity, mobCategory, nameFilter, null)
            }
        }
    }

    data class LocationCondition(
        val areaName: String,
        val matchType: MatchType = MatchType.CONTAINS
    ) : LoadoutCondition() {
        override fun matches(context: ConditionContext): Boolean {
            val loc = context.location ?: return false
            return when (matchType) {
                MatchType.CONTAINS -> loc.contains(areaName, ignoreCase = true)
                MatchType.EQUALS -> loc.equals(areaName, ignoreCase = true)
                MatchType.STARTS_WITH -> loc.startsWith(areaName, ignoreCase = true)
                MatchType.ENDS_WITH -> loc.endsWith(areaName, ignoreCase = true)
                MatchType.REGEX -> runCatching { Regex(areaName, RegexOption.IGNORE_CASE).containsMatchIn(loc) }.getOrDefault(false)
            }
        }
    }

    data class CompositeCondition(
        val conditions: List<LoadoutCondition>,
        val mode: CompositeMode = CompositeMode.AND
    ) : LoadoutCondition() {
        override fun matches(context: ConditionContext): Boolean {
            if (conditions.isEmpty()) return false
            return when (mode) {
                CompositeMode.AND -> conditions.all { it.matches(context) }
                CompositeMode.OR -> conditions.any { it.matches(context) }
            }
        }
    }
}

data class ConditionContext(
    val chatMessage: String? = null,
    val aimedEntity: Entity? = null,
    val nearbyEntities: List<Pair<Entity, Double>>? = null,
    val location: String? = null
)

object SkyblockLoadoutConstants {
    val LOADOUT_MENU_REGEX = Regex("""^\(\d+/\d+\) Loadouts$""")
    val LOADOUT_SLOTS = listOf(
        14, 15, 16,
        23, 24, 25,
        32, 33, 34,
        41, 42, 43
    )
}

data class Loadout(
    val id: String,
    var name: String,
    var loadoutSlot: Int = 1, // Skyblock Loadout Slot 1..12 (maps to LOADOUT_SLOTS[loadoutSlot - 1])
    var openCommand: String = "/loadouts",
    var petName: String? = null,
    var slot: Int? = null, // Hotbar slot (0..8)
    var commands: MutableList<String> = mutableListOf(),
    var delayMs: Long = 100L
) {
    val containerSlot: Int
        get() = SkyblockLoadoutConstants.LOADOUT_SLOTS.getOrElse(loadoutSlot - 1) { 14 }
}

data class LoadoutRule(
    val id: String,
    var name: String,
    var enabled: Boolean = true,
    var targetLoadoutId: String,
    var condition: LoadoutCondition,
    var cooldownSeconds: Double = 2.0,
    var onlyIfNotCurrent: Boolean = true,
    @Transient var lastTriggeredMs: Long = 0L
)
