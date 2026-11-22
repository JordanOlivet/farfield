package io.lakio.farfield.net;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.compat.Mc;
import io.lakio.farfield.core.stream.StreamProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * The one custom payload of LOD streaming (ADR-068), both ways: a message of
 * {@link StreamProtocol}, its type in the first byte. 1.20.1 has no payload
 * types: a channel id and the bytes on a {@link FriendlyByteBuf}, which each
 * loader sends on its own channel API. Optional on both loaders: players and
 * servers without Farfield still connect.
 */
public record StreamPayload(byte[] data) {

    /** The channel of the payload. */
    public static final ResourceLocation ID = Mc.id(Farfield.MOD_ID, "stream");

    public void write(FriendlyByteBuf buf) {
        buf.writeByteArray(data);
    }

    public static StreamPayload read(FriendlyByteBuf buf) {
        return new StreamPayload(buf.readByteArray(StreamProtocol.MAX_PAYLOAD + 1024));
    }
}
