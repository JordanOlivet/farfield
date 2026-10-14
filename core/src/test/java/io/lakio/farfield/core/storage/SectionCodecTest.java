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
    void mipOfUniformChildrenIsUniform() {
        MipBuilder mips = new MipBuilder(0, m -> m == 0);
        LodSection[] children = new LodSection[8];
        for (int i = 0; i < 8; i++) {
            children[i] = new LodSection(i < 4 ? 7 : 0); // lower half stone, upper half air
        }
        LodSection parent = mips.build(children);
        assertEquals(7, parent.material(3, 15, 3));
        assertEquals(0, parent.material(3, 16, 3));
        assertEquals(LodSection.BRICK_COUNT, parent.uniformBrickCount());
    }

    @Test
    void mipKeepsMajorityNonAirAndMaxLight() {
        MipBuilder mips = new MipBuilder(0, m -> m == 0);
        LodSection child = new LodSection(0);
        // Cell (0, 0, 0) of the parent: 5 of 8 children non-air (3 x 4, 2 x 9).
        child.set(0, 0, 0, 4);
        child.set(1, 0, 0, 4);
        child.set(0, 1, 0, 4);
        child.set(1, 1, 0, 9);
        child.set(0, 0, 1, 9);
        child.blockLight().set(1, 1, 1, 12);
        // Cell (1, 0, 0): only 3 non-air children -> air.
        child.set(2, 0, 0, 4);
        child.set(3, 0, 0, 4);
        child.set(2, 1, 0, 4);
        LodSection[] children = new LodSection[8];
        children[0] = child;
        LodSection parent = mips.build(children);
        assertEquals(4, parent.material(0, 0, 0));
        assertEquals(12, parent.blockLight().get(0, 0, 0));
        assertEquals(0, parent.material(1, 0, 0));
        assertEquals(15, parent.skyLight().get(20, 20, 20));
    }

    @Test
    void mipParentsEncodeCanonically() {
        // Regression: light written voxel by voxel left packed bricks whose
        // values were all equal, so a decoded copy re-encoded differently.
        MipBuilder mips = new MipBuilder(0, m -> m == 0);
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
