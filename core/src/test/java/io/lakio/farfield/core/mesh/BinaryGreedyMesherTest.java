package io.lakio.farfield.core.mesh;

import io.lakio.farfield.core.lod.LodSection;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BinaryGreedyMesherTest {

    // Materials: 0 air, 1..9 opaque, 10..12 translucent.
    private static final MaterialClassifier CLASSES = m -> m == 0 ? MaterialClassifier.AIR
            : m < 10 ? MaterialClassifier.OPAQUE : MaterialClassifier.TRANSLUCENT;

    private static final int[][] STEP = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private final BinaryGreedyMesher mesher = new BinaryGreedyMesher();
    private final MeshBuffer out = new MeshBuffer();

    @Test
    void quadPackingRoundTrips() {
        long q = Quad.pack(31, 0, 17, 32, 1, Quad.POS_Z, Quad.MAX_MATERIAL);
        assertEquals(31, Quad.x(q));
        assertEquals(0, Quad.y(q));
        assertEquals(17, Quad.z(q));
        assertEquals(32, Quad.sizeU(q));
        assertEquals(1, Quad.sizeV(q));
        assertEquals(Quad.POS_Z, Quad.direction(q));
        assertEquals(Quad.MAX_MATERIAL, Quad.material(q));
        assertEquals(0, q >>> 48);
        assertEquals(-1, Quad.biome(q));

        q = Quad.pack(1, 2, 3, 4, 5, Quad.NEG_Y, 77, Quad.MAX_BIOME);
        assertEquals(Quad.MAX_BIOME, Quad.biome(q));
        assertEquals(77, Quad.material(q));
        assertEquals(Quad.NEG_Y, Quad.direction(q));
        assertEquals(0, Quad.biome(Quad.pack(0, 0, 0, 1, 1, 0, 1, 0)));
        assertEquals(-1, Quad.biome(Quad.pack(0, 0, 0, 1, 1, 0, 1, Quad.MAX_BIOME + 1)));
    }

    @Test
    void tintedQuadsSplitByBiomeCell() {
        // Grass top layer over two biomes split at x = 16: the tinted top
        // splits in two quads, the untinted stone below stays one.
        LodSection section = new LodSection(0);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                section.set(x, 0, z, 2);
                section.set(x, 1, z, 3);
            }
        }
        for (int cz = 0; cz < 8; cz++) {
            for (int cy = 0; cy < 8; cy++) {
                for (int cx = 0; cx < 8; cx++) {
                    section.setBiome(cx, cy, cz, cx < 4 ? 5 : 6);
                }
            }
        }
        section.compact();
        mesher.mesh(section, CLASSES, m -> m == 3, null, out);
        assertEquals(2, out.count(Quad.POS_Y));
        for (int i = 0; i < 2; i++) {
            long q = out.quads(Quad.POS_Y)[i];
            assertEquals(Quad.z(q) < 16 && Quad.x(q) < 16 ? 5 : 6, Quad.biome(q));
        }
        assertEquals(1, out.count(Quad.NEG_Y));
        assertEquals(-1, Quad.biome(out.quads(Quad.NEG_Y)[0]));

        // One biome for the whole section: tinted quads carry it and merge.
        LodSection uniform = new LodSection(0);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                uniform.set(x, 0, z, 3);
            }
        }
        uniform.fillBiome(9);
        mesher.mesh(uniform, CLASSES, m -> m == 3, null, out);
        assertEquals(1, out.count(Quad.POS_Y));
        assertEquals(9, Quad.biome(out.quads(Quad.POS_Y)[0]));
    }

    @Test
    void tintedMatchesBruteForceOnRandomBiomes() {
        SplittableRandom random = new SplittableRandom(17);
        for (int round = 0; round < 30; round++) {
            LodSection section = new LodSection(0);
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int m = y < 8 + (x + z) % 9 ? 1 + random.nextInt(4) : y < 20 ? 10 + random.nextInt(2) : 0;
                        section.set(x, y, z, random.nextDouble() < 0.1 ? 0 : m);
                    }
                }
            }
            int biomes = 1 + random.nextInt(4);
            for (int cz = 0; cz < 8; cz++) {
                for (int cy = 0; cy < 8; cy++) {
                    for (int cx = 0; cx < 8; cx++) {
                        section.setBiome(cx, cy, cz, random.nextInt(biomes));
                    }
                }
            }
            section.compact();
            verify(section, null, m -> m % 2 == 0);
        }
    }

    @Test
    void emptySectionHasNoQuads() {
        mesher.mesh(new LodSection(0), CLASSES, null, out);
        assertEquals(0, out.total());
    }

    @Test
    void singleVoxelHasSixUnitQuads() {
        LodSection section = new LodSection(0);
        section.set(3, 4, 5, 2);
        mesher.mesh(section, CLASSES, null, out);
        assertEquals(6, out.total());
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            assertEquals(1, out.count(d));
            long q = out.quads(d)[0];
            assertEquals(3, Quad.x(q));
            assertEquals(4, Quad.y(q));
            assertEquals(5, Quad.z(q));
            assertEquals(1, Quad.sizeU(q) * Quad.sizeV(q));
            assertEquals(2, Quad.material(q));
        }
        verify(section, null);
    }

    @Test
    void solidSectionIsSixFullQuads() {
        LodSection section = new LodSection(1);
        mesher.mesh(section, CLASSES, null, out);
        assertEquals(6, out.total());
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            long q = out.quads(d)[0];
            assertEquals(32, Quad.sizeU(q));
            assertEquals(32, Quad.sizeV(q));
        }
        int[][] walls = new int[6][];
        for (int d = 0; d < 6; d++) {
            walls[d] = new int[32];
            Arrays.fill(walls[d], -1);
        }
        mesher.mesh(section, CLASSES, walls, out);
        assertEquals(0, out.total());
    }

    @Test
    void waterBodyShowsOnlyItsSurface() {
        LodSection section = new LodSection(0);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    section.set(x, y, z, y < 10 ? 1 : y < 20 ? 10 : 0);
                }
            }
        }
        int[][] walls = new int[6][];
        for (int d = 0; d < 6; d++) {
            walls[d] = new int[32];
            Arrays.fill(walls[d], -1);
        }
        mesher.mesh(section, CLASSES, walls, out);
        // Enclosed by opaque neighbors: the sea floor shows through the water
        // (one stone quad), the water shows its top (one quad), nothing else.
        assertEquals(2, out.total());
        assertEquals(2, out.count(Quad.POS_Y));
        for (int i = 0; i < 2; i++) {
            long q = out.quads(Quad.POS_Y)[i];
            assertEquals(Quad.material(q) == 1 ? 9 : 19, Quad.y(q));
            assertEquals(32 * 32, Quad.sizeU(q) * Quad.sizeV(q));
        }
        verify(section, walls);
    }

    @Test
    void matchesBruteForceOnRandomSections() {
        SplittableRandom random = new SplittableRandom(9);
        for (int round = 0; round < 60; round++) {
            LodSection section = new LodSection(0);
            // Mix of sparse noise, layered terrain and blocky regions.
            int materials = 1 + random.nextInt(12);
            double density = random.nextDouble();
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int m;
                        if (round % 3 == 0) {
                            m = random.nextDouble() < density ? 1 + random.nextInt(materials) : 0;
                        } else if (round % 3 == 1) {
                            m = y < 10 + ((x / 4 + z / 4) % 6) ? 1 + (y % materials) : y < 18 ? 10 : 0;
                        } else {
                            m = ((x / 8 + y / 8 + z / 8) % 3 == 0) ? 0 : 1 + ((x / 8) % materials);
                        }
                        section.set(x, y, z, m);
                    }
                }
            }
            section.compact();
            int[][] neighbors = null;
            if (random.nextBoolean()) {
                neighbors = new int[6][];
                for (int d = 0; d < 6; d++) {
                    if (random.nextBoolean()) {
                        neighbors[d] = new int[32];
                        for (int u = 0; u < 32; u++) {
                            neighbors[d][u] = random.nextInt();
                        }
                    }
                }
            }
            verify(section, neighbors);
        }
    }

    @Test
    void borderPlaneMatchesNeighborLayer() {
        LodSection neighbor = new LodSection(0);
        neighbor.set(31, 4, 9, 1);   // touches a section on its +X side, at (u = y 4, v = z 9)
        neighbor.set(0, 7, 2, 1);    // touches a section on its -X side
        neighbor.set(6, 0, 30, 10);  // translucent: not opaque
        int[] plane = BinaryGreedyMesher.borderPlane(neighbor, Quad.NEG_X, CLASSES, null);
        assertEquals(1 << 9, plane[4]);
        plane = BinaryGreedyMesher.borderPlane(neighbor, Quad.POS_X, CLASSES, null);
        assertEquals(1 << 2, plane[7]);
        plane = BinaryGreedyMesher.borderPlane(neighbor, Quad.POS_Y, CLASSES, null);
        for (int row : plane) {
            assertEquals(0, row);
        }
    }

    /**
     * Meshes and checks that the quads cover exactly the visible faces, once
     * each, with the right material.
     */
    private void verify(LodSection section, int[][] neighbors) {
        verify(section, neighbors, null);
    }

    /** Also checks each face's biome: its cell's for tinted materials, none otherwise. */
    private void verify(LodSection section, int[][] neighbors, IntPredicate tinted) {
        mesher.mesh(section, CLASSES, tinted, neighbors, out);
        Map<Long, Integer> covered = new HashMap<>();
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            for (int i = 0; i < out.count(d); i++) {
                long q = out.quads(d)[i];
                assertEquals(d, Quad.direction(q));
                int axis = Quad.axis(d);
                for (int a = 0; a < Quad.sizeU(q); a++) {
                    for (int b = 0; b < Quad.sizeV(q); b++) {
                        int x = Quad.x(q);
                        int y = Quad.y(q);
                        int z = Quad.z(q);
                        switch (axis) {
                            case 0 -> { y += a; z += b; }
                            case 1 -> { z += a; x += b; }
                            default -> { x += a; y += b; }
                        }
                        assertTrue(x < 32 && y < 32 && z < 32, "quad out of the section: " + Quad.toString(q));
                        long key = faceKey(x, y, z, d);
                        int biome = Quad.biome(q);
                        assertNull(covered.put(key, Quad.material(q) | (biome + 1) << 20),
                                "face covered twice: " + Quad.toString(q));
                    }
                }
            }
        }
        int expected = 0;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int m = section.material(x, y, z);
                    int c = CLASSES.classOf(m);
                    if (c == MaterialClassifier.AIR) {
                        continue;
                    }
                    for (int d = 0; d < 6; d++) {
                        int nx = x + STEP[d][0];
                        int ny = y + STEP[d][1];
                        int nz = z + STEP[d][2];
                        int nc;
                        if (nx < 0 || ny < 0 || nz < 0 || nx > 31 || ny > 31 || nz > 31) {
                            nc = neighborOpaque(neighbors, d, x, y, z) ? MaterialClassifier.OPAQUE : MaterialClassifier.AIR;
                        } else {
                            nc = CLASSES.classOf(section.material(nx, ny, nz));
                        }
                        boolean visible = c == MaterialClassifier.OPAQUE
                                ? nc != MaterialClassifier.OPAQUE
                                : nc == MaterialClassifier.AIR;
                        Integer got = covered.get(faceKey(x, y, z, d));
                        if (visible) {
                            expected++;
                            if (got == null) {
                                fail("missing face at " + x + "," + y + "," + z + " dir " + d);
                            }
                            int biome = tinted != null && tinted.test(m) ? section.biome(x >> 2, y >> 2, z >> 2) : -1;
                            assertEquals(m | (biome + 1) << 20, got.intValue(),
                                    "material or biome at " + x + "," + y + "," + z + " dir " + d);
                        } else if (got != null) {
                            fail("hidden face drawn at " + x + "," + y + "," + z + " dir " + d);
                        }
                    }
                }
            }
        }
        assertEquals(expected, covered.size());
        assertEquals(expected, mesher.facesEmitted());
    }

    private static boolean neighborOpaque(int[][] neighbors, int d, int x, int y, int z) {
        if (neighbors == null || neighbors[d] == null) {
            return false;
        }
        int u;
        int v;
        switch (d >> 1) {
            case 0 -> { u = y; v = z; }
            case 1 -> { u = z; v = x; }
            default -> { u = x; v = y; }
        }
        return (neighbors[d][u] >>> v & 1) != 0;
    }

    private static long faceKey(int x, int y, int z, int d) {
        return ((long) d << 15) | (y << 10) | (z << 5) | x;
    }
}
