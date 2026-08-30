package dev.noemt.client.features.loadout

import dev.noemt.client.features.blood.BloodCamp
import dev.noemt.client.utils.DungeonListener
import dev.noemt.client.utils.ItemUtils
import dev.noemt.client.utils.LocationUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB

object MobMatcher {
    private val mc: Minecraft get() = Minecraft.getInstance()

    private val MINIBOSS_NAMES = listOf(
        "Shadow Assassin", "Lost Adventurer", "Angry Archaeologist", "King Midas", "Frozen Adventurer"
    )

    private val BOSS_NAMES = listOf(
        "Bonzo", "Scarf", "Professor", "Thorn", "Livid", "Sadan", "Necron", "Maxor", "Storm", "Goldor", "Kuudra"
    )

    private val SLAYER_NAMES = listOf(
        "Revenant Horror", "Atoned Horror", "Tarantula Broodfather", "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend"
    )

    fun isTeammate(entity: Entity): Boolean {
        if (entity == mc.player) return true
        val name = entity.name.string
        return DungeonListener.dungeonTeammates.any { it.name.equals(name, ignoreCase = true) || it.entity == entity }
    }

    fun getTrueMinibossBody(entity: Entity): Entity {
        if (entity is ArmorStand) {
            val level = mc.level ?: return entity
            val checkArea = AABB(
                entity.x - 1.5, entity.y - 3.0, entity.z - 1.5,
                entity.x + 1.5, entity.y + 1.0, entity.z + 1.5
            )
            val body = level.getEntities(entity, checkArea).find {
                it is LivingEntity && it !is ArmorStand && it != mc.player && !isTeammate(it)
            }
            if (body != null) return body
        }
        return entity
    }

    fun matches(
        entity: Entity,
        category: MobCategory,
        nameFilter: String? = null,
        skullTexture: String? = null
    ): Boolean {
        // Exclude self and dungeon teammates
        if (entity == mc.player || isTeammate(entity)) return false

        val allNames = getAllEntityNames(entity)
        val skull = getEntitySkullTexture(entity)

        // 1. Check custom skull match if specified
        if (!skullTexture.isNullOrBlank()) {
            if (skull == null || !skull.contains(skullTexture, ignoreCase = true)) {
                return false
            }
        }

        // 2. Check custom name filter if specified
        if (!nameFilter.isNullOrBlank()) {
            val nameMatch = allNames.any { it.contains(nameFilter, ignoreCase = true) }
            if (!nameMatch) return false
        }

        // 3. Check Mob Category
        return when (category) {
            MobCategory.ANY -> true

            MobCategory.BLOOD_MOB -> {
                if (!LocationUtils.inDungeon) return false
                (skull != null && skull in BloodCamp.mobSkulls) ||
                        (entity is LivingEntity && entity !is ArmorStand && dev.noemt.client.features.blood.AutoBloodCamp.isInsideBloodRoom(entity.position())) ||
                        allNames.any { name ->
                            name.contains("Revived Undead", ignoreCase = true) ||
                                    name.contains("Tear", ignoreCase = true) ||
                                    name.contains("Psycho", ignoreCase = true) ||
                                    name.contains("Vader", ignoreCase = true) ||
                                    name.contains("Wandering Soul", ignoreCase = true) ||
                                    name.contains("Cannibal", ignoreCase = true) ||
                                    name.contains("Mute", ignoreCase = true) ||
                                    name.contains("Ooze", ignoreCase = true) ||
                                    name.contains("Parasite", ignoreCase = true) ||
                                    name.contains("Putrid", ignoreCase = true) ||
                                    name.contains("Freak", ignoreCase = true) ||
                                    name.contains("Leech", ignoreCase = true) ||
                                    name.contains("Flamethrower", ignoreCase = true)
                        }
            }

            MobCategory.WATCHER -> {
                if (!LocationUtils.inDungeon) return false
                (skull != null && skull in BloodCamp.watcherSkulls) ||
                        allNames.any { it.contains("The Watcher", ignoreCase = true) }
            }

            MobCategory.MINIBOSS -> {
                if (!LocationUtils.inDungeon) return false
                MINIBOSS_NAMES.any { mb ->
                    allNames.any { name ->
                        name.contains(mb, ignoreCase = true)
                    }
                }
            }

            MobCategory.BOSS -> {
                BOSS_NAMES.any { boss -> allNames.any { it.contains(boss, ignoreCase = true) } }
            }

            MobCategory.SLAYER -> {
                SLAYER_NAMES.any { slayer -> allNames.any { it.contains(slayer, ignoreCase = true) } }
            }

            MobCategory.CUSTOM_NAME -> {
                if (nameFilter.isNullOrBlank()) true
                else allNames.any { it.contains(nameFilter, ignoreCase = true) }
            }

            MobCategory.CUSTOM_SKULL -> {
                if (skullTexture.isNullOrBlank()) skull != null
                else skull != null && skull.contains(skullTexture, ignoreCase = true)
            }
        }
    }

    fun getEntitySkullTexture(entity: Entity): String? {
        val item = when (entity) {
            is ArmorStand -> entity.getItemBySlot(EquipmentSlot.HEAD)
            is LivingEntity -> entity.getItemBySlot(EquipmentSlot.HEAD)
            else -> null
        } ?: return null

        if (item.isEmpty) return null
        return ItemUtils.getSkullTexture(item)
    }

    fun getAllEntityNames(entity: Entity): List<String> {
        val names = mutableListOf<String>()

        entity.name.string.takeIf { it.isNotBlank() }?.let { names.add(it) }
        entity.customName?.string?.takeIf { it.isNotBlank() }?.let { names.add(it) }

        // Find nearby floating ArmorStands (Hypixel Skyblock name tags above mob)
        val level = mc.level
        if (level != null) {
            val checkArea = AABB(
                entity.x - 1.5, entity.y - 1.0, entity.z - 1.5,
                entity.x + 1.5, entity.y + 3.5, entity.z + 1.5
            )
            for (near in level.getEntities(entity, checkArea)) {
                if (near is ArmorStand) {
                    near.name.string.takeIf { it.isNotBlank() }?.let { names.add(it) }
                    near.customName?.string?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
            }
        }

        return names
    }

    fun getNametagArmorStands(entity: Entity): List<ArmorStand> {
        val level = mc.level ?: return emptyList()
        val checkArea = AABB(
            entity.x - 1.5, entity.y - 1.0, entity.z - 1.5,
            entity.x + 1.5, entity.y + 3.5, entity.z + 1.5
        )
        return level.getEntities(entity, checkArea).filterIsInstance<ArmorStand>()
    }

    private val healthRegex = Regex("""([\d,.]+[kKmMbBtT]?)/([\d,.]+[kKmMbBtT]?)[❤|❤]""")

    fun getEntityHealth(entity: Entity): Pair<Double, Double>? {
        for (name in getAllEntityNames(entity)) {
            val clean = name.replace("§.", "").trim()
            val match = healthRegex.find(clean)
            if (match != null) {
                val current = parseShortenedNumber(match.groupValues[1]) ?: continue
                val max = parseShortenedNumber(match.groupValues[2]) ?: continue
                return current to max
            }
        }
        return null
    }

    private fun parseShortenedNumber(str: String): Double? {
        val clean = str.replace(",", "").trim()
        val multiplier = when (clean.lastOrNull()?.lowercaseChar()) {
            'k' -> 1_000.0
            'm' -> 1_000_000.0
            'b' -> 1_000_000_000.0
            't' -> 1_000_000_000_000.0
            else -> 1.0
        }
        val numberPart = if (multiplier != 1.0) clean.dropLast(1) else clean
        return numberPart.toDoubleOrNull()?.let { it * multiplier }
    }

    fun isMiniboss(entity: Entity): Boolean = matches(entity, MobCategory.MINIBOSS)
    fun isBoss(entity: Entity): Boolean = matches(entity, MobCategory.BOSS)
    fun isSlayer(entity: Entity): Boolean = matches(entity, MobCategory.SLAYER)

    fun getAimedEntity(maxDistance: Double = 20.0): Entity? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null

        val eyePos = player.eyePosition
        val lookVec = player.lookAngle
        val reachVec = eyePos.add(lookVec.scale(maxDistance))
        val box = player.boundingBox.expandTowards(lookVec.scale(maxDistance)).inflate(2.0)

        var closestEntity: Entity? = null
        var closestDist = maxDistance * maxDistance

        for (entity in level.getEntities(player, box)) {
            if (entity == player) continue
            if (entity is Player && isTeammate(entity)) continue

            val hitBox = entity.boundingBox.inflate(0.5)
            val clip = hitBox.clip(eyePos, reachVec)
            if (clip.isPresent) {
                val dist = eyePos.distanceToSqr(clip.get())
                if (dist < closestDist) {
                    closestDist = dist
                    closestEntity = entity
                }
            }
        }

        return closestEntity ?: mc.crosshairPickEntity?.takeUnless { it == player || (it is Player && isTeammate(it)) }
    }
}
