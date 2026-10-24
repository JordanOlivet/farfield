package io.lakio.farfield.server;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * A noise router density evaluated point by point, with vanilla's cache
 * markers honored as {@code NoiseChunk} does: {@code flat_cache} and
 * {@code cache_2d} functions (continentalness, erosion, ridges, the terrain
 * splines) are computed once per column, {@code cache_once} ones once per
 * point. A plain {@link DensityFunction.SinglePointContext} evaluation
 * ignores the markers and recomputes all of them for every block the
 * preview's surface search reads in a column (ADR-057). Not thread-safe:
 * one per thread.
 */
final class ColumnDensity {

    private ColumnDensity() {
    }

    /** {@code root} with its cache markers replaced by single-entry caches. */
    static DensityFunction of(DensityFunction root) {
        return root.mapAll(f -> {
            if (f instanceof DensityFunctions.MarkerOrMarked marker) {
                String type = ((StringRepresentable) (Object) marker.type()).getSerializedName();
                if (type.equals("flat_cache") || type.equals("cache_2d")) {
                    return new Cached(marker.wrapped(), true);
                }
                if (type.equals("cache_once")) {
                    return new Cached(marker.wrapped(), false);
                }
            }
            return f;
        });
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
