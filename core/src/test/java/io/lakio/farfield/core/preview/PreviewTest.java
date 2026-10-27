package io.lakio.farfield.core.preview;

import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.lod.MipBuilder;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void coarseTreesByAreaMatchBlockByBlock() {
        // A dense forest of every crown shape, with and without snow, on rolling ground, some crowns over a real chunk.
        PreviewTile t = slope();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 70 + (int) (6 * Math.sin(i / 5.0) + 6 * Math.cos(k / 7.0)));
            }
        }
        int[] kinds = {
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 7, 2, 3, PreviewTile.BLOB)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 9, 3, 6, PreviewTile.CONE)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 6, 3, 0, PreviewTile.FLAT)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 12, 0, 0, PreviewTile.COLUMN)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 7, 2, 3, PreviewTile.BLOB, SNOW)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 8, 3, 4, PreviewTile.BLOB, SNOW)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 9, 3, 6, PreviewTile.CONE, SNOW)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 6, 3, 0, PreviewTile.FLAT, SNOW)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 8, 3, 7, PreviewTile.TIERED)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 8, 3, 7, PreviewTile.TIERED, SNOW)),
                t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 7, 2, 5, PreviewTile.TIERED, SNOW))};
        java.util.Random random = new java.util.Random(3);
        for (int n = 0; n < 4000; n++) {
            t.addTree(random.nextInt(PreviewTile.BLOCKS), random.nextInt(PreviewTile.BLOCKS),
                    kinds[random.nextInt(kinds.length)]);
        }
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        PreviewVoxels.Mask mask = (cx, cz) -> cx == 9 && cz == 9;
        for (int level = 1; level <= 4; level++) {
            int size = LodSection.SIZE << level;
            int sy = Math.floorDiv(70, size);
            for (int sx = 0; sx < PreviewTile.BLOCKS / size; sx++) {
                LodSection byArea = PreviewVoxels.build(level, sx, sy, 0, tiles, mask, AIR, -64, -1, -1, -1, 0);
                PreviewVoxels.blockByBlockTrees = true;
                LodSection byBlock;
                try {
                    byBlock = PreviewVoxels.build(level, sx, sy, 0, tiles, mask, AIR, -64, -1, -1, -1, 0);
                } finally {
                    PreviewVoxels.blockByBlockTrees = false;
                }
                for (int y = 0; y < LodSection.SIZE; y++) {
                    for (int z = 0; z < LodSection.SIZE; z++) {
                        for (int x = 0; x < LodSection.SIZE; x++) {
                            assertEquals(byBlock.material(x, y, z), byArea.material(x, y, z),
                                    "level " + level + " section " + sx + " voxel " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
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
    void icebergsAreIceToTheirFoot() {
        // Cell (1, 1) of the slope's sea (ground about 54) rises to 80 as an iceberg.
        PreviewTile t = slope();
        t.setIceberg(ICE);
        for (int k = 1; k <= 2; k++) {
            for (int i = 1; i <= 2; i++) {
                t.setHeight(i, k, 80);
            }
        }
        t.setCell(1, 1, ICE, ICE);
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        assertEquals(ICE, back.iceberg());
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? back : null;
        LodSection high = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        LodSection low = PreviewVoxels.build(0, 0, 1, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(ICE, high.material(5, 79 - 64, 5), "the top");
        assertEquals(AIR, high.material(5, 80 - 64, 5));
        // Cell (1, 1), blocks 4..7, is the iceberg. Column 4 stands at 80 beside column 3 at 76: top 79, filler 76..78, then 75, ice and not the tile's stone.
        assertEquals(ICE, high.material(4, 75 - 64, 5), "under the filler");
        for (int y = 0; y < LodSection.SIZE; y++) {
            for (int z = 4; z <= 7; z++) {
                for (int x = 4; x <= 7; x++) {
                    assertNotEquals(STONE, high.material(x, y, z), "block " + x + "," + (y + 64) + "," + z);
                    assertNotEquals(STONE, low.material(x, y, z), "block " + x + "," + (y + 32) + "," + z);
                }
            }
        }
    }

    @Test
    void plantsGrowOnTheirGroundAtTheChunksDensity() {
        int shortGrass = 9;
        PreviewTile flat = new PreviewTile();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                flat.setHeight(i, k, 70);
            }
        }
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                flat.setCell(cx, cz, GRASS, DIRT);
            }
        }
        flat.setBelow(STONE);
        flat.setWater(WATER, 63);
        flat.setPlantGround(GRASS);
        // 256 per chunk: 1 - 1/e of the ground covered.
        int kind = flat.plantKind(shortGrass);
        for (int c = 0; c < PreviewTile.CHUNKS * PreviewTile.CHUNKS; c++) {
            flat.setPlants(c % PreviewTile.CHUNKS, c / PreviewTile.CHUNKS,
                    new int[]{PreviewTile.plantEntry(kind, 256)});
        }
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? flat : null;
        // Ground up to y 69: plants at 70, in section 2 (y 64..95), voxel y 6.
        LodSection fine = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        int plants = 0;
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int m = fine.material(x, 6, z);
                assertTrue(m == AIR || m == shortGrass);
                plants += m == shortGrass ? 1 : 0;
                assertEquals(GRASS, fine.material(x, 5, z));
            }
        }
        assertTrue(plants > 1024 * 0.55 && plants < 1024 * 0.71, "about 63 % covered: " + plants);
        // The same block gets the same plant whatever the section build.
        assertEquals(fine.material(3, 6, 4), PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0)
                .material(3, 6, 4));
        // Level 2: the top voxel (blocks 68..71) is grass covered by short grass, 3 quarters of 0.63 * 4.
        MipBuilder.Shapes covers = new MipBuilder.Shapes() {
            @Override
            public int shapeOf(int material) {
                return FULL;
            }

            @Override
            public int covered(int ground, int plant, int quarters) {
                return 1000 + quarters * 100 + ground * 10 + plant;
            }
        };
        LodSection coarse = PreviewVoxels.build(2, 0, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0, covers);
        assertEquals(1000 + 300 + GRASS * 10 + shortGrass, coarse.material(0, 17, 0));
        assertEquals(DIRT, coarse.material(0, 16, 0));
    }

    @Test
    void skyLightFadesThroughTheSea() {
        // Column x 1 of the slope: ground up to about 50, water up to 62 (sea 63).
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? slope() : null;
        LodSection fine = PreviewVoxels.build(0, 0, 1, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(14, fine.skyLight().get(1, 62 - 32, 1), "the top water block");
        assertEquals(7, fine.skyLight().get(1, 55 - 32, 1));
        assertEquals(4, fine.skyLight().get(1, 52 - 32, 1));
        assertEquals(15, fine.skyLight().get(1, 63 - 32, 1), "the air above");
        // Level 2: a voxel takes its highest block's light, full when it holds air above the sea.
        LodSection coarse = PreviewVoxels.build(2, 0, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(15, coarse.skyLight().get(0, 15, 0), "blocks 60..63, 63 is air");
        assertEquals(11, coarse.skyLight().get(0, 14, 0), "blocks 56..59: 59's light");
    }

    @Test
    void partlyFilledGroundUnderTheSeaTakesTheWatersLight() {
        // A ground voxel filled only partly shows a neighbor's side above its fill: that face
        // takes the voxel's light, which must be the water's, not full light (light bands).
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? slope() : null;
        int found = 0;
        for (int sy = -1; sy <= 0; sy++) {
            LodSection coarse = PreviewVoxels.build(2, 0, sy, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
            if (coarse == null) {
                continue;
            }
            for (int x = 0; x < LodSection.SIZE; x++) {
                for (int z = 0; z < LodSection.SIZE; z++) {
                    for (int vy = 0; vy < LodSection.SIZE; vy++) {
                        int bottom = sy * 128 + vy * 4;
                        int m = coarse.material(x, vy, z);
                        if (bottom + 4 > 63 || m == AIR || m == WATER || coarse.fill().get(x, vy, z) == 15) {
                            continue;
                        }
                        found++;
                        assertEquals(15 - (63 - (bottom + 3)), coarse.skyLight().get(x, vy, z),
                                "blocks " + bottom + ".." + (bottom + 3));
                    }
                }
            }
        }
        assertTrue(found > 0, "no partly filled ground under the sea in the slope");
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
        // The fluid mask lights the preview's land faces beside it: the real water's light.
        assertEquals(14, hidden.skyLight().get(4, 62 - 32, 4), "the real water's top block");
        assertEquals(0, hidden.skyLight().get(4, 40 - 32, 4), "23 blocks down");
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
    private static final int SNOW = 9;

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

    /** {@link #forest()} with snow on its oak. */
    private static PreviewTile snowyForest() {
        PreviewTile t = slope();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 70);
            }
        }
        t.addTree(16, 16, t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 5, 2, 3, PreviewTile.BLOB, SNOW)));
        return t;
    }

    @Test
    void snowLiesOnTheTopOfEachCrownColumn() {
        PreviewTile t = snowyForest();
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        LodSection s = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        // Crown rows 72 to 75: radius 2 up to 74, radius 1 on top.
        assertEquals(SNOW, s.material(16, 76 - 64, 16), "over the top row, trunk column");
        assertEquals(SNOW, s.material(17, 76 - 64, 15));
        assertEquals(SNOW, s.material(18, 75 - 64, 16), "over the ring the top row leaves open");
        assertEquals(SNOW, s.material(18, 75 - 64, 17));
        assertEquals(LEAVES, s.material(18, 74 - 64, 16));
        assertEquals(AIR, s.material(18, 74 - 64, 18), "no snow on a rounded corner");
        assertEquals(AIR, s.material(18, 75 - 64, 18));
        assertEquals(LEAVES, s.material(17, 75 - 64, 17), "the top row's leaves, under their snow");
        // Level 1 (2 blocks): snow where its layers top half the voxel's columns, as the real mip rule's vote.
        // Voxel (9, 5, 8), blocks 18..19 x 74..75 x 16..17: the ring's snow at 75 over 2 of its 4 columns.
        LodSection l1 = PreviewVoxels.build(1, 0, 1, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(LEAVES, l1.material(8, (72 - 64) / 2, 8), "under other rows");
        assertEquals(LEAVES, l1.material(8, (74 - 64) / 2, 8), "its snow lies in the voxel above");
        assertEquals(SNOW, l1.material(9, (74 - 64) / 2, 8));
        // Level 2 (4 blocks): snow over 4 of the crown voxel's 16 columns, too few.
        LodSection l2 = PreviewVoxels.build(2, 0, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(LEAVES, l2.material(4, 18, 4));
        // The snow raises the summary by one block.
        assertTrue(t.summary().spans(0, 0, 0, 0, 76, 77, 1));
        assertFalse(forest().summary().spans(0, 0, 0, 0, 76, 77, 1));
    }

    @Test
    void spruceTiersWidenDownwardAndTakeSnowOnEachStep() {
        PreviewTile t = slope();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 70);
            }
        }
        // Trunk 70..77, crown rows 70..78: radii from the top 0, 1, 0, 1, 2, 1, 2, 3, 1 (vanilla's spruce).
        t.addTree(16, 16, t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 8, 3, 8, PreviewTile.TIERED, SNOW)));
        LodSection s = PreviewVoxels.build(0, 0, 2, 0, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64,
                -1, -1, -1, 0);
        int[] radii = {0, 1, 0, 1, 2, 1, 2, 3, 1};
        for (int i = 0; i < radii.length; i++) {
            int y = 78 - i;
            int center = radii[i] == 0 && y < 78 ? LOG : LEAVES;
            assertEquals(center, s.material(16 + radii[i], y - 64, 16), "row " + y + " reaches " + radii[i]);
            assertTrue(s.material(17 + radii[i], y - 64, 16) != LEAVES, "row " + y + " ends at " + radii[i]);
        }
        assertEquals(AIR, s.material(19, 71 - 64, 19), "rounded corners, as vanilla's spruce");
        assertEquals(AIR, s.material(17, 77 - 64, 17), "a plus at radius 1");
        assertEquals(LEAVES, s.material(19, 71 - 64, 18));
        // Snow over the top of each column: the top, then each row wider than every row above it.
        assertEquals(SNOW, s.material(16, 79 - 64, 16));
        assertEquals(SNOW, s.material(17, 78 - 64, 16), "radius 1 under the top");
        assertEquals(SNOW, s.material(18, 75 - 64, 16), "the first tier's base");
        assertEquals(SNOW, s.material(19, 72 - 64, 16), "the widest row");
        assertEquals(AIR, s.material(18, 73 - 64, 16), "radius 2 at 72 lies under radius 2 at 74: no snow");
    }

    @Test
    void columnsTakeNoSnowAndSnowNeverReplacesLeaves() {
        PreviewTile t = snowyForest();
        t.addTree(40, 40, t.kind(new PreviewTile.TreeKind(LOG, -1, 3, 0, 0, PreviewTile.COLUMN, SNOW)));
        // A taller tree beside the oak whose leaves fill where the oak's snow would lie (18, 75, 16).
        t.addTree(20, 16, t.kind(new PreviewTile.TreeKind(LOG, LEAVES, 6, 2, 3, PreviewTile.BLOB)));
        LodSection s = PreviewVoxels.build(0, 0, 2, 0, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64,
                -1, -1, -1, 0);
        LodSection column = PreviewVoxels.build(0, 1, 2, 1, (x, z) -> x == 0 && z == 0 ? t : null, null, AIR, -64,
                -1, -1, -1, 0);
        assertEquals(LOG, column.material(40 - 32, 72 - 64, 40 - 32));
        assertEquals(AIR, column.material(40 - 32, 73 - 64, 40 - 32));
        assertEquals(LEAVES, s.material(18, 75 - 64, 16));
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

    @Test
    void tileFilesMirrorAnotherStore() throws IOException {
        PreviewStore server = new PreviewStore(dir.resolve("server"));
        server.write(4, -1, slope());
        byte[] file = server.readFile(4, -1);
        assertNotNull(file);
        assertNull(server.readFile(0, 0));
        long version = PreviewStore.version(file);
        assertTrue(version >= 0);
        assertEquals(version, PreviewStore.version(server.readFile(4, -1)));

        PreviewStore client = new PreviewStore(dir.resolve("client"));
        List<Long> heard = new ArrayList<>();
        client.addListener(heard::add);
        client.write(4, -1, PreviewStore.decodeFile(file));
        assertEquals(List.of(PreviewStore.key(4, -1)), heard);
        assertNotNull(client.summary(4, -1));
        assertEquals(slope().sample(100, 7), client.read(4, -1).sample(100, 7));
        assertEquals(version, PreviewStore.version(client.readFile(4, -1)));
        assertThrows(IOException.class, () -> PreviewStore.decodeFile(new byte[]{1, 2}));
        assertThrows(IOException.class, () -> PreviewStore.decodeFile(new byte[]{1, 2, 3, 4, 5, 6}));
    }

    @Test
    void remappedTilesNameTheMirrorsIds() {
        PreviewTile t = new PreviewTile();
        t.setBelow(1);
        t.setCell(0, 0, 2, 3);
        t.setBiome(0, 0, 0);
        t.setPlantGround(2);
        int kind = t.kind(new PreviewTile.TreeKind(4, 5, 6, 2, 3, PreviewTile.BLOB, -1));
        t.plantKind(5);
        int[] materials = {10, 11, 12, 13, 14, 15};
        int[] biomes = {7};
        t.remapIds(materials, biomes);
        assertEquals(11, t.below());
        assertEquals(12, t.top(0, 0));
        assertEquals(13, t.filler(0, 0));
        assertEquals(7, t.biome(0, 0));
        assertEquals(12, t.plantGround());
        assertEquals(14, t.treeKind(kind).trunk());
        assertEquals(15, t.treeKind(kind).leaves());
        assertEquals(-1, t.treeKind(kind).snow());
        assertEquals(15, t.plantMaterial(0));
        assertThrows(IllegalArgumentException.class, () -> t.remapIds(new int[3], biomes));
    }

    @Test
    void aRuggedSeaFloorShowsNoHollowToTheWater() {
        // Heights jumping by up to 12 blocks between samples, under the sea: no water voxel
        // may touch, by a face, an edge or a corner, the hollow under a preview column
        // (the sky showed through such edges as white specks on underwater ridges).
        PreviewTile t = new PreviewTile();
        java.util.SplittableRandom random = new java.util.SplittableRandom(7);
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 34 + random.nextInt(13));
            }
        }
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                t.setCell(cx, cz, SAND, SAND);
            }
        }
        t.setBelow(STONE);
        t.setWater(WATER, 63);
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        int sections = 4;
        byte[] g = new byte[(sections * 32) * 64 * (sections * 32)];
        int w = sections * 32;
        for (int sz = 0; sz < sections; sz++) {
            for (int sx = 0; sx < sections; sx++) {
                for (int sy = 0; sy < 2; sy++) {
                    LodSection s = PreviewVoxels.build(0, sx, sy, sz, tiles, null, AIR, -64, -1, -1, -1, 0);
                    if (s == null) {
                        continue;
                    }
                    for (int z = 0; z < 32; z++) {
                        for (int y = 0; y < 32; y++) {
                            for (int x = 0; x < 32; x++) {
                                int m = s.material(x, y, z);
                                g[((sz * 32 + z) * 64 + sy * 32 + y) * w + sx * 32 + x] =
                                        (byte) (m == AIR ? 0 : m == WATER ? 1 : 2);
                            }
                        }
                    }
                }
            }
        }
        int contacts = 0;
        for (int z = 1; z < w - 1; z++) {
            for (int x = 1; x < w - 1; x++) {
                for (int y = 1; y < 63; y++) {
                    if (g[(z * 64 + y) * w + x] != 1) {
                        continue;
                    }
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int nx = x + dx;
                                int ny = y + dy;
                                int nz = z + dz;
                                if (g[(nz * 64 + ny) * w + nx] != 0) {
                                    continue;
                                }
                                for (int yy = ny + 1; yy < 64; yy++) {
                                    if (g[(nz * 64 + yy) * w + nx] == 2) {
                                        contacts++;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(0, contacts, "water touching a hollow under the ground");
    }
}
