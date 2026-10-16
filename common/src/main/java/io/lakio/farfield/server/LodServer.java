package io.lakio.farfield.server;

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
 * from the region files, skipping loaded chunks.
 */
public final class LodServer {

    private static volatile LodServer instance;

    private final LodPipeline pipeline;
    private volatile ImportJob importJob;

    private LodServer() {
        this.pipeline = new LodPipeline("server", level -> level instanceof ServerLevel,
                level -> dimensionFolder((ServerLevel) level).resolve("farfield"), LevelChunk::isLightCorrect);
    }

    private static Path dimensionFolder(ServerLevel level) {
        return DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
    }

    // ---------------------------------------------------------------- loader events

    public static void onServerStarting(MinecraftServer server) {
        instance = new LodServer();
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
        LodServer s = instance;
        if (s != null) {
            s.pipeline.tick();
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
        return s == null ? null : s.pipeline.world(dimension);
    }

    private void stop() {
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
