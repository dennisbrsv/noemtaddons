package dev.noemt.client.mixin;

import dev.noemt.client.features.gambling.dungeons.CroesusGamblingModifier;
import dev.noemt.client.features.gambling.dungeons.DungeonChestGambling;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(AbstractContainerScreen.class)
public abstract class MixinAbstractContainerScreen extends Screen {

    protected MixinAbstractContainerScreen(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void onExtractRenderStateHead(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (DungeonChestGambling.INSTANCE.handleRender(screen, graphics, mouseX, mouseY, partialTick)) {
            ci.cancel();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClickedHead(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (DungeonChestGambling.INSTANCE.isSessionActive(screen)) {
            boolean handled = DungeonChestGambling.INSTANCE.onMouseClicked(screen, event, doubleClick);
            if (handled) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressedHead(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_F4 || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_F8) {
            dev.noemt.client.utils.DebugUtils.INSTANCE.dumpCurrentChest();
            cir.setReturnValue(true);
            return;
        }
        if (DungeonChestGambling.INSTANCE.isSessionActive(screen)) {
            boolean handled = DungeonChestGambling.INSTANCE.onKeyPressed(screen, event);
            if (handled) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemovedHead(CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        DungeonChestGambling.INSTANCE.onContainerClosed(screen);
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void onGetTooltipFromContainerItem(ItemStack itemStack, CallbackInfoReturnable<List<Component>> cir) {
        List<Component> original = cir.getReturnValue();
        if (original != null && CroesusGamblingModifier.INSTANCE.shouldModifyTooltip(itemStack, this.getTitle().getString())) {
            cir.setReturnValue(CroesusGamblingModifier.INSTANCE.modifyTooltip(original));
        }
    }
}
