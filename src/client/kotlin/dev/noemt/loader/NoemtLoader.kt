package dev.noemt.loader

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

object NoemtLoader : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("NoemtLoader")
    private const val SERVER_BASE = "https://addons.noemt.dev"

    val flavor: String by lazy {
        runCatching {
            val stream = NoemtLoader::class.java.getResourceAsStream("/loader-info.properties")
            if (stream != null) {
                val props = Properties()
                props.load(stream)
                props.getProperty("flavor", "cheat").trim().lowercase()
            } else {
                "cheat"
            }
        }.getOrDefault("cheat")
    }

    override fun onInitializeClient() {
        val flavorName = if (flavor == "legit") "Legit" else "Cheat"
        logger.info("[NoemtLoader] Starting NoemtAddons $flavorName Loader...")

        val gameDir = FabricLoader.getInstance().gameDir
        val cacheDir = gameDir.resolve("noemtaddons").resolve("cache")
        Files.createDirectories(cacheDir)

        val targetJarFile = cacheDir.resolve("noemtaddons-$flavor.jar").toFile()
        val downloadUrl = "$SERVER_BASE/loaders/noemtaddons-$flavor.jar"

        var loadSuccess = false

        // 1. Check for updates / download latest build from server
        try {
            logger.info("[NoemtLoader] Checking for latest mod files from $downloadUrl...")
            val updated = downloadIfNewer(downloadUrl, targetJarFile)
            if (updated || targetJarFile.exists()) {
                logger.info("[NoemtLoader] Mod jar ready: ${targetJarFile.name} (${targetJarFile.length()} bytes)")
            }
        } catch (e: Exception) {
            logger.warn("[NoemtLoader] Remote server check failed: ${e.message}")
            if (targetJarFile.exists()) {
                logger.info("[NoemtLoader] Falling back to cached mod jar: ${targetJarFile.absolutePath}")
            } else {
                logger.error("[NoemtLoader] No cached mod jar found and update server is unreachable!")
            }
        }

        // 2. Load and initialize the mod
        if (targetJarFile.exists() && targetJarFile.length() > 0) {
            try {
                loadAndExecuteMod(targetJarFile)
                loadSuccess = true
            } catch (e: Throwable) {
                logger.error("[NoemtLoader] Failed to load mod jar!", e)
            }
        }

        if (!loadSuccess) {
            logger.error("[NoemtLoader] NoemtAddons could not be initialized.")
        }
    }

    private fun downloadIfNewer(urlString: String, destination: File): Boolean {
        val url = URI(urlString).toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 6000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "NoemtAddons-Loader/1.0.0 ($flavor)")

        if (destination.exists()) {
            conn.ifModifiedSince = destination.lastModified()
        }

        conn.connect()
        val responseCode = conn.responseCode

        if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            logger.info("[NoemtLoader] Local mod files are up to date.")
            return false
        }

        if (responseCode == HttpURLConnection.HTTP_OK) {
            val tempFile = File(destination.parentFile, destination.name + ".tmp")
            conn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.length() > 5000) {
                Files.move(tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                logger.info("[NoemtLoader] Downloaded latest $flavor mod files (${destination.length()} bytes).")
                return true
            } else {
                tempFile.delete()
                throw IllegalStateException("Downloaded file is incomplete or invalid.")
            }
        } else {
            throw IllegalStateException("Server returned HTTP $responseCode")
        }
    }

    private fun loadAndExecuteMod(jarFile: File) {
        val parentLoader = NoemtLoader::class.java.classLoader
        val jarUrl = jarFile.toURI().toURL()

        // Attempt injecting jar into KnotClassLoader if available
        var injected = false
        try {
            val addUrlMethod = parentLoader.javaClass.getMethod("addURL", URL::class.java)
            addUrlMethod.isAccessible = true
            addUrlMethod.invoke(parentLoader, jarUrl)
            injected = true
            logger.info("[NoemtLoader] Successfully attached mod to KnotClassLoader.")
        } catch (ignored: Throwable) {}

        val effectiveClassLoader = if (injected) parentLoader else URLClassLoader(arrayOf(jarUrl), parentLoader)

        // Find and invoke NoemtaddonsClient.onInitializeClient()
        val clientClass = effectiveClassLoader.loadClass("dev.noemt.client.NoemtaddonsClient")
        val instance = runCatching {
            clientClass.getField("INSTANCE").get(null)
        }.getOrElse {
            clientClass.getDeclaredConstructor().newInstance()
        }

        val initMethod = clientClass.getMethod("onInitializeClient")
        initMethod.invoke(instance)
        logger.info("[NoemtLoader] NoemtAddons client successfully started!")
    }
}
