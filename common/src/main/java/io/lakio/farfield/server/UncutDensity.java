package io.lakio.farfield.server;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A noise router density without its cave branches, for the surface search
 * of {@code docs/analysis/preview-to-the-border.md}: a {@code range_choice}
 * whose in-range branch is {@code min(input, ...)} only ever cuts into its
 * input (vanilla's overworld picks between the sloped terrain cut by cave
 * entrances and the underground caves this way), so it is replaced by its
 * input. The surface of what is left has no cave under it to fall into; the
 * caller checks the full density at the result.
 *
 * <p>{@code RangeChoice} and the two-argument functions are package-private
 * records; they are recognized by the shape of their components (types and
 * order, not names), which holds under every mapping.
 */
final class UncutDensity {

    private UncutDensity() {
    }

    /** What the last {@link #of} saw, for the spike: range choices, their in-range mins, and the first failure. */
    static volatile String seen = "";
    private static int choices;
    private static int mins;
    private static String failure;

    /** {@code root} without its cave branches, and how many were cut ({@code cut[0]}). */
    static DensityFunction of(DensityFunction root, int[] cut) {
        choices = 0;
        mins = 0;
        failure = null;
        Set<DensityFunction> counted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<DensityFunction, DensityFunction> done = new IdentityHashMap<>();
        return root.mapAll(f -> {
            DensityFunction known = done.get(f);
            if (known != null) {
                return known;
            }
            DensityFunction out = f;
            Object[] choice = rangeChoice(f);
            if (choice != null) {
                choices++;
                if (twoArguments(unwrap((DensityFunction) choice[3]), "min") != null) {
                    mins++;
                }
            }
            if (choice != null && cutsItsInput((DensityFunction) choice[0], (DensityFunction) choice[3])) {
                out = (DensityFunction) choice[0];
                if (counted.add(f)) {
                    cut[0]++;
                }
            }
            done.put(f, out);
            seen = choices + " range choices, " + mins + " with a min in range"
                    + (failure == null ? "" : ", failure: " + failure);
            return out;
        });
    }

    /** True when {@code inRange} is {@code min(input, x)} or {@code min(x, input)}. */
    private static boolean cutsItsInput(DensityFunction input, DensityFunction inRange) {
        Object[] min = twoArguments(unwrap(inRange), "min");
        if (min == null) {
            return false;
        }
        DensityFunction plain = unwrap(input);
        return plain.equals(unwrap((DensityFunction) min[1])) || plain.equals(unwrap((DensityFunction) min[2]));
    }

    /** The function a named reference ({@code HolderHolder}: one {@link Holder} component) stands for. */
    private static DensityFunction unwrap(DensityFunction f) {
        for (int depth = 0; depth < 16; depth++) {
            RecordComponent[] parts = f.getClass().getRecordComponents();
            if (parts == null || parts.length != 1 || !Holder.class.isAssignableFrom(parts[0].getType())) {
                return f;
            }
            Object[] values = values(f, parts);
            if (values == null || !(values[0] instanceof Holder<?> holder)
                    || !(holder.value() instanceof DensityFunction inner)) {
                return f;
            }
            f = inner;
        }
        return f;
    }

    /** The components of a {@code range_choice} (input, min, max, in range, out of range), or null. */
    private static Object[] rangeChoice(DensityFunction f) {
        RecordComponent[] parts = f.getClass().getRecordComponents();
        if (parts == null || parts.length != 5
                || parts[0].getType() != DensityFunction.class || parts[1].getType() != double.class
                || parts[2].getType() != double.class || parts[3].getType() != DensityFunction.class
                || parts[4].getType() != DensityFunction.class) {
            return null;
        }
        return values(f, parts);
    }

    /** The components (type, first, second, ...) of a two-argument function of this serialized type, or null. */
    private static Object[] twoArguments(DensityFunction f, String type) {
        RecordComponent[] parts = f.getClass().getRecordComponents();
        if (parts == null || parts.length < 3 || !parts[0].getType().isEnum()
                || !StringRepresentable.class.isAssignableFrom(parts[0].getType())
                || parts[1].getType() != DensityFunction.class || parts[2].getType() != DensityFunction.class) {
            return null;
        }
        Object[] values = values(f, parts);
        if (values == null || !(values[0] instanceof StringRepresentable named)
                || !type.equals(named.getSerializedName())) {
            return null;
        }
        return values;
    }

    private static Object[] values(Object record, RecordComponent[] parts) {
        Object[] out = new Object[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                Method accessor = parts[i].getAccessor();
                accessor.setAccessible(true);
                out[i] = accessor.invoke(record);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (failure == null) {
                failure = e.toString();
            }
            return null;
        }
        return out;
    }
}
