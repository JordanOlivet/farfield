package io.lakio.farfield.core.world;

import io.lakio.farfield.core.lod.LodSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** LOCAL PROBE, never committed. Reads a copied LOD store (FF_MIRROR) and prints PROBE lines. */
class MirrorLightProbe {

    @Test
    void probe() throws Exception {
        String dir = System.getenv("FF_MIRROR");
        if (dir == null) {
            return;
        }
        int px = Integer.parseInt(System.getenv().getOrDefault("FF_X", "-435"));
        int pz = Integer.parseInt(System.getenv().getOrDefault("FF_Z", "544"));
        try (LodWorld w = LodWorld.open(Path.of(dir), MaterialTable.SIMPLE)) {
            int water = w.blockStates().id("minecraft:water[level=0]");
            int air = w.air();
            for (int level = 0; level <= 3; level++) {
                List<Long> keys = new ArrayList<>();
                w.forEachVersion(level, (px >> (5 + level)) >> 5, (pz >> (5 + level)) >> 5, (k, v) -> keys.add(k));
                Map<Integer, Integer> above = new TreeMap<>();
                int tops = 0;
                int borderTops = 0;
                for (long key : keys) {
                    LodSection s = w.read(key);
                    if (s == null) {
                        continue;
                    }
                    for (int z = 0; z < 32; z++) {
                        for (int x = 0; x < 32; x++) {
                            for (int y = 31; y >= 0; y--) {
                                int m = s.material(x, y, z);
                                if (m == air) {
                                    continue;
                                }
                                if (m == water) {
                                    tops++;
                                    if (y == 31) {
                                        borderTops++;
                                    } else {
                                        above.merge(s.skyLight().get(x, y + 1, z), 1, Integer::sum);
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                System.out.println("PROBE level " + level + ": " + keys.size() + " sections, " + tops
                        + " water tops (" + borderTops + " on the section's top layer); sky light above " + above);
            }
        }
    }
}
