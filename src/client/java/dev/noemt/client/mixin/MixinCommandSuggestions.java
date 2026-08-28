package dev.noemt.client.mixin;

import com.mojang.brigadier.StringReader;
import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CommandSuggestions.class)
public abstract class MixinCommandSuggestions {
    @Redirect(method = "updateCommandInfo", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/StringReader;peek()C", remap = false))
    private char onPeek(StringReader reader) {
        char c = reader.peek();
        if (c == '&') {
            return '/';
        }
        return c;
    }
}
