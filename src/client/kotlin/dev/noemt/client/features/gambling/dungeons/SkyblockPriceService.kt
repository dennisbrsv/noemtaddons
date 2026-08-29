package dev.noemt.client.features.gambling.dungeons

import com.google.gson.JsonParser
import dev.noemt.client.utils.ItemUtils.lore
import dev.noemt.client.utils.ItemUtils.skyblockId
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SkyblockPriceService {
    private const val LOWESTBINS_URL = "https://lb.tricked.dev/lowestbins"
    private const val BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
    private const val ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NoemtAddons-PriceService").apply { isDaemon = true }
    }

    private val bazaarPrices = ConcurrentHashMap<String, Long>()
    private val lowestBinPrices = ConcurrentHashMap<String, Long>()
    val nameToIdMap = ConcurrentHashMap<String, String>()

    private val essenceRegex = Regex("""(?:§[0-9a-fk-or])*([A-Za-z]+)\s+Essence\s*(?:§[0-9a-fk-or])*x(\d+)""", RegexOption.IGNORE_CASE)

    init {
        loadFallbackPrices()

        // Fetch prices in background without blocking
        executor.submit {
            fetchAllPrices()
        }

        executor.scheduleAtFixedRate({
            fetchAllPrices()
        }, 10, 10, TimeUnit.MINUTES)
    }

    fun init() {
        // Trigger initialization
    }

    private fun fetchAllPrices() {
        try {
            updateLowestBins()
        } catch (_: Throwable) {}
        try {
            updateBazaarPrices()
        } catch (_: Throwable) {}
        try {
            updateSkyblockItems()
        } catch (_: Throwable) {}
    }

    private fun updateLowestBins() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(LOWESTBINS_URL))
            .header("User-Agent", "NoemtAddons/1.0")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val jsonObject = JsonParser.parseString(response.body()).asJsonObject
            for ((key, element) in jsonObject.entrySet()) {
                lowestBinPrices[key.uppercase()] = element.asDouble.toLong()
            }
        }
    }

    private fun updateBazaarPrices() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(BAZAAR_URL))
            .header("User-Agent", "NoemtAddons/1.0")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val jsonObject = JsonParser.parseString(response.body()).asJsonObject
            val products = jsonObject.getAsJsonObject("products") ?: return
            for ((key, element) in products.entrySet()) {
                val product = element.asJsonObject
                val productId = product.get("product_id")?.asString ?: key
                val buySummary = product.getAsJsonArray("buy_summary")
                val sellPrice = buySummary?.firstOrNull()?.asJsonObject?.get("pricePerUnit")?.asDouble?.toLong()
                    ?: product.getAsJsonArray("sell_summary")?.firstOrNull()?.asJsonObject?.get("pricePerUnit")?.asDouble?.toLong()
                    ?: 0L

                if (sellPrice > 0L) {
                    bazaarPrices[productId.uppercase()] = sellPrice
                }
            }
        }
    }

    private fun updateSkyblockItems() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(ITEMS_URL))
            .header("User-Agent", "NoemtAddons/1.0")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val jsonObject = JsonParser.parseString(response.body()).asJsonObject
            val itemsArray = jsonObject.getAsJsonArray("items") ?: return
            for (element in itemsArray) {
                val item = element.asJsonObject
                val id = item.get("id")?.asString ?: continue
                val name = item.get("name")?.asString ?: continue
                val cleanName = name.replace("§[0-9a-zA-Z]".toRegex(), "").trim()
                nameToIdMap[cleanName] = id
            }
        }
    }

    fun getPrice(itemId: String): Long {
        val cleanId = itemId.trim().uppercase()
        return bazaarPrices[cleanId]
            ?: lowestBinPrices[cleanId]
            ?: fallbackPrices[cleanId]
            ?: 0L
    }

    fun getItemValue(stack: ItemStack): Long {
        if (stack.isEmpty) return 0L
        val rawName = stack.hoverName.string
        val cleanName = rawName.replace("§[0-9a-zA-Z]".toRegex(), "").replace("’", "'").trim()
        val itemId = stack.skyblockId.removePrefix("STARRED_")

        // 1. Enchanted Books
        if (itemId == "ENCHANTED_BOOK" || stack.`is`(Items.ENCHANTED_BOOK) || cleanName.contains("Enchanted Book", ignoreCase = true)) {
            val lore = stack.lore
            val bookName = if (lore.isNotEmpty()) {
                val first = lore[0]
                if (first == "§8Combinable in Anvil" && lore.size > 2) lore[2] else first
            } else {
                rawName
            }
            val enchantId = enchantNameToID(bookName)
            val enchantPrice = getPrice(enchantId)
            if (enchantPrice > 0L) return enchantPrice
        }

        // 2. Essence Drops
        val essenceVal = getEssenceValue(rawName)
        if (essenceVal > 0L) return essenceVal

        // 3. Shards
        if (cleanName.contains("Shard", ignoreCase = true)) {
            val shardClean = cleanName.uppercase().replace(" SHARD", "").replace(" ", "_").replace("_X1", "")
            val shardId = "SHARD_$shardClean"
            val shardPrice = getPrice(shardId)
            if (shardPrice > 0L) return shardPrice
        }

        // 4. By Skyblock ID
        if (itemId.isNotBlank()) {
            val price = getPrice(itemId)
            if (price > 0L) return price
        }

        // 5. By Clean Name lookup
        val resolvedId = getIdFromName(rawName)
        if (resolvedId != null) {
            val price = getPrice(resolvedId)
            if (price > 0L) return price
        }

        return fallbackPrices[cleanName.uppercase().replace(" ", "_")] ?: 10_000L
    }

    fun getIdFromName(name: String): String? {
        val cleanName = name.replace("§[0-9a-zA-Z]".toRegex(), "").replace("’", "'").trim()

        if (cleanName.startsWith("Enchanted Book (", ignoreCase = true) || cleanName.contains("Enchanted Book", ignoreCase = true)) {
            val inside = if (cleanName.contains("(") && cleanName.contains(")")) {
                cleanName.substringAfter("(").substringBefore(")")
            } else {
                cleanName.removePrefix("Enchanted Book").trim()
            }
            return enchantNameToID(inside)
        }

        if (cleanName.contains("Shard", ignoreCase = true)) {
            val shardClean = cleanName.uppercase().replace(" SHARD", "").replace(" ", "_").replace("_X1", "")
            return "SHARD_$shardClean"
        }

        val noShiny = cleanName.removePrefix("Shiny ").trim()
        val mappedId = nameToIdMap[noShiny]?.removePrefix("STARRED_")
        if (mappedId != null) return mappedId

        return noShiny.uppercase().replace(" ", "_")
    }

    fun enchantNameToID(enchant: String): String {
        val clean = enchant.replace("§[0-9a-zA-Z]".toRegex(), "").replace("’", "'").trim()
        if (clean.isBlank()) return "ENCHANTMENT_UNKNOWN_1"

        val parts = clean.split(" ")
        val enchantName = if (parts.size > 1) parts.dropLast(1).joinToString(" ") else clean
        val cleanName = enchantName.trim().uppercase().replace(" ", "_")

        val isUltimate = enchant.contains("§9§d§l") || enchant.contains("§d§l") || enchant.contains("§7§l") ||
                cleanName in listOf(
                    "LEGION", "SOUL_EATER", "ONE_FOR_ALL", "COMBO", "BANK", "NO_PAIN_NO_GAIN",
                    "WISDOM", "REND", "FATAL_TEMPO", "INFERNO", "HABANERO_TACTICS", "CHIMERA", "DUPLEX", "FLASH"
                )

        val enchantId = if (isUltimate && !cleanName.startsWith("ULTIMATE_")) "ULTIMATE_$cleanName" else cleanName

        val levelStr = if (parts.size > 1) parts.last() else "1"
        val level = levelStr.toIntOrNull() ?: romanToDecimal(levelStr)

        return "ENCHANTMENT_${enchantId}_$level"
    }

    fun getEssenceValue(text: String): Long {
        val match = essenceRegex.find(text) ?: return 0L
        val type = match.groups[1]?.value?.uppercase() ?: return 0L
        val count = match.groups[2]?.value?.toLongOrNull() ?: 1L
        val unitPrice = getPrice("ESSENCE_$type").takeIf { it > 0L } ?: 3_000L
        return unitPrice * count
    }

    fun getChestCost(lore: List<String>): Long {
        for (line in lore) {
            val clean = line.replace("§[0-9a-zA-Z]".toRegex(), "").trim()
            if (clean.contains("FREE", ignoreCase = true)) return 0L
            if (clean.contains(" Coins", ignoreCase = true)) {
                val coinsPart = clean.substringBefore(" Coins").substringAfterLast(" ").replace(",", "").trim()
                val parsed = coinsPart.toLongOrNull()
                if (parsed != null) return parsed
            }
        }
        return 0L
    }

    private fun romanToDecimal(roman: String): Int {
        val clean = roman.uppercase().trim()
        val romanMap = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var result = 0
        for (i in clean.indices) {
            val current = romanMap[clean[i]] ?: continue
            val next = if (i + 1 < clean.length) romanMap[clean[i + 1]] ?: 0 else 0
            if (current < next) {
                result -= current
            } else {
                result += current
            }
        }
        return result.coerceAtLeast(1)
    }

    private val fallbackPrices = ConcurrentHashMap<String, Long>()

    private fun loadFallbackPrices() {
        fallbackPrices["NECRON_HANDLE"] = 1_050_000_000L
        fallbackPrices["SHADOW_WARP_SCROLL"] = 380_000_000L
        fallbackPrices["WITHER_SHIELD_SCROLL"] = 380_000_000L
        fallbackPrices["IMPLOSION_SCROLL"] = 380_000_000L
        fallbackPrices["DARK_CLAYMORE"] = 220_000_000L
        fallbackPrices["GIANTS_SWORD"] = 170_000_000L
        fallbackPrices["FIFTH_MASTER_STAR"] = 95_000_000L
        fallbackPrices["FOURTH_MASTER_STAR"] = 55_000_000L
        fallbackPrices["THIRD_MASTER_STAR"] = 35_000_000L
        fallbackPrices["SECOND_MASTER_STAR"] = 22_000_000L
        fallbackPrices["FIRST_MASTER_STAR"] = 12_000_000L
        fallbackPrices["SHADOW_FURY"] = 45_000_000L
        fallbackPrices["SHADOW_ASSASSIN_CHESTPLATE"] = 28_000_000L
        fallbackPrices["WITHER_CHESTPLATE"] = 24_000_000L
        fallbackPrices["PRECURSOR_EYE"] = 25_000_000L
        fallbackPrices["NECROMANCER_LORD_CHESTPLATE"] = 14_000_000L
        fallbackPrices["RECOMBOBULATOR_3000"] = 10_500_000L
        fallbackPrices["LIVID_DAGGER"] = 11_000_000L
        fallbackPrices["LAST_BREATH"] = 9_000_000L
        fallbackPrices["SPIRIT_SWORD"] = 7_000_000L
        fallbackPrices["ITEM_SPIRIT_BOW"] = 7_000_000L
        fallbackPrices["SPIRIT_WING"] = 2_500_000L
        fallbackPrices["SPIRIT_BONE"] = 800_000L
        fallbackPrices["BONZO_STAFF"] = 3_000_000L
        fallbackPrices["BONZO_MASK"] = 3_000_000L
        fallbackPrices["FUMING_POTATO_BOOK"] = 1_800_000L
        fallbackPrices["HOT_POTATO_BOOK"] = 350_000L
        fallbackPrices["WITHER_CATALYST"] = 1_200_000L
        fallbackPrices["WITHER_BLOOD"] = 1_200_000L
        fallbackPrices["ESSENCE_WITHER"] = 4_000L
        fallbackPrices["ESSENCE_UNDEAD"] = 2_500L
        fallbackPrices["ESSENCE_DRAGON"] = 3_000L

        // Enchants
        fallbackPrices["ENCHANTMENT_ULTIMATE_FATAL_TEMPO_1"] = 40_000_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_INFERNO_1"] = 15_000_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_LEGION_1"] = 6_500_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_LEGION_5"] = 105_000_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_SOUL_EATER_1"] = 4_500_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_SOUL_EATER_5"] = 72_000_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_ONE_FOR_ALL_1"] = 3_500_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_COMBO_1"] = 1_500_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_WISDOM_1"] = 1_200_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_BANK_1"] = 800_000L
        fallbackPrices["ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_1"] = 600_000L
        fallbackPrices["ENCHANTMENT_OVERLOAD_1"] = 2_500_000L
        fallbackPrices["ENCHANTMENT_OVERLOAD_5"] = 40_000_000L
        fallbackPrices["ENCHANTMENT_REJUVENATE_1"] = 300_000L
        fallbackPrices["ENCHANTMENT_INFINITE_QUIVER_6"] = 25_000L
        fallbackPrices["ENCHANTMENT_INFINITE_QUIVER_10"] = 1_500_000L
        fallbackPrices["ENCHANTMENT_FEATHER_FALLING_6"] = 20_000L
        fallbackPrices["ENCHANTMENT_FEATHER_FALLING_10"] = 1_200_000L
    }
}
