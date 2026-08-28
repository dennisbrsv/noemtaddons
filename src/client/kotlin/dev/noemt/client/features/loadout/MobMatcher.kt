package dev.noemt.client.features.loadout

import dev.noemt.client.features.blood.BloodCamp
import dev.noemt.client.utils.ItemUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

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

    fun matches(
        entity: Entity,
        category: MobCategory,
        nameFilter: String? = null,
        skullTexture: String? = null
    ): Boolean {
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
                (skull != null && skull in BloodCamp.mobSkulls) ||
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
                (skull != null && skull in BloodCamp.watcherSkulls) ||
                        allNames.any { it.contains("The Watcher", ignoreCase = true) }
            }

            MobCategory.MINIBOSS -> {
                MINIBOSS_NAMES.any { mb -> allNames.any { it.contains(mb, ignoreCase = true) } }
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

        // Find nearby floating ArmorStands (Hypixel Skyblock name tags)
        val level = mc.level
        if (level != null) {
            val checkArea = AABB(
                entity.x - 1.0, entity.y - 0.5, entity.z - 1.0,
                entity.x + 1.0, entity.y + 2.8, entity.z + 1.0
            )
            for (near in level.getEntities(entity, checkArea)) {
                if (near is ArmorStand && near.hasCustomName()) {
                    near.customName?.string?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
            }
        }

        return names
    }

    fun getAimedEntity(maxDistance: Double = 20.0): Entity? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null

        val eyePos = player.eyePosition
        val lookVec = player.lookAngle
        val reachVec = eyePos.add(lookVec.scale(maxDistance))
        val box = player.boundingBox.expandTowards(lookVec.scale(maxDistance)).inflate(1.5)

        var closestEntity: Entity? = null
        var closestDist = maxDistance * maxDistance

        for (entity in level.getEntities(player, box)) {
            if (entity == player) continue
            // Allow targeting armor stands, living entities, mobs
            val hitBox = entity.boundingBox.inflate(0.35)
            val clip = hitBox.clip(eyePos, reachVec)
            if (clip.isPresent) {
                val dist = eyePos.distanceToSqr(clip.get())
                if (dist < closestDist) {
                    closestDist = dist
                    closestEntity = entity
                }
            }
        }

        return closestEntity ?: mc.crosshairPickEntity
    }
}
