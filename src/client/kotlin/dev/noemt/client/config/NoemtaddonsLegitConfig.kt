package dev.noemt.client.config

import com.google.gson.annotations.Expose
import dev.noemt.client.BuildConstants
import io.github.notenoughupdates.moulconfig.ChromaColour
import io.github.notenoughupdates.moulconfig.annotations.Category
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import io.github.notenoughupdates.moulconfig.common.text.StructuredText

class NoemtaddonsLegitConfig : NoemtaddonsConfig() {
    override fun getTitle(): StructuredText {
        return StructuredText.of("NoemtAddons Config (Legit)")
    }
}
