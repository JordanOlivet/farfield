package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.lod.PreviewColumns;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.BlockKeys;
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
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.io.IOException;
import java.util.ArrayList;
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

/**
 * Noise preview of terrain not generated yet (ADR-038), built by the
 * integrated server: every {@link #UPDATE_TICKS} ticks, the section columns
 * (512 x 512 blocks) within {@link #RANGE} blocks of a player, nearest first,
 * on two low-priority threads. Each column of a chunk gets its surface from
 * the noise router's final density (scanned down by cell height, bisected
 * to the block), its biome, a surface block guessed from the biome, and
 * water up to the sea level; {@link PreviewColumns} turns them into level-4
 * sections stored under {@link LodWorld#previewKey}.
 */
final class NoisePreview {

    private static final int UPDATE_TICKS = 40;
    private static final int REPORT_TICKS = 1200;
    private static final int MAX_IN_FLIGHT = 8;
    /** The LOD range: the renderer draws nothing farther. */
    private static final double RANGE = 4096;
    private static final int SECTION_BLOCKS = LodSection.SIZE * PreviewColumns.VOXEL_BLOCKS;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "farfield-noise-preview");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Map<ResourceKey<Level>, Optional<Dimension>> dimensions = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private long ticks;

    /** What one dimension's preview needs, and its progress. */
    private static final class Dimension {
        final ResourceKey<Level> key;
        final ChunkGenerator generator;
        final RandomState state;
        final BiomeSource biomes;
        final Climate.Sampler sampler;
        final DensityFunction density;
        final NoiseSettings noise;
        final int seaLevel;
        final BlockState defaultBlock;
        /** The sea's fluid, null when the dimension has none (the End). */
        final BlockState fluid;
        final boolean end;
        volatile LodWorld world;
        final Set<Long> done = ConcurrentHashMap.newKeySet();
        final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
        final AtomicInteger built = new AtomicInteger();
        final AtomicLong builtNanos = new AtomicLong();
        int left = -1;
        boolean reportedDone;

        Dimension(ServerLevel level, NoiseBasedChunkGenerator generator) {
            key = level.dimension();
            this.generator = generator;
            state = level.getChunkSource().randomState();
            biomes = generator.getBiomeSource();
            sampler = state.sampler();
            density = state.router().finalDensity();
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            noise = settings.noiseSettings();
            seaLevel = generator.getSeaLevel();
            defaultBlock = settings.defaultBlock();
            fluid = settings.defaultFluid().isAir() ? null : settings.defaultFluid();
            end = key == Level.END;
        }
    }

    void tick(MinecraftServer server, LodPipeline pipeline) {
        if (++ticks % UPDATE_TICKS != 0 || stopped || !RenderSettings.noisePreview()) {
            return;
        }
        boolean report = ticks % REPORT_TICKS == 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            Optional<Dimension> d = dimensions.computeIfAbsent(level.dimension(), k -> open(level));
            if (d.isEmpty()) {
                continue;
            }
            io.lakio.farfield.lod.LodLevel lod = pipeline.level(level);
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

    /** Submits the nearest missing section columns, up to {@link #MAX_IN_FLIGHT} at a time. */
    private void schedule(Dimension d, List<ServerPlayer> players) {
        List<long[]> candidates = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (ServerPlayer p : players) {
            int x0 = Math.floorDiv((int) Math.floor(p.getX() - RANGE), SECTION_BLOCKS);
            int x1 = Math.floorDiv((int) Math.floor(p.getX() + RANGE), SECTION_BLOCKS);
            int z0 = Math.floorDiv((int) Math.floor(p.getZ() - RANGE), SECTION_BLOCKS);
            int z1 = Math.floorDiv((int) Math.floor(p.getZ() + RANGE), SECTION_BLOCKS);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    double dx = Math.max(0, Math.max(x * SECTION_BLOCKS - p.getX(), p.getX() - (x + 1) * SECTION_BLOCKS));
                    double dz = Math.max(0, Math.max(z * SECTION_BLOCKS - p.getZ(), p.getZ() - (z + 1) * SECTION_BLOCKS));
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    long column = column(x, z);
                    if (distance > RANGE || d.done.contains(column) || !seen.add(column)) {
                        continue;
                    }
                    candidates.add(new long[]{column, (long) distance});
                }
            }
        }
        d.left = candidates.size();
        candidates.sort((a, b) -> Long.compare(a[1], b[1]));
        for (long[] c : candidates) {
            if (d.inFlight.size() >= MAX_IN_FLIGHT) {
                break;
            }
            long column = c[0];
            if (d.inFlight.add(column)) {
                pool.execute(() -> build(d, column));
            }
        }
    }

    private static long column(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    private void report(Dimension d) {
        int built = d.built.getAndSet(0);
        long nanos = d.builtNanos.getAndSet(0);
        if (built > 0) {
            Farfield.LOG.info("Noise preview of {}: {} section columns built in the last minute ({} ms each),"
                            + " {} left within {} blocks", d.key.location(), built, nanos / 1_000_000 / built,
                    d.left, (int) RANGE);
            d.reportedDone = false;
        } else if (d.left == 0 && !d.reportedDone) {
            Farfield.LOG.info("Noise preview of {}: every section column within {} blocks is built",
                    d.key.location(), (int) RANGE);
            d.reportedDone = true;
        }
    }

    /** Builds and stores one section column, unless it is stored already. */
    private void build(Dimension d, long column) {
        int x = (int) (column >> 32);
        int z = (int) column;
        long start = System.nanoTime();
        try {
            LodWorld world = d.world;
            int minY = d.noise.minY();
            int y0 = Math.floorDiv(minY, SECTION_BLOCKS);
            int y1 = Math.floorDiv(minY + d.noise.height() - 1, SECTION_BLOCKS);
            for (int y = y0; y <= y1; y++) {
                if (world.exists(LodWorld.previewKey(x, y, z))) {
                    d.done.add(column);
                    return;
                }
            }
            PreviewColumns columns = new PreviewColumns(world.air());
            Materials m = new Materials(world, d);
            for (int cz = 0; cz < PreviewColumns.SIZE; cz++) {
                for (int cx = 0; cx < PreviewColumns.SIZE; cx++) {
                    if (stopped) {
                        return;
                    }
                    int bx = x * SECTION_BLOCKS + cx * PreviewColumns.VOXEL_BLOCKS + 8;
                    int bz = z * SECTION_BLOCKS + cz * PreviewColumns.VOXEL_BLOCKS + 8;
                    int surface = surface(d, bx, bz);
                    Holder<Biome> biome = d.biomes.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(surface),
                            QuartPos.fromBlock(bz), d.sampler);
                    columns.set(cx, cz, surface, m.surface(biome, bx, surface, bz),
                            world.biomes().id(BlockKeys.key(biome)));
                }
            }
            columns.setBelow(m.below);
            columns.setWater(m.water, d.seaLevel);
            for (int y = y0; y <= y1; y++) {
                LodSection section = columns.build(y, minY);
                if (section != null) {
                    world.writePreview(LodWorld.previewKey(x, y, z), section);
                }
            }
            d.done.add(column);
            d.built.incrementAndGet();
            d.builtNanos.addAndGet(System.nanoTime() - start);
        } catch (IOException | RuntimeException e) {
            if (!stopped) {
                Farfield.LOG.warn("Noise preview of {}: section column {}, {} failed", d.key.location(), x, z, e);
            }
        } finally {
            d.inFlight.remove(column);
        }
    }

    /**
     * The first free block above the highest point where the final density
     * is positive (solid), scanned down by cell height, then bisected.
     */
    private static int surface(Dimension d, int x, int z) {
        int minY = d.noise.minY();
        int top = minY + d.noise.height() - 1;
        int step = d.noise.getCellHeight();
        for (int y = top; y >= minY; y -= step) {
            if (d.density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0) {
                int lo = y;
                int hi = Math.min(y + step, top + 1);
                while (hi - lo > 1) {
                    int mid = (lo + hi) >>> 1;
                    if (d.density.compute(new DensityFunction.SinglePointContext(x, mid, z)) > 0) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                return lo + 1;
            }
        }
        return minY;
    }

    /** Material ids of one world for the surface guesses. */
    private static final class Materials {
        final LodWorld world;
        final Dimension d;
        final int below;
        final int water;

        Materials(LodWorld world, Dimension d) {
            this.world = world;
            this.d = d;
            below = id(d.defaultBlock);
            water = d.fluid == null ? -1 : id(d.fluid);
        }

        int id(BlockState state) {
            return world.blockStates().id(BlockKeys.key(state));
        }

        /** The block seen on top of a column, guessed from its biome. */
        int surface(Holder<Biome> biome, int x, int surfaceY, int z) {
            if (d.end) {
                return below;
            }
            if (surfaceY < d.seaLevel && d.fluid != null) {
                return id(biome.value().getBaseTemperature() < 0.15f ? Blocks.GRAVEL.defaultBlockState()
                        : Blocks.SAND.defaultBlockState());
            }
            if (biome.is(BiomeTags.IS_BADLANDS)) {
                return id(Blocks.RED_SAND.defaultBlockState());
            }
            if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BEACH)) {
                return id(Blocks.SAND.defaultBlockState());
            }
            if (biome.is(Biomes.MUSHROOM_FIELDS)) {
                return id(Blocks.MYCELIUM.defaultBlockState());
            }
            if (biome.is(Biomes.STONY_PEAKS) || biome.is(Biomes.STONY_SHORE)) {
                return id(Blocks.STONE.defaultBlockState());
            }
            if (biome.value().coldEnoughToSnow(new BlockPos(x, surfaceY, z))) {
                return id(Blocks.SNOW_BLOCK.defaultBlockState());
            }
            return id(Blocks.GRASS_BLOCK.defaultBlockState());
        }
    }

    /** Stops building and waits for the running columns, before the stores close. */
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
