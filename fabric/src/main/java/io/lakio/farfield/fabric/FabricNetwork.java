package io.lakio.farfield.fabric;

import io.lakio.farfield.net.StreamPayload;
import io.lakio.farfield.platform.services.INetwork;
import io.lakio.farfield.server.StreamServer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * LOD streaming on Fabric (ADR-068). Fabric negotiates the channel without
 * making it mandatory, so players and servers without Farfield still
 * connect. Receivers run on the game thread.
 */
public final class FabricNetwork implements INetwork {

    /** Both sides: the payload in both play directions, the server's receiver. */
    static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(StreamPayload.TYPE, StreamPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamPayload.TYPE, StreamPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StreamPayload.TYPE,
                (payload, context) -> StreamServer.receive(context.player(), payload.data()));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, byte[] message) {
        ServerPlayNetworking.send(player, new StreamPayload(message));
    }

    @Override
    public void sendToServer(byte[] message) {
        ClientPlayNetworking.send(new StreamPayload(message));
    }

    @Override
    public boolean serverStreams() {
        return ClientPlayNetworking.canSend(StreamPayload.TYPE);
    }
}
