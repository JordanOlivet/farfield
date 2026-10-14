package io.lakio.farfield.client.render;

import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.server.BlockKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * One color per block-state id of an LOD world, for the shader (ABGR8):
 * the block's map color for now (ADR-010 wants averaged texture colors and
 * biome tints later). Render thread only.
 */
final class MaterialColors {

    private static final int FALLBACK = abgr(0x808080);

    private int[] colors = new int[0];
    private int count;

    /** Colors for ids {@code count .. registry.size() - 1}; returns the first new id, or -1 if none. */
    int update(KeyRegistry registry) {
        int size = registry.size();
        if (size == count) {
            return -1;
        }
        if (colors.length < size) {
            colors = Arrays.copyOf(colors, Math.max(size, colors.length * 2));
        }
        int first = count;
        for (int id = count; id < size; id++) {
            colors[id] = colorOf(registry.key(id));
        }
        count = size;
        return first;
    }

    int[] colors() {
        return colors;
    }

    int count() {
        return count;
    }

    private static int colorOf(String key) {
        BlockState state = BlockKeys.state(key);
        if (state == null) {
            return FALLBACK;
        }
        try {
            MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return color == null || color == MapColor.NONE ? FALLBACK : abgr(color.col);
        } catch (RuntimeException e) {
            return FALLBACK;
        }
    }

    private static int abgr(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0xFF000000 | b << 16 | g << 8 | r;
    }
}
