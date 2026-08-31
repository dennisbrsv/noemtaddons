package dev.noemt.client.mixin;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(AvatarRenderState.class)
public class MixinAvatarRenderState implements IAvatarRenderState {
    @Unique
    private UUID noemt$uuid;
    @Unique
    private String noemt$playerName;

    @Override
    public UUID noemt$getUuid() {
        return this.noemt$uuid;
    }

    @Override
    public void noemt$setUuid(UUID uuid) {
        this.noemt$uuid = uuid;
    }

    @Override
    public String noemt$getPlayerName() {
        return this.noemt$playerName;
    }

    @Override
    public void noemt$setPlayerName(String name) {
        this.noemt$playerName = name;
    }
}
