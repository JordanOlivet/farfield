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
    void kelpStandsOnTheSeaFloorAtItsHeightUnderTheSurface() {
        int kelp = 31;
        PreviewTile sea = new PreviewTile();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                sea.setHeight(i, k, 50);
            }
        }
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                sea.setCell(cx, cz, SAND, SAND);
            }
        }
        sea.setBelow(STONE);
        sea.setWater(WATER, 63);
        // 256 columns per chunk, 20 blocks tall: capped one block under the surface (y 62).
        int kind = sea.plantKind(kelp);
        for (int c = 0; c < PreviewTile.CHUNKS * PreviewTile.CHUNKS; c++) {
            sea.setSeaPlants(c % PreviewTile.CHUNKS, c / PreviewTile.CHUNKS,
                    new int[]{PreviewTile.seaPlantEntry(kind, 256, 20)});
        }
        ByteWriter out = new ByteWriter(1024);
        sea.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        assertEquals(1, back.seaPlants(3, 4).length);
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? back : null;
        // Sea floor up to y 49, water from 50; section 1 holds y 32..63.
        LodSection fine = PreviewVoxels.build(0, 0, 1, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        int columns = 0;
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                if (fine.material(x, 50 - 32, z) == kelp) {
                    columns++;
                    assertEquals(kelp, fine.material(x, 61 - 32, z));
                    assertEquals(WATER, fine.material(x, 62 - 32, z)); // under the surface block
                } else {
                    assertEquals(WATER, fine.material(x, 50 - 32, z));
                }
            }
        }
        // 1 - 1/e of the floor, as the plants on land.
        assertTrue(columns > 1024 * 0.55 && columns < 1024 * 0.72, "kelp columns " + columns);
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
        // Plants hidden (plan item D.3): bare ground on every level.
        PreviewVoxels.Shown noPlants = new PreviewVoxels.Shown(true, false, true, m -> false, m -> false);
        LodSection bare = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0, null, false, noPlants);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                assertEquals(AIR, bare.material(x, 6, z));
            }
        }
        LodSection bareCoarse = PreviewVoxels.build(2, 0, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0, covers, false,
                noPlants);
        assertEquals(GRASS, bareCoarse.material(0, 17, 0));
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
        assertEquals(STONE, s.material(0, 2, 0));
        assertEquals(AIR, s.material(0, 1, 0), "under the skin: one voxel below the neighbors' lowest block (50)");
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
    void theStorePersistsTheKeysATileNamesBeforeWritingIt() throws IOException {
        List<String> order = new ArrayList<>();
        PreviewStore store = new PreviewStore(dir.resolve("keys"), () -> order.add("keys"));
        store.addListener(tile -> order.add("tile"));
        store.write(0, 0, slope());
        assertEquals(List.of("keys", "tile"), order);
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
        PreviewTile t = ruggedSeaFloor();
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
                                g[((sz * 32 + z) * 64 + sy * 32 + y) * w + sx * 32 + x] = kind(s, x, y, z);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(0, hollowContacts(g, w, w), "water touching a hollow under the ground");
    }

    @Test
    void aCoarseSectionShowsNoHollowToAFinerOneBeside() {
        // A level-1 section beside level-0 ones, as on the seam between two levels of
        // detail: a coarse column stands as high as the highest of its blocks, so the finer
        // terrain beside it can lie lower than the coarse column's floor. The view then passed
        // under the coarse column, into its hollow (light-coloured specks along the seam).
        PreviewTile t = ruggedSeaFloor();
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        int w = 96; // blocks 32 to 127 along X: level 0 up to 63, level 1 from 64
        int d = 64;
        byte[] g = new byte[w * 64 * d];
        for (int sz = 0; sz < 2; sz++) {
            for (int sy = 0; sy < 2; sy++) {
                LodSection s = PreviewVoxels.build(0, 1, sy, sz, tiles, null, AIR, -64, -1, -1, -1, 0);
                for (int z = 0; z < 32; z++) {
                    for (int y = 0; y < 32; y++) {
                        for (int x = 0; x < 32; x++) {
                            g[((sz * 32 + z) * 64 + sy * 32 + y) * w + x] = kind(s, x, y, z);
                        }
                    }
                }
            }
        }
        LodSection coarse = PreviewVoxels.build(1, 1, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        for (int z = 0; z < 64; z++) {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    g[(z * 64 + y) * w + 32 + x] = kind(coarse, x / 2, y / 2, z / 2);
                }
            }
        }
        assertEquals(0, hollowContacts(g, w, d), "water touching a hollow under the coarse ground");
    }

    @Test
    void groundAndHollowsUnderTheSeaTakeTheWatersLight() {
        // Ground and the hollows under it are never meant to be seen, but a face beside them
        // takes their light: at full light, the side of a coarse column over finer terrain on
        // a seam between levels lit far brighter than the sea floor around it.
        PreviewTile t = ruggedSeaFloor();
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        for (int level = 0; level <= 2; level++) {
            int s = 1 << level;
            int sy = level == 0 ? 1 : 0; // the section holding the sea floor
            LodSection section = PreviewVoxels.build(level, 1, sy, 1, tiles, null, AIR, -64, -1, -1, -1, 0);
            for (int z = 0; z < 32; z++) {
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 32; x++) {
                        int top = (sy * 32 + y) * s + s - 1;
                        if (top < 63) {
                            assertEquals(Math.max(0, 15 - (63 - top)), section.skyLight().get(x, y, z),
                                    "level " + level + ", voxel " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
    }

    /** Heights jumping by up to 12 blocks between samples, under a sea at 63. */
    private static PreviewTile ruggedSeaFloor() {
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
        return t;
    }

    /** 0 air, 1 water, 2 ground; a missing section is air. */
    private static byte kind(LodSection s, int x, int y, int z) {
        int m = s == null ? AIR : s.material(x, y, z);
        return (byte) (m == AIR ? 0 : m == WATER ? 1 : 2);
    }

    /**
     * Water blocks touching, by a face, an edge or a corner, an air block with ground
     * above it in its column: a hollow under the terrain. Grid of {@code w} by 64 by
     * {@code d} blocks, X fastest.
     */
    private static int hollowContacts(byte[] g, int w, int d) {
        int contacts = 0;
        for (int z = 1; z < d - 1; z++) {
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
        return contacts;
    }

    /**
     * Flat land at y 64 (the first free block 65) with one feature column:
     * a trunk from 66 to 75 (voxels 33 to 37, as {@code PreviewFeatures}
     * puts the blocks y 66 to 75 in 2-block voxels), leaves from 76 to 79,
     * at blocks x 10..11, z 20..21 of the tile.
     */
    private static PreviewTile featureTile() {
        PreviewTile t = new PreviewTile();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 65);
            }
        }
        for (int c = 0; c < PreviewTile.CELLS * PreviewTile.CELLS; c++) {
            t.setCell(c % PreviewTile.CELLS, c / PreviewTile.CELLS, GRASS, DIRT);
        }
        t.setBelow(STONE);
        int fx = (10 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        int fz = (20 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        t.setFeatureColumn(fx, fz, new int[]{PreviewTile.featureRun(33, 5), LOG, PreviewTile.featureRun(38, 2), LEAVES});
        return t;
    }

    @Test
    void featuresUnderTheSeaTakeThePlaceOfItsWater() {
        int coral = 40;
        PreviewTile t = new PreviewTile();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 40);
            }
        }
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                t.setCell(cx, cz, SAND, SAND);
            }
        }
        t.setBelow(STONE);
        t.setWater(WATER, 63);
        // A reef run at y 40 to 43 over block (10, 20), under 20 blocks of sea (runs count 2-block voxels).
        int fx = (10 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        int fz = (20 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        t.setFeatureColumn(fx, fz, new int[]{PreviewTile.featureRun(20, 2), coral});
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        LodSection fine = PreviewVoxels.build(0, 0, 1, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(coral, fine.material(10, 40 - 32, 20));
        assertEquals(coral, fine.material(10, 43 - 32, 20));
        assertEquals(WATER, fine.material(10, 44 - 32, 20));
        // Level 1 (2-block voxels), section y 0 holding y 0 to 63: the reef's voxels too.
        LodSection coarse = PreviewVoxels.build(1, 0, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(coral, coarse.material(5, 20, 10)); // y 0..63 at level 1: voxel y 20 holds y 40, 41
    }

    @Test
    void theShownKindsOfFeaturesLeaveTheOthersOut() {
        int rock = 41;
        PreviewTile t = featureTile();
        // A boulder at blocks x 30..31, z 20..21, y 66 to 69, beside the tree at x 10.
        int fx = (30 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        int fz = (20 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        t.setFeatureColumn(fx, fz, new int[]{PreviewTile.featureRun(33, 2), rock});
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        java.util.function.IntPredicate tree = m -> m == LOG || m == LEAVES;
        int snow = 42;
        java.util.function.IntPredicate cover = m -> m == snow;
        // Snow on the crown (y 80, touching the leaves' run up to 79) and on the boulder's top (y 70).
        int tfx = (10 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        t.setFeatureColumn(tfx, fz, new int[]{PreviewTile.featureRun(33, 5), LOG, PreviewTile.featureRun(38, 2), LEAVES,
                PreviewTile.featureRun(40, 1), snow});
        t.setFeatureColumn(fx, fz, new int[]{PreviewTile.featureRun(33, 2), rock, PreviewTile.featureRun(35, 1), snow});
        // Section y 2 holds blocks 64 to 95: the trunk at y 66, the boulder at y 66.
        LodSection all = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0, null, false,
                PreviewVoxels.Shown.ALL);
        assertEquals(LOG, all.material(10, 2, 20));
        assertEquals(rock, all.material(30, 2, 20));
        assertEquals(snow, all.material(10, 16, 20), "snow on the crown");
        LodSection noTrees = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0, null, false,
                new PreviewVoxels.Shown(false, true, true, tree, cover));
        assertEquals(AIR, noTrees.material(10, 2, 20));
        assertEquals(AIR, noTrees.material(10, 12, 20), "leaves at y 76");
        assertEquals(AIR, noTrees.material(10, 16, 20), "the crown's snow goes with it");
        assertEquals(rock, noTrees.material(30, 2, 20));
        assertEquals(snow, noTrees.material(30, 6, 20), "the boulder's snow stays");
        LodSection noOthers = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0, null, false,
                new PreviewVoxels.Shown(true, true, false, tree, cover));
        assertEquals(LOG, noOthers.material(10, 2, 20));
        assertEquals(snow, noOthers.material(10, 16, 20), "the crown keeps its snow");
        assertEquals(AIR, noOthers.material(30, 2, 20));
        assertEquals(AIR, noOthers.material(30, 6, 20), "the boulder's snow goes with it");
        // A light source goes with the run holding it; one in no run stays.
        PreviewVoxels.Shown noTreesShown = new PreviewVoxels.Shown(false, true, true, tree, cover);
        assertFalse(PreviewVoxels.lightShown(t, 0, 0, 10, 77, 20, noTreesShown), "in the leaves");
        assertTrue(PreviewVoxels.lightShown(t, 0, 0, 30, 67, 20, noTreesShown), "in the boulder");
        assertTrue(PreviewVoxels.lightShown(t, 0, 0, 100, 60, 100, noTreesShown), "in no run");
    }

    @Test
    void featureColumnsRoundTripAndRaiseTheSummary() {
        PreviewTile t = featureTile();
        // A run of the margin, west of the tile: stored, but no chunk of this tile under it.
        t.setFeatureColumn(0, 0, new int[]{PreviewTile.featureRun(-40, 3), LEAVES});
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        int fx = (10 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        int fz = (20 + PreviewTile.FEATURE_MARGIN) / PreviewTile.FEATURE_VOXEL;
        int[] runs = back.featureColumn(fx, fz);
        assertEquals(4, runs.length);
        assertEquals(66, PreviewTile.featureRunY(runs[0]));
        assertEquals(10, PreviewTile.featureRunBlocks(runs[0]));
        assertEquals(LOG, runs[1]);
        assertEquals(76, PreviewTile.featureRunY(runs[2]));
        assertEquals(LEAVES, runs[3]);
        assertEquals(-80, PreviewTile.featureRunY(back.featureColumn(0, 0)[0]));
        assertEquals(0, back.featureColumn(5, 5).length);
        // The chunk under the column reaches the crown's top (80); its neighbors keep the ground.
        PreviewTile.Summary summary = back.summary();
        assertEquals(80, summary.chunkMax()[1 * PreviewTile.CHUNKS]);
        assertEquals(65, summary.chunkMax()[1 * PreviewTile.CHUNKS + 1]);

        int[] materials = new int[LEAVES + 1];
        for (int m = 0; m < materials.length; m++) {
            materials[m] = m + 100;
        }
        back.remapIds(materials, new int[]{0});
        assertEquals(LOG + 100, back.featureColumn(fx, fz)[1]);
    }

    @Test
    void featuresStandBlockByBlockAndFillCoarseVoxelsByAQuarter() {
        PreviewTile t = featureTile();
        PreviewVoxels.Tiles tiles = (x, z) -> x == 0 && z == 0 ? t : null;
        // Level 0, section (0, 2, 0): blocks 64..95. The 2-block voxels fill their blocks.
        LodSection fine = PreviewVoxels.build(0, 0, 2, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(LOG, fine.material(10, 66 - 64, 20));
        assertEquals(LOG, fine.material(11, 75 - 64, 21));
        assertEquals(LEAVES, fine.material(10, 79 - 64, 21));
        assertEquals(AIR, fine.material(10, 80 - 64, 20));
        assertEquals(AIR, fine.material(12, 70 - 64, 20));
        // Level 2 (4-block voxels): a 2 x 2 trunk fills a quarter of the voxels it passes through.
        LodSection coarse = PreviewVoxels.build(2, 0, 0, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(LOG, coarse.material(10 / 4, 68 / 4, 20 / 4));
        // Over a real chunk, nothing grows.
        PreviewVoxels.Mask real = (cx, cz) -> cx == 0 && cz == 1;
        LodSection masked = PreviewVoxels.build(0, 0, 2, 0, tiles, real, AIR, -64, -1, -1, -1, 0);
        assertTrue(masked == null || masked.material(10, 66 - 64, 20) == AIR);
    }

    // ---------------------------------------------------------------- light sources (ADR-117)

    /** Builds section (0, 1, 0) of the slope (blocks 0..31, y 32..63) with a source of {@code emission} at (x, y, z). */
    private static LodSection litSlope(int level, int x, int y, int z, int emission, PreviewVoxels.Mask mask,
                                       boolean blockLight) {
        PreviewTile t = slope();
        t.setLights(new int[]{PreviewTile.lightWord(x, z, emission), y});
        return PreviewVoxels.build(level, 0, level == 0 ? 1 : 0, 0, (tx, tz) -> tx == 0 && tz == 0 ? t : null, mask,
                AIR, -64, -1, -1, -1, 0, null, blockLight);
    }

    @Test
    void lightSourcesRoundTripThroughTheirEncoding() {
        PreviewTile t = slope();
        t.setLights(new int[]{PreviewTile.lightWord(-16, 527, 15), -40, PreviewTile.lightWord(3, 4, 6), 70});
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        int[] lights = back.lights();
        assertEquals(4, lights.length);
        assertEquals(-16, PreviewTile.lightX(lights[0]));
        assertEquals(527, PreviewTile.lightZ(lights[0]));
        assertEquals(15, PreviewTile.lightEmission(lights[0]));
        assertEquals(-40, lights[1]);
        assertEquals(6, PreviewTile.lightEmission(lights[2]));
        assertEquals(70, lights[3]);
    }

    @Test
    void aSourceLightsTheVoxelsAroundItOneLevelLessPerBlock() {
        // Under the slope's sea: column 8 has its surface at 59, water up to 62.
        LodSection s = litSlope(0, 8, 60, 8, 10, null, true);
        assertNotNull(s);
        assertEquals(10, s.blockLight().get(8, 60 - 32, 8));
        assertEquals(7, s.blockLight().get(11, 60 - 32, 8));
        assertEquals(8, s.blockLight().get(9, 61 - 32, 8));
        assertEquals(1, s.blockLight().get(8, 60 - 32, 17));
        assertEquals(0, s.blockLight().get(8, 60 - 32, 18));
        // Off: no block light at all, as before ADR-117.
        LodSection off = litSlope(0, 8, 60, 8, 10, null, false);
        assertEquals(0, off.blockLight().get(8, 60 - 32, 8));
    }

    @Test
    void aCoarseVoxelTakesTheLightOfItsNearestBlock() {
        // Level 1, section (0, 0, 0): voxels of 2 blocks, y 0..63.
        LodSection s = litSlope(1, 8, 56, 8, 10, null, true);
        assertNotNull(s);
        assertEquals(10, s.blockLight().get(4, 28, 4)); // holds the source
        assertEquals(8, s.blockLight().get(5, 28, 4)); // spans x 10..11: x 10 is 2 away
        assertEquals(9, s.blockLight().get(3, 28, 4)); // spans x 6..7: x 7 is 1 away
        assertEquals(6, s.blockLight().get(5, 29, 4)); // x 10 and y 58: 2 + 2 away
    }

    @Test
    void sourcesOfTheTileBesideAndOverRealChunksLightOnlyPreviewTerrain() {
        PreviewTile west = slope();
        PreviewTile t = slope();
        // Block x -1 of the world: the last column of the tile to the west, a source of 5.
        west.setLights(new int[]{PreviewTile.lightWord(511, 8, 5), 60});
        PreviewVoxels.Tiles tiles = (tx, tz) -> tz != 0 ? null : tx == 0 ? t : tx == -1 ? west : null;
        LodSection s = PreviewVoxels.build(0, 0, 1, 0, tiles, null, AIR, -64, -1, -1, -1, 0, null, true);
        assertEquals(4, s.blockLight().get(0, 60 - 32, 8));
        assertEquals(1, s.blockLight().get(3, 60 - 32, 8));
        // Over a real chunk the real LODs light it: the source is left out.
        LodSection masked = litSlope(0, 8, 60, 8, 10, (cx, cz) -> cx == 0 && cz == 0, true);
        // Chunk (1, 0) is preview terrain: without the mask, block x 17 would read 1.
        assertTrue(masked == null || masked.blockLight().get(17, 60 - 32, 8) == 0);
        assertEquals(1, litSlope(0, 8, 60, 8, 10, null, true).blockLight().get(17, 60 - 32, 8));
    }

    private static final int LAVA = 10;

    /** Flat land, its first free block at 65 (grass at 64), and a dug lake on the given columns. */
    private static PreviewTile dugTile(int x0, int x1, int z0, int z1, int bottom, int fluidTop) {
        PreviewTile t = new PreviewTile();
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                t.setHeight(i, k, 65);
            }
        }
        for (int c = 0; c < PreviewTile.CELLS * PreviewTile.CELLS; c++) {
            t.setCell(c % PreviewTile.CELLS, c / PreviewTile.CELLS, GRASS, DIRT);
        }
        t.setBelow(STONE);
        java.util.List<Integer> pits = new java.util.ArrayList<>();
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                java.util.Collections.addAll(pits, PreviewTile.pitWord(x, z), bottom, fluidTop, LAVA);
            }
        }
        t.setPits(pits.stream().mapToInt(Integer::intValue).toArray());
        return t;
    }

    @Test
    void dugColumnsRoundTripAndAreFoundByColumn() {
        PreviewTile t = dugTile(0, -1, 0, -1, 0, 0);
        t.setPits(new int[]{PreviewTile.pitWord(3, 4), 60, 63, LAVA, PreviewTile.pitWord(-16, 527), 58, 58, -1});
        ByteWriter out = new ByteWriter(1024);
        t.encode(out);
        PreviewTile back = PreviewTile.decode(new ByteReader().reset(out.array(), 0, out.length()));
        int i = back.pit(3, 4);
        assertTrue(i >= 0);
        assertEquals(60, back.pits()[i + 1]);
        assertEquals(63, back.pits()[i + 2]);
        assertEquals(LAVA, back.pits()[i + 3]);
        int j = back.pit(-16, 527);
        assertTrue(j >= 0);
        assertEquals(58, back.pits()[j + 2]);
        assertEquals(-1, back.pits()[j + 3]);
        assertEquals(-1, back.pit(4, 3));
        // A server's ids become the client's.
        int[] materials = new int[11];
        materials[LAVA] = 7;
        back.remapIds(materials, new int[8]);
        assertEquals(7, back.pits()[back.pit(3, 4) + 3]);
        assertEquals(-1, back.pits()[j + 3]);
    }

    @Test
    void aDugLakeLowersItsColumnsAndFillsThemOnTheFinestLevel() {
        // Lava on columns x 8..11, z 8..11, blocks 61 to 63, block 64 dug open.
        PreviewTile t = dugTile(8, 11, 8, 11, 61, 64);
        LodSection low = PreviewVoxels.build(0, 0, 1, 0, (tx, tz) -> tx == 0 && tz == 0 ? t : null, null, AIR, -64, -1, -1, -1, 0);
        LodSection high = PreviewVoxels.build(0, 0, 2, 0, (tx, tz) -> tx == 0 && tz == 0 ? t : null, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(LAVA, low.material(9, 61 - 32, 9));
        assertEquals(LAVA, low.material(9, 63 - 32, 9));
        assertEquals(DIRT, low.material(9, 60 - 32, 9)); // the lake's floor, the filler under its new top
        assertEquals(AIR, high == null ? AIR : high.material(9, 0, 9)); // block 64: dug open
        // The banks: the columns beside reach down past the lake's bottom, no hollow under them in view.
        assertTrue(low.material(7, 61 - 32, 9) != AIR);
        assertTrue(low.material(9, 61 - 32, 12) != AIR);
        // Away from the lake the ground is as before.
        assertEquals(GRASS, high.material(20, 0, 20));
        assertEquals(AIR, low.material(20, 61 - 32, 20));
    }

    @Test
    void aCoarseTopVoxelShowsTheLakeWhereItCoversHalfItsColumns() {
        // Level 2: voxels of 4 blocks, the top one at blocks 64..67. Lava on x 0..7, z 0..7, and on x 12, z 0..3.
        PreviewTile t = dugTile(0, 7, 0, 7, 61, 64);
        int[] more = new int[4 * PreviewTile.PIT_INTS];
        for (int z = 0; z < 4; z++) {
            more[z * 4] = PreviewTile.pitWord(12, z);
            more[z * 4 + 1] = 61;
            more[z * 4 + 2] = 64;
            more[z * 4 + 3] = LAVA;
        }
        int[] all = java.util.Arrays.copyOf(t.pits(), t.pits().length + more.length);
        System.arraycopy(more, 0, all, t.pits().length, more.length);
        t.setPits(all);
        LodSection s = PreviewVoxels.build(2, 0, 0, 0, (tx, tz) -> tx == 0 && tz == 0 ? t : null, null, AIR, -64, -1, -1, -1, 0);
        assertEquals(LAVA, s.material(0, 16, 0));
        assertEquals(LAVA, s.material(1, 16, 1));
        assertEquals(GRASS, s.material(2, 16, 0)); // x 8..11: no lake
        assertEquals(GRASS, s.material(3, 16, 0)); // x 12..15: a quarter of its columns
    }

    @Test
    void aLakeOfTheTileBesideIsFoundInItsMargin() {
        PreviewTile own = dugTile(0, -1, 0, -1, 0, 0);
        PreviewTile east = dugTile(-3, -3, 8, 8, 61, 64);
        PreviewVoxels.Tiles tiles = (tx, tz) -> tz != 0 ? null : tx == 0 ? own : tx == 1 ? east : null;
        int[] pit = new int[3];
        assertTrue(PreviewVoxels.pitAt(tiles, 509, 8, pit));
        assertEquals(61, pit[0]);
        assertEquals(64, pit[1]);
        assertEquals(LAVA, pit[2]);
        assertTrue(!PreviewVoxels.pitAt(tiles, 508, 8, pit));
    }

    @Test
    void aSectionLooksUpItsTilesAFewTimesNotOncePerColumn() {
        // The client's tile source takes a shared lock per read: reading the lakes per column through it
        // made the mesh jobs 3 to 10 times slower (2026-11-08). Every tile here has a lake.
        PreviewTile t = dugTile(8, 11, 8, 11, 61, 64);
        int[] reads = new int[1];
        PreviewVoxels.Tiles tiles = (tx, tz) -> {
            reads[0]++;
            return t;
        };
        for (int level = 0; level <= 4; level++) {
            reads[0] = 0;
            PreviewVoxels.build(level, 0, 64 >> level >> 5, 0, tiles, null, AIR, -64, -1, -1, -1, 0);
            System.out.println("tile reads at level " + level + ": " + reads[0]);
            assertTrue(reads[0] < 400, "level " + level + ": " + reads[0] + " tile reads");
        }
    }
}
