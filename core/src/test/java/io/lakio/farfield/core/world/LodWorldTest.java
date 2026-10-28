package io.lakio.farfield.core.world;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.SectionData;
import io.lakio.farfield.core.lod.LodSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodWorldTest {

    @TempDir
    Path dir;

    /** Chunk (cx, cz) with section -1 all stone and section 0 dirt in its lower half (y 0..7). */
    private static ChunkData chunk(LodWorld world, int cx, int cz) {
        int stone = world.blockStates().id("minecraft:stone");
        int dirt = world.blockStates().id("minecraft:dirt");
        int plains = world.biomes().id("minecraft:plains");
        ChunkData chunk = new ChunkData();
        chunk.beginLive(cx, cz);
        SectionData below = chunk.addLiveSection(-1);
        below.setBlockPalette(new int[]{stone}, 1);
        below.setBiomePalette(new int[]{plains}, 1);
        byte[] dark = new byte[SectionData.LIGHT_BYTES];
        below.setSkyLight(dark);
        SectionData ground = chunk.addLiveSection(0);
        ground.setBlockPalette(new int[]{world.air(), dirt}, 2);
        short[] indices = ground.blockIndicesForWrite();
        for (int i = 0; i < SectionData.BLOCK_COUNT; i++) {
            indices[i] = (short) ((i >> 8) < 8 ? 1 : 0);
        }
        ground.setBiomePalette(new int[]{plains}, 1);
        chunk.addLiveSection(1); // no blocks: ignored
        return chunk;
    }

    @Test
    void recordsMirrorAWorldAndTheirVersionMovesOnRewrite() throws IOException {
        Path server = dir.resolve("server");
        Path client = dir.resolve("client");
        long key = LodKey.pack(0, 1, 0, -1);
        try (LodWorld from = LodWorld.open(server, MaterialTable.SIMPLE);
             LodWorld to = LodWorld.open(client, MaterialTable.SIMPLE)) {
            from.ingest(chunk(from, 3, -1));
            from.flush(0);
            long version = from.version(key);
            assertTrue(version >= 0);
            assertEquals(-1, from.version(LodKey.pack(0, 9, 9, 9)));
            List<Long> regionKeys = new ArrayList<>();
            List<Long> regionVersions = new ArrayList<>();
            from.forEachVersion(0, 0, -1, (k, v) -> {
                regionKeys.add(k);
                regionVersions.add(v);
            });
            assertEquals(Set.of(key, LodKey.pack(0, 1, -1, -1)), Set.copyOf(regionKeys));
            for (int i = 0; i < regionKeys.size(); i++) {
                assertEquals(from.version(regionKeys.get(i)), regionVersions.get(i));
            }

            // The mirror takes the keys in the server's id order, then the records as they are.
            for (int id = 0; id < from.blockStates().size(); id++) {
                assertEquals(id, to.blockStates().id(from.blockStates().key(id)));
            }
            for (int id = 0; id < from.biomes().size(); id++) {
                assertEquals(id, to.biomes().id(from.biomes().key(id)));
            }
            List<Long> heard = new ArrayList<>();
            to.addListener(heard::add);
            byte[] record = from.readRecord(key);
            to.putRecord(key, record, 0, record.length);
            assertEquals(List.of(key), heard);
            LodSection mirrored = to.read(key);
            assertEquals(from.blockStates().id("minecraft:dirt"), mirrored.material(16, 7, 16));
            assertEquals(from.read(key).material(16, 8, 16), mirrored.material(16, 8, 16));
            assertTrue(!to.hasDirtyMips(), "a mirror never rebuilds coarse levels");

            // A mirror with a key of its own first: the server's ids land elsewhere, translated on receipt.
            try (LodWorld other = LodWorld.open(dir.resolve("other"), MaterialTable.SIMPLE)) {
                int own = other.blockStates().id("farfield:client_only");
                io.lakio.farfield.core.stream.KeyTranslation blocks =
                        new io.lakio.farfield.core.stream.KeyTranslation(other.blockStates());
                io.lakio.farfield.core.stream.KeyTranslation biomes =
                        new io.lakio.farfield.core.stream.KeyTranslation(other.biomes());
                String[] serverBlocks = new String[from.blockStates().size()];
                for (int id = 0; id < serverBlocks.length; id++) {
                    serverBlocks[id] = from.blockStates().key(id);
                }
                String[] serverBiomes = new String[from.biomes().size()];
                for (int id = 0; id < serverBiomes.length; id++) {
                    serverBiomes[id] = from.biomes().key(id);
                }
                assertTrue(blocks.add(0, serverBlocks));
                assertTrue(biomes.add(0, serverBiomes));
                assertTrue(blocks.add(1, java.util.Arrays.copyOfRange(serverBlocks, 1, 2)), "keys held again");
                assertFalse(blocks.add(serverBlocks.length + 1, new String[]{"x:gap"}));
                assertFalse(blocks.add(0, new String[]{"x:other"}));
                LodSection received = other.decodeRecord(key, record);
                assertTrue(received.remapIds(blocks.map(), biomes.map()));
                other.putSection(key, received);
                LodSection translated = other.read(key);
                assertEquals("minecraft:dirt", other.blockStates().key(translated.material(16, 7, 16)));
                assertTrue(translated.material(16, 7, 16) != own);
                LodSection original = from.read(key);
                assertEquals(from.biomes().key(original.biome(4, 1, 4)),
                        other.biomes().key(translated.biome(4, 1, 4)));
            }

            // Another chunk in the same section: a new record, a new version.
            from.ingest(chunk(from, 2, -1));
            from.flush(0);
            assertTrue(from.version(key) != version);
            assertTrue(from.epoch() != 0 && from.epoch() == from.epoch());
        }
    }

    @Test
    void ingestWritesTheChunkOctantsAndSkipsUnchangedSections() throws IOException {
        try (LodWorld world = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            List<Long> written = new ArrayList<>();
            world.addListener(written::add);
            int stone = world.blockStates().id("minecraft:stone");
            int dirt = world.blockStates().id("minecraft:dirt");

            world.ingest(chunk(world, 3, -1));
            world.flush(0);
            assertEquals(2, world.sectionsWritten());
            LodSection below = world.read(LodKey.pack(0, 1, -1, -1));
            assertNotNull(below);
            assertEquals(stone, below.material(16, 16, 16));
            assertEquals(stone, below.material(31, 31, 31));
            assertEquals(world.air(), below.material(15, 31, 31), "other chunks' octants stay air");
            assertEquals(world.air(), below.material(31, 15, 31), "chunk section -2 is absent");
            assertEquals(0, below.skyLight().get(20, 20, 20));
            assertEquals(15, below.skyLight().get(0, 20, 20));
            LodSection ground = world.read(LodKey.pack(0, 1, 0, -1));
            assertEquals(dirt, ground.material(16, 7, 16));
            assertEquals(world.air(), ground.material(16, 8, 16));
            assertEquals(world.biomes().id("minecraft:plains"), ground.biome(5, 0, 5));

            world.ingest(chunk(world, 3, -1));
            world.flush(0);
            assertEquals(2, world.sectionsWritten());
            assertEquals(2, world.sectionsUnchanged());
            assertEquals(Set.of(LodKey.pack(0, 1, -1, -1), LodKey.pack(0, 1, 0, -1)), Set.copyOf(written));
            assertEquals(2, written.size());
        }
    }

    @Test
    void aRebuildQueuesEveryCoarseLevelApartAndBuildsItInSlices() throws IOException {
        try (LodWorld world = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            // Chunks far apart: four level-0 columns of two sections (y -1 and 0), under eight
            // level-1 parents.
            for (int i = 0; i < 4; i++) {
                world.ingest(chunk(world, i * 4, 0));
            }
            world.flush(0);
            world.buildMips();
            long top = world.version(LodKey.pack(4, 0, -1, 0));
            assertTrue(top >= 0);

            assertEquals(8, world.rebuildCoarseLevels());
            int[] pending = world.coarseLevelsPending();
            assertEquals(8, pending[1]);
            assertTrue(pending[4] >= 1, "every level is queued, not only level 1");
            assertTrue(world.rebuilding());
            // The rebuild is apart from new chunks' work: building that leaves it as it was.
            assertTrue(!world.hasDirtyMips());
            world.ingest(chunk(world, 40, 0));
            world.flush(0);
            world.buildMips();
            assertEquals(8, world.coarseLevelsPending()[1]);
            // Slices of time until done, the finest level first.
            int slices = 0;
            while (world.rebuilding()) {
                world.buildRebuildSlice(null, 1_000_000L);
                slices++;
                assertTrue(slices < 10_000);
            }
            assertEquals(0, world.mipsToUpgrade());
            // Built again from the same level 0: the same data, so the top section is unchanged.
            assertEquals(top, world.version(LodKey.pack(4, 0, -1, 0)));
        }
    }

    @Test
    void aMirrorNeverRebuildsItsCoarseLevels() throws IOException {
        try (LodWorld world = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            world.ingest(chunk(world, 0, 0));
            world.flush(0);
            world.buildMips();
        }
        // An older mip rules version on disk: a store rebuilds at open, a mirror does not.
        java.nio.file.Files.writeString(dir.resolve("mips.version"), "1");
        try (LodWorld mirror = LodWorld.openMirror(dir, MaterialTable.SIMPLE)) {
            assertTrue(!mirror.rebuilding());
            assertEquals(0, mirror.mipsToUpgrade());
        }
        try (LodWorld store = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            assertTrue(store.rebuilding());
        }
    }

    @Test
    void buildMipsPropagatesToTheTopLevel() throws IOException {
        try (LodWorld world = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            int stone = world.blockStates().id("minecraft:stone");
            world.ingest(chunk(world, 0, 0));
            assertNull(world.read(LodKey.pack(0, 0, -1, 0)), "cached, not written yet");
            assertEquals(2, world.pendingSections());
            world.flush(0);
            assertTrue(world.hasDirtyMips());
            int written = world.buildMips(Executors.newFixedThreadPool(2));
            assertTrue(written >= 4, "at least one parent per level: " + written);
            assertTrue(!world.hasDirtyMips());
            // Level 1 section (0, -1, 0) spans blocks -64..-1; chunk section -1 is
            // its top quarter (y 24..31 in level-1 voxels), x and z 0..7.
            LodSection level1 = world.read(LodKey.pack(1, 0, -1, 0));
            assertEquals(stone, level1.material(0, 31, 0));
            assertEquals(stone, level1.material(7, 24, 7));
            assertEquals(world.air(), level1.material(8, 31, 0));
            assertNotNull(world.read(LodKey.pack(4, 0, -1, 0)));
            assertEquals(0, world.buildMips(), "nothing left to build");
        }
    }

    @Test
    void reopenKeepsKeysAndSections() throws IOException {
        int stone;
        LodSection before;
        try (LodWorld world = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            world.blockStates().id("minecraft:bedrock");
            world.ingest(chunk(world, -5, 7));
            world.flush(0);
            stone = world.blockStates().id("minecraft:stone");
            before = world.read(LodKey.pack(0, -3, -1, 3));
        }
        assertTrue(Files.readString(dir.resolve("block_states.txt")).startsWith("minecraft:air\n"));
        try (LodWorld world = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            assertEquals(stone, world.blockStates().id("minecraft:stone"));
            LodSection after = world.read(LodKey.pack(0, -3, -1, 3));
            assertEquals(before.material(16, 16, 16), after.material(16, 16, 16));
            assertEquals(stone, after.material(16, 16, 16));
            assertNull(world.read(LodKey.pack(0, 100, 0, 100)));
        }
    }
}
