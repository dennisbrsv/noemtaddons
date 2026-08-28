package dev.noemt.client.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChatScreen.class)
public abstract class MixinChatScreen {
    @Redirect(method = "handleChatInput", at = @At(value = "INVOKE", target = "Ljava/lang/String;startsWith(Ljava/lang/String;)Z"))
    private boolean onStartsWith(String instance, String prefix) {
        return instance.startsWith("/") || instance.startsWith("$") || instance.startsWith("&");
    }
}
