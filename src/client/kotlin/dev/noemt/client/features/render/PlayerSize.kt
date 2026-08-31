package dev.noemt.client.features.render

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.mojang.blaze3d.vertex.PoseStack
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.mixin.IAvatarRenderState
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.remote.RemoteWebSocketClient
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.utils.ThreadUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object PlayerSize : Module {
    override val id = "player_size"
    override val name = "Player Size"
    override val description = "Adjusts player model size and synchronizes across all online players via WebSockets."
    override val type = ModuleType.LEGIT

    private val mc: Minecraft get() = Minecraft.getInstance()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NoemtAddons-PlayerSize").apply { isDaemon = true }
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .build()

    // Keyed by UUID
    val playerSizes = ConcurrentHashMap<UUID, PlayerScaleData>()
    val nameToUuid = ConcurrentHashMap<String, UUID>()

    private val cacheFile: File
        get() {
            val dir = File(mc.gameDirectory, "config/noemtaddons")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "player_sizes_cache.json")
        }

    data class PlayerScaleData(
        @SerializedName("uuid", alternate = ["Uuid", "UUID"]) val uuid: UUID,
        @SerializedName("name", alternate = ["DevName", "name", "username"]) val name: String,
        @SerializedName("scale", alternate = ["Size", "scale", "sizes"]) val scale: List<Float>,
        @SerializedName("customName", alternate = ["CustomName"]) val customName: String? = null
    ) {
        val scaleX: Float get() = scale.getOrElse(0) { 1.0f }
        val scaleY: Float get() = scale.getOrElse(1) { 1.0f }
        val scaleZ: Float get() = scale.getOrElse(2) { 1.0f }
    }

    override fun init() {
        // Failsafe 1: Load cached player sizes from local disk
        loadCacheFromDisk()

        // Failsafe 2: Periodic HTTP background fetch if WebSocket is disconnected
        scheduler.scheduleWithFixedDelay({
            try {
                val config = ConfigManager.config.playerSize
                if (config.enabled && config.syncOnlinePlayers && !RemoteWebSocketClient.isConnected) {
                    fetchSizesHttp()
                }
            } catch (e: Exception) {
                // Ignore background polling errors
            }
        }, 5, 30, TimeUnit.SECONDS)
    }

    @JvmStatic
    fun applyScaleHook(state: AvatarRenderState, matrix: PoseStack) {
        val config = ConfigManager.config.playerSize
        if (!config.enabled) return

        val accessor = state as? IAvatarRenderState ?: return
        val uuid = accessor.`noemt$getUuid`() ?: return
        val name = accessor.`noemt$getPlayerName`() ?: ""

        val localPlayer = mc.player
        val isLocal = (localPlayer != null && (uuid == localPlayer.uuid || (name.isNotEmpty() && name.equals(localPlayer.name.string, ignoreCase = true))))

        var scaleX = 1.0f
        var scaleY = 1.0f
        var scaleZ = 1.0f
        var shouldApply = false

        if (isLocal) {
            if (config.localCustomSize) {
                scaleX = config.sizeX
                scaleY = config.sizeY
                scaleZ = config.sizeZ
                shouldApply = true
            } else if (config.syncOnlinePlayers && playerSizes.containsKey(uuid)) {
                val data = playerSizes[uuid]
                if (data != null) {
                    scaleX = data.scaleX
                    scaleY = data.scaleY
                    scaleZ = data.scaleZ
                    shouldApply = true
                }
            }
        } else {
            if (config.syncOnlinePlayers) {
                val data = playerSizes[uuid] ?: nameToUuid[name.lowercase()]?.let { playerSizes[it] }
                if (data != null) {
                    scaleX = data.scaleX
                    scaleY = data.scaleY
                    scaleZ = data.scaleZ
                    shouldApply = true
                }
            }
        }

        if (!shouldApply) return

        // Failsafe: Validate finite numbers
        if (scaleX.isNaN() || scaleY.isNaN() || scaleZ.isNaN() ||
            scaleX.isInfinite() || scaleY.isInfinite() || scaleZ.isInfinite()) {
            return
        }

        // Clamp to safe range -10f..10f
        scaleX = scaleX.coerceIn(-10f, 10f)
        scaleY = scaleY.coerceIn(-10f, 10f)
        scaleZ = scaleZ.coerceIn(-10f, 10f)

        // Prevent matrix singularity / exact zero
        if (abs(scaleX) < 0.001f) scaleX = if (scaleX < 0) -0.001f else 0.001f
        if (abs(scaleY) < 0.001f) scaleY = if (scaleY < 0) -0.001f else 0.001f
        if (abs(scaleZ) < 0.001f) scaleZ = if (scaleZ < 0) -0.001f else 0.001f

        // Upside down translation flip (Dinnerbone style)
        if (scaleY < 0f) {
            matrix.translate(0f, scaleY * 2f, 0f)
        }

        matrix.scale(scaleX, scaleY, scaleZ)
    }

    fun updatePlayerSize(uuid: UUID, name: String, scale: List<Float>, customName: String? = null) {
        val sanitized = sanitizeScale(scale)
        val data = PlayerScaleData(uuid, name, sanitized, customName)
        playerSizes[uuid] = data
        if (name.isNotEmpty()) {
            nameToUuid[name.lowercase()] = uuid
        }
        saveCacheToDisk()
    }

    fun updateAllSizes(list: List<PlayerScaleData>) {
        playerSizes.clear()
        nameToUuid.clear()
        for (item in list) {
            val sanitized = sanitizeScale(item.scale)
            val clean = PlayerScaleData(item.uuid, item.name, sanitized, item.customName)
            playerSizes[clean.uuid] = clean
            if (clean.name.isNotEmpty()) {
                nameToUuid[clean.name.lowercase()] = clean.uuid
            }
        }
        saveCacheToDisk()
    }

    fun removePlayerSize(uuid: UUID) {
        val removed = playerSizes.remove(uuid)
        if (removed != null && removed.name.isNotEmpty()) {
            nameToUuid.remove(removed.name.lowercase())
        }
        saveCacheToDisk()
    }

    private fun sanitizeScale(scale: List<Float>): List<Float> {
        val sx = scale.getOrElse(0) { 1.0f }.coerceIn(-10f, 10f)
        val sy = scale.getOrElse(1) { 1.0f }.coerceIn(-10f, 10f)
        val sz = scale.getOrElse(2) { 1.0f }.coerceIn(-10f, 10f)
        return listOf(
            if (sx.isNaN() || sx.isInfinite()) 1.0f else sx,
            if (sy.isNaN() || sy.isInfinite()) 1.0f else sy,
            if (sz.isNaN() || sz.isInfinite()) 1.0f else sz
        )
    }

    /**
     * Broadcasts client player size across the network to all other players.
     * Uses live WebSocket first; falls back to HTTP POST if offline.
     */
    fun broadcastOwnSize(scaleX: Float, scaleY: Float, scaleZ: Float, customName: String = "") {
        val player = mc.player ?: return
        val uuid = player.uuid
        val name = player.name.string

        // 1. WebSocket Live Broadcast
        val payload = JsonObject().apply {
            addProperty("type", "PLAYER_SIZE_UPDATE")
            addProperty("uuid", uuid.toString())
            addProperty("name", name)
            add("scale", JsonArray().apply {
                add(scaleX)
                add(scaleY)
                add(scaleZ)
            })
            addProperty("customName", customName)
            addProperty("timestamp", System.currentTimeMillis())
        }

        val wsSuccess = RemoteWebSocketClient.sendJson(payload)

        // 2. Failsafe: HTTP POST fallback if WebSocket is not connected
        if (!wsSuccess) {
            sendSizeHttpFallback(uuid, name, scaleX, scaleY, scaleZ, customName)
        }

        // Locally record
        updatePlayerSize(uuid, name, listOf(scaleX, scaleY, scaleZ), customName)
    }

    /**
     * Broadcasts current configured local sizes if broadcast toggle is on.
     */
    fun syncCurrentConfig() {
        val config = ConfigManager.config.playerSize
        if (config.enabled && config.broadcastSize) {
            broadcastOwnSize(config.sizeX, config.sizeY, config.sizeZ)
        }
    }

    /**
     * Called when WebSocket connects to request initial sync and broadcast own size.
     */
    fun onWebSocketConnected() {
        // Request full sizes sync
        val query = JsonObject().apply {
            addProperty("type", "PLAYER_SIZE_QUERY")
            addProperty("timestamp", System.currentTimeMillis())
        }
        RemoteWebSocketClient.sendJson(query)

        // Broadcast own size if configured
        syncCurrentConfig()
    }

    /**
     * Failsafe HTTP GET fetch for player sizes from server API.
     */
    fun fetchSizesHttp() {
        scheduler.execute {
            try {
                val httpUrl = getHttpApiUrl("/api/player-sizes")
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(httpUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "NoemtAddons-Client/1.0")
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    val type = object : TypeToken<List<PlayerScaleData>>() {}.type
                    val list: List<PlayerScaleData> = gson.fromJson(response.body(), type) ?: emptyList()
                    if (list.isNotEmpty()) {
                        updateAllSizes(list)
                    }
                }
            } catch (e: Exception) {
                // Silent failsafe: retain cached sizes
            }
        }
    }

    /**
     * Failsafe HTTP POST update when WebSocket is temporarily down.
     */
    private fun sendSizeHttpFallback(uuid: UUID, name: String, scaleX: Float, scaleY: Float, scaleZ: Float, customName: String) {
        scheduler.execute {
            try {
                val httpUrl = getHttpApiUrl("/api/player-sizes")
                val bodyJson = JsonObject().apply {
                    addProperty("uuid", uuid.toString())
                    addProperty("name", name)
                    add("scale", JsonArray().apply {
                        add(scaleX)
                        add(scaleY)
                        add(scaleZ)
                    })
                    addProperty("customName", customName)
                }

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(httpUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NoemtAddons-Client/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(bodyJson)))
                    .build()

                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                // Fallback error ignored
            }
        }
    }

    private fun getHttpApiUrl(path: String): String {
        val wsUrl = ConfigManager.config.remote.wsUrl.trim()
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val httpBase = when {
            wsUrl.startsWith("wss://") -> wsUrl.replaceFirst("wss://", "https://")
            wsUrl.startsWith("ws://") -> wsUrl.replaceFirst("ws://", "http://")
            wsUrl.startsWith("https://") || wsUrl.startsWith("http://") -> wsUrl
            else -> "https://$wsUrl"
        }.removeSuffix("/")
        return "$httpBase$cleanPath"
    }

    private fun loadCacheFromDisk() {
        scheduler.execute {
            try {
                val file = cacheFile
                if (file.exists()) {
                    val json = file.readText(Charsets.UTF_8)
                    val type = object : TypeToken<List<PlayerScaleData>>() {}.type
                    val list: List<PlayerScaleData> = gson.fromJson(json, type) ?: emptyList()
                    for (item in list) {
                        playerSizes[item.uuid] = item
                        if (item.name.isNotEmpty()) {
                            nameToUuid[item.name.lowercase()] = item.uuid
                        }
                    }
                }
            } catch (e: Exception) {
                // Cache corrupted or unavailable
            }
        }
    }

    private fun saveCacheToDisk() {
        scheduler.execute {
            try {
                val file = cacheFile
                val list = playerSizes.values.toList()
                file.writeText(gson.toJson(list), Charsets.UTF_8)
            } catch (e: Exception) {
                // Disk write error ignored
            }
        }
    }

    fun clearAllSizes() {
        playerSizes.clear()
        nameToUuid.clear()
        saveCacheToDisk()
    }
}
