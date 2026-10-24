package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
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
 * in front first, on low-priority threads (the preview threads setting). A tile holds the surface every 4
 * blocks from the noise router's final density (each search starts from
 * the neighbor sample's height, then bisects to the block), the biome per
 * chunk and the surface blocks per 4-block cell, guessed from the biome and
 * the slope, and whether the sea freezes over it (vanilla's rule); the houses of the villages to come ({@link PreviewVillages});
 * then the biome's trees and plants at their average density
 * ({@link PreviewTrees}), at random but always the same places (seeded by
 * the world and the chunk), on the ground each needs, above the sea and
 * away from villages. The client turns tiles into sections of every level.
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
    private static final double RANGE = 4096;
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
    private final PreviewTrees trees = new PreviewTrees();
    /** Most trees placed in one chunk, whatever the biome says. */
    private static final int MAX_TREES_PER_CHUNK = 48;
    private volatile boolean stopped;
    private long ticks;

    /** What one dimension's preview needs, and its progress. */
    private static final class Dimension {
        final ResourceKey<Level> key;
        final BiomeSource biomes;
        final Climate.Sampler sampler;
        final DensityFunction density;
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
        volatile LodWorld world;
        /** Tiles stored or built, with the grid of their heights. */
        final Map<Long, Integer> done = new ConcurrentHashMap<>();
        final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
        final AtomicInteger built = new AtomicInteger();
        final AtomicLong builtNanos = new AtomicLong();
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
            RandomState state = level.getChunkSource().randomState();
            biomes = generator.getBiomeSource();
            sampler = state.sampler();
            density = state.router().finalDensity();
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
            return density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0;
        }
    }

    void tick(MinecraftServer server, LodPipeline pipeline) {
        if (++ticks % UPDATE_TICKS != 0 || stopped || !RenderSettings.noisePreview()) {
            return;
        }
        RenderSettings.resize(pool, RenderSettings.effectiveThreads(RenderSettings.previewThreads()), "Noise preview");
        boolean report = ticks % REPORT_TICKS == 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            Optional<Dimension> d = dimensions.computeIfAbsent(level.dimension(), k -> open(level));
            LodLevel lod = d.isEmpty() ? null : pipeline.level(level);
            if (lod == null) {
                continue;
            }
            Dimension dim = d.get();
            dim.world = lod.world();
            schedule(dim, players);
            if (report) {
                report(dim);
            }
        }
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
     * Submits the missing tiles nearest a player, those behind counted
     * farther ({@link WantOrder#score}, ADR-056), up to {@link #MAX_IN_FLIGHT}
     * (or two per thread) at a time.
     */
    private void schedule(Dimension d, List<ServerPlayer> players) {
        PreviewStore store = d.world.preview();
        List<long[]> candidates = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ServerPlayer p : players) {
            Vec3 look = p.getLookAngle();
            double lookLength = Math.sqrt(look.x * look.x + look.z * look.z);
            int x0 = Math.floorDiv((int) Math.floor(p.getX() - RANGE), TILE);
            int x1 = Math.floorDiv((int) Math.floor(p.getX() + RANGE), TILE);
            int z0 = Math.floorDiv((int) Math.floor(p.getZ() - RANGE), TILE);
            int z1 = Math.floorDiv((int) Math.floor(p.getZ() + RANGE), TILE);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    double dx = Math.max(0, Math.max(x * (double) TILE - p.getX(), p.getX() - (x + 1) * (double) TILE));
                    double dz = Math.max(0, Math.max(z * (double) TILE - p.getZ(), p.getZ() - (z + 1) * (double) TILE));
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    long tile = PreviewStore.key(x, z);
                    int grid = grid(distance);
                    if (distance > RANGE || !seen.add(tile)) {
                        continue;
                    }
                    Integer built = d.done.get(tile);
                    if (built == null && store.contains(x, z)) {
                        built = store.summary(x, z).grid();
                        d.done.put(tile, built);
                    }
                    if (built != null && built <= grid) {
                        continue;
                    }
                    double cx = (x + 0.5) * TILE - p.getX();
                    double cz = (z + 0.5) * TILE - p.getZ();
                    double centerDistance = Math.sqrt(cx * cx + cz * cz);
                    // Looking straight up or down, or standing in the tile: no side is behind.
                    double facing = lookLength < 1e-3 || centerDistance < 1 ? 1
                            : (look.x * cx + look.z * cz) / (lookLength * centerDistance);
                    candidates.add(new long[]{tile, (long) WantOrder.score(distance, facing), grid,
                            distance <= NEAR ? 1 : 0, facing >= FRONT_COS ? 1 : 0});
                }
            }
        }
        d.left = candidates.size();
        trackFill(d, players.get(0), candidates);
        candidates.sort((a, b) -> Long.compare(a[1], b[1]));
        for (long[] c : candidates) {
            if (d.inFlight.size() >= Math.max(MAX_IN_FLIGHT, 2 * pool.getCorePoolSize())) {
                break;
            }
            long tile = c[0];
            int grid = (int) c[2];
            if (d.inFlight.add(tile)) {
                pool.execute(() -> build(d, tile, grid));
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
            near |= c[3] != 0;
            front |= c[4] != 0;
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
        Farfield.LOG.info("Noise preview fill after {}: {} tiles to build; within {} blocks done at {}, in front at {},"
                        + " {} at {} s", d.fillCause, d.fillTiles, (int) NEAR, seconds(d, d.nearDone),
                seconds(d, d.frontDone), end, String.format("%.1f", (now - d.fillStart) / 1e9));
        d.fillStart = 0;
    }

    private static String seconds(Dimension d, long t) {
        return t == 0 ? "-" : String.format("%.1f s", (t - d.fillStart) / 1e9);
    }

    private void report(Dimension d) {
        int built = d.built.getAndSet(0);
        long nanos = d.builtNanos.getAndSet(0);
        if (built > 0) {
            Farfield.LOG.info("Noise preview of {}: {} tiles built in the last minute ({} ms each, {} villages),"
                    + " {} left within {} blocks", d.key.location(), built, nanos / 1_000_000 / built,
                    d.villagesBuilt.getAndSet(0), d.left, (int) RANGE);
            d.reportedDone = false;
        } else if (d.left == 0 && !d.reportedDone) {
            Farfield.LOG.info("Noise preview of {}: every tile within {} blocks is built",
                    d.key.location(), (int) RANGE);
            d.reportedDone = true;
        }
    }

    /** Builds and stores one tile. */
    /**
     * Blocks between the heights a tile computes at a distance: {@link
     * PreviewTile#GRID} up to 1,024 blocks, 8 up to 2,048, 16 beyond. The
     * LODs there draw voxels of 8 blocks from about 1,250 blocks and of 16
     * from about 2,500 at the default detail.
     */
    static int grid(double distance) {
        return distance <= 1024 ? PreviewTile.GRID : distance <= 2048 ? 8 : 16;
    }

    private void build(Dimension d, long key, int grid) {
        int tileX = PreviewStore.tileX(key);
        int tileZ = PreviewStore.tileZ(key);
        long start = System.nanoTime();
        try {
            LodWorld world = d.world;
            PreviewTile tile = new PreviewTile();
            int x0 = tileX * TILE;
            int z0 = tileZ * TILE;
            int previous = UNKNOWN;
            int step = grid / PreviewTile.GRID;
            for (int k = 0; k < PreviewTile.SAMPLES; k += step) {
                // Each row starts from the one above: neighbors are seeds, not results.
                int seed = k == 0 ? UNKNOWN : tile.sample(0, k - step);
                for (int i = 0; i < PreviewTile.SAMPLES; i += step) {
                    if (stopped) {
                        return;
                    }
                    int h = surface(d, x0 + i * PreviewTile.GRID, z0 + k * PreviewTile.GRID, i == 0 ? seed : previous);
                    tile.setHeight(i, k, h);
                    previous = h;
                }
            }
            boolean[] icebergs = icebergs(d, tile, x0, z0, step);
            tile.fillBetweenSamples(step);
            tile.setGrid(grid);
            Materials m = new Materials(world, d);
            Holder<Biome>[] chunkBiomes = biomes(d, tile, x0, z0, world);
            for (int cz = 0; cz < PreviewTile.CELLS; cz++) {
                for (int cx = 0; cx < PreviewTile.CELLS; cx++) {
                    int h = Math.max(Math.max(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                            Math.max(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                    int low = Math.min(Math.min(tile.sample(cx, cz), tile.sample(cx + 1, cz)),
                            Math.min(tile.sample(cx, cz + 1), tile.sample(cx + 1, cz + 1)));
                    Holder<Biome> biome = chunkBiomes[(cz >> 2) * PreviewTile.CHUNKS + (cx >> 2)];
                    m.surface(biome, x0 + cx * PreviewTile.GRID, h, z0 + cz * PreviewTile.GRID, h - low >= CLIFF_RISE);
                    if (h > d.seaLevel && iceberg(icebergs, cx, cz, step)) {
                        // Vanilla caps the highest icebergs with a few blocks of snow from sea + 18 to 27.
                        m.set(h - 1 > d.seaLevel + ICEBERG_SNOW ? m.snow : m.packedIce, m.packedIce);
                    }
                    tile.setCell(cx, cz, m.top, m.filler);
                    // Vanilla's freeze_top_layer: the sea's top block turns to ice where the
                    // biome there is too cold to rain (frozen oceans: a noise leaves open water).
                    // The cell's own biome at the sea's top, as vanilla, not the chunk's: one
                    // chunk-wide biome left whole chunks of open water in a frozen sea.
                    if (m.ice >= 0 && low < d.seaLevel) {
                        int x = x0 + cx * PreviewTile.GRID + PreviewTile.GRID / 2;
                        int z = z0 + cz * PreviewTile.GRID + PreviewTile.GRID / 2;
                        Holder<Biome> surface = d.biomes.getNoiseBiome(QuartPos.fromBlock(x),
                                QuartPos.fromBlock(d.seaLevel - 1), QuartPos.fromBlock(z), d.sampler);
                        tile.setFrozen(cx, cz, !surface.value().warmEnoughToRain(new BlockPos(x, d.seaLevel - 1, z)));
                    }
                }
            }
            tile.setBelow(m.below);
            tile.setWater(m.water, d.seaLevel);
            tile.setIce(m.ice);
            tile.setIceberg(m.packedIce);
            List<PreviewVillages.Village> villages = villages(d, tile, x0, z0, m);
            plantTrees(d, tile, tileX, tileZ, chunkBiomes, m, villages);
            world.preview().write(tileX, tileZ, tile);
            d.done.put(key, grid);
            d.built.incrementAndGet();
            d.builtNanos.addAndGet(System.nanoTime() - start);
        } catch (IOException | RuntimeException e) {
            if (!stopped) {
                Farfield.LOG.warn("Noise preview of {}: tile {}, {} failed", d.key.location(), tileX, tileZ, e);
            }
        } finally {
            d.inFlight.remove(key);
        }
    }

    /** Blocks above the sea from which an iceberg's top shows snow (vanilla: 18 to 27, random). */
    private static final int ICEBERG_SNOW = 22;

    /**
     * Raises the computed samples (every {@code step}) under the sea of a
     * frozen ocean to the icebergs vanilla's surface rule builds there; returns
     * which samples rose, {@code k * SAMPLES + i}.
     */
    private static boolean[] icebergs(Dimension d, PreviewTile tile, int x0, int z0, int step) {
        boolean[] raised = new boolean[PreviewTile.SAMPLES * PreviewTile.SAMPLES];
        if (d.fluid == null || !d.fluid.is(Blocks.WATER)) {
            return raised;
        }
        for (int k = 0; k < PreviewTile.SAMPLES; k += step) {
            for (int i = 0; i < PreviewTile.SAMPLES; i += step) {
                if (tile.sample(i, k) > d.seaLevel) {
                    continue;
                }
                int x = x0 + i * PreviewTile.GRID;
                int z = z0 + k * PreviewTile.GRID;
                Holder<Biome> biome = d.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(d.seaLevel - 1),
                        QuartPos.fromBlock(z), d.sampler);
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

    /** Whether cell (cx, cz) touches a raised sample of the computed grid ({@code step} samples apart). */
    private static boolean iceberg(boolean[] raised, int cx, int cz, int step) {
        int i0 = cx / step * step;
        int k0 = cz / step * step;
        for (int k = k0; k <= Math.min(k0 + step, PreviewTile.SAMPLES - 1); k += step) {
            for (int i = i0; i <= Math.min(i0 + step, PreviewTile.SAMPLES - 1); i += step) {
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
                        QuartPos.fromBlock(surface.applyAsInt(x, z)), QuartPos.fromBlock(z), d.sampler));
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

    /**
     * Places each chunk's trees and plants: the biome's average count, at
     * random cells of the ground each needs, above the sea and outside villages.
     */
    private void plantTrees(Dimension d, PreviewTile tile, int tileX, int tileZ, Holder<Biome>[] chunkBiomes,
                            Materials m, List<PreviewVillages.Village> villages) {
        for (int cz = 0; cz < PreviewTile.CHUNKS; cz++) {
            for (int cx = 0; cx < PreviewTile.CHUNKS; cx++) {
                Holder<Biome> biome = chunkBiomes[cz * PreviewTile.CHUNKS + cx];
                PreviewTrees.BiomeTrees bt = trees.of(biome);
                if (bt.total() <= 0) {
                    continue;
                }
                long chunkX = tileX * (long) PreviewTile.CHUNKS + cx;
                long chunkZ = tileZ * (long) PreviewTile.CHUNKS + cz;
                java.util.Random random = new java.util.Random(d.seed ^ chunkX * 341873128712L ^ chunkZ * 132897987541L);
                double expected = Math.min(MAX_TREES_PER_CHUNK, bt.total());
                int n = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
                for (int i = 0; i < n; i++) {
                    int bx = cx * 16 + random.nextInt(16);
                    int bz = cz * 16 + random.nextInt(16);
                    PreviewTrees.Spec spec = bt.pick(random.nextDouble());
                    int top = tile.top(bx / PreviewTile.GRID, bz / PreviewTile.GRID);
                    boolean ground = switch (spec.ground()) {
                        case SOIL -> top == m.grass || top == m.snow;
                        case FUNGAL -> top == m.grass || top == m.mycelium;
                        case SAND -> top == m.sand;
                    };
                    int surface = tile.height(bx, bz);
                    int x = tileX * TILE + bx;
                    int z = tileZ * TILE + bz;
                    if (!ground || surface <= d.seaLevel || inVillage(villages, x, z)) {
                        continue;
                    }
                    // Vanilla's freeze_top_layer: snow over the crown where the block above its
                    // top is cold enough to snow (the chunk's biome, as the snowy ground).
                    boolean snowy = spec.leaves() != null && spec.shape() != PreviewTile.COLUMN
                            && biome.value().coldEnoughToSnow(new BlockPos(x, surface + spec.height() + 1, z));
                    int kind = tile.kind(new PreviewTile.TreeKind(m.id(spec.trunk()),
                            spec.leaves() == null ? -1 : m.id(spec.leaves()), spec.height(), spec.radius(),
                            spec.crownHeight(), spec.shape(), snowy ? m.snowLayer : -1));
                    if (kind >= 0) {
                        tile.addTree(bx, bz, kind);
                    }
                }
            }
        }
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

    /** The biome at the surface of each chunk's center, stored in the tile and returned. */
    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] biomes(Dimension d, PreviewTile tile, int x0, int z0, LodWorld world) {
        Holder<Biome>[] out = new Holder[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
        for (int cz = 0; cz < PreviewTile.CHUNKS; cz++) {
            for (int cx = 0; cx < PreviewTile.CHUNKS; cx++) {
                int x = x0 + cx * 16 + 8;
                int z = z0 + cz * 16 + 8;
                int y = tile.sample(cx * 4 + 2, cz * 4 + 2);
                Holder<Biome> biome = d.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                        QuartPos.fromBlock(z), d.sampler);
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

        /** The blocks seen on top of a cell and just under it, guessed from its biome and slope. */
        void surface(Holder<Biome> biome, int x, int surfaceY, int z, boolean steep) {
            if (d.end) {
                set(below, below);
            } else if (surfaceY < d.seaLevel && d.fluid != null) {
                int floor = biome.value().getBaseTemperature() < 0.15f ? gravel : sand;
                set(floor, floor);
            } else if (biome.is(BiomeTags.IS_BADLANDS)) {
                set(steep ? terracotta : redSand, terracotta);
            } else if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BEACH)) {
                set(sand, sandstone);
            } else if (steep || biome.is(Biomes.STONY_PEAKS) || biome.is(Biomes.STONY_SHORE)) {
                set(stone, stone);
            } else if (biome.value().coldEnoughToSnow(new BlockPos(x, surfaceY, z))) {
                set(snow, biome.is(BiomeTags.IS_MOUNTAIN) ? snow : dirt);
            } else if (biome.is(Biomes.MUSHROOM_FIELDS)) {
                set(mycelium, dirt);
            } else {
                set(grass, dirt);
            }
        }
    }

    /** Stops building and waits for the running tiles, before the stores close. */
    void stop() {
        stopped = true;
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                Farfield.LOG.warn("Noise preview threads still running after 10 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
