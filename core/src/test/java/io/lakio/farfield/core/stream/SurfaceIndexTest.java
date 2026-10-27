package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceIndexTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 2;
    private static final int LEAVES = 3;
    private static final MaterialClassifier CLASSES = m -> m == STONE || m == LEAVES ? MaterialClassifier.OPAQUE
            : m == WATER ? MaterialClassifier.TRANSLUCENT : MaterialClassifier.AIR;

    /** A section filled with stone below {@code ground} and {@code above} from there to {@code top}. */
    private static LodSection layered(int ground, int above, int top) {
        LodSection s = new LodSection(AIR);
        for (int y = 0; y < LodSection.SIZE; y++) {
            int m = y < ground ? STONE : y < top ? above : AIR;
            if (m == AIR) {
                continue;
            }
            for (int z = 0; z < LodSection.SIZE; z++) {
                for (int x = 0; x < LodSection.SIZE; x++) {
                    s.set(x, y, z, m);
                }
            }
        }
        return s;
    }

    /**
     * Land columns over x and z from 3 to 9 (inside one region): ground at
     * y 40 in section 1, stone in sections 0 and -1 under it.
     */
    private static Map<Long, LodSection> land() {
        Map<Long, LodSection> store = new HashMap<>();
        for (int z = 3; z <= 9; z++) {
            for (int x = 3; x <= 9; x++) {
                store.put(LodKey.pack(0, x, 1, z), layered(8, AIR, 8));
                store.put(LodKey.pack(0, x, 0, z), layered(32, AIR, 32));
                store.put(LodKey.pack(0, x, -1, z), layered(32, AIR, 32));
            }
        }
        return store;
    }

    private static Set<Long> hidden(Map<Long, LodSection> store) throws IOException {
        long[] keys = store.keySet().stream().mapToLong(Long::longValue).toArray();
        return Set.copyOf(SurfaceIndex.hidden(keys, store::get, CLASSES, m -> m == LEAVES));
    }

    @Test
    void sectionsUnderTheFloorAroundHide() throws IOException {
        // The columns on the area's rim have a neighbor without sections: they stay.
        Set<Long> expected = new HashSet<>();
        for (int z = 4; z <= 8; z++) {
            for (int x = 4; x <= 8; x++) {
                expected.add(LodKey.pack(0, x, 0, z));
                expected.add(LodKey.pack(0, x, -1, z));
            }
        }
        assertEquals(expected, hidden(land()));
    }

    @Test
    void aSectionBesideALowerColumnStays() throws IOException {
        // Column (6, 7) is sea down to a floor at y 9: the section of (6, 6) from 0 to 31
        // faces it from the side (an overhang seen from the sea), so it stays.
        Map<Long, LodSection> store = land();
        store.put(LodKey.pack(0, 6, 1, 7), layered(0, WATER, 31));
        store.put(LodKey.pack(0, 6, 0, 7), layered(10, WATER, 32));
        Set<Long> hidden = hidden(store);
        assertTrue(!hidden.contains(LodKey.pack(0, 6, 0, 6)), "beside the sea floor, above it");
        assertTrue(!hidden.contains(LodKey.pack(0, 6, 0, 7)), "the sea floor itself");
        assertTrue(hidden.contains(LodKey.pack(0, 6, -1, 6)), "under every floor around");
        assertTrue(hidden.contains(LodKey.pack(0, 4, 0, 4)), "far from the sea");
    }

    @Test
    void groundUnderACanopyStays() throws IOException {
        // Leaves from y 70 to 72 over the whole area: the LODs draw them with holes,
        // so the ground under them (section 1) is seen and stays; stone under it goes.
        Map<Long, LodSection> store = land();
        for (int z = 3; z <= 9; z++) {
            for (int x = 3; x <= 9; x++) {
                LodSection canopy = new LodSection(AIR);
                for (int y = 70 - 64; y <= 72 - 64; y++) {
                    for (int vz = 0; vz < LodSection.SIZE; vz++) {
                        for (int vx = 0; vx < LodSection.SIZE; vx++) {
                            canopy.set(vx, y, vz, LEAVES);
                        }
                    }
                }
                store.put(LodKey.pack(0, x, 2, z), canopy);
            }
        }
        Set<Long> hidden = hidden(store);
        assertTrue(!hidden.contains(LodKey.pack(0, 6, 1, 6)), "the ground under the canopy");
        assertTrue(hidden.contains(LodKey.pack(0, 6, 0, 6)), "the stone under the ground");
    }

    @Test
    void columnsOnTheRegionsEdgeStay() throws IOException {
        Map<Long, LodSection> store = new HashMap<>();
        for (int z = 0; z <= 2; z++) {
            for (int x = 0; x <= 2; x++) {
                store.put(LodKey.pack(0, x, 1, z), layered(8, AIR, 8));
                store.put(LodKey.pack(0, x, 0, z), layered(32, AIR, 32));
            }
        }
        assertEquals(Set.of(LodKey.pack(0, 1, 0, 1)), hidden(store), "only the column with all its neighbors inside");
    }

    @Test
    void coarseLevelsHideNothing() throws IOException {
        // A coarse section and its children find different floors: hiding one level and
        // not the other left the renderer without a way down (holes in new terrain).
        Map<Long, LodSection> store = new HashMap<>();
        for (int z = 3; z <= 9; z++) {
            for (int x = 3; x <= 9; x++) {
                store.put(LodKey.pack(1, x, 1, z), layered(8, AIR, 8));
                store.put(LodKey.pack(1, x, 0, z), layered(32, AIR, 32));
            }
        }
        assertEquals(Set.of(), hidden(store));
    }

    @Test
    void floorIsTheLowestFirstSolidVoxel() throws IOException {
        long key = LodKey.pack(2, 3, -2, 4);
        LodSection s = layered(20, AIR, 20);
        s.set(31, 19, 31, AIR);
        s.set(31, 18, 31, AIR);
        Map<Long, LodSection> store = Map.of(key, s);
        assertEquals(-2 * 32 + 17, SurfaceIndex.floor(List.of(key), store::get, CLASSES, m -> false));
    }

    @Test
    void anOpenVoxelColumnKeepsItsColumnAndTheOnesAround() throws IOException {
        Map<Long, LodSection> store = land();
        for (int section = -1; section <= 1; section++) {
            LodSection s = store.get(LodKey.pack(0, 6, section, 6));
            for (int y = 0; y < LodSection.SIZE; y++) {
                s.set(7, y, 7, AIR);
            }
        }
        Set<Long> hidden = hidden(store);
        for (int z = 5; z <= 7; z++) {
            for (int x = 5; x <= 7; x++) {
                assertTrue(!hidden.contains(LodKey.pack(0, x, -1, z)), "column " + x + ", " + z);
            }
        }
        assertTrue(hidden.contains(LodKey.pack(0, 4, -1, 4)));
    }
}
