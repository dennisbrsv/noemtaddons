package dev.noemt.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.noemt.client.event.EventBus;
import dev.noemt.client.event.impl.MainThreadPacketReceivedEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @WrapOperation(
        method = "handleBundlePacket",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"
        )
    )
    private void wrapPacketHandle(Packet packet, PacketListener listener, Operation<Void> original) {
        if (EventBus.post(new MainThreadPacketReceivedEvent.Pre(packet))) return;
        original.call(packet, listener);
        EventBus.post(new MainThreadPacketReceivedEvent.Post(packet));
    }
}
