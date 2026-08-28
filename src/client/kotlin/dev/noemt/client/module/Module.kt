package dev.noemt.client.module

enum class ModuleType {
    LEGIT,
    CHEAT
}

interface Module {
    val id: String
    val name: String
    val description: String
    val type: ModuleType

    fun init()
    fun isEnabled(): Boolean = true
}
