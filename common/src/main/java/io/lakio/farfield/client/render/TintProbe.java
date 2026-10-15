package io.lakio.farfield.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * A one-block level for {@code BlockColors}: the probed state at the origin
 * in one biome. It records the {@link ColorResolver} a block color asks for,
 * which tells whether the color depends on the biome and how.
 */
final class TintProbe implements BlockAndTintGetter {

    private BlockState state = Blocks.AIR.defaultBlockState();
    private Biome biome;
    private ColorResolver resolver;

    void reset(BlockState state, Biome biome) {
        this.state = state;
        this.biome = biome;
        this.resolver = null;
    }

    /** The resolver the last color asked for, or null if none (the color does not depend on the biome). */
    ColorResolver resolver() {
        return resolver;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        resolver = colorResolver;
        return colorResolver.getColor(biome, pos.getX(), pos.getZ());
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1f;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        throw new UnsupportedOperationException("no light in a tint probe");
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return pos.equals(BlockPos.ZERO) ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }
}
