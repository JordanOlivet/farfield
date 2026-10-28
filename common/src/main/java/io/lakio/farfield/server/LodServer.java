package io.lakio.farfield.server;

import java.util.List;
import java.util.ArrayList;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.importer.ImportListener;
import io.lakio.farfield.core.importer.ImportOptions;
import io.lakio.farfield.core.importer.ImportStats;
import io.lakio.farfield.core.importer.RegionImporter;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.LodLevel;
import io.lakio.farfield.lod.LodPipeline;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Server mode of ADR-022: the logical server (dedicated, or the integrated
 * server of single player) builds the LODs of its dimensions in
 * {@code <dimension folder>/farfield/} with a {@link LodPipeline}, and
 * {@code /farfield import} builds those of everything already generated
 * from the region files, skipping loaded chunks. The integrated server also
 * builds the noise preview of terrain not generated yet ({@link NoisePreview}).
 */
public final class LodServer {

    private static volatile LodServer instance;

    private final LodPipeline pipeline;
    /** Null on a dedicated server that does not stream the preview to its players (ADR-068). */
    private final NoisePreview preview;
    private volatile ImportJob importJob;

    private LodServer(boolean preview) {
        this.preview = preview ? new NoisePreview() : null;
        this.pipeline = new LodPipeline("server", level -> level instanceof ServerLevel,
                level -> dimensionFolder((ServerLevel) level).resolve("farfield"), LevelChunk::isLightCorrect);
    }

    private static Path dimensionFolder(ServerLevel level) {
        return DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
    }

    // ---------------------------------------------------------------- loader events

    public static void onServerStarting(MinecraftServer server) {
        // Single player builds the preview for its own client; a dedicated server for its players
        // when it streams it (ADR-068).
        StreamServer.onServerStarting(server);
        instance = new LodServer(!server.isDedicatedServer() || StreamServer.streamsPreview());
    }

    public static void onServerStopping(MinecraftServer server) {
        StreamServer.onServerStopping();
        LodServer s = instance;
        instance = null;
        if (s != null) {
            s.stop();
        }
    }

    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        LodServer s = instance;
        if (s != null) {
            s.pipeline.onChunkLoad(level, chunk);
        }
    }

    public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        LodServer s = instance;
        if (s != null) {
            s.pipeline.onChunkUnload(level, chunk);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        StreamServer.tick(server);
        LodServer s = instance;
        if (s != null) {
            s.pipeline.tick();
            if (s.preview != null) {
                s.preview.tick(server, s.pipeline);
            }
        }
    }

    static LodServer get() {
        return instance;
    }

    /**
     * {@code /farfield rebuild} (ADR-082): queues every coarse level of the
     * open stores of {@code dimension} (null: every dimension) for a rebuild
     * from level 0, which the maintenance builds a batch at a time; one line
     * per store, or none without a server.
     */
    static List<String> rebuildCoarseLevels(ResourceKey<Level> dimension) throws IOException {
        LodServer s = instance;
        List<String> lines = new ArrayList<>();
        if (s == null) {
            return lines;
        }
        for (LodLevel lod : s.pipeline.levels()) {
            if (dimension == null || lod.dimension() == dimension) {
                int queued = lod.world().rebuildCoarseLevels();
                lines.add(lod.name() + ": " + queued + " level-1 sections queued, the levels above follow");
            }
        }
        return lines;
    }

    /**
     * {@code /farfield rebuild cancel}: stops the rebuilds of {@code dimension}
     * (null: every dimension); one line per store, or none without a server.
     */
    static List<String> cancelRebuild(ResourceKey<Level> dimension) {
        LodServer s = instance;
        List<String> lines = new ArrayList<>();
        if (s == null) {
            return lines;
        }
        for (LodLevel lod : s.pipeline.levels()) {
            if (dimension == null || lod.dimension() == dimension) {
                int dropped = lod.world().cancelRebuild();
                lines.add(lod.name() + ": " + (dropped == 0 ? "no rebuild under way"
                        : "rebuild cancelled, " + dropped + " sections left unbuilt"));
            }
        }
        return lines;
    }

    /** {@code /farfield rebuild status}: per open store, the coarse sections left to build per level. */
    static List<String> coarseLevelsPending() {
        LodServer s = instance;
        List<String> lines = new ArrayList<>();
        if (s == null) {
            return lines;
        }
        for (LodLevel lod : s.pipeline.levels()) {
            int[] pending = lod.world().coarseLevelsPending();
            int total = 0;
            for (int n : pending) {
                total += n;
            }
            lines.add(lod.name() + ": " + (total == 0 ? "nothing left to build"
                    : total + " sections left (levels 1 to 4: " + pending[1] + ", " + pending[2] + ", " + pending[3]
                    + ", " + pending[4] + ")"));
        }
        return lines;
    }

    /**
     * Spike for ADR-079: builds one preview tile of {@code level} now, on the
     * calling thread, as the preview builds it for a player; the stage times
     * in nanoseconds ({@link NoisePreview#buildNow}), or null without a
     * preview or a store there.
     */
    static long[] buildPreviewTileNow(ServerLevel level, int tileX, int tileZ) {
        LodServer s = instance;
        LodLevel lod = s == null ? null : s.pipeline.level(level);
        if (s == null || s.preview == null || lod == null) {
            return null;
        }
        return s.preview.buildNow(level, lod.world(), tileX, tileZ);
    }

    /** Where the last tile {@link #buildPreviewTileNow} built spent its feature time. */
    static String lastPreviewTiming() {
        LodServer s = instance;
        return s == null || s.preview == null ? "" : s.preview.lastTiming;
    }

    /**
     * The LOD world of a dimension of the running server, or null (no
     * server, or the dimension has no store yet). For the single-player
     * renderer, which reads the integrated server's store in process.
     */
    public static LodWorld world(ResourceKey<Level> dimension) {
        LodServer s = instance;
        return s == null ? null : s.pipeline.world(dimension);
    }

    private void stop() {
        if (preview != null) {
            preview.stop();
        }
        ImportJob job = importJob;
        if (job != null) {
            job.importer.cancel();
        }
        pipeline.stop(() -> {
            if (job != null) {
                try {
                    job.thread.join(TimeUnit.SECONDS.toMillis(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
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
        if (importJob != null) {
            return false;
        }
        LodLevel lod = pipeline.level(level);
        Path regionDir = dimensionFolder(level).resolve("region");
        if (lod == null || !Files.isDirectory(regionDir)) {
            return false;
        }
        Path checkpoint = lod.dir().resolve("import.checkpoint");
        ImportOptions options = ImportOptions.defaults().withThreads(threads).withCheckpoint(checkpoint);
        LodWorld world = lod.world();
        RegionImporter importer = new RegionImporter(options, world.blockStates(), world.biomes(), chunk -> {
            if (lod.isLoaded(chunk.chunkX(), chunk.chunkZ())) {
                return; // the live path has a newer copy
            }
            try {
                world.ingest(chunk);
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
        Thread thread = new Thread(() -> runImport(lod, regionDir, importer, report), "farfield-import");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        importJob = new ImportJob(lod, importer, thread, System.nanoTime());
        thread.start();
        Farfield.LOG.info("LOD import of {} started from {} ({} threads)", lod.name(), regionDir, threads);
        return true;
    }

    private void runImport(LodLevel lod, Path regionDir, RegionImporter importer, Consumer<String> report) {
        long start = System.nanoTime();
        String line;
        try {
            ImportStats stats = importer.run(regionDir);
            double seconds = (System.nanoTime() - start) / 1e9;
            line = String.format(Locale.ROOT,
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
