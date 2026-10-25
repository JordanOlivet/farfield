package io.lakio.farfield.platform.services;

import net.minecraft.server.level.ServerPlayer;

/**
 * Sends the {@code StreamPayload} of LOD streaming (ADR-068) on the game
 * connection. Each loader registers the payload and hands what it receives
 * to {@code StreamServer} and {@code StreamClient}.
 */
public interface INetwork {

    void sendToPlayer(ServerPlayer player, byte[] message);

    /** Client only. */
    void sendToServer(byte[] message);

    /** Client only: whether the server of the current connection has Farfield's streaming channel. */
    boolean serverStreams();
}
