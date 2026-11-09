package io.lakio.farfield.core.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PregenTasksTest {

    @TempDir
    Path dir;

    @Test
    void tilesComeNearestFirstAndStayWithinTheRadius() {
        int[][] tiles = PregenTasks.tiles(100, -200, 4096);
        // The center's own tile first.
        assertEquals(0, tiles[0][0]);
        assertEquals(-1, tiles[0][1]);
        Set<Long> seen = new HashSet<>();
        double last = -1;
        for (int[] t : tiles) {
            assertTrue(seen.add(PreviewStore.key(t[0], t[1])), "each tile once");
            double dx = Math.max(0, Math.max(t[0] * 512.0 - 100, 100 - (t[0] + 1) * 512.0));
            double dz = Math.max(0, Math.max(t[1] * 512.0 + 200, -200 - (t[1] + 1) * 512.0));
            double d = Math.sqrt(dx * dx + dz * dz);
            assertTrue(d <= 4096, "within the radius: " + d);
            assertTrue(d >= last, "nearest first");
            last = d;
        }
        // About pi * (4096 + a tile)^2 / 512^2.
        assertTrue(tiles.length > 200 && tiles.length < 300, "tiles: " + tiles.length);
        assertEquals(1, PregenTasks.tiles(256, 256, 0).length, "radius 0: the tile under the point");
        int big = PregenTasks.tiles(0, 0, PregenTasks.MAX_RADIUS).length;
        assertTrue(big > 3000 && big < 3600, "16,384 blocks: " + big);
    }

    @Test
    void tasksRoundTripAndABadLineIsLeftOut() throws IOException {
        Path file = dir.resolve("farfield").resolve("pregen.txt");
        PregenTasks tasks = new PregenTasks();
        assertEquals("1", tasks.nextId());
        tasks.put(new PregenTasks.Task(PregenTasks.SPAWN, "minecraft:overworld", -8, 24, 4096,
                PregenTasks.State.RUNNING));
        tasks.put(new PregenTasks.Task("1", "minecraft:the_nether", 1000, 0, 99_999, PregenTasks.State.PAUSED));
        assertEquals("2", tasks.nextId());
        assertEquals(PregenTasks.MAX_RADIUS, tasks.get("1").radius(), "radius clamped");
        tasks.write(file);
        Files.writeString(file, Files.readString(file) + "broken line\n");
        PregenTasks back = PregenTasks.read(file);
        assertEquals(tasks.tasks(), back.tasks());
        back.put(back.get("1").with(PregenTasks.State.CANCELLED));
        assertEquals(PregenTasks.State.CANCELLED, back.get("1").state());
        assertEquals(2, back.tasks().size(), "replaced, not added");
        assertNull(back.get("3"));
        assertEquals(0, PregenTasks.read(dir.resolve("none.txt")).tasks().size());
    }

    @Test
    void idsAndDimensionsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new PregenTasks.Task("a b", "minecraft:overworld", 0, 0, 1,
                PregenTasks.State.RUNNING));
        assertThrows(IllegalArgumentException.class, () -> new PregenTasks.Task("a", "", 0, 0, 1,
                PregenTasks.State.RUNNING));
        assertTrue(new PregenTasks.Task("a", "x:y", 0, 0, 1, PregenTasks.State.PAUSED).open());
        assertTrue(!new PregenTasks.Task("a", "x:y", 0, 0, 1, PregenTasks.State.DONE).open());
    }
}
