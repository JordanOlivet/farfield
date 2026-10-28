package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.config.ServerConfig;
import io.lakio.farfield.core.preview.PreviewTile;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.BlockKeys;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world's own features in the noise preview (ADR-079): each chunk of a
 * tile is decorated in a {@link FeatureSandbox} whose ground is the tile's
 * (its surface, top and filler blocks, the sea), with the generator's
 * biomes, so every mod's trees, rocks and plants grow where the game will
 * grow them as long as the ground matches. What they set above the ground
 * goes into the tile: blocks LODs draw as plants as counts per chunk
 * (ADR-060), the rest as {@link PreviewTile#FEATURE_VOXEL}-block voxels.
 * Underground steps (ores, caves, springs, strongholds) are not run; the
 * server's settings turn features off or leave some out by id.
 */
final class PreviewFeatures {

    /** The steps whose features show from above. */
    private static final Set<GenerationStep.Decoration> STEPS = EnumSet.of(GenerationStep.Decoration.RAW_GENERATION,
            GenerationStep.Decoration.LAKES, GenerationStep.Decoration.LOCAL_MODIFICATIONS,
            GenerationStep.Decoration.SURFACE_STRUCTURES, GenerationStep.Decoration.VEGETAL_DECORATION,
            GenerationStep.Decoration.TOP_LAYER_MODIFICATION);
    /** Sections filled with biomes around the ground, below and above it: where features grow. */
    private static final int BIOME_BELOW = 32;
    private static final int BIOME_ABOVE = 96;
    /** A voxel of {@link PreviewTile#FEATURE_VOXEL} blocks takes a material once this many of its 8 blocks hold one. */
    private static final int VOXEL_FILL = 2;
    /** Features that failed once, logged once per server. */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private final ServerLevel level;
    private final BiomeSource biomes;
    private final Climate.Sampler sampler;
    private final BlockState fluid;
    private final int seaLevel;
    private final LodWorld world;
    private final ServerConfig settings;
    private final PreviewTile tile;
    private final int x0;
    private final int z0;
    /** Columns where nothing grows in the preview (its villages' houses stand there). */
    private final java.util.function.BiPredicate<Integer, Integer> bare;
    private final Int2ObjectOpenHashMap<BlockState> states = new Int2ObjectOpenHashMap<>();
    private final Map<PlacedFeature, Boolean> runs = new IdentityHashMap<>();
    private final FeatureSandbox sandbox;
    /** Per feature voxel ({@link #voxel}): blocks set in it, by material. */
    private final Long2ObjectOpenHashMap<Int2IntOpenHashMap> voxels = new Long2ObjectOpenHashMap<>();
    /** Per chunk of the tile: plant blocks set in it, by material. */
    private final Int2IntOpenHashMap[] plants = new Int2IntOpenHashMap[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
    private int failures;
    /** Time spent making stand-in chunks and, per decoration step, running features (the spike's report). */
    private long standInNanos;
    private final long[] stepNanos = new long[GenerationStep.Decoration.values().length];

    PreviewFeatures(ServerLevel level, BiomeSource biomes, Climate.Sampler sampler, BlockState fluid, int seaLevel,
                    LodWorld world, ServerConfig settings, PreviewTile tile, int tileX, int tileZ,
                    java.util.function.BiPredicate<Integer, Integer> bare) {
        this.level = level;
        this.biomes = biomes;
        this.sampler = sampler;
        this.fluid = fluid;
        this.seaLevel = seaLevel;
        this.world = world;
        this.settings = settings;
        this.tile = tile;
        this.x0 = tileX * PreviewTile.BLOCKS;
        this.z0 = tileZ * PreviewTile.BLOCKS;
        this.bare = bare;
        this.sandbox = new FeatureSandbox(level, this::standIn);
        this.sandbox.timeSteps(stepNanos);
    }

    /** Features that threw while decorating this tile. */
    int failures() {
        return failures;
    }

    /** Where the time went: stand-in chunks, then each decoration step, in milliseconds. */
    String timing() {
        StringBuilder out = new StringBuilder("stand-in chunks " + standInNanos / 1_000_000 + " ms");
        for (int i = 0; i < stepNanos.length; i++) {
            if (stepNanos[i] > 0) {
                out.append(", ").append(GenerationStep.Decoration.values()[i].name().toLowerCase(java.util.Locale.ROOT))
                        .append(' ').append(stepNanos[i] / 1_000_000).append(" ms");
            }
        }
        return out.toString();
    }

    /**
     * Decorates every chunk of the tile, row by row, and stores what grew
     * in it; the rows two behind are written into the tile as it goes, so
     * at most four rows of stand-in chunks and blocks are held.
     */
    void decorate() {
        int chunkX0 = x0 >> 4;
        int chunkZ0 = z0 >> 4;
        for (int cz = 0; cz < PreviewTile.CHUNKS; cz++) {
            for (int cx = 0; cx < PreviewTile.CHUNKS; cx++) {
                for (FeatureSandbox.Failure f : sandbox.decorate(chunkX0 + cx, chunkZ0 + cz, this::runs)) {
                    failures++;
                    if (FAILED.add(f.feature())) {
                        Farfield.LOG.warn("Noise preview: feature {} failed in the preview's stand-in world; left out"
                                + " where it fails", f.feature(), f.error());
                    }
                }
            }
            // A crown reaches at most a chunk back: two rows behind nothing changes any more.
            collect((chunkZ0 + cz - 1) * 16);
            sandbox.forgetChunksBefore(chunkZ0 + cz - 2);
        }
        collect(Integer.MAX_VALUE);
        store();
    }

    /** Whether a placed feature runs: a step that shows, not left out by the settings. */
    private boolean runs(int step, PlacedFeature feature) {
        GenerationStep.Decoration decoration = FeatureSandbox.step(step);
        if (decoration == null || !STEPS.contains(decoration)) {
            return false;
        }
        return runs.computeIfAbsent(feature, f -> !settings.excludesFeature(sandbox.name(f)));
    }

    // ---------------------------------------------------------------- the stand-in ground

    /** The tile's surface at block (x, z), the first free block above the ground; outside the tile, its edge's. */
    private int ground(int x, int z) {
        return tile.height(clamp(x - x0), clamp(z - z0));
    }

    private static int clamp(int local) {
        return Math.max(0, Math.min(PreviewTile.BLOCKS - 1, local));
    }

    private BlockState state(int material) {
        BlockState s = states.get(material);
        if (s == null) {
            String key = material < 0 ? null : world.blockStates().key(material);
            BlockState found = key == null ? null : BlockKeys.state(key);
            s = found != null ? found : Blocks.STONE.defaultBlockState();
            states.put(material, s);
        }
        return s;
    }

    /**
     * A stand-in chunk: the tile's ground (its top block, the filler under
     * it, then the tile's ground material), the sea over lower ground, the
     * generator's biomes around the ground, at the carvers status with its
     * heightmaps, as the game hands a chunk to its features.
     */
    private ProtoChunk standIn(long chunkKey) {
        long start = System.nanoTime();
        try {
            return makeStandIn(chunkKey);
        } finally {
            standInNanos += System.nanoTime() - start;
        }
    }

    private ProtoChunk makeStandIn(long chunkKey) {
        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);
        int bx0 = chunkX << 4;
        int bz0 = chunkZ << 4;
        int[] heights = new int[256];
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = ground(bx0 + x, bz0 + z);
                heights[z * 16 + x] = h;
                lowest = Math.min(lowest, h);
                highest = Math.max(highest, h);
            }
        }
        BlockState below = state(tile.below());
        // One biome per quart column, at its ground: features grow at the ground and ask its biome
        // there. Sampling every quart of the sections around cost 6 ms a chunk (the climate noise).
        @SuppressWarnings("unchecked")
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] groundBiomes =
                new net.minecraft.core.Holder[16];
        for (int q = 0; q < 16; q++) {
            int qx = q & 3;
            int qz = q >> 2;
            groundBiomes[q] = biomes.getNoiseBiome(QuartPos.fromBlock(bx0) + qx,
                    QuartPos.fromBlock(heights[(qz * 4 + 2) * 16 + qx * 4 + 2]), QuartPos.fromBlock(bz0) + qz, sampler);
        }
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int minSection = level.getMinSection();
        LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        int top = Math.max(highest, fluid != null ? seaLevel : highest);
        for (int i = 0; i < sections.length; i++) {
            int sectionY = minSection + i;
            int y0 = SectionPos.sectionToBlockCoord(sectionY);
            PalettedContainer<BlockState> blocks;
            if (y0 + 16 <= lowest - PreviewTile.FILLER_DEPTH - 1) {
                // Wholly under the ground: one material, no palette work.
                blocks = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, below,
                        PalettedContainer.Strategy.SECTION_STATES);
            } else {
                blocks = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                        PalettedContainer.Strategy.SECTION_STATES);
                if (y0 <= top) {
                    fill(blocks, bx0, y0, bz0, heights, below);
                }
            }
            PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> sectionBiomes;
            if (y0 + 16 > lowest - BIOME_BELOW && y0 < highest + BIOME_ABOVE) {
                sectionBiomes = new PalettedContainer<>(biomeRegistry.asHolderIdMap(), groundBiomes[0],
                        PalettedContainer.Strategy.SECTION_BIOMES);
                for (int q = 0; q < 16; q++) {
                    for (int qy = 0; qy < 4; qy++) {
                        sectionBiomes.getAndSetUnchecked(q & 3, qy, q >> 2, groundBiomes[q]);
                    }
                }
            } else {
                sectionBiomes = plains(biomeRegistry);
            }
            sections[i] = new LevelChunkSection(blocks, sectionBiomes);
        }
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, sections,
                new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), level, biomeRegistry, null);
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE));
        return chunk;
    }

    /**
     * The block the features find on top of the ground: the tile's top,
     * except snow over dirt, which the game generates as snowy grass (its
     * snow layer comes with the top layer step). On a snow block no sapling
     * survives, so snowy forests had almost no trees in the preview
     * (ADR-086); snow over snow stays, as on snowy peaks.
     */
    private static BlockState soil(BlockState top, BlockState filler) {
        if (top.is(Blocks.SNOW_BLOCK) && !filler.is(Blocks.SNOW_BLOCK)) {
            return SNOWY_GRASS;
        }
        return top;
    }

    private static final BlockState SNOWY_GRASS = Blocks.GRASS_BLOCK.defaultBlockState()
            .setValue(net.minecraft.world.level.block.SnowyDirtBlock.SNOWY, true);

    /** Fills the blocks of one section from y0 up: top, filler, ground, then the sea. */
    private void fill(PalettedContainer<BlockState> blocks, int bx0, int y0, int bz0, int[] heights, BlockState below) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                int cx = clamp(bx0 + x - x0) / PreviewTile.GRID;
                int cz = clamp(bz0 + z - z0) / PreviewTile.GRID;
                BlockState filler = state(tile.filler(cx, cz));
                BlockState top = soil(state(tile.top(cx, cz)), filler);
                for (int y = 0; y < 16; y++) {
                    int by = y0 + y;
                    BlockState s;
                    if (by < h - 1 - PreviewTile.FILLER_DEPTH) {
                        s = below;
                    } else if (by < h - 1) {
                        s = filler;
                    } else if (by == h - 1) {
                        s = top;
                    } else if (fluid != null && by < seaLevel) {
                        s = fluid;
                    } else {
                        continue;
                    }
                    blocks.getAndSetUnchecked(x, y, z, s);
                }
            }
        }
    }

    /** A read-only biome container of plains, for the sections far from the ground; shared. */
    private PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> plains;

    private PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> plains(
            net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> registry) {
        if (plains == null) {
            plains = new PalettedContainer<>(registry.asHolderIdMap(),
                    registry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS),
                    PalettedContainer.Strategy.SECTION_BIOMES);
        }
        return plains;
    }

    // ---------------------------------------------------------------- what grew

    /**
     * Sorts the blocks set north of block {@code zLimit} into plants and
     * feature voxels, then forgets them: blocks under the ground, the sea
     * itself and its ice are the tile's already.
     */
    private void collect(int zLimit) {
        Long2ObjectOpenHashMap<BlockState> written = sandbox.written();
        ObjectIterator<Long2ObjectOpenHashMap.Entry<BlockState>> it = written.long2ObjectEntrySet().fastIterator();
        int margin = PreviewTile.FEATURE_MARGIN;
        while (it.hasNext()) {
            Long2ObjectOpenHashMap.Entry<BlockState> e = it.next();
            long pos = e.getLongKey();
            int z = BlockPos.getZ(pos);
            if (z >= zLimit) {
                continue;
            }
            // Read before removing: the fast iterator's entry moves with the removal.
            int x = BlockPos.getX(pos);
            int y = BlockPos.getY(pos);
            BlockState state = e.getValue();
            it.remove();
            if (state.isAir() || x < x0 - margin || z < z0 - margin || x >= x0 + PreviewTile.BLOCKS + margin
                    || z >= z0 + PreviewTile.BLOCKS + margin || y < ground(x, z) || bare.test(x, z)) {
                continue;
            }
            if (y < seaLevel && (!state.getFluidState().isEmpty() || state.is(Blocks.ICE))) {
                continue; // the tile's sea, its ice, and what grows in it (the tile's water covers them)
            }
            int meshClass = BlockKeys.meshClass(state);
            if (meshClass == io.lakio.farfield.core.mesh.MaterialClassifier.AIR) {
                continue; // LODs never draw it (torches, rails): no room in the tile for it
            }
            int material = world.blockStates().id(BlockKeys.key(state));
            if (meshClass == io.lakio.farfield.core.mesh.MaterialClassifier.PLANT) {
                if (x >= x0 && z >= z0 && x < x0 + PreviewTile.BLOCKS && z < z0 + PreviewTile.BLOCKS) {
                    int c = ((z - z0) >> 4) * PreviewTile.CHUNKS + ((x - x0) >> 4);
                    if (plants[c] == null) {
                        plants[c] = new Int2IntOpenHashMap();
                    }
                    plants[c].addTo(material, 1);
                }
                continue;
            }
            voxels.computeIfAbsent(voxel(x, y, z), k -> new Int2IntOpenHashMap()).addTo(material, 1);
        }
    }

    /** A feature voxel's key: its column in the tile's feature grid and its y in voxels. */
    private long voxel(int x, int y, int z) {
        int v = PreviewTile.FEATURE_VOXEL;
        int fx = Math.floorDiv(x - x0 + PreviewTile.FEATURE_MARGIN, v);
        int fz = Math.floorDiv(z - z0 + PreviewTile.FEATURE_MARGIN, v);
        return ((long) (fz * PreviewTile.FEATURE_SIDE + fx) << 32) | (Math.floorDiv(y, v) & 0xFFFFFFFFL);
    }

    /** Writes the voxels filled enough as runs per column, and the plants per chunk, into the tile. */
    private void store() {
        Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> columns = new Long2ObjectOpenHashMap<>();
        var it = voxels.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            int total = 0;
            int best = -1;
            int bestCount = 0;
            for (var m : e.getValue().int2IntEntrySet()) {
                total += m.getIntValue();
                if (m.getIntValue() > bestCount) {
                    bestCount = m.getIntValue();
                    best = m.getIntKey();
                }
            }
            if (total < VOXEL_FILL) {
                continue;
            }
            long key = e.getLongKey();
            it.unimi.dsi.fastutil.ints.IntArrayList list =
                    columns.computeIfAbsent(key >>> 32, k -> new it.unimi.dsi.fastutil.ints.IntArrayList());
            list.add((int) key);
            list.add(best);
        }
        for (var e : columns.long2ObjectEntrySet()) {
            int column = (int) e.getLongKey();
            int[] pairs = e.getValue().toIntArray();
            // Sorted bottom up, then neighbors of one material merged into runs.
            long[] packed = new long[pairs.length / 2];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = (long) pairs[i * 2] << 32 | (pairs[i * 2 + 1] & 0xFFFFFFFFL);
            }
            java.util.Arrays.sort(packed);
            it.unimi.dsi.fastutil.ints.IntArrayList runList = new it.unimi.dsi.fastutil.ints.IntArrayList();
            int runY = 0;
            int runLength = 0;
            int runMaterial = -1;
            for (long p : packed) {
                int y = (int) (p >> 32);
                int material = (int) p;
                if (runLength > 0 && y == runY + runLength && material == runMaterial && runLength < 255) {
                    runLength++;
                    continue;
                }
                if (runLength > 0) {
                    runList.add(PreviewTile.featureRun(runY, runLength));
                    runList.add(runMaterial);
                }
                runY = y;
                runLength = 1;
                runMaterial = material;
            }
            if (runLength > 0) {
                runList.add(PreviewTile.featureRun(runY, runLength));
                runList.add(runMaterial);
            }
            tile.setFeatureColumn(column % PreviewTile.FEATURE_SIDE, column / PreviewTile.FEATURE_SIDE,
                    runList.toIntArray());
        }
        for (int c = 0; c < plants.length; c++) {
            if (plants[c] == null) {
                continue;
            }
            int[] entries = new int[plants[c].size()];
            int n = 0;
            for (var m : plants[c].int2IntEntrySet()) {
                int kind = tile.plantKind(m.getIntKey());
                if (kind >= 0) {
                    entries[n++] = PreviewTile.plantEntry(kind, m.getIntValue());
                }
            }
            tile.setPlants(c % PreviewTile.CHUNKS, c / PreviewTile.CHUNKS, java.util.Arrays.copyOf(entries, n));
        }
    }
}
