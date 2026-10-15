package io.lakio.farfield.core.storage;

import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.util.ByteWriter;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionCodecTest {

    private final SectionCodec codec = new SectionCodec();

    static LodSection randomSection(SplittableRandom random, int materials) {
        LodSection s = new LodSection(0);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    if (y < 12 || random.nextInt(20) == 0) {
                        s.set(x, y, z, 1 + random.nextInt(materials));
                    }
                    if (y < 16) {
                        s.skyLight().set(x, y, z, random.nextInt(16));
                    }
                    if (random.nextInt(50) == 0) {
                        s.blockLight().set(x, y, z, random.nextInt(16));
                    }
                }
            }
        }
        for (int c = 0; c < 64; c++) {
            s.setBiome(random.nextInt(8), random.nextInt(8), random.nextInt(8), 3 + random.nextInt(5));
        }
        s.compact();
        return s;
    }

    static void assertSame(LodSection a, LodSection b) {
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    assertEquals(a.material(x, y, z), b.material(x, y, z));
                    assertEquals(a.skyLight().get(x, y, z), b.skyLight().get(x, y, z));
                    assertEquals(a.blockLight().get(x, y, z), b.blockLight().get(x, y, z));
                }
            }
        }
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(a.biome(x, y, z), b.biome(x, y, z));
                }
            }
        }
    }

    private LodSection roundTrip(LodSection s) {
        ByteWriter out = new ByteWriter(1024);
        codec.encode(s, out);
        return codec.decode(out.array(), 0, out.length());
    }

    @Test
    void roundTripsUniformAndRandomSections() {
        LodSection empty = new LodSection(5);
        LodSection back = roundTrip(empty);
        assertSame(empty, back);
        assertEquals(LodSection.BRICK_COUNT, back.uniformBrickCount());

        SplittableRandom random = new SplittableRandom(1);
        for (int materials : new int[]{1, 3, 20, 400}) {
            LodSection s = randomSection(random, materials);
            assertSame(s, roundTrip(s));
        }
    }

    @Test
    void roundTripsFill() {
        LodSection s = randomSection(new SplittableRandom(3), 5);
        s.fill().set(1, 2, 3, 4);
        s.fill().set(30, 31, 0, 0);
        LodSection back = roundTrip(s);
        assertEquals(4, back.fill().get(1, 2, 3));
        assertEquals(0, back.fill().get(30, 31, 0));
        assertEquals(15, back.fill().get(5, 5, 5));
    }

    @Test
    void readsVersionOneAsFull() {
        // A version-2 encoding without fill data, turned into version 1 by
        // dropping its fill map (64 bytes of 15 right after the block light map).
        LodSection s = new LodSection(1);
        s.skyLight().fill(14);
        s.blockLight().fill(3);
        ByteWriter out = new ByteWriter(1024);
        codec.encode(s, out);
        byte[] v2 = out.toByteArray();
        int start = -1;
        for (int i = 0; i + 128 <= v2.length && start < 0; i++) {
            boolean match = true;
            for (int j = 0; j < 64 && match; j++) {
                match = v2[i + j] == 3 && v2[i + 64 + j] == 15;
            }
            if (match) {
                start = i + 64;
            }
        }
        assertTrue(start > 0, "fill map not found");
        byte[] v1 = new byte[v2.length - 64];
        System.arraycopy(v2, 0, v1, 0, start);
        System.arraycopy(v2, start + 64, v1, start, v2.length - start - 64);
        v1[0] = 1;
        LodSection back = codec.decode(v1, 0, v1.length);
        assertEquals(14, back.skyLight().get(7, 7, 7));
        assertEquals(3, back.blockLight().get(7, 7, 7));
        assertEquals(15, back.fill().get(7, 7, 7));
    }

    @Test
    void uniformSectionIsTiny() {
        ByteWriter out = new ByteWriter(16);
        codec.encode(new LodSection(1), out);
        assertTrue(out.length() < 300, out.length() + " bytes");
    }

    @Test
    void rejectsCorruptData() {
        ByteWriter out = new ByteWriter(1024);
        codec.encode(randomSection(new SplittableRandom(2), 5), out);
        byte[] data = out.toByteArray();
        assertThrows(IllegalStateException.class, () -> codec.decode(data, 0, data.length - 1));
        byte[] badVersion = data.clone();
        badVersion[0] = 9;
        assertThrows(IllegalStateException.class, () -> codec.decode(badVersion, 0, badVersion.length));
    }

    @Test
    void mipParentsEncodeCanonically() {
        // Regression: light written voxel by voxel left packed bricks whose
        // values were all equal, so a decoded copy re-encoded differently.
        MipBuilder mips = new MipBuilder(0, m -> m == 0 ? MipBuilder.Shapes.EMPTY : MipBuilder.Shapes.FULL);
        LodSection child = new LodSection(0);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    child.skyLight().set(x, y, z, 14);
                    if (y < 4) {
                        child.set(x, y, z, 3);
                    }
                }
            }
        }
        LodSection[] children = new LodSection[8];
        children[0] = child;
        LodSection parent = mips.build(children);
        ByteWriter first = new ByteWriter(1024);
        codec.encode(parent, first);
        LodSection back = codec.decode(first.array(), 0, first.length());
        ByteWriter second = new ByteWriter(1024);
        codec.encode(back, second);
        assertEquals(first.length(), second.length());
        assertTrue(java.util.Arrays.equals(first.array(), 0, first.length(), second.array(), 0, second.length()));
        assertEquals(LodSection.BRICK_COUNT, parent.skyLight().uniformBrickCount());
    }
}
