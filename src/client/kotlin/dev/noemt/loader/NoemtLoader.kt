package dev.noemt.loader

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

object NoemtLoader : PreLaunchEntrypoint, ClientModInitializer {
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

    private var targetJarFile: File? = null
    private var isPayloadReady: Boolean = false

    override fun onPreLaunch() {
        val flavorName = if (flavor == "legit") "Legit" else "Cheat"
        logger.info("[NoemtLoader] PreLaunch: Checking for latest NoemtAddons ($flavorName)...")

        val gameDir = FabricLoader.getInstance().gameDir
        val cacheDir = gameDir.resolve("noemtaddons").resolve("cache")
        Files.createDirectories(cacheDir)

        val jarFile = cacheDir.resolve("noemtaddons-$flavor.jar").toFile()
        targetJarFile = jarFile
        val downloadUrl = "$SERVER_BASE/loaders/noemtaddons-$flavor.jar"

        // 1. Download or update payload jar before Mixins/Classes are initialized
        try {
            logger.info("[NoemtLoader] Contacting update server: $downloadUrl")
            val updated = downloadIfNewer(downloadUrl, jarFile)
            if (updated || jarFile.exists()) {
                logger.info("[NoemtLoader] Mod payload is ready (${jarFile.length()} bytes).")
            }
        } catch (e: Exception) {
            logger.warn("[NoemtLoader] Update server unreachable: ${e.message}")
            if (jarFile.exists()) {
                logger.info("[NoemtLoader] Using cached build: ${jarFile.absolutePath}")
            }
        }

        // 2. Attach payload jar to Fabric KnotClassLoader / CodeSource
        if (jarFile.exists() && jarFile.length() > 5000) {
            attachToKnotClassLoader(jarFile)
            isPayloadReady = true
        } else {
            logger.error("[NoemtLoader] No valid mod payload available to load!")
        }
    }

    override fun onInitializeClient() {
        logger.info("[NoemtLoader] ClientInit: Launching NoemtAddons client...")
        val jarFile = targetJarFile
        if (jarFile != null && jarFile.exists() && isPayloadReady) {
            try {
                executeClientEntrypoint(jarFile)
            } catch (e: Throwable) {
                logger.error("[NoemtLoader] Error initializing NoemtAddons client!", e)
            }
        } else {
            logger.error("[NoemtLoader] Cannot initialize NoemtAddons: Payload is missing.")
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
            logger.info("[NoemtLoader] Local mod files are already up to date.")
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
                logger.info("[NoemtLoader] Successfully updated $flavor payload (${destination.length()} bytes).")
                return true
            } else {
                tempFile.delete()
                throw IllegalStateException("Downloaded file is incomplete.")
            }
        } else {
            throw IllegalStateException("Server returned HTTP $responseCode")
        }
    }

    private fun attachToKnotClassLoader(jarFile: File) {
        val loader = NoemtLoader::class.java.classLoader
        val jarPath = jarFile.toPath()
        val jarUrl = jarFile.toURI().toURL()

        // 1. Try KnotClassDelegate.addCodeSource(Path)
        try {
            val getDelegateMethod = loader.javaClass.getDeclaredMethod("getDelegate")
            getDelegateMethod.isAccessible = true
            val delegate = getDelegateMethod.invoke(loader)
            val addCodeSourceMethod = delegate.javaClass.getMethod("addCodeSource", Path::class.java)
            addCodeSourceMethod.isAccessible = true
            addCodeSourceMethod.invoke(delegate, jarPath)
            logger.info("[NoemtLoader] Added code source to KnotClassDelegate.")
        } catch (ignored: Throwable) {}

        // 2. Try KnotClassLoader.addUrlFwd(URL) / addURL(URL)
        try {
            val addUrlFwd = loader.javaClass.getMethod("addUrlFwd", URL::class.java)
            addUrlFwd.isAccessible = true
            addUrlFwd.invoke(loader, jarUrl)
            logger.info("[NoemtLoader] Injected URL into KnotClassLoader.")
        } catch (ignored: Throwable) {
            try {
                val addUrl = loader.javaClass.getMethod("addURL", URL::class.java)
                addUrl.isAccessible = true
                addUrl.invoke(loader, jarUrl)
            } catch (ignored2: Throwable) {}
        }
    }

    private fun executeClientEntrypoint(jarFile: File) {
        val loader = NoemtLoader::class.java.classLoader
        val effectiveLoader = try {
            loader.loadClass("dev.noemt.client.NoemtaddonsClient")
            loader
        } catch (e: ClassNotFoundException) {
            URLClassLoader(arrayOf(jarFile.toURI().toURL()), loader)
        }

        val clientClass = effectiveLoader.loadClass("dev.noemt.client.NoemtaddonsClient")
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
