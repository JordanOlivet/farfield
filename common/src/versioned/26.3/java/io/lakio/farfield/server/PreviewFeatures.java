package io.lakio.farfield.server;

import io.lakio.farfield.core.config.ServerConfig;
import io.lakio.farfield.core.preview.PreviewTile;
import io.lakio.farfield.core.world.LodWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minecraft 26.3, phase 2a (ADR-171): the preview's ground from the surface
 * rules (26.3's material system) and the world's own features (stand-in
 * chunks, a stand-in world) are not ported yet. The tile keeps the ground
 * guessed per cell, without features.
 */
final class PreviewFeatures {

    PreviewFeatures(ServerLevel level, BiomeSource biomes, Climate.Sampler sampler, BlockState fluid, int seaLevel,
                    LodWorld world, ServerConfig settings, PreviewTile tile, int tileX, int tileZ,
                    java.util.function.BiPredicate<Integer, Integer> bare, boolean[] keepCells) {
    }

    void budget(FeatureBudget budget) {
    }

    void measure(FeatureCosts costs) {
    }

    int failures() {
        return 0;
    }

    String timing() {
        return "no features on 26.3 yet";
    }

    void decorateWindow(int lx0, int lz0, int n) {
    }

    void groundWindow(int lx0, int lz0, int n) {
    }

    void compareGround() {
    }
}
