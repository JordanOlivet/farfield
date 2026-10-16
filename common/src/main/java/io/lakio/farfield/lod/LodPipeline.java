package io.lakio.farfield.lod;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.world.LodWorld;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Turns the loaded chunks of a set of levels into LOD data (ADR-022), on
 * either side: the logical server (server mode) or the client (client mode,
 * servers without Farfield).
 *
 * <ul>
 *   <li>The owner thread (server thread or client thread) reports chunk
 *       loads, unloads and block changes, and calls {@link #tick()}: a chunk
 *       is snapshotted 2 s after it loads (once its light is correct, or
 *       10 s later without it) and 5 s after its last block change, at most
 *       16 chunks and 2 ms per tick and 256 in flight. A due chunk that
 *       unloads first is snapshotted at unload, whatever its light: a chunk
 *       generated and left quickly must not leave a hole. The queue keeps
 *       the chunk object: a loaded chunk the player left becomes a border
 *       chunk that {@code getChunkNow} does not return, yet it is complete
 *       and in memory until it unloads, and coming back to it fires no new
 *       load event.</li>
 *   <li>Low-priority workers convert snapshots and ingest them into the
 *       level's {@link LodWorld}.</li>
 *   <li>A maintenance thread writes sections idle for 3 s and rebuilds the
 *       mips every second, and syncs every 30 s.</li>
 * </ul>
 *
 * <p>The client closes a level when the player changes dimension or leaves
 * the server ({@link #closeLevel}, {@link #stopAsync}): its waiting chunks
 * are snapshotted on the owner thread, the store is written and closed on
 * the maintenance thread, so the game does not wait for the disk.
 *
 * <p>Active pipelines are registered so that the block-change mixin can
 * reach the one that owns a level ({@link #blockChanged}).
 */
public final class LodPipeline {

    private static final long LOAD_DELAY_TICKS = 40;
    private static final long CHANGE_DELAY_TICKS = 100;
    /** Past its due tick, a chunk whose light is still not correct is taken anyway. */
    private static final long LIGHT_WAIT_TICKS = 200;
    private static final int SNAPSHOTS_PER_TICK = 16;
    private static final long SNAPSHOT_BUDGET_NANOS = 2_000_000;
    private static final int MAX_IN_FLIGHT = 256;

    private static final List<LodPipeline> ACTIVE = new CopyOnWriteArrayList<>();

    private final String name;
    private final Predicate<Level> accepts;
    private final Function<Level, Path> storage;
    private final Predicate<LevelChunk> lightReady;
    /**
     * Stores being closed in the background, by folder, across pipelines (a
     * reconnection starts a new one): opening one again waits for its close.
     */
    private static final Map<Path, CompletableFuture<Void>> CLOSING = new ConcurrentHashMap<>();
    private final Map<Level, LodLevel> levels = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService maintenance;
    private final ThreadLocal<ChunkSnapshot.Scratch> scratch = ThreadLocal.withInitial(ChunkSnapshot.Scratch::new);
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean stopping;
    private long lastSync = System.nanoTime();
    private long lastReport = System.nanoTime();

    /**
     * @param name    for thread names and logs ("server", "client")
     * @param accepts the levels this pipeline handles
     * @param storage    the folder of a level's LOD store, or null to leave the level alone
     * @param lightReady whether a chunk's light is complete (server: its light stage ran;
     *                   client: the light the server sent is applied)
     */
    public LodPipeline(String name, Predicate<Level> accepts, Function<Level, Path> storage,
                       Predicate<LevelChunk> lightReady) {
        this.name = name;
        this.accepts = accepts;
        this.storage = storage;
        this.lightReady = lightReady;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        this.workers = Executors.newFixedThreadPool(threads, daemon("farfield-" + name + "-worker"));
        this.maintenance = Executors.newSingleThreadScheduledExecutor(daemon("farfield-" + name + "-maintenance"));
        maintenance.scheduleWithFixedDelay(this::maintain, 1, 1, TimeUnit.SECONDS);
        ACTIVE.add(this);
        Farfield.LOG.info("LOD pipeline ({}) started with {} worker threads", name, threads);
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
    }

    /** A block changed in a loaded chunk (mixin into {@code LevelChunk.setBlockState}); any side. */
    public static void blockChanged(Level level, LevelChunk chunk) {
        for (LodPipeline p : ACTIVE) {
            if (p.accepts.test(level)) {
                p.onBlockChanged(level, chunk);
            }
        }
    }

    // ---------------------------------------------------------------- owner thread

    public void onChunkLoad(Level level, LevelChunk chunk) {
        if (stopping) {
            return;
        }
        LodLevel lod = level(level);
        if (lod != null) {
            long pos = chunk.getPos().toLong();
            lod.loaded.add(pos);
            lod.due.put(pos, new LodLevel.Due(level.getGameTime() + LOAD_DELAY_TICKS, chunk));
        }
    }

    public void onChunkUnload(Level level, LevelChunk chunk) {
        LodLevel lod = levels.get(level);
        if (stopping || lod == null) {
            return;
        }
        long pos = chunk.getPos().toLong();
        lod.loaded.remove(pos);
        // Loaded or changed and not snapshotted yet: take it now, while it is
        // still in memory, even if its light is not complete (the LODs do not
        // draw light yet; a hole is worse).
        if (lod.due.remove(pos) != null) {
            lod.snapshotsAtUnload.incrementAndGet();
            if (!lightReady.test(chunk)) {
                lod.snapshotsWithoutLight.incrementAndGet();
            }
            submit(lod, ChunkSnapshot.take(level, chunk));
        }
    }

    private void onBlockChanged(Level level, LevelChunk chunk) {
        LodLevel lod = levels.get(level);
        if (!stopping && lod != null) {
            lod.due.put(chunk.getPos().toLong(), new LodLevel.Due(level.getGameTime() + CHANGE_DELAY_TICKS, chunk));
            lod.blockChanges.incrementAndGet();
        }
    }

    /** Snapshots the chunks that are due, under the per-tick budget. */
    public void tick() {
        if (stopping) {
            return;
        }
        long start = System.nanoTime();
        int taken = 0;
        for (LodLevel lod : levels.values()) {
            long now = lod.level.getGameTime();
            Iterator<Map.Entry<Long, LodLevel.Due>> it = lod.due.entrySet().iterator();
            while (it.hasNext() && taken < SNAPSHOTS_PER_TICK && inFlight.get() < MAX_IN_FLIGHT
                    && System.nanoTime() - start < SNAPSHOT_BUDGET_NANOS) {
                Map.Entry<Long, LodLevel.Due> e = it.next();
                LodLevel.Due d = e.getValue();
                if (d.tick() > now) {
                    continue;
                }
                LevelChunk chunk = d.chunk();
                if (!lightReady.test(chunk)) {
                    if (now - d.tick() < LIGHT_WAIT_TICKS) {
                        continue;
                    }
                    lod.snapshotsWithoutLight.incrementAndGet();
                }
                long pos = e.getKey();
                if (lod.level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos)) != chunk) {
                    lod.snapshotsOfBorderChunks.incrementAndGet();
                }
                it.remove();
                submit(lod, ChunkSnapshot.take(lod.level, chunk));
                taken++;
            }
        }
    }

    // ---------------------------------------------------------------- levels and work

    /** The LOD state of a level, opened on first use; null if the level is not handled or cannot open. */
    public LodLevel level(Level level) {
        LodLevel lod = levels.get(level);
        if (lod != null || stopping || !accepts.test(level)) {
            return lod;
        }
        synchronized (levels) {
            lod = levels.get(level);
            if (lod == null) {
                Path dir = storage.apply(level);
                if (dir == null) {
                    return null;
                }
                CompletableFuture<Void> previous = CLOSING.get(dir);
                if (previous != null) {
                    previous.join(); // back in a dimension just left: its store is still closing
                }
                try {
                    lod = LodLevel.open(level, dir);
                } catch (IOException e) {
                    Farfield.LOG.error("Cannot open the LOD store of {} at {}", level.dimension().location(), dir, e);
                    return null;
                }
                levels.put(level, lod);
                Farfield.LOG.info("LOD store of {} ({}) opened at {}", lod.name(), name, dir);
                int upgrade = lod.world().mipsToUpgrade();
                if (upgrade > 0) {
                    Farfield.LOG.info("LOD store of {}: rebuilding its coarse levels with the current mip rules"
                            + " (once, {} level-1 sections queued)", lod.name(), upgrade);
                }
            }
            return lod;
        }
    }

    /** The LOD world of an open level of this dimension, or null. */
    public LodWorld world(ResourceKey<Level> dimension) {
        if (stopping) {
            return null;
        }
        for (LodLevel lod : levels.values()) {
            if (lod.level.dimension() == dimension) {
                return lod.world();
            }
        }
        return null;
    }

    public ExecutorService workers() {
        return workers;
    }

    private void submit(LodLevel lod, ChunkSnapshot snapshot) {
        inFlight.incrementAndGet();
        lod.pending.incrementAndGet();
        lod.snapshots.incrementAndGet();
        workers.execute(() -> {
            try {
                lod.world().ingest(snapshot.fill(lod, scratch.get()));
            } catch (Throwable t) {
                Farfield.LOG.error("LOD ingest of chunk {}, {} in {} failed", snapshot.chunkX, snapshot.chunkZ,
                        lod.name(), t);
            } finally {
                lod.pending.decrementAndGet();
                inFlight.decrementAndGet();
            }
        });
    }

    /**
     * Owner thread: stops handling {@code level}. Its waiting chunks are
     * snapshotted now, while in memory; once they are ingested, the
     * maintenance thread writes and closes its store.
     */
    public void closeLevel(Level level) {
        LodLevel lod = levels.remove(level);
        if (lod == null) {
            return;
        }
        for (LodLevel.Due d : lod.due.values()) {
            lod.snapshotsAtStop.incrementAndGet();
            submit(lod, ChunkSnapshot.take(lod.level, d.chunk()));
        }
        lod.due.clear();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        CLOSING.put(lod.dir(), closed);
        maintenance.execute(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (lod.pending.get() > 0 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            close(lod);
            CLOSING.remove(lod.dir(), closed);
            closed.complete(null);
        });
    }

    /**
     * Owner thread: closes every level in the background ({@link #closeLevel}),
     * then ends the threads; {@link #awaitStopped} waits for it.
     */
    public void stopAsync() {
        for (Level level : List.copyOf(levels.keySet())) {
            closeLevel(level);
        }
        stopping = true;
        ACTIVE.remove(this);
        maintenance.execute(() -> {
            workers.shutdown();
            maintenance.shutdown();
        });
    }

    /** Waits until a {@link #stopAsync} has written and closed every store, at most {@code seconds}. */
    public void awaitStopped(long seconds) {
        try {
            maintenance.awaitTermination(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void maintain() {
        if (stopping) {
            return;
        }
        boolean sync = System.nanoTime() - lastSync > TimeUnit.SECONDS.toNanos(30);
        boolean report = System.nanoTime() - lastReport > TimeUnit.SECONDS.toNanos(60);
        for (LodLevel lod : levels.values()) {
            if (report) {
                report(lod);
            }
            try {
                lod.world().flush(TimeUnit.SECONDS.toNanos(3));
                if (lod.world().hasDirtyMips()) {
                    boolean upgrading = lod.world().mipsToUpgrade() > 0;
                    int written = lod.world().buildMips(workers);
                    if (upgrading && lod.world().mipsToUpgrade() == 0) {
                        Farfield.LOG.info("LOD store of {}: coarse levels rebuilt with the current mip rules"
                                + " ({} sections written in the last pass)", lod.name(), written);
                    }
                }
                if (sync) {
                    lod.world().sync();
                }
            } catch (Throwable t) {
                Farfield.LOG.error("LOD maintenance of {} failed", lod.name(), t);
            }
        }
        if (sync) {
            lastSync = System.nanoTime();
        }
        if (report) {
            lastReport = System.nanoTime();
        }
    }

    /** One line per minute when chunks were snapshotted. */
    private void report(LodLevel lod) {
        long snapshots = lod.snapshots.get();
        if (snapshots == lod.loggedSnapshots) {
            return;
        }
        Farfield.LOG.info("LOD pipeline ({}) {}: {} snapshots in the last minute; in total {} at unload, {} of border"
                        + " chunks, {} without complete light; {} waiting, {} chunks loaded", name, lod.name(),
                snapshots - lod.loggedSnapshots, lod.snapshotsAtUnload.get(), lod.snapshotsOfBorderChunks.get(),
                lod.snapshotsWithoutLight.get(), lod.due.size(), lod.loaded.size());
        lod.loggedSnapshots = snapshots;
    }

    /**
     * Stops taking chunks, lets the workers finish, then writes and closes
     * every store. {@code beforeClose} runs once the workers stopped (to end
     * work that feeds the stores from elsewhere, such as an import).
     */
    public void stop(Runnable beforeClose) {
        // Called on the owner thread: take every chunk still waiting (loaded or
        // changed recently) while it is in memory, or it would be missing from
        // the LODs until it loads again.
        for (LodLevel lod : levels.values()) {
            for (LodLevel.Due d : lod.due.values()) {
                lod.snapshotsAtStop.incrementAndGet();
                submit(lod, ChunkSnapshot.take(lod.level, d.chunk()));
            }
            lod.due.clear();
        }
        stopping = true;
        ACTIVE.remove(this);
        maintenance.shutdown();
        workers.shutdown();
        try {
            maintenance.awaitTermination(30, TimeUnit.SECONDS);
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        beforeClose.run();
        for (LodLevel lod : levels.values()) {
            close(lod);
        }
        levels.clear();
    }

    private void close(LodLevel lod) {
        try {
            lod.world().close();
            Farfield.LOG.info("LOD store of {} ({}) closed: {} chunks ingested ({} live snapshots, {} at unload,"
                            + " {} at stop, {} of border chunks, {} without complete light, {} block changes), {}"
                            + " sections written, {} unchanged; {} chunks loaded, {} not snapshotted", lod.name(),
                    name, lod.world().chunksIngested(), lod.snapshots.get(), lod.snapshotsAtUnload.get(),
                    lod.snapshotsAtStop.get(),
                    lod.snapshotsOfBorderChunks.get(), lod.snapshotsWithoutLight.get(), lod.blockChanges.get(),
                    lod.world().sectionsWritten(), lod.world().sectionsUnchanged(), lod.loaded.size(),
                    lod.due.size());
        } catch (IOException e) {
            Farfield.LOG.error("Closing the LOD store of {} failed", lod.name(), e);
        }
    }
}
