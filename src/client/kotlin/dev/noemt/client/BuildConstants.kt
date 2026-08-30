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

    val isCheatBuild: Boolean get() = true
    val buildDisplayName: String get() = "Cheat"

    const val WS_SECRET: String = "462265aee003624360bb0ca8a27176407f48a84eae64ee1b3bc1c6a46410a9ed"
}
