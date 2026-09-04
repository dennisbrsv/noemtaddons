package dev.noemt.client.utils

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.util.Optional

object ChatUtils {
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun modMessage(msg: Any?) = chat("§b[NoemtAddons]§r $msg")

    fun chat(msg: Any?) = mc.execute {
        mc.gui.chat.addClientSystemMessage(Component.literal(msg.toString().addColor()))
    }

    fun chat(comp: Component) = mc.execute {
        mc.gui.chat.addClientSystemMessage(comp)
    }

    fun sendPartyMessage(msg: Any?) {
        val conn = mc.player?.connection ?: return
        conn.sendCommand("pc $msg")
    }

    fun showTitle(title: String, subtitle: String = "") {
        mc.execute {
            mc.gui.setTitle(Component.literal(title.addColor()))
            if (subtitle.isNotEmpty()) {
                mc.gui.setSubtitle(Component.literal(subtitle.addColor()))
            }
            mc.gui.setTimes(5, 30, 5)
        }
    }

    fun String.addColor() = replace("&", "§")

    private val colorCodeMap: Map<Int, Char> = buildMap {
        for (format in ChatFormatting.entries) {
            val color = net.minecraft.network.chat.TextColor.fromLegacyFormat(format)
            if (color != null) {
                put(color.value, format.char)
            }
        }
    }

    val Component.unformattedText get() = string.removeFormatting()
    val Component.formattedText get() = formatted(this)

    private val formatted = fun(comp: Component): String {
        val sb = StringBuilder()
        comp.visit({ style, string ->
            style.color?.let { textColor ->
                val code = colorCodeMap[textColor.value]
                if (code != null) {
                    sb.append('§').append(code)
                }
            }

            if (style.isBold) sb.append('§').append(ChatFormatting.BOLD.char)
            if (style.isItalic) sb.append('§').append(ChatFormatting.ITALIC.char)
            if (style.isUnderlined) sb.append('§').append(ChatFormatting.UNDERLINE.char)
            if (style.isStrikethrough) sb.append('§').append(ChatFormatting.STRIKETHROUGH.char)
            if (style.isObfuscated) sb.append('§').append(ChatFormatting.OBFUSCATED.char)

            sb.append(string)
            Optional.empty<String>()
        }, Style.EMPTY)
        return sb.toString()
    }

    fun String.removeFormatting(): String {
        if (isEmpty()) return this
        val len = length
        val out = CharArray(len)
        var outPos = 0
        var i = 0

        while (i < len) {
            val c = this[i]
            if ((c == '§' || c == '&') && i + 1 < len) {
                // Strip all Minecraft formatting and Hypixel scoreboard unique keys
                i += 2
                continue
            }
            out[outPos++] = c
            i++
        }
        return if (outPos == len) this else String(out, 0, outPos)
    }

    enum class ChatChannel {
        ALL, PARTY, GUILD, OFFICER, COOP, PRIVATE_MESSAGE, SYSTEM
    }

    data class ParsedChatMessage(
        val channel: ChatChannel,
        val sender: String?,
        val rank: String?,
        val message: String,
        val raw: String
    )

    private val partyRegex = Regex("""^Party > (?:\[([^\]]+)\]\s+)?([a-zA-Z0-9_]+)(?:\s+\[[^\]]+\])?:\s+(.+)$""")
    private val guildRegex = Regex("""^Guild > (?:\[([^\]]+)\]\s+)?([a-zA-Z0-9_]+)(?:\s+\[[^\]]+\])?:\s+(.+)$""")
    private val officerRegex = Regex("""^Officer > (?:\[([^\]]+)\]\s+)?([a-zA-Z0-9_]+)(?:\s+\[[^\]]+\])?:\s+(.+)$""")
    private val coopRegex = Regex("""^Co-op > (?:\[([^\]]+)\]\s+)?([a-zA-Z0-9_]+)(?:\s+\[[^\]]+\])?:\s+(.+)$""")
    private val pmFromRegex = Regex("""^From (?:\[([^\]]+)\]\s+)?([a-zA-Z0-9_]+):\s+(.+)$""")
    private val allChatRegex = Regex("""^(?:\[([^\]]+)\]\s+)?([a-zA-Z0-9_]+):\s+(.+)$""")

    /**
     * Parses a chat message into structured sender, rank, channel, and message content.
     */
    fun parseChatMessage(text: String): ParsedChatMessage {
        val clean = text.removeFormatting().trim()

        partyRegex.matchEntire(clean)?.let {
            return ParsedChatMessage(ChatChannel.PARTY, it.groupValues[2], it.groupValues[1].takeIf(String::isNotEmpty), it.groupValues[3], clean)
        }
        guildRegex.matchEntire(clean)?.let {
            return ParsedChatMessage(ChatChannel.GUILD, it.groupValues[2], it.groupValues[1].takeIf(String::isNotEmpty), it.groupValues[3], clean)
        }
        officerRegex.matchEntire(clean)?.let {
            return ParsedChatMessage(ChatChannel.OFFICER, it.groupValues[2], it.groupValues[1].takeIf(String::isNotEmpty), it.groupValues[3], clean)
        }
        coopRegex.matchEntire(clean)?.let {
            return ParsedChatMessage(ChatChannel.COOP, it.groupValues[2], it.groupValues[1].takeIf(String::isNotEmpty), it.groupValues[3], clean)
        }
        pmFromRegex.matchEntire(clean)?.let {
            return ParsedChatMessage(ChatChannel.PRIVATE_MESSAGE, it.groupValues[2], it.groupValues[1].takeIf(String::isNotEmpty), it.groupValues[3], clean)
        }
        allChatRegex.matchEntire(clean)?.let {
            return ParsedChatMessage(ChatChannel.ALL, it.groupValues[2], it.groupValues[1].takeIf(String::isNotEmpty), it.groupValues[3], clean)
        }

        return ParsedChatMessage(ChatChannel.SYSTEM, null, null, clean, clean)
    }
}
