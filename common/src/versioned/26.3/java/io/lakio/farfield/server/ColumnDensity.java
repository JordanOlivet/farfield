package io.lakio.farfield.server;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;

/**
 * Minecraft 26.3 (ADR-171): the world compiles its density functions into
 * samplers, and a sampler context with caches keeps their flat and 2D
 * values per column itself, which this class did by hand up to 26.2
 * (ADR-057). One context per thread, shared by the densities the surface
 * search reads in turn, as one set of caches was.
 */
final class ColumnDensity {

    private ColumnDensity() {
    }

    static PointDensity[] points(RandomState state, DensityFunction... roots) {
        SamplerContext context = SamplerContext.builder().enableCaches().build();
        PointDensity[] points = new PointDensity[roots.length];
        for (int i = 0; i < roots.length; i++) {
            DensitySampler sampler = state.getSampler(pointwise(roots[i]));
            points[i] = (x, y, z) -> sampler.sampleValue(context, x, y, z);
        }
        return points;
    }

    /**
     * {@code root} read at single blocks: its interpolation nodes give way to
     * the functions they interpolate. A 26.3 interpolation read at one block
     * samples the volumes of its cell's corners (thousands of values for one),
     * which held a quarter for minutes; up to 26.2 the marker read at one block
     * was the function it marks, as here. References are inlined first, so the
     * rewrite reaches into the registry's functions.
     */
    static DensityFunction pointwise(DensityFunction root) {
        DfRewriteRule rule = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction function) {
                DensityFunction f = DfRewriteRule.INLINE_REFERENCE.rewrite(function);
                while (f instanceof InterpolatedFunction interpolated) {
                    f = DfRewriteRule.INLINE_REFERENCE.rewrite(interpolated.input());
                }
                return f.rewriteChildren(this);
            }
        };
        return rule.rewrite(root);
    }

    /** A climate sampler over a context with caches, per thread. */
    static Climate.Sampler climate(RandomState state, Climate.Sampler shared) {
        return state.createClimateSampler(SamplerContext.builder().enableCaches().build());
    }
}
