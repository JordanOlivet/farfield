package io.lakio.farfield.forge.mixin;

import io.lakio.farfield.forge.ForgeNetwork;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the server's list of channels ({@code minecraft:register}) beside
 * Forge: Forge 47 drops the last name of a list that does not end with a zero
 * byte, which is how a Fabric server sends it, so a Forge client never saw
 * Farfield's channel on a Fabric server and built its own LODs instead of
 * streaming them (ADR-068).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void farfield$channelsListed(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        // Forge's own accessor of the channel: the vanilla one's name is caught by a Stonecutter rename.
        if (ForgeNetwork.REGISTER.equals(packet.getName())) {
            FriendlyByteBuf buf = packet.getData(); // a copy
            try {
                ForgeNetwork.onRegister(((ClientPacketListener) (Object) this).getConnection(), buf);
            } finally {
                buf.release();
            }
        }
    }
}
