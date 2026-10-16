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

    // Materials: 0 air, 1..9 opaque, 10..12 translucent, 20..22 partial.
    private static final MaterialClassifier CLASSES = m -> m == 0 ? MaterialClassifier.AIR
            : m < 10 ? MaterialClassifier.OPAQUE : m < 20 ? MaterialClassifier.TRANSLUCENT
            : MaterialClassifier.PARTIAL;

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
        assertEquals(-1, Quad.biome(q));
        assertEquals(15, Quad.skyLight(q));
        assertEquals(0, Quad.blockLight(q));
        assertEquals(Quad.FULL_FILL, Quad.fill(q));
        q = Quad.pack(0, 0, 0, 1, 1, Quad.POS_Y, Quad.MAX_MATERIAL, Quad.MAX_BIOME, 7, 13, 3);
        assertEquals(Quad.MAX_MATERIAL, Quad.material(q));
        assertEquals(Quad.MAX_BIOME, Quad.biome(q));
        assertEquals(7, Quad.skyLight(q));
        assertEquals(13, Quad.blockLight(q));
        assertEquals(3, Quad.fill(q));

        q = Quad.pack(1, 2, 3, 4, 5, Quad.NEG_Y, 77, Quad.MAX_BIOME);
        assertEquals(Quad.MAX_BIOME, Quad.biome(q));
        assertEquals(77, Quad.material(q));
        assertEquals(Quad.NEG_Y, Quad.direction(q));
        assertEquals(0, Quad.biome(Quad.pack(0, 0, 0, 1, 1, 0, 1, 0)));
        assertEquals(-1, Quad.biome(Quad.pack(0, 0, 0, 1, 1, 0, 1, Quad.MAX_BIOME + 1)));
    }

    @Test
    void quadsCarryTheLightInFrontOfTheirFaces() {
        // A floor whose air above is dark on one half: the top splits by light.
        LodSection section = new LodSection(0);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                section.set(x, 0, z, 1);
                section.skyLight().set(x, 1, z, x < 16 ? 15 : 4);
                section.blockLight().set(x, 1, z, x < 16 ? 0 : 9);
            }
        }
        section.compact();
        mesher.mesh(section, CLASSES, null, out);
        assertEquals(2, out.count(Quad.POS_Y));
        for (int i = 0; i < 2; i++) {
            long q = out.quads(Quad.POS_Y)[i];
            boolean lit = Quad.x(q) < 16;
            assertEquals(lit ? 15 : 4, Quad.skyLight(q));
            assertEquals(lit ? 0 : 9, Quad.blockLight(q));
        }
        // The bottom face lies on the section border: opaque, so full sky light.
        assertEquals(1, out.count(Quad.NEG_Y));
        assertEquals(15, Quad.skyLight(out.quads(Quad.NEG_Y)[0]));
    }

    @Test
    void borderFacesTakeTheNeighborLayerLight() {
        // A full stone section: every face lies on a border. Each neighbor
        // has a light pattern on its touching layer and a decoy elsewhere.
        LodSection section = new LodSection(0);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    section.set(x, y, z, 1);
                }
            }
        }
        section.compact();
        byte[][] light = new byte[6][];
        for (int d = 0; d < 6; d++) {
            int axis = d >> 1;
            int layer = (d & 1) != 0 ? 0 : 31;
            LodSection neighbor = new LodSection(0);
            for (int a = 0; a < 32; a++) {
                for (int b = 0; b < 32; b++) {
                    for (int c = 0; c < 32; c++) {
                        // (a, b, c) = (depth, u, v) in the direction's axes.
                        int x = axis == 0 ? a : axis == 1 ? c : b;
                        int y = axis == 0 ? b : axis == 1 ? a : c;
                        int z = axis == 0 ? c : axis == 1 ? b : a;
                        boolean touching = a == layer;
                        neighbor.skyLight().set(x, y, z, touching ? expectedSky(d, b, c) : 9);
                        neighbor.blockLight().set(x, y, z, touching ? expectedBlock(b, c) : 9);
                    }
                }
            }
            light[d] = BinaryGreedyMesher.borderLight(neighbor, d, null);
        }
        mesher.mesh(section, CLASSES, null, null, null, light, out);
        for (int d = 0; d < 6; d++) {
            assertTrue(out.count(d) > 0, "faces of direction " + d);
            for (int i = 0; i < out.count(d); i++) {
                long q = out.quads(d)[i];
                int u = switch (d >> 1) {
                    case 0 -> Quad.y(q);
                    case 1 -> Quad.z(q);
                    default -> Quad.x(q);
                };
                int v = switch (d >> 1) {
                    case 0 -> Quad.z(q);
                    case 1 -> Quad.x(q);
                    default -> Quad.y(q);
                };
                assertEquals(expectedSky(d, u, v), Quad.skyLight(q), "sky light, direction " + d);
                assertEquals(expectedBlock(u, v), Quad.blockLight(q), "block light, direction " + d);
            }
        }

        // Without neighbor light, opaque border faces keep full sky light.
        mesher.mesh(section, CLASSES, null, out);
        assertEquals(15, Quad.skyLight(out.quads(Quad.POS_X)[0]));
    }

    private static int expectedSky(int direction, int u, int v) {
        return (direction * 5 + u * 3 + v) & 15;
    }

    private static int expectedBlock(int u, int v) {
        return (u ^ v) & 15;
    }

    @Test
    void partialVoxelsHideOnlyTheTopBelowThem() {
        // Stone floor, a snow layer on half of it, a stone wall beside the snow.
        LodSection section = new LodSection(0);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                section.set(x, 0, z, 1);
                if (x < 16) {
                    section.set(x, 1, z, 20);
                }
            }
            section.set(16, 1, z, 2);
        }
        section.compact();
        mesher.mesh(section, CLASSES, null, out);
        boolean floorTopUnderSnow = false;
        boolean wallSideTowardSnow = false;
        for (int i = 0; i < out.count(Quad.POS_Y); i++) {
            long q = out.quads(Quad.POS_Y)[i];
            floorTopUnderSnow |= Quad.material(q) == 1 && Quad.y(q) == 0 && Quad.x(q) < 16;
        }
        for (int i = 0; i < out.count(Quad.NEG_X); i++) {
            long q = out.quads(Quad.NEG_X)[i];
            wallSideTowardSnow |= Quad.material(q) == 2 && Quad.x(q) == 16;
        }
        assertTrue(!floorTopUnderSnow, "the snow hides the floor top under it");
        assertTrue(wallSideTowardSnow, "the snow does not hide the wall beside it");
        verify(section, null);
    }

    @Test
    void lowFillVoxelsMeshAsPartialWithTheirFill() {
        // A coarse floor (fill full) under a coarse snow cover filled to 3/16:
        // the cover hides the floor's top, the cover's top carries its fill
        // (2 eighths), and a full wall beside the cover keeps its side.
        LodSection section = new LodSection(0);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                section.set(x, 0, z, 1);
                if (x < 16) {
                    section.set(x, 1, z, 3);
                    section.fill().set(x, 1, z, 2);
                }
            }
            section.set(16, 1, z, 2);
        }
        section.compact();
        mesher.mesh(section, CLASSES, null, out);
        boolean floorTopUnderCover = false;
        boolean coverTop = false;
        for (int i = 0; i < out.count(Quad.POS_Y); i++) {
            long q = out.quads(Quad.POS_Y)[i];
            floorTopUnderCover |= Quad.material(q) == 1 && Quad.x(q) < 16;
            if (Quad.material(q) == 3) {
                coverTop = true;
                assertEquals(2, Quad.fill(q));
            }
        }
        boolean wallSide = false;
        for (int i = 0; i < out.count(Quad.NEG_X); i++) {
            long q = out.quads(Quad.NEG_X)[i];
            wallSide |= Quad.material(q) == 2 && Quad.x(q) == 16;
        }
        assertTrue(!floorTopUnderCover, "the cover hides the floor top");
        assertTrue(coverTop, "the cover's top is drawn");
        assertTrue(wallSide, "the wall beside the cover keeps its side");
    }

    @Test
    void lowFillUnderASolidVoxelStaysFull() {
        // A low-fill voxel with an opaque voxel above it is not lowered: its
        // sides keep the full height, so no slit opens under the overhang.
        LodSection section = new LodSection(0);
        section.set(5, 5, 5, 1);
        section.fill().set(5, 5, 5, 3);
        section.set(5, 6, 5, 2);
        section.compact();
        mesher.mesh(section, CLASSES, null, out);
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            for (int i = 0; i < out.count(d); i++) {
                long q = out.quads(d)[i];
                if (Quad.material(q) == 1) {
                    assertEquals(Quad.FULL_FILL, Quad.fill(q), "fill of " + Quad.toString(q));
                }
            }
        }
        assertEquals(0, countMaterial(Quad.POS_Y, 1), "the covered top stays hidden");
    }

    private int countMaterial(int direction, int material) {
        int n = 0;
        for (int i = 0; i < out.count(direction); i++) {
            if (Quad.material(out.quads(direction)[i]) == material) {
                n++;
            }
        }
        return n;
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
        mesher.mesh(section, CLASSES, m -> m == 3, null, null, out);
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
        mesher.mesh(uniform, CLASSES, m -> m == 3, null, null, out);
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
            verify(section, null, null, m -> m % 2 == 0);
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
                            if (m > 0 && random.nextInt(4) == 0) {
                                m = 20 + random.nextInt(3);
                            }
                        } else if (round % 3 == 1) {
                            int top = 10 + ((x / 4 + z / 4) % 6);
                            m = y < top ? 1 + (y % materials) : y == top && (x + z) % 3 == 0 ? 21
                                    : y < 18 ? 10 : 0;
                        } else {
                            m = ((x / 8 + y / 8 + z / 8) % 3 == 0) ? 0 : 1 + ((x / 8) % materials);
                        }
                        section.set(x, y, z, m);
                    }
                }
            }
            section.compact();
            int[][] neighbors = null;
            int[][] solid = null;
            if (random.nextBoolean()) {
                neighbors = new int[6][];
                solid = random.nextBoolean() ? new int[6][] : null;
                for (int d = 0; d < 6; d++) {
                    if (random.nextBoolean()) {
                        neighbors[d] = new int[32];
                        for (int u = 0; u < 32; u++) {
                            neighbors[d][u] = random.nextInt();
                        }
                    }
                    if (solid != null && random.nextBoolean()) {
                        // Solid layers include the opaque voxels.
                        solid[d] = new int[32];
                        for (int u = 0; u < 32; u++) {
                            solid[d][u] = random.nextInt() | (neighbors[d] == null ? 0 : neighbors[d][u]);
                        }
                    }
                }
            }
            verify(section, neighbors, solid, null);
        }
    }

    @Test
    void waterSpanningSectionsHasNoInnerWalls() {
        LodSection water = new LodSection(0);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    water.set(x, y, z, 10);
                }
            }
        }
        int[] full = new int[32];
        Arrays.fill(full, -1);
        int[][] solid = new int[6][];
        for (int d = 0; d < 6; d++) {
            solid[d] = d == Quad.POS_Y ? null : full;
        }
        mesher.mesh(water, CLASSES, null, null, solid, out);
        // Water all around and below: only the top shows.
        assertEquals(1, out.total());
        assertEquals(1, out.count(Quad.POS_Y));
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
        plane = BinaryGreedyMesher.borderPlane(neighbor, Quad.POS_Y, CLASSES, true, null);
        assertEquals(1 << 6, plane[30]);
    }

    /**
     * Meshes and checks that the quads cover exactly the visible faces, once
     * each, with the right material.
     */
    private void verify(LodSection section, int[][] neighbors) {
        verify(section, neighbors, null, null);
    }

    /**
     * Also checks each face's biome (its cell's for tinted materials, none
     * otherwise) and the culling of translucent faces against solid neighbors.
     */
    private void verify(LodSection section, int[][] neighbors, int[][] solid, IntPredicate tinted) {
        mesher.mesh(section, CLASSES, tinted, neighbors, solid, out);
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
                            nc = neighborBit(neighbors, d, x, y, z) ? MaterialClassifier.OPAQUE
                                    : neighborBit(solid, d, x, y, z) ? MaterialClassifier.TRANSLUCENT
                                    : MaterialClassifier.AIR;
                        } else {
                            nc = CLASSES.classOf(section.material(nx, ny, nz));
                        }
                        boolean visible = switch (c) {
                            case MaterialClassifier.OPAQUE -> nc != MaterialClassifier.OPAQUE
                                    && !(d == Quad.POS_Y && nc == MaterialClassifier.PARTIAL);
                            case MaterialClassifier.PARTIAL -> nc != MaterialClassifier.OPAQUE;
                            default -> nc == MaterialClassifier.AIR || nc == MaterialClassifier.PARTIAL;
                        };
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

    private static boolean neighborBit(int[][] neighbors, int d, int x, int y, int z) {
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
