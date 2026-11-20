package io.lakio.farfield.server;

import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/**
 * Minecraft 26.3 (ADR-171): the cut of the cave branches (ADR-133) walks the
 * density tree, which 26.3 rewrites through {@code rewriteChildren} and new
 * node classes. Not ported yet: the surface is searched on the whole
 * density, caves included, and the log says no branch was cut.
 */
final class UncutDensity {

    /** The density functions seen while cutting, for the log. */
    static volatile String seen = "not cut on 26.3 yet";

    private UncutDensity() {
    }

    static DensityFunction of(DensityFunction root, int[] cut) {
        cut[0] = 0;
        return root;
    }
}
