package io.lakio.farfield.core.bench;

import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.lod.NibbleBricks;
import io.lakio.farfield.core.util.ByteWriter;

/**
 * Encode-only variant of {@code SectionCodec} for spike S1 measurements:
 * brick indices as whole bytes (u8, or u16 above 255 palette entries)
 * instead of bit-packed, optionally with an "up" filter (0 when a voxel
 * equals the one below it in the brick, index + 1 otherwise), which turns
 * layered terrain into runs of zeros for the byte compressor.
 */
final class ByteCodec {

    private ByteCodec() {
    }

    static void encode(LodSection s, ByteWriter out, boolean up) {
        out.writeByte(1);
        int n = s.paletteSize();
        out.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            out.writeVarInt(s.paletteEntry(i));
        }
        boolean wide = n + (up ? 1 : 0) > 256;
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            out.writeVarInt(s.isUniform(b) ? s.uniformIndex(b) << 1 : 1);
        }
        lightMap(s.skyLight(), out);
        lightMap(s.blockLight(), out);
        int[] cells = s.biomeCells();
        if (cells == null) {
            out.writeVarInt((s.uniformBiome() + 1) << 1);
        } else {
            out.writeVarInt(1);
            for (int c : cells) {
                out.writeVarInt(c + 1);
            }
        }
        int[] indices = new int[LodSection.BRICK_VOLUME];
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            long[] data = s.brickData(b);
            if (data == null) {
                continue;
            }
            for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
                indices[v] = LodSection.unpack(data, s.bits(), v);
            }
            for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
                int value = indices[v];
                if (up) {
                    value = v >= 64 && indices[v - 64] == value ? 0 : value + 1;
                }
                out.writeByte(value);
                if (wide) {
                    out.writeByte(value >>> 8);
                }
            }
        }
        lightData(s.skyLight(), out);
        lightData(s.blockLight(), out);
    }

    private static void lightMap(NibbleBricks light, ByteWriter out) {
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            out.writeByte(light.isUniform(b) ? light.uniformValue(b) : 0x10);
        }
    }

    private static void lightData(NibbleBricks light, ByteWriter out) {
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            byte[] d = light.brickData(b);
            if (d != null) {
                out.writeBytes(d, 0, d.length);
            }
        }
    }
}
