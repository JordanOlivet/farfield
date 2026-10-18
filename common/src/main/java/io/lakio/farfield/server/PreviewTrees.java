package io.lakio.farfield.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.lakio.farfield.Farfield;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The trees of each biome for the noise preview (ADR-040), read from its
 * vegetal decoration features: the tree features reached through random
 * selectors, with their trunk and leaves blocks, average trunk height and
 * crown size, and how many grow per chunk on average from the placement
 * modifiers (count, rarity, noise counts; read through their codecs, so
 * modded biomes using the vanilla modifiers work too). Not where they grow:
 * the preview places them at random, at that density.
 */
final class PreviewTrees {

    /** A tree as the preview draws it. */
    record Spec(BlockState trunk, BlockState leaves, int height, int radius, int crownHeight, boolean cone) {
    }

    /** The trees of a biome: each spec with its average count per chunk. */
    record BiomeTrees(List<Spec> specs, double[] perChunk, double total) {
        static final BiomeTrees NONE = new BiomeTrees(List.of(), new double[0], 0);

        /** A spec drawn by weight, {@code r} in [0, 1). */
        Spec pick(double r) {
            double x = r * total;
            for (int i = 0; i < specs.size(); i++) {
                x -= perChunk[i];
                if (x < 0) {
                    return specs.get(i);
                }
            }
            return specs.get(specs.size() - 1);
        }
    }

    private static final int MAX_DEPTH = 4;
    private final Map<Holder<Biome>, BiomeTrees> cache = new ConcurrentHashMap<>();

    BiomeTrees of(Holder<Biome> biome) {
        return cache.computeIfAbsent(biome, PreviewTrees::read);
    }

    private static BiomeTrees read(Holder<Biome> biome) {
        List<HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
        int vegetal = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (steps.size() <= vegetal) {
            return BiomeTrees.NONE;
        }
        Map<Spec, Double> counts = new LinkedHashMap<>();
        for (Holder<PlacedFeature> placed : steps.get(vegetal)) {
            try {
                double n = expectedCount(placed.value().placement());
                if (n > 0) {
                    collect(placed.value().feature().value(), n, counts, 0);
                }
            } catch (RuntimeException e) {
                // A feature the preview cannot read: left out.
            }
        }
        if (counts.isEmpty()) {
            return BiomeTrees.NONE;
        }
        List<Spec> specs = new ArrayList<>(counts.keySet());
        double[] perChunk = new double[specs.size()];
        double total = 0;
        for (int i = 0; i < specs.size(); i++) {
            perChunk[i] = counts.get(specs.get(i));
            total += perChunk[i];
        }
        BiomeTrees trees = new BiomeTrees(specs, perChunk, total);
        Farfield.LOG.info("Noise preview: {} grows {} trees per chunk of {} kinds ({})",
                biome.unwrapKey().map(k -> k.location().toString()).orElse("?"), String.format(java.util.Locale.ROOT, "%.2f", total),
                specs.size(), specs.get(0).trunk().getBlock().getName().getString());
        return trees;
    }

    /** Adds the trees a configured feature places, {@code weight} times on average per chunk. */
    private static void collect(ConfiguredFeature<?, ?> feature, double weight, Map<Spec, Double> out, int depth) {
        if (depth > MAX_DEPTH || weight <= 0) {
            return;
        }
        FeatureConfiguration config = feature.config();
        if (feature.feature() instanceof TreeFeature && config instanceof TreeConfiguration tree) {
            Spec spec = spec(tree);
            if (spec != null) {
                out.merge(spec, weight, Double::sum);
            }
        } else if (config instanceof RandomFeatureConfiguration random) {
            // The first entry whose chance passes, else the default.
            double left = 1;
            for (WeightedPlacedFeature w : random.features) {
                double p = left * w.chance;
                collect(w.feature.value().feature().value(), weight * p, out, depth + 1);
                left -= p;
            }
            collect(random.defaultFeature.value().feature().value(), weight * left, out, depth + 1);
        } else if (config instanceof SimpleRandomFeatureConfiguration random) {
            int n = random.features.size();
            for (Holder<PlacedFeature> f : random.features) {
                collect(f.value().feature().value(), weight / n, out, depth + 1);
            }
        } else if (config instanceof RandomBooleanFeatureConfiguration random) {
            collect(random.featureTrue.value().feature().value(), weight / 2, out, depth + 1);
            collect(random.featureFalse.value().feature().value(), weight / 2, out, depth + 1);
        }
    }

    private static Spec spec(TreeConfiguration tree) {
        try {
            RandomSource random = RandomSource.create(42);
            BlockState trunk = tree.trunkProvider.getState(random, BlockPos.ZERO);
            BlockState leaves = tree.foliageProvider.getState(random, BlockPos.ZERO);
            int samples = 8;
            int height = 0;
            int radius = 0;
            int crown = 0;
            for (int i = 0; i < samples; i++) {
                int h = tree.trunkPlacer.getTreeHeight(random);
                height += h;
                radius += tree.foliagePlacer.foliageRadius(random, h);
                crown += tree.foliagePlacer.foliageHeight(random, h, tree);
            }
            String placer = tree.foliagePlacer.getClass().getSimpleName();
            boolean cone = placer.contains("Spruce") || placer.contains("Pine");
            int crownHeight = clamp(Math.round(crown / (float) samples), 0, 16);
            int crownRadius = Math.round(radius / (float) samples);
            if (cone) {
                // Mega pines start their foliage at radius 0 and widen it down the trunk.
                crownRadius = Math.max(crownRadius, (crownHeight + 2) / 4);
            }
            return new Spec(trunk, leaves, clamp(Math.round(height / (float) samples), 1, 40), clamp(crownRadius, 0, 6),
                    crownHeight, cone);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** The average number of positions placement modifiers give per chunk, from their codec form. */
    static double expectedCount(List<PlacementModifier> modifiers) {
        double n = 1;
        for (PlacementModifier m : modifiers) {
            JsonElement json = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, m).result().orElse(null);
            if (!(json instanceof JsonObject o) || !o.has("type")) {
                continue;
            }
            switch (o.get("type").getAsString()) {
                case "minecraft:count", "minecraft:count_on_every_layer" -> n *= mean(o.get("count"));
                case "minecraft:rarity_filter" -> n /= Math.max(1, o.get("chance").getAsDouble());
                case "minecraft:noise_threshold_count" ->
                        n *= (o.get("below_noise").getAsDouble() + o.get("above_noise").getAsDouble()) / 2;
                case "minecraft:noise_based_count" -> n *= o.get("noise_to_count_ratio").getAsDouble() / 2;
                default -> {
                    // Filters and positions: in_square, heightmap, biome, surface_water_depth_filter...
                }
            }
        }
        return n;
    }

    /** The mean of an int provider in its codec form. */
    private static double mean(JsonElement e) {
        if (e == null) {
            return 1;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsDouble();
        }
        if (!e.isJsonObject()) {
            return 1;
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("min_inclusive") && o.has("max_inclusive")) {
            return (o.get("min_inclusive").getAsDouble() + o.get("max_inclusive").getAsDouble()) / 2;
        }
        if (o.has("distribution") && o.get("distribution").isJsonArray()) {
            JsonArray d = o.getAsJsonArray("distribution");
            double sum = 0;
            double weights = 0;
            for (JsonElement entry : d) {
                JsonObject w = entry.getAsJsonObject();
                double weight = w.has("weight") ? w.get("weight").getAsDouble() : 1;
                sum += mean(w.get("data")) * weight;
                weights += weight;
            }
            return weights > 0 ? sum / weights : 1;
        }
        if (o.has("source")) {
            return mean(o.get("source"));
        }
        if (o.has("value")) {
            return mean(o.get("value"));
        }
        return 1;
    }
}
