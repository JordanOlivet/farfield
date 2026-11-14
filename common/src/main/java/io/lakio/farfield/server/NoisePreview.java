package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.preview.PreviewPyramid;
import io.lakio.farfield.core.preview.PreviewStore;
import io.lakio.farfield.core.preview.PreviewTile;
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
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
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
import java.nio.file.Path;
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
    private static final double RANGE = PreviewPyramid.MAX_RANGE;
    /** Chunks a side a biome's sample decorates (a chunk more around for what spills over). */
    private static final int SAMPLE_CHUNKS = 4;
    private static final long SNOW_SALT = 0x5DEECE66DL;
    /** The biome profiles' file, beside the tiles (ADR-130). */
    private static final String PROFILES_FILE = "profiles.bin";
    /** The features the cost guard left out, beside the profiles (ADR-132). */
    private static final String FEATURE_COSTS_FILE = "feature-costs.bin";
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
    /** The server's preview threads setting last logged; -1 before the first update. */
    private int loggedServerThreads = -1;
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
        /**
         * {@link #sampler} with the same caches, shared by its six functions
         * (ADR-057), for a multi-noise biome source; any other source gets
         * {@link #sampler} itself (ADR-157).
         */
        final ThreadLocal<Climate.Sampler> columnSampler;
        /**
         * The surface search's densities (ADR-133): {@link #density} without
         * its cave branches ({@link UncutDensity}), how many were cut, and the
         * density without jaggedness vanilla's preliminary surface reads,
         * each with the per-column caches.
         */
        final int cutBranches;
        final ThreadLocal<DensityFunction> columnUncut;
        final ThreadLocal<DensityFunction> columnInitial;
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
        /** Tiles stored or built, by key: their quarters built and complete ({@link PreviewTile#quarters()}). */
        final Map<Long, Integer> done = new ConcurrentHashMap<>();
        /** Coarse quarters (tile key | quarter << 62) built without the canopy of these biomes, waiting for their sample. */
        final Map<Long, int[]> pending = new ConcurrentHashMap<>();
        /** Biomes whose sample is queued or running, and the queue: {biome, x, z}. */
        final Set<Integer> sampling = ConcurrentHashMap.newKeySet();
        final java.util.concurrent.ConcurrentLinkedQueue<int[]> sampleQueue =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        final AtomicInteger samplesInFlight = new AtomicInteger();
        /** Quarters in flight (queued or running) of the coarse levels and of level 0. */
        final AtomicInteger coarseInFlight = new AtomicInteger();
        final AtomicInteger fineInFlight = new AtomicInteger();
        /** The features' cost guard (ADR-131), shared by every tile and sample of the dimension. */
        final FeatureBudget budget = new FeatureBudget();
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
        /** The farthest distance a player's preview was built to at the last schedule (E.3b), for the logs. */
        volatile int range = PreviewPyramid.MAX_RANGE;
        boolean reportedDone;
        /** Fill line: where the first player was at the last update, when they joined or jumped (0: not timed). */
        double lastX = Double.NaN;
        double lastZ;
        long fillStart;
        String fillCause;
        int fillTiles;
        long nearDone;
        long frontDone;
        /** When the coarse disc was whole: no coarse quarter left to build (its canopy aside). */
        long coarseDone;
        /** When the coarsest level was whole (ADR-139): the first image to the drawn distance, or the border. */
        long topDone;

        Dimension(ServerLevel level, NoiseBasedChunkGenerator generator) {
            key = level.dimension();
            this.level = level;
            RandomState state = level.getChunkSource().randomState();
            biomes = generator.getBiomeSource();
            this.generator = generator;
            sampler = state.sampler();
            density = state.router().finalDensity();
            columnDensity = ThreadLocal.withInitial(() -> ColumnDensity.of(density));
            // The cached copy only where the climate is what the biome source reads (ADR-157): Fabric's End
            // biome API asks the world's own sampler for the seed it set on it, and a copy has none.
            columnSampler = biomes instanceof MultiNoiseBiomeSource
                    ? ThreadLocal.withInitial(() -> ColumnDensity.of(sampler))
                    : ThreadLocal.withInitial(() -> sampler);
            int[] cut = new int[1];
            DensityFunction uncut = UncutDensity.of(density, cut);
            cutBranches = cut[0];
            columnUncut = ThreadLocal.withInitial(() -> ColumnDensity.of(uncut));
            DensityFunction initial = state.router().initialDensityWithoutJaggedness();
            columnInitial = ThreadLocal.withInitial(() -> ColumnDensity.of(initial));
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

        Path profilesFile() {
            return world.preview().dir().resolve(PROFILES_FILE);
        }

        Path featureCostsFile() {
            return world.preview().dir().resolve(FEATURE_COSTS_FILE);
        }

        /**
         * What the profiles were measured with: the features the settings
         * leave out and the biomes the generator has. Another stamp drops them.
         */
        String profilesStamp() {
            List<String> keys = new ArrayList<>();
            for (Holder<Biome> b : biomes.possibleBiomes()) {
                keys.add(BlockKeys.key(b));
            }
            java.util.Collections.sort(keys);
            io.lakio.farfield.core.config.ServerConfig settings = StreamServer.settings();
            return "exclude=" + settings.previewFeatureExclude() + ";features=" + settings.previewFeatures()
                    + ";biomes=" + keys.size() + ":" + Integer.toHexString(String.join(",", keys).hashCode());
        }

        /** Saves the profiles when they changed; the key tables they refer to first. */
        void saveProfiles() {
            PreviewProfiles p = profiles;
            if (p == null || world == null) {
                return;
            }
            try {
                world.preview().persistKeys();
                String stamp = profilesStamp();
                if (p.save(profilesFile(), stamp)) {
                    Farfield.LOG.debug("Noise preview of {}: {} biome profiles saved", key.location(), p.size());
                }
                var registry = level.registryAccess().registryOrThrow(
                        net.minecraft.core.registries.Registries.PLACED_FEATURE);
                budget.save(featureCostsFile(), stamp, f -> {
                    var id = registry.getKey(f);
                    return id == null ? "" : id.toString();
                });
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.warn("Noise preview of {}: could not save {}", key.location(), PROFILES_FILE, e);
            }
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
                        PreviewProfiles made = new PreviewProfiles(snow,
                                w.blockStates().id(BlockKeys.key(Blocks.SNOW_BLOCK.defaultBlockState())), CLIFF_RISE);
                        int read = made.load(profilesFile(), profilesStamp());
                        Farfield.LOG.info("Noise preview of {}: {}", key.location(), read > 0
                                ? read + " biome profiles read from " + PROFILES_FILE
                                : read == -1 ? PROFILES_FILE + " measured with other features or biomes, measured again"
                                : read == -2 ? PROFILES_FILE + " did not read, measured again"
                                : "no biome profiles yet, measured as tiles are built");
                        profiles = made;
                        int left = budget.load(featureCostsFile(), profilesStamp(), name -> {
                            var registry = level.registryAccess().registryOrThrow(
                                    net.minecraft.core.registries.Registries.PLACED_FEATURE);
                            var id = net.minecraft.resources.ResourceLocation.tryParse(name);
                            return id == null ? null : registry.get(id);
                        });
                        if (left != 0) {
                            Farfield.LOG.info("Noise preview of {}: {}", key.location(), left > 0
                                    ? left + " features left out for their cost read from " + FEATURE_COSTS_FILE
                                    : FEATURE_COSTS_FILE + " of other settings or unreadable, measured again");
                        }
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
        // The server's own setting first (ADR-129): a dedicated server has no preview setting of its own.
        int serverThreads = StreamServer.settings().previewThreads();
        int usual = serverThreads != io.lakio.farfield.core.config.ServerConfig.AUTO ? serverThreads
                : RenderSettings.effectiveThreads(RenderSettings.previewThreads());
        if (serverThreads != loggedServerThreads) {
            loggedServerThreads = serverThreads;
            Farfield.LOG.info("Noise preview threads around players: {} ({})", usual,
                    serverThreads == io.lakio.farfield.core.config.ServerConfig.AUTO ? "auto"
                            : "preview_threads=" + serverThreads + " in farfield-server.properties");
        }
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

    /** Whether a tile (by its key, any level) is stored with every quarter built and complete. */
    private static boolean built(Dimension d, long key) {
        return quarters(d, key) == PreviewTile.ALL_QUARTERS;
    }

    /** A tile's quarters built and complete ({@link PreviewTile#quarters()}), 0 when not stored. */
    /**
     * The blocks a player's preview is built to (E.3b): the single player's
     * own draw distance (the client in this process), else
     * what its client's hint asks, at most the streaming radius.
     */
    private static int distance(ServerPlayer p) {
        if (!p.server.isDedicatedServer() && p.server.isSingleplayerOwner(p.getGameProfile())) {
            return Math.min(PreviewPyramid.MAX_RANGE, RenderSettings.drawDistance());
        }
        return Math.min(PreviewPyramid.MAX_RANGE, StreamServer.previewDistance(p));
    }

    /**
     * Whether a quarter (its corner, its side in blocks) lies wholly outside
     * the world border (E.3d): nothing past it is ever generated or drawn.
     */
    private static boolean outsideBorder(Dimension d, double x0, double z0, int side) {
        var border = d.level.getWorldBorder();
        return x0 >= border.getMaxX() || z0 >= border.getMaxZ() || x0 + side <= border.getMinX()
                || z0 + side <= border.getMinZ();
    }

    private static int quarters(Dimension d, long key) {
        Integer quarters = d.done.get(key);
        if (quarters == null) {
            PreviewTile.Summary s = d.world.preview().summary(PreviewStore.level(key), PreviewStore.tileX(key),
                    PreviewStore.tileZ(key));
            quarters = s == null ? 0 : s.quarters();
            if (s != null) {
                d.done.put(key, quarters);
            }
        }
        return quarters;
    }

    private static Optional<Dimension> open(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
                || level.dimensionType().hasCeiling()) {
            Farfield.LOG.info("Noise preview of {}: not built (no noise generator, or a ceiling)",
                    level.dimension().location());
            return Optional.empty();
        }
        Farfield.LOG.info("Noise preview of {}: building the terrain not generated yet, to each player's draw"
                + " distance (at most {} blocks)", level.dimension().location(), (int) RANGE);
        Dimension d = new Dimension(level, generator);
        Farfield.LOG.info("Noise preview of {}: surface searched on the density without {} cave branch{} (ADR-133)",
                level.dimension().location(), d.cutBranches, d.cutBranches == 1 ? "" : "es");
        return Optional.of(d);
    }

    /** Blocks a finer level's quarters wait behind the coarser front, per level (ADR-127). */
    private static final double LEVEL_LAG = PreviewPyramid.LEVEL_LAG;
    /** Blocks a coarse quarter waiting for its canopy waits behind everything else. */
    private static final double REFINE_LAG = 8192;
    /**
     * Submits the quarters of the pyramid missing around the players
     * (ADR-126, ADR-127), in rings: by their distance alone (a pure circle:
     * counting those behind farther filled the view no sooner, ADR-127), a
     * finer level's {@link #LEVEL_LAG} blocks behind the coarser one, so a
     * coarse disc grows from the player and the detail follows it; in turn
     * with a far front, the coarsest level's quarters by distance, so it
     * reaches the border early (ADR-139); one quarter of a
     * tile at a time; up to
     * {@link #MAX_IN_FLIGHT} (or two per thread) at a time, the biomes'
     * samples given part of the threads first.
     */
    private void schedule(Dimension d, List<ServerPlayer> players, boolean pregenHere) {
        int farthest = 0;
        List<long[]> candidates = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int features = StreamServer.settings().previewFeaturesDistance();
        for (ServerPlayer p : players) {
            Vec3 look = p.getLookAngle();
            double lookLength = Math.sqrt(look.x * look.x + look.z * look.z);
            int drawn = distance(p);
            farthest = Math.max(farthest, drawn);
            // The four steps (ADR-152): the near ring past the chunks the game itself sends, the levels
            // by the player's own detail.
            double ring = p.server.getPlayerList().getViewDistance() * 16.0 + PreviewPyramid.NEAR_RING_MARGIN;
            double detail = !p.server.isDedicatedServer() && p.server.isSingleplayerOwner(p.getGameProfile())
                    ? RenderSettings.splitDetail() : StreamServer.previewDetail(p);
            for (int level = PreviewPyramid.LEVELS - 1; level >= 0; level--) {
                if (level == 0 && features <= 0) {
                    continue;
                }
                int range = PreviewPyramid.range(level, features, drawn);
                int size = PreviewStore.blocks(level);
                int quarter = size / 2;
                int x0 = Math.floorDiv((int) Math.floor(p.getX() - range), quarter);
                int x1 = Math.floorDiv((int) Math.floor(p.getX() + range), quarter);
                int z0 = Math.floorDiv((int) Math.floor(p.getZ() - range), quarter);
                int z1 = Math.floorDiv((int) Math.floor(p.getZ() + range), quarter);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        double dx = Math.max(0, Math.max(x * (double) quarter - p.getX(),
                                p.getX() - (x + 1) * (double) quarter));
                        double dz = Math.max(0, Math.max(z * (double) quarter - p.getZ(),
                                p.getZ() - (z + 1) * (double) quarter));
                        double distance = Math.sqrt(dx * dx + dz * dz);
                        if (distance > range || outsideBorder(d, x * (double) quarter, z * (double) quarter,
                                quarter)) {
                            continue;
                        }
                        long tile = PreviewStore.key(level, Math.floorDiv(x, 2), Math.floorDiv(z, 2));
                        int q = Math.floorMod(z, 2) * 2 + Math.floorMod(x, 2);
                        int quarters = quarters(d, tile);
                        boolean refine = (quarters >> q & 1) != 0;
                        if (refine && ((quarters >> (q + 4) & 1) != 0 || !ready(d, tile, q))
                                || !seen.add(tile | (long) q << 62)) {
                            continue;
                        }
                        double cx = (x + 0.5) * quarter - p.getX();
                        double cz = (z + 0.5) * quarter - p.getZ();
                        double centerDistance = Math.sqrt(cx * cx + cz * cz);
                        // Looking straight up or down, or standing in the quarter: no side is behind.
                        double facing = lookLength < 1e-3 || centerDistance < 1 ? 1
                                : (look.x * cx + look.z * cz) / (lookLength * centerDistance);
                        double fx = Math.max(Math.abs(x * (double) quarter - p.getX()),
                                Math.abs((x + 1) * (double) quarter - p.getX()));
                        double fz = Math.max(Math.abs(z * (double) quarter - p.getZ()),
                                Math.abs((z + 1) * (double) quarter - p.getZ()));
                        int step = PreviewPyramid.step(level, distance, Math.sqrt(fx * fx + fz * fz), ring, detail);
                        if (step < 0) {
                            seen.remove(tile | (long) q << 62); // another player's view may need it
                            continue;
                        }
                        double score = PreviewPyramid.stepOrder(step, distance) + (refine ? REFINE_LAG : 0);
                        candidates.add(new long[]{tile, (long) score, distance <= NEAR ? 1 : 0,
                                facing >= FRONT_COS ? 1 : 0, q, refine ? 1 : 0, (long) distance});
                    }
                }
            }
        }
        if (!players.isEmpty()) {
            d.range = farthest;
            d.left = candidates.size();
            trackFill(d, players.get(0), candidates);
        }
        candidates.sort((a, b) -> Long.compare(a[1], b[1]));
        int threads = pool.getCorePoolSize();
        int limit = Math.max(MAX_IN_FLIGHT, 2 * threads);
        // The steps decide (ADR-152): every quarter takes any thread in their order. The coarsest level
        // reaches the border first (what the far front of ADR-139 did), the near ring comes next (what
        // the near disc of ADR-145 and the half threads of ADR-128 were for).
        int fineCap = limit;
        int coarseCap = limit;
        // The biomes' samples first, on part of the coarse share: the canopy of the coarse quarters waits for them.
        int sampleSlots = Math.max(1, threads / 2);
        int[] request;
        while (d.samplesInFlight.get() < sampleSlots && coarseUsed(d) < coarseCap && inFlight(d) < limit
                && (request = d.sampleQueue.poll()) != null) {
            d.samplesInFlight.incrementAndGet();
            int[] r = request;
            pool.execute(() -> runSample(d, r));
        }
        // A task an operator forced while players are online keeps half the queue: the players'
        // tiles alone filled it, and the forced task built nothing (2026-11-09).
        int playerLimit = pregenHere && pregen.forcedIn(d.key.location().toString()) ? limit - Math.max(1, limit / 2)
                : limit;
        for (long[] c : candidates) {
            if (inFlight(d) >= playerLimit) {
                break;
            }
            submit(d, c[0], (int) c[4], fineCap, coarseCap);
        }
        // Then the pregeneration's tiles, its pyramid first, in what the players leave free.
        if (pregenHere && inFlight(d) < limit) {
            for (long tile : pregen.next(d.key.location().toString(), t -> built(d, t), d.inFlight,
                    limit - inFlight(d))) {
                submit(d, tile, -1, fineCap, coarseCap);
            }
        }
    }

    /** Quarters and samples in flight. */
    private static int inFlight(Dimension d) {
        return d.coarseInFlight.get() + d.fineInFlight.get() + d.samplesInFlight.get();
    }

    /** What the coarse share holds in flight: coarse quarters and biome samples. */
    private static int coarseUsed(Dimension d) {
        return d.coarseInFlight.get() + d.samplesInFlight.get();
    }

    /** Submits a quarter of a tile (-1: its next) within its level's share; one quarter of a tile at a time. */
    private void submit(Dimension d, long tile, int q, int fineCap, int coarseCap) {
        boolean fine = PreviewStore.level(tile) == 0;
        if (fine ? d.fineInFlight.get() >= fineCap : coarseUsed(d) >= coarseCap) {
            return;
        }
        if (d.inFlight.add(tile)) {
            (fine ? d.fineInFlight : d.coarseInFlight).incrementAndGet();
            pool.execute(() -> build(d, tile, q));
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
            d.coarseDone = 0;
            d.topDone = 0;
        }
        if (d.fillStart == 0) {
            return;
        }
        boolean near = false;
        boolean front = false;
        boolean coarse = false;
        boolean top = false;
        for (long[] c : candidates) {
            near |= c[2] != 0;
            front |= c[3] != 0;
            coarse |= PreviewStore.level(c[0]) > 0 && c[5] == 0;
            top |= PreviewStore.level(c[0]) == PreviewPyramid.LEVELS - 1;
        }
        d.nearDone = near || d.nearDone != 0 ? d.nearDone : now;
        d.frontDone = front || d.frontDone != 0 ? d.frontDone : now;
        d.coarseDone = coarse || d.coarseDone != 0 ? d.coarseDone : now;
        d.topDone = top || d.topDone != 0 ? d.topDone : now;
        if (candidates.isEmpty()) {
            logFill(d, now, "all built");
        } else if (now - d.fillStart > FILL_GIVE_UP_NANOS) {
            logFill(d, now, candidates.size() + " still missing");
        }
    }

    private static void logFill(Dimension d, long now, String end) {
        if (RenderSettings.performanceLogs()) {
            Farfield.LOG.info("Noise preview fill after {}: {} quarters to build; coarsest level whole at {}, coarse"
                            + " disc whole at {}, within {} blocks done at {}, in front at {}, {} at {} s",
                    d.fillCause, d.fillTiles, seconds(d, d.topDone), seconds(d, d.coarseDone), (int) NEAR, seconds(d, d.nearDone), seconds(d, d.frontDone), end,
                    String.format("%.1f", (now - d.fillStart) / 1e9));
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
        d.saveProfiles();
        int built = d.built.getAndSet(0);
        long nanos = d.builtNanos.getAndSet(0);
        if (built > 0) {
            StringBuilder stages = new StringBuilder();
            for (int i = 0; i < STAGES.length; i++) {
                stages.append(i == 0 ? "" : ", ").append(STAGES[i]).append(' ')
                        .append(d.stageNanos.getAndSet(i, 0) / 1_000_000 / built);
            }
            if (RenderSettings.performanceLogs()) {
                Farfield.LOG.info("Noise preview of {}: {} quarters built in the last minute, {} of them coarse ({} ms"
                        + " each: {}; {} villages, {} features failed, {} left out for their cost, {} light sources,"
                        + " {} dug columns, {} biomes sampled, {} known), {} left within {} blocks",
                        d.key.location(), built, d.coarseBuilt.getAndSet(0), nanos / 1_000_000 / built, stages,
                        d.villagesBuilt.getAndSet(0), d.featureFailures.getAndSet(0), d.budget.excludedCount(),
                        d.lightSources.getAndSet(0), d.dugColumns.getAndSet(0), d.biomesSampled.getAndSet(0),
                        d.profiles().size(), d.left,
                        d.range);
            }
            d.reportedDone = false;
        } else if (d.left == 0 && !d.reportedDone) {
            if (RenderSettings.performanceLogs()) {
                Farfield.LOG.info("Noise preview of {}: every tile within {} blocks is built",
                        d.key.location(), d.range);
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
        // The four quarters on a fresh tile, then the tile written once.
        StringBuilder timing = new StringBuilder();
        FeatureCosts costs = new FeatureCosts();
        PreviewTile tile = new PreviewTile();
        tile.setQuarters(0);
        try {
            for (int q = 0; q < 4; q++) {
                if (buildFine(d, tileX, tileZ, q, tile, costs, System.nanoTime()) < 0) {
                    return null;
                }
                timing.append(q == 0 ? "" : "; ").append("quarter ").append(q).append(": ").append(d.lastFeatureTiming);
            }
            long t = System.nanoTime();
            world.preview().write(0, tileX, tileZ, tile);
            stage(d, WRITE, t);
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("Noise preview of {}: the spike's tile {}, {} failed", d.key.location(), tileX, tileZ, e);
            return null;
        }
        long[] out = new long[STAGES.length + 1];
        for (int i = 0; i < STAGES.length; i++) {
            out[i] = d.stageNanos.getAndSet(i, 0);
        }
        out[STAGES.length] = d.featureFailures.getAndSet(0);
        lastTiming = timing + "; " + costs.report(12) + "; " + d.budget.excludedCount()
                + " features left out for their cost so far (ADR-131)";
        return out;
    }

    /**
     * Spike for E.3a: builds the level-{@code tileLevel} tile over block (x,
     * z) now on the calling thread, on a fresh tile, and stores it; reports
     * its time a quarter by stage, then its cells' top and filler against the
     * level-0 tile stored there (build it first with the tile spike), cell
     * center by cell center, and the guess's against the same. False without
     * a preview there.
     */
    boolean coarseSpike(ServerLevel level, LodWorld world, int tileLevel, int x, int z,
                        java.util.function.Consumer<String> report) throws IOException {
        Optional<Dimension> found = dimensions.computeIfAbsent(level.dimension(), k -> open(level));
        if (found.isEmpty()) {
            return false;
        }
        Dimension d = found.get();
        d.world = world;
        int size = PreviewStore.blocks(tileLevel);
        int tileX = Math.floorDiv(x, size);
        int tileZ = Math.floorDiv(z, size);
        int spacing = PreviewTile.GRID << tileLevel;
        for (int i = 0; i < STAGES.length; i++) {
            d.stageNanos.set(i, 0);
        }
        PreviewTile tile = new PreviewTile();
        tile.setLevel(tileLevel);
        tile.setQuarters(0);
        long start = System.nanoTime();
        for (int q = 0; q < 4; q++) {
            if (buildCoarse(d, tileLevel, tileX, tileZ, q, System.nanoTime(), tile) < 0) {
                return true;
            }
        }
        long nanos = System.nanoTime() - start;
        world.preview().write(tileLevel, tileX, tileZ, tile);
        StringBuilder stages = new StringBuilder();
        for (int i = 0; i < STAGES.length; i++) {
            stages.append(i == 0 ? "" : ", ").append(STAGES[i]).append(' ')
                    .append(d.stageNanos.getAndSet(i, 0) / 4 / 1_000_000);
        }
        report.accept(String.format(java.util.Locale.ROOT, "level %d tile %d, %d (%d blocks, cells of %d): %d ms,"
                + " %d ms a quarter (%s)", tileLevel, tileX, tileZ, size, spacing, nanos / 1_000_000,
                nanos / 4 / 1_000_000, stages));
        int fx = Math.floorDiv(x, TILE);
        int fz = Math.floorDiv(z, TILE);
        PreviewTile fine = world.preview().load(0, fx, fz);
        if (fine == null || (fine.quarters() & 0xF) != 0xF) {
            report.accept("no whole level-0 tile at " + fx + ", " + fz + " to compare with (run the tile spike there)");
            return true;
        }
        Materials m = new Materials(world, d);
        int cells = 0;
        int ruledTop = 0;
        int ruledBoth = 0;
        int guessedTop = 0;
        int guessedBoth = 0;
        StringBuilder examples = new StringBuilder();
        int x0 = tileX * size;
        int z0 = tileZ * size;
        int cliff = CLIFF_RISE * spacing / PreviewTile.GRID;
        for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
            for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                int bx = x0 + cx * spacing + spacing / 2;
                int bz = z0 + cz * spacing + spacing / 2;
                if (Math.floorDiv(bx, TILE) != fx || Math.floorDiv(bz, TILE) != fz) {
                    continue;
                }
                int fcx = (bx - fx * TILE) / PreviewTile.GRID;
                int fcz = (bz - fz * TILE) / PreviewTile.GRID;
                int top = fine.top(fcx, fcz);
                int filler = fine.filler(fcx, fcz);
                cells++;
                boolean sameTop = tile.top(cx, cz) == top;
                ruledTop += sameTop ? 1 : 0;
                ruledBoth += sameTop && tile.filler(cx, cz) == filler ? 1 : 0;
                if (!sameTop && examples.length() < 500) {
                    examples.append(String.format(java.util.Locale.ROOT, " [%d %d: %s, level 0 %s]", bx, bz,
                            blockName(world, tile.top(cx, cz)), blockName(world, top)));
                }
                int h = Math.max(Math.max(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.max(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                int low = Math.min(Math.min(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.min(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                Holder<Biome> biome = d.biomes.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(h),
                        QuartPos.fromBlock(bz), d.columnSampler.get());
                m.surface(biome, bx - spacing / 2, h, bz - spacing / 2, h - low >= cliff);
                guessedTop += m.top == top ? 1 : 0;
                guessedBoth += m.top == top && m.filler == filler ? 1 : 0;
            }
        }
        report.accept(String.format(java.util.Locale.ROOT, "against level 0 at %d cell centers: the rules' top"
                + " matches %.1f %% (top and filler %.1f %%), the guess's %.1f %% (%.1f %%); differing tops:%s",
                cells, 100.0 * ruledTop / Math.max(1, cells), 100.0 * ruledBoth / Math.max(1, cells),
                100.0 * guessedTop / Math.max(1, cells), 100.0 * guessedBoth / Math.max(1, cells), examples));
        return true;
    }

    private static String blockName(LodWorld world, int material) {
        String key = world.blockStates().key(material);
        return key == null ? "?" : key.replace("minecraft:", "");
    }

    /** Blocks between the samples of the grids {@link #heightsSpike} times: tile levels 0, 2, 4, 6, 8, 12 and 15. */
    private static final int[] SPIKE_SPACINGS = {4, 16, 64, 256, 1024, 16384, 131072};
    /** One agreeing sample in this many is checked against the generator's own height. */
    private static final int SPIKE_EXACT_EVERY = 64;

    /**
     * Spike for the coarse-to-fine study
     * ({@code docs/analysis/preview-coarse-to-fine.md}) and the border study
     * ({@code docs/analysis/preview-to-the-border.md}): the heights of one
     * tile's grid ({@link PreviewTile#SAMPLES} a side) centered on a block,
     * at each of {@link #SPIKE_SPACINGS}, by three searches: from the
     * neighbor's height as {@link #build} does, from the top of the world, and
     * each column on its own from vanilla's preliminary surface on the density
     * without caves ({@link #surfaceFromEstimate}). One line of times and one
     * of errors per spacing, against the generator's own height
     * ({@code getBaseHeight}, exact and slow) on the samples where the searches
     * differ and on one sample in {@link #SPIKE_EXACT_EVERY} of the others.
     * False when the dimension has no preview.
     */
    boolean heightsSpike(ServerLevel level, int x, int z, java.util.function.Consumer<String> report) {
        Optional<Dimension> found = dimensions.computeIfAbsent(level.dimension(), k -> open(level));
        if (found.isEmpty()) {
            return false;
        }
        Dimension d = found.get();
        report.accept("cave branches cut from the density: " + d.cutBranches
                + (d.cutBranches == 0 ? " (none found: the estimate's search runs on the full density)" : "")
                + "; " + UncutDensity.seen);
        int n = PreviewTile.SAMPLES;
        int[][] grids = new int[SPIKE_SEARCHES.length][n * n];
        // Once unmeasured: the first grid would pay for the compiler.
        for (int s = 0; s < SPIKE_SEARCHES.length; s++) {
            heightsGrid(d, x, z, PreviewTile.GRID, s, grids[s]);
        }
        for (int spacing : SPIKE_SPACINGS) {
            if (stopped) {
                return true;
            }
            long[] nanos = new long[SPIKE_SEARCHES.length];
            for (int s = 0; s < SPIKE_SEARCHES.length; s++) {
                long t0 = System.nanoTime();
                heightsGrid(d, x, z, spacing, s, grids[s]);
                nanos[s] = System.nanoTime() - t0;
            }
            int x0 = x - spacing * (n / 2);
            int z0 = z - spacing * (n / 2);
            int checked = 0;
            int shown = 0;
            int differ = 0;
            long[] error = new long[SPIKE_SEARCHES.length];
            int[] off = new int[SPIKE_SEARCHES.length];
            int[] worst = new int[SPIKE_SEARCHES.length];
            for (int i = 0; i < n * n && d.generator != null; i++) {
                boolean differs = false;
                for (int s = 1; s < SPIKE_SEARCHES.length; s++) {
                    differs |= grids[s][i] != grids[0][i];
                }
                differ += differs ? 1 : 0;
                if (!differs && i % SPIKE_EXACT_EVERY != 0) {
                    continue;
                }
                int exact = d.generator.getBaseHeight(x0 + i % n * spacing, z0 + i / n * spacing,
                        net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level,
                        level.getChunkSource().randomState());
                checked++;
                if (Math.abs(grids[2][i] - exact) > SPIKE_OFF && shown < 6 && spacing == SPIKE_SPACINGS[0]) {
                    shown++;
                    int bx = x0 + i % n * spacing;
                    int bz = z0 + i / n * spacing;
                    int e = preliminary(d, bx, bz, UNKNOWN);
                    int raw = surfaceFromEstimate(d, d.columnUncut.get(), false, bx, bz, e);
                    int full = surfaceFromEstimate(d, d.columnDensity.get(), false, bx, bz, e);
                    report.accept(String.format(java.util.Locale.ROOT, "off at %d %d: exact %d, neighbor %d, top %d,"
                                    + " estimate %d -> uncut %d (checked %d), full from the estimate %d", bx, bz, exact,
                            grids[0][i], grids[1][i], e, raw, grids[2][i], full));
                }
                for (int s = 0; s < SPIKE_SEARCHES.length; s++) {
                    int e = Math.abs(grids[s][i] - exact);
                    error[s] += e;
                    off[s] += e > SPIKE_OFF ? 1 : 0;
                    worst[s] = Math.max(worst[s], e);
                }
            }
            StringBuilder times = new StringBuilder();
            StringBuilder errors = new StringBuilder();
            for (int s = 0; s < SPIKE_SEARCHES.length; s++) {
                times.append(String.format(java.util.Locale.ROOT, "%s%s %d ms (%.1f us a sample)", s == 0 ? "" : ", ",
                        SPIKE_SEARCHES[s], nanos[s] / 1_000_000, nanos[s] / 1e3 / (n * n)));
                errors.append(String.format(java.util.Locale.ROOT, "%s%s mean %.2f, %d off by more than %d, worst %d",
                        s == 0 ? "" : "; ", SPIKE_SEARCHES[s], error[s] / (double) Math.max(1, checked), off[s],
                        SPIKE_OFF, worst[s]));
            }
            report.accept(String.format(java.util.Locale.ROOT, "samples every %d blocks (tile level %d): %s;"
                            + " %d of %d samples differ between the searches", spacing,
                    Integer.numberOfTrailingZeros(spacing / PreviewTile.GRID), times, differ, n * n));
            report.accept(String.format(java.util.Locale.ROOT, "samples every %d blocks, against the generator"
                    + " (%d samples checked): %s", spacing, checked, errors));
        }
        return true;
    }

    /** The searches {@link #heightsSpike} compares, in {@link #heightsGrid}'s order. */
    private static final String[] SPIKE_SEARCHES = {"from the neighbor", "from the top", "from the estimate"};
    /** Blocks from the generator's height past which the spike counts a sample as wrong. */
    private static final int SPIKE_OFF = 8;

    /**
     * The surface of a grid of {@link PreviewTile#SAMPLES} samples a side
     * centered on (x, z), row by row into {@code out}, by search {@code s} of
     * {@link #SPIKE_SEARCHES}.
     */
    private static void heightsGrid(Dimension d, int x, int z, int spacing, int s, int[] out) {
        int n = PreviewTile.SAMPLES;
        int x0 = x - spacing * (n / 2);
        int z0 = z - spacing * (n / 2);
        int previous = UNKNOWN;
        int rowEstimate = UNKNOWN;
        int estimate = UNKNOWN;
        for (int k = 0; k < n; k++) {
            int seed = k == 0 ? UNKNOWN : out[(k - 1) * n];
            for (int i = 0; i < n; i++) {
                int bx = x0 + i * spacing;
                int bz = z0 + k * spacing;
                int h;
                if (s == 2) {
                    // The estimate is smooth and has no caves: searched from the previous one.
                    estimate = preliminary(d, bx, bz, i == 0 ? rowEstimate : estimate);
                    if (i == 0) {
                        rowEstimate = estimate;
                    }
                    h = surfaceFromEstimate(d, d.columnUncut.get(), true, bx, bz, estimate);
                } else {
                    h = surface(d, bx, bz, s == 1 ? UNKNOWN : i == 0 ? seed : previous);
                }
                out[k * n + i] = h;
                previous = h;
            }
        }
    }

    /** Vanilla's threshold on the density without jaggedness for the preliminary surface ({@code NoiseChunk}). */
    private static final double PRELIMINARY_THRESHOLD = 0.390625;
    /** Blocks above a found surface checked for more ground ({@link #surfaceFromEstimate}). */
    private static final int[] SURFACE_PROBES = {4, 8, 16, 24, 32};
    /** Cells below the estimate the search from it starts at. */
    private static final int ESTIMATE_MARGIN_CELLS = 2;

    /**
     * Vanilla's preliminary surface level at (x, z) ({@code
     * NoiseChunk.computePreliminarySurfaceLevel}): the highest y of the cell
     * grid from the top of the world where the density without jaggedness
     * exceeds {@link #PRELIMINARY_THRESHOLD}. From {@code hint} (a near
     * column's result) up or down, since that density has no caves; from the
     * top without one. {@link #NO_ESTIMATE} when nothing exceeds it (vanilla
     * has none then): the search for the surface starts from the top, and
     * the ground there floats (ADR-159). The End's density without
     * jaggedness (its islands less 0.703125) never exceeds it; from the
     * bottom of the world, every End column came out empty (ADR-158).
     */
    private static int preliminary(Dimension d, int x, int z, int hint) {
        DensityFunction f = d.columnInitial.get();
        int minY = d.noise.minY();
        int step = d.noise.getCellHeight();
        int top = minY + d.noise.height();
        if (hint != UNKNOWN && hint >= minY && hint <= top) {
            int y = hint;
            if (above(f, x, y, z)) {
                while (y + step <= top && above(f, x, y + step, z)) {
                    y += step;
                }
                return y;
            }
            for (y -= step; y >= minY; y -= step) {
                if (above(f, x, y, z)) {
                    return y;
                }
            }
            return NO_ESTIMATE;
        }
        for (int y = top; y >= minY; y -= step) {
            if (above(f, x, y, z)) {
                return y;
            }
        }
        return NO_ESTIMATE;
    }

    /** {@link #preliminary} where nothing exceeds the threshold, as vanilla's own. */
    private static final int NO_ESTIMATE = Integer.MAX_VALUE;

    /**
     * The lowest solid block of the ground under {@code surface} (its first
     * free block) at (x, z), on the density without caves: the underside of
     * floating ground (ADR-159). {@code surface} itself where there is no
     * ground.
     */
    private static int underside(Dimension d, int x, int z, int surface) {
        int minY = d.noise.minY();
        if (surface <= minY) {
            return surface;
        }
        DensityFunction density = d.columnUncut.get();
        int step = d.noise.getCellHeight();
        int solid = surface - 1;
        while (solid - step >= minY && solid(density, x, solid - step, z)) {
            solid -= step;
        }
        int free = solid - step; // below the world counts as free
        while (solid - free > 1) {
            int mid = (solid + free) >>> 1;
            if (mid >= minY && solid(density, x, mid, z)) {
                solid = mid;
            } else {
                free = mid;
            }
        }
        return solid;
    }

    private static boolean above(DensityFunction f, int x, int y, int z) {
        return f.compute(new DensityFunction.SinglePointContext(x, y, z)) > PRELIMINARY_THRESHOLD;
    }

    /**
     * The first free block above the ground at (x, z), searched on its own on
     * {@code density} (the density without caves, ADR-133): from
     * {@code estimate} lowered by {@link #ESTIMATE_MARGIN_CELLS} cells (vanilla's
     * preliminary surface lies below the real one, ADR-037), down while free
     * to the first solid cell, then {@link #climb}. With {@code check}, the
     * full density at the result: where it is free there (a cave opening at
     * the surface) the search goes on down on the full density, then climbs
     * on it again, below the first result.
     */
    private static int surfaceFromEstimate(Dimension d, DensityFunction density, boolean check, int x, int z,
                                           int estimate) {
        int minY = d.noise.minY();
        int step = d.noise.getCellHeight();
        int top = minY + d.noise.height() - 1;
        if (estimate == NO_ESTIMATE) {
            estimate = top + 1; // the search from the top (ADR-158)
        }
        int lo = Math.min(top, Math.max(minY, minY + Math.floorDiv(estimate - minY, step) * step
                - ESTIMATE_MARGIN_CELLS * step));
        while (lo >= minY && !solid(density, x, lo, z)) {
            lo -= step;
        }
        int h = lo < minY ? minY : climb(density, x, z, lo, step, top);
        if (check && h > minY && h <= top && !d.solid(x, h - 1, z)) {
            DensityFunction full = d.columnDensity.get();
            int ceiling = h - 1;
            int down = ceiling - step;
            while (down >= minY && !solid(full, x, down, z)) {
                down -= step;
            }
            h = down < minY ? minY : climb(full, x, z, down, step, ceiling);
        }
        return h;
    }

    /**
     * From {@code lo}, solid, up by {@code step} while solid, bisected to the
     * first free block; then, where one of {@link #SURFACE_PROBES} above it is
     * solid (a ledge over a gap, a noodle cave under the surface), on from
     * there. Never above {@code top}.
     */
    private static int climb(DensityFunction density, int x, int z, int lo, int step, int top) {
        while (true) {
            int hi = lo + step;
            while (hi <= top && solid(density, x, hi, z)) {
                lo = hi;
                hi += step;
            }
            int h = hi > top ? bisect(density, x, z, lo, top + 1) : bisect(density, x, z, lo, hi);
            int next = Integer.MIN_VALUE;
            for (int probe : SURFACE_PROBES) {
                if (h + probe <= top && solid(density, x, h + probe, z)) {
                    next = h + probe;
                    break;
                }
            }
            if (next == Integer.MIN_VALUE) {
                return h;
            }
            lo = next;
        }
    }

    private static boolean solid(DensityFunction f, int x, int y, int z) {
        return f.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0;
    }

    /** {@link #bisect(Dimension, int, int, int, int)} on another density. */
    private static int bisect(DensityFunction f, int x, int z, int lo, int hi) {
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (solid(f, x, mid, z)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo + 1;
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

    /** Builds and stores a quarter of a tile (by its key, any level), the next its tile needs when {@code q} is -1. */
    private void build(Dimension d, long key, int q) {
        int level = PreviewStore.level(key);
        int tileX = PreviewStore.tileX(key);
        int tileZ = PreviewStore.tileZ(key);
        long start = System.nanoTime();
        try {
            int quarter = q >= 0 ? q : nextQuarter(d, key);
            if (quarter < 0) {
                return;
            }
            int quarters = level == 0 ? buildFine(d, tileX, tileZ, quarter, null, null, start)
                    : buildCoarse(d, level, tileX, tileZ, quarter, start);
            if (quarters >= 0) {
                d.done.put(key, quarters);
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
            (level == 0 ? d.fineInFlight : d.coarseInFlight).decrementAndGet();
        }
    }

    /** The first quarter of a tile to build: not built, or coarse and incomplete with its biomes measured now; -1 for none. */
    private static int nextQuarter(Dimension d, long key) {
        int quarters = quarters(d, key);
        for (int q = 0; q < 4; q++) {
            if ((quarters >> q & 1) == 0 || (quarters >> (q + 4) & 1) == 0 && ready(d, key, q)) {
                return q;
            }
        }
        return -1;
    }

    /** Whether an incomplete coarse quarter may be built again: none of its biomes waits for a sample. */
    private static boolean ready(Dimension d, long key, int q) {
        int[] biomes = d.pending.get(key | (long) q << 62);
        if (biomes == null) {
            return true;
        }
        for (int b : biomes) {
            if (d.sampling.contains(b)) {
                return false;
            }
        }
        return true;
    }

    /** Whether sample (i, k) lies in a built quarter of {@code built} (bits 0 to 3): it is kept. */
    private static boolean kept(int built, int i, int k) {
        int half = PreviewTile.QUARTER_SAMPLES;
        for (int qz = k < half ? 0 : 1; qz <= (k <= half ? 0 : 1); qz++) {
            for (int qx = i < half ? 0 : 1; qx <= (i <= half ? 0 : 1); qx++) {
                if ((built >> (qz * 2 + qx) & 1) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether cell (cx, cz) lies in a built quarter of {@code built}. */
    private static boolean cellKept(int built, int cx, int cz) {
        int half = PreviewTile.QUARTER_SAMPLES;
        return (built >> ((cz < half ? 0 : 2) + (cx < half ? 0 : 1)) & 1) != 0;
    }

    /**
     * The surface of a tile's samples [i0, i1] x [k0, k1], {@code spacing}
     * blocks apart from its corner (x0, z0), each column searched on its own
     * from vanilla's preliminary surface ({@link #surfaceAt}, ADR-133); the
     * samples of the quarters in {@code built} are kept. False when the
     * preview stopped meanwhile.
     */
    private boolean heights(Dimension d, PreviewTile tile, int x0, int z0, int spacing, int i0, int i1, int k0,
                            int k1, int built) {
        int rowEstimate = UNKNOWN;
        for (int k = k0; k <= k1; k++) {
            // The estimate, smooth and without caves, is searched from a near one: the row's first from the
            // row above's, the others from the previous sample's.
            int estimate = rowEstimate;
            for (int i = i0; i <= i1; i++) {
                if (stopped) {
                    return false;
                }
                if (kept(built, i, k)) {
                    continue;
                }
                int x = x0 + i * spacing;
                int z = z0 + k * spacing;
                estimate = preliminary(d, x, z, estimate);
                if (i == i0 || rowEstimate == UNKNOWN) {
                    rowEstimate = estimate;
                }
                int h = surfaceFromEstimate(d, d.columnUncut.get(), true, x, z, estimate);
                tile.setHeight(i, k, h);
                // Without vanilla's estimate the ground floats (the End): its underside too (ADR-159).
                tile.setUnderside(i, k, estimate == NO_ESTIMATE ? underside(d, x, z, h) : PreviewTile.SOLID_DOWN);
            }
        }
        return true;
    }

    /** The surface at (x, z) by {@link #surfaceFromEstimate}, with the estimate searched from the top. */
    private static int surfaceAt(Dimension d, int x, int z) {
        return surfaceFromEstimate(d, d.columnUncut.get(), true, x, z, preliminary(d, x, z, UNKNOWN));
    }

    /**
     * Sets a tile's cells [c0x, c1x) x [c0z, c1z] (those of built quarters
     * kept) from its samples and biomes: the top and filler blocks (from the
     * surface rules when {@code rules} is given, else the biome's measured
     * ground when {@code profiles} is given and knows it, else guessed), the
     * icebergs, the frozen sea; then the tile's ground, sea and ice. Returns
     * the cells icebergs took.
     */
    private static boolean[] cells(Dimension d, PreviewTile tile, int x0, int z0, int spacing, Materials m,
                                   Holder<Biome>[] chunkBiomes, boolean[] icebergs, Holder<Biome>[] seaBiomes,
                                   PreviewProfiles profiles, CoarseGround rules, int c0x, int c1x, int c0z, int c1z,
                                   int built) {
        boolean[] icebergCells = new boolean[PreviewTile.CELLS * PreviewTile.CELLS];
        int cliff = CLIFF_RISE * spacing / PreviewTile.GRID;
        for (int cz = c0z; cz < c1z; cz++) {
            for (int cx = c0x; cx < c1x; cx++) {
                if (cellKept(built, cx, cz)) {
                    continue;
                }
                int h = Math.max(Math.max(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.max(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                int low = Math.min(Math.min(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                        Math.min(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                Holder<Biome> biome = chunkBiomes[(cz >> 2) * PreviewTile.CHUNKS + (cx >> 2)];
                int x = x0 + cx * spacing;
                int z = z0 + cz * spacing;
                boolean steep = h - low >= cliff;
                BlockState[] ruled = rules == null ? null : rules.cell(cx, cz, h, low);
                long measured = ruled != null ? (long) m.cachedId(ruled[0]) << 32 | (m.cachedId(ruled[1]) & 0xFFFFFFFFL)
                        : profiles == null ? -1 : profiles.ground(tile.biome(cx >> 2, cz >> 2),
                        d.fluid != null && h <= d.seaLevel ? PreviewProfiles.UNDER_SEA
                                : steep ? PreviewProfiles.STEEP : PreviewProfiles.FLAT);
                if (measured >= 0) {
                    int top = (int) (measured >>> 32);
                    int filler = (int) measured;
                    // Snow over the rules' top on cold land, as a level-0 tile's cells have it.
                    if (ruled != null ? rules.snow() : h > d.seaLevel && d.fluid != null
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
     * Builds a quarter of a level-0 tile with the world's own features
     * (unless the settings turn them off) and stores the tile (ADR-127): its
     * samples, cells and chunks, and those a chunk around inside the tile,
     * where the features spill over; the quarters built before kept. On
     * {@code base} (a fresh tile) instead of the stored one when given.
     * Returns the tile's quarters, or -1 when the preview stopped meanwhile.
     */
    private int buildFine(Dimension d, int tileX, int tileZ, int q, PreviewTile base, FeatureCosts costs, long start)
            throws IOException {
        LodWorld world = d.world;
        d.profiles(); // read with the features the cost guard left out, before any feature runs
        PreviewTile tile = base != null ? base : world.preview().load(0, tileX, tileZ);
        if (tile == null) {
            tile = new PreviewTile();
            tile.setQuarters(0);
        }
        int built = tile.quarters() & 0xF & ~(1 << q);
        int half = PreviewTile.QUARTER_SAMPLES;
        int qx = q & 1;
        int qz = q >> 1;
        int i0 = Math.max(0, qx * half - 4);
        int i1 = Math.min(PreviewTile.SAMPLES - 1, qx * half + half + 4);
        int k0 = Math.max(0, qz * half - 4);
        int k1 = Math.min(PreviewTile.SAMPLES - 1, qz * half + half + 4);
        int x0 = tileX * TILE;
        int z0 = tileZ * TILE;
        if (!heights(d, tile, x0, z0, PreviewTile.GRID, i0, i1, k0, k1, built)) {
            return -1;
        }
        // Biomes at the sea's top by quart, shared by the icebergs (samples) and the frozen cells.
        @SuppressWarnings("unchecked")
        Holder<Biome>[] seaBiomes = new Holder[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        boolean[] icebergs = icebergs(d, tile, x0, z0, PreviewTile.GRID, seaBiomes, i0, i1, k0, k1, built);
        long t = stage(d, HEIGHTS, start);
        Materials m = new Materials(world, d);
        Holder<Biome>[] chunkBiomes = biomes(d, tile, x0, z0, 0, world, i0 / 4, (i1 + 3) / 4, k0 / 4, (k1 + 3) / 4);
        t = stage(d, BIOMES, t);
        boolean[] icebergCells = cells(d, tile, x0, z0, PreviewTile.GRID, m, chunkBiomes, icebergs, seaBiomes, null,
                null, i0, i1, k0, k1, built);
        t = stage(d, CELLS, t);
        if (stopped) {
            return -1;
        }
        int bx0 = x0 + qx * TILE / 2;
        int bz0 = z0 + qz * TILE / 2;
        List<PreviewVillages.Village> villages = villages(d, tile, x0, z0, m, bx0, bz0, bx0 + TILE / 2,
                bz0 + TILE / 2, x0 + i0 * PreviewTile.GRID, z0 + k0 * PreviewTile.GRID, x0 + i1 * PreviewTile.GRID,
                z0 + k1 * PreviewTile.GRID);
        t = stage(d, VILLAGES, t);
        io.lakio.farfield.core.config.ServerConfig settings = StreamServer.settings();
        PreviewFeatures features = new PreviewFeatures(d.level, d.biomes, d.columnSampler.get(), d.fluid, d.seaLevel,
                d.world, settings, tile, tileX, tileZ, (x, z) -> inVillage(villages, x, z), icebergCells);
        features.budget(d.budget);
        if (costs != null) {
            features.measure(costs);
            features.compareGround();
        }
        int lx0 = qx * PreviewTile.CHUNKS / 2;
        int lz0 = qz * PreviewTile.CHUNKS / 2;
        features.groundWindow(lx0, lz0, PreviewTile.CHUNKS / 2);
        t = stage(d, GROUND, t);
        boolean grow = settings.previewFeatures();
        if (grow) {
            features.decorateWindow(lx0, lz0, PreviewTile.CHUNKS / 2);
            d.featureFailures.addAndGet(features.failures());
            t = stage(d, FEATURES, t);
        }
        d.lastFeatureTiming = features.timing();
        tile.setQuarter(q, true);
        if (stopped) {
            return -1; // the stores close next
        }
        if (base == null) {
            world.preview().write(0, tileX, tileZ, tile, 1 << q);
        }
        stage(d, WRITE, t);
        d.lightSources.addAndGet(tile.lights().length / 2);
        d.dugColumns.addAndGet(tile.pits().length / PreviewTile.PIT_INTS);
        if (grow) {
            d.profiles().record(tile, lx0, lz0, PreviewTile.CHUNKS / 2);
        }
        return tile.quarters();
    }

    /**
     * Builds a quarter of a coarse tile of the pyramid and stores the tile
     * (ADR-126, ADR-127): its samples {@code 4 << level} blocks apart, then
     * the ground, plants and canopy from what the features grow in each
     * biome. A biome not measured yet goes to the samples' queue and the
     * quarter stays incomplete, built again once it is measured. Returns the
     * tile's quarters, or -1 when the preview stopped meanwhile.
     */
    private int buildCoarse(Dimension d, int level, int tileX, int tileZ, int q, long start) throws IOException {
        return buildCoarse(d, level, tileX, tileZ, q, start, null);
    }

    /** Like {@link #buildCoarse(Dimension, int, int, int, int, long)}, on {@code base} (unstored) when given. */
    private int buildCoarse(Dimension d, int level, int tileX, int tileZ, int q, long start, PreviewTile base)
            throws IOException {
        LodWorld world = d.world;
        PreviewTile tile = base != null ? base : world.preview().load(level, tileX, tileZ);
        if (tile == null) {
            tile = new PreviewTile();
            tile.setLevel(level);
            tile.setQuarters(0);
        }
        int built = tile.quarters() & 0xF & ~(1 << q);
        int half = PreviewTile.QUARTER_SAMPLES;
        int i0 = (q & 1) * half;
        int k0 = (q >> 1) * half;
        int spacing = PreviewTile.GRID << level;
        int x0 = tileX * PreviewStore.blocks(level);
        int z0 = tileZ * PreviewStore.blocks(level);
        if (!heights(d, tile, x0, z0, spacing, i0, i0 + half, k0, k0 + half, built)) {
            return -1;
        }
        @SuppressWarnings("unchecked")
        Holder<Biome>[] seaBiomes = new Holder[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        boolean[] icebergs = icebergs(d, tile, x0, z0, spacing, seaBiomes, i0, i0 + half, k0, k0 + half, built);
        long t = stage(d, HEIGHTS, start);
        Materials m = new Materials(world, d);
        int ch0x = i0 / 4;
        int ch0z = k0 / 4;
        int chunks = PreviewTile.CHUNKS / 2;
        Holder<Biome>[] chunkBiomes = biomes(d, tile, x0, z0, level, world, ch0x, ch0x + chunks, ch0z, ch0z + chunks);
        t = stage(d, BIOMES, t);
        // From GROUND_ONLY up (E.3a): the surface rules' ground, no plants, canopy or biome sample.
        boolean groundOnly = level >= PreviewPyramid.GROUND_ONLY;
        boolean grow = !groundOnly && StreamServer.settings().previewFeatures();
        int[] waiting = grow ? queueSamples(d, tile, x0, z0, level, ch0x, ch0z, chunks) : new int[0];
        CoarseGround rules = groundOnly ? CoarseGround.create(d.level, d.generator, d.biomes, d.columnSampler.get(),
                d.fluid, d.seaLevel, tile, x0, z0, spacing) : null;
        boolean[] icebergCells = cells(d, tile, x0, z0, spacing, m, chunkBiomes, icebergs, seaBiomes,
                groundOnly ? null : d.profiles(), rules, i0, i0 + half, k0, k0 + half, built);
        if (grow) {
            profileCover(d, tile, x0, z0, m, icebergCells, ch0x, ch0z, chunks);
        }
        t = stage(d, CELLS, t);
        tile.setQuarter(q, waiting.length == 0);
        long pendingKey = PreviewStore.key(level, tileX, tileZ) | (long) q << 62;
        if (waiting.length == 0) {
            d.pending.remove(pendingKey);
        } else {
            d.pending.put(pendingKey, waiting);
        }
        if (stopped) {
            return -1;
        }
        if (base == null) {
            world.preview().write(level, tileX, tileZ, tile, 1 << q);
        }
        stage(d, WRITE, t);
        return tile.quarters();
    }

    /**
     * Queues a sample of each biome of a coarse quarter's chunks its profiles
     * do not know yet (once or twice a biome, where its first coarse chunk
     * lies); returns the biomes still waiting for one.
     */
    private static int[] queueSamples(Dimension d, PreviewTile tile, int x0, int z0, int level, int ch0x, int ch0z,
                                      int chunks) {
        PreviewProfiles profiles = d.profiles();
        Set<Integer> seen = new HashSet<>();
        List<Integer> waiting = new ArrayList<>();
        for (int cz = ch0z; cz < ch0z + chunks; cz++) {
            for (int cx = ch0x; cx < ch0x + chunks; cx++) {
                int id = tile.biome(cx, cz);
                if (id < 0 || !seen.add(id) || profiles.known(id)) {
                    continue;
                }
                if (!d.sampling.contains(id) && profiles.takeSample(id)) {
                    d.sampling.add(id);
                    d.sampleQueue.add(new int[]{id, x0 + ((cx * 16 + 8) << level), z0 + ((cz * 16 + 8) << level)});
                }
                if (d.sampling.contains(id)) {
                    waiting.add(id);
                }
            }
        }
        return waiting.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Runs one queued biome sample ({@link #sample}); then the biome waits no more. */
    private void runSample(Dimension d, int[] request) {
        try {
            sample(d, request[1], request[2], new Materials(d.world, d));
            d.biomesSampled.incrementAndGet();
        } catch (RuntimeException e) {
            if (!stopped) {
                Farfield.LOG.warn("Noise preview of {}: a biome's sample at {}, {} failed", d.key.location(),
                        request[1], request[2], e);
            }
        } finally {
            d.sampling.remove(request[0]);
            d.samplesInFlight.decrementAndGet();
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
        if (!heights(d, tile, x0, z0, PreviewTile.GRID, i0, i1, k0, k1, 0)) {
            return;
        }
        // Outside the window, the window's edge: a stand-in the features reach past it stands on that.
        for (int k = 0; k < PreviewTile.SAMPLES; k++) {
            for (int i = 0; i < PreviewTile.SAMPLES; i++) {
                if (i < i0 || i > i1 || k < k0 || k > k1) {
                    tile.setHeight(i, k, tile.sample(Math.max(i0, Math.min(i1, i)), Math.max(k0, Math.min(k1, k))));
                }
            }
        }
        Holder<Biome>[] chunkBiomes = biomes(d, tile, x0, z0, 0, d.world, lx, lx + window, lz, lz + window);
        Holder<Biome> fallback = chunkBiomes[lz * PreviewTile.CHUNKS + lx];
        for (int c = 0; c < chunkBiomes.length; c++) {
            if (chunkBiomes[c] == null) {
                chunkBiomes[c] = fallback;
                tile.setBiome(c % PreviewTile.CHUNKS, c / PreviewTile.CHUNKS, -1);
            }
        }
        @SuppressWarnings("unchecked")
        Holder<Biome>[] seaBiomes = new Holder[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        cells(d, tile, x0, z0, PreviewTile.GRID, m, chunkBiomes, new boolean[PreviewTile.SAMPLES * PreviewTile.SAMPLES],
                seaBiomes, null, null, i0, i1, k0, k1, 0);
        PreviewFeatures features = new PreviewFeatures(d.level, d.biomes, d.columnSampler.get(), d.fluid, d.seaLevel,
                d.world, StreamServer.settings(), tile, tileX, tileZ, (x, z) -> false, null);
        features.budget(d.budget);
        features.decorateWindow(lx + 1, lz + 1, SAMPLE_CHUNKS);
        d.featureFailures.addAndGet(features.failures());
        d.profiles().record(tile, lx + 1, lz + 1, SAMPLE_CHUNKS);
    }

    /**
     * Lays the plants and canopy the profiles give each biome over the
     * {@code chunks} x {@code chunks} chunks of a tile of any level from
     * chunk (ch0x, ch0z) and their cells (ADR-126): a chunk's plants are its
     * biome's mean; a cell on land takes its biome's canopy where a hash of
     * its place falls under the biome's cover, so the share of the ground
     * under the canopy holds, and its snow where another hash falls under
     * the snowy share.
     */
    private static void profileCover(Dimension d, PreviewTile tile, int x0, int z0, Materials m,
                                     boolean[] icebergCells, int ch0x, int ch0z, int chunks) {
        PreviewProfiles profiles = d.profiles();
        Map<Integer, PreviewProfiles.Canopy> canopies = new java.util.HashMap<>();
        for (int cz = ch0z; cz < ch0z + chunks; cz++) {
            for (int cx = ch0x; cx < ch0x + chunks; cx++) {
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
        for (int cz = ch0z * 4; cz < (ch0z + chunks) * 4; cz++) {
            for (int cx = ch0x * 4; cx < (ch0x + chunks) * 4; cx++) {
                tile.setCanopy(cx, cz, 0);
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
     * Raises the samples [i0, i1] x [k0, k1] ({@code spacing} blocks apart;
     * those of built quarters kept) under the sea of a frozen ocean to the
     * icebergs vanilla's surface rule builds there; returns which samples
     * rose, {@code k * SAMPLES + i}.
     */
    private static boolean[] icebergs(Dimension d, PreviewTile tile, int x0, int z0, int spacing,
                                      Holder<Biome>[] seaBiomes, int i0, int i1, int k0, int k1, int built) {
        boolean[] raised = new boolean[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        if (d.fluid == null || !d.fluid.is(Blocks.WATER)) {
            return raised;
        }
        for (int k = k0; k <= k1; k++) {
            for (int i = i0; i <= i1; i++) {
                if (tile.sample(i, k) > d.seaLevel || kept(built, i, k)) {
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
     * Adds the houses of the villages near a tile whose corner lies in the
     * blocks [bx0, bx1) x [bz0, bz1) (a quarter); returns the villages near
     * it (no trees grow in them). The tile's heights stand for the ground
     * within [hx0, hx1] x [hz0, hz1], the ones just computed.
     */
    private static List<PreviewVillages.Village> villages(Dimension d, PreviewTile tile, int x0, int z0, Materials m,
                                                          int bx0, int bz0, int bx1, int bz1, int hx0, int hz0,
                                                          int hx1, int hz1) {
        int reach = PreviewVillages.RADIUS + 16;
        IntBinaryOperator surface = (x, z) -> x >= hx0 && z >= hz0 && x < hx1 && z < hz1
                ? tile.height(x - x0, z - z0) : surfaceAt(d, x, z);
        List<PreviewVillages.Village> villages = d.villages.within(bx0 - reach, bz0 - reach, bx1 + reach,
                bz1 + reach, (x, z) -> d.biomes.getNoiseBiome(QuartPos.fromBlock(x),
                        QuartPos.fromBlock(surface.applyAsInt(x, z)), QuartPos.fromBlock(z), d.columnSampler.get()));
        for (PreviewVillages.Village v : villages) {
            if (v.x() >= bx0 && v.z() >= bz0 && v.x() < bx1 && v.z() < bz1) {
                d.villagesBuilt.incrementAndGet();
            }
            int[] materials = m.village(v.style());
            for (PreviewVillages.House h : PreviewVillages.houses(v, d.seed, d.seaLevel, surface)) {
                if (h.x() >= bx0 && h.z() >= bz0 && h.x() < bx1 && h.z() < bz1) {
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

    /**
     * The biome at the surface of the center of chunks [cx0, cx1) x [cz0,
     * cz1) (chunks {@code 16 << level} blocks wide), stored in the tile and
     * returned; null for the other chunks.
     */
    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] biomes(Dimension d, PreviewTile tile, int x0, int z0, int level, LodWorld world,
                                          int cx0, int cx1, int cz0, int cz1) {
        Holder<Biome>[] out = new Holder[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
        for (int cz = Math.max(0, cz0); cz < Math.min(PreviewTile.CHUNKS, cz1); cz++) {
            for (int cx = Math.max(0, cx0); cx < Math.min(PreviewTile.CHUNKS, cx1); cx++) {
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
     * The tiles' search until ADR-133, which replaced it (it falls into
     * caves); kept as the heights spike's reference.
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
        for (Optional<Dimension> d : dimensions.values()) {
            d.ifPresent(Dimension::saveProfiles);
        }
    }
}
