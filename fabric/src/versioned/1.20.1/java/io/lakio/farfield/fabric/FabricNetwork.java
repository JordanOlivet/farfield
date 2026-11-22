package io.lakio.farfield.fabric;

import io.lakio.farfield.client.StreamClient;
import io.lakio.farfield.net.StreamPayload;
import io.lakio.farfield.platform.services.INetwork;
import io.lakio.farfield.server.StreamServer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * LOD streaming on Fabric for Minecraft 1.20.1 (ADR-068): Fabric API 0.92
 * has no payload types, so the payload goes on a channel of its own as
 * bytes. Fabric negotiates the channel without making it mandatory, so
 * players and servers without Farfield still connect. Its receivers run on
 * the network thread: the bytes are read there, the message handled on the
 * game thread.
 */
public final class FabricNetwork implements INetwork {

    /** Both sides: the server's receiver. */
    static void registerCommon() {
        ServerPlayNetworking.registerGlobalReceiver(StreamPayload.ID, (server, player, handler, buf, sender) -> {
            StreamPayload payload = StreamPayload.read(buf);
            server.execute(() -> StreamServer.receive(player, payload.data()));
        });
    }

    /** Client only: the client's receiver. */
    static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(StreamPayload.ID, (client, handler, buf, sender) -> {
            StreamPayload payload = StreamPayload.read(buf);
            client.execute(() -> StreamClient.receive(payload.data()));
        });
    }

    @Override
    public void sendToPlayer(ServerPlayer player, byte[] message) {
        ServerPlayNetworking.send(player, StreamPayload.ID, buf(message));
    }

    @Override
    public void sendToServer(byte[] message) {
        ClientPlayNetworking.send(StreamPayload.ID, buf(message));
    }

    @Override
    public boolean serverStreams() {
        return ClientPlayNetworking.canSend(StreamPayload.ID);
    }

    private static FriendlyByteBuf buf(byte[] message) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        new StreamPayload(message).write(buf);
        return buf;
    }
}
