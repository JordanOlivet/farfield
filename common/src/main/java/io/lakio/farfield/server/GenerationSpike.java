package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Spike S6 (terrain not generated yet): measures what showing unexplored
 * terrain would cost, in the world it runs in.
 *
 * <ol>
 *   <li>Coarse noise preview, one column per chunk on a pool: the
 *       generator's surface height ({@code getBaseHeight}, ocean floor) and
 *       the biome there; then vanilla's preliminary surface level (the
 *       density without jaggedness, scanned down by cell height, as
 *       {@code NoiseChunk} does for aquifers) with and without the biome.</li>
 *   <li>Generation by the game: square areas of chunks, each fresh and far
 *       from spawn, generated up to {@code noise}, {@code surface} and
 *       {@code full} by tickets of their own, timed until every chunk of the
 *       area reached the status.</li>
 * </ol>
 *
 * <p>Throwaway measurement: the generated chunks are saved into the world
 * like any other, so run it in a test world. One run at a time.
 */
final class GenerationSpike {

    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("farfield_spike", Comparator.comparingLong(ChunkPos::toLong));
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** Columns of the noise sample: a square of this many chunks per side, one column each. */
    private static final int NOISE_SIDE = 64;
    /** Columns of a 1,000-chunk radius horizon at one column per chunk (spike S6 target). */
    private static final double HORIZON_COLUMNS = Math.PI * 1000 * 1000;

    private GenerationSpike() {
    }

    /** Starts a run on a thread of its own; false if one is running. */
    static boolean start(ServerLevel level, int side, Consumer<String> report) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                run(level, side, report);
            } catch (Exception e) {
                Farfield.LOG.warn("Generation spike failed", e);
                report.accept("Generation spike failed: " + e);
            } finally {
                RUNNING.set(false);
            }
        }, "Farfield generation spike");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static void run(ServerLevel level, int side, Consumer<String> report) throws Exception {
        Random random = new Random(level.getSeed() ^ System.nanoTime());
        noise(level, random, report);
        for (ChunkStatus status : List.of(ChunkStatus.NOISE, ChunkStatus.SURFACE, ChunkStatus.FULL)) {
            generate(level, status, farChunk(random), farChunk(random), side, report);
        }
        report.accept("Generation spike done.");
    }

    /** A chunk coordinate 20,000 to 50,000 chunks from spawn, either side: fresh terrain. */
    private static int farChunk(Random random) {
        int c = 20_000 + random.nextInt(30_000);
        return random.nextBoolean() ? c : -c;
    }

    /** How a noise column is sampled. */
    private enum Mode {
        BASE_HEIGHT_AND_BIOME("getBaseHeight + biome"),
        PRELIMINARY_AND_BIOME("preliminary surface + biome"),
        PRELIMINARY("preliminary surface only");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private record Noise(ServerLevel level, ChunkGenerator generator, RandomState state, BiomeSource biomes,
                         Climate.Sampler sampler, DensityFunction density, NoiseSettings settings) {
    }

    private static void noise(ServerLevel level, Random random, Consumer<String> report) throws Exception {
        ServerChunkCache chunks = level.getChunkSource();
        ChunkGenerator generator = chunks.getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            report.accept("Noise preview: skipped, " + generator.getClass().getSimpleName() + " has no noise router.");
            return;
        }
        RandomState state = chunks.randomState();
        Noise noise = new Noise(level, generator, state, generator.getBiomeSource(), state.sampler(),
                state.router().initialDensityWithoutJaggedness(),
                noiseGenerator.generatorSettings().value().noiseSettings());
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Farfield generation spike noise");
            t.setDaemon(true);
            return t;
        });
        try {
            for (Mode mode : Mode.values()) {
                int x0 = farChunk(random) * 16 + 8;
                int z0 = farChunk(random) * 16 + 8;
                // Warm-up on other columns: class loading and JIT.
                sampleSquare(pool, noise, mode, x0, z0 + NOISE_SIDE * 32);
                long start = System.nanoTime();
                long checksum = sampleSquare(pool, noise, mode, x0, z0);
                double seconds = (System.nanoTime() - start) / 1e9;
                int columns = NOISE_SIDE * NOISE_SIDE;
                double rate = columns / seconds;
                String line = String.format(Locale.ROOT,
                        "Noise preview (%s): %,d columns, one per chunk, in %.2f s on %d threads (%,.0f columns/s,"
                                + " %.0f us per column and thread); a 1,000-chunk radius horizon would take %.0f s"
                                + " [checksum %d]",
                        mode.label, columns, seconds, threads, rate, seconds * threads * 1e6 / columns,
                        HORIZON_COLUMNS / rate, checksum & 0xFF);
                Farfield.LOG.info(line);
                report.accept(line);
            }
            accuracy(noise, farChunk(random) * 16 + 8, farChunk(random) * 16 + 8, report);
        } finally {
            pool.shutdown();
        }
    }

    /** How far the preliminary surface lies from the generator's ocean-floor height, over one square. */
    private static void accuracy(Noise noise, int x0, int z0, Consumer<String> report) {
        int columns = 0;
        long sum = 0;
        int max = 0;
        int within8 = 0;
        long signed = 0;
        for (int row = 0; row < NOISE_SIDE; row += 2) {
            for (int col = 0; col < NOISE_SIDE; col += 2) {
                int x = x0 + col * 16;
                int z = z0 + row * 16;
                int exact = noise.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, noise.level, noise.state);
                // getBaseHeight is the first free block above the floor; the preliminary level a cell top.
                int d = preliminarySurface(noise, x, z) - (exact - 1);
                columns++;
                sum += Math.abs(d);
                signed += d;
                max = Math.max(max, Math.abs(d));
                within8 += Math.abs(d) <= 8 ? 1 : 0;
            }
        }
        String line = String.format(Locale.ROOT,
                "Noise preview accuracy: preliminary surface minus ocean floor over %d columns: mean %.1f blocks"
                        + " (signed %.1f), max %d, %d%% within 8 blocks",
                columns, sum / (double) columns, signed / (double) columns, max, within8 * 100 / columns);
        Farfield.LOG.info(line);
        report.accept(line);
    }

    /** One column per chunk over the square at (x0, z0), one task per row. */
    private static long sampleSquare(ExecutorService pool, Noise noise, Mode mode, int x0, int z0) throws Exception {
        List<Future<Long>> rows = new ArrayList<>();
        for (int row = 0; row < NOISE_SIDE; row++) {
            int z = z0 + row * 16;
            rows.add(pool.submit(() -> sampleRow(noise, mode, x0, z)));
        }
        long checksum = 0;
        for (Future<Long> f : rows) {
            checksum += f.get();
        }
        return checksum;
    }

    private static long sampleRow(Noise noise, Mode mode, int x0, int z) {
        long checksum = 0;
        for (int col = 0; col < NOISE_SIDE; col++) {
            int x = x0 + col * 16;
            int height = mode == Mode.BASE_HEIGHT_AND_BIOME
                    ? noise.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, noise.level, noise.state)
                    : preliminarySurface(noise, x, z);
            checksum += height * 31L;
            if (mode != Mode.PRELIMINARY) {
                Holder<Biome> biome = noise.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(height),
                        QuartPos.fromBlock(z), noise.sampler);
                checksum += biome.hashCode();
            }
        }
        return checksum;
    }

    /** {@code NoiseChunk.computePreliminarySurfaceLevel}: the highest cell top above the density threshold. */
    private static int preliminarySurface(Noise noise, int x, int z) {
        int minY = noise.settings.minY();
        for (int y = minY + noise.settings.height(); y >= minY; y -= noise.settings.getCellHeight()) {
            if (noise.density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.390625) {
                return y;
            }
        }
        return minY;
    }

    private static void generate(ServerLevel level, ChunkStatus status, int cx, int cz, int side,
                                 Consumer<String> report) throws Exception {
        MinecraftServer server = level.getServer();
        List<ChunkPos> area = new ArrayList<>(side * side);
        for (int dz = 0; dz < side; dz++) {
            for (int dx = 0; dx < side; dx++) {
                area.add(new ChunkPos(cx + dx, cz + dz));
            }
        }
        // Region tickets count from the full-chunk level: a negative distance holds a lower status.
        int distance = ChunkLevel.byStatus(FullChunkStatus.FULL) - ChunkLevel.byStatus(status);
        long cpu = processCpuNanos();
        long start = System.nanoTime();
        List<CompletableFuture<ChunkResult<ChunkAccess>>> futures = server.submit(() -> {
            ServerChunkCache chunks = level.getChunkSource();
            List<CompletableFuture<ChunkResult<ChunkAccess>>> list = new ArrayList<>(area.size());
            for (ChunkPos p : area) {
                chunks.addRegionTicket(TICKET, p, distance, p);
            }
            for (ChunkPos p : area) {
                list.add(chunks.getChunkFuture(p.x, p.z, status, true));
            }
            return list;
        }).get();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.MINUTES);
        } finally {
            server.submit(() -> {
                for (ChunkPos p : area) {
                    level.getChunkSource().removeRegionTicket(TICKET, p, distance, p);
                }
            }).get();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double cores = (processCpuNanos() - cpu) / 1e9 / seconds;
        long ok = futures.stream().filter(f -> f.join().isSuccess()).count();
        String line = String.format(Locale.ROOT,
                "Generation up to %s: %d of %d chunks at %d,%d in %.1f s (%.1f chunks/s, %.1f ms per chunk;"
                        + " %.1f cores busy on average)",
                status.getName(), ok, area.size(), cx, cz, seconds, ok / seconds, seconds * 1000 / Math.max(ok, 1),
                cores);
        Farfield.LOG.info(line);
        report.accept(line);
    }

    private static long processCpuNanos() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return 0;
    }
}
