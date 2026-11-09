package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.config.ServerConfig;
import io.lakio.farfield.core.preview.PregenTasks;
import io.lakio.farfield.core.preview.PreviewStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Preview pregeneration (ADR-123): the tiles within a radius of a point,
 * nearest first, built while the server has nothing better to do: the area
 * around the world's spawn from a dedicated server's start
 * ({@code preview_spawn_radius}), any area an operator names
 * ({@code /farfield pregen}). Tasks are saved in {@code farfield-pregen.txt}
 * of the world and resume after a restart; the tiles already built are
 * skipped, so progress needs no saving. While players are online the tasks
 * pause ({@code pregen_when_players=pause}, the default) or go on behind
 * the players' own tiles. Tiles are built at the finest grid, whatever their
 * distance: a player coming near does not build them again. Server thread.
 */
final class PreviewPregen {

    private static final String FILE = "farfield-pregen.txt";
    /** How far past its cursor a task looks for tiles to build in one update. */
    private static final int LOOK_AHEAD = 64;

    private final Path file;
    private final boolean pausesForPlayers;
    private final PregenTasks tasks;
    private final Map<String, int[][]> order = new HashMap<>();
    private final Map<String, Integer> cursor = new HashMap<>();
    /** Per task, the tiles built when it was last reported and when. */
    private final Map<String, long[]> lastReport = new HashMap<>();
    private final Map<String, Long> started = new HashMap<>();
    private boolean held;

    private PreviewPregen(Path file, boolean pausesForPlayers, PregenTasks tasks) {
        this.file = file;
        this.pausesForPlayers = pausesForPlayers;
        this.tasks = tasks;
    }

    /** Reads the saved tasks; on a dedicated server, adds or widens the spawn's task. */
    static PreviewPregen open(MinecraftServer server, ServerConfig config) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        PregenTasks tasks;
        try {
            tasks = PregenTasks.read(file);
        } catch (IOException e) {
            Farfield.LOG.warn("Noise preview pregen: cannot read {}, no task resumed", file, e);
            tasks = new PregenTasks();
        }
        PreviewPregen p = new PreviewPregen(file, config.pregenPausesForPlayers(), tasks);
        int radius = config.previewSpawnRadius();
        if (server.isDedicatedServer() && radius > 0) {
            BlockPos spawn = server.overworld().getSharedSpawnPos();
            PregenTasks.Task old = tasks.get(PregenTasks.SPAWN);
            boolean same = old != null && old.centerX() == spawn.getX() && old.centerZ() == spawn.getZ()
                    && old.radius() >= radius;
            // An operator's cancel holds while the setting stays; a wider radius or a moved spawn runs again.
            if (!same) {
                p.put(new PregenTasks.Task(PregenTasks.SPAWN, "minecraft:overworld", spawn.getX(), spawn.getZ(),
                        radius, PregenTasks.State.RUNNING));
            }
        }
        for (PregenTasks.Task t : tasks.tasks()) {
            if (t.open()) {
                Farfield.LOG.info("Noise preview pregen {}: {} tiles within {} blocks of {}, {} in {}, {}", t.id(),
                        p.order(t).length, t.radius(), t.centerX(), t.centerZ(), t.dimension(),
                        t.state().name().toLowerCase(Locale.ROOT));
            }
        }
        return p;
    }

    private void put(PregenTasks.Task t) {
        PregenTasks.Task old = tasks.get(t.id());
        if (old == null || old.centerX() != t.centerX() || old.centerZ() != t.centerZ() || old.radius() != t.radius()) {
            order.remove(t.id());
            cursor.remove(t.id());
        }
        tasks.put(t);
        save();
    }

    private void save() {
        try {
            tasks.write(file);
        } catch (IOException e) {
            Farfield.LOG.warn("Noise preview pregen: cannot save {}", file, e);
        }
    }

    private int[][] order(PregenTasks.Task t) {
        return order.computeIfAbsent(t.id(), k -> PregenTasks.tiles(t.centerX(), t.centerZ(), t.radius()));
    }

    /** Whether tasks may run now: some running, and no player holds them. */
    boolean active(MinecraftServer server) {
        boolean players = pausesForPlayers && server.getPlayerCount() > 0;
        if (players != held) {
            held = players;
            if (tasks.tasks().stream().anyMatch(t -> t.state() == PregenTasks.State.RUNNING)) {
                Farfield.LOG.info(held ? "Noise preview pregen paused: players online (pregen_when_players=pause)"
                        : "Noise preview pregen resumed: no player online");
            }
        }
        return !held && tasks.tasks().stream().anyMatch(t -> t.state() == PregenTasks.State.RUNNING);
    }

    boolean runningIn(String dimension) {
        return tasks.tasks().stream().anyMatch(t -> t.state() == PregenTasks.State.RUNNING
                && t.dimension().equals(dimension));
    }

    /**
     * Up to {@code max} tiles of the first running task of a dimension, not
     * built at the finest grid and not in flight, nearest its center first.
     * Marks the task done once every tile is built.
     */
    List<Long> next(String dimension, LongPredicate built, Set<Long> inFlight, int max) {
        List<Long> out = new ArrayList<>();
        for (PregenTasks.Task t : tasks.tasks()) {
            if (t.state() != PregenTasks.State.RUNNING || !t.dimension().equals(dimension)) {
                continue;
            }
            int[][] tiles = order(t);
            int at = cursor.getOrDefault(t.id(), 0);
            while (at < tiles.length && built.test(PreviewStore.key(tiles[at][0], tiles[at][1]))) {
                at++;
            }
            cursor.put(t.id(), at);
            if (at == tiles.length) {
                finish(t);
                continue;
            }
            started.putIfAbsent(t.id(), System.nanoTime());
            for (int i = at; i < Math.min(tiles.length, at + LOOK_AHEAD) && out.size() < max; i++) {
                long key = PreviewStore.key(tiles[i][0], tiles[i][1]);
                if (!inFlight.contains(key) && !built.test(key)) {
                    out.add(key);
                }
            }
            return out; // one task at a time per dimension: the nearest area is ready first
        }
        return out;
    }

    private void finish(PregenTasks.Task t) {
        Long since = started.remove(t.id());
        put(t.with(PregenTasks.State.DONE));
        Farfield.LOG.info("Noise preview pregen {}: done, {} tiles within {} blocks of {}, {}{}", t.id(),
                order(t).length, t.radius(), t.centerX(), t.centerZ(),
                since == null ? " (all built already)" : String.format(Locale.ROOT, " in %.1f min",
                        (System.nanoTime() - since) / 6e10));
    }

    /** One line per open task, once a minute while it runs: tiles built, pace and time left. */
    void report(LongPredicate builtIn, String dimension) {
        for (PregenTasks.Task t : tasks.tasks()) {
            if (t.state() != PregenTasks.State.RUNNING || !t.dimension().equals(dimension) || held) {
                continue;
            }
            int[][] tiles = order(t);
            int done = 0;
            for (int[] tile : tiles) {
                done += builtIn.test(PreviewStore.key(tile[0], tile[1])) ? 1 : 0;
            }
            long now = System.nanoTime();
            long[] last = lastReport.put(t.id(), new long[]{done, now});
            String pace = "";
            if (last != null && done > last[0]) {
                double perTile = (now - last[1]) / 1e9 / (done - last[0]);
                pace = String.format(Locale.ROOT, ", %.1f s a tile, about %.0f min left", perTile,
                        perTile * (tiles.length - done) / 60);
            }
            Farfield.LOG.info("Noise preview pregen {}: {} of {} tiles built{}", t.id(), done, tiles.length, pace);
        }
    }

    // ---------------------------------------------------------------- commands

    String start(String id, String dimension, int x, int z, int radius) {
        String key = id == null ? tasks.nextId() : id;
        PregenTasks.Task t = new PregenTasks.Task(key, dimension, x, z, radius, PregenTasks.State.RUNNING);
        put(t);
        lastReport.remove(key);
        Farfield.LOG.info("Noise preview pregen {} started: {} tiles within {} blocks of {}, {} in {}", key,
                order(t).length, t.radius(), x, z, dimension);
        return "Preview pregeneration " + key + ": " + order(t).length + " tiles within " + t.radius() + " blocks of "
                + x + ", " + z + " in " + dimension + (held ? " (paused while players are online)" : "") + ".";
    }

    /** Pauses, resumes or cancels one task, or every open one for null. */
    String set(String id, PregenTasks.State state) {
        List<PregenTasks.Task> picked = new ArrayList<>();
        for (PregenTasks.Task t : tasks.tasks()) {
            if ((id == null || t.id().equals(id)) && t.open()) {
                picked.add(t);
            }
        }
        if (picked.isEmpty()) {
            return null;
        }
        for (PregenTasks.Task t : picked) {
            put(t.with(state));
            started.remove(t.id());
            Farfield.LOG.info("Noise preview pregen {}: {}", t.id(), state.name().toLowerCase(Locale.ROOT));
        }
        return picked.size() == 1 ? "Preview pregeneration " + picked.get(0).id() + ": "
                + state.name().toLowerCase(Locale.ROOT) + "."
                : picked.size() + " preview pregenerations: " + state.name().toLowerCase(Locale.ROOT) + ".";
    }

    /** Every task with its progress, for {@code /farfield pregen list}. */
    List<String> list(java.util.function.Function<String, LongPredicate> builtIn) {
        List<String> lines = new ArrayList<>();
        for (PregenTasks.Task t : tasks.tasks()) {
            int[][] tiles = order(t);
            int done = 0;
            LongPredicate built = t.open() ? builtIn.apply(t.dimension()) : null;
            if (built != null) {
                for (int[] tile : tiles) {
                    done += built.test(PreviewStore.key(tile[0], tile[1])) ? 1 : 0;
                }
            }
            lines.add(String.format(Locale.ROOT, "%s: %s, %d blocks around %d, %d in %s, %s", t.id(),
                    t.state().name().toLowerCase(Locale.ROOT), t.radius(), t.centerX(), t.centerZ(), t.dimension(),
                    t.open() ? done + " of " + tiles.length + " tiles" : tiles.length + " tiles"));
        }
        if (held) {
            lines.add("Paused while players are online (pregen_when_players=pause).");
        }
        return lines;
    }

    PregenTasks.Task task(String id) {
        return tasks.get(id);
    }
}
