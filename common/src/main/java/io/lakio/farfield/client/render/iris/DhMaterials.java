package io.lakio.farfield.client.render.iris;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The DH contract's material class of a block state, the pack's
 * {@code dhMaterialId} (ADR-119): Iris's {@code StandardMacros} names them
 * {@code DH_BLOCK_*}. Our own rules from the game's block tags and sound
 * types, the same for every mod's blocks; no per-mod list.
 */
public final class DhMaterials {

    public static final int UNKNOWN = 0;
    public static final int LEAVES = 1;
    public static final int STONE = 2;
    public static final int WOOD = 3;
    public static final int METAL = 4;
    public static final int DIRT = 5;
    public static final int LAVA = 6;
    public static final int DEEPSLATE = 7;
    public static final int SNOW = 8;
    public static final int SAND = 9;
    public static final int TERRACOTTA = 10;
    public static final int NETHER_STONE = 11;
    public static final int WATER = 12;
    public static final int GRASS = 13;
    public static final int AIR = 14;
    public static final int ILLUMINATED = 15;

    private DhMaterials() {
    }

    public static int of(BlockState state) {
        if (state == null) {
            return UNKNOWN;
        }
        if (state.isAir()) {
            return AIR;
        }
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return LAVA;
        }
        if (state.getFluidState().is(FluidTags.WATER) && state.is(Blocks.WATER)) {
            return WATER;
        }
        if (state.is(BlockTags.LEAVES)) {
            return LEAVES;
        }
        if (state.getLightEmission() > 0) {
            return ILLUMINATED;
        }
        // Grass blocks and the plants on them (short grass, ferns, flowers, saplings).
        if (state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES) && state.getFluidState().isEmpty()) {
            return GRASS;
        }
        if (state.is(BlockTags.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
            return SNOW;
        }
        if (state.is(BlockTags.SAND)) {
            return SAND;
        }
        if (state.is(BlockTags.TERRACOTTA)) {
            return TERRACOTTA;
        }
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) {
            return WOOD;
        }
        if (state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.NYLIUM)) {
            return NETHER_STONE;
        }
        if (state.is(BlockTags.DIRT)) {
            return DIRT;
        }
        SoundType sound = state.getSoundType();
        if (sound == SoundType.DEEPSLATE || sound == SoundType.DEEPSLATE_BRICKS || sound == SoundType.DEEPSLATE_TILES
                || sound == SoundType.POLISHED_DEEPSLATE) {
            return DEEPSLATE;
        }
        if (sound == SoundType.WOOD || sound == SoundType.NETHER_WOOD || sound == SoundType.BAMBOO_WOOD
                || sound == SoundType.CHERRY_WOOD) {
            return WOOD;
        }
        if (sound == SoundType.METAL || sound == SoundType.COPPER || sound == SoundType.ANVIL
                || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.CHAIN) {
            return METAL;
        }
        if (sound == SoundType.NETHERRACK || sound == SoundType.NETHER_BRICKS || sound == SoundType.BASALT
                || sound == SoundType.NYLIUM || sound == SoundType.SOUL_SAND || sound == SoundType.SOUL_SOIL) {
            return NETHER_STONE;
        }
        if (sound == SoundType.SAND) {
            return SAND;
        }
        if (sound == SoundType.SNOW || sound == SoundType.POWDER_SNOW) {
            return SNOW;
        }
        if (sound == SoundType.GRAVEL || sound == SoundType.ROOTED_DIRT || sound == SoundType.MUD) {
            return DIRT;
        }
        if (sound == SoundType.GRASS || sound == SoundType.CROP || sound == SoundType.SWEET_BERRY_BUSH) {
            return GRASS;
        }
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || sound == SoundType.STONE || sound == SoundType.TUFF
                || sound == SoundType.CALCITE || sound == SoundType.DRIPSTONE_BLOCK) {
            return STONE;
        }
        return UNKNOWN;
    }
}
