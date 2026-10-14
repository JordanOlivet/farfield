package io.lakio.farfield.core.anvil;

import io.lakio.farfield.core.anvil.TestChunks.Section;
import io.lakio.farfield.core.anvil.TestChunks.State;
import io.lakio.farfield.core.nbt.NbtFormatException;
import io.lakio.farfield.core.nbt.NbtTag;
import io.lakio.farfield.core.nbt.NbtWriter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDecoderTest {

    private final KeyRegistry blocks = new KeyRegistry();
    private final KeyRegistry biomes = new KeyRegistry();
    private final ChunkDecoder decoder = new ChunkDecoder(blocks, biomes);
    private final ChunkData chunk = new ChunkData();

    private void decode(byte[] nbt, int x, int z) {
        assertTrue(decoder.decodeHeader(nbt, 0, nbt.length, x, z, chunk));
        decoder.decodeSections(chunk);
    }

    @Test
    void decodesHeader() {
        decode(TestChunks.chunk(5, -7, "minecraft:full", List.of()), 5, -7);
        assertEquals(5, chunk.chunkX());
        assertEquals(-7, chunk.chunkZ());
        assertEquals(TestChunks.DATA_VERSION_1_21_1, chunk.dataVersion());
        assertEquals("minecraft:full", chunk.status());
        assertTrue(chunk.isFull());
        assertTrue(chunk.isLightOn());
        assertEquals(1234, chunk.lastUpdate());
        assertEquals(0, chunk.sectionCount());
        assertEquals(0, chunk.warnings());
    }

    @Test
    void decodesUniformAndPalettedSections() {
        SplittableRandom random = new SplittableRandom(7);
        // Palette sizes around every bit-width boundary: 4 bits minimum, then
        // 5, 8 and 9 bits (9+ is what the game writes for huge palettes).
        int[] paletteSizes = {2, 16, 17, 256, 257, 4096};
        List<Section> sections = new ArrayList<>();
        sections.add(new Section(-4, new State[]{new State("minecraft:stone")}, null,
                new String[]{"minecraft:plains"}, null, null, null));
        int[][] expectedIndices = new int[paletteSizes.length][];
        for (int s = 0; s < paletteSizes.length; s++) {
            int n = paletteSizes[s];
            State[] palette = new State[n];
            for (int i = 0; i < n; i++) {
                palette[i] = new State("test:block_" + i);
            }
            int[] indices = new int[SectionData.BLOCK_COUNT];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = random.nextInt(n);
            }
            expectedIndices[s] = indices;
            sections.add(new Section(s - 3, palette, indices, new String[]{"minecraft:plains"}, null, null, null));
        }
        decode(TestChunks.chunk(0, 0, "minecraft:full", sections), 0, 0);

        assertEquals(sections.size(), chunk.sectionCount());
        SectionData uniform = chunk.section(0);
        assertEquals(-4, uniform.y());
        assertTrue(uniform.hasBlocks());
        assertTrue(uniform.isUniform());
        assertEquals("minecraft:stone", blocks.key(uniform.blockAt(1234)));

        for (int s = 0; s < paletteSizes.length; s++) {
            SectionData section = chunk.section(s + 1);
            assertEquals(s - 3, section.y());
            assertEquals(paletteSizes[s], section.blockPaletteSize());
            for (int i = 0; i < SectionData.BLOCK_COUNT; i++) {
                assertEquals("test:block_" + expectedIndices[s][i], blocks.key(section.blockAt(i)),
                        "palette " + paletteSizes[s] + ", block " + i);
            }
        }
        assertEquals(0, chunk.warnings());
    }

    @Test
    void canonicalizesBlockStateKeys() {
        State stairsA = new State("minecraft:oak_stairs", "waterlogged", "false", "facing", "north", "shape", "straight", "half", "top");
        State stairsB = new State("minecraft:oak_stairs", "half", "top", "shape", "straight", "facing", "north", "waterlogged", "false");
        decode(TestChunks.chunk(0, 0, "minecraft:full", List.of(
                new Section(0, new State[]{stairsA}, null, null, null, null, null),
                new Section(1, new State[]{stairsB}, null, null, null, null, null))), 0, 0);
        int a = chunk.section(0).blockAt(0);
        int b = chunk.section(1).blockAt(0);
        assertEquals(a, b);
        assertEquals("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]", blocks.key(a));
    }

    @Test
    void decodesBiomesAndLight() {
        int[] biomeIndices = new int[SectionData.BIOME_COUNT];
        for (int i = 0; i < biomeIndices.length; i++) {
            biomeIndices[i] = i % 3;
        }
        byte[] sky = new byte[SectionData.LIGHT_BYTES];
        byte[] block = new byte[SectionData.LIGHT_BYTES];
        sky[0] = (byte) 0xA5; // index 0 -> 5, index 1 -> 10
        block[2047] = (byte) 0xF0; // index 4095 -> 15
        decode(TestChunks.chunk(0, 0, "minecraft:full", List.of(
                new Section(3, new State[]{new State("minecraft:air")}, null,
                        new String[]{"minecraft:plains", "minecraft:river", "minecraft:forest"}, biomeIndices,
                        sky, block),
                // Light-only section above the build height.
                new Section(20, null, null, null, null, sky, null))), 0, 0);

        SectionData s = chunk.section(0);
        assertTrue(s.hasBiomes());
        assertEquals(3, s.biomePaletteSize());
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int i = (y * 4 + z) * 4 + x;
                    String expected = new String[]{"minecraft:plains", "minecraft:river", "minecraft:forest"}[i % 3];
                    assertEquals(expected, biomes.key(s.biomeAt(x, y, z)));
                }
            }
        }
        assertTrue(s.hasSkyLight());
        assertTrue(s.hasBlockLight());
        assertEquals(5, s.skyLightAt(0));
        assertEquals(10, s.skyLightAt(1));
        assertEquals(0, s.skyLightAt(2));
        assertEquals(15, s.blockLightAt(4095));
        assertEquals(0, s.blockLightAt(4094));

        SectionData lightOnly = chunk.section(1);
        assertEquals(20, lightOnly.y());
        assertFalse(lightOnly.hasBlocks());
        assertFalse(lightOnly.hasBiomes());
        assertTrue(lightOnly.hasSkyLight());
        assertFalse(lightOnly.hasBlockLight());
    }

    @Test
    void reusesHolderAcrossChunks() {
        decode(TestChunks.chunk(0, 0, "minecraft:full", List.of(
                new Section(0, new State[]{new State("a:a")}, null, null, null, new byte[2048], new byte[2048]),
                new Section(1, new State[]{new State("a:b")}, null, null, null, null, null))), 0, 0);
        assertEquals(2, chunk.sectionCount());
        decode(TestChunks.chunk(1, 0, "minecraft:full", List.of(
                new Section(5, new State[]{new State("a:c")}, null, null, null, null, null))), 1, 0);
        assertEquals(1, chunk.sectionCount());
        SectionData s = chunk.section(0);
        assertEquals(5, s.y());
        assertFalse(s.hasSkyLight());
        assertFalse(s.hasBiomes());
        assertEquals("a:c", blocks.key(s.blockAt(0)));
    }

    @Test
    void reportsPartialChunksAndMisplacedChunks() {
        byte[] nbt = TestChunks.chunk(3, 4, "minecraft:features", List.of());
        assertTrue(decoder.decodeHeader(nbt, 0, nbt.length, 3, 5, chunk));
        assertEquals("minecraft:features", chunk.status());
        assertFalse(chunk.isFull());
        assertEquals(ChunkData.WARN_MISPLACED, chunk.warnings());
        // The region slot wins over the stored position.
        assertEquals(5, chunk.chunkZ());
    }

    @Test
    void rejectsLegacyFormat() {
        byte[] nbt = new NbtWriter().root()
                .putInt("DataVersion", 2586) // 1.16.5
                .beginCompound("Level").putInt("xPos", 0).putInt("zPos", 0)
                .beginList("Sections", NbtTag.COMPOUND, 0).end()
                .end().toByteArray();
        assertFalse(decoder.decodeHeader(nbt, 0, nbt.length, 0, 0, chunk));
        assertThrows(IllegalStateException.class, () -> decoder.decodeSections(chunk));
    }

    @Test
    void dropsSectionsWithBadDataLength() {
        byte[] nbt = new NbtWriter().root()
                .putInt("DataVersion", TestChunks.DATA_VERSION_1_21_1)
                .putString("Status", "minecraft:full")
                .beginList("sections", NbtTag.COMPOUND, 1)
                .putByte("Y", 0)
                .beginCompound("block_states")
                .beginList("palette", NbtTag.COMPOUND, 2)
                .putString("Name", "a:a").end()
                .putString("Name", "a:b").end()
                .putLongArray("data", new long[10]) // 256 expected
                .end()
                .beginCompound("biomes").beginList("palette", NbtTag.STRING, 1).string("a:p").end()
                .end()
                .end().toByteArray();
        decode(nbt, 0, 0);
        assertEquals(1, chunk.sectionCount());
        assertFalse(chunk.section(0).hasBlocks());
        assertTrue(chunk.section(0).hasBiomes());
        assertEquals(ChunkData.WARN_BAD_DATA_LENGTH, chunk.warnings());
    }

    @Test
    void clampsOutOfRangeIndices() {
        int[] indices = new int[SectionData.BLOCK_COUNT];
        indices[7] = 2; // palette has 2 entries
        Section s = new Section(0, new State[]{new State("a:a"), new State("a:b")}, indices, null, null, null, null);
        decode(TestChunks.chunk(0, 0, "minecraft:full", List.of(s)), 0, 0);
        assertEquals("a:a", blocks.key(chunk.section(0).blockAt(7)));
        assertEquals(ChunkData.WARN_BAD_INDEX, chunk.warnings());
    }

    @Test
    void neverFailsOtherThanCleanlyOnCorruptInput() {
        byte[] nbt = TestChunks.chunk(0, 0, "minecraft:full", List.of(
                new Section(0, new State[]{new State("a:a"), new State("a:b", "k", "v")}, new int[4096],
                        new String[]{"a:p", "a:q"}, new int[64], new byte[2048], new byte[2048])));
        SplittableRandom random = new SplittableRandom(11);
        for (int i = 0; i < 2000; i++) {
            byte[] fuzzed = nbt.clone();
            int flips = 1 + random.nextInt(4);
            for (int f = 0; f < flips; f++) {
                fuzzed[random.nextInt(fuzzed.length)] = (byte) random.nextInt();
            }
            int length = random.nextInt(5) == 0 ? random.nextInt(fuzzed.length) : fuzzed.length;
            try {
                if (decoder.decodeHeader(fuzzed, 0, length, 0, 0, chunk)) {
                    decoder.decodeSections(chunk);
                }
            } catch (NbtFormatException expected) {
                // fine
            }
        }
    }

    @Test
    void packHelperMatchesDecoderLayout() {
        int[] values = new int[SectionData.BLOCK_COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 5;
        }
        long[] packed = TestChunks.pack(values, 4);
        assertEquals(256, packed.length);
        assertArrayEquals(new long[]{0x0432104321043210L}, new long[]{packed[0]});
    }
}
