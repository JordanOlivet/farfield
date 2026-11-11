package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.preview.PreviewTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * The ground of a coarse tile's cells from the generator's surface rules
 * (E.3a of milestone 2), for the tiles that hold the ground only ({@code
 * PreviewPyramid.GROUND_ONLY} and up): the rules run on one column a cell,
 * at its center, through {@link SurfaceColumns}, as a level-0 tile's cells
 * since ADR-134. A cell is 32 blocks or more wide, so each sits in a chunk of
 * its own: the chunk's world generation heightmap the slope reads is laid
 * from the tile's samples, interpolated over the cell. One biome sample a
 * cell, at the height first asked (the ground's), no zoom: a cell's quart
 * columns are all its own. The snow the rules do not lay is decided at the
 * cell's center, by its own biome at its interpolated height, as a level-0
 * cell decides it at its center: by the coarse chunk's biome at the cell's
 * highest corner, forests far inland came out snowy.
 */
final class CoarseGround {

    /** Dimensions whose coarse ground was logged once, by the success line or the failure. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private final SurfaceColumns columns;
    private final PreviewTile tile;
    private final int x0;
    private final int z0;
    private final int spacing;
    private final int minY;
    private final int seaLevel;
    private final boolean sea;
    private final java.util.function.Function<net.minecraft.core.BlockPos, Holder<Biome>> biomes;
    private final String dimension;
    private final int[] heights = new int[256];
    private final BlockState[] out = new BlockState[2];
    private boolean failed;
    private boolean snow;

    private CoarseGround(SurfaceColumns columns, PreviewTile tile, int x0, int z0, int spacing, int minY,
                         int seaLevel, boolean sea,
                         java.util.function.Function<net.minecraft.core.BlockPos, Holder<Biome>> biomes,
                         String dimension) {
        this.seaLevel = seaLevel;
        this.sea = sea;
        this.biomes = biomes;
        this.columns = columns;
        this.tile = tile;
        this.x0 = x0;
        this.z0 = z0;
        this.spacing = spacing;
        this.minY = minY;
        this.dimension = dimension;
    }

    /**
     * The rules for a coarse tile whose block (x0, z0) is its corner and
     * whose samples are {@code spacing} blocks apart; null when the rules
     * cannot run column by column (the guess lays the ground then).
     */
    static CoarseGround create(ServerLevel level, NoiseBasedChunkGenerator generator, BiomeSource biomes,
                               Climate.Sampler sampler, BlockState fluid, int seaLevel, PreviewTile tile, int x0,
                               int z0, int spacing) {
        String dimension = level.dimension().location().toString();
        Long2ObjectOpenHashMap<Holder<Biome>> quarts = new Long2ObjectOpenHashMap<>();
        java.util.function.Function<net.minecraft.core.BlockPos, Holder<Biome>> cellBiomes = pos -> {
                    int qx = QuartPos.fromBlock(pos.getX());
                    int qz = QuartPos.fromBlock(pos.getZ());
                    long key = ChunkPos.asLong(qx, qz);
                    Holder<Biome> biome = quarts.get(key);
                    if (biome == null) {
                        biome = biomes.getNoiseBiome(qx, QuartPos.fromBlock(pos.getY()), qz, sampler);
                        quarts.put(key, biome);
                    }
                    return biome;
                };
        SurfaceColumns columns = SurfaceColumns.create(level, generator,
                PreviewFeatures.surfaceNoise(level, generator, fluid, seaLevel, x0 >> 4, z0 >> 4), cellBiomes,
                fluid, seaLevel);
        if (columns == null) {
            if (LOGGED.add(dimension)) {
                Farfield.LOG.warn("Noise preview of {}: the coarse tiles' ground is guessed, the surface rules"
                        + " cannot run column by column", dimension);
            }
            return null;
        }
        if (LOGGED.add(dimension)) {
            Farfield.LOG.info("Noise preview of {}: tiles of level {} and up hold the ground only, from the"
                    + " surface rules (E.3a)", dimension, io.lakio.farfield.core.preview.PreviewPyramid.GROUND_ONLY);
        }
        return new CoarseGround(columns, tile, x0, z0, spacing, level.getMinBuildHeight(), seaLevel, fluid != null,
                cellBiomes, dimension);
    }

    /**
     * The rules' top and filler of cell (cx, cz), whose highest corner sample
     * is {@code high} and lowest {@code low}; null once the rules failed (the
     * guess takes over for the rest of the tile).
     */
    BlockState[] cell(int cx, int cz, int high, int low) {
        snow = false;
        if (failed) {
            return null;
        }
        int cellX = x0 + cx * spacing;
        int cellZ = z0 + cz * spacing;
        int bx = cellX + spacing / 2;
        int bz = cellZ + spacing / 2;
        int chunkX = bx & ~15;
        int chunkZ = bz & ~15;
        int h00 = tile.sample(cx, cz);
        int h10 = tile.sample(cx + 1, cz);
        int h01 = tile.sample(cx, cz + 1);
        int h11 = tile.sample(cx + 1, cz + 1);
        for (int z = 0; z < 16; z++) {
            double v = Math.max(0, Math.min(1, (chunkZ + z - cellZ) / (double) spacing));
            for (int x = 0; x < 16; x++) {
                double u = Math.max(0, Math.min(1, (chunkX + x - cellX) / (double) spacing));
                heights[z * 16 + x] = (int) Math.round((h00 * (1 - u) + h10 * u) * (1 - v) + (h01 * (1 - u) + h11 * u) * v);
            }
        }
        try {
            columns.chunk(heights);
            int bottom = Math.max(minY, Math.floorDiv(low - PreviewFeatures.RULES_DEPTH, 16) * 16);
            columns.column(bx, bz, high, bottom, out);
            int center = (int) Math.round((h00 + h10 + h01 + h11) / 4.0);
            var at = new net.minecraft.core.BlockPos(bx, center, bz);
            snow = sea && center > seaLevel && biomes.apply(at).value().coldEnoughToSnow(at);
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            if (LOGGED.add(dimension + " failed")) {
                Farfield.LOG.warn("Noise preview of {}: the surface rules failed on a coarse tile; its ground is"
                        + " guessed", dimension, e);
            }
            return null;
        }
    }

    /** Whether the last cell asked lies under snow the rules do not lay (see the class comment). */
    boolean snow() {
        return snow;
    }
}
