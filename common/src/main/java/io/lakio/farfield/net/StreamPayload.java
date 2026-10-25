package io.lakio.farfield.net;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.stream.StreamProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The one custom payload of LOD streaming (ADR-068), both ways: a message of
 * {@link StreamProtocol}, its type in the first byte. Optional on both
 * loaders: players and servers without Farfield still connect.
 */
public record StreamPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<StreamPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Farfield.MOD_ID, "stream"));
    public static final StreamCodec<FriendlyByteBuf, StreamPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeByteArray(payload.data),
            buf -> new StreamPayload(buf.readByteArray(StreamProtocol.MAX_PAYLOAD + 1024)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
