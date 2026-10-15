package io.lakio.farfield.core.mesh;

import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.util.Bits;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * Meshes a {@link LodSection} into packed quads with binary greedy meshing:
 *
 * <ol>
 *   <li>Occupancy as bit columns: for each axis, one 32-bit column per (U, V)
 *       position, built from X rows and 32x32 bit transposes, for opaque and
 *       translucent voxels.</li>
 *   <li>Face culling 32 voxels at a time: a face is visible where a voxel's
 *       neighbor along the axis is not opaque (translucent voxels also hide
 *       translucent neighbors). Neighbor sections contribute their border
 *       layer as one bit per column: opaque voxels for every face, and
 *       optionally solid (opaque or translucent) voxels for translucent
 *       faces only, so water bodies spanning sections show no inner walls
 *       while opaque borders stay closed.</li>
 *   <li>Face columns are transposed into one 32x32 plane per depth, split by
 *       material, and each plane is merged greedily with bit operations:
 *       take the lowest run of set bits in a row, extend it over the next
 *       rows while they contain the whole run.</li>
 * </ol>
 *
 * <p>Quads only merge faces of the same material and light, and for
 * materials whose color depends on the biome (grass, leaves, water) of the
 * same biome cell (4x4x4 voxels); those quads carry their biome. A face's
 * light is the sky and block light of the voxel in front of it; in front of
 * a section border, the face voxel's own light when it is translucent
 * (water), else full sky light (open terrain at section tops). Ambient
 * occlusion is not part of the merge key.
 *
 * <p>Not thread-safe: one instance per worker; all buffers are reused.
 */
public final class BinaryGreedyMesher {

    private static final int S = LodSection.SIZE;
    private static final int PLANE = S * S;

    private int[] opaque = new int[64];
    private int[] translucent = new int[64];

    /** Opaque and translucent X rows, index {@code y * 32 + z}, bit x. */
    private final int[] rowsOpaque = new int[PLANE];
    private final int[] rowsTranslucent = new int[PLANE];
    /** Columns of the current axis, index {@code u * 32 + v}, bit = depth. */
    private final int[] colOpaque = new int[PLANE];
    private final int[] colTranslucent = new int[PLANE];
    /** Visible faces of the current direction, same layout as the columns. */
    private final int[] faces = new int[PLANE];
    /** Faces of the current direction per depth: index {@code depth * 32 + u}, bit v. */
    private final int[] planes = new int[PLANE];
    private final int[] scratch = new int[S];

    private int[] slotIndex = new int[16];
    private int[] slotBiome = new int[16];
    private int[] slotLight = new int[16];
    private int[] slotRows = new int[16 * S];
    private boolean[] tintedIndex = new boolean[64];
    private boolean anyTinted;
    /** Tinted faces split by biome cell (the section's cells differ). */
    private boolean splitBiomes;
    /** The section's biome when all its cells share one. */
    private int sectionBiome;
    private int slotCount;

    private long facesEmitted;

    /**
     * Meshes a section.
     *
     * @param neighborOpaque per direction (see {@link Quad}), the opacity of
     *                       the adjacent layer of the neighbor section, as 32
     *                       rows indexed by U with bits along V in that
     *                       direction's plane axes (see {@link #borderPlane});
     *                       null, or a null entry, means no opaque neighbor
     */
    public void mesh(LodSection section, MaterialClassifier classifier, int[][] neighborOpaque, MeshBuffer out) {
        mesh(section, classifier, null, neighborOpaque, null, out);
    }

    /**
     * Meshes a section; the quads of the materials {@code tinted} accepts
     * carry their biome.
     *
     * @param tinted        materials whose color depends on the biome; null for none
     * @param neighborSolid like {@code neighborOpaque}, the solid (opaque or
     *                      translucent) voxels of the neighbor layers; they
     *                      only hide translucent faces. Null, or a null
     *                      entry, culls translucent faces against
     *                      {@code neighborOpaque} only
     */
    public void mesh(LodSection section, MaterialClassifier classifier, IntPredicate tinted, int[][] neighborOpaque,
                     int[][] neighborSolid, MeshBuffer out) {
        out.clear();
        facesEmitted = 0;
        if (!classify(section, classifier)) {
            return;
        }
        classifyTints(section, tinted);
        buildRows(section);

        for (int axis = 0; axis < 3; axis++) {
            buildColumns(axis);
            for (int sign = 0; sign < 2; sign++) {
                int direction = axis * 2 + sign;
                int[] minus = neighborOpaque == null ? null : neighborOpaque[axis * 2];
                int[] plus = neighborOpaque == null ? null : neighborOpaque[axis * 2 + 1];
                int[] minusSolid = neighborSolid == null ? null : neighborSolid[axis * 2];
                int[] plusSolid = neighborSolid == null ? null : neighborSolid[axis * 2 + 1];
                if (cullFaces(sign == 1, minus, plus, minusSolid, plusSolid)) {
                    buildPlanes();
                    mergePlanes(section, direction, out);
                }
            }
        }
    }

    /**
     * Copies the opaque voxels of the last meshed section into {@code out}
     * (1024 ints): index {@code y * 32 + z}, bit x. Only valid when the last
     * mesh produced quads. Renderers use it for ambient occlusion.
     */
    public void copyOpaqueRows(int[] out) {
        System.arraycopy(rowsOpaque, 0, out, 0, PLANE);
    }

    /** Faces covered by the last mesh's quads (before merging, for statistics). */
    public long facesEmitted() {
        return facesEmitted;
    }

    /** Fills the per-palette-index class tables; returns false if the section has nothing to draw. */
    private boolean classify(LodSection section, MaterialClassifier classifier) {
        int n = section.paletteSize();
        if (opaque.length < n) {
            opaque = new int[Math.max(n, opaque.length * 2)];
            translucent = new int[opaque.length];
        }
        boolean anyVisible = false;
        for (int i = 0; i < n; i++) {
            int c = classifier.classOf(section.paletteEntry(i));
            opaque[i] = c == MaterialClassifier.OPAQUE ? 1 : 0;
            translucent[i] = c == MaterialClassifier.TRANSLUCENT ? 1 : 0;
            anyVisible |= c != MaterialClassifier.AIR;
        }
        if (!anyVisible) {
            return false;
        }
        // All bricks uniform air (palette entries may be unused): nothing to draw.
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            if (!section.isUniform(b)) {
                return true;
            }
            int i = section.uniformIndex(b);
            if (opaque[i] != 0 || translucent[i] != 0) {
                return true;
            }
        }
        return false;
    }

    private void classifyTints(LodSection section, IntPredicate tinted) {
        int n = section.paletteSize();
        if (tintedIndex.length < n) {
            tintedIndex = new boolean[Math.max(n, tintedIndex.length * 2)];
        }
        anyTinted = false;
        for (int i = 0; i < n; i++) {
            boolean t = tinted != null && tinted.test(section.paletteEntry(i));
            tintedIndex[i] = t;
            anyTinted |= t;
        }
        splitBiomes = anyTinted && section.biomeCells() != null;
        sectionBiome = section.uniformBiome();
    }

    private void buildRows(LodSection section) {
        Arrays.fill(rowsOpaque, 0);
        Arrays.fill(rowsTranslucent, 0);
        int bits = section.bits();
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int by = 0; by < LodSection.BRICKS_PER_AXIS; by++) {
            for (int bz = 0; bz < LodSection.BRICKS_PER_AXIS; bz++) {
                for (int bx = 0; bx < LodSection.BRICKS_PER_AXIS; bx++) {
                    int b = LodSection.brickIndex(bx, by, bz);
                    int xShift = bx * 8;
                    long[] data = section.brickData(b);
                    if (data == null) {
                        int i = section.uniformIndex(b);
                        int[] target = opaque[i] != 0 ? rowsOpaque : translucent[i] != 0 ? rowsTranslucent : null;
                        if (target == null) {
                            continue;
                        }
                        int byteMask = 0xFF << xShift;
                        for (int ly = 0; ly < 8; ly++) {
                            int row = (by * 8 + ly) * S + bz * 8;
                            for (int lz = 0; lz < 8; lz++) {
                                target[row + lz] |= byteMask;
                            }
                        }
                        continue;
                    }
                    // Sequential unpack: 8 voxels make one byte of an X row.
                    int word = 0;
                    long current = data[0];
                    int left = perLong;
                    for (int ly = 0; ly < 8; ly++) {
                        int row = (by * 8 + ly) * S + bz * 8;
                        for (int lz = 0; lz < 8; lz++) {
                            int op = 0;
                            int tr = 0;
                            for (int lx = 0; lx < 8; lx++) {
                                if (left == 0) {
                                    current = data[++word];
                                    left = perLong;
                                }
                                int i = (int) (current & mask);
                                current >>>= bits;
                                left--;
                                op |= opaque[i] << lx;
                                tr |= translucent[i] << lx;
                            }
                            rowsOpaque[row + lz] |= op << xShift;
                            rowsTranslucent[row + lz] |= tr << xShift;
                        }
                    }
                }
            }
        }
    }

    /** Fills the column arrays of an axis from the X rows. */
    private void buildColumns(int axis) {
        switch (axis) {
            case 0 -> {
                // X columns at (u = y, v = z) are the rows themselves.
                System.arraycopy(rowsOpaque, 0, colOpaque, 0, PLANE);
                System.arraycopy(rowsTranslucent, 0, colTranslucent, 0, PLANE);
            }
            case 1 -> {
                // Y columns at (u = z, v = x): for each z, transpose the (y, x) matrix.
                transposeGather(rowsOpaque, colOpaque, true);
                transposeGather(rowsTranslucent, colTranslucent, true);
            }
            default -> {
                // Z columns at (u = x, v = y): for each y, transpose the (z, x) matrix.
                transposeGather(rowsOpaque, colOpaque, false);
                transposeGather(rowsTranslucent, colTranslucent, false);
            }
        }
    }

    private void transposeGather(int[] rows, int[] columns, boolean yAxis) {
        int[] m = scratch;
        for (int outer = 0; outer < S; outer++) {
            int any = 0;
            for (int inner = 0; inner < S; inner++) {
                // Y axis: outer = z, inner = y. Z axis: outer = y, inner = z.
                int v = yAxis ? rows[inner * S + outer] : rows[outer * S + inner];
                m[inner] = v;
                any |= v;
            }
            if (any != 0) {
                Bits.transpose32(m, 0);
            }
            for (int x = 0; x < S; x++) {
                int v = any == 0 ? 0 : m[x];
                if (yAxis) {
                    columns[outer * S + x] = v;      // u = z, v = x
                } else {
                    columns[x * S + outer] = v;      // u = x, v = y
                }
            }
        }
    }

    /** Computes the visible faces of one direction; returns false if there are none. */
    private boolean cullFaces(boolean positive, int[] minusNeighbor, int[] plusNeighbor, int[] minusSolid,
                              int[] plusSolid) {
        int any = 0;
        for (int u = 0; u < S; u++) {
            int minusRow = minusNeighbor == null ? 0 : minusNeighbor[u];
            int plusRow = plusNeighbor == null ? 0 : plusNeighbor[u];
            int minusSolidRow = minusSolid == null ? 0 : minusSolid[u];
            int plusSolidRow = plusSolid == null ? 0 : plusSolid[u];
            for (int v = 0; v < S; v++) {
                int i = u * S + v;
                int op = colOpaque[i];
                int tr = colTranslucent[i];
                if ((op | tr) == 0) {
                    faces[i] = 0;
                    continue;
                }
                // Bit k + 1 holds the voxel at depth k; bits 0 and 33 the neighbors.
                long c = (op & 0xFFFFFFFFL) << 1
                        | (minusRow >>> v & 1L)
                        | (plusRow >>> v & 1L) << 33;
                // Neighbor solid bits only enter the translucent test; bits 0
                // and 33 never survive the shift back, so they emit nothing.
                long t = (tr & 0xFFFFFFFFL) << 1
                        | (minusSolidRow >>> v & 1L)
                        | (plusSolidRow >>> v & 1L) << 33;
                long solid = c | t;
                long visible = positive
                        ? (c & ~(c >>> 1)) | (t & ~(solid >>> 1))
                        : (c & ~(c << 1)) | (t & ~(solid << 1));
                int f = (int) (visible >>> 1);
                faces[i] = f;
                any |= f;
            }
        }
        return any != 0;
    }

    /** Transposes the face columns into one plane per depth. */
    private void buildPlanes() {
        int[] m = scratch;
        for (int u = 0; u < S; u++) {
            int any = 0;
            for (int v = 0; v < S; v++) {
                int f = faces[u * S + v];
                m[v] = f;
                any |= f;
            }
            if (any != 0) {
                Bits.transpose32(m, 0);
                for (int depth = 0; depth < S; depth++) {
                    planes[depth * S + u] = m[depth];
                }
            } else {
                for (int depth = 0; depth < S; depth++) {
                    planes[depth * S + u] = 0;
                }
            }
        }
    }

    private void mergePlanes(LodSection section, int direction, MeshBuffer out) {
        int axis = direction >> 1;
        for (int depth = 0; depth < S; depth++) {
            int base = depth * S;
            int any = 0;
            for (int u = 0; u < S; u++) {
                any |= planes[base + u];
            }
            if (any == 0) {
                continue;
            }
            splitByMaterial(section, axis, (direction & 1) != 0 ? 1 : -1, depth, base);
            for (int slot = 0; slot < slotCount; slot++) {
                int index = slotIndex[slot];
                int biome = !tintedIndex[index] ? -1 : splitBiomes ? slotBiome[slot] : sectionBiome;
                greedy(slotRows, slot * S, direction, axis, depth, section.paletteEntry(index), biome,
                        slotLight[slot], out);
            }
        }
    }

    /**
     * Sorts the face bits of one plane into per-palette-index planes (slots);
     * tinted materials get one slot per biome when the section's cells differ.
     */
    private void splitByMaterial(LodSection section, int axis, int depthStep, int depth, int base) {
        slotCount = 0;
        int lastIndex = -1;
        int lastSlot = -1;
        for (int u = 0; u < S; u++) {
            int row = planes[base + u];
            while (row != 0) {
                int v = Integer.numberOfTrailingZeros(row);
                row &= row - 1;
                int x;
                int y;
                int z;
                switch (axis) {
                    case 0 -> {
                        x = depth;
                        y = u;
                        z = v;
                    }
                    case 1 -> {
                        x = v;
                        y = depth;
                        z = u;
                    }
                    default -> {
                        x = u;
                        y = v;
                        z = depth;
                    }
                }
                int index = section.paletteIndex(x, y, z);
                int biome = splitBiomes && tintedIndex[index] ? section.biome(x >> 2, y >> 2, z >> 2) : -1;
                int light = light(section, x, y, z, index, axis, depthStep);
                int slot;
                if (index == lastIndex && slotBiome[lastSlot] == biome && slotLight[lastSlot] == light) {
                    slot = lastSlot;
                } else {
                    slot = -1;
                    for (int s = 0; s < slotCount; s++) {
                        if (slotIndex[s] == index && slotBiome[s] == biome && slotLight[s] == light) {
                            slot = s;
                            break;
                        }
                    }
                    if (slot < 0) {
                        slot = newSlot(index, biome, light);
                    }
                    lastIndex = index;
                    lastSlot = slot;
                }
                slotRows[slot * S + u] |= 1 << v;
            }
        }
    }

    /** Sky light << 4 | block light in front of a face (see the class comment). */
    private int light(LodSection section, int x, int y, int z, int index, int axis, int step) {
        int nx = x;
        int ny = y;
        int nz = z;
        switch (axis) {
            case 0 -> nx += step;
            case 1 -> ny += step;
            default -> nz += step;
        }
        if (nx >= 0 && ny >= 0 && nz >= 0 && nx < S && ny < S && nz < S) {
            return section.skyLight().get(nx, ny, nz) << 4 | section.blockLight().get(nx, ny, nz);
        }
        if (translucent[index] != 0) {
            return section.skyLight().get(x, y, z) << 4 | section.blockLight().get(x, y, z);
        }
        return 15 << 4;
    }

    private int newSlot(int index, int biome, int light) {
        if (slotCount == slotIndex.length) {
            slotIndex = Arrays.copyOf(slotIndex, slotCount * 2);
            slotBiome = Arrays.copyOf(slotBiome, slotCount * 2);
            slotLight = Arrays.copyOf(slotLight, slotCount * 2);
            slotRows = Arrays.copyOf(slotRows, slotCount * 2 * S);
        }
        int slot = slotCount++;
        slotIndex[slot] = index;
        slotBiome[slot] = biome;
        slotLight[slot] = light;
        Arrays.fill(slotRows, slot * S, slot * S + S, 0);
        return slot;
    }

    /** Greedy merge of one 32x32 plane (rows by U, bits by V), consuming it. */
    private void greedy(int[] rows, int off, int direction, int axis, int depth, int material, int biome,
                        int light, MeshBuffer out) {
        for (int u = 0; u < S; u++) {
            int row = rows[off + u];
            while (row != 0) {
                int v = Integer.numberOfTrailingZeros(row);
                int width = Integer.numberOfTrailingZeros(~(row >>> v));
                int runMask = width == 32 ? -1 : ((1 << width) - 1) << v;
                row &= ~runMask;
                int height = 1;
                while (u + height < S && (rows[off + u + height] & runMask) == runMask) {
                    rows[off + u + height] &= ~runMask;
                    height++;
                }
                facesEmitted += (long) width * height;
                long quad = switch (axis) {
                    case 0 -> Quad.pack(depth, u, v, height, width, direction, material, biome, light >> 4, light & 15);
                    case 1 -> Quad.pack(v, depth, u, height, width, direction, material, biome, light >> 4, light & 15);
                    default -> Quad.pack(u, v, depth, height, width, direction, material, biome, light >> 4, light & 15);
                };
                out.add(direction, quad);
            }
        }
    }

    /**
     * Opacity of the layer of {@code neighbor} that touches a section across
     * {@code direction} (the direction from the meshed section to the
     * neighbor), in the layout {@link #mesh} expects: 32 rows by U, bits by V.
     */
    public static int[] borderPlane(LodSection neighbor, int direction, MaterialClassifier classifier, int[] out) {
        return borderPlane(neighbor, direction, classifier, false, out);
    }

    /**
     * Like {@link #borderPlane(LodSection, int, MaterialClassifier, int[])};
     * with {@code solid}, translucent voxels count as well.
     */
    public static int[] borderPlane(LodSection neighbor, int direction, MaterialClassifier classifier, boolean solid,
                                    int[] out) {
        int[] plane = out != null ? out : new int[S];
        Arrays.fill(plane, 0);
        int axis = direction >> 1;
        // The neighbor in the + direction touches with its layer 0, the other with layer 31.
        int layer = (direction & 1) != 0 ? 0 : S - 1;
        for (int u = 0; u < S; u++) {
            int row = 0;
            for (int v = 0; v < S; v++) {
                int material = switch (axis) {
                    case 0 -> neighbor.material(layer, u, v);
                    case 1 -> neighbor.material(v, layer, u);
                    default -> neighbor.material(u, v, layer);
                };
                int c = classifier.classOf(material);
                if (c == MaterialClassifier.OPAQUE || solid && c == MaterialClassifier.TRANSLUCENT) {
                    row |= 1 << v;
                }
            }
            plane[u] = row;
        }
        return plane;
    }
}
