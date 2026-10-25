package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.MaterialClassifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which sections of a region lie entirely under the surface (ADR-013,
 * ADR-068): a server streams only the surface unless its operator allows
 * full data, so a client cannot see caves or ores through the LODs.
 *
 * <p>Per section column: in each of its 32 x 32 voxel columns, the first
 * solid voxel from the sky down (air, fluids, glass, ice and plants let the
 * view through, so a sea floor counts); the column's floor is the lowest of
 * them. A section whose top lies under its column's floor is hidden. Cliffs
 * stay (they span from the floor up), so do sea floors; caves under the
 * floor go. A voxel column with no solid voxel at all leaves every section
 * of its column visible.
 */
public final class SurfaceIndex {

    /** Reads a stored section, null if absent. */
    @FunctionalInterface
    public interface Sections {
        LodSection read(long key) throws IOException;
    }

    private SurfaceIndex() {
    }

    /** Whether a material stops the view from the sky. */
    static boolean solid(MaterialClassifier classes, int material) {
        int c = classes.classOf(material);
        return c == MaterialClassifier.OPAQUE || c == MaterialClassifier.PARTIAL || c == MaterialClassifier.SHAPED;
    }

    /**
     * The keys among {@code keys} (one region's sections) that lie entirely
     * under their column's surface.
     */
    public static List<Long> hidden(long[] keys, Sections sections, MaterialClassifier classes) throws IOException {
        Map<Long, List<Long>> columns = new HashMap<>();
        for (long key : keys) {
            columns.computeIfAbsent(LodKey.pack(LodKey.level(key), LodKey.x(key), 0, LodKey.z(key)),
                    c -> new ArrayList<>()).add(key);
        }
        List<Long> hidden = new ArrayList<>();
        for (List<Long> column : columns.values()) {
            column.sort((a, b) -> Integer.compare(LodKey.y(b), LodKey.y(a)));
            int floor = floor(column, sections, classes);
            if (floor == Integer.MIN_VALUE) {
                continue;
            }
            for (long key : column) {
                int top = LodKey.y(key) * LodSection.SIZE + LodSection.SIZE - 1;
                if (top < floor) {
                    hidden.add(key);
                }
            }
        }
        return hidden;
    }

    /**
     * The lowest of the first solid voxels from the top, in voxels of the
     * column's level; {@link Integer#MIN_VALUE} when some voxel column has
     * none (everything stays visible). {@code column} is sorted top down.
     */
    static int floor(List<Long> column, Sections sections, MaterialClassifier classes) throws IOException {
        final int s = LodSection.SIZE;
        boolean[] found = new boolean[s * s];
        int left = s * s;
        int floor = Integer.MAX_VALUE;
        for (long key : column) {
            LodSection section = sections.read(key);
            if (section == null) {
                continue;
            }
            int base = LodKey.y(key) * s;
            // Materials per palette index, classified once.
            boolean[] solidIndex = new boolean[section.paletteSize()];
            boolean any = false;
            for (int i = 0; i < solidIndex.length; i++) {
                solidIndex[i] = solid(classes, section.paletteEntry(i));
                any |= solidIndex[i];
            }
            if (!any) {
                continue;
            }
            for (int z = 0; z < s; z++) {
                for (int x = 0; x < s; x++) {
                    int c = z * s + x;
                    if (found[c]) {
                        continue;
                    }
                    for (int y = s - 1; y >= 0; y--) {
                        if (solidIndex[section.paletteIndex(x, y, z)]) {
                            found[c] = true;
                            left--;
                            floor = Math.min(floor, base + y);
                            break;
                        }
                    }
                }
            }
            if (left == 0) {
                return floor;
            }
        }
        return Integer.MIN_VALUE;
    }
}
