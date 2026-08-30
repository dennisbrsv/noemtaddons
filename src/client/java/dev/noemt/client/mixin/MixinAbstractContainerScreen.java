package dev.noemt.client.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class MixinAbstractContainerScreen extends Screen {

    protected MixinAbstractContainerScreen(Component title) {
        super(title);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressedHead(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (dev.noemt.client.features.loadout.LoadoutManager.INSTANCE.isExecutingSwap()) {
            cir.setReturnValue(true);
            return;
        }
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_F4) {
            dev.noemt.client.utils.DebugUtils.INSTANCE.dumpHoveredOrHeldItem();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClickedHead(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (dev.noemt.client.features.loadout.LoadoutManager.INSTANCE.isExecutingSwap()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void onMouseReleasedHead(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (dev.noemt.client.features.loadout.LoadoutManager.INSTANCE.isExecutingSwap()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void onMouseDraggedHead(MouseButtonEvent event, double dragX, double dragY, CallbackInfoReturnable<Boolean> cir) {
        if (dev.noemt.client.features.loadout.LoadoutManager.INSTANCE.isExecutingSwap()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void onMouseScrolledHead(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (dev.noemt.client.features.loadout.LoadoutManager.INSTANCE.isExecutingSwap()) {
            cir.setReturnValue(true);
        }
    }
}
