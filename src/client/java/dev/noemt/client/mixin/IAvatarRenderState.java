package dev.noemt.client.mixin;

import java.util.UUID;

public interface IAvatarRenderState {
    UUID noemt$getUuid();
    void noemt$setUuid(UUID uuid);
    String noemt$getPlayerName();
    void noemt$setPlayerName(String name);
}
