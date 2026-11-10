package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.config.ServerConfig;
import io.lakio.farfield.core.preview.PregenTasks;
import io.lakio.farfield.core.preview.PreviewPyramid;
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
 * the players' own tiles; an operator's {@code resume} or {@code start} while
 * players hold them forces that task on until it ends or is paused, for this
 * session only (a restart goes back to the setting), with half of the
 * preview's queue kept for it (the players' tiles took every slot, and a
 * forced task built nothing). A task builds its pyramid first (ADR-126):
 * the coarsest tiles over its whole radius, then each finer level, the
 * level-0 tiles with their features last, nearest first in each level;
 * whatever their distance, so a player coming near builds none again.
 * Server thread.
 */
final class PreviewPregen {

    private static final String FILE = "farfield-pregen.txt";
    /** How far past its cursor a task looks for tiles to build in one update. */
    private static final int LOOK_AHEAD = 64;
    /**
     * While an LOD import runs, tasks build only this near their center (the
     * user's order, ADR-124): what a player joining early sees, then the
     * import finishes, then the rest.
     */
    static final int NEAR_WHILE_IMPORTING = 1024;

    private final Path file;
    private final boolean pausesForPlayers;
    private final PregenTasks tasks;
    private final Map<String, long[]> order = new HashMap<>();
    private final Map<String, Integer> cursor = new HashMap<>();
    /** Per task, the tiles built when it was last reported and when. */
    private final Map<String, long[]> lastReport = new HashMap<>();
    private final Map<String, Long> started = new HashMap<>();
    /** Tasks an operator started or resumed while players held them: they run anyway, this session. */
    private final Set<String> forced = new java.util.HashSet<>();
    private boolean held;
    private boolean importing;

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

    /** A task's tiles by key, in the order it builds them ({@link PregenTasks#pyramid}). */
    private long[] order(PregenTasks.Task t) {
        return order.computeIfAbsent(t.id(), k -> PregenTasks.pyramid(t.centerX(), t.centerZ(), t.radius(),
                PreviewPyramid.LEVELS));
    }

    /** An LOD import runs or waits: tasks keep to their near area until it ends. */
    void setImporting(boolean now) {
        if (now != importing) {
            importing = now;
            if (tasks.tasks().stream().anyMatch(PregenTasks.Task::open)) {
                Farfield.LOG.info(now ? "Noise preview pregen: within " + NEAR_WHILE_IMPORTING
                        + " blocks of each task's center while the LOD import runs"
                        : "Noise preview pregen: the LOD import ended, building out to each task's radius");
            }
        }
    }

    /** Blocks between a task's center and the nearest point of a tile, by its key (any level). */
    private static double distance(PregenTasks.Task t, long key) {
        int size = PreviewStore.blocks(PreviewStore.level(key));
        int tx = PreviewStore.tileX(key);
        int tz = PreviewStore.tileZ(key);
        double dx = Math.max(0, Math.max((double) tx * size - t.centerX(), t.centerX() - ((double) tx + 1) * size));
        double dz = Math.max(0, Math.max((double) tz * size - t.centerZ(), t.centerZ() - ((double) tz + 1) * size));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Whether a running task waits for the LOD import: its near area is built. */
    private boolean waitsForImport(PregenTasks.Task t) {
        if (!importing) {
            return false;
        }
        long[] tiles = order(t);
        int at = cursor.getOrDefault(t.id(), 0);
        return at < tiles.length && distance(t, tiles[at]) > NEAR_WHILE_IMPORTING;
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
        return tasks.tasks().stream().anyMatch(this::runs);
    }

    /** Running, and not held by players (or forced by an operator). */
    private boolean runs(PregenTasks.Task t) {
        return t.state() == PregenTasks.State.RUNNING && (!held || forced.contains(t.id()));
    }

    /** Whether an operator forced a task of this dimension while players hold the others. */
    boolean forcedIn(String dimension) {
        return held && tasks.tasks().stream().anyMatch(t -> runs(t) && forced.contains(t.id())
                && t.dimension().equals(dimension));
    }

    boolean runningIn(String dimension) {
        return tasks.tasks().stream().anyMatch(t -> runs(t) && t.dimension().equals(dimension));
    }

    /**
     * Up to {@code max} tiles of the first running task of a dimension, not
     * built at the finest grid and not in flight, nearest its center first.
     * Marks the task done once every tile is built.
     */
    List<Long> next(String dimension, LongPredicate built, Set<Long> inFlight, int max) {
        List<Long> out = new ArrayList<>();
        for (PregenTasks.Task t : tasks.tasks()) {
            if (!runs(t) || !t.dimension().equals(dimension)) {
                continue;
            }
            long[] tiles = order(t);
            int at = cursor.getOrDefault(t.id(), 0);
            while (at < tiles.length && built.test(tiles[at])) {
                at++;
            }
            cursor.put(t.id(), at);
            if (at == tiles.length) {
                finish(t);
                continue;
            }
            started.putIfAbsent(t.id(), System.nanoTime());
            for (int i = at; i < Math.min(tiles.length, at + LOOK_AHEAD) && out.size() < max; i++) {
                if (importing && distance(t, tiles[i]) > NEAR_WHILE_IMPORTING) {
                    break; // tiles come nearest first in a level: the rest waits for the import's end
                }
                long key = tiles[i];
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
        forced.remove(t.id());
        put(t.with(PregenTasks.State.DONE));
        Farfield.LOG.info("Noise preview pregen {}: done, {} tiles within {} blocks of {}, {}{}", t.id(),
                order(t).length, t.radius(), t.centerX(), t.centerZ(),
                since == null ? " (all built already)" : String.format(Locale.ROOT, " in %.1f min",
                        (System.nanoTime() - since) / 6e10));
    }

    /** One line per open task, once a minute while it runs: tiles built, pace and time left. */
    void report(LongPredicate builtIn, String dimension) {
        for (PregenTasks.Task t : tasks.tasks()) {
            if (!runs(t) || !t.dimension().equals(dimension)) {
                continue;
            }
            long[] tiles = order(t);
            int done = 0;
            for (long tile : tiles) {
                done += builtIn.test(tile) ? 1 : 0;
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
        if (held) {
            forced.add(key); // an operator asked for it now: players online do not hold it
        }
        Farfield.LOG.info("Noise preview pregen {} started: {} tiles within {} blocks of {}, {} in {}{}", key,
                order(t).length, t.radius(), x, z, dimension, held ? ", forced while players are online" : "");
        return "Preview pregeneration " + key + ": " + order(t).length + " tiles within " + t.radius() + " blocks of "
                + x + ", " + z + " in " + dimension + (held ? ", running even with players online (started by"
                + " hand; pregen_when_players=pause applies again after a restart)" : "") + ".";
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
        boolean force = state == PregenTasks.State.RUNNING && held;
        for (PregenTasks.Task t : picked) {
            put(t.with(state));
            started.remove(t.id());
            if (force) {
                forced.add(t.id()); // resumed by hand while players hold the tasks: it runs anyway
            } else if (state != PregenTasks.State.RUNNING) {
                forced.remove(t.id());
            }
            Farfield.LOG.info("Noise preview pregen {}: {}{}", t.id(), state.name().toLowerCase(Locale.ROOT),
                    force ? ", forced while players are online" : "");
        }
        String what = force ? "running even with players online (resumed by hand; pregen_when_players=pause"
                + " applies again after a restart)" : state.name().toLowerCase(Locale.ROOT);
        return picked.size() == 1 ? "Preview pregeneration " + picked.get(0).id() + ": " + what + "."
                : picked.size() + " preview pregenerations: " + what + ".";
    }

    /** Every task with its progress, for {@code /farfield pregen list}. */
    List<String> list(java.util.function.Function<String, LongPredicate> builtIn) {
        List<String> lines = new ArrayList<>();
        for (PregenTasks.Task t : tasks.tasks()) {
            long[] tiles = order(t);
            int done = 0;
            LongPredicate built = t.open() ? builtIn.apply(t.dimension()) : null;
            if (built != null) {
                for (long tile : tiles) {
                    done += built.test(tile) ? 1 : 0;
                }
            }
            String state = t.state().name().toLowerCase(Locale.ROOT);
            if (t.state() == PregenTasks.State.RUNNING && held) {
                state = forced.contains(t.id()) ? "running, forced by hand" : "waiting: players online";
            } else if (t.state() == PregenTasks.State.RUNNING && waitsForImport(t)) {
                state = "near area built, waiting for the LOD import";
            }
            lines.add(String.format(Locale.ROOT, "%s: %s, %d blocks around %d, %d in %s, %s", t.id(), state,
                    t.radius(), t.centerX(), t.centerZ(), t.dimension(),
                    t.open() ? done + " of " + tiles.length + " tiles" : tiles.length + " tiles"));
        }
        if (held) {
            lines.add("Players online hold the tasks (pregen_when_players=pause); /farfield pregen resume <id> runs"
                    + " one anyway.");
        }
        return lines;
    }

    PregenTasks.Task task(String id) {
        return tasks.get(id);
    }
}
