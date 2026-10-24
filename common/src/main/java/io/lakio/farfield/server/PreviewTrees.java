package io.lakio.farfield.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.preview.PreviewTile;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.BambooFeature;
import net.minecraft.world.level.levelgen.feature.HugeBrownMushroomFeature;
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
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
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.MegaPineFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.PineFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.SpruceFoliagePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The trees and plants of each biome for the noise preview (ADR-040,
 * ADR-041), read from its vegetal decoration features: tree features
 * reached through random selectors and patches, with their trunk and leaves
 * blocks, average trunk height and crown size; huge mushrooms (stem and
 * cap); bamboo, block columns (cactus) and single blocks (pumpkins) as
 * columns. Single blocks without a collision that LODs draw as plants
 * (grass, flowers, ferns: {@code BlockKeys}, ADR-059) are kept apart with
 * their count per chunk (ADR-060). How many grow per chunk on average comes from
 * the placement modifiers (count, rarity, noise counts; read through their
 * codecs, so modded biomes using the vanilla modifiers work too). Not where
 * they grow: the preview places them at random, at that density, on the
 * ground each one needs.
 */
final class PreviewTrees {

    /** The ground a plant grows on in the preview. */
    enum Ground {
        /** Grass or snow: trees, bamboo, pumpkins. */
        SOIL,
        /** Grass or mycelium: huge mushrooms. */
        FUNGAL,
        /** Sand: cactus. */
        SAND
    }

    /** A tree or plant as the preview draws it; {@code leaves} is null for columns. */
    record Spec(BlockState trunk, BlockState leaves, int height, int radius, int crownHeight, int shape,
                Ground ground) {
    }

    /** Patches try many spots around one; this share of the tries takes. */
    private static final double PATCH_SUCCESS = 0.15;
    /**
     * The share for plants: grass and flowers only need a free block on
     * grass, which most tries of a patch on a meadow find.
     */
    private static final double PLANT_PATCH_SUCCESS = 0.8;

    /** The trees of a biome: each spec with its average count per chunk; then its plants, the same way. */
    record BiomeTrees(List<Spec> specs, double[] perChunk, double total, List<BlockState> plants,
                      double[] plantsPerChunk) {
        static final BiomeTrees NONE = new BiomeTrees(List.of(), new double[0], 0, List.of(), new double[0]);

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
        Map<BlockState, Double> plantCounts = new LinkedHashMap<>();
        for (Holder<PlacedFeature> placed : steps.get(vegetal)) {
            try {
                double n = expectedCount(placed.value().placement());
                if (n > 0) {
                    collect(placed.value().feature().value(), n, counts, plantCounts, 0, 1);
                }
            } catch (RuntimeException e) {
                // A feature the preview cannot read: left out.
            }
        }
        if (counts.isEmpty() && plantCounts.isEmpty()) {
            return BiomeTrees.NONE;
        }
        List<Spec> specs = new ArrayList<>(counts.keySet());
        double[] perChunk = new double[specs.size()];
        double total = 0;
        for (int i = 0; i < specs.size(); i++) {
            perChunk[i] = counts.get(specs.get(i));
            total += perChunk[i];
        }
        List<BlockState> plants = new ArrayList<>(plantCounts.keySet());
        double[] plantsPerChunk = new double[plants.size()];
        double plantTotal = 0;
        for (int i = 0; i < plants.size(); i++) {
            plantsPerChunk[i] = plantCounts.get(plants.get(i));
            plantTotal += plantsPerChunk[i];
        }
        BiomeTrees trees = new BiomeTrees(specs, perChunk, total, plants, plantsPerChunk);
        Farfield.LOG.info("Noise preview: {} grows {} trees per chunk of {} kinds, {} plants of {} kinds",
                biome.unwrapKey().map(k -> k.location().toString()).orElse("?"),
                String.format(java.util.Locale.ROOT, "%.2f", total), specs.size(),
                String.format(java.util.Locale.ROOT, "%.1f", plantTotal), plants.size());
        return trees;
    }

    /**
     * Adds the trees a configured feature places, {@code weight} times on
     * average per chunk, and its plants into {@code plants}, their weight
     * times {@code plantScale} (patches count tries of plants as more successful).
     */
    private static void collect(ConfiguredFeature<?, ?> feature, double weight, Map<Spec, Double> out,
                                Map<BlockState, Double> plants, int depth, double plantScale) {
        if (depth > MAX_DEPTH || weight <= 0) {
            return;
        }
        FeatureConfiguration config = feature.config();
        if (feature.feature() instanceof TreeFeature && config instanceof TreeConfiguration tree) {
            add(spec(tree), weight, out);
        } else if (config instanceof HugeMushroomFeatureConfiguration mushroom) {
            add(mushroom(mushroom, feature.feature() instanceof HugeBrownMushroomFeature), weight, out);
        } else if (feature.feature() instanceof BambooFeature) {
            add(column(Blocks.BAMBOO.defaultBlockState(), 12), weight, out);
        } else if (config instanceof BlockColumnConfiguration column && !column.layers().isEmpty()) {
            RandomSource random = RandomSource.create(42);
            int height = 0;
            for (BlockColumnConfiguration.Layer layer : column.layers()) {
                height += Math.round((layer.height().getMinValue() + layer.height().getMaxValue()) / 2f);
            }
            BlockState block = column.layers().get(column.layers().size() - 1).state().getState(random, BlockPos.ZERO);
            add(column(block, height), weight, out);
        } else if (config instanceof SimpleBlockConfiguration block) {
            BlockState state = block.toPlace().getState(RandomSource.create(42), BlockPos.ZERO);
            if (isPlant(state)) {
                plants.merge(state, weight * plantScale, Double::sum);
            } else {
                add(column(state, 1), weight, out);
            }
        } else if (config instanceof RandomPatchConfiguration patch) {
            collect(patch.feature().value().feature().value(), weight * patch.tries() * PATCH_SUCCESS, out, plants,
                    depth + 1, plantScale * PLANT_PATCH_SUCCESS / PATCH_SUCCESS);
        } else if (config instanceof RandomFeatureConfiguration random) {
            // The first entry whose chance passes, else the default.
            double left = 1;
            for (WeightedPlacedFeature w : random.features) {
                double p = left * w.chance;
                collect(w.feature.value().feature().value(), weight * p, out, plants, depth + 1, plantScale);
                left -= p;
            }
            collect(random.defaultFeature.value().feature().value(), weight * left, out, plants, depth + 1, plantScale);
        } else if (config instanceof SimpleRandomFeatureConfiguration random) {
            int n = random.features.size();
            for (Holder<PlacedFeature> f : random.features) {
                collect(f.value().feature().value(), weight / n, out, plants, depth + 1, plantScale);
            }
        } else if (config instanceof RandomBooleanFeatureConfiguration random) {
            collect(random.featureTrue.value().feature().value(), weight / 2, out, plants, depth + 1, plantScale);
            collect(random.featureFalse.value().feature().value(), weight / 2, out, plants, depth + 1, plantScale);
        }
    }

    /** Whether LODs draw a block as a plant: no collision, a bush or sugar cane (as {@code BlockKeys}). */
    private static boolean isPlant(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                    && (state.getBlock() instanceof net.minecraft.world.level.block.BushBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.SugarCaneBlock);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Adds a spec, unless it is missing or its trunk has no collision (not drawn in LODs). */
    private static void add(Spec spec, double weight, Map<Spec, Double> out) {
        if (spec == null) {
            return;
        }
        try {
            if (spec.trunk().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                return;
            }
        } catch (RuntimeException e) {
            return;
        }
        out.merge(spec, weight, Double::sum);
    }

    private static Spec column(BlockState block, int height) {
        Ground ground = block.is(Blocks.CACTUS) ? Ground.SAND : Ground.SOIL;
        return new Spec(block, null, clamp(height, 1, 24), 0, 0, PreviewTile.COLUMN, ground);
    }

    /** Red caps as rounded domes over a 5-block stem, brown ones as one wide layer. */
    private static Spec mushroom(HugeMushroomFeatureConfiguration config, boolean brown) {
        try {
            RandomSource random = RandomSource.create(42);
            BlockState stem = config.stemProvider.getState(random, BlockPos.ZERO);
            BlockState cap = config.capProvider.getState(random, BlockPos.ZERO);
            int radius = clamp(config.foliageRadius + (brown ? 1 : 0), 1, 6);
            return new Spec(stem, cap, brown ? 4 : 5, radius, brown ? 0 : 3,
                    brown ? PreviewTile.FLAT : PreviewTile.BLOB, Ground.FUNGAL);
        } catch (RuntimeException e) {
            return null;
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
            // Vanilla classes by type (Fabric runs them under intermediary names), modded ones by name.
            FoliagePlacer foliage = tree.foliagePlacer;
            String placer = foliage.getClass().getSimpleName();
            boolean spruce = foliage instanceof SpruceFoliagePlacer || placer.contains("Spruce");
            boolean cone = spruce || foliage instanceof PineFoliagePlacer || foliage instanceof MegaPineFoliagePlacer
                    || placer.contains("Pine");
            int crownHeight = clamp(Math.round(crown / (float) samples), 0, 16);
            int crownRadius = Math.round(radius / (float) samples);
            if (cone) {
                // Mega pines start their foliage at radius 0 and widen it down the trunk.
                crownRadius = Math.max(crownRadius, (crownHeight + 2) / 4);
            }
            if (foliage instanceof PineFoliagePlacer) {
                // Vanilla's pine widens one block per row from its top: its rows bound its radius.
                crownRadius = Math.min(crownRadius, Math.max(1, crownHeight - 1));
            }
            return new Spec(trunk, leaves, clamp(Math.round(height / (float) samples), 1, 40), clamp(crownRadius, 0, 6),
                    crownHeight, spruce ? PreviewTile.TIERED : cone ? PreviewTile.CONE : PreviewTile.BLOB, Ground.SOIL);
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
