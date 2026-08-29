package dev.noemt.client.features.gambling.chest

enum class DungeonChestType(val displayName: String, val colorCode: String) {
    WOODEN("Wood", "§6"),
    GOLD("Gold", "§e"),
    DIAMOND("Diamond", "§b"),
    EMERALD("Emerald", "§a"),
    OBSIDIAN("Obsidian", "§5"),
    BEDROCK("Bedrock", "§8");

    companion object {
        fun getByName(name: String): DungeonChestType? {
            val clean = name.trim()
            if (clean.equals("Wood", ignoreCase = true) || clean.equals("Wooden", ignoreCase = true)) return WOODEN
            if (clean.equals("Gold", ignoreCase = true) || clean.equals("Golden", ignoreCase = true)) return GOLD
            return entries.find { it.name.equals(clean, ignoreCase = true) || it.displayName.equals(clean, ignoreCase = true) }
        }

        fun getByNameStartsWith(name: String): DungeonChestType? {
            val clean = name.trim()
            if (clean.startsWith("Wood", ignoreCase = true) || clean.startsWith("Wooden", ignoreCase = true)) return WOODEN
            if (clean.startsWith("Gold", ignoreCase = true) || clean.startsWith("Golden", ignoreCase = true)) return GOLD
            return entries.find { clean.startsWith(it.name, ignoreCase = true) || clean.startsWith(it.displayName, ignoreCase = true) }
        }

        fun findInText(text: String): DungeonChestType? {
            val clean = text.trim()
            if (clean.contains("Bedrock", ignoreCase = true)) return BEDROCK
            if (clean.contains("Obsidian", ignoreCase = true)) return OBSIDIAN
            if (clean.contains("Emerald", ignoreCase = true)) return EMERALD
            if (clean.contains("Diamond", ignoreCase = true)) return DIAMOND
            if (clean.contains("Golden", ignoreCase = true) || clean.contains("Gold", ignoreCase = true)) return GOLD
            if (clean.contains("Wooden", ignoreCase = true) || clean.contains("Wood", ignoreCase = true)) return WOODEN
            return null
        }
    }
}
