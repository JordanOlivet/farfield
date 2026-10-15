package io.lakio.farfield.core.lod;

import java.util.Arrays;

/**
 * Builds a parent {@link LodSection} (level N+1, one voxel per 2x2x2 voxels)
 * from its eight children (level N), with the downsampling rules of
 * analysis §5.3 (ADR-020).
 *
 * <p><b>Occupancy</b> is a weighted vote over the eight child voxels: a
 * {@link Shapes#FULL full} cube weighs 4, a {@link Shapes#COVER cover}
 * (slab, snow layer, carpet) 2, a {@link Shapes#SPARSE sparse} block (plant,
 * torch, rail, fence) 1. The parent voxel is solid from a weight of 8: two
 * full children are enough, so one-block walls, floors and tree trunks
 * survive where a majority vote erases them, an isolated block disappears,
 * and grass or flowers alone never raise the ground.
 *
 * <p><b>Material</b> is what is seen from above: in each of the four child
 * columns, the highest non-sparse voxel, or the highest sparse one if the
 * column has nothing else; the most frequent of these (ties go to one seen
 * in the upper layer). Water over sand stays water, snow over grass stays
 * snow, grass plants over a grass block show the block.
 *
 * <p><b>Fill</b> is how high the content stands in the parent voxel: per
 * child column, the upper child's height above a full lower half, or the
 * lower child's height; the highest of the four columns, rounded up to a
 * sixteenth. Children on level 0 take their material's height (a snow
 * layer 2, a slab 8, a cube 16, air and plants 0); coarser children their
 * stored fill. The highest column keeps trunks, walls and cliff edges, and a
 * snow layer or the sea surface lands where it is instead of at the top of
 * the coarse voxel.
 *
 * <p><b>Light</b> is the maximum of the children (the light of the air next
 * to a surface survives). A <b>biome</b> cell takes the most frequent known
 * biome of its eight child cells.
 *
 * <p>Not thread-safe; one instance per worker.
 */
public final class MipBuilder {

    /** How a material fills its voxel; supplied by the Minecraft side from block states. */
    @FunctionalInterface
    public interface Shapes {
        /** Air and anything without a shape. */
        int EMPTY = 0;
        /** Thin or sparse: plants, flowers, torches, rails, fences, panes. */
        int SPARSE = 1;
        /** Partial but covering the voxel from above: slabs, snow layers, carpets, paths. */
        int COVER = 2;
        /** A full cube, opaque or not: stone, leaves, glass, water. */
        int FULL = 3;

        int shapeOf(int material);

        /** Height of the block in sixteenths: 16 for a cube, less for a cover, 0 for nothing. */
        default int heightOf(int material) {
            int shape = shapeOf(material);
            return shape >= COVER ? 16 : 0;
        }
    }

    private static final int[] WEIGHT = {0, 1, 2, 4};
    private static final int SOLID_WEIGHT = 8;
    private static final int OCTANT = LodSection.SIZE / 2;

    private final int airMaterial;
    private final Shapes shapes;

    private int[] shapeOfIndex = new int[16];
    private int[] heightOfIndex = new int[16];
    private boolean childrenFinest;
    private final byte[] octantFill = new byte[OCTANT * OCTANT * OCTANT];
    private final int[] heights = new int[LodSection.BRICK_VOLUME];
    private final int[] indices = new int[LodSection.BRICK_VOLUME];
    private final int[] octantMaterials = new int[OCTANT * OCTANT * OCTANT];
    private final byte[] octantSky = new byte[OCTANT * OCTANT * OCTANT];
    private final byte[] octantBlock = new byte[OCTANT * OCTANT * OCTANT];
    private final byte[] nibbles = new byte[NibbleBricks.BRICK_BYTES];
    private final int[] candidates = new int[4];
    private final boolean[] candidateUpper = new boolean[4];
    private final int[] biomeVotes = new int[8];

    public MipBuilder(int airMaterial, Shapes shapes) {
        this.airMaterial = airMaterial;
        this.shapes = shapes;
    }

    /**
     * @param children the eight children indexed {@code (oy * 2 + oz) * 2 + ox};
     *                 a null child counts as all air with full sky light
     */
    public LodSection build(LodSection[] children) {
        return build(children, true);
    }

    /**
     * @param childrenFinest the children are level-0 sections (their heights
     *                       come from their materials, not their fill)
     */
    public LodSection build(LodSection[] children, boolean childrenFinest) {
        this.childrenFinest = childrenFinest;
        LodSection parent = new LodSection(airMaterial);
        for (int oy = 0; oy < 2; oy++) {
            for (int oz = 0; oz < 2; oz++) {
                for (int ox = 0; ox < 2; ox++) {
                    LodSection child = children[(oy * 2 + oz) * 2 + ox];
                    if (child != null) {
                        downsample(child, parent, ox * OCTANT, oy * OCTANT, oz * OCTANT);
                    }
                }
            }
        }
        parent.compact();
        parent.compactBiomes();
        return parent;
    }

    private void downsample(LodSection child, LodSection parent, int baseX, int baseY, int baseZ) {
        int paletteSize = child.paletteSize();
        if (shapeOfIndex.length < paletteSize) {
            shapeOfIndex = new int[Integer.highestOneBit(paletteSize - 1) << 1];
            heightOfIndex = new int[shapeOfIndex.length];
        }
        for (int i = 0; i < paletteSize; i++) {
            int material = child.paletteEntry(i);
            shapeOfIndex[i] = shapes.shapeOf(material);
            heightOfIndex[i] = shapes.heightOf(material);
        }
        for (int by = 0; by < LodSection.BRICKS_PER_AXIS; by++) {
            for (int bz = 0; bz < LodSection.BRICKS_PER_AXIS; bz++) {
                for (int bx = 0; bx < LodSection.BRICKS_PER_AXIS; bx++) {
                    int brick = LodSection.brickIndex(bx, by, bz);
                    // Child brick (8^3) -> 4^3 voxels of the octant, from (bx, by, bz) * 4.
                    if (child.isUniform(brick)) {
                        int index = child.uniformIndex(brick);
                        int material = shapeOfIndex[index] == Shapes.EMPTY ? airMaterial : child.paletteEntry(index);
                        fillOctant(octantMaterials, bx, by, bz, material);
                    } else {
                        downsampleBrick(child, brick, bx, by, bz);
                    }
                    downsampleLight(child.skyLight(), brick, bx, by, bz, octantSky);
                    downsampleLight(child.blockLight(), brick, bx, by, bz, octantBlock);
                    downsampleFill(child, brick, bx, by, bz);
                }
            }
        }
        writeOctant(parent, baseX, baseY, baseZ);
        downsampleBiomes(child, parent, baseX, baseY, baseZ);
    }

    private static void fillOctant(int[] target, int bx, int by, int bz, int value) {
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                int row = ((by * 4 + y) * OCTANT + bz * 4 + z) * OCTANT + bx * 4;
                Arrays.fill(target, row, row + 4, value);
            }
        }
    }

    private void downsampleBrick(LodSection child, int brick, int bx, int by, int bz) {
        long[] data = child.brickData(brick);
        int bits = child.bits();
        for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
            indices[v] = LodSection.unpack(data, bits, v);
        }
        for (int py = 0; py < 4; py++) {
            for (int pz = 0; pz < 4; pz++) {
                for (int px = 0; px < 4; px++) {
                    int material = pick(child, px * 2, py * 2, pz * 2);
                    octantMaterials[((by * 4 + py) * OCTANT + bz * 4 + pz) * OCTANT + bx * 4 + px] = material;
                }
            }
        }
    }

    /** The parent material of the 2x2x2 cell at (x, y, z) of the unpacked brick in {@link #indices}. */
    private int pick(LodSection child, int x, int y, int z) {
        int weight = 0;
        for (int i = 0; i < 8; i++) {
            weight += WEIGHT[shapeOfIndex[indices[voxel(x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1))]]];
        }
        if (weight < SOLID_WEIGHT) {
            return airMaterial;
        }
        // Per column, the voxel seen from above; rank 2 non-sparse, 1 sparse.
        int bestRank = 0;
        for (int c = 0; c < 4; c++) {
            int cx = x + (c & 1);
            int cz = z + (c >> 1);
            int upper = indices[voxel(cx, y + 1, cz)];
            int lower = indices[voxel(cx, y, cz)];
            int su = shapeOfIndex[upper];
            int sl = shapeOfIndex[lower];
            int chosen;
            int rank;
            boolean isUpper;
            if (su >= Shapes.COVER) {
                chosen = upper;
                rank = 2;
                isUpper = true;
            } else if (sl >= Shapes.COVER) {
                chosen = lower;
                rank = 2;
                isUpper = false;
            } else if (su == Shapes.SPARSE) {
                chosen = upper;
                rank = 1;
                isUpper = true;
            } else if (sl == Shapes.SPARSE) {
                chosen = lower;
                rank = 1;
                isUpper = false;
            } else {
                chosen = -1;
                rank = 0;
                isUpper = false;
            }
            if (rank > bestRank) {
                bestRank = rank;
            }
            candidates[c] = rank == 0 ? -1 : (rank << 20) | chosen;
            candidateUpper[c] = isUpper;
        }
        int best = -1;
        int bestScore = -1;
        for (int c = 0; c < 4; c++) {
            int candidate = candidates[c];
            if (candidate < 0 || candidate >>> 20 != bestRank) {
                continue;
            }
            int score = 0;
            boolean upper = false;
            for (int d = 0; d < 4; d++) {
                if (candidates[d] == candidate) {
                    score += 2;
                    upper |= candidateUpper[d];
                }
            }
            if (upper) {
                score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate & 0xFFFFF;
            }
        }
        // Weight >= 8 needs at least one non-empty voxel, so some column has a candidate.
        return child.paletteEntry(best);
    }

    /**
     * Fills {@link #octantFill} for the 4^3 parent voxels of a child brick:
     * per parent voxel, the highest of its four child columns (see the class
     * comment), as sixteenths minus one.
     */
    private void downsampleFill(LodSection child, int brick, int bx, int by, int bz) {
        NibbleBricks fill = child.fill();
        boolean fillUniform = fill.isUniform(brick);
        int uniformFill = fill.uniformValue(brick);
        byte[] fillData = fill.brickData(brick);
        if (child.isUniform(brick) && (childrenFinest || fillUniform)) {
            int index = child.uniformIndex(brick);
            int h = height(index, uniformFill);
            int column = h == 0 ? 0 : 16 + h;
            byte value = (byte) (toFill(column) & 15);
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    int row = ((by * 4 + y) * OCTANT + bz * 4 + z) * OCTANT + bx * 4;
                    Arrays.fill(octantFill, row, row + 4, value);
                }
            }
            return;
        }
        long[] data = child.brickData(brick);
        int bits = child.bits();
        int uniformIndex = data == null ? child.uniformIndex(brick) : 0;
        for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
            int index = data == null ? uniformIndex : LodSection.unpack(data, bits, v);
            int f = fillData == null ? uniformFill : (fillData[v >> 1] >> ((v & 1) << 2)) & 15;
            heights[v] = height(index, f);
        }
        for (int py = 0; py < 4; py++) {
            for (int pz = 0; pz < 4; pz++) {
                for (int px = 0; px < 4; px++) {
                    int best = 0;
                    for (int c = 0; c < 4; c++) {
                        int cx = px * 2 + (c & 1);
                        int cz = pz * 2 + (c >> 1);
                        int lower = heights[voxel(cx, py * 2, cz)];
                        int upper = heights[voxel(cx, py * 2 + 1, cz)];
                        best = Math.max(best, upper > 0 ? 16 + upper : lower);
                    }
                    octantFill[((by * 4 + py) * OCTANT + bz * 4 + pz) * OCTANT + bx * 4 + px] =
                            (byte) (toFill(best) & 15);
                }
            }
        }
    }

    /** Height of a child voxel in sixteenths of the child voxel. */
    private int height(int paletteIndex, int fillNibble) {
        if (shapeOfIndex[paletteIndex] == Shapes.EMPTY) {
            return 0;
        }
        return childrenFinest ? heightOfIndex[paletteIndex] : fillNibble + 1;
    }

    /** A column height in sixteenths of a child voxel (0..32) as a parent fill nibble. */
    private static int toFill(int column) {
        int sixteenths = (column + 1) / 2;
        return Math.max(1, Math.min(16, sixteenths)) - 1;
    }

    private static int voxel(int x, int y, int z) {
        return (y << 6) | (z << 3) | x;
    }

    private static void downsampleLight(NibbleBricks light, int brick, int bx, int by, int bz, byte[] target) {
        if (light.isUniform(brick)) {
            byte value = (byte) light.uniformValue(brick);
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    int row = ((by * 4 + y) * OCTANT + bz * 4 + z) * OCTANT + bx * 4;
                    Arrays.fill(target, row, row + 4, value);
                }
            }
            return;
        }
        byte[] d = light.brickData(brick);
        for (int py = 0; py < 4; py++) {
            for (int pz = 0; pz < 4; pz++) {
                for (int px = 0; px < 4; px++) {
                    int max = 0;
                    for (int i = 0; i < 8; i++) {
                        int v = voxel(px * 2 + (i & 1), py * 2 + (i >> 2), pz * 2 + ((i >> 1) & 1));
                        max = Math.max(max, (d[v >> 1] >> ((v & 1) << 2)) & 15);
                    }
                    target[((by * 4 + py) * OCTANT + bz * 4 + pz) * OCTANT + bx * 4 + px] = (byte) max;
                }
            }
        }
    }

    /** Copies the octant buffers into the eight parent bricks they cover. */
    private void writeOctant(LodSection parent, int baseX, int baseY, int baseZ) {
        for (int qy = 0; qy < 2; qy++) {
            for (int qz = 0; qz < 2; qz++) {
                for (int qx = 0; qx < 2; qx++) {
                    int brick = LodSection.brickIndex((baseX >> 3) + qx, (baseY >> 3) + qy, (baseZ >> 3) + qz);
                    int ox = qx * 8;
                    int oy = qy * 8;
                    int oz = qz * 8;
                    int first = octantMaterials[(oy * OCTANT + oz) * OCTANT + ox];
                    boolean uniform = true;
                    for (int y = 0; y < 8 && uniform; y++) {
                        for (int z = 0; z < 8 && uniform; z++) {
                            int row = ((oy + y) * OCTANT + oz + z) * OCTANT + ox;
                            for (int x = 0; x < 8; x++) {
                                if (octantMaterials[row + x] != first) {
                                    uniform = false;
                                    break;
                                }
                            }
                        }
                    }
                    if (uniform) {
                        if (first != airMaterial) {
                            parent.fillBrick(brick, first);
                        }
                    } else {
                        for (int y = 0; y < 8; y++) {
                            for (int z = 0; z < 8; z++) {
                                int row = ((oy + y) * OCTANT + oz + z) * OCTANT + ox;
                                for (int x = 0; x < 8; x++) {
                                    int material = octantMaterials[row + x];
                                    if (material != airMaterial) {
                                        parent.set(baseX + ox + x, baseY + oy + y, baseZ + oz + z, material);
                                    }
                                }
                            }
                        }
                    }
                    writeLightBrick(parent.skyLight(), brick, octantSky, ox, oy, oz);
                    writeLightBrick(parent.blockLight(), brick, octantBlock, ox, oy, oz);
                    writeLightBrick(parent.fill(), brick, octantFill, ox, oy, oz);
                }
            }
        }
    }

    private void writeLightBrick(NibbleBricks light, int brick, byte[] source, int ox, int oy, int oz) {
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                int row = ((oy + y) * OCTANT + oz + z) * OCTANT + ox;
                for (int x = 0; x < 8; x += 2) {
                    nibbles[(y * 64 + z * 8 + x) >> 1] = (byte) ((source[row + x] & 15) | (source[row + x + 1] << 4));
                }
            }
        }
        light.setBrick(brick, nibbles);
    }

    private void downsampleBiomes(LodSection child, LodSection parent, int baseX, int baseY, int baseZ) {
        int cellBase = LodSection.BIOME_CELLS_PER_AXIS / 2;
        int[] cells = child.biomeCells();
        for (int cy = 0; cy < cellBase; cy++) {
            for (int cz = 0; cz < cellBase; cz++) {
                for (int cx = 0; cx < cellBase; cx++) {
                    int biome;
                    if (cells == null) {
                        biome = child.uniformBiome();
                    } else {
                        for (int i = 0; i < 8; i++) {
                            biomeVotes[i] = child.biome(cx * 2 + (i & 1), cy * 2 + (i >> 2), cz * 2 + ((i >> 1) & 1));
                        }
                        biome = mostFrequentKnown(biomeVotes);
                    }
                    parent.setBiome(baseX / 4 + cx, baseY / 4 + cy, baseZ / 4 + cz, biome);
                }
            }
        }
    }

    /** Most frequent value other than -1 (first one on ties), or -1 if all are unknown. */
    private static int mostFrequentKnown(int[] values) {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            if (v == -1) {
                continue;
            }
            int count = 0;
            for (int j = i; j < values.length; j++) {
                if (values[j] == v) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = v;
            }
        }
        return best;
    }
}
