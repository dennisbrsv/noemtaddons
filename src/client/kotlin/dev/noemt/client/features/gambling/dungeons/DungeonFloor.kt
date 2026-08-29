package dev.noemt.client.features.gambling.dungeons

enum class DungeonFloor(val displayName: String, val isMasterMode: Boolean, val floorNumber: Int) {
    F0("Entrance", false, 0),
    F1("Floor I", false, 1),
    F2("Floor II", false, 2),
    F3("Floor III", false, 3),
    F4("Floor IV", false, 4),
    F5("Floor V", false, 5),
    F6("Floor VI", false, 6),
    F7("Floor VII", false, 7),
    M1("Master Floor I", true, 1),
    M2("Master Floor II", true, 2),
    M3("Master Floor III", true, 3),
    M4("Master Floor IV", true, 4),
    M5("Master Floor V", true, 5),
    M6("Master Floor VI", true, 6),
    M7("Master Floor VII", true, 7);

    companion object {
        fun fromString(str: String): DungeonFloor? {
            val upper = str.trim().uppercase()
            entries.find { it.name.equals(upper, ignoreCase = true) }?.let { return it }
            return when {
                upper.contains("M7") || upper.contains("MASTER FLOOR VII") || upper.contains("MASTER CATACOMBS - FLOOR VII") -> M7
                upper.contains("M6") || upper.contains("MASTER FLOOR VI") || upper.contains("MASTER CATACOMBS - FLOOR VI") -> M6
                upper.contains("M5") || upper.contains("MASTER FLOOR V") || upper.contains("MASTER CATACOMBS - FLOOR V") -> M5
                upper.contains("M4") || upper.contains("MASTER FLOOR IV") || upper.contains("MASTER CATACOMBS - FLOOR IV") -> M4
                upper.contains("M3") || upper.contains("MASTER FLOOR III") || upper.contains("MASTER CATACOMBS - FLOOR III") -> M3
                upper.contains("M2") || upper.contains("MASTER FLOOR II") || upper.contains("MASTER CATACOMBS - FLOOR II") -> M2
                upper.contains("M1") || upper.contains("MASTER FLOOR I") || upper.contains("MASTER CATACOMBS - FLOOR I") -> M1
                upper.contains("F7") || upper.contains("FLOOR VII") || upper.contains("CATACOMBS - FLOOR VII") -> F7
                upper.contains("F6") || upper.contains("FLOOR VI") || upper.contains("CATACOMBS - FLOOR VI") -> F6
                upper.contains("F5") || upper.contains("FLOOR V") || upper.contains("CATACOMBS - FLOOR V") -> F5
                upper.contains("F4") || upper.contains("FLOOR IV") || upper.contains("CATACOMBS - FLOOR IV") -> F4
                upper.contains("F3") || upper.contains("FLOOR III") || upper.contains("CATACOMBS - FLOOR III") -> F3
                upper.contains("F2") || upper.contains("FLOOR II") || upper.contains("CATACOMBS - FLOOR II") -> F2
                upper.contains("F1") || upper.contains("FLOOR I") || upper.contains("CATACOMBS - FLOOR I") -> F1
                upper.contains("ENTRANCE") || upper.contains("F0") -> F0
                else -> null
            }
        }

        fun fromFloorNumber(number: Int, isMaster: Boolean = false): DungeonFloor {
            return when (number) {
                1 -> if (isMaster) M1 else F1
                2 -> if (isMaster) M2 else F2
                3 -> if (isMaster) M3 else F3
                4 -> if (isMaster) M4 else F4
                5 -> if (isMaster) M5 else F5
                6 -> if (isMaster) M6 else F6
                7 -> if (isMaster) M7 else F7
                else -> if (isMaster) M7 else F7
            }
        }
    }
}
