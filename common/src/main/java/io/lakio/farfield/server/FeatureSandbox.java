package io.lakio.farfield.server;

import io.lakio.farfield.mixin.ChunkGeneratorAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * A stand-in world the noise preview decorates with the world's own
 * features (ADR-079): chunks made from what the preview knows of the ground
 * ({@link #chunks}, built on demand around the chunk decorated), the
 * generator's features per step run with the game's seeds, so a tree grows
 * where the real one will as long as the ground matches, and every block a
 * feature sets is recorded ({@link #written}). Any feature a mod registers
 * runs the same, with no code for it here. One sandbox per thread; it never
 * touches the real world, and holds no entities, block entities, light or
 * ticks.
 */
final class FeatureSandbox implements WorldGenLevel {

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final LongFunction<ChunkAccess> chunkSource;
    private final Long2ObjectOpenHashMap<ChunkAccess> chunks = new Long2ObjectOpenHashMap<>();
    /** Blocks the features set, by {@link BlockPos#asLong}, the last one set at each place. */
    private final Long2ObjectOpenHashMap<BlockState> written = new Long2ObjectOpenHashMap<>();
    private final RandomSource random = RandomSource.create();
    private final BiomeManager biomeManager;
    private final List<FeatureSorter.StepFeatureData> steps;
    private final Function<Holder<Biome>, net.minecraft.world.level.biome.BiomeGenerationSettings> settings;
    /** Per step, the time its features took (stand-in chunks made meanwhile included); null: not timed. */
    @Nullable
    private long[] stepNanos;

    /**
     * @param chunkSource makes the stand-in chunk at a {@link ChunkPos#toLong} key, its ground
     *                    and biomes set, at a status before features (its world-gen heightmaps kept)
     */
    FeatureSandbox(ServerLevel level, LongFunction<ChunkAccess> chunkSource) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.chunkSource = chunkSource;
        this.biomeManager = new BiomeManager(this, BiomeManager.obfuscateSeed(level.getSeed()));
        ChunkGeneratorAccessor accessor = (ChunkGeneratorAccessor) generator;
        this.steps = accessor.farfield$featuresPerStep().get();
        this.settings = accessor.farfield$generationSettingsGetter();
    }

    /** Times each step's features into {@code nanos} from now on. */
    void timeSteps(long[] nanos) {
        stepNanos = nanos;
    }

    /** The blocks set since the last {@link #clearWritten}. */
    Long2ObjectOpenHashMap<BlockState> written() {
        return written;
    }

    void clearWritten() {
        written.clear();
    }

    /** Stand-in chunks made so far. */
    int chunkCount() {
        return chunks.size();
    }

    /** Forgets the stand-in chunks north of chunk row {@code chunkZ}: decorating goes south row by row. */
    void forgetChunksBefore(int chunkZ) {
        chunks.long2ObjectEntrySet().removeIf(e -> ChunkPos.getZ(e.getLongKey()) < chunkZ);
    }

    /** Forgets every stand-in chunk (their ground included). */
    void clearChunks() {
        chunks.clear();
    }

    /** A placed feature and whether it is decorated; the preview's settings choose (ADR-079). */
    interface Filter {
        boolean runs(int step, PlacedFeature feature);
    }

    /** What went wrong with one feature: its name and the failure. */
    record Failure(String feature, Throwable error) {
    }

    /**
     * Runs a chunk's decoration as {@code ChunkGenerator.applyBiomeDecoration}
     * does, without structures: per step, the features of the biomes in the
     * chunk and around it, in the generator's order, each seeded as the game
     * seeds it. A feature that throws is skipped and reported, as the
     * preview must not stop for one mod's feature.
     */
    List<Failure> decorate(int chunkX, int chunkZ, Filter filter) {
        List<Failure> failures = new ArrayList<>();
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        SectionPos sectionPos = SectionPos.of(chunkPos, getMinSection());
        BlockPos origin = sectionPos.origin();
        WorldgenRandom worldRandom = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = worldRandom.setDecorationSeed(getSeed(), origin.getX(), origin.getZ());
        Set<Holder<Biome>> biomes = new it.unimi.dsi.fastutil.objects.ObjectArraySet<>();
        ChunkPos.rangeClosed(chunkPos, 1).forEach(p -> {
            for (var section : getChunk(p.x, p.z).getSections()) {
                section.getBiomes().getAll(biomes::add);
            }
        });
        biomes.retainAll(generator.getBiomeSource().possibleBiomes());
        for (int step = 0; step < steps.size(); step++) {
            it.unimi.dsi.fastutil.ints.IntSet indices = new it.unimi.dsi.fastutil.ints.IntArraySet();
            FeatureSorter.StepFeatureData data = steps.get(step);
            for (Holder<Biome> biome : biomes) {
                List<HolderSet<PlacedFeature>> perStep = settings.apply(biome).features();
                if (step < perStep.size()) {
                    for (Holder<PlacedFeature> f : perStep.get(step)) {
                        indices.add(data.indexMapping().applyAsInt(f.value()));
                    }
                }
            }
            int[] sorted = indices.toIntArray();
            Arrays.sort(sorted);
            for (int index : sorted) {
                PlacedFeature feature = data.features().get(index);
                if (!filter.runs(step, feature)) {
                    continue;
                }
                worldRandom.setFeatureSeed(decorationSeed, index, step);
                long start = stepNanos != null ? System.nanoTime() : 0;
                try {
                    feature.placeWithBiomeCheck(this, generator, worldRandom, origin);
                } catch (Throwable t) {
                    failures.add(new Failure(name(feature), t));
                }
                if (stepNanos != null && step < stepNanos.length) {
                    stepNanos[step] += System.nanoTime() - start;
                }
            }
        }
        return failures;
    }

    /** A placed feature's registry name, or what it prints. */
    String name(PlacedFeature feature) {
        return registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE)
                .getResourceKey(feature).map(k -> k.location().toString()).orElseGet(feature::toString);
    }

    /** The decoration steps by index, as {@code GenerationStep.Decoration} orders them. */
    static GenerationStep.Decoration step(int index) {
        GenerationStep.Decoration[] all = GenerationStep.Decoration.values();
        return index < all.length ? all[index] : null;
    }

    // ---------------------------------------------------------------- chunks and blocks

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
        long key = ChunkPos.asLong(x, z);
        ChunkAccess chunk = chunks.get(key);
        if (chunk == null) {
            chunk = chunkSource.apply(key);
            chunks.put(key, chunk);
        }
        return chunk;
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return true;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
                .getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (isOutsideBuildHeight(pos)) {
            return false;
        }
        ChunkAccess chunk = getChunk(pos);
        chunk.setBlockState(pos, state, false);
        written.put(pos.asLong(), state);
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        return setBlock(pos, getFluidState(pos).createLegacyBlock(), 3);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursionLeft) {
        return !getBlockState(pos).isAir() && setBlock(pos, Blocks.AIR.defaultBlockState(), 3, recursionLeft);
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> test) {
        return test.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> test) {
        return test.test(getFluidState(pos));
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getHeight(type, x & 15, z & 15)
                + 1;
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null; // chests and spawners of features are left out of the preview
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        return true;
    }

    @Override
    public void setCurrentlyGenerating(@Nullable java.util.function.Supplier<String> what) {
    }

    // ---------------------------------------------------------------- the world around

    @Override
    public long getSeed() {
        return level.getSeed();
    }

    @Override
    public ServerLevel getLevel() {
        return level;
    }

    @Override
    public RegistryAccess registryAccess() {
        return level.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return level.enabledFeatures();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return biomeManager;
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return level.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public int getSeaLevel() {
        return level.getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return level.dimensionType();
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1f;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
        return resolver.getColor(getBiome(pos).value(), pos.getX(), pos.getZ());
    }

    @Override
    public WorldBorder getWorldBorder() {
        return level.getWorldBorder();
    }

    @Override
    public LevelData getLevelData() {
        return level.getLevelData();
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return new DifficultyInstance(level.getDifficulty(), level.getDayTime(), 0, level.getMoonBrightness());
    }

    @Nullable
    @Override
    public MinecraftServer getServer() {
        return level.getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        return level.getChunkSource();
    }

    @Override
    public RandomSource getRandom() {
        return random;
    }

    @Override
    public long nextSubTickCount() {
        return 0;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
                          float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        return false; // mobs of features (a spawner's) are left out
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
    public List<? extends Player> players() {
        return List.of();
    }

}
