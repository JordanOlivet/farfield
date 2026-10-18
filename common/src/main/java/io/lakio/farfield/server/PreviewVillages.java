package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

/**
 * Where villages will generate, for the noise preview (ADR-041): the
 * world's structure sets with village structures and a random spread
 * placement (vanilla's villages, and modded sets built the same way), their
 * candidate chunk per region with the game's own frequency and exclusion
 * rules, kept when a village of the set accepts the biome there. The
 * village's houses are not the game's (no jigsaw): {@link #houses} lays out
 * a few approximate ones around the center.
 */
final class PreviewVillages {

    /** Village styles, from the structure's name. */
    static final int PLAINS = 0;
    static final int DESERT = 1;
    static final int SAVANNA = 2;
    static final int TAIGA = 3;
    static final int SNOWY = 4;

    /** A village to come: its center block and style. */
    record Village(int x, int z, int style) {
    }

    /** A house laid out by {@link #houses}: corner, size, floor and the top of its walls (its roof row). */
    record House(int x, int z, int width, int depth, int floor, int top) {
    }

    private record Set(RandomSpreadStructurePlacement placement, List<Holder<Structure>> villages) {
    }

    /** Houses stay within this many blocks of the village center. */
    static final int RADIUS = 56;

    private final ChunkGeneratorStructureState state;
    private final List<Set> sets = new ArrayList<>();

    PreviewVillages(ServerLevel level) {
        state = level.getChunkSource().getGeneratorState();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            if (!(set.value().placement() instanceof RandomSpreadStructurePlacement placement)) {
                continue;
            }
            List<Holder<Structure>> villages = new ArrayList<>();
            for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
                if (entry.structure().is(StructureTags.VILLAGE)) {
                    villages.add(entry.structure());
                }
            }
            if (!villages.isEmpty()) {
                sets.add(new Set(placement, villages));
            }
        }
        Farfield.LOG.info("Noise preview of {}: {} village structure sets", level.dimension().location(), sets.size());
    }

    /**
     * The villages whose center lies in blocks [x0, x1] x [z0, z1].
     *
     * @param biomeAt the biome at the surface of a block column (x, z)
     */
    List<Village> within(int x0, int z0, int x1, int z1, BiomeAt biomeAt) {
        List<Village> out = new ArrayList<>();
        for (Set set : sets) {
            int spacing = set.placement.spacing();
            for (int rz = Math.floorDiv(z0 >> 4, spacing); rz <= Math.floorDiv(z1 >> 4, spacing); rz++) {
                for (int rx = Math.floorDiv(x0 >> 4, spacing); rx <= Math.floorDiv(x1 >> 4, spacing); rx++) {
                    try {
                        ChunkPos chunk = set.placement.getPotentialStructureChunk(state.getLevelSeed(), rx * spacing,
                                rz * spacing);
                        int x = chunk.getMiddleBlockX();
                        int z = chunk.getMiddleBlockZ();
                        if (x < x0 || x > x1 || z < z0 || z > z1
                                || !set.placement.isStructureChunk(state, chunk.x, chunk.z)) {
                            continue;
                        }
                        Holder<Biome> biome = biomeAt.biome(x, z);
                        for (Holder<Structure> village : set.villages) {
                            if (village.value().biomes().contains(biome)) {
                                out.add(new Village(x, z, style(village)));
                                break;
                            }
                        }
                    } catch (RuntimeException e) {
                        // A placement the preview cannot evaluate: no village.
                    }
                }
            }
        }
        return out;
    }

    /** The biome at the surface of a block column. */
    @FunctionalInterface
    interface BiomeAt {
        Holder<Biome> biome(int x, int z);
    }

    private static int style(Holder<Structure> village) {
        String name = village.unwrapKey().map(k -> k.location().getPath()).orElse("");
        if (name.contains("desert")) {
            return DESERT;
        }
        if (name.contains("savanna")) {
            return SAVANNA;
        }
        if (name.contains("snowy")) {
            return SNOWY;
        }
        if (name.contains("taiga")) {
            return TAIGA;
        }
        return PLAINS;
    }

    /**
     * Up to ten to seventeen houses around the center, the same for every tile
     * that asks (seeded by the world and the center), apart from each other,
     * on land: a floor one block under the lowest corner, walls four to five
     * blocks over the center's ground.
     *
     * @param surface the first free block above the ground of a column (x, z)
     */
    static List<House> houses(Village v, long seed, int seaLevel, IntBinaryOperator surface) {
        java.util.Random random = new java.util.Random(seed ^ v.x() * 341873128712L ^ v.z() * 132897987541L ^ 0x5EED);
        int n = 10 + random.nextInt(8);
        List<House> houses = new ArrayList<>();
        // Many tries: a center by a lake loses the spots in the water and spreads along the shore.
        for (int attempt = 0; attempt < n * 8 && houses.size() < n; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int distance = attempt == 0 ? 0 : 8 + random.nextInt(RADIUS - 18);
            int width = 5 + random.nextInt(5);
            int depth = 5 + random.nextInt(5);
            int x = v.x() + (int) Math.round(Math.cos(angle) * distance) - width / 2;
            int z = v.z() + (int) Math.round(Math.sin(angle) * distance) - depth / 2;
            boolean overlaps = false;
            for (House h : houses) {
                if (x < h.x() + h.width() + 2 && h.x() < x + width + 2 && z < h.z() + h.depth() + 2 && h.z() < z + depth + 2) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue;
            }
            int center = surface.applyAsInt(x + width / 2, z + depth / 2);
            int low = Math.min(Math.min(surface.applyAsInt(x, z), surface.applyAsInt(x + width - 1, z)),
                    Math.min(surface.applyAsInt(x, z + depth - 1), surface.applyAsInt(x + width - 1, z + depth - 1)));
            if (center <= seaLevel || center - low > 6) {
                continue; // in water, or on a slope too steep for a house
            }
            houses.add(new House(x, z, width, depth, low - 1, center + 4 + random.nextInt(2)));
        }
        return houses;
    }
}
