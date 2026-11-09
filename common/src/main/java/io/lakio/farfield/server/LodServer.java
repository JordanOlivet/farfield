package io.lakio.farfield.server;

import java.util.List;
import java.util.ArrayList;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.importer.ImportListener;
import io.lakio.farfield.core.importer.ImportOptions;
import io.lakio.farfield.core.importer.ImportStats;
import io.lakio.farfield.core.importer.RegionImporter;
import io.lakio.farfield.core.importer.StartImport;
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
    /** Set at a stop that may leave a clean stop mark, written once the game has saved its regions. */
    private static volatile boolean cleanStop;

    private final LodPipeline pipeline;
    /** Null on a dedicated server that does not stream the preview to its players (ADR-068). */
    private final NoisePreview preview;
    private volatile ImportJob importJob;

    // ---- the import at start (ADR-124), server thread
    /** The world's stop mark, {@code farfield-stop.txt}. */
    private static final String STOP_MARK = "farfield-stop.txt";
    /** A finished whole import of a dimension, beside its checkpoint. */
    private static final String IMPORT_COMPLETE = "import.complete";
    private boolean startPlanned;
    private final java.util.ArrayDeque<Object[]> startQueue = new java.util.ArrayDeque<>();
    /** An operator's import pause holds the import; an operator's resume runs it with players online. */
    private boolean importManualPause;
    private boolean importForced;
    private boolean importHeldForPlayers;

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
            boolean importsLeft = s.importJob != null || !s.startQueue.isEmpty();
            cleanStop = s.startPlanned && !importsLeft;
            s.stop();
        }
    }

    /**
     * After the game saved its regions at a stop: a clean stop with nothing
     * left to import leaves the mark, so the next start reads only the regions
     * changed after it (Farfield away). Written here, not at the stop's start:
     * the final save rewrites regions after that, and a long save would have
     * made them look changed. With an import cut short, the "running" mark
     * stays and the next start offers every region again (the checkpoint
     * skips the finished ones).
     */
    public static void onServerStopped(MinecraftServer server) {
        if (!cleanStop) {
            return;
        }
        cleanStop = false;
        try {
            StartImport.write(server.getWorldPath(LevelResource.ROOT).resolve(STOP_MARK), true,
                    System.currentTimeMillis());
        } catch (IOException e) {
            Farfield.LOG.warn("Could not write the stop mark", e);
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
            if (server.isDedicatedServer()) {
                s.tickStartImport(server);
            }
            if (s.preview != null) {
                s.preview.tick(server, s.pipeline, s.importJob != null || !s.startQueue.isEmpty());
            }
        }
    }

    /**
     * The import at start (ADR-124): on the first tick, the dimensions whose
     * region files the LODs have not seen are queued (never imported, or
     * changed since the last clean stop); one runs at a time, nearest the
     * spawn first, held while players are online unless an operator resumed
     * it.
     */
    private void tickStartImport(MinecraftServer server) {
        if (!startPlanned) {
            startPlanned = true;
            planStartImport(server);
        }
        ImportJob job = importJob;
        if (job == null && !startQueue.isEmpty()) {
            Object[] next = startQueue.poll();
            ServerLevel level = (ServerLevel) next[0];
            @SuppressWarnings("unchecked")
            List<Path> regions = (List<Path>) next[1];
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            startImport(level, threads, line -> { }, regions, true);
            job = importJob;
        }
        if (job == null || !job.auto()) {
            return;
        }
        boolean players = StreamServer.settings().pregenPausesForPlayers() && server.getPlayerCount() > 0;
        boolean hold = importManualPause || players && !importForced;
        if (hold != importHeldForPlayers) {
            importHeldForPlayers = hold;
            if (hold) {
                job.importer().pause();
            } else {
                job.importer().resume();
            }
            Farfield.LOG.info(hold ? (importManualPause ? "LOD import paused by an operator"
                    : "LOD import paused: players online (pregen_when_players=pause)")
                    : "LOD import resumed");
        }
    }

    private void planStartImport(MinecraftServer server) {
        Path mark = server.getWorldPath(LevelResource.ROOT).resolve(STOP_MARK);
        StartImport.Mark last = null;
        try {
            last = StartImport.read(mark);
            StartImport.write(mark, false, System.currentTimeMillis());
        } catch (IOException e) {
            Farfield.LOG.warn("Could not read or write the stop mark {}", mark, e);
        }
        if (!StreamServer.settings().importOnStart()) {
            Farfield.LOG.info("LOD import at start: off (import_on_start=false)");
            return;
        }
        net.minecraft.core.BlockPos spawn = server.overworld().getSharedSpawnPos();
        for (ServerLevel level : server.getAllLevels()) {
            Path regionDir = dimensionFolder(level).resolve("region");
            LodLevel lod = pipeline.level(level);
            if (lod == null || !Files.isDirectory(regionDir)) {
                continue;
            }
            double scale = level.dimensionType().coordinateScale();
            int regionX = Math.floorDiv((int) Math.floor(spawn.getX() / scale), 512);
            int regionZ = Math.floorDiv((int) Math.floor(spawn.getZ() / scale), 512);
            try {
                boolean complete = Files.exists(lod.dir().resolve(IMPORT_COMPLETE));
                List<Path> all = RegionImporter.listRegions(regionDir, regionX, regionZ);
                List<Path> regions = StartImport.select(all, complete, last);
                String why = !complete ? "never imported whole" : last == null || !last.clean()
                        ? "no clean stop recorded, the checkpoint skips what did not change"
                        : "changed since the server's last stop";
                if (regions.isEmpty()) {
                    Farfield.LOG.info("LOD import at start of {}: up to date ({} regions)", lod.name(), all.size());
                } else {
                    Farfield.LOG.info("LOD import at start of {}: {} of {} regions queued ({})", lod.name(),
                            regions.size(), all.size(), why);
                    startQueue.add(new Object[]{level, regions});
                }
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.warn("LOD import at start of {}: cannot list its regions", lod.name(), e);
            }
        }
    }

    /** {@code /farfield import pause}: holds the running import until an operator resumes it. */
    synchronized boolean pauseImport() {
        ImportJob job = importJob;
        if (job == null) {
            return false;
        }
        importManualPause = true;
        importForced = false;
        job.importer().pause();
        importHeldForPlayers = true;
        Farfield.LOG.info("LOD import paused by an operator");
        return true;
    }

    /** {@code /farfield import resume}: runs the import, players online or not, until it ends. */
    synchronized boolean resumeImport() {
        ImportJob job = importJob;
        if (job == null) {
            return false;
        }
        importManualPause = false;
        importForced = true;
        job.importer().resume();
        importHeldForPlayers = false;
        Farfield.LOG.info("LOD import resumed by an operator");
        return true;
    }

    /** The dimensions the import at start still has to read. */
    List<String> importQueue() {
        List<String> names = new ArrayList<>();
        for (Object[] q : startQueue) {
            names.add(((ServerLevel) q[0]).dimension().location() + " (" + ((List<?>) q[1]).size() + " regions)");
        }
        return names;
    }

    /** The pregeneration tasks (ADR-123): null without a preview here or before the first update. */
    static PreviewPregen pregen() {
        LodServer s = instance;
        return s == null || s.preview == null ? null : s.preview.pregen();
    }

    /** Whether a preview tile of a dimension is built at the finest grid; null when not known yet. */
    static java.util.function.LongPredicate previewBuilt(String dimension) {
        LodServer s = instance;
        return s == null || s.preview == null ? null : s.preview.builtIn(dimension);
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
    record ImportJob(LodLevel level, RegionImporter importer, Thread thread, long startNanos, boolean auto) {
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
        return startImport(level, threads, report, null, false);
    }

    /**
     * As {@link #startImport(ServerLevel, int, Consumer)}, on the given regions
     * (null: all of them, nearest the origin first); {@code auto} for the
     * import at start, which the player rule holds.
     */
    synchronized boolean startImport(ServerLevel level, int threads, Consumer<String> report, List<Path> regions,
                                     boolean auto) {
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
        Thread thread = new Thread(() -> runImport(lod, regionDir, importer, report, regions), "farfield-import");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        importJob = new ImportJob(lod, importer, thread, System.nanoTime(), auto);
        importManualPause = false;
        importForced = !auto;
        importHeldForPlayers = false;
        thread.start();
        Farfield.LOG.info("LOD import of {} started from {} ({} threads{})", lod.name(), regionDir, threads,
                auto ? ", at start, " + regions.size() + " regions" : "");
        return true;
    }

    private void runImport(LodLevel lod, Path regionDir, RegionImporter importer, Consumer<String> report,
                           List<Path> regions) {
        long start = System.nanoTime();
        String line;
        try {
            ImportStats stats = regions == null ? importer.run(regionDir) : importer.run(regions);
            if (!importer.isCancelled() && stats.regionsFailed() == 0) {
                // Every region read: later starts only read what changed after a clean stop.
                Files.writeString(lod.dir().resolve(IMPORT_COMPLETE), "complete " + System.currentTimeMillis() + "\n");
            }
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
