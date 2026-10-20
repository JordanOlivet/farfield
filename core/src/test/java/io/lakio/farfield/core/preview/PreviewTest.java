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
    void coarseTilesFillTheirSamplesBetween() {
        // Heights computed every 16 blocks (every 4th sample): a plane rising 2 blocks per sample along X.
        PreviewTile t = new PreviewTile();
        for (int k = 0; k < PreviewTile.SAMPLES; k += 4) {
            for (int i = 0; i < PreviewTile.SAMPLES; i += 4) {
                t.setHeight(i, k, 60 + 2 * i);
            }
        }
        t.fillBetweenSamples(4);
        t.setGrid(16);
        assertEquals(62, t.sample(1, 0));
        assertEquals(66, t.sample(3, 7));
        assertEquals(60 + 2 * 128, t.sample(128, 127));
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        assertEquals(16, back.grid());
        assertEquals(16, back.summary().grid());
        assertEquals(PreviewTile.GRID, new PreviewTile().grid());
    }

    private static final int ICE = 8;

    @Test
    void frozenCellsTopTheSeaWithIce() {
        // Cells 0..1 x 0..1 of the slope's sea (ground 50 to 57, sea 63) freeze.
        PreviewTile t = slope();
        t.setIce(ICE);
        for (int cz = 0; cz < 2; cz++) {
            for (int cx = 0; cx < 2; cx++) {
                t.setFrozen(cx, cz, true);
            }
        }
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        assertTrue(back.frozen(1, 1));
        assertFalse(back.frozen(2, 1));
        assertEquals(ICE, back.ice());
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? back : null;
        // Level 0: the top block (62) is ice, the water under it stays; an open cell keeps water on top.
        LodSection fine = PreviewVoxels.build(0, 0, 1, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(ICE, fine.material(1, 62 - 32, 1));
        assertEquals(WATER, fine.material(1, 61 - 32, 1));
        assertEquals(WATER, fine.material(1, 62 - 32, 9));
        // Level 2: the voxel holding block 62 (60..63) is ice, at the sea's fill.
        LodSection coarse = PreviewVoxels.build(2, 0, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(ICE, coarse.material(0, 15, 0));
        assertEquals(11, coarse.fill().get(0, 15, 0), "3 blocks of 4 (60..62): 12/16");
        assertEquals(WATER, coarse.material(0, 14, 0));
        assertEquals(WATER, coarse.material(0, 15, 4));
        // Without ice material nothing freezes.
        t.setIce(-1);
        assertFalse(t.frozen(0, 0));
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

    private static final int LOG = 6;
    private static final int LEAVES = 7;

    /** Flat land at 70 (ground top at 69) with one oak-like tree at block (16, 16). */
    private static PreviewTile forest() {
        PreviewTile t = slope();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 70);
            }
        }
        int oak = t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 5, 2, 3, PreviewTile.BLOB));
        t.addTree(16, 16, oak);
        return t;
    }

    @Test
    void treesStandOnTheSurfaceBlockByBlock() {
        PreviewTile t = forest();
        LodSection s = PreviewVoxels.build(0, 0, 2, 0, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64,
                -1, -1, -1, 0);
        // Trunk from 70 to 74, crown rows 72 to 75 (radius 2, then 1 on top).
        assertEquals(LOG, s.material(16, 70 - 64, 16));
        assertEquals(LOG, s.material(16, 74 - 64, 16));
        assertEquals(LEAVES, s.material(16, 75 - 64, 16));
        assertEquals(LEAVES, s.material(18, 73 - 64, 16));
        assertEquals(AIR, s.material(18, 73 - 64, 18), "rounded corner");
        assertEquals(AIR, s.material(18, 75 - 64, 16), "narrower top row");
        assertEquals(AIR, s.material(16, 76 - 64, 16));
        assertEquals(GRASS, s.material(16, 69 - 64, 16), "the ground under the trunk");
    }

    @Test
    void coarseLevelsKeepWellFilledTreeVoxels() {
        PreviewTile t = forest();
        // Level 2 (4 blocks): the crown fills voxel (4, 18, 4), blocks 16..19 x 72..75 x 16..19, well over a quarter.
        LodSection s = PreviewVoxels.build(2, 0, 0, 0, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64,
                -1, -1, -1, 0);
        assertEquals(LEAVES, s.material(4, 18, 4));
        // Level 4 (16 blocks): one tree is far less than a quarter of a voxel.
        LodSection far = PreviewVoxels.build(4, 0, 0, 0, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64,
                -1, -1, -1, 0);
        assertEquals(GRASS, far.material(1, 4, 1), "the ground voxel, no crown over it");
        assertEquals(AIR, far.material(1, 5, 1));
    }

    @Test
    void treesRoundTripAndRaiseTheSummary() {
        PreviewTile t = forest();
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        int[] trees = back.trees(1, 1);
        assertEquals(1, trees.length);
        assertEquals(new PreviewTile.TreeKind(LOG, LEAVES, 5, 2, 3, PreviewTile.BLOB), back.treeKind(trees[0] >>> 8));
        assertEquals(0, back.trees(0, 0).length);
        // The tree's top (76) counts for its chunk and the ones around.
        assertTrue(back.summary().spans(0, 0, 0, 0, 72, 80, 1));
        assertFalse(back.summary().spans(5, 5, 5, 5, 72, 80, 1));
    }

    @Test
    void columnsFlatCapsAndBuildingsStand() {
        PreviewTile t = forest();
        int cactus = t.kind(new PreviewTile.TreeKind(LOG, -1, 3, 0, 0, PreviewTile.COLUMN));
        int mushroom = t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 4, 2, 2, PreviewTile.FLAT));
        t.addTree(40, 40, cactus);
        t.addTree(60, 40, mushroom);
        t.addBuilding(new PreviewTile.Building(80, 80, 5, 7, 69, 74, SAND, STONE));
        LodSection s = PreviewVoxels.build(0, 1, 2, 1, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64,
                -1, -1, -1, 0);
        // Cactus: 70..72, nothing above.
        assertEquals(LOG, s.material(40 - 32, 72 - 64, 40 - 32));
        assertEquals(AIR, s.material(40 - 32, 73 - 64, 40 - 32));
        // Flat cap: one layer of radius 2 at 74 over a trunk 70..73.
        assertEquals(LOG, s.material(60 - 32, 73 - 64, 40 - 32));
        assertEquals(LEAVES, s.material(62 - 32, 74 - 64, 40 - 32));
        assertEquals(AIR, s.material(60 - 32, 75 - 64, 40 - 32));
        // Building: walls from 69 to 73, roof at 74, over x 80..84 and z 80..86 (section 1, 2, 1 starts at 32, 64, 32).
        LodSection house = PreviewVoxels.build(0, 2, 2, 2, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64,
                -1, -1, -1, 0);
        assertEquals(SAND, house.material(80 - 64, 71 - 64, 86 - 64));
        assertEquals(STONE, house.material(84 - 64, 74 - 64, 80 - 64));
        assertEquals(AIR, house.material(85 - 64, 72 - 64, 80 - 64));
        assertTrue(t.summary().spans(5, 5, 5, 5, 72, 80, 1), "the building raises its chunk");
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        assertEquals(t.buildings(), back.buildings());
        assertEquals(PreviewTile.FLAT, back.treeKind(mushroom).shape());
    }

    @Test
    void treesOfRealChunksAreLeftOut() {
        PreviewTile t = forest();
        LodSection s = PreviewVoxels.build(0, 0, 2, 0, (x, z) -> x == 0 && z == 0 ? t : null,
                (cx, cz) -> cx == 1 && cz == 1, AIR, -64, -1, -1, -1, 0);
        assertEquals(AIR, s.material(16, 72 - 64, 16));
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
        // Layers along Z (the last one) and Y, with only the columns they need.
        LodSection back = PreviewVoxels.build(1, 0, 0, 0, tiles, null, AIR, -64, -1, -1, 2, 31);
        // Level-1 voxel row 27 holds blocks 54 and 55: the slope's surface on part of the section.
        LodSection floor = PreviewVoxels.build(1, 0, 0, 0, tiles, null, AIR, -64, -1, -1, 1, 27);
        for (int a = 0; a < LodSection.SIZE; a++) {
            for (int b = 0; b < LodSection.SIZE; b++) {
                assertEquals(whole.material(a, b, 31), back.material(a, b, 31));
                assertEquals(whole.material(a, 27, b), floor.material(a, 27, b));
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
