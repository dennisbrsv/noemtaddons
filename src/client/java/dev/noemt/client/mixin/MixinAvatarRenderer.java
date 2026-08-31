package dev.noemt.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.noemt.client.features.render.PlayerSize;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(AvatarRenderer.class)
public abstract class MixinAvatarRenderer {
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL")
    )
    private void noemt$onExtractRenderState(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (state instanceof IAvatarRenderState accessor) {
            UUID uuid = avatar.getUUID();
            accessor.noemt$setUuid(uuid);
            String name = null;
            if (avatar.getProfile() != null && avatar.getProfile().partialProfile() != null) {
                name = avatar.getProfile().partialProfile().name();
            }
            if (name == null || name.isEmpty()) {
                name = avatar.getName().getString();
            }
            accessor.noemt$setPlayerName(name);
        }
    }

    @Inject(
        method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("TAIL")
    )
    private void noemt$onScale(AvatarRenderState state, PoseStack poseStack, CallbackInfo ci) {
        PlayerSize.applyScaleHook(state, poseStack);
    }
}
