package io.lakio.farfield.neoforge;

import io.lakio.farfield.client.StreamClient;
import io.lakio.farfield.core.stream.StreamProtocol;
import io.lakio.farfield.net.StreamPayload;
import io.lakio.farfield.platform.services.INetwork;
import io.lakio.farfield.server.StreamServer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * LOD streaming on NeoForge (ADR-068): an optional payload in both play
 * directions, so players and servers without Farfield still connect.
 * Handlers run on the game thread.
 */
public final class NeoForgeNetwork implements INetwork {

    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Integer.toString(StreamProtocol.VERSION)).optional();
        registrar.playBidirectional(StreamPayload.TYPE, StreamPayload.CODEC, NeoForgeNetwork::handle);
    }

    private static void handle(StreamPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            StreamServer.receive(player, payload.data());
        } else {
            StreamClient.receive(payload.data());
        }
    }

    @Override
    public void sendToPlayer(ServerPlayer player, byte[] message) {
        PacketDistributor.sendToPlayer(player, new StreamPayload(message));
    }

    @Override
    public void sendToServer(byte[] message) {
        PacketDistributor.sendToServer(new StreamPayload(message));
    }

    @Override
    public boolean serverStreams() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(StreamPayload.TYPE);
    }
}
