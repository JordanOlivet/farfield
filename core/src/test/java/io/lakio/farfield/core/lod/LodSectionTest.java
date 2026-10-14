package io.lakio.farfield.core.lod;

import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.ChunkDecoder;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.anvil.TestChunks;
import io.lakio.farfield.core.anvil.TestChunks.Section;
import io.lakio.farfield.core.anvil.TestChunks.State;
import io.lakio.farfield.core.util.Bits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodSectionTest {

    @Test
    void transposeMatchesNaive() {
        SplittableRandom random = new SplittableRandom(3);
        for (int round = 0; round < 100; round++) {
            int[] a = new int[40];
            for (int i = 0; i < a.length; i++) {
                a[i] = random.nextInt();
            }
            int[] t = a.clone();
            Bits.transpose32(t, 5);
            for (int i = 0; i < 32; i++) {
                for (int j = 0; j < 32; j++) {
                    assertEquals((a[5 + i] >>> j) & 1, (t[5 + j] >>> i) & 1);
                }
            }
            assertEquals(a[0], t[0]);
            assertEquals(a[39], t[39]);
        }
    }

    @Test
    void bitsGrowInPowersOfTwo() {
        int[][] cases = {{1, 1}, {2, 1}, {3, 2}, {4, 2}, {5, 4}, {16, 4}, {17, 8}, {256, 8}, {257, 16}, {65536, 16}};
        for (int[] c : cases) {
            assertEquals(c[1], LodSection.bitsFor(c[0]), "palette " + c[0]);
        }
    }

    @Test
    void setAndGetAcrossPaletteGrowth() {
        SplittableRandom random = new SplittableRandom(4);
        LodSection section = new LodSection(1000);
        int[] expected = new int[32 * 32 * 32];
        Arrays.fill(expected, 1000);
        // 300 materials force repacks at 2, 4, 8 and 16 bits.
        for (int i = 0; i < 60_000; i++) {
            int x = random.nextInt(32);
            int y = random.nextInt(32);
            int z = random.nextInt(32);
            int material = 1000 + random.nextInt(1 + i / 200);
            section.set(x, y, z, material);
            expected[(y * 32 + z) * 32 + x] = material;
        }
        assertEquals(16, section.bits());
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    assertEquals(expected[(y * 32 + z) * 32 + x], section.material(x, y, z));
                }
            }
        }
    }

    @Test
    void uniformBricksCostNothing() {
        LodSection section = new LodSection(0);
        assertEquals(LodSection.BRICK_COUNT, section.uniformBrickCount());
        section.set(1, 2, 3, 7);
        assertFalse(section.isUniform(0));
        section.set(1, 2, 3, 0);
        section.compact();
        assertTrue(section.isUniform(0));
        section.fillBrick(LodSection.brickIndex(3, 3, 3), 7);
        assertEquals(7, section.material(31, 31, 31));
        assertEquals(0, section.material(23, 31, 31));
    }

    @Test
    void writesDecodedChunkSections() {
        KeyRegistry blocks = new KeyRegistry();
        int air = blocks.id("minecraft:air");
        int[] indices = new int[4096];
        for (int i = 0; i < indices.length; i++) {
            int y = i >> 8;
            indices[i] = y < 5 ? 1 : (i % 7 == 0 ? 2 : 0);
        }
        byte[] nbt = TestChunks.chunk(0, 0, "minecraft:full", List.of(
                new Section(0, new State[]{new State("minecraft:air"), new State("minecraft:stone"),
                        new State("minecraft:dirt")}, indices, null, null, null, null),
                new Section(1, new State[]{new State("minecraft:stone")}, null, null, null, null, null)));
        ChunkDecoder decoder = new ChunkDecoder(blocks, new KeyRegistry());
        ChunkData chunk = new ChunkData();
        decoder.decodeHeader(nbt, 0, nbt.length, 0, 0, chunk);
        decoder.decodeSections(chunk);

        LodSection section = new LodSection(air);
        // Chunk section y=0 into the lower octant at (1, 0, 0), y=1 above it.
        section.writeChunkSection(1, 0, 0, chunk.section(0));
        section.writeChunkSection(1, 1, 0, chunk.section(1));
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int i = (y << 8) | (z << 4) | x;
                    String key = new String[]{"minecraft:air", "minecraft:stone", "minecraft:dirt"}[indices[i]];
                    assertEquals(key, blocks.key(section.material(16 + x, y, z)));
                    assertEquals("minecraft:stone", blocks.key(section.material(16 + x, 16 + y, z)));
                    assertEquals(air, section.material(x, y, z));
                }
            }
        }
        // The stone floor (y < 5 is one brick row of 8 only partly): lower bricks are mixed,
        // the upper octant is uniform stone.
        assertTrue(section.isUniform(LodSection.brickIndex(2, 2, 0)));
        assertFalse(section.isUniform(LodSection.brickIndex(2, 0, 0)));
    }

    @Test
    void writesLightAndBiomesOfChunkSections() {
        KeyRegistry blocks = new KeyRegistry();
        KeyRegistry biomes = new KeyRegistry();
        byte[] sky = new byte[2048];
        byte[] block = new byte[2048];
        for (int i = 0; i < 4096; i++) {
            int y = i >> 8;
            int value = Math.min(15, y);
            sky[i >> 1] |= (byte) (value << ((i & 1) << 2));
        }
        block[(5 << 8 | 6 << 4 | 7) >> 1] = (byte) (9 << 4); // index 5*256+6*16+7 is odd: high nibble
        int[] biomeIndices = new int[64];
        biomeIndices[(2 * 4 + 1) * 4 + 3] = 1; // cell x=3, y=2, z=1
        byte[] nbt = TestChunks.chunk(0, 0, "minecraft:full", List.of(
                new Section(0, new State[]{new State("minecraft:air")}, null,
                        new String[]{"minecraft:plains", "minecraft:river"}, biomeIndices, sky, block)));
        ChunkDecoder decoder = new ChunkDecoder(blocks, biomes);
        ChunkData chunk = new ChunkData();
        decoder.decodeHeader(nbt, 0, nbt.length, 0, 0, chunk);
        decoder.decodeSections(chunk);

        LodSection section = new LodSection(blocks.id("minecraft:air"));
        section.writeChunkSection(0, 1, 1, chunk.section(0));
        for (int y = 0; y < 16; y++) {
            assertEquals(Math.min(15, y), section.skyLight().get(4, 16 + y, 20));
        }
        assertEquals(15, section.skyLight().get(4, 3, 3)); // untouched octant keeps the default
        assertEquals(9, section.blockLight().get(7, 16 + 5, 16 + 6));
        assertEquals(0, section.blockLight().get(6, 16 + 5, 16 + 6));
        assertEquals("minecraft:river", biomes.key(section.biome(3, 4 + 2, 4 + 1)));
        assertEquals("minecraft:plains", biomes.key(section.biome(2, 4 + 2, 4 + 1)));
        assertEquals(-1, section.biome(0, 0, 0));
        // Sky light varies with y in the written octant, not in the others.
        assertFalse(section.skyLight().isUniform(LodSection.brickIndex(0, 2, 2)));
        assertTrue(section.skyLight().isUniform(LodSection.brickIndex(0, 0, 0)));
    }
}
