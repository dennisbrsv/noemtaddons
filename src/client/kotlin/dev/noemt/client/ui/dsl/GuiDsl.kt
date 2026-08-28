package dev.noemt.client.ui.dsl

import dev.noemt.client.ui.core.GuiTheme
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

class UiBuilder(
    val font: Font,
    val addWidget: (net.minecraft.client.gui.components.AbstractWidget) -> Unit
) {
    fun button(
        text: String,
        x: Int,
        y: Int,
        width: Int = 80,
        height: Int = 18,
        onClick: () -> Unit
    ): Button {
        val btn = Button.builder(Component.literal(text)) {
            onClick()
        }.bounds(x, y, width, height).build()
        addWidget(btn)
        return btn
    }

    fun primaryButton(
        text: String,
        x: Int,
        y: Int,
        width: Int = 80,
        height: Int = 18,
        onClick: () -> Unit
    ): Button = button("§b§l$text", x, y, width, height, onClick)

    fun dangerButton(
        text: String,
        x: Int,
        y: Int,
        width: Int = 80,
        height: Int = 18,
        onClick: () -> Unit
    ): Button = button("§c$text", x, y, width, height, onClick)

    fun successButton(
        text: String,
        x: Int,
        y: Int,
        width: Int = 80,
        height: Int = 18,
        onClick: () -> Unit
    ): Button = button("§a$text", x, y, width, height, onClick)

    fun toggleButton(
        label: String,
        isActive: Boolean,
        x: Int,
        y: Int,
        width: Int = 90,
        height: Int = 18,
        onToggle: () -> Unit
    ): Button {
        val text = if (isActive) "§a● $label" else "§7○ $label"
        return button(text, x, y, width, height, onToggle)
    }

    fun textInput(
        hint: String,
        initialValue: String = "",
        x: Int,
        y: Int,
        width: Int = 120,
        height: Int = 18,
        onTextChange: ((String) -> Unit)? = null
    ): EditBox {
        val box = EditBox(font, x, y, width, height, Component.literal(hint))
        box.value = initialValue
        box.setHint(Component.literal(hint))
        if (onTextChange != null) {
            box.setResponder { onTextChange(it) }
        }
        addWidget(box)
        return box
    }

    fun buttonRow(
        startX: Int,
        y: Int,
        gap: Int = 6,
        items: List<Pair<String, () -> Unit>>
    ) {
        var currX = startX
        for ((label, action) in items) {
            val btnWidth = font.width(label) + 16
            button(label, currX, y, btnWidth, 18, action)
            currX += btnWidth + gap
        }
    }

    fun selectorGroup(
        startX: Int,
        y: Int,
        selectedIndex: Int,
        options: List<String>,
        onSelect: (Int) -> Unit
    ) {
        var currX = startX
        for ((idx, opt) in options.withIndex()) {
            val isSel = idx == selectedIndex
            val label = if (isSel) "§b§l$opt" else "§7$opt"
            val w = font.width(opt) + 14
            button(label, currX, y, w, 18) {
                onSelect(idx)
            }
            currX += w + 4
        }
    }
}
