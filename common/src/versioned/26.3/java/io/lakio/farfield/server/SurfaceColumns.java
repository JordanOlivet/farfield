package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/**
 * Minecraft 26.3 (ADR-171): the generator's own surface rules, 26.3's
 * material system, evaluated on one column at a time for the preview's ground
 * (ADR-134), as up to 26.2 with {@code SurfaceRules}. 26.3 makes this simpler:
 * the rule tree compiles to an evaluator with a public {@code tryApply}, and
 * the rule context takes the column's slope itself, so no stand-in chunk or
 * heightmap is needed. The context's constructor, its two updates and the
 * eroded badlands' pillars are not public: found by name (26.3 is not
 * obfuscated), and when one is missing {@link #create} returns null and the
 * ground stays guessed.
 *
 * <p>The rules read the preliminary surface through the context's density
 * samplers: they get the interpolation-free densities the preview reads at
 * single blocks ({@link ColumnDensity#pointwise}).
 */
final class SurfaceColumns {

    private record Access(Constructor<MaterialRuleContext> context, Method updateXZ, Method updateY, Method badlands) {
    }

    private static volatile Access access;
    private static volatile boolean resolved;

    private final Access a;
    private final MaterialRuleContext context;
    private final RuleEvaluator rule;
    private final MaterialSystem system;
    private final LevelHeightAccessor heights;
    private final BlockState defaultBlock;
    private final BlockState fluid;
    private final int seaLevel;
    private final Function<BlockPos, Holder<Biome>> biomes;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final Column column = new Column();
    /** The chunk's surface per column ({@code z * 16 + x}), the sea's over lower ground, for the slope. */
    private final int[] tops = new int[256];
    private int chunkX0;
    private int chunkZ0;

    private SurfaceColumns(Access a, MaterialRuleContext context, RuleEvaluator rule, MaterialSystem system,
                           ServerLevel level, BlockState defaultBlock, BlockState fluid, int seaLevel,
                           Function<BlockPos, Holder<Biome>> biomes) {
        this.a = a;
        this.context = context;
        this.rule = rule;
        this.system = system;
        this.heights = level;
        this.defaultBlock = defaultBlock;
        this.fluid = fluid;
        this.seaLevel = seaLevel;
        this.biomes = biomes;
    }

    /**
     * The rules of {@code generator} ready for one tile, or null when the
     * game's classes do not have the expected shape (logged once). {@code
     * noise} is unused on 26.3 (the rules read the densities themselves).
     */
    static SurfaceColumns create(ServerLevel level, NoiseBasedChunkGenerator generator, Object noise,
                                 Function<BlockPos, Holder<Biome>> biomes, BlockState fluid, int seaLevel) {
        Access a = access();
        if (a == null) {
            return null;
        }
        try {
            RandomState random = level.getChunkSource().randomState();
            MaterialSystem system = random.surfaceSystem();
            SamplerContext samplerContext = SamplerContext.builder().enableCaches().build();
            Map<DensityFunction, DensitySampler> samplers = new IdentityHashMap<>();
            DensitySamplerSet set = function -> samplers
                    .computeIfAbsent(function, f -> random.getSampler(ColumnDensity.pointwise(f)))
                    .bind(samplerContext);
            // A volume no column falls in: the rules read the preliminary surface one column at a time.
            DensityVolume none = new DensityVolume(1, 1, 1, Integer.MIN_VALUE / 2, 0, Integer.MIN_VALUE / 2);
            MaterialRuleContext context = a.context().newInstance(system, random, none, set, biomes,
                    new WorldGenerationContext(generator, level), null);
            RuleEvaluator rule = generator.generatorSettings().value().materialRule().value().compile(context);
            return new SurfaceColumns(a, context, rule, system, level,
                    generator.generatorSettings().value().defaultBlock(), fluid, seaLevel, biomes);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e.toString());
            return null;
        }
    }

    /**
     * The chunk's heights (the first free block above the ground per column,
     * {@code z * 16 + x}), the sea over lower ground, for the slope the rules
     * read, as the world generation heightmap had them.
     */
    void chunk(int[] heights) {
        for (int i = 0; i < 256; i++) {
            tops[i] = fluid != null ? Math.max(heights[i], seaLevel) : heights[i];
        }
    }

    /**
     * The rules' top and filler of column (x, z), whose first free block
     * above the ground is {@code height}, into {@code out} ({@code out[0]} the
     * top block, {@code out[1]} the one under it; the dimension's default
     * block where no rule matches); {@code bottom} is where the stone under it
     * ends. Returns the column's height after the eroded badlands' pillars.
     */
    int column(int x, int z, int height, int bottom, BlockState[] out) throws ReflectiveOperationException {
        int surface = fluid != null ? Math.max(height, seaLevel) : height;
        int h = height;
        if (biomes.apply(pos.set(x, surface, z)).is(Biomes.ERODED_BADLANDS)) {
            column.reset(h);
            a.badlands().invoke(system, column, x, z, surface, heights);
            h = column.height;
        }
        // The slope as MaterialSystem reads it: the heights one column either side, clamped to the chunk.
        int lx = x & 15;
        int lz = z & 15;
        int gradientX = tops[lz * 16 + Math.min(lx + 1, 15)] - tops[lz * 16 + Math.max(lx - 1, 0)];
        int gradientZ = tops[Math.min(lz + 1, 15) * 16 + lx] - tops[Math.max(lz - 1, 0) * 16 + lx];
        a.updateXZ().invoke(context, x, z, gradientX, gradientZ);
        int water = fluid != null && h < seaLevel ? seaLevel : Integer.MIN_VALUE;
        int found = 0;
        // Top down as buildSurface walks: a rule that sets a fluid or air at the top (a swamp's water)
        // leaves the top to the block under it.
        for (int depth = 1, y = h - 1; found < 2 && depth <= PreviewFeatures.CRUST && y >= bottom; depth++, y--) {
            a.updateY().invoke(context, depth, y - bottom + 1, water, y);
            BlockState state = rule.tryApply(x, y, z);
            if (state == null) {
                state = defaultBlock;
            }
            if (found == 0 && !Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) {
                continue;
            }
            out[found++] = state;
        }
        if (found == 0) {
            out[0] = defaultBlock;
        }
        if (found < 2) {
            out[1] = out[0];
        }
        return h;
    }

    /** One column for the badlands pillars: the ground to its height, the sea, air above, what they set. */
    private final class Column implements BlockColumn {
        int height;

        void reset(int h) {
            height = h;
        }

        @Override
        public BlockState getBlock(int y) {
            if (y < height) {
                return defaultBlock;
            }
            return fluid != null && y < seaLevel ? fluid : Blocks.AIR.defaultBlockState();
        }

        @Override
        public void setBlock(int y, BlockState state) {
            // The pillars fill air down from their top with the default block: the column grows to it.
            if (!state.isAir() && y >= height) {
                height = y + 1;
            }
        }
    }

    private static Access access() {
        if (!resolved) {
            synchronized (SurfaceColumns.class) {
                if (!resolved) {
                    access = resolve();
                    resolved = true;
                }
            }
        }
        return access;
    }

    @SuppressWarnings("unchecked")
    private static Access resolve() {
        try {
            Constructor<MaterialRuleContext> context = null;
            for (Constructor<?> c : MaterialRuleContext.class.getDeclaredConstructors()) {
                if (c.getParameterCount() == 7) {
                    context = (Constructor<MaterialRuleContext>) c;
                }
            }
            if (context == null) {
                warn("no rule context constructor");
                return null;
            }
            Method updateXZ = MaterialRuleContext.class.getDeclaredMethod("updateXZ", int.class, int.class, int.class,
                    int.class);
            Method updateY = MaterialRuleContext.class.getDeclaredMethod("updateY", int.class, int.class, int.class,
                    int.class);
            Method badlands = MaterialSystem.class.getDeclaredMethod("erodedBadlandsExtension", BlockColumn.class,
                    int.class, int.class, int.class, LevelHeightAccessor.class);
            context.setAccessible(true);
            updateXZ.setAccessible(true);
            updateY.setAccessible(true);
            badlands.setAccessible(true);
            Farfield.LOG.info("Noise preview: the surface rules (26.3's material system) run column by column");
            return new Access(context, updateXZ, updateY, badlands);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e.toString());
            return null;
        }
    }

    private static void warn(String why) {
        Farfield.LOG.warn("Noise preview: the surface rules cannot be evaluated column by column ({}); the ground"
                + " stays guessed", why);
    }
}
