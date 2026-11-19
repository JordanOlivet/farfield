package io.lakio.farfield.server;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.HashMap;
import java.util.Map;

/**
 * A noise router density evaluated point by point, with vanilla's cache
 * markers honored as {@code NoiseChunk} does: {@code flat_cache} and
 * {@code cache_2d} functions (continentalness, erosion, ridges, the terrain
 * splines) are computed once per column, {@code cache_once} ones once per
 * point. A plain {@link DensityFunction.SinglePointContext} evaluation
 * ignores the markers and recomputes all of them for every block the
 * preview's surface search reads in a column (ADR-057). Equal marked
 * functions share one cache, as in {@code NoiseChunk.wrap}: the shift
 * noises under the six climate functions are computed once per column.
 * Not thread-safe: one per thread.
 */
final class ColumnDensity {

    private ColumnDensity() {
    }

    /** {@code root} with its cache markers replaced by single-entry caches. */
    static DensityFunction of(DensityFunction root) {
        return mapped(root, new Caches());
    }

    /** The class names of the density functions C2ME compiles (its density function compiler, ADR-170). */
    private static final String C2ME_COMPILED = "com.ishland.c2me.opts.dfc.";
    private static volatile boolean c2meSaid;
    private static volatile boolean opaqueSaid;

    /**
     * The vanilla tree of a function C2ME compiled (ADR-170): its compiled
     * functions refuse a visitor that wraps their nodes ("Unsupported
     * transformation on Wrapping node"), and they keep the tree they were
     * compiled from ({@code getFallback}), which takes every visitor. Any
     * other function is returned as it is.
     */
    static DensityFunction vanilla(DensityFunction f) {
        for (int depth = 0; depth < 8 && f != null && f.getClass().getName().startsWith(C2ME_COMPILED); depth++) {
            DensityFunction tree = fallback(f);
            if (tree == null || tree == f) {
                break;
            }
            if (!c2meSaid) {
                c2meSaid = true;
                io.lakio.farfield.Farfield.LOG.info("Noise preview: C2ME's compiled density functions read through"
                        + " their original trees (ADR-170)");
            }
            f = tree;
        }
        return f;
    }

    private static DensityFunction fallback(DensityFunction f) {
        for (Class<?> c = f.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("getFallback");
                m.setAccessible(true);
                Object tree = m.invoke(f);
                return tree instanceof DensityFunction d ? d : null;
            } catch (NoSuchMethodException e) {
                // up the hierarchy
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * {@code f} (its vanilla tree, {@link #vanilla}) mapped by {@code visitor};
     * a function that still refuses it is read as it is, without the caches:
     * slower, never wrong.
     */
    static DensityFunction mapped(DensityFunction f, DensityFunction.Visitor visitor) {
        DensityFunction tree = vanilla(f);
        try {
            return tree.mapAll(visitor);
        } catch (RuntimeException e) {
            if (!opaqueSaid) {
                opaqueSaid = true;
                io.lakio.farfield.Farfield.LOG.warn("Noise preview: a density function ({}) takes no column caches,"
                        + " read as it is: {}", f.getClass().getName(), String.valueOf(e));
            }
            return f;
        }
    }

    /**
     * The {@code roots} with one set of caches: the 2D functions they share
     * (the terrain splines under the final density, its uncut copy and the
     * initial density) are computed once per column for all of them, not
     * once per root. The surface search reads the three at the same column
     * in turn, so the single-entry caches hold.
     */
    static DensityFunction[] of(DensityFunction... roots) {
        Caches caches = new Caches();
        DensityFunction[] out = new DensityFunction[roots.length];
        for (int i = 0; i < roots.length; i++) {
            out[i] = mapped(roots[i], caches);
        }
        return out;
    }

    /** A climate sampler whose six functions share their caches. */
    static Climate.Sampler of(Climate.Sampler sampler) {
        Caches caches = new Caches();
        return new Climate.Sampler(mapped(sampler.temperature(), caches), mapped(sampler.humidity(), caches),
                mapped(sampler.continentalness(), caches), mapped(sampler.erosion(), caches),
                mapped(sampler.depth(), caches), mapped(sampler.weirdness(), caches), sampler.spawnTarget());
    }

    /** Replaces cache markers, one cache per distinct marked function (records compare by value). */
    private static final class Caches implements DensityFunction.Visitor {

        private final Map<DensityFunction, DensityFunction> caches = new HashMap<>();

        @Override
        public DensityFunction apply(DensityFunction f) {
            if (f instanceof DensityFunctions.MarkerOrMarked marker) {
                String type = ((StringRepresentable) (Object) marker.type()).getSerializedName();
                if (type.equals("flat_cache") || type.equals("cache_2d")) {
                    return caches.computeIfAbsent(f, k -> new Cached(marker.wrapped(), true));
                }
                if (type.equals("cache_once")) {
                    return caches.computeIfAbsent(f, k -> new Cached(marker.wrapped(), false));
                }
            }
            return f;
        }
    }

    /** The last value of a function, by column ({@code column}) or by point. */
    private static final class Cached implements DensityFunction {

        private final DensityFunction wrapped;
        private final boolean column;
        private boolean valid;
        private int lastX;
        private int lastY;
        private int lastZ;
        private double value;

        Cached(DensityFunction wrapped, boolean column) {
            this.wrapped = wrapped;
            this.column = column;
        }

        @Override
        public double compute(FunctionContext context) {
            int x = context.blockX();
            int y = column ? 0 : context.blockY();
            int z = context.blockZ();
            if (valid && x == lastX && y == lastY && z == lastZ) {
                return value;
            }
            value = wrapped.compute(context);
            lastX = x;
            lastY = y;
            lastZ = z;
            valid = true;
            return value;
        }

        @Override
        public void fillArray(double[] values, ContextProvider provider) {
            provider.fillAllDirectly(values, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new Cached(wrapped.mapAll(visitor), column));
        }

        //? if >=26.2 {
        /*// Since 26.2 every function maps its direct children; mapAll recurses through them.
        @Override
        public DensityFunction mapChildren(Visitor visitor) {
            return new Cached(visitor.apply(wrapped), column);
        }
        *///?}

        @Override
        public double minValue() {
            return wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return wrapped.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return wrapped.codec();
        }
    }
}
