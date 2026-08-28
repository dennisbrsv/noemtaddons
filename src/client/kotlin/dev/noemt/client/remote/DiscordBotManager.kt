package dev.noemt.client.remote

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.noemt.client.config.ConfigManager
import dev.noemt.client.utils.ChatUtils
import dev.noemt.client.module.Module
import dev.noemt.client.module.ModuleType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

object DiscordBotManager : Module {
    override val id = "discord_bot"
    override val name = "Discord Bot"
    override val description = "Discord Bot and Webhook notifications integration"
    override val type = ModuleType.LEGIT

    override fun init() {}

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()

    fun sendNotification(
        title: String,
        description: String,
        colorHex: Int = 0x5865F2,
        fields: Map<String, String> = emptyMap(),
        footer: String = "NoemtAddons Notification"
    ): CompletableFuture<Boolean> {
        val config = ConfigManager.config.remote
        if (!config.discordEnabled) {
            return CompletableFuture.completedFuture(false)
        }

        val embed = JsonObject().apply {
            addProperty("title", title)
            addProperty("description", description)
            addProperty("color", colorHex)
            if (fields.isNotEmpty()) {
                val fieldArray = JsonArray()
                for ((k, v) in fields) {
                    fieldArray.add(JsonObject().apply {
                        addProperty("name", k)
                        addProperty("value", v)
                        addProperty("inline", true)
                    })
                }
                add("fields", fieldArray)
            }
            add("footer", JsonObject().apply {
                addProperty("text", footer)
            })
            addProperty("timestamp", java.time.Instant.now().toString())
        }

        val payload = JsonObject().apply {
            val embeds = JsonArray()
            embeds.add(embed)
            add("embeds", embeds)
        }

        return sendRawPayload(payload)
    }

    fun sendMessage(messageText: String): CompletableFuture<Boolean> {
        val config = ConfigManager.config.remote
        if (!config.discordEnabled) {
            return CompletableFuture.completedFuture(false)
        }

        val payload = JsonObject().apply {
            addProperty("content", messageText)
        }

        return sendRawPayload(payload)
    }

    private fun sendRawPayload(payload: JsonObject): CompletableFuture<Boolean> {
        val config = ConfigManager.config.remote
        val jsonString = gson.toJson(payload)

        // Priority 1: Discord Bot Token + Channel ID
        val token = config.discordBotToken.trim()
        val channelId = config.discordChannelId.trim()

        if (token.isNotEmpty() && channelId.isNotEmpty()) {
            val url = "https://discord.com/api/v10/channels/$channelId/messages"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bot $token")
                .header("Content-Type", "application/json")
                .header("User-Agent", "NoemtAddons/1.0.0 (DiscordNotificationClient)")
                .POST(HttpRequest.BodyPublishers.ofString(jsonString))
                .timeout(Duration.ofSeconds(10))
                .build()

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply { resp ->
                    val ok = resp.statusCode() in 200..299
                    if (!ok) {
                        ChatUtils.modMessage("&c[Discord] Failed to send message via Bot Token (HTTP ${resp.statusCode()}): ${resp.body()}")
                    }
                    ok
                }.exceptionally { err ->
                    ChatUtils.modMessage("&c[Discord] Error sending bot notification: ${err.message}")
                    false
                }
        }

        // Priority 2: Webhook URL fallback
        val webhookUrl = config.discordWebhookUrl.trim()
        if (webhookUrl.isNotEmpty()) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .header("User-Agent", "NoemtAddons/1.0.0 (DiscordWebhookClient)")
                .POST(HttpRequest.BodyPublishers.ofString(jsonString))
                .timeout(Duration.ofSeconds(10))
                .build()

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply { resp ->
                    val ok = resp.statusCode() in 200..299
                    if (!ok) {
                        ChatUtils.modMessage("&c[Discord] Failed to send webhook (HTTP ${resp.statusCode()}): ${resp.body()}")
                    }
                    ok
                }.exceptionally { err ->
                    ChatUtils.modMessage("&c[Discord] Error sending webhook notification: ${err.message}")
                    false
                }
        }

        ChatUtils.modMessage("&c[Discord] Both Bot Token + Channel ID and Webhook URL are unconfigured. Please configure them in /noemt config!")
        return CompletableFuture.completedFuture(false)
    }
}
