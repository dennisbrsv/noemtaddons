package dev.noemt.client.features.gambling.dungeons

import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import dev.noemt.client.features.gambling.chest.DungeonChestType
import dev.noemt.client.utils.ItemUtils.lore
import dev.noemt.client.utils.ItemUtils.skyblockId
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

data class DungeonLootEntry(val id: String, val weight: Double)

object DungeonItemRegistry {

    private val chestLoot = mutableMapOf<String, Map<String, List<DungeonLootEntry>>>()
    private val itemCache = mutableMapOf<String, ItemStack>()

    init {
        loadData()
    }

    private fun loadData() {
        try {
            val stream = DungeonItemRegistry::class.java.getResourceAsStream("/assets/noemtaddons/data/dungeon_chests.json")
            if (stream != null) {
                val reader = InputStreamReader(stream)
                val jsonObject = JsonParser.parseReader(reader).asJsonObject

                for ((floorKey, chestsObj) in jsonObject.entrySet()) {
                    val floorChests = mutableMapOf<String, List<DungeonLootEntry>>()
                    for ((chestKey, itemsArray) in chestsObj.asJsonObject.entrySet()) {
                        val list = mutableListOf<DungeonLootEntry>()
                        for (element in itemsArray.asJsonArray) {
                            val entryObj = element.asJsonObject
                            val id = entryObj.get("id").asString
                            val weight = entryObj.get("weight").asDouble
                            list.add(DungeonLootEntry(id, weight))
                        }
                        floorChests[chestKey.lowercase()] = list
                    }
                    chestLoot[floorKey.uppercase()] = floorChests
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getChestPool(floor: DungeonFloor, chest: DungeonChestType): List<DungeonLootEntry> {
        val floorKey = floor.name
        val chestKey = chest.name.lowercase()
        val direct = chestLoot[floorKey]?.get(chestKey)
        if (!direct.isNullOrEmpty()) return direct

        // Fallback to F7/M7 if high floor
        val fallbackFloor = if (floor.isMasterMode) "M7" else "F7"
        val fallback = chestLoot[fallbackFloor]?.get(chestKey)
        if (!fallback.isNullOrEmpty()) return fallback

        // Default pool with high tier drops
        return listOf(
            DungeonLootEntry("item:necron_handle", 1.0),
            DungeonLootEntry("item:shadow_warp_scroll", 2.0),
            DungeonLootEntry("item:wither_shield_scroll", 2.0),
            DungeonLootEntry("item:implosion_scroll", 2.0),
            DungeonLootEntry("item:recombobulator_3000", 8.0),
            DungeonLootEntry("item:wither_chestplate", 5.0),
            DungeonLootEntry("item:wither_catalyst", 10.0),
            DungeonLootEntry("item:fuming_potato_book", 15.0),
            DungeonLootEntry("enchantment:ultimate_legion:1", 6.0),
            DungeonLootEntry("enchantment:ultimate_soul_eater:1", 8.0),
            DungeonLootEntry("item:hot_potato_book", 25.0),
        )
    }

    fun getRandomItem(floor: DungeonFloor, chest: DungeonChestType): DungeonLootEntry {
        val pool = getChestPool(floor, chest)
        if (pool.isEmpty()) return DungeonLootEntry("item:recombobulator_3000", 1.0)

        val totalWeight = pool.sumOf { it.weight }
        var randomWeight = ThreadLocalRandom.current().nextDouble() * totalWeight

        for (entry in pool) {
            randomWeight -= entry.weight
            if (randomWeight <= 0) {
                return entry
            }
        }
        return pool.last()
    }

    fun getItemStack(id: String): ItemStack {
        return itemCache.getOrPut(id) {
            createItemStack(id)
        }.copy()
    }

    fun getDropDisplayName(stack: ItemStack): String {
        if (stack.isEmpty) return ""
        if (stack.`is`(Items.ENCHANTED_BOOK)) {
            val loreLines = stack.lore
            val firstLore = loreLines.firstOrNull { line ->
                val clean = line.replace("§[0-9a-zA-Z]".toRegex(), "").trim()
                clean.isNotEmpty() && !clean.startsWith("Enchanted Book", ignoreCase = true)
            }
            if (!firstLore.isNullOrBlank()) {
                return firstLore.trim()
            }

            val customName = stack.hoverName.string
            val cleanCustom = customName.replace("§[0-9a-zA-Z]".toRegex(), "").trim()
            if (cleanCustom.isNotEmpty() && !cleanCustom.startsWith("Enchanted Book", ignoreCase = true)) {
                return customName
            }
        }
        return stack.hoverName.string
    }

    fun normalizeDropItem(stack: ItemStack): ItemStack {
        if (stack.isEmpty) return stack
        if (stack.`is`(Items.ENCHANTED_BOOK)) {
            val clone = stack.copy()
            val enchantName = getDropDisplayName(clone)
            if (enchantName.isNotBlank() && !enchantName.startsWith("Enchanted Book", ignoreCase = true)) {
                clone.set(DataComponents.CUSTOM_NAME, Component.literal(enchantName))
            }
            return clone
        }
        return stack
    }

    fun findBestWinner(items: List<ItemStack>): ItemStack? {
        val candidates = items.filter { stack ->
            if (stack.isEmpty) return@filter false
            val name = stack.hoverName.string
            val isIgnored = name.contains("Reward Chest", ignoreCase = true) ||
                    name.contains("Open Chest", ignoreCase = true) ||
                    name.contains("Go Back", ignoreCase = true) ||
                    name.contains("Close", ignoreCase = true) ||
                    name.contains("Glass Pane", ignoreCase = true) ||
                    stack.`is`(Items.GRAY_STAINED_GLASS_PANE) ||
                    stack.`is`(Items.BLACK_STAINED_GLASS_PANE) ||
                    stack.`is`(Items.WHITE_STAINED_GLASS_PANE) ||
                    stack.`is`(Items.ARROW) ||
                    stack.`is`(Items.BARRIER)
            !isIgnored
        }

        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { getItemValue(it) } ?: return null
        return normalizeDropItem(best)
    }

    fun createItemFromDropName(name: String): ItemStack {
        val clean = name.replace("§[0-9a-zA-Z]".toRegex(), "").replace("’", "'").trim()
        val lower = clean.lowercase()

        // 1. Check for Enchanted Book (e.g. "Enchanted Book (Combo I)", "Combo I", "Ultimate Legion V")
        val bookMatch = Regex("""(?:Enchanted\s+Book\s*\()?\s*([A-Za-z\s]+?)\s+([IVXLCDM\d]+)\s*\)?""", RegexOption.IGNORE_CASE).find(clean)
        if (bookMatch != null || lower.contains("enchanted book") || lower.contains("legion") || lower.contains("soul eater") || lower.contains("overload") || lower.contains("combo") || lower.contains("rejuvenate")) {
            val enchantWord = bookMatch?.groups?.get(1)?.value?.trim() ?: clean.removePrefix("Enchanted Book").replace("(", "").replace(")", "").trim()
            val levelStr = bookMatch?.groups?.get(2)?.value?.trim() ?: "1"
            val level = when (levelStr.uppercase()) {
                "I", "1" -> 1
                "II", "2" -> 2
                "III", "3" -> 3
                "IV", "4" -> 4
                "V", "5" -> 5
                "VI", "6" -> 6
                "VII", "7" -> 7
                else -> 1
            }

            val enchantId = enchantWord.lowercase().replace(" ", "_")
            val fullId = if (enchantId.startsWith("ultimate_")) enchantId else when (enchantId) {
                "legion", "soul_eater", "one_for_all", "combo", "bank", "no_pain_no_gain", "wisdom", "rend", "fatal_tempo", "inferno", "habanero_tactics" -> "ultimate_$enchantId"
                else -> enchantId
            }

            return createEnchantedBook(fullId, level)
        }

        // 2. Check for known items
        val id = nameToId(clean)
        return getItemStack(id)
    }

    fun extractCroesusDrops(items: List<ItemStack>): List<ItemStack> {
        val drops = mutableListOf<ItemStack>()
        for (item in items) {
            if (item.isEmpty) continue
            val lore = item.lore
            var inRewardsSection = false

            for (line in lore) {
                val clean = line.replace("§[0-9a-zA-Z]".toRegex(), "").replace("’", "'").trim()
                if (clean.equals("Rewards:", ignoreCase = true) ||
                    clean.equals("Contents:", ignoreCase = true) ||
                    clean.equals("Contents", ignoreCase = true) ||
                    clean.equals("Rewards", ignoreCase = true)
                ) {
                    inRewardsSection = true
                    continue
                }
                if (inRewardsSection && (
                    clean.startsWith("Cost", ignoreCase = true) ||
                    clean.startsWith("NOTE:", ignoreCase = true) ||
                    clean.startsWith("Click to open", ignoreCase = true) ||
                    clean.startsWith("[Skyblocker]", ignoreCase = true) ||
                    clean.startsWith("Require", ignoreCase = true) ||
                    clean.isEmpty()
                )) {
                    inRewardsSection = false
                    continue
                }

                if (inRewardsSection || clean.startsWith("•") || clean.startsWith("-") || clean.startsWith("*") || clean.startsWith("+")) {
                    val dropName = clean
                        .removePrefix("•")
                        .removePrefix("-")
                        .removePrefix("*")
                        .removePrefix("+")
                        .trim()

                    if (dropName.isNotBlank() && !dropName.contains("Coins", ignoreCase = true)) {
                        val rawName = dropName.replace(Regex("""\s+x\d+$"""), "").trim()
                        val stack = createItemFromDropName(rawName)
                        if (!stack.isEmpty) {
                            drops.add(stack)
                        }
                    }
                }
            }
        }
        return drops
    }

    fun getItemValue(stack: ItemStack): Long {
        if (stack.isEmpty) return 0L

        // 1. Check if Enchanted Book
        if (stack.`is`(Items.ENCHANTED_BOOK)) {
            val enchantName = getDropDisplayName(stack).lowercase()
            for (line in listOf(enchantName) + stack.lore.map { it.lowercase() }) {
                val clean = line.replace("§[0-9a-zA-Z]".toRegex(), "").trim()
                when {
                    clean.contains("fatal tempo") -> return 40_000_000L
                    clean.contains("inferno") -> return 15_000_000L
                    clean.contains("legion") -> return 6_500_000L
                    clean.contains("soul eater") -> return 4_500_000L
                    clean.contains("one for all") -> return 3_500_000L
                    clean.contains("overload") -> return 2_500_000L
                    clean.contains("combo") -> return 1_500_000L
                    clean.contains("wisdom") -> return 1_200_000L
                    clean.contains("bank") -> return 800_000L
                    clean.contains("no pain no gain") -> return 600_000L
                    clean.contains("fuming") -> return 1_800_000L
                    clean.contains("rejuvenate") -> return 300_000L
                    clean.contains("infinite quiver") -> return 200_000L
                    clean.contains("feather falling") -> return 150_000L
                }
            }
            return 100_000L
        }

        // 2. Check SkyBlock ID
        val sbId = stack.skyblockId
        if (sbId.isNotBlank()) {
            val v = getItemValue(sbId)
            if (v > 0L) return v
        }

        // 3. Check Name
        val name = stack.hoverName.string
        val nameId = nameToId(name)
        val nameVal = getItemValue(nameId)
        if (nameVal > 0L) return nameVal

        return 50_000L
    }

    fun getItemValue(id: String): Long {
        val clean = id.lowercase()
        return when {
            clean.contains("necron_handle") -> 1_050_000_000L
            clean.contains("shadow_warp") || clean.contains("wither_shield") || clean.contains("implosion") -> 380_000_000L
            clean.contains("dark_claymore") -> 220_000_000L
            clean.contains("giants_sword") -> 170_000_000L
            clean.contains("fifth_master_star") -> 95_000_000L
            clean.contains("fourth_master_star") -> 55_000_000L
            clean.contains("third_master_star") -> 35_000_000L
            clean.contains("shadow_fury") -> 45_000_000L
            clean.contains("second_master_star") -> 22_000_000L
            clean.contains("first_master_star") -> 12_000_000L
            clean.contains("shadow_assassin_chestplate") -> 28_000_000L
            clean.contains("wither_chestplate") -> 24_000_000L
            clean.contains("precursor_eye") -> 25_000_000L
            clean.contains("necromancer_lord_chestplate") -> 14_000_000L
            clean.contains("recombobulator") -> 10_500_000L
            clean.contains("livid_dagger") -> 11_000_000L
            clean.contains("last_breath") -> 9_000_000L
            clean.contains("spirit_sword") || clean.contains("item_spirit_bow") -> 7_000_000L
            clean.contains("ultimate_fatal_tempo") -> 40_000_000L
            clean.contains("ultimate_inferno") -> 15_000_000L
            clean.contains("ultimate_legion") -> 6_500_000L
            clean.contains("ultimate_soul_eater") -> 4_500_000L
            clean.contains("ultimate_one_for_all") -> 3_500_000L
            clean.contains("ultimate_combo") -> 1_500_000L
            clean.contains("ultimate_wisdom") -> 1_200_000L
            clean.contains("ultimate_bank") -> 800_000L
            clean.contains("ultimate_no_pain_no_gain") -> 600_000L
            clean.contains("overload") -> 2_500_000L
            clean.contains("bonzo_staff") || clean.contains("bonzo_mask") -> 3_000_000L
            clean.contains("fuming_potato_book") -> 1_800_000L
            clean.contains("hot_potato_book") -> 350_000L
            clean.contains("wither_catalyst") || clean.contains("wither_blood") -> 1_200_000L
            clean.contains("spirit_wing") -> 2_500_000L
            clean.contains("spirit_bone") -> 800_000L
            clean.contains("rejuvenate") -> 300_000L
            clean.contains("infinite_quiver") -> 200_000L
            clean.contains("feather_falling") -> 150_000L
            clean.contains("master_skull") -> 5_000_000L
            clean.contains("essence") -> 5_000L
            clean.contains("coin") -> 1_000L
            else -> 20_000L
        }
    }

    private fun nameToId(name: String): String {
        val clean = name.replace("§[0-9a-fk-or]".toRegex(), "").replace("’", "'").trim().lowercase()
        return when {
            clean.contains("necron's handle") -> "item:necron_handle"
            clean.contains("shadow warp") -> "item:shadow_warp_scroll"
            clean.contains("wither shield") -> "item:wither_shield_scroll"
            clean.contains("implosion") -> "item:implosion_scroll"
            clean.contains("giant's sword") -> "item:giants_sword"
            clean.contains("dark claymore") -> "item:dark_claymore"
            clean.contains("shadow fury") -> "item:shadow_fury"
            clean.contains("livid dagger") -> "item:livid_dagger"
            clean.contains("recombobulator") -> "item:recombobulator_3000"
            clean.contains("fifth master star") -> "item:fifth_master_star"
            clean.contains("fourth master star") -> "item:fourth_master_star"
            clean.contains("third master star") -> "item:third_master_star"
            clean.contains("second master star") -> "item:second_master_star"
            clean.contains("first master star") -> "item:first_master_star"
            clean.contains("fuming potato book") -> "item:fuming_potato_book"
            clean.contains("hot potato book") -> "item:hot_potato_book"
            clean.contains("wither chestplate") -> "item:wither_chestplate"
            clean.contains("shadow assassin chestplate") -> "item:shadow_assassin_chestplate"
            clean.contains("bonzo's staff") -> "item:bonzo_staff"
            clean.contains("bonzo's mask") -> "item:bonzo_mask"
            clean.contains("spirit bow") -> "item:item_spirit_bow"
            clean.contains("spirit sword") -> "item:spirit_sword"
            clean.contains("spirit wing") -> "item:spirit_wing"
            clean.contains("spirit bone") -> "item:spirit_bone"
            clean.contains("precursor eye") -> "item:precursor_eye"
            clean.contains("legion") -> "enchantment:ultimate_legion:1"
            clean.contains("soul eater") -> "enchantment:ultimate_soul_eater:1"
            clean.contains("one for all") -> "enchantment:ultimate_one_for_all:1"
            clean.contains("overload") -> "enchantment:overload:1"
            clean.contains("combo") -> "enchantment:ultimate_combo:1"
            clean.contains("bank") -> "enchantment:ultimate_bank:1"
            clean.contains("wisdom") -> "enchantment:ultimate_wisdom:1"
            clean.contains("rejuvenate") -> "enchantment:rejuvenate:1"
            clean.contains("infinite quiver") -> "enchantment:infinite_quiver:1"
            clean.contains("feather falling") -> "enchantment:feather_falling:1"
            clean.contains("essence") -> "item:essence"
            else -> "item:$clean"
        }
    }

    private fun createItemStack(id: String): ItemStack {
        val parts = id.split(":")
        val type = parts.getOrNull(0) ?: "item"
        val nameId = parts.getOrNull(1) ?: id
        val extraParam = parts.getOrNull(2)

        return when (type) {
            "enchantment" -> createEnchantedBook(nameId, extraParam?.toIntOrNull() ?: 1)
            "pet" -> createPetItem(nameId, extraParam ?: "LEGENDARY")
            else -> createCustomItem(nameId)
        }
    }

    private fun createEnchantedBook(enchantId: String, level: Int): ItemStack {
        val stack = ItemStack(Items.ENCHANTED_BOOK)
        val romanLevel = when (level) {
            1 -> "I"
            2 -> "II"
            3 -> "III"
            4 -> "IV"
            5 -> "V"
            6 -> "VI"
            7 -> "VII"
            else -> "$level"
        }

        val isUltimate = enchantId.startsWith("ultimate_")
        val cleanEnchantName = enchantId.removePrefix("ultimate_")
            .split("_")
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

        val displayName = if (isUltimate) {
            "§d§l$cleanEnchantName $romanLevel"
        } else {
            "§9$cleanEnchantName $romanLevel"
        }

        val lore = mutableListOf(
            displayName,
            "",
            "§7Use in an Anvil to apply to a valid item!",
            "",
            if (isUltimate) "§d§lULTIMATE ENCHANTMENT" else "§9§lRARE ENCHANTMENT"
        )

        stack.set(DataComponents.CUSTOM_NAME, Component.literal(displayName))
        stack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)

        val customData = net.minecraft.nbt.CompoundTag()
        customData.putString("id", "ENCHANTED_BOOK")
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData))

        return stack
    }

    private fun createPetItem(petId: String, rarity: String): ItemStack {
        val stack = ItemStack(Items.PLAYER_HEAD)
        val cleanPetName = petId.split("_").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
        val color = if (rarity == "LEGENDARY") "§6" else "§5"

        stack.set(DataComponents.CUSTOM_NAME, Component.literal("$color[Lvl 1] $cleanPetName"))
        val lore = listOf(
            "§8Dungeon Pet",
            "",
            "§7Grants powerful dungeon bonuses!",
            "",
            "$color§l$rarity PET"
        )
        stack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
        setSkullTexture(stack, SKULL_TEXTURES["spirit_pet"] ?: SKULL_TEXTURES["spirit"] ?: DEFAULT_SKULL)

        val customData = net.minecraft.nbt.CompoundTag()
        customData.putString("id", "${petId.uppercase()}_PET")
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData))

        return stack
    }

    private fun createCustomItem(nameId: String): ItemStack {
        val (baseItem, name, rarityColor, rarityName, isGlint, skullKey) = getItemMeta(nameId)
        val stack = ItemStack(baseItem)

        stack.set(DataComponents.CUSTOM_NAME, Component.literal("$rarityColor$name"))
        if (isGlint) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        }

        if (baseItem == Items.PLAYER_HEAD) {
            val texture = SKULL_TEXTURES[skullKey ?: nameId] ?: DEFAULT_SKULL
            setSkullTexture(stack, texture)
        }

        val lore = mutableListOf(
            "§8Dungeon Reward",
            "",
            "§7Found inside Catacombs Reward Chests.",
            "",
            "$rarityColor§l$rarityName"
        )
        stack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))

        val customData = net.minecraft.nbt.CompoundTag()
        customData.putString("id", nameId.uppercase())
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData))

        return stack
    }

    private data class ItemMeta(
        val baseItem: Item,
        val displayName: String,
        val rarityColor: String,
        val rarityName: String,
        val glint: Boolean = false,
        val skullKey: String? = null
    )

    private fun getItemMeta(id: String): ItemMeta {
        return when (id) {
            "necron_handle" -> ItemMeta(Items.PLAYER_HEAD, "Necron's Handle", "§d", "DIVINE / NECRON DROP", true, "necron_handle")
            "shadow_warp_scroll" -> ItemMeta(Items.PLAYER_HEAD, "Shadow Warp Scroll", "§5", "EPIC DUNGEON SCROLL", true, "wither_scroll")
            "wither_shield_scroll" -> ItemMeta(Items.PLAYER_HEAD, "Wither Shield Scroll", "§5", "EPIC DUNGEON SCROLL", true, "wither_scroll")
            "implosion_scroll" -> ItemMeta(Items.PLAYER_HEAD, "Implosion Scroll", "§5", "EPIC DUNGEON SCROLL", true, "wither_scroll")
            "giants_sword" -> ItemMeta(Items.IRON_SWORD, "Giant's Sword", "§6", "LEGENDARY DUNGEON SWORD", true)
            "dark_claymore" -> ItemMeta(Items.STONE_SWORD, "Dark Claymore", "§6", "LEGENDARY MASTER MODE SWORD", true)
            "shadow_fury" -> ItemMeta(Items.DIAMOND_SWORD, "Shadow Fury", "§6", "LEGENDARY SWORD", true)
            "livid_dagger" -> ItemMeta(Items.IRON_SWORD, "Livid Dagger", "§6", "LEGENDARY SWORD", true)
            "bonzo_staff" -> ItemMeta(Items.BLAZE_ROD, "Bonzo's Staff", "§9", "RARE DUNGEON WEAPON", true)
            "bonzo_mask" -> ItemMeta(Items.PLAYER_HEAD, "Bonzo's Mask", "§9", "RARE DUNGEON HELMET", true, "bonzo_mask")
            "recombobulator_3000" -> ItemMeta(Items.PLAYER_HEAD, "Recombobulator 3000", "§6", "LEGENDARY ACCESSORY UPGRADE", true, "recombobulator")
            "first_master_star" -> ItemMeta(Items.NETHER_STAR, "First Master Star", "§6", "SPECIAL MASTER STAR", true)
            "second_master_star" -> ItemMeta(Items.NETHER_STAR, "Second Master Star", "§6", "SPECIAL MASTER STAR", true)
            "third_master_star" -> ItemMeta(Items.NETHER_STAR, "Third Master Star", "§6", "SPECIAL MASTER STAR", true)
            "fourth_master_star" -> ItemMeta(Items.NETHER_STAR, "Fourth Master Star", "§6", "SPECIAL MASTER STAR", true)
            "fifth_master_star" -> ItemMeta(Items.NETHER_STAR, "Fifth Master Star", "§d", "MYTHIC MASTER STAR", true)
            "fuming_potato_book" -> ItemMeta(Items.BOOK, "Fuming Potato Book", "§5", "EPIC ITEM UPGRADE", true)
            "hot_potato_book" -> ItemMeta(Items.BOOK, "Hot Potato Book", "§a", "COMMON ITEM UPGRADE", false)
            "precursor_eye" -> ItemMeta(Items.PLAYER_HEAD, "Precursor Eye", "§6", "LEGENDARY HELMET", true, "precursor_eye")
            "precursor_gear" -> ItemMeta(Items.PLAYER_HEAD, "Precursor Gear", "§5", "EPIC DUNGEON ITEM", false, "precursor_gear")
            "sadan_brooch" -> ItemMeta(Items.PLAYER_HEAD, "Sadan's Brooch", "§6", "LEGENDARY REFORGE STONE", true, "sadan_brooch")
            "necromancer_brooch" -> ItemMeta(Items.PLAYER_HEAD, "Necromancer's Brooch", "§5", "EPIC REFORGE STONE", false, "necromancer_brooch")
            "dark_orb" -> ItemMeta(Items.PLAYER_HEAD, "Dark Orb", "§5", "EPIC REFORGE STONE", false, "dark_orb")
            "wither_blood" -> ItemMeta(Items.PLAYER_HEAD, "Wither Blood", "§6", "LEGENDARY REFORGE STONE", true, "wither_blood")
            "wither_catalyst" -> ItemMeta(Items.PLAYER_HEAD, "Wither Catalyst", "§5", "EPIC CRAFTING MATERIAL", true, "wither_catalyst")
            "wither_chestplate" -> ItemMeta(Items.LEATHER_CHESTPLATE, "Wither Chestplate", "§6", "LEGENDARY DUNGEON CHESTPLATE", true)
            "wither_leggings" -> ItemMeta(Items.LEATHER_LEGGINGS, "Wither Leggings", "§6", "LEGENDARY DUNGEON LEGGINGS", true)
            "wither_boots" -> ItemMeta(Items.LEATHER_BOOTS, "Wither Boots", "§6", "LEGENDARY DUNGEON BOOTS", true)
            "wither_helmet" -> ItemMeta(Items.PLAYER_HEAD, "Wither Helmet", "§6", "LEGENDARY DUNGEON HELMET", true, "wither_helmet")
            "wither_cloak" -> ItemMeta(Items.LEATHER_CHESTPLATE, "Wither Cloak Sword", "§5", "EPIC SWORD", true)
            "shadow_assassin_chestplate" -> ItemMeta(Items.LEATHER_CHESTPLATE, "Shadow Assassin Chestplate", "§5", "EPIC DUNGEON CHESTPLATE", true)
            "shadow_assassin_leggings" -> ItemMeta(Items.LEATHER_LEGGINGS, "Shadow Assassin Leggings", "§5", "EPIC DUNGEON LEGGINGS", true)
            "shadow_assassin_boots" -> ItemMeta(Items.LEATHER_BOOTS, "Shadow Assassin Boots", "§5", "EPIC DUNGEON BOOTS", true)
            "shadow_assassin_helmet" -> ItemMeta(Items.PLAYER_HEAD, "Shadow Assassin Helmet", "§5", "EPIC DUNGEON HELMET", true, "shadow_assassin_helmet")
            "shadow_assassin_cloak" -> ItemMeta(Items.PLAYER_HEAD, "Shadow Assassin Cloak", "§5", "EPIC ACCESSORY", true, "shadow_assassin_cloak")
            "necromancer_lord_chestplate" -> ItemMeta(Items.DIAMOND_CHESTPLATE, "Necromancer Lord Chestplate", "§6", "LEGENDARY CHESTPLATE", true)
            "necromancer_lord_leggings" -> ItemMeta(Items.DIAMOND_LEGGINGS, "Necromancer Lord Leggings", "§6", "LEGENDARY LEGGINGS", true)
            "necromancer_lord_boots" -> ItemMeta(Items.DIAMOND_BOOTS, "Necromancer Lord Boots", "§6", "LEGENDARY BOOTS", true)
            "necromancer_lord_helmet" -> ItemMeta(Items.PLAYER_HEAD, "Necromancer Lord Helmet", "§6", "LEGENDARY HELMET", true, "necromancer_lord_helmet")
            "necromancer_sword" -> ItemMeta(Items.DIAMOND_SWORD, "Necromancer Sword", "§6", "LEGENDARY SWORD", true)
            "item_spirit_bow" -> ItemMeta(Items.BOW, "Spirit Bow", "§6", "LEGENDARY DUNGEON BOW", true)
            "spirit_sword" -> ItemMeta(Items.DIAMOND_SWORD, "Spirit Sword", "§6", "LEGENDARY DUNGEON SWORD", true)
            "spirit_wing" -> ItemMeta(Items.FEATHER, "Spirit Wing", "§5", "EPIC CRAFTING MATERIAL", true)
            "spirit_bone" -> ItemMeta(Items.BONE, "Spirit Bone", "§9", "RARE CRAFTING MATERIAL", false)
            "spirit_decoy" -> ItemMeta(Items.PLAYER_HEAD, "Spirit Decoy", "§9", "RARE ITEM", false, "spirit_decoy")
            "last_breath" -> ItemMeta(Items.BOW, "Last Breath", "§6", "LEGENDARY BOW", true)
            "adaptive_chestplate" -> ItemMeta(Items.IRON_CHESTPLATE, "Adaptive Chestplate", "§5", "EPIC DUNGEON CHESTPLATE", true)
            "adaptive_leggings" -> ItemMeta(Items.IRON_LEGGINGS, "Adaptive Leggings", "§5", "EPIC DUNGEON LEGGINGS", true)
            "adaptive_boots" -> ItemMeta(Items.IRON_BOOTS, "Adaptive Boots", "§5", "EPIC DUNGEON BOOTS", true)
            "adaptive_helmet" -> ItemMeta(Items.PLAYER_HEAD, "Adaptive Helmet", "§5", "EPIC DUNGEON HELMET", true, "adaptive_helmet")
            "adaptive_belt" -> ItemMeta(Items.PLAYER_HEAD, "Adaptive Belt", "§5", "EPIC ACCESSORY", false, "adaptive_belt")
            "stone_blade" -> ItemMeta(Items.STONE_SWORD, "Stone Blade", "§9", "RARE SWORD", false)
            "scarf_studies" -> ItemMeta(Items.BOOK, "Scarf's Studies", "§9", "RARE ACCESSORY", false)
            "red_scarf" -> ItemMeta(Items.PLAYER_HEAD, "Red Scarf", "§9", "RARE REFORGE STONE", false, "red_scarf")
            "red_nose" -> ItemMeta(Items.PLAYER_HEAD, "Red Nose", "§9", "RARE REFORGE STONE", false, "red_nose")
            "giant_tooth" -> ItemMeta(Items.GHAST_TEAR, "Giant Tooth", "§9", "RARE REFORGE STONE", false)
            "fel_skull" -> ItemMeta(Items.PLAYER_HEAD, "Fel Skull", "§5", "EPIC REFORGE STONE", false, "fel_skull")
            "balloon_snake" -> ItemMeta(Items.PLAYER_HEAD, "Balloon Snake", "§9", "RARE ITEM", false, "balloon_snake")
            "soulweaver_gloves" -> ItemMeta(Items.PLAYER_HEAD, "Soulweaver Gloves", "§5", "EPIC ACCESSORY", false, "soulweaver_gloves")
            "summoning_ring" -> ItemMeta(Items.PLAYER_HEAD, "Summoning Ring", "§5", "EPIC ITEM", true, "summoning_ring")
            "suspicious_vial" -> ItemMeta(Items.POTION, "Suspicious Vial", "§5", "EPIC REFORGE STONE", true)
            "auto_recombobulator" -> ItemMeta(Items.PLAYER_HEAD, "Auto-Recombobulator", "§6", "LEGENDARY ACCESSORY", true, "auto_recombobulator")
            "dye_necron" -> ItemMeta(Items.PURPLE_DYE, "Necron Dye", "§d", "SPECIAL DYE", true)
            "dye_livid" -> ItemMeta(Items.CYAN_DYE, "Livid Dye", "§d", "SPECIAL DYE", true)
            "goldor_the_fish" -> ItemMeta(Items.SALMON, "Goldor the Fish", "§6", "SPECIAL FISH", true)
            "maxor_the_fish" -> ItemMeta(Items.SALMON, "Maxor the Fish", "§6", "SPECIAL FISH", true)
            "storm_the_fish" -> ItemMeta(Items.SALMON, "Storm the Fish", "§6", "SPECIAL FISH", true)
            "master_skull_tier_1" -> ItemMeta(Items.PLAYER_HEAD, "Master Skull - Tier 1", "§9", "RARE ACCESSORY", false, "master_skull")
            "master_skull_tier_2" -> ItemMeta(Items.PLAYER_HEAD, "Master Skull - Tier 2", "§5", "EPIC ACCESSORY", false, "master_skull")
            "master_skull_tier_3" -> ItemMeta(Items.PLAYER_HEAD, "Master Skull - Tier 3", "§6", "LEGENDARY ACCESSORY", true, "master_skull")
            "master_skull_tier_4" -> ItemMeta(Items.PLAYER_HEAD, "Master Skull - Tier 4", "§d", "MYTHIC ACCESSORY", true, "master_skull")
            "master_skull_tier_5" -> ItemMeta(Items.PLAYER_HEAD, "Master Skull - Tier 5", "§d", "DIVINE ACCESSORY", true, "master_skull")
            else -> {
                val clean = id.split("_").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                ItemMeta(Items.PLAYER_HEAD, clean, "§a", "DUNGEON ITEM", false, id)
            }
        }
    }

    private fun setSkullTexture(stack: ItemStack, textureBase64: String) {
        try {
            val profile = GameProfile(UUID.randomUUID(), "DungeonDrop")
            profile.properties.put("textures", Property("textures", textureBase64))
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
        } catch (e: Exception) {}
    }

    private const val DEFAULT_SKULL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmQ2OWUwNmU1ZGFkZmQ4NGU1ZjNkMWMyMTA2M2YyNTUzYjExNDA5ZmBQZTRiYTFiOTMyOTVhNWRlZDE3MzZmZCJ9fX0="

    private val SKULL_TEXTURES = mapOf(
        "necron_handle" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzkxMmVkZGE3N2I0Nzg0MDgyNTkxNjA5NTEwZjFlZTU1NmQ4ZmIxOWRkNzM1MmU2N2IwYjgzNWY0N2E2Zjc1YyJ9fX0=",
        "wither_scroll" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmEzNDY0ZTBhYmNmYTM5MzQxNjg1Y2Q3MjY0NWNjNWQ3MjgzODMzYWFmOWJmYmM4NjQ3ZWE4ZTI4ZjRhY2Y0OSJ9fX0=",
        "recombobulator" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmM4ZWY4ZDM1MWQ0YTkxMmRmMmExNmY5YWU2YWFmZmZmZmFmZTVmZmEwOWQwODczZWI0ZjVkNGY2M2RiOTI5In19fQ==",
        "bonzo_mask" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjkyNDI5ZTU4ODVmNGYyYjViNDdiOGYyMDI1NGQ5OTYxMGMzZTcxZTk5OWRhMDZhNjU5NTNiMzcxNWNiNmI1YSJ9fX0=",
        "precursor_eye" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmI0ODFjZDVjOTQxZWMxYjM3NGNmOTY3NGE5MDVhNjkyY2VjYmFiYmM0ZjVjMTgxMmY0Njk3YjRhMmU5M2U5MSJ9fX0=",
        "wither_catalyst" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjg0ZGFkZjQxNmNkMWUxYmEyYWIxOGJmMTdiMGRjNDg4ZDNjMDUxY2MyYjIzOGJjODk1MjFjYmE5MmRhYWRhMCJ9fX0=",
        "wither_blood" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzkxMmVkZGE3N2I0Nzg0MDgyNTkxNjA5NTEwZjFlZTU1NmQ4ZmIxOWRkNzM1MmU2N2IwYjgzNWY0N2E2Zjc1YyJ9fX0=",
        "sadan_brooch" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTI2ZDhjOTc5MWFhMWEzOGU3ZDZiOTRiNTlhNzMwNTU0YTFhZGNmZGFkNTQyNmUyM2MwMmVjMmJkYTY1Y2VjIn19fQ==",
        "master_skull" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDY0ZGI3NWZiZTZjZWVjZmVkMjgyNDhlNThkZDIzZjU5NDVlYjEyMTkyMmY0NjQ0ZjE1NGE0NTNkNTBiIn19fQ==",
        "spirit" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWIzYTY4NGJjMzkxMWYxZjZhN2NlZTRhMmQ2NmFlNTJkNTI2NTcyYjIxMTAzMWQ4MmM0OTdhMjcxMTJkYWRmIn19fQ==",
        "auto_recombobulator" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGVkMzFmYjcyYzg3MmNmYTc3YWE3OGNmNGU1OTNkZTI4YWMzODk4MWFiNGU5NzkxNzAxMTc0YmFjZWY0NjkifX19",
        "red_scarf" to "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjU3YjMyMjA2NGM5YzI3ZDFhMTZhYzk3OWFjMzEyMjQ0ZjhhYjExYmUzYmYxNmFmYzM1M2U1MjhkYTE0MzZkIn19fQ=="
    )
}
