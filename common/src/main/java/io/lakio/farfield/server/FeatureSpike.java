package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.util.EnumSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Spike for ADR-079: decorates a square of chunks in a {@link FeatureSandbox}
 * whose ground is the real chunks' copied at the carvers status (no
 * feature yet), then generates the same chunks for real and compares their
 * trees. Measures what a feature pass costs per chunk, which features fail
 * outside a real world, and whether the trees land where the game puts
 * them. Generates far chunks into the world it runs in.
 */
final class FeatureSpike {

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private FeatureSpike() {
    }

    /** Starts a run on a thread of its own near the closest {@code biome}; false if one is running. */
    static boolean start(ServerLevel level, ResourceLocation biome, int side, Consumer<String> report) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                run(level, biome, side, line -> {
                    Farfield.LOG.info("Feature spike: {}", line);
                    report.accept(line);
                });
            } catch (Throwable e) {
                Farfield.LOG.error("Feature spike failed", e);
                report.accept("failed: " + e);
            } finally {
                RUNNING.set(false);
            }
        }, "farfield-feature-spike");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void run(ServerLevel level, ResourceLocation biomeId, int side, Consumer<String> report) {
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, biomeId);
        // Far from spawn: its chunks are generated in full at start.
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

        // The stand-in: each chunk copied from the real one at the carvers status, before any feature.
        long[] copyNanos = new long[1];
        int[] contaminated = new int[1];
        FeatureSandbox sandbox = new FeatureSandbox(level, k -> {
            long start = System.nanoTime();
            ChunkAccess real = level.getChunkSource().getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), ChunkStatus.CARVERS,
                    true);
            if (real.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
                contaminated[0]++;
            }
            ChunkAccess copy = copy(level, real);
            copyNanos[0] += System.nanoTime() - start;
            return copy;
        });
        Map<String, Integer> failures = new TreeMap<>();
        LongOpenHashSet sandboxTrunks = new LongOpenHashSet();
        long[] blocks = new long[4]; // logs, leaves, other with a collision, without
        long decorateNanos = 0;
        for (int dz = 0; dz < side; dz++) {
            for (int dx = 0; dx < side; dx++) {
                long start = System.nanoTime();
                long copiedBefore = copyNanos[0];
                for (FeatureSandbox.Failure f : sandbox.decorate(cx0 + dx, cz0 + dz, (step, feature) -> true)) {
                    if (failures.merge(f.feature(), 1, Integer::sum) == 1) {
                        Farfield.LOG.warn("Feature spike: {} failed in the sandbox", f.feature(), f.error());
                    }
                }
                // The stand-in chunks made meanwhile (the real ones generated to the carvers) apart.
                decorateNanos += System.nanoTime() - start - (copyNanos[0] - copiedBefore);
            }
        }
        int chunks = side * side;
        // Kelp and seagrass (ADR-105): their columns and blocks, in the sandbox and for real.
        LongOpenHashSet sandboxSea = new LongOpenHashSet();
        long[] sandboxSeaBlocks = new long[1];
        sandbox.written().forEach((pos, state) -> {
            int x = BlockPos.getX(pos);
            int z = BlockPos.getZ(pos);
            if (x >> 4 < cx0 || x >> 4 >= cx0 + side || z >> 4 < cz0 || z >> 4 >= cz0 + side) {
                return;
            }
            // Above the ground only, as the preview shows it (ores and the like stay out).
            int ground = sandbox.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
            if (BlockPos.getY(pos) <= ground) {
                return;
            }
            classify(state, blocks);
            if (state.is(BlockTags.LOGS)) {
                sandboxTrunks.add(ChunkPos.asLong(x, z));
            }
            if (io.lakio.farfield.lod.BlockKeys.isSubmergedPlant(state)) {
                sandboxSea.add(ChunkPos.asLong(x, z));
                sandboxSeaBlocks[0]++;
            }
        });
        report.accept(String.format(java.util.Locale.ROOT,
                "sandbox: %.2f ms per chunk decorating, %.2f ms per stand-in chunk copied (%d chunks, %d already"
                        + " decorated), %d blocks set: %d logs, %d leaves, %d other solid, %d without collision;"
                        + " %d features failed", decorateNanos / 1e6 / chunks, copyNanos[0] / 1e6 / Math.max(1,
                        sandbox.chunkCount()), sandbox.chunkCount(), contaminated[0], sandbox.written().size(),
                blocks[0], blocks[1], blocks[2], blocks[3], failures.size()));
        failures.forEach((name, n) -> report.accept("  failed " + n + " times: " + name));

        // The same chunks for real: what their features set above the ground, against a fresh copy of
        // that ground (the sandbox's copies hold its own features now).
        long genStart = System.nanoTime();
        LongOpenHashSet realTrunks = new LongOpenHashSet();
        LongOpenHashSet realSea = new LongOpenHashSet();
        long[] realSeaBlocks = new long[1];
        long[] realBlocks = new long[4];
        java.util.Map<Long, ChunkAccess> grounds = new java.util.HashMap<>();
        for (int dz = 0; dz < side; dz++) {
            for (int dx = 0; dx < side; dx++) {
                long k = ChunkPos.asLong(cx0 + dx, cz0 + dz);
                grounds.put(k, sandbox.getChunk(cx0 + dx, cz0 + dz));
            }
        }
        for (int dz = 0; dz < side; dz++) {
            for (int dx = 0; dx < side; dx++) {
                ChunkAccess real = level.getChunkSource().getChunk(cx0 + dx, cz0 + dz, ChunkStatus.FULL, true);
                ChunkAccess ground = grounds.get(ChunkPos.asLong(cx0 + dx, cz0 + dz));
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int bx = (cx0 + dx) * 16 + x;
                        int bz = (cz0 + dz) * 16 + z;
                        int floor = ground.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
                        int top = real.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        for (int y = floor + 1; y <= top; y++) {
                            p.set(bx, y, bz);
                            BlockState state = real.getBlockState(p);
                            if (state.isAir() || state.getBlock() == net.minecraft.world.level.block.Blocks.WATER) {
                                continue;
                            }
                            classify(state, realBlocks);
                            if (io.lakio.farfield.lod.BlockKeys.isSubmergedPlant(state)) {
                                realSea.add(ChunkPos.asLong(bx, bz));
                                realSeaBlocks[0]++;
                            }
                            if (state.is(BlockTags.LOGS)) {
                                realTrunks.add(ChunkPos.asLong(bx, bz));
                            }
                        }
                    }
                }
            }
        }
        int both = 0;
        for (long column : sandboxTrunks) {
            if (realTrunks.contains(column)) {
                both++;
            }
        }
        report.accept(String.format(java.util.Locale.ROOT,
                "real: %.1f ms per chunk to full; above the ground: %d logs, %d leaves, %d other solid, %d without"
                        + " collision. Columns with logs: %d real, %d in the sandbox, %d in both (%.0f %% of the real"
                        + " ones found, %.0f %% of the sandbox's right)",
                (System.nanoTime() - genStart) / 1e6 / chunks, realBlocks[0], realBlocks[1], realBlocks[2],
                realBlocks[3], realTrunks.size(), sandboxTrunks.size(), both,
                realTrunks.isEmpty() ? 0 : 100.0 * both / realTrunks.size(),
                sandboxTrunks.isEmpty() ? 0 : 100.0 * both / sandboxTrunks.size()));
        int seaBoth = 0;
        for (long column : sandboxSea) {
            if (realSea.contains(column)) {
                seaBoth++;
            }
        }
        report.accept(String.format(java.util.Locale.ROOT,
                "under the sea (kelp, seagrass): columns %d real, %d in the sandbox, %d in both; blocks %d real,"
                        + " %d in the sandbox", realSea.size(), sandboxSea.size(), seaBoth, realSeaBlocks[0],
                sandboxSeaBlocks[0]));
    }

    private static void classify(BlockState state, long[] counts) {
        if (state.is(BlockTags.LOGS)) {
            counts[0]++;
        } else if (state.is(BlockTags.LEAVES)) {
            counts[1]++;
        } else if (!state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                .isEmpty()) {
            counts[2]++;
        } else {
            counts[3]++;
        }
    }

    /**
     * Builds the preview tile over the closest {@code biome} now, as the
     * preview builds it for a player (ADR-079), and reports its stages, its
     * size and what features grew in it; on a thread of its own.
     */
    static boolean startTile(ServerLevel level, ResourceLocation biomeId, Consumer<String> report) {
        return startTile(level, biomeId, null, report);
    }

    /** The tile over block {@code position} (x, z) when it is given, else over the closest {@code biomeId}. */
    static boolean startTile(ServerLevel level, ResourceLocation biomeId, BlockPos position, Consumer<String> report) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                BlockPos at = position;
                if (at == null) {
                    ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, biomeId);
                    BlockPos from = level.getSharedSpawnPos().offset(4000, 0, 4000);
                    var found = level.findClosestBiome3d(h -> h.is(key), from, 6400, 32, 64);
                    if (found == null) {
                        report.accept("no " + biomeId + " within 6400 blocks");
                        return;
                    }
                    at = found.getFirst();
                }
                int tileX = Math.floorDiv(at.getX(), io.lakio.farfield.core.preview.PreviewTile.BLOCKS);
                int tileZ = Math.floorDiv(at.getZ(), io.lakio.farfield.core.preview.PreviewTile.BLOCKS);
                long start = System.nanoTime();
                long[] stages = LodServer.buildPreviewTileNow(level, tileX, tileZ);
                if (stages == null) {
                    report.accept("no preview or no LOD store in " + level.dimension().location());
                    return;
                }
                StringBuilder line = new StringBuilder();
                String[] names = NoisePreview.stages();
                for (int i = 0; i < names.length; i++) {
                    line.append(i == 0 ? "" : ", ").append(names[i]).append(' ').append(stages[i] / 1_000_000).append(" ms");
                }
                var world = LodServer.world(level.dimension());
                byte[] file = world.preview().readFile(tileX, tileZ);
                var tile = world.preview().read(tileX, tileZ);
                int columns = 0;
                int runs = 0;
                for (int fz = 0; fz < io.lakio.farfield.core.preview.PreviewTile.FEATURE_SIDE; fz++) {
                    for (int fx = 0; fx < io.lakio.farfield.core.preview.PreviewTile.FEATURE_SIDE; fx++) {
                        int n = tile.featureColumn(fx, fz).length / 2;
                        columns += n > 0 ? 1 : 0;
                        runs += n;
                    }
                }
                Farfield.LOG.info("Feature spike: tile {} {} over {} at {}: built in {} ms ({}), {} features failed;"
                                + " file {} KB, {} feature columns, {} runs; features: {}", tileX, tileZ, biomeId,
                        at.toShortString(), (System.nanoTime() - start) / 1_000_000, line, stages[names.length],
                        file == null ? 0 : file.length / 1024, columns, runs, LodServer.lastPreviewTiming());
                report.accept("tile " + tileX + " " + tileZ + " built, see the log");
            } catch (Throwable e) {
                Farfield.LOG.error("Feature spike failed", e);
                report.accept("failed: " + e);
            } finally {
                RUNNING.set(false);
            }
        }, "farfield-feature-spike");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** A stand-in copy of a chunk: its blocks and biomes, its world-gen and final heightmaps. */
    static ProtoChunk copy(ServerLevel level, ChunkAccess real) {
        LevelChunkSection[] from = real.getSections();
        LevelChunkSection[] sections = new LevelChunkSection[from.length];
        for (int i = 0; i < from.length; i++) {
            // Block by block: PalettedContainer.copy keeps the original's resize handler, so a copy
            // that grows its palette writes into the original's storage. The biomes are only read.
            var states = new net.minecraft.world.level.chunk.PalettedContainer<>(
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
            if (!from[i].hasOnlyAir()) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = from[i].getBlockState(x, y, z);
                            if (!state.isAir()) {
                                states.getAndSetUnchecked(x, y, z, state);
                            }
                        }
                    }
                }
            }
            LevelChunkSection section = new LevelChunkSection(states, from[i].getBiomes());
            sections[i] = section;
        }
        ProtoChunk copy = new ProtoChunk(real.getPos(), UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(),
                new ProtoChunkTicks<>(), level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
        copy.setPersistedStatus(ChunkStatus.CARVERS);
        Heightmap.primeHeightmaps(copy, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE));
        return copy;
    }

}
