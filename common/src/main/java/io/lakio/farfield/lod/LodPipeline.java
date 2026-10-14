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
 *       is snapshotted 2 s after it loads (once its light is correct) and
 *       5 s after its last block change, at most 16 chunks and 2 ms per tick
 *       and 256 in flight.</li>
 *   <li>Low-priority workers convert snapshots and ingest them into the
 *       level's {@link LodWorld}.</li>
 *   <li>A maintenance thread writes sections idle for 3 s and rebuilds the
 *       mips every second, and syncs every 30 s.</li>
 * </ul>
 *
 * <p>Active pipelines are registered so that the block-change mixin can
 * reach the one that owns a level ({@link #blockChanged}).
 */
public final class LodPipeline {

    private static final long LOAD_DELAY_TICKS = 40;
    private static final long CHANGE_DELAY_TICKS = 100;
    private static final int SNAPSHOTS_PER_TICK = 16;
    private static final long SNAPSHOT_BUDGET_NANOS = 2_000_000;
    private static final int MAX_IN_FLIGHT = 256;

    private static final List<LodPipeline> ACTIVE = new CopyOnWriteArrayList<>();

    private final String name;
    private final Predicate<Level> accepts;
    private final Function<Level, Path> storage;
    private final Map<Level, LodLevel> levels = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService maintenance;
    private final ThreadLocal<ChunkSnapshot.Scratch> scratch = ThreadLocal.withInitial(ChunkSnapshot.Scratch::new);
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean stopping;
    private long lastSync = System.nanoTime();

    /**
     * @param name    for thread names and logs ("server", "client")
     * @param accepts the levels this pipeline handles
     * @param storage the folder of a level's LOD store, or null to leave the level alone
     */
    public LodPipeline(String name, Predicate<Level> accepts, Function<Level, Path> storage) {
        this.name = name;
        this.accepts = accepts;
        this.storage = storage;
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
    public static void blockChanged(Level level, ChunkPos pos) {
        for (LodPipeline p : ACTIVE) {
            if (p.accepts.test(level)) {
                p.onBlockChanged(level, pos);
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
            lod.due.put(pos, level.getGameTime() + LOAD_DELAY_TICKS);
        }
    }

    public void onChunkUnload(Level level, LevelChunk chunk) {
        LodLevel lod = levels.get(level);
        if (stopping || lod == null) {
            return;
        }
        long pos = chunk.getPos().toLong();
        lod.loaded.remove(pos);
        // Changed and not snapshotted yet: take it now, it is still complete.
        if (lod.due.remove(pos) != null && chunk.isLightCorrect()) {
            submit(lod, ChunkSnapshot.take(level, chunk));
        }
    }

    private void onBlockChanged(Level level, ChunkPos pos) {
        LodLevel lod = levels.get(level);
        if (!stopping && lod != null) {
            lod.due.put(pos.toLong(), level.getGameTime() + CHANGE_DELAY_TICKS);
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
            Iterator<Map.Entry<Long, Long>> it = lod.due.entrySet().iterator();
            while (it.hasNext() && taken < SNAPSHOTS_PER_TICK && inFlight.get() < MAX_IN_FLIGHT
                    && System.nanoTime() - start < SNAPSHOT_BUDGET_NANOS) {
                Map.Entry<Long, Long> e = it.next();
                if (e.getValue() > now) {
                    continue;
                }
                long pos = e.getKey();
                LevelChunk chunk = lod.level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos));
                if (chunk == null) {
                    it.remove();
                    continue;
                }
                if (!chunk.isLightCorrect()) {
                    continue;
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
                try {
                    lod = LodLevel.open(level, dir);
                } catch (IOException e) {
                    Farfield.LOG.error("Cannot open the LOD store of {} at {}", level.dimension().location(), dir, e);
                    return null;
                }
                levels.put(level, lod);
                Farfield.LOG.info("LOD store of {} ({}) opened at {}", lod.name(), name, dir);
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
        lod.snapshots.incrementAndGet();
        workers.execute(() -> {
            try {
                lod.world().ingest(snapshot.fill(lod, scratch.get()));
            } catch (Throwable t) {
                Farfield.LOG.error("LOD ingest of chunk {}, {} in {} failed", snapshot.chunkX, snapshot.chunkZ,
                        lod.name(), t);
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    private void maintain() {
        if (stopping) {
            return;
        }
        boolean sync = System.nanoTime() - lastSync > TimeUnit.SECONDS.toNanos(30);
        for (LodLevel lod : levels.values()) {
            try {
                lod.world().flush(TimeUnit.SECONDS.toNanos(3));
                if (lod.world().hasDirtyMips()) {
                    lod.world().buildMips(workers);
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
    }

    /**
     * Stops taking chunks, lets the workers finish, then writes and closes
     * every store. {@code beforeClose} runs once the workers stopped (to end
     * work that feeds the stores from elsewhere, such as an import).
     */
    public void stop(Runnable beforeClose) {
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
            try {
                lod.world().close();
                Farfield.LOG.info("LOD store of {} ({}) closed: {} chunks ingested ({} live snapshots, {} block changes),"
                                + " {} sections written, {} unchanged; {} chunks loaded, {} not snapshotted", lod.name(),
                        name, lod.world().chunksIngested(), lod.snapshots.get(), lod.blockChanges.get(),
                        lod.world().sectionsWritten(), lod.world().sectionsUnchanged(), lod.loaded.size(),
                        lod.due.size());
            } catch (IOException e) {
                Farfield.LOG.error("Closing the LOD store of {} failed", lod.name(), e);
            }
        }
        levels.clear();
    }
}
