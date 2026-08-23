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
        mc.gui.hud.getChat().addClientSystemMessage(Component.literal(msg.toString().addColor()))
    }

    fun chat(comp: Component) = mc.execute {
        mc.gui.hud.getChat().addClientSystemMessage(comp)
    }

    fun sendPartyMessage(msg: Any?) {
        val conn = mc.player?.connection ?: return
        conn.sendCommand("pc $msg")
    }

    fun showTitle(title: String, subtitle: String = "") {
        mc.execute {
            mc.gui.hud.setTitle(Component.literal(title.addColor()))
            if (subtitle.isNotEmpty()) {
                mc.gui.hud.setSubtitle(Component.literal(subtitle.addColor()))
            }
            mc.gui.hud.setTimes(5, 30, 5)
        }
    }

    fun String.addColor() = replace("&", "§")

    val Component.unformattedText get() = string.removeFormatting()
    val Component.formattedText get() = formatted(this)

    private val formatted = fun(comp: Component): String {
        val sb = StringBuilder()
        comp.visit({ style, string ->
            style.color?.let { textColor ->
                val colorMatch = ChatFormatting.entries.firstOrNull {
                    net.minecraft.network.chat.TextColor.fromLegacyFormat(it)?.value == textColor.value
                }
                if (colorMatch != null) {
                    sb.append("§${colorMatch.toString()[1]}")
                }
            }

            if (style.isBold) sb.append("§${ChatFormatting.BOLD.toString()[1]}")
            if (style.isItalic) sb.append("§${ChatFormatting.ITALIC.toString()[1]}")
            if (style.isUnderlined) sb.append("§${ChatFormatting.UNDERLINE.toString()[1]}")
            if (style.isStrikethrough) sb.append("§${ChatFormatting.STRIKETHROUGH.toString()[1]}")
            if (style.isObfuscated) sb.append("§${ChatFormatting.OBFUSCATED.toString()[1]}")

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
                val next = this[i + 1]
                if (next in '0'..'9' || next in 'a'..'f' || next in 'A'..'F' || next in 'k'..'o' || next in 'K'..'O' || next == 'r' || next == 'R') {
                    i += 2
                    continue
                }
            }
            out[outPos++] = c
            i++
        }
        return if (outPos == len) this else String(out, 0, outPos)
    }
}
