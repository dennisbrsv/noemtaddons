package dev.noemt.client

import java.util.Properties

object BuildConstants {
    val buildType: String by lazy {
        runCatching {
            val stream = BuildConstants::class.java.getResourceAsStream("/build-info.properties")
            if (stream != null) {
                val props = Properties()
                props.load(stream)
                props.getProperty("build_type", "cheat").trim().lowercase()
            } else {
                "cheat"
            }
        }.getOrDefault("cheat")
    }

    val isCheatBuild: Boolean get() = buildType == "cheat"
    val isLegitBuild: Boolean get() = buildType == "legit"

    val buildDisplayName: String get() = if (isLegitBuild) "Legit" else "Cheat"
}
