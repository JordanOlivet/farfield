package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.compat.Palettes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The preview's stand-in world, given to the features as the game's own
 * generation region (ADR-178). A feature gets a {@code WorldGenLevel}, and
 * during real generation that is always a {@code WorldGenRegion}: mods cast
 * it to one, or to an accessor of theirs on it, to read its center or its
 * structures (a lake that stays out of a structure), and failed in the
 * stand-in world, which was none. This one is a region by its class only:
 * every method the game's region declares is answered by the
 * {@link FeatureSandbox} under it, so the features see the same world as
 * before, and nothing of the real region's own state (its chunk cache) is
 * read. The center it names is the chunk being decorated.
 *
 * <p>Other mods change the game's region class too (a faster chunk lookup,
 * light read from their own engine). Their changes inside the methods
 * declared here never run, since those are taken over. A level method they
 * <em>add</em> to the class would run on the real region's empty state:
 * {@link #over} looks for one once, and keeps the plain stand-in world when
 * it finds any this class does not take over.
 */
final class SandboxRegion extends WorldGenRegion {

    /** Whether the features get a region: decided at the first stand-in world, then kept. */
    private static volatile Boolean usable;

    /**
     * The stand-in world of the region being made on this thread: the game's constructor already asks the
     * region for its level (1.20.1) or its registries (26.3), before this class's own field is set.
     */
    private static final ThreadLocal<FeatureSandbox> MAKING = new ThreadLocal<>();

    private final FeatureSandbox inner;

    private FeatureSandbox inner() {
        FeatureSandbox world = inner;
        return world != null ? world : MAKING.get();
    }

    /**
     * The world the features of {@code inner} are given: a region over it,
     * or {@code inner} itself where a region cannot stand in (a mod's added
     * method, or the game's region refusing to be made), said once in the log.
     */
    static WorldGenLevel over(FeatureSandbox inner, ServerLevel level) {
        Boolean known = usable;
        if (known != null && !known) {
            return inner;
        }
        try {
            if (known == null) {
                List<String> foreign = foreign();
                if (!foreign.isEmpty()) {
                    usable = false;
                    Farfield.LOG.warn("Noise preview: features run in the plain stand-in world, not in a generation"
                            + " region: a mod added {} to the game's region, which the preview's cannot take over;"
                            + " features that ask for the game's region fail there (ADR-178)", foreign);
                    return inner;
                }
            }
            SandboxRegion region;
            MAKING.set(inner);
            try {
                region = new SandboxRegion(inner, level, center(level));
            } finally {
                MAKING.remove();
            }
            if (known == null) {
                usable = true;
                Farfield.LOG.info("Noise preview: features run in a generation region over the stand-in world"
                        + " (ADR-178)");
            }
            return region;
        } catch (RuntimeException | LinkageError e) {
            usable = false;
            Farfield.LOG.warn("Noise preview: features run in the plain stand-in world: the game's generation"
                    + " region could not be made over it; features that ask for one fail there (ADR-178)", e);
            return inner;
        }
    }

    /**
     * Level methods another mod added to the game's region class that this
     * class does not declare: an override of an interface's default, which
     * would run on the real region's state here. Injected handlers (their
     * names hold a {@code $}) and methods that are no level method (a mod's
     * accessor) are not such.
     */
    private static List<String> foreign() {
        List<String> names = new ArrayList<>();
        for (Method method : WorldGenRegion.class.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers) || method.isSynthetic()
                    || method.getName().indexOf('$') >= 0) {
                continue;
            }
            try {
                SandboxRegion.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
                continue; // taken over
            } catch (NoSuchMethodException e) {
                // not taken over: a level method, or a mod's own?
            }
            try {
                WorldGenLevel.class.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                continue; // no level method: an accessor, a mod's own interface
            }
            StringBuilder name = new StringBuilder(method.getName()).append('(');
            for (Class<?> parameter : method.getParameterTypes()) {
                name.append(name.charAt(name.length() - 1) == '(' ? "" : ", ").append(parameter.getSimpleName());
            }
            names.add(name.append(')').toString());
        }
        return names;
    }

    /** An empty chunk for the game's region to hold as its center: never read, the stand-in world answers. */
    private static ChunkAccess center(ServerLevel level) {
        Palettes palettes = new Palettes(level);
        LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = palettes.emptySection();
        }
        return palettes.protoChunk(new ChunkPos(0, 0), sections, level);
    }

    private SandboxRegion(FeatureSandbox inner, ServerLevel level, ChunkAccess center) {
        //? if >=1.21 {
        // A cache of one place without a holder, and the first step, which waits for no chunk around it.
        super(level, net.minecraft.util.StaticCache2D.create(0, 0, 0, (x, z) -> null),
                net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.EMPTY),
                center);
        //?} else {
        /*super(level, List.of(center), ChunkStatus.FEATURES, 0);
        *///?}
        this.inner = inner;
    }

    // ---------------------------------------------------------------- the region's own

    @Override
    public ChunkPos getCenter() {
        return inner().decorating();
    }

    @Override
    public boolean isOldChunkAround(ChunkPos pos, int radius) {
        return false; // no chunk of an older version to blend with
    }

    //? if >=26.2 {
    /*@Override
    public boolean isWithinWriteZone(BlockPos pos) {
        return true;
    }
    *///?}

    @Override
    public void setCurrentlyGenerating(@Nullable Supplier<String> what) {
        inner().setCurrentlyGenerating(what);
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        return inner().ensureCanWrite(pos);
    }

    // ---------------------------------------------------------------- chunks and blocks

    @Override
    public ChunkAccess getChunk(int x, int z) {
        return inner().getChunk(x, z);
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
        return inner().getChunk(x, z, status, required);
    }

    // A default of the level interfaces, taken over because mods give the game's region their own.
    @Override
    public ChunkAccess getChunk(BlockPos pos) {
        return inner().getChunk(pos);
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return inner().hasChunk(x, z);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return inner().getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return inner().getFluidState(pos);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        return inner().setBlock(pos, state, flags, recursionLeft);
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        return inner().removeBlock(pos, moving);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursionLeft) {
        return inner().destroyBlock(pos, drop, entity, recursionLeft);
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> test) {
        return inner().isStateAtPosition(pos, test);
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> test) {
        return inner().isFluidAtPosition(pos, test);
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return inner().getHeight(type, x, z);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return inner().getBlockEntity(pos);
    }

    //? if >=26.3 {
    /*@Override
    public net.minecraft.world.level.blockscan.BlockMatcher findBlocksIn(BlockPos from, BlockPos to) {
        return inner().findBlocksIn(from, to);
    }
    *///?}

    // ---------------------------------------------------------------- the world around

    @Override
    public long getSeed() {
        return inner().getSeed();
    }

    @Override
    public ServerLevel getLevel() {
        return inner().getLevel();
    }

    @Override
    public RegistryAccess registryAccess() {
        return inner().registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return inner().enabledFeatures();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return inner().getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return inner().getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public int getSeaLevel() {
        return inner().getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return inner().dimensionType();
    }

    @Override
    public int getHeight() {
        return inner().getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return inner().getMinBuildHeight();
    }

    @Override
    public boolean isClientSide() {
        return inner().isClientSide();
    }

    @Override
    public int getSkyDarken() {
        return inner().getSkyDarken();
    }

    //? if <26.1 {
    @Override
    public float getShade(Direction direction, boolean shade) {
        return inner().getShade(direction, shade);
    }

    @Override
    public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
        return inner().getBlockTint(pos, resolver);
    }
    //?}

    @Override
    public LevelLightEngine getLightEngine() {
        return inner().getLightEngine();
    }

    // Two more defaults mods give the game's region their own (light read from another engine).
    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return inner().getBrightness(layer, pos);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return inner().getRawBrightness(pos, darkening);
    }

    @Override
    public WorldBorder getWorldBorder() {
        return inner().getWorldBorder();
    }

    @Override
    public LevelData getLevelData() {
        return inner().getLevelData();
    }

    //? if >=1.21.11 {
    /*@Override
    public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() {
        return inner().environmentAttributes();
    }
    *///?}

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return inner().getCurrentDifficultyAt(pos);
    }

    @Nullable
    @Override
    public MinecraftServer getServer() {
        return inner().getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        return inner().getChunkSource();
    }

    @Override
    public RandomSource getRandom() {
        return inner().getRandom();
    }

    @Override
    public long nextSubTickCount() {
        return inner().nextSubTickCount();
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return inner().getBlockTicks();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return inner().getFluidTicks();
    }

    @Override
    //? if >=1.21.11 {
    /*public void playSound(@Nullable Entity player, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
                          float pitch) {
    *///?} else {
    public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
                          float pitch) {
    //?}
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    //? if >=1.21.11 {
    /*public void levelEvent(@Nullable Entity player, int type, BlockPos pos, int data) {
    *///?} else {
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    //?}
    }

    @Override
    //? if >=1.20.5 {
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    //?} else {
    /*public void gameEvent(GameEvent event, Vec3 pos, GameEvent.Context context) {
    *///?}
    }

    // ---------------------------------------------------------------- entities: none

    @Override
    public boolean addFreshEntity(Entity entity) {
        return inner().addFreshEntity(entity);
    }

    @Nullable
    @Override
    public Player getNearestPlayer(double x, double y, double z, double distance, @Nullable Predicate<Entity> test) {
        return null;
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB box, Predicate<? super Entity> test) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB box, Predicate<? super T> test) {
        return List.of();
    }

    @Override
    public List<Player> players() {
        return List.of();
    }
}
