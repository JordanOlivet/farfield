package io.lakio.farfield.core.preview;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Preview pregeneration tasks (ADR-123): a server builds the preview's
 * tiles within a radius of a point while nobody needs them, the area around
 * the world's spawn at start and any area an operator names. Saved in the
 * world, so a restart resumes them; a tile already built is never built
 * again, so progress needs no saving.
 *
 * <p>One line per task in the file:
 * {@code <id> <dimension> <center x> <center z> <radius> <state>}.
 */
public final class PregenTasks {

    /** The largest radius, in blocks: four times the preview's drawn range, for the distances to come. */
    public static final int MAX_RADIUS = 16_384;
    /** The task the server makes around the world's spawn. */
    public static final String SPAWN = "spawn";

    public enum State { RUNNING, PAUSED, DONE, CANCELLED }

    public record Task(String id, String dimension, int centerX, int centerZ, int radius, State state) {

        public Task {
            if (!id.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("task id: letters, digits, '-' and '_': " + id);
            }
            if (dimension.isBlank() || dimension.contains(" ")) {
                throw new IllegalArgumentException("dimension: " + dimension);
            }
            radius = Math.max(0, Math.min(MAX_RADIUS, radius));
        }

        public Task with(State next) {
            return new Task(id, dimension, centerX, centerZ, radius, next);
        }

        /** Still to build: running or paused. */
        public boolean open() {
            return state == State.RUNNING || state == State.PAUSED;
        }
    }

    private final List<Task> tasks = new ArrayList<>();

    public List<Task> tasks() {
        return List.copyOf(tasks);
    }

    public Task get(String id) {
        for (Task t : tasks) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** Adds the task, or replaces the one with its id. */
    public void put(Task task) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(task.id())) {
                tasks.set(i, task);
                return;
            }
        }
        tasks.add(task);
    }

    /** The smallest free numeric id: 1, 2, ... */
    public String nextId() {
        for (int n = 1; ; n++) {
            if (get(Integer.toString(n)) == null) {
                return Integer.toString(n);
            }
        }
    }

    /**
     * The tiles within {@code radius} blocks of (x, z), nearest first: a tile
     * counts when its nearest point is within the radius. Each as
     * {@code {tileX, tileZ}}.
     */
    public static int[][] tiles(int centerX, int centerZ, int radius) {
        int size = PreviewTile.BLOCKS;
        int x0 = Math.floorDiv(centerX - radius, size);
        int x1 = Math.floorDiv(centerX + radius, size);
        int z0 = Math.floorDiv(centerZ - radius, size);
        int z1 = Math.floorDiv(centerZ + radius, size);
        List<long[]> found = new ArrayList<>();
        for (int tz = z0; tz <= z1; tz++) {
            for (int tx = x0; tx <= x1; tx++) {
                long dx = Math.max(0, Math.max((long) tx * size - centerX, centerX - ((long) tx + 1) * size));
                long dz = Math.max(0, Math.max((long) tz * size - centerZ, centerZ - ((long) tz + 1) * size));
                long d2 = dx * dx + dz * dz;
                if (d2 <= (long) radius * radius) {
                    // Ties by the distance to the tile's center: a ring of equal tiles in a stable order.
                    double cx = (tx + 0.5) * size - centerX;
                    double cz = (tz + 0.5) * size - centerZ;
                    found.add(new long[]{d2, (long) (cx * cx + cz * cz), tx, tz});
                }
            }
        }
        found.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : a[1] != b[1] ? Long.compare(a[1], b[1])
                : a[3] != b[3] ? Long.compare(a[3], b[3]) : Long.compare(a[2], b[2]));
        int[][] out = new int[found.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = new int[]{(int) found.get(i)[2], (int) found.get(i)[3]};
        }
        return out;
    }

    /** Reads the file; none yet is no task. A line that does not read is left out. */
    public static PregenTasks read(Path file) throws IOException {
        PregenTasks result = new PregenTasks();
        if (!Files.exists(file)) {
            return result;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] w = l.split("\\s+");
            try {
                if (w.length != 6) {
                    throw new IllegalArgumentException(Arrays.toString(w));
                }
                result.put(new Task(w[0], w[1], Integer.parseInt(w[2]), Integer.parseInt(w[3]),
                        Integer.parseInt(w[4]), State.valueOf(w[5].toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException e) {
                // A hand-edited line: the others still run.
            }
        }
        return result;
    }

    /** Writes the file through a temporary file. */
    public void write(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            out.write("# Farfield preview pregeneration: id dimension centerX centerZ radius state\n");
            for (Task t : tasks) {
                out.write(String.format(Locale.ROOT, "%s %s %d %d %d %s%n", t.id(), t.dimension(), t.centerX(),
                        t.centerZ(), t.radius(), t.state().name().toLowerCase(Locale.ROOT)));
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
