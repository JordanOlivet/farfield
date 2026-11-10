package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.preview.PreviewPyramid;
import io.lakio.farfield.core.preview.PreviewStore;
import io.lakio.farfield.core.preview.PreviewTile;
import io.lakio.farfield.core.select.WantOrder;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.BlockKeys;
import io.lakio.farfield.lod.LodLevel;
import io.lakio.farfield.lod.LodPipeline;
import io.lakio.farfield.lod.RenderSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntBinaryOperator;

/**
 * Noise preview of terrain not generated yet (ADR-038, ADR-039), built by
 * the integrated server: every {@link #UPDATE_TICKS} ticks, the tiles
 * (512 x 512 blocks) within {@link #RANGE} blocks of a player, nearest and
 * in front first, on low-priority threads (the preview threads setting),
 * then the tiles of the pregeneration tasks ({@link PreviewPregen}, ADR-123)
 * in the threads the players leave free. A tile holds the surface every 4
 * blocks from the noise router's final density (each search starts from
 * the neighbor sample's height, then bisects to the block), the biome per
 * chunk and the surface blocks per 4-block cell, guessed from the biome and
 * the slope, and whether the sea freezes over it (vanilla's rule); the houses of the villages to come ({@link PreviewVillages});
 * then the world's own features grown on that ground ({@link
 * PreviewFeatures}, ADR-079: every mod's trees, plants and rocks where the
 * game will put them), away from villages. The client turns tiles into
 * sections of every level.
 *
 * <p>Tiles come as a pyramid (ADR-126, {@link PreviewPyramid}): the coarsest
 * level first over the whole range, then each finer one, nearest and in
 * front first. A coarse tile has the same samples farther apart, its ground
 * and canopy from what the features grow in each biome ({@link
 * PreviewProfiles}; a biome not measured yet is sampled on a few chunks
 * first), no features. A level-0 tile, within the features' distance, is
 * written twice: its terrain with the surface rules' ground and a canopy,
 * then with the world's own features.
 */
final class NoisePreview {

    /** Every half second: at 2 s, the threads sat idle once the tiles in flight were done. */
    private static final int UPDATE_TICKS = 10;
    /** A player this far (squared blocks) from their place at the last update jumped: the fill line starts. */
    private static final double JUMP_SQ = 512 * 512;
    /** Tiles this near the player count as near for the fill line. */
    private static final double NEAR = 1024;
    /** Tiles within 60 degrees of the view direction count as in front for the fill line. */
    private static final double FRONT_COS = 0.5;
    private static final long FILL_GIVE_UP_NANOS = 300_000_000_000L;
    private static final int REPORT_TICKS = 1200;
    private static final int MAX_IN_FLIGHT = 8;
    /** The LOD range: the renderer draws nothing farther. */
    private static final double RANGE = PreviewPyramid.RANGE;
    /** Chunks a side a biome's sample decorates (a chunk more around for what spills over). */
    private static final int SAMPLE_CHUNKS = 4;
    private static final long SNOW_SALT = 0x5DEECE66DL;
    private static final int TILE = PreviewTile.BLOCKS;
    private static final int UNKNOWN = Integer.MIN_VALUE;
    /** A rise of this many blocks between two samples (4 blocks apart) shows bare stone. */
    private static final int CLIFF_RISE = 8;

    /** Sized by the preview threads setting ({@link RenderSettings#previewThreads}), checked every update. */
    private final java.util.concurrent.ThreadPoolExecutor pool =
            (java.util.concurrent.ThreadPoolExecutor) Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "farfield-noise-preview");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private final Map<ResourceKey<Level>, Optional<Dimension>> dimensions = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private long ticks;
    /** Opened on the first update, once the server and its settings are there. */
    private PreviewPregen pregen;
    /** When the pipeline last took in new chunks; the preview keeps half its threads until {@link #YIELD_NANOS} after. */
    private long lastIngest;
    private static final long YIELD_NANOS = 5_000_000_000L;

    /** What one dimension's preview needs, and its progress. */
    private static final class Dimension {
        final ResourceKey<Level> key;
        final ServerLevel level;
        final BiomeSource biomes;
        final Climate.Sampler sampler;
        final DensityFunction density;
        /** The generator, for the spike's exact heights. */
        final NoiseBasedChunkGenerator generator;
        /** {@link #density} with vanilla's per-column caches, one per preview thread (ADR-057). */
        final ThreadLocal<DensityFunction> columnDensity;
        /** {@link #sampler} with the same caches, shared by its six functions (ADR-057). */
        final ThreadLocal<Climate.Sampler> columnSampler;
        final NoiseSettings noise;
        final int seaLevel;
        final BlockState defaultBlock;
        /** The sea's fluid, null when the dimension has none (the End). */
        final BlockState fluid;
        final boolean end;
        final long seed;
        final PreviewVillages villages;
        /** Vanilla's frozen ocean icebergs ({@code SurfaceSystem.frozenOceanExtension}). */
        final NormalNoise icebergPillar;
        final NormalNoise icebergPillarRoof;
        final NormalNoise icebergSurface;
        final AtomicInteger villagesBuilt = new AtomicInteger();
        /** Features that threw in the stand-in world since the last report (ADR-079). */
        final AtomicInteger featureFailures = new AtomicInteger();
        /** Light sources the features left in the tiles built since the last report (ADR-117). */
        final AtomicInteger lightSources = new AtomicInteger();
        /** Block columns the features dug (lakes) in the tiles built since the last report (ADR-118). */
        final AtomicInteger dugColumns = new AtomicInteger();
        /** Where the last tile's feature time went (the spike's report). */
        volatile String lastFeatureTiming = "";
        volatile LodWorld world;
        /** Tiles stored or built, by key: whether built to their end ({@link PreviewTile#complete()}). */
        final Map<Long, Boolean> done = new ConcurrentHashMap<>();
        /** Coarse tiles built and biomes sampled since the last report. */
        final AtomicInteger coarseBuilt = new AtomicInteger();
        final AtomicInteger biomesSampled = new AtomicInteger();
        private volatile PreviewProfiles profiles;
        final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
        final AtomicInteger built = new AtomicInteger();
        final AtomicLong builtNanos = new AtomicLong();
        /** Time of each {@link #STAGES} of the tiles built since the last report. */
        final java.util.concurrent.atomic.AtomicLongArray stageNanos =
                new java.util.concurrent.atomic.AtomicLongArray(STAGES.length);
        int left = -1;
        boolean reportedDone;
        /** Fill line: where the first player was at the last update, when they joined or jumped (0: not timed). */
        double lastX = Double.NaN;
        double lastZ;
        long fillStart;
        String fillCause;
        int fillTiles;
        long nearDone;
        long frontDone;

        Dimension(ServerLevel level, NoiseBasedChunkGenerator generator) {
            key = level.dimension();
            this.level = level;
            RandomState state = level.getChunkSource().randomState();
            biomes = generator.getBiomeSource();
            this.generator = generator;
            sampler = state.sampler();
            density = state.router().finalDensity();
            columnDensity = ThreadLocal.withInitial(() -> ColumnDensity.of(density));
            columnSampler = ThreadLocal.withInitial(() -> ColumnDensity.of(sampler));
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            noise = settings.noiseSettings();
            seaLevel = generator.getSeaLevel();
            defaultBlock = settings.defaultBlock();
            fluid = settings.defaultFluid().isAir() ? null : settings.defaultFluid();
            end = key == Level.END;
            seed = level.getSeed();
            villages = new PreviewVillages(level);
            icebergPillar = state.getOrCreateNoise(Noises.ICEBERG_PILLAR);
            icebergPillarRoof = state.getOrCreateNoise(Noises.ICEBERG_PILLAR_ROOF);
            icebergSurface = state.getOrCreateNoise(Noises.ICEBERG_SURFACE);
        }

        /**
         * The first free block above the iceberg vanilla's surface rule raises
         * from the sea at column (x, z) of a frozen ocean, or {@link #UNKNOWN}
         * without one ({@code SurfaceSystem.frozenOceanExtension}, without its
         * random holes).
         */
        int iceberg(Biome biome, int x, int z) {
            double d1 = Math.min(Math.abs(icebergSurface.getValue(x, 0.0, z) * 8.25),
                    icebergPillar.getValue(x * 1.28, 0.0, z * 1.28) * 15.0);
            if (d1 <= 1.8) {
                return UNKNOWN;
            }
            double roof = Math.abs(icebergPillarRoof.getValue(x * 1.17, 0.0, z * 1.17) * 1.5);
            double rise = Math.min(d1 * d1 * 1.2, Math.ceil(roof * 40.0) + 14.0);
            if (biome.shouldMeltFrozenOceanIcebergSlightly(new BlockPos(x, 63, z))) {
                rise -= 2.0;
            }
            return rise > 2.0 ? (int) (seaLevel + rise) : UNKNOWN;
        }

        boolean solid(int x, int y, int z) {
            return columnDensity.get().compute(new DensityFunction.SinglePointContext(x, y, z)) > 0;
        }

        /** What the features grow in each biome, made once the dimension's world is known. */
        PreviewProfiles profiles() {
            PreviewProfiles p = profiles;
            if (p == null) {
                synchronized (this) {
                    if (profiles == null) {
                        LodWorld w = world;
                        Map<Integer, Boolean> snowy = new ConcurrentHashMap<>();
                        java.util.function.IntPredicate snow = id -> snowy.computeIfAbsent(id, k -> {
                            String key = w.blockStates().key(k);
                            BlockState state = key == null ? null : BlockKeys.state(key);
                            return state != null && (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
                                    || state.is(Blocks.POWDER_SNOW));
                        });
                        profiles = new PreviewProfiles(snow,
                                w.blockStates().id(BlockKeys.key(Blocks.SNOW_BLOCK.defaultBlockState())), CLIFF_RISE);
                    }
                    p = profiles;
                }
            }
            return p;
        }
    }

    /** @param importing an LOD import runs or waits: the pregeneration holds past its near area (ADR-124) */
    void tick(MinecraftServer server, LodPipeline pipeline, boolean importing) {
        if (++ticks % UPDATE_TICKS != 0 || stopped || !RenderSettings.noisePreview()) {
            return;
        }
        // New chunks first: while the server takes in chunks a player generates, the preview keeps
        // half its threads, so their generation, light and ingest get the processor (ADR-084). One
        // thread there left a 16-core server mostly idle after a teleport (ADR-103).
        long now = System.nanoTime();
        if (pipeline.ingesting()) {
            lastIngest = now;
        }
        if (pregen == null) {
            pregen = PreviewPregen.open(server, StreamServer.settings());
        }
        pregen.setImporting(importing);
        boolean pregenOn = pregen.active(server);
        int usual = RenderSettings.effectiveThreads(RenderSettings.previewThreads());
        int pregenThreads = StreamServer.settings().pregenThreads();
        if (pregenOn && pregenThreads != io.lakio.farfield.core.config.ServerConfig.AUTO
                && server.getPlayerCount() == 0) {
            usual = pregenThreads; // nobody online: the pregeneration's own threads (ADR-126)
        }
        int threads = now - lastIngest < YIELD_NANOS ? io.lakio.farfield.core.config.RenderConfig.yieldingThreads(usual)
                : usual;
        RenderSettings.resize(pool, threads, "Noise preview");
        boolean report = ticks % REPORT_TICKS == 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            String name = level.dimension().location().toString();
            boolean pregenHere = pregenOn && pregen.runningIn(name);
            if (players.isEmpty() && !pregenHere) {
                continue;
            }
            Optional<Dimension> d = dimensions.computeIfAbsent(level.dimension(), k -> open(level));
            LodLevel lod = d.isEmpty() ? null : pipeline.level(level);
            if (lod == null) {
                continue;
            }
            Dimension dim = d.get();
            dim.world = lod.world();
            schedule(dim, players, pregenHere);
            if (report) {
                report(dim);
                if (pregenHere) {
                    pregen.report(tile -> built(dim, tile), name);
                }
            }
        }
    }

    /** The pregeneration tasks; null before the server's first update. */
    PreviewPregen pregen() {
        return pregen;
    }

    /** Whether a tile of a dimension (by its key, any level) is built to its end; null when its preview is not open. */
    java.util.function.LongPredicate builtIn(String dimension) {
        for (Optional<Dimension> d : dimensions.values()) {
            if (d.isPresent() && d.get().world != null && d.get().key.location().toString().equals(dimension)) {
                Dimension dim = d.get();
                return tile -> built(dim, tile);
            }
        }
        return null;
    }

    /** Whether a tile (by its key, any level) is stored and built to its end: a level-0 one with its features. */
    private static boolean built(Dimension d, long key) {
        Boolean built = d.done.get(key);
        if (built == null) {
            PreviewTile.Summary s = d.world.preview().summary(PreviewStore.level(key), PreviewStore.tileX(key),
                    PreviewStore.tileZ(key));
            if (s != null) {
                built = s.complete();
                d.done.put(key, built);
            }
        }
        return Boolean.TRUE.equals(built);
    }

    private static Optional<Dimension> open(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
                || level.dimensionType().hasCeiling()) {
            Farfield.LOG.info("Noise preview of {}: not built (no noise generator, or a ceiling)",
                    level.dimension().location());
            return Optional.empty();
        }
        Farfield.LOG.info("Noise preview of {}: building the terrain not generated yet within {} blocks",
                level.dimension().location(), (int) RANGE);
        return Optional.of(new Dimension(level, generator));
    }

    /**
     * Submits the missing tiles of the pyramid around the players (ADR-126):
     * the coarsest level first, then each finer one; within a level the
     * nearest, those behind counted farther ({@link WantOrder#score},
     * ADR-056); up to {@link #MAX_IN_FLIGHT} (or two per thread) at a time.
     */
    private void schedule(Dimension d, List<ServerPlayer> players, boolean pregenHere) {
        List<long[]> candidates = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int features = StreamServer.settings().previewFeaturesDistance();
        for (ServerPlayer p : players) {
            Vec3 look = p.getLookAngle();
            double lookLength = Math.sqrt(look.x * look.x + look.z * look.z);
            for (int level = PreviewPyramid.LEVELS - 1; level >= 0; level--) {
                if (level == 0 && features <= 0) {
                    continue;
                }
                int range = PreviewPyramid.range(level, features);
                int size = PreviewStore.blocks(level);
                int x0 = Math.floorDiv((int) Math.floor(p.getX() - range), size);
                int x1 = Math.floorDiv((int) Math.floor(p.getX() + range), size);
                int z0 = Math.floorDiv((int) Math.floor(p.getZ() - range), size);
                int z1 = Math.floorDiv((int) Math.floor(p.getZ() + range), size);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        double dx = Math.max(0, Math.max(x * (double) size - p.getX(), p.getX() - (x + 1) * (double) size));
                        double dz = Math.max(0, Math.max(z * (double) size - p.getZ(), p.getZ() - (z + 1) * (double) size));
                        double distance = Math.sqrt(dx * dx + dz * dz);
                        long tile = PreviewStore.key(level, x, z);
                        if (distance > range || !seen.add(tile) || built(d, tile)) {
                            continue;
                        }
                        double cx = (x + 0.5) * size - p.getX();
                        double cz = (z + 0.5) * size - p.getZ();
                        double centerDistance = Math.sqrt(cx * cx + cz * cz);
                        // Looking straight up or down, or standing in the tile: no side is behind.
                        double facing = lookLength < 1e-3 || centerDistance < 1 ? 1
                                : (look.x * cx + look.z * cz) / (lookLength * centerDistance);
                        candidates.add(new long[]{tile, (long) WantOrder.score(distance, facing),
                                distance <= NEAR ? 1 : 0, facing >= FRONT_COS ? 1 : 0});
                    }
                }
            }
        }
        if (!players.isEmpty()) {
            d.left = candidates.size();
            trackFill(d, players.get(0), candidates);
        }
        candidates.sort((a, b) -> PreviewStore.level(a[0]) != PreviewStore.level(b[0])
                ? Integer.compare(PreviewStore.level(b[0]), PreviewStore.level(a[0])) : Long.compare(a[1], b[1]));
        int limit = Math.max(MAX_IN_FLIGHT, 2 * pool.getCorePoolSize());
        // A task an operator forced while players are online keeps half the queue: the players'
        // tiles alone filled it, and the forced task built nothing (2026-11-09).
        int playerLimit = pregenHere && pregen.forcedIn(d.key.location().toString()) ? limit - Math.max(1, limit / 2)
                : limit;
        for (long[] c : candidates) {
            if (d.inFlight.size() >= playerLimit) {
                break;
            }
            long tile = c[0];
            if (d.inFlight.add(tile)) {
                pool.execute(() -> build(d, tile));
            }
        }
        // Then the pregeneration's tiles, its pyramid first, in what the players leave free.
        if (pregenHere && d.inFlight.size() < limit) {
            for (long tile : pregen.next(d.key.location().toString(), t -> built(d, t), d.inFlight,
                    limit - d.inFlight.size())) {
                if (d.inFlight.add(tile)) {
                    pool.execute(() -> build(d, tile));
                }
            }
        }
    }

    /**
     * Times how long the tiles near a player who joined or jumped take, then
     * those in front of the player, then all of them; logs once all are built (or
     * after {@link #FILL_GIVE_UP_NANOS}). The view direction is the one of
     * each update: turning moves what counts as in front.
     */
    private static void trackFill(Dimension d, ServerPlayer p, List<long[]> candidates) {
        long now = System.nanoTime();
        double jx = p.getX() - d.lastX;
        double jz = p.getZ() - d.lastZ;
        boolean joined = Double.isNaN(d.lastX);
        d.lastX = p.getX();
        d.lastZ = p.getZ();
        if (joined || jx * jx + jz * jz > JUMP_SQ) {
            if (d.fillStart != 0) {
                logFill(d, now, "cut by another jump");
            }
            d.fillStart = now;
            d.fillCause = joined ? "joining" : "a jump";
            d.fillTiles = candidates.size();
            d.nearDone = 0;
            d.frontDone = 0;
        }
        if (d.fillStart == 0) {
            return;
        }
        boolean near = false;
        boolean front = false;
        for (long[] c : candidates) {
            near |= c[2] != 0;
            front |= c[3] != 0;
        }
        d.nearDone = near || d.nearDone != 0 ? d.nearDone : now;
        d.frontDone = front || d.frontDone != 0 ? d.frontDone : now;
        if (candidates.isEmpty()) {
            logFill(d, now, "all built");
        } else if (now - d.fillStart > FILL_GIVE_UP_NANOS) {
            logFill(d, now, candidates.size() + " still missing");
        }
    }

    private static void logFill(Dimension d, long now, String end) {
        if (RenderSettings.performanceLogs()) {
            Farfield.LOG.info("Noise preview fill after {}: {} tiles to build; within {} blocks done at {}, in front at {},"
                            + " {} at {} s", d.fillCause, d.fillTiles, (int) NEAR, seconds(d, d.nearDone),
                    seconds(d, d.frontDone), end, String.format("%.1f", (now - d.fillStart) / 1e9));
        }
        d.fillStart = 0;
    }

    private static String seconds(Dimension d, long t) {
        return t == 0 ? "-" : String.format("%.1f s", (t - d.fillStart) / 1e9);
    }

    /** The steps of a tile build, timed for the report line. */
    private static final String[] STAGES = {"heights", "cells", "biomes", "villages", "ground", "features", "write"};
    private static final int HEIGHTS = 0;
    private static final int CELLS = 1;
    private static final int BIOMES = 2;
    private static final int VILLAGES = 3;
    private static final int GROUND = 4;
    private static final int FEATURES = 5;
    private static final int WRITE = 6;

    private void report(Dimension d) {
        int built = d.built.getAndSet(0);
        long nanos = d.builtNanos.getAndSet(0);
        if (built > 0) {
            StringBuilder stages = new StringBuilder();
            for (int i = 0; i < STAGES.length; i++) {
                stages.append(i == 0 ? "" : ", ").append(STAGES[i]).append(' ')
                        .append(d.stageNanos.getAndSet(i, 0) / 1_000_000 / built);
            }
            if (RenderSettings.performanceLogs()) {
                Farfield.LOG.info("Noise preview of {}: {} tiles built in the last minute, {} of them coarse ({} ms"
                        + " each: {}; {} villages, {} features failed, {} light sources, {} dug columns, {} biomes"
                        + " sampled, {} known), {} left within {} blocks",
                        d.key.location(), built, d.coarseBuilt.getAndSet(0), nanos / 1_000_000 / built, stages,
                        d.villagesBuilt.getAndSet(0), d.featureFailures.getAndSet(0), d.lightSources.getAndSet(0),
                        d.dugColumns.getAndSet(0), d.biomesSampled.getAndSet(0), d.profiles().size(), d.left,
                        (int) RANGE);
            }
            d.reportedDone = false;
        } else if (d.left == 0 && !d.reportedDone) {
            if (RenderSettings.performanceLogs()) {
                Farfield.LOG.info("Noise preview of {}: every tile within {} blocks is built",
                        d.key.location(), (int) RANGE);
            }
            d.reportedDone = true;
        }
    }

    /**
     * Spike for ADR-079: builds one tile now, on the calling thread; returns
     * the time of each {@link #STAGES} in nanoseconds, then the features that
     * failed, or null when the dimension has no preview.
     */
    long[] buildNow(ServerLevel level, LodWorld world, int tileX, int tileZ) {
        Optional<Dimension> found = dimensions.computeIfAbsent(level.dimension(), k -> open(level));
        if (found.isEmpty()) {
            return null;
        }
        Dimension d = found.get();
        d.world = world;
        for (int i = 0; i < STAGES.length; i++) {
            d.stageNanos.set(i, 0);
        }
        d.featureFailures.set(0);
        build(d, PreviewStore.key(tileX, tileZ));
        long[] out = new long[STAGES.length + 1];
        for (int i = 0; i < STAGES.length; i++) {
            out[i] = d.stageNanos.getAndSet(i, 0);
        }
        out[STAGES.length] = d.featureFailures.getAndSet(0);
        lastTiming = d.lastFeatureTiming;
        return out;
    }

    /** Blocks between the samples of the grids {@link #heightsSpike} times: tile levels 0, 2, 4, 6, 8, 12 and 15. */
    private static final int[] SPIKE_SPACINGS = {4, 16, 64, 256, 1024, 16384, 131072};
    /** One agreeing sample in this many is checked against the generator's own height. */
    private static final int SPIKE_EXACT_EVERY = 64;

    /**
     * Spike for the coarse-to-fine study
     * ({@code docs/analysis/preview-coarse-to-fine.md}): the heights of one
     * tile's grid ({@link PreviewTile#SAMPLES} a side) centered on a block,
     * at each of {@link #SPIKE_SPACINGS}, each sample searched from its
     * neighbor's height as {@link #build} does, then from the top of the
     * world; one line per spacing, with both searches against the generator's
     * own height ({@code getBaseHeight}, exact and slow) on the samples where
     * they differ and on one sample in {@link #SPIKE_EXACT_EVERY} of the
     * others. False when the dimension has no preview.
     */
    boolean heightsSpike(ServerLevel level, int x, int z, java.util.function.Consumer<String> report) {
        Optional<Dimension> found = dimensions.computeIfAbsent(level.dimension(), k -> open(level));
        if (found.isEmpty()) {
            return false;
        }
        Dimension d = found.get();
        int n = PreviewTile.SAMPLES;
        int[] seeded = new int[n * n];
        int[] fromTop = new int[n * n];
        // Once unmeasured: the first grid would pay for the compiler.
        heightsGrid(d, x, z, PreviewTile.GRID, true, seeded);
        heightsGrid(d, x, z, PreviewTile.GRID, false, fromTop);
        for (int spacing : SPIKE_SPACINGS) {
            if (stopped) {
                return true;
            }
            long t0 = System.nanoTime();
            heightsGrid(d, x, z, spacing, true, seeded);
            long t1 = System.nanoTime();
            heightsGrid(d, x, z, spacing, false, fromTop);
            long t2 = System.nanoTime();
            int differ = 0;
            int lower = 0;
            int worst = 0;
            long steps = 0;
            for (int i = 0; i < seeded.length; i++) {
                int gap = Math.abs(seeded[i] - fromTop[i]);
                differ += gap != 0 ? 1 : 0;
                lower += seeded[i] < fromTop[i] ? 1 : 0;
                worst = Math.max(worst, gap);
                if (i % n != 0) {
                    steps += Math.abs(fromTop[i] - fromTop[i - 1]);
                }
            }
            int x0 = x - spacing * (n / 2);
            int z0 = z - spacing * (n / 2);
            int nearerNeighbor = 0;
            int nearerTop = 0;
            int exactDiffer = 0;
            int exactSame = 0;
            long errorNeighbor = 0;
            long errorTop = 0;
            long errorSame = 0;
            int worstSame = 0;
            for (int i = 0; i < seeded.length && d.generator != null; i++) {
                boolean differs = seeded[i] != fromTop[i];
                if (!differs && i % SPIKE_EXACT_EVERY != 0) {
                    continue;
                }
                int exact = d.generator.getBaseHeight(x0 + i % n * spacing, z0 + i / n * spacing,
                        net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level,
                        level.getChunkSource().randomState());
                int a = Math.abs(seeded[i] - exact);
                int b = Math.abs(fromTop[i] - exact);
                if (differs) {
                    exactDiffer++;
                    errorNeighbor += a;
                    errorTop += b;
                    nearerNeighbor += a < b ? 1 : 0;
                    nearerTop += b < a ? 1 : 0;
                } else {
                    exactSame++;
                    errorSame += a;
                    worstSame = Math.max(worstSame, a);
                }
            }
            report.accept(String.format(java.util.Locale.ROOT, "samples every %d blocks, against the generator: where the"
                            + " searches differ (%d samples) the neighbor's is nearer on %d and the top's on %d, mean error"
                            + " %.1f and %.1f blocks; where they agree (%d samples checked) mean error %.2f blocks,"
                            + " worst %d", spacing, exactDiffer, nearerNeighbor, nearerTop,
                    errorNeighbor / (double) Math.max(1, exactDiffer), errorTop / (double) Math.max(1, exactDiffer),
                    exactSame, errorSame / (double) Math.max(1, exactSame), worstSame));
            report.accept(String.format(java.util.Locale.ROOT, "samples every %d blocks (tile level %d, %d blocks a side):"
                            + " from the neighbor %d ms (%.1f us a sample), from the top %d ms (%.1f us a sample);"
                            + " %d of %d samples differ (%d lower from the neighbor, worst %d blocks);"
                            + " mean step between neighbors %.1f blocks",
                    spacing, Integer.numberOfTrailingZeros(spacing / PreviewTile.GRID), spacing * (n - 1),
                    (t1 - t0) / 1_000_000, (t1 - t0) / 1e3 / seeded.length, (t2 - t1) / 1_000_000,
                    (t2 - t1) / 1e3 / seeded.length, differ, seeded.length, lower, worst,
                    steps / (double) (n * (n - 1))));
        }
        return true;
    }

    /** The surface of a grid of {@link PreviewTile#SAMPLES} samples a side centered on (x, z), row by row into {@code out}. */
    private static void heightsGrid(Dimension d, int x, int z, int spacing, boolean fromNeighbor, int[] out) {
        int n = PreviewTile.SAMPLES;
        int x0 = x - spacing * (n / 2);
        int z0 = z - spacing * (n / 2);
        int previous = UNKNOWN;
        for (int k = 0; k < n; k++) {
            int seed = k == 0 ? UNKNOWN : out[(k - 1) * n];
            for (int i = 0; i < n; i++) {
                int h = surface(d, x0 + i * spacing, z0 + k * spacing, !fromNeighbor ? UNKNOWN : i == 0 ? seed : previous);
                out[k * n + i] = h;
                previous = h;
            }
        }
    }

    /** Where the last tile {@link #buildNow} built spent its feature time. */
    volatile String lastTiming = "";

    /** The names of the stages {@link #buildNow} times. */
    static String[] stages() {
        return STAGES.clone();
    }

    /** Adds the time since {@code from} to a stage; returns now. */
    private static long stage(Dimension d, int stage, long from) {
        long now = System.nanoTime();
        d.stageNanos.addAndGet(stage, now - from);
        return now;
    }

    /** Builds and stores one tile of any level. */
    private void build(Dimension d, long key) {
        int level = PreviewStore.level(key);
        int tileX = PreviewStore.tileX(key);
        int tileZ = PreviewStore.tileZ(key);
        long start = System.nanoTime();
        try {
            boolean done = level == 0 ? buildFine(d, tileX, tileZ, start) : buildCoarse(d, level, tileX, tileZ, start);
            if (done) {
                d.done.put(key, true);
                d.built.incrementAndGet();
                d.coarseBuilt.addAndGet(level > 0 ? 1 : 0);
                d.builtNanos.addAndGet(System.nanoTime() - start);
            }
        } catch (IOException | RuntimeException e) {
            if (!stopped) {
                Farfield.LOG.warn("Noise preview of {}: level-{} tile {}, {} failed", d.key.location(), level, tileX,
                        tileZ, e);
            }
        } finally {
            d.inFlight.remove(key);
        }
    }

    /**
     * The surface of a tile's samples, {@code spacing} blocks apart from its
     * corner (x0, z0), each searched from its neighbor's height; false when
     * the preview stopped meanwhile.
     */
    private boolean heights(Dimension d, PreviewTile tile, int x0, int z0, int spacing) {
        int previous = UNKNOWN;
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            // Each row starts from the one above: neighbors are seeds, not results.
            int seed = k == 0 ? UNKNOWN : tile.sample(0, k - 1);
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                if (stopped) {
                    return false;
                }
                int h = surface(d, x0 + i * spacing, z0 + k * spacing, i == 0 ? seed : previous);
                tile.setHeight(i, k, h);
                previous = h;
            }
        }
        return true;
    }

    /**
     * Sets a tile's cells from its samples and biomes: the top and filler
     * blocks (from the biome's measured ground when {@code profiles} is
     * given and knows it, else guessed), the icebergs, the frozen sea; then
     * the tile's ground, sea and ice. Returns the cells icebergs took.
     */
    private static boolean[] cells(Dimension d, PreviewTile tile, int x0, int z0, int spacing, Materials m,
                                   Holder<Biome>[] chunkBiomes, boolean[] icebergs, Holder<Biome>[] seaBiomes,
                                   PreviewProfiles profiles) {
        boolean[] icebergCells = new boolean[PreviewTile.CELLS * PreviewTile.CELLS];
        int cliff = CLIFF_RISE * spacing / PreviewTile.GRID;
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                int h = Math.max(Math.max(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.max(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                int low = Math.min(Math.min(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.min(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                Holder<Biome> biome = chunkBiomes[(cz >> 2) * PreviewTile.CHUNKS + (cx >> 2)];
                int x = x0 + cx * spacing;
                int z = z0 + cz * spacing;
                boolean steep = h - low >= cliff;
                long measured = profiles == null ? -1 : profiles.ground(tile.biome(cx >> 2, cz >> 2),
                        d.fluid != null && h <= d.seaLevel ? PreviewProfiles.UNDER_SEA
                                : steep ? PreviewProfiles.STEEP : PreviewProfiles.FLAT);
                if (measured >= 0) {
                    int top = (int) (measured >>> 32);
                    int filler = (int) measured;
                    // Snow over the rules' top on cold land, as a level-0 tile's cells have it.
                    if (h > d.seaLevel && d.fluid != null
                            && biome.value().coldEnoughToSnow(new BlockPos(x + spacing / 2, h, z + spacing / 2))) {
                        filler = top;
                        top = m.snow;
                    }
                    m.set(top, filler);
                } else {
                    m.surface(biome, x, h, z, steep);
                }
                if (h > d.seaLevel && iceberg(icebergs, cx, cz)) {
                    // Vanilla caps the highest icebergs with a few blocks of snow from sea + 18 to 27.
                    m.set(h - 1 > d.seaLevel + ICEBERG_SNOW ? m.snow : m.packedIce, m.packedIce);
                    icebergCells[cz * PreviewTile.CELLS + cx] = true;
                }
                tile.setCell(cx, cz, m.top, m.filler);
                // Vanilla's freeze_top_layer: the sea's top block turns to ice where the
                // biome there is too cold to rain (frozen oceans: a noise leaves open water).
                // The cell's own biome at the sea's top, as vanilla, not the chunk's: one
                // chunk-wide biome left whole chunks of open water in a frozen sea.
                if (m.ice >= 0 && low < d.seaLevel) {
                    Holder<Biome> surface = seaBiome(d, seaBiomes, x0, z0, cx, cz, spacing);
                    tile.setFrozen(cx, cz, !surface.value().warmEnoughToRain(
                            new BlockPos(x + spacing / 2, d.seaLevel - 1, z + spacing / 2)));
                }
            }
        }
        tile.setBelow(m.below);
        tile.setWater(m.water, d.seaLevel);
        tile.setIce(m.ice);
        tile.setIceberg(m.packedIce);
        tile.setPlantGround(m.grass);
        return icebergCells;
    }

    /**
     * Builds a level-0 tile and stores it: its terrain (heights, the surface
     * rules' ground, villages, a canopy and plants from the profiles) first,
     * then, unless the settings turn them off, again with the world's own
     * features. False when the preview stopped meanwhile.
     */
    private boolean buildFine(Dimension d, int tileX, int tileZ, long start) throws IOException {
        LodWorld world = d.world;
        PreviewTile tile = new PreviewTile();
        int x0 = tileX * TILE;
        int z0 = tileZ * TILE;
        if (!heights(d, tile, x0, z0, PreviewTile.GRID)) {
            return false;
        }
        // Biomes at the sea's top by quart, shared by the icebergs (samples) and the frozen cells.
        @SuppressWarnings("unchecked")
        Holder<Biome>[] seaBiomes = new Holder[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        boolean[] icebergs = icebergs(d, tile, x0, z0, PreviewTile.GRID, seaBiomes);
        long t = stage(d, HEIGHTS, start);
        Materials m = new Materials(world, d);
        Holder<Biome>[] chunkBiomes = biomes(d, tile, x0, z0, 0, world);
        t = stage(d, BIOMES, t);
        boolean[] icebergCells = cells(d, tile, x0, z0, PreviewTile.GRID, m, chunkBiomes, icebergs, seaBiomes, null);
        t = stage(d, CELLS, t);
        if (stopped) {
            return false;
        }
        List<PreviewVillages.Village> villages = villages(d, tile, x0, z0, m);
        t = stage(d, VILLAGES, t);
        io.lakio.farfield.core.config.ServerConfig settings = StreamServer.settings();
        PreviewFeatures features = new PreviewFeatures(d.level, d.biomes, d.columnSampler.get(), d.fluid, d.seaLevel,
                d.world, settings, tile, tileX, tileZ, (x, z) -> inVillage(villages, x, z), icebergCells);
        features.groundOnly();
        boolean grow = settings.previewFeatures();
        if (grow) {
            profileCover(d, tile, x0, z0, m, icebergCells);
        }
        tile.setComplete(!grow);
        t = stage(d, GROUND, t);
        if (stopped) {
            return false; // the stores close next
        }
        world.preview().write(tileX, tileZ, tile);
        t = stage(d, WRITE, t);
        if (!grow) {
            d.lastFeatureTiming = features.timing();
            return true;
        }
        tile.clearCanopy();
        tile.clearPlants();
        features.decorate();
        d.featureFailures.addAndGet(features.failures());
        d.lightSources.addAndGet(tile.lights().length / 2);
        d.dugColumns.addAndGet(tile.pits().length / PreviewTile.PIT_INTS);
        d.lastFeatureTiming = features.timing();
        tile.setComplete(true);
        t = stage(d, FEATURES, t);
        if (stopped) {
            return false;
        }
        world.preview().write(tileX, tileZ, tile);
        stage(d, WRITE, t);
        d.profiles().record(tile, 0, 0, PreviewTile.CHUNKS);
        return true;
    }

    /**
     * Builds a coarse tile of the pyramid and stores it (ADR-126): the same
     * samples {@code 4 << level} blocks apart, the biomes not measured yet
     * sampled first, then the ground, plants and canopy from what the
     * features grow in each biome. False when the preview stopped meanwhile.
     */
    private boolean buildCoarse(Dimension d, int level, int tileX, int tileZ, long start) throws IOException {
        LodWorld world = d.world;
        PreviewTile tile = new PreviewTile();
        tile.setLevel(level);
        int spacing = PreviewTile.GRID << level;
        int x0 = tileX * PreviewStore.blocks(level);
        int z0 = tileZ * PreviewStore.blocks(level);
        if (!heights(d, tile, x0, z0, spacing)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Holder<Biome>[] seaBiomes = new Holder[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        boolean[] icebergs = icebergs(d, tile, x0, z0, spacing, seaBiomes);
        long t = stage(d, HEIGHTS, start);
        Materials m = new Materials(world, d);
        Holder<Biome>[] chunkBiomes = biomes(d, tile, x0, z0, level, world);
        t = stage(d, BIOMES, t);
        boolean grow = StreamServer.settings().previewFeatures();
        if (grow) {
            sampleBiomes(d, tile, chunkBiomes, x0, z0, level, m);
            t = stage(d, FEATURES, t);
        }
        if (stopped) {
            return false;
        }
        boolean[] icebergCells = cells(d, tile, x0, z0, spacing, m, chunkBiomes, icebergs, seaBiomes, d.profiles());
        if (grow) {
            profileCover(d, tile, x0, z0, m, icebergCells);
        }
        t = stage(d, CELLS, t);
        if (stopped) {
            return false;
        }
        world.preview().write(level, tileX, tileZ, tile);
        stage(d, WRITE, t);
        return true;
    }

    /**
     * Samples the biomes of a coarse tile its profiles do not know yet: for
     * each, a few chunks where its first coarse chunk lies are decorated in
     * the stand-in world ({@link #sample}), once or twice a biome.
     */
    private void sampleBiomes(Dimension d, PreviewTile tile, Holder<Biome>[] chunkBiomes, int x0, int z0, int level,
                              Materials m) {
        PreviewProfiles profiles = d.profiles();
        Set<Integer> asked = new HashSet<>();
        for (int c = 0; c < chunkBiomes.length && !stopped; c++) {
            int id = tile.biome(c % PreviewTile.CHUNKS, c / PreviewTile.CHUNKS);
            if (id < 0 || !asked.add(id) || profiles.known(id) || !profiles.takeSample(id)) {
                continue;
            }
            int bx = x0 + ((c % PreviewTile.CHUNKS * 16 + 8) << level);
            int bz = z0 + ((c / PreviewTile.CHUNKS * 16 + 8) << level);
            sample(d, bx, bz, m);
            d.biomesSampled.incrementAndGet();
        }
    }

    /**
     * Decorates {@link #SAMPLE_CHUNKS} x {@link #SAMPLE_CHUNKS} chunks around
     * block (bx, bz), kept inside its level-0 tile, on their own heights and
     * the surface rules' ground, and measures what grew there into the
     * profiles, each chunk under its own biome.
     */
    private void sample(Dimension d, int bx, int bz, Materials m) {
        int tileX = Math.floorDiv(bx, TILE);
        int tileZ = Math.floorDiv(bz, TILE);
        int x0 = tileX * TILE;
        int z0 = tileZ * TILE;
        int window = SAMPLE_CHUNKS + 2;
        int lx = Math.max(0, Math.min(PreviewTile.CHUNKS - window, Math.floorMod(bx >> 4, PreviewTile.CHUNKS) - window / 2));
        int lz = Math.max(0, Math.min(PreviewTile.CHUNKS - window, Math.floorMod(bz >> 4, PreviewTile.CHUNKS) - window / 2));
        PreviewTile tile = new PreviewTile();
        int i0 = lx * 4;
        int i1 = (lx + window) * 4;
        int k0 = lz * 4;
        int k1 = (lz + window) * 4;
        int previous = UNKNOWN;
        for (int k = k0; k <= k1; k++) {
            int seed = k == k0 ? UNKNOWN : tile.sample(i0, k - 1);
            for (int i = i0; i <= i1; i++) {
                if (stopped) {
                    return;
                }
                int h = surface(d, x0 + i * PreviewTile.GRID, z0 + k * PreviewTile.GRID, i == i0 ? seed : previous);
                tile.setHeight(i, k, h);
                previous = h;
            }
        }
        // Outside the window, the window's edge: a stand-in the features reach past it stands on that.
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                if (i < i0 || i > i1 || k < k0 || k > k1) {
                    tile.setHeight(i, k, tile.sample(Math.max(i0, Math.min(i1, i)), Math.max(k0, Math.min(k1, k))));
                }
            }
        }
        @SuppressWarnings("unchecked")
        Holder<Biome>[] chunkBiomes = new Holder[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
        Holder<Biome> fallback = null;
        for (int cz = lz; cz < lz + window; cz++) {
            for (int cx = lx; cx < lx + window; cx++) {
                int x = x0 + cx * 16 + 8;
                int z = z0 + cz * 16 + 8;
                Holder<Biome> biome = d.biomes.getNoiseBiome(QuartPos.fromBlock(x),
                        QuartPos.fromBlock(tile.sample(cx * 4 + 2, cz * 4 + 2)), QuartPos.fromBlock(z),
                        d.columnSampler.get());
                chunkBiomes[cz * PreviewTile.CHUNKS + cx] = biome;
                tile.setBiome(cx, cz, d.world.biomes().id(BlockKeys.key(biome)));
                fallback = fallback == null ? biome : fallback;
            }
        }
        for (int c = 0; c < chunkBiomes.length; c++) {
            if (chunkBiomes[c] == null) {
                chunkBiomes[c] = fallback;
                tile.setBiome(c % PreviewTile.CHUNKS, c / PreviewTile.CHUNKS, -1);
            }
        }
        @SuppressWarnings("unchecked")
        Holder<Biome>[] seaBiomes = new Holder[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        cells(d, tile, x0, z0, PreviewTile.GRID, m, chunkBiomes, new boolean[PreviewTile.SAMPLES * PreviewTile.SAMPLES],
                seaBiomes, null);
        PreviewFeatures features = new PreviewFeatures(d.level, d.biomes, d.columnSampler.get(), d.fluid, d.seaLevel,
                d.world, StreamServer.settings(), tile, tileX, tileZ, (x, z) -> false, null);
        features.decorateWindow(lx + 1, lz + 1, SAMPLE_CHUNKS);
        d.featureFailures.addAndGet(features.failures());
        d.profiles().record(tile, lx + 1, lz + 1, SAMPLE_CHUNKS);
    }

    /**
     * Lays the plants and canopy the profiles give each biome over a tile of
     * any level (ADR-126): a chunk's plants are its biome's mean; a cell on
     * land takes its biome's canopy where a hash of its place falls under
     * the biome's cover, so the share of the ground under the canopy holds,
     * and its snow where another hash falls under the snowy share.
     */
    private static void profileCover(Dimension d, PreviewTile tile, int x0, int z0, Materials m,
                                     boolean[] icebergCells) {
        PreviewProfiles profiles = d.profiles();
        Map<Integer, PreviewProfiles.Canopy> canopies = new java.util.HashMap<>();
        for (int cz = 0; cz < PreviewTile.CHUNKS; cz++) {
            for (int cx = 0; cx < PreviewTile.CHUNKS; cx++) {
                int[] plants = profiles.plants(tile.biome(cx, cz));
                int[] entries = new int[plants.length / 2];
                int n = 0;
                for (int i = 0; i < plants.length; i += 2) {
                    int kind = tile.plantKind(plants[i]);
                    if (kind >= 0) {
                        entries[n++] = PreviewTile.plantEntry(kind, plants[i + 1] / 16.0);
                    }
                }
                tile.setPlants(cx, cz, java.util.Arrays.copyOf(entries, n));
            }
        }
        tile.setCanopySnow(m.snowLayer);
        int level = tile.level();
        int cellBlocks = PreviewTile.GRID << level;
        int cellX0 = Math.floorDiv(x0, cellBlocks);
        int cellZ0 = Math.floorDiv(z0, cellBlocks);
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                if (icebergCells[cz * PreviewTile.CELLS + cx]) {
                    continue;
                }
                int h = Math.max(Math.max(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.max(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                if (d.fluid != null && h <= d.seaLevel) {
                    continue; // under the sea: no canopy
                }
                PreviewProfiles.Canopy c = canopies.computeIfAbsent(tile.biome(cx >> 2, cz >> 2), profiles::canopy);
                if (c == null || c.cover() <= 0 || c.material() < 0
                        || !chance(d.seed, cellX0 + cx, cellZ0 + cz, level, c.cover())) {
                    continue;
                }
                boolean snow = chance(d.seed ^ SNOW_SALT, cellX0 + cx, cellZ0 + cz, level, c.snow());
                tile.setCanopy(cx, cz, PreviewTile.canopyWord(c.material(), c.top(), c.thickness(), snow));
            }
        }
    }

    /** Whether a hash of a cell's place, its level and the world's seed falls under {@code share} (0 to 1). */
    static boolean chance(long seed, int cellX, int cellZ, int level, double share) {
        long h = seed ^ cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xC2B2AE3D27D4EB4FL ^ (long) level << 56;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53 < share;
    }

    /** Blocks above the sea from which an iceberg's top shows snow (vanilla: 18 to 27, random). */
    private static final int ICEBERG_SNOW = 22;

    /**
     * Raises the samples ({@code spacing} blocks apart) under the sea of a
     * frozen ocean to the icebergs vanilla's surface rule builds there; returns
     * which samples rose, {@code k * SAMPLES + i}.
     */
    private static boolean[] icebergs(Dimension d, PreviewTile tile, int x0, int z0, int spacing,
                                      Holder<Biome>[] seaBiomes) {
        boolean[] raised = new boolean[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        if (d.fluid == null || !d.fluid.is(Blocks.WATER)) {
            return raised;
        }
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                if (tile.sample(i, k) > d.seaLevel) {
                    continue;
                }
                int x = x0 + i * spacing;
                int z = z0 + k * spacing;
                Holder<Biome> biome = seaBiome(d, seaBiomes, x0, z0, i, k, spacing);
                if (!biome.is(Biomes.FROZEN_OCEAN) && !biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                    continue;
                }
                int top = d.iceberg(biome.value(), x, z);
                if (top != UNKNOWN && top > tile.sample(i, k)) {
                    tile.setHeight(i, k, top);
                    raised[k * PreviewTile.SAMPLES + i] = true;
                }
            }
        }
        return raised;
    }

    /**
     * The biome at the sea's top at sample (i, k) of a tile, {@code spacing}
     * blocks apart, computed once: a sample and the cell it starts share it.
     */
    private static Holder<Biome> seaBiome(Dimension d, Holder<Biome>[] seaBiomes, int x0, int z0, int i, int k,
                                          int spacing) {
        int index = k * PreviewTile.SAMPLES + i;
        Holder<Biome> biome = seaBiomes[index];
        if (biome == null) {
            biome = d.biomes.getNoiseBiome(QuartPos.fromBlock(x0 + i * spacing),
                    QuartPos.fromBlock(d.seaLevel - 1), QuartPos.fromBlock(z0 + k * spacing), d.columnSampler.get());
            seaBiomes[index] = biome;
        }
        return biome;
    }

    /** Whether cell (cx, cz) touches a raised sample. */
    private static boolean iceberg(boolean[] raised, int cx, int cz) {
        for (int k = cz; k <= Math.min(cz + 1, PreviewTile.SAMPLES - 1); k++) {
            for (int i = cx; i <= Math.min(cx + 1, PreviewTile.SAMPLES - 1); i++) {
                if (raised[k * PreviewTile.SAMPLES + i]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds the houses of the villages near the tile whose corner lies in it;
     * returns those villages (no trees grow in them).
     */
    private static List<PreviewVillages.Village> villages(Dimension d, PreviewTile tile, int x0, int z0, Materials m) {
        int reach = PreviewVillages.RADIUS + 16;
        IntBinaryOperator surface = (x, z) -> x >= x0 && z >= z0 && x < x0 + TILE && z < z0 + TILE
                ? tile.height(x - x0, z - z0) : surface(d, x, z, UNKNOWN);
        List<PreviewVillages.Village> villages = d.villages.within(x0 - reach, z0 - reach, x0 + TILE + reach,
                z0 + TILE + reach, (x, z) -> d.biomes.getNoiseBiome(QuartPos.fromBlock(x),
                        QuartPos.fromBlock(surface.applyAsInt(x, z)), QuartPos.fromBlock(z), d.columnSampler.get()));
        for (PreviewVillages.Village v : villages) {
            if (v.x() >= x0 && v.z() >= z0 && v.x() < x0 + TILE && v.z() < z0 + TILE) {
                d.villagesBuilt.incrementAndGet();
            }
            int[] materials = m.village(v.style());
            for (PreviewVillages.House h : PreviewVillages.houses(v, d.seed, d.seaLevel, surface)) {
                if (h.x() >= x0 && h.z() >= z0 && h.x() < x0 + TILE && h.z() < z0 + TILE) {
                    tile.addBuilding(new PreviewTile.Building(h.x() - x0, h.z() - z0, h.width(), h.depth(), h.floor(),
                            h.top(), materials[0], materials[1]));
                }
            }
        }
        return villages;
    }

    private static boolean inVillage(List<PreviewVillages.Village> villages, int x, int z) {
        for (PreviewVillages.Village v : villages) {
            int dx = x - v.x();
            int dz = z - v.z();
            if (dx * dx + dz * dz < PreviewVillages.RADIUS * PreviewVillages.RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** The biome at the surface of each chunk's center (chunks {@code 16 << level} blocks wide), stored in the tile and returned. */
    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] biomes(Dimension d, PreviewTile tile, int x0, int z0, int level, LodWorld world) {
        Holder<Biome>[] out = new Holder[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
        for (int cz = 0; cz < PreviewTile.CHUNKS; cz++) {
            for (int cx = 0; cx < PreviewTile.CHUNKS; cx++) {
                int x = x0 + ((cx * 16 + 8) << level);
                int z = z0 + ((cz * 16 + 8) << level);
                int y = tile.sample(cx * 4 + 2, cz * 4 + 2);
                Holder<Biome> biome = d.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                        QuartPos.fromBlock(z), d.columnSampler.get());
                out[cz * PreviewTile.CHUNKS + cx] = biome;
                tile.setBiome(cx, cz, world.biomes().id(BlockKeys.key(biome)));
            }
        }
        return out;
    }

    /**
     * The first free block above the ground at (x, z): from {@code seed}
     * (a neighbor's surface) up or down by cell height to the first change,
     * then bisected to the block. A result with solid ground right above it
     * (a cave, an overhang) is searched again from the top of the world.
     */
    private static int surface(Dimension d, int x, int z, int seed) {
        int minY = d.noise.minY();
        int top = minY + d.noise.height() - 1;
        int step = d.noise.getCellHeight();
        if (seed != UNKNOWN && seed > minY && seed <= top) {
            int h;
            if (d.solid(x, seed - 1, z)) {
                int lo = seed - 1;
                int hi = lo + step;
                while (hi <= top && d.solid(x, hi, z)) {
                    lo = hi;
                    hi += step;
                }
                h = hi > top ? top + 1 : bisect(d, x, z, lo, hi);
            } else {
                int hi = seed - 1;
                int lo = hi - step;
                while (lo >= minY && !d.solid(x, lo, z)) {
                    hi = lo;
                    lo -= step;
                }
                h = lo < minY ? minY : bisect(d, x, z, lo, hi);
            }
            if (h > top || !d.solid(x, h + step, z) && !d.solid(x, h + 2 * step, z)) {
                return h;
            }
        }
        for (int y = top; y >= minY; y -= step) {
            if (d.solid(x, y, z)) {
                return bisect(d, x, z, y, Math.min(y + step, top + 1));
            }
        }
        return minY;
    }

    /** With {@code lo} solid and {@code hi} free: the first free block above the solid run, between them. */
    private static int bisect(Dimension d, int x, int z, int lo, int hi) {
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (d.solid(x, mid, z)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo + 1;
    }

    /** Material ids of one world for the surface guesses; {@link #surface} sets {@link #top} and {@link #filler}. */
    private static final class Materials {
        final LodWorld world;
        final Dimension d;
        final int below;
        final int water;
        final int grass;
        final int dirt;
        final int sand;
        final int sandstone;
        final int redSand;
        final int terracotta;
        final int gravel;
        final int stone;
        final int snow;
        final int packedIce;
        /** A snow layer, on tree tops. */
        final int snowLayer;
        final int mycelium;
        /** Ice over a frozen water sea; -1 for other fluids. */
        final int ice;
        int top;
        int filler;

        Materials(LodWorld world, Dimension d) {
            this.world = world;
            this.d = d;
            below = id(d.defaultBlock);
            water = d.fluid == null ? -1 : id(d.fluid);
            ice = d.fluid != null && d.fluid.is(Blocks.WATER) ? id(Blocks.ICE.defaultBlockState()) : -1;
            grass = id(Blocks.GRASS_BLOCK.defaultBlockState());
            dirt = id(Blocks.DIRT.defaultBlockState());
            sand = id(Blocks.SAND.defaultBlockState());
            sandstone = id(Blocks.SANDSTONE.defaultBlockState());
            redSand = id(Blocks.RED_SAND.defaultBlockState());
            terracotta = id(Blocks.TERRACOTTA.defaultBlockState());
            gravel = id(Blocks.GRAVEL.defaultBlockState());
            stone = id(Blocks.STONE.defaultBlockState());
            snow = id(Blocks.SNOW_BLOCK.defaultBlockState());
            packedIce = id(Blocks.PACKED_ICE.defaultBlockState());
            snowLayer = id(Blocks.SNOW.defaultBlockState());
            mycelium = id(Blocks.MYCELIUM.defaultBlockState());
        }

        /** Walls and roof of a village style ({@link PreviewVillages}). */
        int[] village(int style) {
            return switch (style) {
                case PreviewVillages.DESERT -> new int[]{id(Blocks.SMOOTH_SANDSTONE.defaultBlockState()),
                        id(Blocks.CUT_SANDSTONE.defaultBlockState())};
                case PreviewVillages.SAVANNA -> new int[]{id(Blocks.ACACIA_PLANKS.defaultBlockState()),
                        id(Blocks.ACACIA_LOG.defaultBlockState())};
                case PreviewVillages.TAIGA -> new int[]{id(Blocks.SPRUCE_PLANKS.defaultBlockState()),
                        id(Blocks.DARK_OAK_PLANKS.defaultBlockState())};
                case PreviewVillages.SNOWY -> new int[]{id(Blocks.SPRUCE_PLANKS.defaultBlockState()), snow};
                default -> new int[]{id(Blocks.OAK_PLANKS.defaultBlockState()),
                        id(Blocks.SPRUCE_PLANKS.defaultBlockState())};
            };
        }

        int id(BlockState state) {
            return world.blockStates().id(BlockKeys.key(state));
        }

        private void set(int topMaterial, int fillerMaterial) {
            top = topMaterial;
            filler = fillerMaterial;
        }

        /**
         * The blocks seen on top of a cell and just under it, guessed from its
         * biome and slope ({@link #guess}); the generator's surface rules replace
         * them where they run (ADR-101).
         */
        void surface(Holder<Biome> biome, int x, int surfaceY, int z, boolean steep) {
            BlockState[] g = guess(biome, x, surfaceY, z, steep, d.seaLevel, d.fluid != null, d.end, d.defaultBlock);
            set(cachedId(g[0]), cachedId(g[1]));
        }

        private final java.util.Map<BlockState, Integer> ids = new java.util.IdentityHashMap<>();

        private int cachedId(BlockState state) {
            return ids.computeIfAbsent(state, this::id);
        }
    }

    /**
     * The top block of a column and the one under it, guessed from its biome
     * and slope: the preview's ground before ADR-101, and still where the
     * generator's surface rules cannot run. {top, filler}.
     */
    static BlockState[] guess(Holder<Biome> biome, int x, int surfaceY, int z, boolean steep, int seaLevel,
                              boolean sea, boolean end, BlockState below) {
        if (end) {
            return new BlockState[]{below, below};
        }
        if (surfaceY < seaLevel && sea) {
            BlockState floor = biome.value().getBaseTemperature() < 0.15f ? Blocks.GRAVEL.defaultBlockState()
                    : Blocks.SAND.defaultBlockState();
            return new BlockState[]{floor, floor};
        }
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return new BlockState[]{steep ? Blocks.TERRACOTTA.defaultBlockState() : Blocks.RED_SAND.defaultBlockState(),
                    Blocks.TERRACOTTA.defaultBlockState()};
        }
        if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BEACH)) {
            return new BlockState[]{Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState()};
        }
        if (steep || biome.is(Biomes.STONY_PEAKS) || biome.is(Biomes.STONY_SHORE)) {
            return new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()};
        }
        if (biome.value().coldEnoughToSnow(new BlockPos(x, surfaceY, z))) {
            return new BlockState[]{Blocks.SNOW_BLOCK.defaultBlockState(), biome.is(BiomeTags.IS_MOUNTAIN)
                    ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState()};
        }
        if (biome.is(Biomes.MUSHROOM_FIELDS)) {
            return new BlockState[]{Blocks.MYCELIUM.defaultBlockState(), Blocks.DIRT.defaultBlockState()};
        }
        return new BlockState[]{Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState()};
    }

    /** Height a 4-block cell rises across its corners from which the guess calls it a cliff. */
    static int cliffRise() {
        return CLIFF_RISE;
    }

    /**
     * Stops building and waits for the running tiles, before the stores
     * close. The threads are not interrupted: one interrupted inside a read
     * of a mod jar (a structure template) closes that jar's channel for the
     * rest of the session (ClosedByInterruptException, seen on Cobblemon's
     * fossils at every stop). Running tiles end at their next stop check.
     */
    void stop() {
        stopped = true;
        pool.getQueue().clear();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                Farfield.LOG.warn("Noise preview threads still running after 10 s, interrupted");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
