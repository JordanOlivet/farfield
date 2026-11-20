package io.lakio.farfield.server;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;

/**
 * Minecraft 26.3 (ADR-171): the final density without its cave branches, as
 * up to 26.2 (ADR-133): vanilla's overworld picks with a range choice on its
 * terrain between that terrain cut by cave entrances ({@code min(terrain,
 * entrances)}) and the underground caves; such a choice is replaced by the
 * side of its {@code min} that holds its input. 26.3's nodes are public
 * records ({@link RangeChoiceFunction}, {@link BinaryFunction}), walked
 * through {@code rewriteChildren} with the registry's references inlined,
 * as {@code mapAll} walked into them up to 26.2.
 */
final class UncutDensity {

    private UncutDensity() {
    }

    /** What the last {@link #of} saw, for the spike: range choices and their in-range mins. */
    static volatile String seen = "";

    /** {@code root} without its cave branches, and how many were cut ({@code cut[0]}). */
    static DensityFunction of(DensityFunction root, int[] cut) {
        int[] choices = new int[2];
        Map<DensityFunction, DensityFunction> done = new IdentityHashMap<>();
        DfRewriteRule rule = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction function) {
                DensityFunction known = done.get(function);
                if (known != null) {
                    return known;
                }
                DensityFunction f = unwrap(function);
                DensityFunction out = f;
                if (f instanceof RangeChoiceFunction choice) {
                    choices[0]++;
                    if (min(choice.whenInRange()) != null) {
                        choices[1]++;
                    }
                    DensityFunction terrain = terrainOf(choice.input(), choice.whenInRange());
                    if (terrain != null) {
                        out = terrain;
                        cut[0]++;
                    }
                }
                out = out.rewriteChildren(this);
                done.put(function, out);
                return out;
            }
        };
        DensityFunction result = rule.rewrite(root);
        seen = choices[0] + " range choices, " + choices[1] + " with a min in range";
        return result;
    }

    /**
     * The terrain a cave branch cuts: when {@code inRange} is {@code min(a, b)}
     * and only one of {@code a} and {@code b} holds {@code input}, that one;
     * else null.
     */
    private static DensityFunction terrainOf(DensityFunction input, DensityFunction inRange) {
        BinaryFunction min = min(inRange);
        if (min == null) {
            return null;
        }
        DensityFunction plain = unwrap(input);
        boolean first = holds(min.left(), plain);
        boolean second = holds(min.right(), plain);
        return first == second ? null : first ? min.left() : min.right();
    }

    private static BinaryFunction min(DensityFunction f) {
        return unwrap(f) instanceof BinaryFunction b && b.type() == BinaryFunction.Type.MIN ? b : null;
    }

    /** True when {@code target} is {@code f} or one of the functions under it, references inlined. */
    private static boolean holds(DensityFunction f, DensityFunction target) {
        boolean[] found = {false};
        Map<DensityFunction, Boolean> seenNodes = new IdentityHashMap<>();
        DfRewriteRule walk = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction function) {
                DensityFunction g = unwrap(function);
                if (found[0] || seenNodes.put(g, Boolean.TRUE) != null) {
                    return function;
                }
                if (g.equals(target)) {
                    found[0] = true;
                    return function;
                }
                g.rewriteChildren(this);
                return function;
            }
        };
        walk.rewrite(f);
        return found[0];
    }

    /** The function a named reference stands for. */
    private static DensityFunction unwrap(DensityFunction f) {
        for (int depth = 0; depth < 16 && f instanceof DensityFunctions.HolderHolder holder; depth++) {
            f = holder.function().value();
        }
        return f;
    }
}
