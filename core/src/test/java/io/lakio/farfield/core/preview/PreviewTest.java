package io.lakio.farfield.core.preview;

import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.util.ByteReader;
import io.lakio.farfield.core.util.ByteWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewTest {

    private static final int AIR = 0;
    private static final int GRASS = 1;
    private static final int DIRT = 2;
    private static final int STONE = 3;
    private static final int WATER = 4;
    private static final int SAND = 5;

    @TempDir
    Path dir;

    /** A slope rising one block per block along X from y 50 at x 0, sea at 63, grass on land, sand under water. */
    private static PreviewTile slope() {
        PreviewTile t = new PreviewTile();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 50 + i * PreviewTile.GRID);
            }
        }
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                boolean wet = 50 + cx * PreviewTile.GRID < 63;
                t.setCell(cx, cz, wet ? SAND : GRASS, wet ? SAND : DIRT);
            }
        }
        for (int c = 0; c < PreviewTile.CHUNKS * PreviewTile.CHUNKS; c++) {
            t.setBiome(c % PreviewTile.CHUNKS, c / PreviewTile.CHUNKS, 7);
        }
        t.setBelow(STONE);
        t.setWater(WATER, 63);
        return t;
    }

    @Test
    void heightsInterpolateBetweenSamples() {
        PreviewTile t = slope();
        // Block 0's center lies at x 0.5: 50.5, rounded up; block 10's at 10.5.
        assertEquals(51, t.height(0, 0));
        assertEquals(61, t.height(10, 3));
        assertEquals(50 + 511 + 1, t.height(511, 511));
    }

    @Test
    void tilesRoundTripThroughTheirEncoding() {
        PreviewTile t = slope();
        t.setHeight(3, 4, -20);
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                assertEquals(t.sample(i, k), back.sample(i, k));
            }
        }
        assertEquals(SAND, back.top(0, 0));
        assertEquals(DIRT, back.filler(100, 5));
        assertEquals(7, back.biome(31, 31));
        assertEquals(STONE, back.below());
        assertEquals(WATER, back.water());
        assertEquals(63, back.seaLevel());
    }

    @Test
    void levelZeroColumnsStandOnTheInterpolatedSurface() {
        PreviewTile t = slope();
        // Section (0, 1, 0) at level 0: blocks 0..31 on X and Z, 32..63 on Y.
        LodSection s = PreviewVoxels.build(0, 0, 1, 0, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64, -1, -1, -1, 0);
        assertNotNull(s);
        // Column x 4: surface 55 (blocks up to 54), sand on top, sand filler, water up to 62.
        assertEquals(SAND, s.material(4, 54 - 32, 0));
        assertEquals(WATER, s.material(4, 55 - 32, 0));
        assertEquals(WATER, s.material(4, 62 - 32, 0));
        assertEquals(AIR, s.material(4, 63 - 32, 0));
        // Column x 20 on land: top 70 is above this section; the skin goes one block under the lowest neighbor.
        assertEquals(AIR, s.material(20, 0, 0));
        assertEquals(7, s.biome(0, 0, 0));
    }

    @Test
    void columnsShowTopFillerAndGroundOverASkin() {
        // A flat land at 70 with one pit at 40 in the middle of section (0, 1, 0) at level 0.
        PreviewTile t = slope();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, i == 4 && k == 4 ? 40 : 70);
            }
        }
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                t.setCell(cx, cz, GRASS, DIRT);
            }
        }
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        // Flat land: only the top block, the skin stops one block under the neighbors' surface.
        LodSection flat = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(GRASS, flat.material(20, 69 - 64, 20));
        assertEquals(AIR, flat.material(20, 68 - 64, 20));
        // The pit's side: down to one block under the lowest neighbor, filler then ground.
        int h = t.height(17, 16);
        int lowest = Math.min(Math.min(t.height(16, 16), t.height(18, 16)), Math.min(t.height(17, 15), t.height(17, 17)));
        assertTrue(h - lowest >= 5, "a steep side: " + h + " over " + lowest);
        LodSection side = PreviewVoxels.build(0, 0, 1, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(GRASS, side.material(17, h - 1 - 32, 16));
        assertEquals(DIRT, side.material(17, h - 4 - 32, 16));
        assertEquals(STONE, side.material(17, h - 5 - 32, 16));
        assertEquals(STONE, side.material(17, lowest - 1 - 32, 16));
        assertEquals(AIR, side.material(17, lowest - 2 - 32, 16), "nothing under the skin");
    }

    @Test
    void coarseColumnsTakeTheHighestSampleAndTheirFill() {
        PreviewTile t = slope();
        // Level 4 (16 blocks), section (0, 0, 0): voxel column 0 covers samples 0..4: highest 66.
        LodSection s = PreviewVoxels.build(4, 0, 0, 0, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(GRASS, s.material(0, 4, 0));
        assertEquals(1, s.fill().get(0, 4, 0), "66 - 64 = 2 blocks: 2/16");
        assertEquals(DIRT, s.material(0, 3, 0), "the filler reaches into the voxel below");
        assertEquals(AIR, s.material(0, 2, 0), "under the skin: one voxel below the lowest neighbor (66)");
        assertEquals(15, s.fill().get(0, 3, 0));
    }

    @Test
    void realChunksAndMissingTilesStayEmpty() {
        PreviewTile t = slope();
        LodSection s = PreviewVoxels.build(0, 0, 1, 0, (x, z) -> x == 0 && z == 0 ? t : null,
                (cx, cz) -> cx == 0 && cz == 0, AIR, -64, -1, -1, -1, 0);
        assertEquals(AIR, s.material(4, 54 - 32, 4), "chunk 0, 0 is real");
        // Chunk 0, 0 is real: sea floor at 40, water up to 63.
        PreviewVoxels.Mask sea = new PreviewVoxels.Mask() {
            @Override
            public boolean real(int cx, int cz) {
                return cx == 0 && cz == 0;
            }

            @Override
            public int ground(int cx, int cz) {
                return 40;
            }

            @Override
            public int fluidTop(int cx, int cz) {
                return 63;
            }
        };
        LodSection hidden = PreviewVoxels.build(0, 0, 1, 0, (x, z) -> x == 0 && z == 0 ? t : null, sea, AIR, -64,
                9, 8, -1, 0);
        assertEquals(AIR, hidden.material(4, 38 - 32, 4), "under the real ground's voxel");
        assertEquals(9, hidden.material(4, 39 - 32, 4), "real ground");
        assertEquals(8, hidden.material(4, 40 - 32, 4), "real fluid");
        assertEquals(8, hidden.material(4, 62 - 32, 4));
        assertEquals(AIR, hidden.material(4, 63 - 32, 4));
        assertEquals(SAND, hidden.material(4, 54 - 32, 20));
        assertNull(PreviewVoxels.build(0, 40, 1, 0, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64, -1, -1, -1, 0));
    }

    @Test
    void columnsBesideARealSeaGoDownToItsFloor() {
        PreviewTile t = slope();
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        PreviewVoxels.Mask deep = new PreviewVoxels.Mask() {
            @Override
            public boolean real(int cx, int cz) {
                return cx == 0 && cz == 0;
            }

            @Override
            public int ground(int cx, int cz) {
                return 20;
            }

            @Override
            public int fluidTop(int cx, int cz) {
                return 63;
            }
        };
        // Column x 16, z 5 (chunk 1, 0) stands at 67, beside the real chunk 0, 0 whose floor is at 20.
        LodSection low = PreviewVoxels.build(0, 0, 0, 0, tiles, deep, AIR, -64, 9, 8, -1, 0);
        assertEquals(STONE, low.material(16, 25, 5), "the cliff goes down to the real floor");
        assertEquals(STONE, low.material(16, 19, 5));
        assertEquals(AIR, low.material(16, 18, 5));
        // A column with only preview neighbors keeps its thin skin.
        assertEquals(AIR, low.material(16, 25, 20));
    }

    @Test
    void aLayerBuildMatchesTheWholeSection() {
        PreviewTile t = slope();
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        LodSection whole = PreviewVoxels.build(1, 0, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        LodSection layer = PreviewVoxels.build(1, 0, 0, 0, tiles, null, AIR, -64, -1, -1, 0, 0);
        for (int y = 0; y < LodSection.SIZE; y++) {
            for (int z = 0; z < LodSection.SIZE; z++) {
                assertEquals(whole.material(0, y, z), layer.material(0, y, z));
                assertEquals(AIR, layer.material(1, y, z));
            }
        }
    }

    @Test
    void summariesTellWhichSectionsHoldSomething() {
        PreviewTile.Summary s = slope().summary();
        // Chunk 0: samples 0..4, heights 50..66; the sea at 63.
        assertTrue(s.spans(0, 0, 0, 0, 32, 64, 1));
        assertFalse(s.spans(0, 0, 0, 0, 96, 128, 1), "above the surface and the sea");
        assertFalse(s.spans(0, 0, 0, 0, -64, 0, 1), "buried");
    }

    @Test
    void theStoreKeepsTilesAndSummariesAcrossReopening() throws IOException {
        List<Long> written = new ArrayList<>();
        PreviewStore store = new PreviewStore(dir);
        store.addListener(written::add);
        store.write(-3, 2, slope());
        assertEquals(List.of(PreviewStore.key(-3, 2)), written);
        assertEquals(-3, PreviewStore.tileX(written.get(0)));
        assertEquals(2, PreviewStore.tileZ(written.get(0)));

        PreviewStore reopened = new PreviewStore(dir);
        assertTrue(reopened.contains(-3, 2));
        assertFalse(reopened.contains(0, 0));
        assertNotNull(reopened.summary(-3, 2));
        assertEquals(slope().sample(100, 7), reopened.read(-3, 2).sample(100, 7));
        assertNull(reopened.read(5, 5));
    }
}
