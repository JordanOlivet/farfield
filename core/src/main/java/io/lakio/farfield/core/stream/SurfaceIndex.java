package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.storage.SectionStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.LongToIntFunction;

/**
 * Which sections of a region lie entirely under the surface (ADR-013,
 * ADR-068): a server streams only the surface unless its operator allows
 * full data, so a client cannot see caves or ores through the LODs.
 *
 * <p>Per section column: in each of its 32 x 32 voxel columns, the first
 * solid voxel from the sky down (air, fluids, glass, ice, plants and leaves
 * let the view through, so a sea floor counts, and the ground under a
 * canopy); the column's floor is the lowest of them. A section whose top
 * lies under the floor of its column and of the eight columns around is
 * hidden: terrain seen from the side, under an overhang or an arch, stays.
 * Cliffs stay (they span from the floor up), so do sea floors; caves under
 * the floor go. A voxel column with no solid voxel at all leaves every
 * section of its column, and of the columns around, visible; so does a
 * column on the region's edge, whose neighbors are not read.
 *
 * <p>Only the finest level hides sections. A coarse section and its children
 * found different floors (their voxels differ), so one level could be left
 * out where the other was sent; the renderer, which reaches a section only
 * through its parent and draws nothing where a child is missing, showed
 * holes there (the sky through new terrain, under canopies and arches).
 */
public final class SurfaceIndex {

    /** Reads a stored section, null if absent. */
    @FunctionalInterface
    public interface Sections {
        LodSection read(long key) throws IOException;
    }

    private SurfaceIndex() {
    }

    /** Whether a material stops the view from the sky; {@code seeThrough} ones (leaves) do not. */
    static boolean solid(MaterialClassifier classes, IntPredicate seeThrough, int material) {
        int c = classes.classOf(material);
        return (c == MaterialClassifier.OPAQUE || c == MaterialClassifier.PARTIAL || c == MaterialClassifier.SHAPED)
                && !seeThrough.test(material);
    }

    /** {@link #hidden(long[], Sections, MaterialClassifier, IntPredicate)} with no see-through solid. */
    public static List<Long> hidden(long[] keys, Sections sections, MaterialClassifier classes) throws IOException {
        return hidden(keys, sections, classes, m -> false);
    }

    /**
     * The keys among {@code keys} (one region's sections) that lie entirely
     * under the surface of their column and of the columns around; none on
     * a level other than the finest.
     *
     * @param seeThrough solid materials the view passes through all the same (leaves)
     */
    public static List<Long> hidden(long[] keys, Sections sections, MaterialClassifier classes,
                                    IntPredicate seeThrough) throws IOException {
        Map<Long, List<Long>> columns = new HashMap<>();
        for (long key : keys) {
            if (LodKey.level(key) == 0) {
                columns.computeIfAbsent(LodKey.pack(0, LodKey.x(key), 0, LodKey.z(key)), c -> new ArrayList<>()).add(key);
            }
        }
        Map<Long, Integer> floors = new HashMap<>();
        for (Map.Entry<Long, List<Long>> column : columns.entrySet()) {
            column.getValue().sort((a, b) -> Integer.compare(LodKey.y(b), LodKey.y(a)));
            floors.put(column.getKey(), floor(column.getValue(), sections, classes, seeThrough));
        }
        List<Long> hidden = new ArrayList<>();
        // A column without any section is not known here: everything around stays.
        LongToIntFunction floorOf = column -> floors.getOrDefault(column, Integer.MIN_VALUE);
        for (List<Long> column : columns.values()) {
            for (long key : column) {
                if (hidden(key, floorOf)) {
                    hidden.add(key);
                }
            }
        }
        return hidden;
    }

    /**
     * Whether one section lies under the surface by the rule of
     * {@link #hidden(long[], Sections, MaterialClassifier, IntPredicate)}:
     * a level-0 section, not on its region's edge, whose top lies under the
     * floors of its column and of the eight around. {@code floorOf} takes a
     * column ({@code LodKey.pack(0, x, 0, z)}) and gives its floor
     * ({@link #floor}), {@link Integer#MIN_VALUE} for a column without
     * sections or without a floor. Lets a server check a section it pushes
     * without reading its whole region.
     */
    public static boolean hidden(long key, LongToIntFunction floorOf) {
        if (LodKey.level(key) != 0) {
            return false;
        }
        int last = (1 << SectionStore.REGION_SHIFT) - 1;
        int x = LodKey.x(key);
        int z = LodKey.z(key);
        int lx = x & last;
        int lz = z & last;
        if (lx == 0 || lz == 0 || lx == last || lz == last) {
            return false;
        }
        int floor = Integer.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                floor = Math.min(floor, floorOf.applyAsInt(LodKey.pack(0, x + dx, 0, z + dz)));
            }
        }
        if (floor == Integer.MIN_VALUE) {
            return false;
        }
        return LodKey.y(key) * LodSection.SIZE + LodSection.SIZE - 1 < floor;
    }

    /**
     * The floor of one section column of level 0, from its sections listed
     * top down ({@link #floor(List, Sections, MaterialClassifier, IntPredicate)}).
     */
    public static int columnFloor(List<Long> topDown, Sections sections, MaterialClassifier classes,
                                  IntPredicate seeThrough) throws IOException {
        return floor(topDown, sections, classes, seeThrough);
    }

    /**
     * The lowest of the first solid voxels from the top, in voxels of the
     * column's level; {@link Integer#MIN_VALUE} when some voxel column has
     * none (everything stays visible). {@code column} is sorted top down.
     */
    static int floor(List<Long> column, Sections sections, MaterialClassifier classes, IntPredicate seeThrough)
            throws IOException {
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
                solidIndex[i] = solid(classes, seeThrough, section.paletteEntry(i));
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
