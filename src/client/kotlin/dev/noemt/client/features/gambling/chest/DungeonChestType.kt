package dev.noemt.client.features.gambling.chest

enum class DungeonChestType(val displayName: String, val colorCode: String) {
    WOODEN("Wood", "§6"),
    GOLD("Gold", "§e"),
    DIAMOND("Diamond", "§b"),
    EMERALD("Emerald", "§a"),
    OBSIDIAN("Obsidian", "§5"),
    BEDROCK("Bedrock", "§8");

    companion object {
        fun getByName(name: String): DungeonChestType? =
            entries.find { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }

        fun getByNameStartsWith(name: String): DungeonChestType? =
            entries.find { name.startsWith(it.name, ignoreCase = true) || name.startsWith(it.displayName, ignoreCase = true) }
    }
}
