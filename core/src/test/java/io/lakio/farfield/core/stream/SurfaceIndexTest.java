package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceIndexTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 2;
    private static final MaterialClassifier CLASSES = m -> m == STONE ? MaterialClassifier.OPAQUE
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

    @Test
    void cavesUnderTheFloorHideAndSeaFloorsAndCliffsStay() throws IOException {
        Map<Long, LodSection> store = new HashMap<>();
        // Column (0, 0): ground at y 40 in section 1, with a cliff down to 36 in one corner; caves below.
        long land1 = LodKey.pack(0, 0, 1, 0);
        LodSection top = layered(8, AIR, 8);
        for (int y = 4; y < 8; y++) {
            top.set(0, y, 0, AIR); // the cliff's foot: this voxel column's first solid voxel is at 32 + 3
        }
        store.put(land1, top);
        long land0 = LodKey.pack(0, 0, 0, 0);
        LodSection cave = layered(32, AIR, 32);
        cave.set(5, 5, 5, AIR);
        store.put(land0, cave);
        long landDeep = LodKey.pack(0, 0, -1, 0);
        store.put(landDeep, layered(32, AIR, 32));
        // Column (1, 0): sea from y 10 to 63 over a floor at y 9 of section 0; rock below.
        long sea1 = LodKey.pack(0, 1, 1, 0);
        store.put(sea1, layered(0, WATER, 31));
        long sea0 = LodKey.pack(0, 1, 0, 0);
        store.put(sea0, layered(10, WATER, 32));
        long seaDeep = LodKey.pack(0, 1, -1, 0);
        store.put(seaDeep, layered(32, AIR, 32));
        // Column (2, 0): one voxel column open down to the void: nothing hidden.
        long open1 = LodKey.pack(0, 2, 0, 0);
        LodSection holed = layered(32, AIR, 32);
        for (int y = 0; y < 32; y++) {
            holed.set(7, y, 7, AIR);
        }
        store.put(open1, holed);
        long open0 = LodKey.pack(0, 2, -1, 0);
        LodSection holedBelow = layered(32, AIR, 32);
        for (int y = 0; y < 32; y++) {
            holedBelow.set(7, y, 7, AIR);
        }
        store.put(open0, holedBelow);

        long[] keys = store.keySet().stream().mapToLong(Long::longValue).toArray();
        List<Long> hidden = SurfaceIndex.hidden(keys, store::get, CLASSES);
        assertEquals(Set.of(land0, landDeep, seaDeep), Set.copyOf(hidden));
    }

    @Test
    void floorIsTheLowestFirstSolidVoxel() throws IOException {
        long key = LodKey.pack(2, 3, -2, 4);
        LodSection s = layered(20, AIR, 20);
        s.set(31, 19, 31, AIR);
        s.set(31, 18, 31, AIR);
        Map<Long, LodSection> store = Map.of(key, s);
        assertEquals(-2 * 32 + 17, SurfaceIndex.floor(List.of(key), store::get, CLASSES));
    }
}
