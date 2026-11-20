package io.lakio.farfield.server;

import io.lakio.farfield.core.preview.PreviewTile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * Minecraft 26.3, phase 2a (ADR-171): the coarse tiles' ground from the
 * surface rules waits for the port to 26.3's material system; without it the
 * coarse cells keep the ground guessed from their biome.
 */
final class CoarseGround {

    private CoarseGround() {
    }

    static CoarseGround create(ServerLevel level, NoiseBasedChunkGenerator generator, BiomeSource biomes,
                               Climate.Sampler sampler, BlockState fluid, int seaLevel, PreviewTile tile, int x0,
                               int z0, int spacing) {
        return null;
    }

    BlockState[] cell(int cx, int cz, int high, int low) {
        return null;
    }

    boolean snow() {
        return false;
    }
}
