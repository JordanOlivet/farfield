package io.lakio.farfield.server;

import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.lod.BlockKeys;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a spike tile's feature time goes, per placed feature (the measure
 * of option 2, `docs/analysis/preview-coarse-to-fine.md`): its time without
 * the stand-in chunks made meanwhile, and what it set: plants (blocks the
 * LODs draw as plants, and plants under the sea), tree blocks (logs, leaves,
 * huge mushrooms, mangrove roots), or anything else; and whether any of it
 * lies where the preview keeps it (above the tile's ground, or in the top
 * blocks a lake digs): a feature that sets blocks only deeper (a geode, a
 * cave's lichen) costs time for nothing. One thread.
 */
final class FeatureCosts {

    private static final int NANOS = 0;
    private static final int CALLS = 1;
    private static final int PLANTS = 2;
    private static final int TREES = 3;
    private static final int OTHERS = 4;
    private static final int SEEN = 5;

    private final Map<String, long[]> features = new HashMap<>();
    /** The tile's surface at a block, the first free block above the ground. */
    private java.util.function.IntBinaryOperator ground = (x, z) -> Integer.MIN_VALUE;
    private int keptDepth;

    /** The surface blocks are compared with, and how deep under it the preview still keeps blocks. */
    void ground(java.util.function.IntBinaryOperator surface, int depth) {
        ground = surface;
        keptDepth = depth;
    }

    void ran(String feature, long nanos) {
        long[] c = features.computeIfAbsent(feature, k -> new long[6]);
        c[NANOS] += nanos;
        c[CALLS]++;
    }

    void set(String feature, int x, int y, int z, BlockState state) {
        long[] c = features.computeIfAbsent(feature, k -> new long[6]);
        c[kind(state)]++;
        if (y >= ground.applyAsInt(x, z) - keptDepth) {
            c[SEEN]++;
        }
    }

    private static int kind(BlockState state) {
        if (BlockKeys.isSubmergedPlant(state) || BlockKeys.meshClass(state) == MaterialClassifier.PLANT) {
            return PLANTS;
        }
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(Blocks.MANGROVE_ROOTS)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.MUSHROOM_STEM) || state.is(BlockTags.WART_BLOCKS)) {
            return TREES;
        }
        return OTHERS;
    }

    /** The kind of a feature by what it set: plants alone, trees, others, nothing. */
    private static String kindOf(long[] c) {
        if (c[PLANTS] + c[TREES] + c[OTHERS] > 0 && c[SEEN] == 0) {
            return "under the ground only";
        }
        if (c[TREES] > 0) {
            return "trees";
        }
        if (c[OTHERS] > 0) {
            return c[PLANTS] > 0 ? "others with plants" : "others";
        }
        return c[PLANTS] > 0 ? "plants only" : "nothing set";
    }

    /** Time and features per kind, then the costliest features. */
    String report(int top) {
        Map<String, long[]> kinds = new java.util.TreeMap<>();
        long total = 0;
        for (long[] c : features.values()) {
            long[] k = kinds.computeIfAbsent(kindOf(c), x -> new long[2]);
            k[0] += c[NANOS];
            k[1]++;
            total += c[NANOS];
        }
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "feature kinds of %d ms:", total / 1_000_000));
        for (var e : kinds.entrySet()) {
            out.append(String.format(Locale.ROOT, " %s %d ms (%d features, %.0f %%);", e.getKey(),
                    e.getValue()[0] / 1_000_000, e.getValue()[1], 100.0 * e.getValue()[0] / Math.max(1, total)));
        }
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(features.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue()[NANOS], a.getValue()[NANOS]));
        out.append(" costliest:");
        for (int i = 0; i < Math.min(top, sorted.size()); i++) {
            long[] c = sorted.get(i).getValue();
            out.append(String.format(Locale.ROOT, " %s %d ms (%s)%s", sorted.get(i).getKey(), c[NANOS] / 1_000_000,
                    kindOf(c), i + 1 < Math.min(top, sorted.size()) ? "," : ""));
        }
        return out.toString();
    }
}
