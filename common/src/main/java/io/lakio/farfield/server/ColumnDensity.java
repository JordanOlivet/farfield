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
        return root.mapAll(new Caches());
    }

    /** A climate sampler whose six functions share their caches. */
    static Climate.Sampler of(Climate.Sampler sampler) {
        Caches caches = new Caches();
        return new Climate.Sampler(sampler.temperature().mapAll(caches), sampler.humidity().mapAll(caches),
                sampler.continentalness().mapAll(caches), sampler.erosion().mapAll(caches),
                sampler.depth().mapAll(caches), sampler.weirdness().mapAll(caches), sampler.spawnTarget());
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
