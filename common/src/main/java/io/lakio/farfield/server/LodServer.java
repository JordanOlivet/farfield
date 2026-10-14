package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.importer.ImportListener;
import io.lakio.farfield.core.importer.ImportOptions;
import io.lakio.farfield.core.importer.ImportStats;
import io.lakio.farfield.core.importer.RegionImporter;
import io.lakio.farfield.core.world.LodWorld;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The LOD side of a logical server (dedicated or integrated): builds and
 * stores the LODs of its dimensions (ADR-022).
 *
 * <ul>
 *   <li>A chunk is snapshotted on the server thread 2 s after it loads (its
 *       light is complete by then) and 5 s after its last block change, under
 *       a per-tick budget; workers convert and ingest it.</li>
 *   <li>A maintenance thread writes idle cached sections and rebuilds the
 *       mips every second, and makes the store durable every 30 s.</li>
 *   <li>{@code /farfield import} builds the LODs of everything already
 *       generated, from the region files, skipping loaded chunks.</li>
 * </ul>
 */
public final class LodServer {

    private static final long LOAD_DELAY_TICKS = 40;
    private static final long CHANGE_DELAY_TICKS = 100;
    private static final int SNAPSHOTS_PER_TICK = 16;
    private static final long SNAPSHOT_BUDGET_NANOS = 2_000_000;
    private static final int MAX_IN_FLIGHT = 256;

    private static volatile LodServer instance;

    private final MinecraftServer server;
    private final Map<ServerLevel, LodLevel> levels = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService maintenance;
    private final ThreadLocal<ChunkSnapshot.Scratch> scratch = ThreadLocal.withInitial(ChunkSnapshot.Scratch::new);
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean stopping;
    private volatile ImportJob importJob;
    private long lastSync = System.nanoTime();

    private LodServer(MinecraftServer server) {
        this.server = server;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        this.workers = Executors.newFixedThreadPool(threads, daemon("farfield-worker"));
        this.maintenance = Executors.newSingleThreadScheduledExecutor(daemon("farfield-maintenance"));
        maintenance.scheduleWithFixedDelay(this::maintain, 1, 1, TimeUnit.SECONDS);
        Farfield.LOG.info("LOD server started ({} worker threads)", threads);
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

    // ---------------------------------------------------------------- loader events

    public static void onServerStarting(MinecraftServer server) {
        instance = new LodServer(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        LodServer s = instance;
        instance = null;
        if (s != null) {
            s.stop();
        }
    }

    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        LodServer s = instance;
        if (s != null && !s.stopping) {
            LodLevel lod = s.level(level);
            if (lod != null) {
                long pos = chunk.getPos().toLong();
                lod.loaded.add(pos);
                lod.due.put(pos, level.getGameTime() + LOAD_DELAY_TICKS);
            }
        }
    }

    public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        LodServer s = instance;
        if (s == null || s.stopping) {
            return;
        }
        LodLevel lod = s.levels.get(level);
        if (lod == null) {
            return;
        }
        long pos = chunk.getPos().toLong();
        lod.loaded.remove(pos);
        // Changed and not snapshotted yet: take it now, it is still complete.
        if (lod.due.remove(pos) != null && chunk.isLightCorrect()) {
            s.submit(lod, ChunkSnapshot.take(level, chunk));
        }
    }

    /** A block changed in a loaded chunk (mixin into {@code LevelChunk.setBlockState}). */
    public static void onBlockChanged(ServerLevel level, ChunkPos pos) {
        LodServer s = instance;
        if (s != null && !s.stopping) {
            LodLevel lod = s.levels.get(level);
            if (lod != null) {
                lod.due.put(pos.toLong(), level.getGameTime() + CHANGE_DELAY_TICKS);
                lod.blockChanges.incrementAndGet();
            }
        }
    }

    public static void onServerTick(MinecraftServer server) {
        LodServer s = instance;
        if (s != null && !s.stopping) {
            s.tick();
        }
    }

    static LodServer get() {
        return instance;
    }

    /**
     * The LOD world of a dimension of the running server, or null (no
     * server, or the dimension has no store yet). For the single-player
     * renderer, which reads the integrated server's store in process.
     */
    public static LodWorld world(ResourceKey<Level> dimension) {
        LodServer s = instance;
        if (s == null || s.stopping) {
            return null;
        }
        ServerLevel level = s.server.getLevel(dimension);
        LodLevel lod = level == null ? null : s.levels.get(level);
        return lod == null ? null : lod.world;
    }

    // ---------------------------------------------------------------- work

    private LodLevel level(ServerLevel level) {
        LodLevel lod = levels.get(level);
        if (lod != null) {
            return lod;
        }
        synchronized (levels) {
            lod = levels.get(level);
            if (lod == null) {
                try {
                    lod = LodLevel.open(level);
                } catch (IOException e) {
                    Farfield.LOG.error("Cannot open the LOD store of {}", level.dimension().location(), e);
                    return null;
                }
                levels.put(level, lod);
                Farfield.LOG.info("LOD store of {} opened at {}", lod.name(), lod.dir);
            }
            return lod;
        }
    }

    private void tick() {
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

    private void submit(LodLevel lod, ChunkSnapshot snapshot) {
        inFlight.incrementAndGet();
        lod.snapshots.incrementAndGet();
        workers.execute(() -> {
            try {
                lod.world.ingest(snapshot.fill(lod, scratch.get()));
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
                lod.world.flush(TimeUnit.SECONDS.toNanos(3));
                if (lod.world.hasDirtyMips()) {
                    lod.world.buildMips(workers);
                }
                if (sync) {
                    lod.world.sync();
                }
            } catch (Throwable t) {
                Farfield.LOG.error("LOD maintenance of {} failed", lod.name(), t);
            }
        }
        if (sync) {
            lastSync = System.nanoTime();
        }
    }

    private void stop() {
        stopping = true;
        ImportJob job = importJob;
        maintenance.shutdown();
        workers.shutdown();
        try {
            if (job != null) {
                job.importer.cancel();
                job.thread.join(TimeUnit.SECONDS.toMillis(30));
            }
            maintenance.awaitTermination(30, TimeUnit.SECONDS);
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (LodLevel lod : levels.values()) {
            try {
                lod.world.close();
                Farfield.LOG.info("LOD store of {} closed: {} chunks ingested ({} live snapshots, {} block changes),"
                                + " {} sections written, {} unchanged; {} chunks loaded, {} not snapshotted", lod.name(),
                        lod.world.chunksIngested(), lod.snapshots.get(), lod.blockChanges.get(),
                        lod.world.sectionsWritten(), lod.world.sectionsUnchanged(), lod.loaded.size(), lod.due.size());
            } catch (IOException e) {
                Farfield.LOG.error("Closing the LOD store of {} failed", lod.name(), e);
            }
        }
        levels.clear();
    }

    // ---------------------------------------------------------------- import

    /** A running region-file import. */
    record ImportJob(LodLevel level, RegionImporter importer, Thread thread, long startNanos) {
    }

    ImportJob importJob() {
        return importJob;
    }

    /**
     * Starts importing the region files of {@code level} on a background
     * thread; {@code report} receives one line at the end. Returns false if
     * an import already runs or the level has no region folder.
     */
    synchronized boolean startImport(ServerLevel level, int threads, Consumer<String> report) {
        if (importJob != null || stopping) {
            return false;
        }
        LodLevel lod = level(level);
        if (lod == null || !Files.isDirectory(lod.regionDir)) {
            return false;
        }
        Path checkpoint = lod.dir.resolve("import.checkpoint");
        ImportOptions options = ImportOptions.defaults().withThreads(threads).withCheckpoint(checkpoint);
        RegionImporter importer = new RegionImporter(options, lod.world.blockStates(), lod.world.biomes(), chunk -> {
            if (lod.loaded.contains(ChunkPos.asLong(chunk.chunkX(), chunk.chunkZ()))) {
                return; // the live path has a newer copy
            }
            try {
                lod.world.ingest(chunk);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, new ImportListener() {
            @Override
            public void regionFailed(Path region, Throwable error) {
                Farfield.LOG.warn("LOD import: region {} failed", region.getFileName(), error);
            }

            @Override
            public void chunkFailed(Path region, int chunkX, int chunkZ, Throwable error) {
                Farfield.LOG.warn("LOD import: chunk {}, {} of {} failed: {}", chunkX, chunkZ, region.getFileName(),
                        error.toString());
            }
        });
        Thread thread = new Thread(() -> runImport(lod, importer, report), "farfield-import");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        importJob = new ImportJob(lod, importer, thread, System.nanoTime());
        thread.start();
        Farfield.LOG.info("LOD import of {} started from {} ({} threads)", lod.name(), lod.regionDir, threads);
        return true;
    }

    private void runImport(LodLevel lod, RegionImporter importer, Consumer<String> report) {
        long start = System.nanoTime();
        String line;
        try {
            ImportStats stats = importer.run(lod.regionDir);
            double seconds = (System.nanoTime() - start) / 1e9;
            line = String.format(java.util.Locale.ROOT,
                    "LOD import of %s %s: %,d chunks in %.0f s (%,.0f/s), %,d regions (%,d already done, %,d failed)",
                    lod.name(), importer.isCancelled() ? "stopped" : "done", stats.chunksImported(), seconds,
                    stats.chunksImported() / Math.max(seconds, 1e-3), stats.regionsDone(), stats.regionsSkipped(),
                    stats.regionsFailed());
        } catch (Throwable t) {
            Farfield.LOG.error("LOD import of {} failed", lod.name(), t);
            line = "LOD import of " + lod.name() + " failed: " + t;
        } finally {
            synchronized (this) {
                importJob = null;
            }
        }
        Farfield.LOG.info(line);
        report.accept(line);
    }
}
