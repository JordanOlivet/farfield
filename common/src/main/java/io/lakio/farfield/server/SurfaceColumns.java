package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.ticks.ProtoChunkTicks;

/**
 * The generator's own surface rules evaluated on one column at a time, for
 * the preview's ground (step 3 of {@code docs/analysis/preview-to-the-border.md},
 * ADR-134): what {@code SurfaceSystem.buildSurface} does for a column, for
 * the two blocks a tile's cell keeps (its top and the one under it), with
 * the column's stone depths and sea known from its height, and without a
 * chunk of blocks to walk. The rule tree is built once for a tile, not once
 * a chunk. Mods' rules run as they are: they read the same context (its
 * position, depths, biome, the chunk's world generation heightmap for the
 * slope, the noise chunk).
 *
 * <p>{@code SurfaceRules.Context}, its two updates, the rules' {@code
 * tryApply} and {@code SurfaceSystem.erodedBadlandsExtension} are not
 * public; they are found by the shape of their parameters, which holds
 * under every mapping. When one is missing, {@link #create} returns null and
 * the stand-in chunks lay the ground as before.
 */
final class SurfaceColumns {

    /** The context's constructor, its updates, and the badlands pillars; resolved once. */
    private record Access(Constructor<?> context, Method updateXZ, Method updateY, Method badlands) {
    }

    private static volatile Access access;
    private static volatile boolean resolved;

    private final Object context;
    private final Method tryApply;
    private final Object rule;
    private final Access a;
    private final SurfaceSystem system;
    private final ProtoChunk host;
    private final int minY;
    private final int bits;
    private final BlockState defaultBlock;
    private final BlockState fluid;
    private final int seaLevel;
    private final Function<BlockPos, Holder<Biome>> biomes;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    /** The column the badlands pillars may raise: its height and what was set above it. */
    private final Column column = new Column();

    private SurfaceColumns(Access a, Object context, Object rule, Method tryApply, SurfaceSystem system,
                           ProtoChunk host, ServerLevel level, BlockState defaultBlock, BlockState fluid, int seaLevel,
                           Function<BlockPos, Holder<Biome>> biomes) {
        this.a = a;
        this.context = context;
        this.rule = rule;
        this.tryApply = tryApply;
        this.system = system;
        this.host = host;
        this.minY = level.getMinBuildHeight();
        this.bits = Mth.ceillog2(level.getHeight() + 1);
        this.defaultBlock = defaultBlock;
        this.fluid = fluid;
        this.seaLevel = seaLevel;
        this.biomes = biomes;
    }

    /**
     * The rules of {@code generator} ready for one tile, or null when the
     * game's classes do not have the expected shape (logged once).
     */
    static SurfaceColumns create(ServerLevel level, NoiseBasedChunkGenerator generator, NoiseChunk noise,
                                 Function<BlockPos, Holder<Biome>> biomes, BlockState fluid, int seaLevel) {
        Access a = access();
        if (a == null) {
            return null;
        }
        try {
            RandomState random = level.getChunkSource().randomState();
            SurfaceSystem system = random.surfaceSystem();
            Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
            for (int i = 0; i < sections.length; i++) {
                sections[i] = new LevelChunkSection(registry);
            }
            ProtoChunk host = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, sections,
                    new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), level, registry, null);
            host.setPersistedStatus(ChunkStatus.NOISE);
            Object context = a.context().newInstance(system, random, host, noise, biomes, registry,
                    new WorldGenerationContext(generator, level));
            @SuppressWarnings("unchecked")
            Function<Object, Object> source = (Function<Object, Object>) (Object) generator.generatorSettings().value()
                    .surfaceRule();
            Object rule = source.apply(context);
            Method tryApply = tryApply(rule.getClass());
            if (tryApply == null) {
                warn("no tryApply on " + rule.getClass().getName());
                return null;
            }
            return new SurfaceColumns(a, context, rule, tryApply, system, host, level,
                    generator.generatorSettings().value().defaultBlock(), fluid, seaLevel, biomes);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e.toString());
            return null;
        }
    }

    /**
     * Sets the world generation heightmap the slope reads, from a chunk's
     * heights (the first free block above the ground per column, {@code z *
     * 16 + x}) and the sea over lower ground, as the stand-in chunks had it.
     */
    void chunk(int[] heights) {
        SimpleBitStorage tops = new SimpleBitStorage(bits, 256);
        for (int i = 0; i < 256; i++) {
            int top = fluid != null ? Math.max(heights[i], seaLevel) : heights[i];
            tops.set(i, Math.max(0, top - minY));
        }
        host.setHeightmap(Heightmap.Types.WORLD_SURFACE_WG, tops.getRaw());
    }

    /**
     * The rules' top and filler of column (x, z), whose first free block
     * above the ground is {@code height}, into {@code out} ({@code out[0]} the
     * top block, {@code out[1]} the one under it; the dimension's default
     * block where no rule matches); {@code bottom} is where the stone under it
     * ends, for the depth the rules read below a block. Returns the column's
     * height after the eroded badlands' pillars.
     */
    int column(int x, int z, int height, int bottom, BlockState[] out) throws ReflectiveOperationException {
        int surface = fluid != null ? Math.max(height, seaLevel) : height;
        int h = height;
        // As buildSurface: the biome and the pillars at the first free block above the top (the sea's included).
        if (biomes.apply(pos.set(x, surface, z)).is(Biomes.ERODED_BADLANDS)) {
            column.reset(h);
            a.badlands().invoke(system, column, x, z, surface, (LevelHeightAccessor) host);
            h = column.height;
        }
        a.updateXZ().invoke(context, x, z);
        int water = fluid != null && h < seaLevel ? seaLevel : Integer.MIN_VALUE;
        int found = 0;
        // Top down as buildSurface walks: a rule that sets a fluid or air at the top (a swamp's water)
        // leaves the top to the block under it, as the heightmap the stand-ins were read by did.
        for (int depth = 1, y = h - 1; found < 2 && depth <= PreviewFeatures.CRUST && y >= bottom; depth++, y--) {
            a.updateY().invoke(context, depth, y - bottom + 1, water, x, y, z);
            BlockState state = (BlockState) tryApply.invoke(rule, x, y, z);
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
        int ground;
        int height;

        void reset(int h) {
            ground = h;
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

    private static Access resolve() {
        Constructor<?> context = null;
        for (Class<?> nested : SurfaceRules.class.getDeclaredClasses()) {
            for (Constructor<?> c : nested.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 7 && p[0] == SurfaceSystem.class && p[1] == RandomState.class
                        && p[2] == ChunkAccess.class && p[3] == NoiseChunk.class && p[4] == Function.class
                        && p[5] == Registry.class && p[6] == WorldGenerationContext.class) {
                    context = c;
                }
            }
        }
        if (context == null) {
            warn("no surface rules context");
            return null;
        }
        Method updateXZ = null;
        Method updateY = null;
        for (Method m : context.getDeclaringClass().getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() != void.class || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (p.length == 2 && p[0] == int.class && p[1] == int.class) {
                updateXZ = updateXZ == null ? m : null;
            } else if (p.length == 6 && allInts(p)) {
                updateY = updateY == null ? m : null;
            }
        }
        Method badlands = null;
        for (Method m : SurfaceSystem.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5 && p[0] == BlockColumn.class && p[1] == int.class && p[2] == int.class
                    && p[3] == int.class && p[4] == LevelHeightAccessor.class) {
                badlands = badlands == null ? m : null;
            }
        }
        if (updateXZ == null || updateY == null || badlands == null) {
            warn("context updates " + (updateXZ != null) + " " + (updateY != null) + ", badlands " + (badlands != null));
            return null;
        }
        try {
            context.setAccessible(true);
            updateXZ.setAccessible(true);
            updateY.setAccessible(true);
            badlands.setAccessible(true);
        } catch (RuntimeException e) {
            warn(e.toString());
            return null;
        }
        return new Access(context, updateXZ, updateY, badlands);
    }

    /** The rule's {@code tryApply(int, int, int)}: the method of that shape returning a block state among its interfaces. */
    private static Method tryApply(Class<?> type) {
        List<Class<?>> todo = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            todo.add(c);
        }
        for (int i = 0; i < todo.size(); i++) {
            Class<?> c = todo.get(i);
            for (Class<?> parent : c.getInterfaces()) {
                todo.add(parent);
                for (Method m : parent.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() == BlockState.class && p.length == 3 && allInts(p)) {
                        try {
                            m.setAccessible(true);
                            return m;
                        } catch (RuntimeException e) {
                            return null;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean allInts(Class<?>[] p) {
        for (Class<?> t : p) {
            if (t != int.class) {
                return false;
            }
        }
        return true;
    }

    private static void warn(String why) {
        Farfield.LOG.warn("Noise preview: the surface rules cannot be evaluated column by column ({}); the stand-in"
                + " chunks lay the ground", why);
    }
}
