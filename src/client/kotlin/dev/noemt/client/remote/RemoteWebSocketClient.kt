package dev.noemt.client.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import dev.noemt.client.utils.ChatUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object RemoteWebSocketClient : Module {
    override val id = "remote_ws"
    override val name = "Remote WebSocket"
    override val description = "Remote WebSocket connection client"
    override val type = ModuleType.LEGIT

    private val mc: Minecraft get() = Minecraft.getInstance()
    private val gson = Gson()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NoemtAddons-RemoteWS").apply { isDaemon = true }
    }

    private var activeSocket: WebSocket? = null
    private val isConnecting = AtomicBoolean(false)
    private val isManuallyDisconnected = AtomicBoolean(false)

    var isConnected: Boolean = false
        private set
    var lastPingMs: Long = 0
        private set
    var serverUrl: String = "wss://addons.noemt.dev"
        private set

    override fun init() {
        // Periodic check daemon: ensures persistent connection whenever enabled
        scheduler.scheduleWithFixedDelay({
            try {
                val config = ConfigManager.config.remote
                if (config.wsEnabled && !isManuallyDisconnected.get() && !isConnected && !isConnecting.get()) {
                    connect(config.wsUrl)
                }
            } catch (e: Exception) {
                // Ignore background check errors
            }
        }, 3, 5, TimeUnit.SECONDS)
    }

    fun connect(url: String = ConfigManager.config.remote.wsUrl) {
        if (isConnecting.get()) return
        isConnecting.set(true)
        isManuallyDisconnected.set(false)
        serverUrl = url.trim()

        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build()

            client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .buildAsync(URI.create(serverUrl), WebSocketListener())
                .whenComplete { ws, error ->
                    isConnecting.set(false)
                    if (error != null) {
                        isConnected = false
                        activeSocket = null
                        // Silent retry or notification
                    } else {
                        activeSocket = ws
                        isConnected = true
                        onConnected(ws)
                    }
                }
        } catch (e: Exception) {
            isConnecting.set(false)
            isConnected = false
            activeSocket = null
        }
    }

    fun disconnect() {
        isManuallyDisconnected.set(true)
        try {
            activeSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "Client disconnecting")
        } catch (e: Exception) {
            // Ignore close error
        }
        activeSocket = null
        isConnected = false
        ChatUtils.modMessage("&e[Remote] Disconnected from WebSocket server.")
    }

    private fun onConnected(ws: WebSocket) {
        ChatUtils.modMessage("&a[Remote] Connected to WebSocket server: &b$serverUrl")

        // Send Handshake packet
        val player = mc.player
        val handshake = JsonObject().apply {
            addProperty("type", "HANDSHAKE")
            addProperty("player", player?.name?.string ?: "Unknown")
            addProperty("uuid", player?.uuid?.toString() ?: "Unknown")
            addProperty("secret", ConfigManager.config.remote.wsSecret)
            addProperty("modVersion", "1.0.0")
            addProperty("timestamp", System.currentTimeMillis())
        }
        sendJson(handshake)
    }

    fun sendJson(obj: JsonObject): Boolean {
        val ws = activeSocket ?: return false
        return try {
            ws.sendText(gson.toJson(obj), true)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendEvent(eventType: String, data: JsonObject = JsonObject()) {
        val payload = JsonObject().apply {
            addProperty("type", "EVENT")
            addProperty("event", eventType)
            add("data", data)
            addProperty("timestamp", System.currentTimeMillis())
        }
        sendJson(payload)
    }

    private class WebSocketListener : WebSocket.Listener {
        private val messageBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            messageBuffer.append(data)
            if (last) {
                val fullMessage = messageBuffer.toString()
                messageBuffer.setLength(0)
                handleIncomingMessage(fullMessage)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onPing(webSocket: WebSocket, message: java.nio.ByteBuffer): CompletionStage<*>? {
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onPong(webSocket: WebSocket, message: java.nio.ByteBuffer): CompletionStage<*>? {
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            isConnected = false
            activeSocket = null
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            isConnected = false
            activeSocket = null
        }
    }

    private fun handleIncomingMessage(rawJson: String) {
        try {
            val json = gson.fromJson(rawJson, JsonObject::class.java) ?: return
            val type = json.get("type")?.asString?.uppercase() ?: return

            when (type) {
                "HANDSHAKE_ACK" -> {
                    val msg = json.get("message")?.asString ?: "Authentication accepted"
                    ChatUtils.modMessage("&a[Remote] Server: $msg")
                }

                "PING" -> {
                    val pong = JsonObject().apply {
                        addProperty("type", "PONG")
                        addProperty("timestamp", System.currentTimeMillis())
                    }
                    sendJson(pong)
                }

                "MESSAGE" -> {
                    val msg = json.get("message")?.asString ?: return
                    ChatUtils.modMessage(msg)
                }

                "CHAT" -> {
                    val text = json.get("text")?.asString ?: return
                    mc.execute {
                        val player = mc.player
                        if (text.startsWith("/")) {
                            player?.connection?.sendCommand(text.removePrefix("/"))
                        } else {
                            player?.connection?.sendChat(text)
                        }
                    }
                }

                "TITLE" -> {
                    val title = json.get("title")?.asString ?: ""
                    val subtitle = json.get("subtitle")?.asString ?: ""
                    ChatUtils.showTitle(title, subtitle)
                }

                "DISCORD_NOTIFY" -> {
                    val title = json.get("title")?.asString ?: "Remote Notification"
                    val desc = json.get("description")?.asString ?: ""
                    DiscordBotManager.sendNotification(title, desc)
                }

                "STATUS_REQUEST" -> {
                    mc.execute {
                        val player = mc.player
                        val status = JsonObject().apply {
                            addProperty("type", "STATUS_RESPONSE")
                            addProperty("player", player?.name?.string ?: "Unknown")
                            addProperty("uuid", player?.uuid?.toString() ?: "Unknown")
                            addProperty("x", player?.x ?: 0.0)
                            addProperty("y", player?.y ?: 0.0)
                            addProperty("z", player?.z ?: 0.0)
                            addProperty("health", player?.health ?: 0f)
                            addProperty("timestamp", System.currentTimeMillis())
                        }
                        sendJson(status)
                    }
                }

                else -> {
                    // Custom remote event
                    val customName = json.get("action")?.asString ?: type
                    ChatUtils.modMessage("&7[Remote Event] &b$customName: &f$json")
                }
            }
        } catch (e: Exception) {
            // Malformed packet
        }
    }
}
