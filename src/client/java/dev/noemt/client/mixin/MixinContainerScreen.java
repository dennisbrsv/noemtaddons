package dev.noemt.client.mixin;

import dev.noemt.client.features.gambling.dungeons.DungeonChestGambling;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ContainerScreen.class)
public abstract class MixinContainerScreen {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void onExtractBackgroundHead(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (DungeonChestGambling.INSTANCE.isSessionActive(screen)) {
            ci.cancel();
        }
    }
}
