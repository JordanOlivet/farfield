package io.lakio.farfield.core.lod;

import io.lakio.farfield.core.lod.MipBuilder.Shapes;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MipBuilderTest {

    static final int AIR = 0;
    static final int STONE = 1;
    static final int SAND = 2;
    static final int WATER = 3;
    static final int GRASS_BLOCK = 4;
    static final int SHORT_GRASS = 5;
    static final int SNOW_LAYER = 6;
    static final int CAVE_AIR = 7;
    static final int SLAB = 8;
    static final int TORCH = 9;

    static final Shapes SHAPES = m -> switch (m) {
        case AIR, CAVE_AIR -> Shapes.EMPTY;
        case SHORT_GRASS, TORCH -> Shapes.SPARSE;
        case SNOW_LAYER, SLAB -> Shapes.COVER;
        default -> Shapes.FULL;
    };

    private final MipBuilder mips = new MipBuilder(AIR, SHAPES);

    /** Parent of a single child (octant 0) whose voxels are set by {@code fill}. */
    private LodSection parentOf(LodSection child) {
        LodSection[] children = new LodSection[8];
        children[0] = child;
        return mips.build(children);
    }

    @Test
    void fillFollowsTheSurfaceInsideCoarseVoxels() {
        Shapes shapes = new Shapes() {
            @Override
            public int shapeOf(int material) {
                return SHAPES.shapeOf(material);
            }

            @Override
            public int heightOf(int material) {
                return material == SNOW_LAYER ? 2 : material == SLAB ? 8 : Shapes.super.heightOf(material);
            }
        };
        MipBuilder builder = new MipBuilder(AIR, shapes);
        LodSection child = new LodSection(AIR);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                for (int y = 0; y < 10; y++) {
                    child.set(x, y, z, STONE);
                }
                if (x < 16) {
                    child.set(x, 10, z, SNOW_LAYER);
                } else {
                    for (int y = 10; y <= 12; y++) {
                        child.set(x, y, z, WATER);
                    }
                }
            }
        }
        LodSection[] children = new LodSection[8];
        children[0] = child;
        LodSection parent = builder.build(children, true);
        // A snow layer of 2/16 in a 2-block voxel fills 1/16 of it (nibble 0), on full stone.
        assertEquals(SNOW_LAYER, parent.material(2, 5, 2));
        assertEquals(0, parent.fill().get(2, 5, 2));
        assertEquals(15, parent.fill().get(2, 4, 2));
        // Water up to y 12 fills the voxel over y 12..13 by half (nibble 7).
        assertEquals(WATER, parent.material(10, 6, 2));
        assertEquals(7, parent.fill().get(10, 6, 2));
        assertEquals(15, parent.fill().get(10, 5, 2));

        // One level up the children are coarse: their fill counts, not their material.
        // Blocks 8..11: stone 8..9 and 2/16 of snow on 10: 2.125 of 4 blocks, 9/16 (nibble 8).
        LodSection[] coarse = new LodSection[8];
        coarse[0] = parent;
        LodSection grand = builder.build(coarse, false);
        assertEquals(8, grand.fill().get(1, 2, 1));
    }

    @Test
    void uniformChildrenGiveUniformParent() {
        LodSection[] children = new LodSection[8];
        for (int i = 0; i < 8; i++) {
            children[i] = new LodSection(i < 4 ? STONE : AIR); // lower half stone, upper half air
        }
        LodSection parent = mips.build(children);
        assertEquals(STONE, parent.material(3, 15, 3));
        assertEquals(AIR, parent.material(3, 16, 3));
        assertEquals(LodSection.BRICK_COUNT, parent.uniformBrickCount());
    }

    @Test
    void oneBlockWallSurvivesAndLoneBlockVanishes() {
        LodSection child = new LodSection(AIR);
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                child.set(4, y, z, STONE); // wall one block thick along z
            }
        }
        child.set(20, 20, 20, STONE); // lone block
        LodSection parent = parentOf(child);
        assertEquals(STONE, parent.material(2, 1, 1));
        assertEquals(AIR, parent.material(10, 10, 10));
    }

    @Test
    void sparseBlocksAloneDoNotRaiseTheGround() {
        LodSection child = new LodSection(AIR);
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                child.set(x, 0, z, GRASS_BLOCK);
                child.set(x, 1, z, SHORT_GRASS);
                child.set(x, 3, z, SHORT_GRASS); // a cell holding only plants
            }
        }
        LodSection parent = parentOf(child);
        assertEquals(GRASS_BLOCK, parent.material(0, 0, 0), "plants over the block show the block");
        assertEquals(AIR, parent.material(0, 1, 0), "four plants weigh 4 < 8");
    }

    @Test
    void materialIsWhatIsSeenFromAbove() {
        LodSection child = new LodSection(AIR);
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                child.set(x, 0, z, SAND);
                child.set(x, 1, z, WATER);           // cell (0, 0, 0): water over sand
                child.set(x + 2, 0, z, SAND);
                child.set(x + 2, 1, z, WATER);
                child.set(x + 2, 0, z + 2, STONE);   // cell (1, 0, 1): snow over stone
                child.set(x + 2, 1, z + 2, SNOW_LAYER);
            }
        }
        // Cell (2, 0, 0): three columns of sand under air, one of stone -> sand.
        child.set(4, 0, 0, SAND);
        child.set(5, 0, 0, SAND);
        child.set(4, 0, 1, SAND);
        child.set(5, 0, 1, STONE);
        LodSection parent = parentOf(child);
        assertEquals(WATER, parent.material(0, 0, 0));
        assertEquals(SNOW_LAYER, parent.material(1, 0, 1));
        assertEquals(SAND, parent.material(2, 0, 0));
    }

    @Test
    void lightIsTheMaximumAndBiomeTheMajority() {
        LodSection child = new LodSection(AIR);
        child.skyLight().fill(0);
        child.skyLight().set(1, 1, 1, 9);
        child.blockLight().set(0, 0, 0, 12);
        child.setBiome(0, 0, 0, 5);
        child.setBiome(1, 0, 0, 5);
        child.setBiome(0, 1, 0, 7);
        child.setBiome(1, 1, 1, -1);
        LodSection parent = parentOf(child);
        assertEquals(9, parent.skyLight().get(0, 0, 0));
        assertEquals(0, parent.skyLight().get(1, 0, 0));
        assertEquals(12, parent.blockLight().get(0, 0, 0));
        assertEquals(15, parent.skyLight().get(20, 20, 20), "missing child: full sky light");
        assertEquals(5, parent.biome(0, 0, 0));
    }

    @Test
    void matchesTheReferenceRuleOnRandomSections() {
        SplittableRandom random = new SplittableRandom(42);
        for (int round = 0; round < 6; round++) {
            LodSection[] children = new LodSection[8];
            for (int i = 0; i < 8; i++) {
                if (random.nextInt(8) != 0) {
                    children[i] = randomChild(random);
                }
            }
            LodSection expected = reference(children);
            LodSection actual = mips.build(children);
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        String at = x + "," + y + "," + z;
                        assertEquals(expected.material(x, y, z), actual.material(x, y, z), at);
                        assertEquals(expected.skyLight().get(x, y, z), actual.skyLight().get(x, y, z), at);
                        assertEquals(expected.blockLight().get(x, y, z), actual.blockLight().get(x, y, z), at);
                    }
                }
            }
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) {
                    for (int x = 0; x < 8; x++) {
                        assertEquals(expected.biome(x, y, z), actual.biome(x, y, z));
                    }
                }
            }
        }
    }

    private static LodSection randomChild(SplittableRandom random) {
        LodSection s = new LodSection(random.nextBoolean() ? AIR : STONE);
        int surface = random.nextInt(32);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    // Leave some bricks uniform: only touch the lower 3/4 of the section.
                    if (y < 24) {
                        int m = y < surface ? STONE : random.nextInt(3) == 0 ? random.nextInt(10) : AIR;
                        s.set(x, y, z, m);
                        s.skyLight().set(x, y, z, y < surface ? 0 : random.nextInt(16));
                    }
                    if (random.nextInt(40) == 0) {
                        s.blockLight().set(x, y, z, random.nextInt(16));
                    }
                }
            }
        }
        if (random.nextBoolean()) {
            for (int c = 0; c < 40; c++) {
                s.setBiome(random.nextInt(8), random.nextInt(8), random.nextInt(8), random.nextInt(4) - 1);
            }
        } else {
            s.fillBiome(random.nextInt(5));
        }
        s.compact();
        return s;
    }

    private static final int[] WEIGHT = {0, 1, 2, 4};

    /** The rule of {@link MipBuilder}, written voxel by voxel. */
    private static LodSection reference(LodSection[] children) {
        LodSection parent = new LodSection(AIR);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    LodSection child = children[((y >> 4) * 2 + (z >> 4)) * 2 + (x >> 4)];
                    if (child == null) {
                        continue;
                    }
                    int cx = (x & 15) * 2;
                    int cy = (y & 15) * 2;
                    int cz = (z & 15) * 2;
                    int weight = 0;
                    int sky = 0;
                    int block = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                weight += WEIGHT[SHAPES.shapeOf(child.material(cx + dx, cy + dy, cz + dz))];
                                sky = Math.max(sky, child.skyLight().get(cx + dx, cy + dy, cz + dz));
                                block = Math.max(block, child.blockLight().get(cx + dx, cy + dy, cz + dz));
                            }
                        }
                    }
                    parent.skyLight().set(x, y, z, sky);
                    parent.blockLight().set(x, y, z, block);
                    if (weight >= 8) {
                        parent.set(x, y, z, seenFromAbove(child, cx, cy, cz));
                    }
                }
            }
        }
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    LodSection child = children[((y >> 2) * 2 + (z >> 2)) * 2 + (x >> 2)];
                    if (child == null) {
                        continue;
                    }
                    int[] votes = new int[8];
                    for (int i = 0; i < 8; i++) {
                        votes[i] = child.biome((x & 3) * 2 + (i & 1), (y & 3) * 2 + (i >> 2), (z & 3) * 2 + ((i >> 1) & 1));
                    }
                    int best = -1;
                    int bestCount = 0;
                    for (int v : votes) {
                        if (v == -1) {
                            continue;
                        }
                        int count = 0;
                        for (int w : votes) {
                            count += w == v ? 1 : 0;
                        }
                        if (count > bestCount) {
                            bestCount = count;
                            best = v;
                        }
                    }
                    parent.setBiome(x, y, z, best);
                }
            }
        }
        parent.compact();
        parent.compactBiomes();
        return parent;
    }

    private static int seenFromAbove(LodSection child, int cx, int cy, int cz) {
        int[] material = new int[4];
        int[] rank = new int[4];
        boolean[] upper = new boolean[4];
        int bestRank = 0;
        for (int c = 0; c < 4; c++) {
            int x = cx + (c & 1);
            int z = cz + (c >> 1);
            int top = child.material(x, cy + 1, z);
            int bottom = child.material(x, cy, z);
            int st = SHAPES.shapeOf(top);
            int sb = SHAPES.shapeOf(bottom);
            if (st >= Shapes.COVER) {
                material[c] = top;
                rank[c] = 2;
                upper[c] = true;
            } else if (sb >= Shapes.COVER) {
                material[c] = bottom;
                rank[c] = 2;
            } else if (st == Shapes.SPARSE) {
                material[c] = top;
                rank[c] = 1;
                upper[c] = true;
            } else if (sb == Shapes.SPARSE) {
                material[c] = bottom;
                rank[c] = 1;
            }
            bestRank = Math.max(bestRank, rank[c]);
        }
        int best = -1;
        int bestScore = -1;
        for (int c = 0; c < 4; c++) {
            if (rank[c] != bestRank) {
                continue;
            }
            int score = 0;
            boolean seenUpper = false;
            for (int d = 0; d < 4; d++) {
                if (rank[d] == bestRank && material[d] == material[c]) {
                    score += 2;
                    seenUpper |= upper[d];
                }
            }
            score += seenUpper ? 1 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = material[c];
            }
        }
        return best;
    }
}
