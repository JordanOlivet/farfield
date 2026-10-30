package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.preview.PreviewTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Spike for ADR-101: a square of chunks generated for real up to their
 * surface, then, on the same heights, the generator's surface rules run on
 * a preview stand-in, and the tops the preview guessed from the biome
 * before. Both are compared with the real top block of every column, and
 * per 4-block cell as the tile keeps them (the most common top). The
 * heights are the real ones, so only the top blocks are judged, not the
 * preview's heights. Generates far chunks into the world it runs in.
 */
final class SurfaceSpike {

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private SurfaceSpike() {
    }

    /** Starts a run on a thread of its own near the closest {@code biome}; false if one is running. */
    static boolean start(ServerLevel level, ResourceLocation biome, int side, Consumer<String> report) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                run(level, biome, side, line -> {
                    Farfield.LOG.info("Surface spike: {}", line);
                    report.accept(line);
                });
            } catch (Throwable e) {
                Farfield.LOG.error("Surface spike failed", e);
                report.accept("failed: " + e);
            } finally {
                RUNNING.set(false);
            }
        }, "farfield-surface-spike");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void run(ServerLevel level, ResourceLocation biomeId, int side, Consumer<String> report) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            report.accept("the generator of " + level.dimension().location() + " has no surface rules");
            return;
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, biomeId);
        BlockPos from = level.getSharedSpawnPos().offset(4000, 0, 4000);
        var found = level.findClosestBiome3d(h -> h.is(key), from, 6400, 32, 64);
        if (found == null) {
            report.accept("no " + biomeId + " within 6400 blocks of " + from.toShortString());
            return;
        }
        BlockPos center = found.getFirst();
        int cx0 = (center.getX() >> 4) - side / 2;
        int cz0 = (center.getZ() >> 4) - side / 2;
        report.accept(biomeId + " at " + center.toShortString() + ": " + side + "x" + side + " chunks from chunk "
                + cx0 + " " + cz0);

        var settings = generator.generatorSettings().value();
        int seaLevel = generator.getSeaLevel();
        boolean sea = !settings.defaultFluid().isAir();
        var biomes = generator.getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
        PreviewFeatures stand = new PreviewFeatures(level, biomes, sampler, sea ? settings.defaultFluid() : null,
                seaLevel, null, StreamServer.settings(), new PreviewTile(), 0, 0, (x, z) -> false, null);

        long realNanos = 0;
        int columns = 0;
        int rulesRight = 0;
        int guessRight = 0;
        int cells = 0;
        int rulesCellsRight = 0;
        int guessCellsRight = 0;
        Map<String, Integer> rulesWrong = new HashMap<>();
        Map<String, Integer> guessWrong = new HashMap<>();
        for (int dz = 0; dz < side; dz++) {
            for (int dx = 0; dx < side; dx++) {
                int chunkX = cx0 + dx;
                int chunkZ = cz0 + dz;
                long t = System.nanoTime();
                // On the server thread: C2ME's chunk system hands no chunk to another thread (null).
                ChunkAccess real = level.getServer().submit(() -> level.getChunkSource().getChunk(chunkX, chunkZ,
                        ChunkStatus.SURFACE, true)).join();
                realNanos += System.nanoTime() - t;
                int[] heights = new int[256];
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        // getHeight is the top block itself: the stand-in wants the first free block above.
                        heights[z * 16 + x] = real.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
                    }
                }
                ChunkAccess rules = stand.surfaced(chunkX, chunkZ, heights);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                int per = 16 / PreviewTile.GRID;
                for (int gz = 0; gz < per; gz++) {
                    for (int gx = 0; gx < per; gx++) {
                        Map<BlockState, Integer> realTops = new HashMap<>();
                        Map<BlockState, Integer> ruleTops = new HashMap<>();
                        int low = Integer.MAX_VALUE;
                        int high = Integer.MIN_VALUE;
                        for (int z = gz * PreviewTile.GRID; z < (gz + 1) * PreviewTile.GRID; z++) {
                            for (int x = gx * PreviewTile.GRID; x < (gx + 1) * PreviewTile.GRID; x++) {
                                low = Math.min(low, heights[z * 16 + x]);
                                high = Math.max(high, heights[z * 16 + x]);
                            }
                        }
                        int bx = (chunkX << 4) + gx * PreviewTile.GRID + PreviewTile.GRID / 2;
                        int bz = (chunkZ << 4) + gz * PreviewTile.GRID + PreviewTile.GRID / 2;
                        Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(high),
                                QuartPos.fromBlock(bz), sampler);
                        BlockState guess = NoisePreview.guess(biome, bx, high, bz, high - low >= NoisePreview.cliffRise(),
                                seaLevel, sea, false, settings.defaultBlock())[0];
                        for (int z = gz * PreviewTile.GRID; z < (gz + 1) * PreviewTile.GRID; z++) {
                            for (int x = gx * PreviewTile.GRID; x < (gx + 1) * PreviewTile.GRID; x++) {
                                BlockState top = real.getBlockState(pos.set(x, heights[z * 16 + x] - 1, z));
                                realTops.merge(top, 1, Integer::sum);
                                columns++;
                                if (guess.getBlock() == top.getBlock()) {
                                    guessRight++;
                                } else {
                                    guessWrong.merge(name(top) + " as " + name(guess), 1, Integer::sum);
                                }
                                if (rules != null && PreviewFeatures.ruled(x, z)) {
                                    BlockState r = rules.getBlockState(pos.set(x,
                                            rules.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z), z));
                                    ruleTops.merge(r, 1, Integer::sum);
                                    if (r.getBlock() == top.getBlock()) {
                                        rulesRight++;
                                    } else {
                                        rulesWrong.merge(name(top) + " as " + name(r), 1, Integer::sum);
                                    }
                                }
                            }
                        }
                        cells++;
                        BlockState realCell = mostCommon(realTops);
                        guessCellsRight += guess.getBlock() == realCell.getBlock() ? 1 : 0;
                        if (!ruleTops.isEmpty()) {
                            rulesCellsRight += mostCommon(ruleTops).getBlock() == realCell.getBlock() ? 1 : 0;
                        }
                    }
                }
            }
        }
        int chunks = side * side;
        report.accept(String.format(java.util.Locale.ROOT,
                "tops right, columns: surface rules %.1f %% (its one column a cell), guess %.1f %%; cells: surface"
                        + " rules %.1f %%, guess %.1f %%",
                100.0 * rulesRight / cells, 100.0 * guessRight / columns,
                100.0 * rulesCellsRight / cells, 100.0 * guessCellsRight / cells));
        report.accept(String.format(java.util.Locale.ROOT,
                "surface rules %.2f ms a stand-in chunk; real chunks to surface %.1f ms each",
                stand.surfaceNanos() / 1e6 / chunks, realNanos / 1e6 / chunks));
        report.accept("surface rules wrong: " + worst(rulesWrong));
        report.accept("guess wrong: " + worst(guessWrong));
    }

    private static BlockState mostCommon(Map<BlockState, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
    }

    private static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** The five most frequent mistakes, "real as predicted: columns". */
    private static String worst(Map<String, Integer> wrong) {
        StringBuilder out = new StringBuilder();
        wrong.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
                .forEach(e -> out.append(out.isEmpty() ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue()));
        return out.isEmpty() ? "none" : out.toString();
    }
}
