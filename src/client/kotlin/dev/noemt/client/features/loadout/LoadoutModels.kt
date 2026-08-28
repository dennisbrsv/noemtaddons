package dev.noemt.client.features.loadout

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import dev.noemt.client.utils.LocationUtils
import net.minecraft.world.entity.Entity
import java.lang.reflect.Type

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

enum class GameInstanceType(
    val displayName: String,
    val keywords: List<String>,
    val negativeKeywords: List<String> = emptyList()
) {
    DUNGEONS("The Catacombs", listOf("CATACOMBS (", "THE CATACOMBS"), listOf("DUNGEON HUB", "HUB")),
    DUNGEON_BOSS("Dungeon Boss Room", listOf("BOSS ROOM", "CATACOMBS BOSS")),
    DUNGEON_HUB("Dungeon Hub", listOf("DUNGEON HUB")),
    KUUDRA("Kuudra Arena", listOf("KUUDRA")),
    CRIMSON_ISLE("Crimson Isle", listOf("CRIMSON ISLE", "CRIMSON"), listOf("KUUDRA")),
    THE_END("The End", listOf("THE END", "DRAGON'S NEST")),
    GARDEN("The Garden", listOf("THE GARDEN", "GARDEN", "BARN")),
    DWARVEN_MINES("Dwarven Mines", listOf("DWARVEN MINES", "DWARVEN")),
    CRYSTAL_HOLLOWS("Crystal Hollows", listOf("CRYSTAL HOLLOWS")),
    MINESHAFT("Glacite Mineshafts", listOf("MINESHAFT", "GLACITE")),
    THE_PARK("The Park", listOf("THE PARK", "SPRUCE WOODS", "DARK THICKET")),
    SPIDER_DEN("Spider's Den", listOf("SPIDER'S DEN", "SPIDERS DEN")),
    THE_RIFT("The Rift", listOf("THE RIFT")),
    DARK_AUCTION("Dark Auction", listOf("DARK AUCTION")),
    WINTER("Jerry's Workshop", listOf("JERRY'S WORKSHOP", "WINTER")),
    HUB("Hub", listOf("VILLAGE", "THE HUB"), listOf("DUNGEON HUB")),
    PRIVATE_ISLAND("Private Island", listOf("PRIVATE ISLAND", "YOUR ISLAND"))
}

sealed class LoadoutCondition {
    abstract fun matches(context: ConditionContext): Boolean

    data class GameInstanceCondition(
        val instanceType: GameInstanceType,
        val floorFilter: String? = null
    ) : LoadoutCondition() {
        override fun matches(context: ConditionContext): Boolean {
            val loc = context.location ?: return false
            val locUpper = loc.uppercase()
            if (instanceType.negativeKeywords.any { locUpper.contains(it) }) return false
            return instanceType.keywords.any { locUpper.contains(it) } || locUpper.contains(instanceType.name)
        }
    }

    data class MinibossCondition(
        val autoRevertOnKill: Boolean = true
    ) : LoadoutCondition() {
        override fun matches(context: ConditionContext): Boolean {
            if (!LocationUtils.inDungeon) return false
            val target = context.aimedEntity ?: return false
            return MobMatcher.matches(target, MobCategory.MINIBOSS)
        }
    }

    data class BloodRoomCondition(
        val autoRevertOnClear: Boolean = false
    ) : LoadoutCondition() {
        override fun matches(context: ConditionContext): Boolean {
            if (!LocationUtils.inDungeon) return false
            if (context.inBloodRoom == true) return true
            if (context.dungeonRoomType == dev.noemt.client.utils.map.core.RoomType.BLOOD) return true
            return dev.noemt.client.features.blood.AutoBloodCamp.isPlayerInBloodRoom()
        }
    }

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
            if (mobCategory == MobCategory.BLOOD_MOB && (context.inBloodRoom == true || dev.noemt.client.features.blood.AutoBloodCamp.isPlayerInBloodRoom())) {
                return true
            }
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

class LoadoutConditionAdapter : JsonSerializer<LoadoutCondition>, JsonDeserializer<LoadoutCondition> {
    override fun serialize(src: LoadoutCondition, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        when (src) {
            is LoadoutCondition.GameInstanceCondition -> {
                obj.addProperty("type", "GAME_INSTANCE")
                obj.addProperty("instanceType", src.instanceType.name)
                if (src.floorFilter != null) obj.addProperty("floorFilter", src.floorFilter)
            }
            is LoadoutCondition.MinibossCondition -> {
                obj.addProperty("type", "MINIBOSS")
                obj.addProperty("autoRevertOnKill", src.autoRevertOnKill)
            }
            is LoadoutCondition.BloodRoomCondition -> {
                obj.addProperty("type", "BLOOD_ROOM")
                obj.addProperty("autoRevertOnClear", src.autoRevertOnClear)
            }
            is LoadoutCondition.AimCondition -> {
                obj.addProperty("type", "AIM")
                obj.addProperty("mobCategory", src.mobCategory.name)
                if (src.nameFilter != null) obj.addProperty("nameFilter", src.nameFilter)
                if (src.skullTexture != null) obj.addProperty("skullTexture", src.skullTexture)
                obj.addProperty("maxDistance", src.maxDistance)
            }
            is LoadoutCondition.ChatCondition -> {
                obj.addProperty("type", "CHAT")
                obj.addProperty("pattern", src.pattern)
                obj.addProperty("matchType", src.matchType.name)
            }
            is LoadoutCondition.ProximityCondition -> {
                obj.addProperty("type", "PROXIMITY")
                obj.addProperty("mobCategory", src.mobCategory.name)
                if (src.nameFilter != null) obj.addProperty("nameFilter", src.nameFilter)
                obj.addProperty("radius", src.radius)
            }
            is LoadoutCondition.LocationCondition -> {
                obj.addProperty("type", "LOCATION")
                obj.addProperty("areaName", src.areaName)
                obj.addProperty("matchType", src.matchType.name)
            }
            is LoadoutCondition.CompositeCondition -> {
                obj.addProperty("type", "COMPOSITE")
                obj.addProperty("mode", src.mode.name)
                obj.add("conditions", context.serialize(src.conditions))
            }
        }
        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): LoadoutCondition {
        val obj = json.asJsonObject
        val type = obj.get("type")?.asString ?: "AIM"
        return when (type) {
            "GAME_INSTANCE" -> {
                val instName = obj.get("instanceType")?.asString ?: "DUNGEONS"
                val instEnum = runCatching { GameInstanceType.valueOf(instName) }.getOrDefault(GameInstanceType.DUNGEONS)
                val floor = obj.get("floorFilter")?.asString
                LoadoutCondition.GameInstanceCondition(instEnum, floor)
            }
            "MINIBOSS" -> {
                val revert = obj.get("autoRevertOnKill")?.asBoolean ?: true
                LoadoutCondition.MinibossCondition(revert)
            }
            "BLOOD_ROOM" -> {
                val revert = obj.get("autoRevertOnClear")?.asBoolean ?: false
                LoadoutCondition.BloodRoomCondition(revert)
            }
            "AIM" -> {
                val catName = obj.get("mobCategory")?.asString ?: "ANY"
                val catEnum = runCatching { MobCategory.valueOf(catName) }.getOrDefault(MobCategory.ANY)
                val nameFilter = obj.get("nameFilter")?.asString
                val skull = obj.get("skullTexture")?.asString
                val dist = obj.get("maxDistance")?.asDouble ?: 15.0
                LoadoutCondition.AimCondition(catEnum, nameFilter, skull, dist)
            }
            "CHAT" -> {
                val pattern = obj.get("pattern")?.asString ?: ""
                val matchName = obj.get("matchType")?.asString ?: "CONTAINS"
                val matchEnum = runCatching { MatchType.valueOf(matchName) }.getOrDefault(MatchType.CONTAINS)
                LoadoutCondition.ChatCondition(pattern, matchEnum)
            }
            "PROXIMITY" -> {
                val catName = obj.get("mobCategory")?.asString ?: "ANY"
                val catEnum = runCatching { MobCategory.valueOf(catName) }.getOrDefault(MobCategory.ANY)
                val nameFilter = obj.get("nameFilter")?.asString
                val rad = obj.get("radius")?.asDouble ?: 8.0
                LoadoutCondition.ProximityCondition(catEnum, nameFilter, rad)
            }
            "LOCATION" -> {
                val area = obj.get("areaName")?.asString ?: ""
                val matchName = obj.get("matchType")?.asString ?: "CONTAINS"
                val matchEnum = runCatching { MatchType.valueOf(matchName) }.getOrDefault(MatchType.CONTAINS)
                LoadoutCondition.LocationCondition(area, matchEnum)
            }
            "COMPOSITE" -> {
                val condsType = object : TypeToken<List<LoadoutCondition>>() {}.type
                val conds: List<LoadoutCondition> = context.deserialize(obj.get("conditions"), condsType) ?: emptyList()
                val modeName = obj.get("mode")?.asString ?: "AND"
                val modeEnum = runCatching { CompositeMode.valueOf(modeName) }.getOrDefault(CompositeMode.AND)
                LoadoutCondition.CompositeCondition(conds, modeEnum)
            }
            else -> LoadoutCondition.AimCondition(MobCategory.ANY)
        }
    }
}

data class ConditionContext(
    val chatMessage: String? = null,
    val aimedEntity: Entity? = null,
    val nearbyEntities: List<Pair<Entity, Double>>? = null,
    val location: String? = null,
    val inBloodRoom: Boolean? = null,
    val dungeonRoomType: dev.noemt.client.utils.map.core.RoomType? = null
)

object SkyblockLoadoutConstants {
    val LOADOUT_MENU_REGEX = Regex("""(?:\(\d+/\d+\)\s+)?(?:Loadouts|Wardrobe)""", RegexOption.IGNORE_CASE)
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
    var itemType: String? = null,
    var skullTexture: String? = null,
    var nbtString: String? = null,
    var dyedColor: Int? = null,
    var hasGlint: Boolean = false,
    var loreLines: List<String> = emptyList(),
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
