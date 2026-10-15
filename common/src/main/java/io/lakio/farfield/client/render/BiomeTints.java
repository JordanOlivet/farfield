package io.lakio.farfield.client.render;

import io.lakio.farfield.core.anvil.KeyRegistry;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Biome tints for the shader: per biome of an LOD world and per tint kind
 * (one kind per {@link ColorResolver} a block color asks for: grass, foliage
 * and water first, then those of mods), the tint in ABGR8. Slot
 * {@code biome id + 1}, slot 0 being the fallback biome for unknown ids,
 * as quads store them ({@code Quad#biome}). Render thread only.
 */
final class BiomeTints {

    static final int KINDS = 8;

    private final List<ColorResolver> resolvers = new ArrayList<>(List.of(
            BiomeColors.GRASS_COLOR_RESOLVER, BiomeColors.FOLIAGE_COLOR_RESOLVER, BiomeColors.WATER_COLOR_RESOLVER));
    private int[] table = new int[0];
    private int slots;
    private boolean dirty = true;

    /** The kind of a resolver, added if new; -1 when all kinds are taken (the caller bakes the color). */
    int kindOf(ColorResolver resolver) {
        int kind = resolvers.indexOf(resolver);
        if (kind < 0 && resolvers.size() < KINDS) {
            kind = resolvers.size();
            resolvers.add(resolver);
            dirty = true;
        }
        return kind;
    }

    /** Forgets the table (new level or resource reload); kinds stay, as materials refer to them. */
    void reset() {
        dirty = true;
    }

    /**
     * Rebuilds the table when biomes or kinds were added.
     *
     * @return true if the table changed and must be uploaded
     */
    boolean update(KeyRegistry biomes, Registry<Biome> registry, Biome fallback) {
        int size = biomes.size() + 1;
        if (!dirty && size == slots) {
            return false;
        }
        if (table.length < size * KINDS) {
            table = Arrays.copyOf(table, Math.max(size, slots * 2) * KINDS);
        }
        for (int slot = 0; slot < size; slot++) {
            Biome biome = slot == 0 ? null : lookup(registry, biomes.key(slot - 1));
            if (biome == null) {
                biome = fallback;
            }
            for (int kind = 0; kind < KINDS; kind++) {
                table[slot * KINDS + kind] = kind < resolvers.size() ? tint(resolvers.get(kind), biome) : 0xFFFFFFFF;
            }
        }
        slots = size;
        dirty = false;
        return true;
    }

    int[] table() {
        return table;
    }

    /** Valid entries of {@link #table()}. */
    int length() {
        return slots * KINDS;
    }

    private static Biome lookup(Registry<Biome> registry, String key) {
        ResourceLocation id = ResourceLocation.tryParse(key);
        return id == null ? null : registry.get(id);
    }

    private static int tint(ColorResolver resolver, Biome biome) {
        int rgb;
        try {
            rgb = resolver.getColor(biome, 0, 0);
        } catch (RuntimeException e) {
            rgb = 0xFFFFFF;
        }
        return MaterialColors.abgr(rgb, 0xFF);
    }
}
