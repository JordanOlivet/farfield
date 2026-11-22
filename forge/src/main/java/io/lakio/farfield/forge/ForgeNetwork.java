package io.lakio.farfield.forge;

import io.lakio.farfield.client.StreamClient;
import io.lakio.farfield.core.stream.StreamProtocol;
import io.lakio.farfield.net.StreamPayload;
import io.lakio.farfield.platform.services.INetwork;
import io.lakio.farfield.server.StreamServer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * LOD streaming on Forge 47 (ADR-068): one channel, the payload as bytes,
 * versioned by the stream protocol. Optional both ways: a side without
 * Farfield (absent) or a vanilla one is accepted, so players and servers
 * without Farfield still connect. Handlers run on the game thread.
 */
public final class ForgeNetwork implements INetwork {

    private static final String VERSION = Integer.toString(StreamProtocol.VERSION);

    /** The same version, or no Farfield on the other side. */
    private static final Predicate<String> ACCEPTED = v -> VERSION.equals(v)
            || NetworkRegistry.ABSENT.version().equals(v) || NetworkRegistry.ACCEPTVANILLA.equals(v);

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            StreamPayload.ID, () -> VERSION, ACCEPTED, ACCEPTED);

    static void register() {
        CHANNEL.messageBuilder(StreamPayload.class, 0)
                .encoder(StreamPayload::write)
                .decoder(StreamPayload::read)
                .consumerMainThread(ForgeNetwork::handle)
                .add();
    }

    private static void handle(StreamPayload payload, Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        if (sender != null) {
            StreamServer.receive(sender, payload.data());
        } else {
            StreamClient.receive(payload.data());
        }
    }

    @Override
    public void sendToPlayer(ServerPlayer player, byte[] message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StreamPayload(message));
    }

    @Override
    public void sendToServer(byte[] message) {
        CHANNEL.sendToServer(new StreamPayload(message));
    }

    @Override
    public boolean serverStreams() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && CHANNEL.isRemotePresent(connection.getConnection());
    }
}
