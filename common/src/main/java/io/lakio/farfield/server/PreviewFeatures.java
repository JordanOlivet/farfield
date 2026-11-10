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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world's own ground and features in the noise preview (ADR-079,
 * ADR-101): each chunk of a tile is made from the tile's surface, the
 * generator's own surface rules lay its top blocks (every mod's rules
 * included), and the tile's top and filler blocks are read from it; then
 * it is decorated in a {@link FeatureSandbox} with the generator's biomes,
 * so every mod's trees, rocks and plants grow where the game will grow them
 * as long as the ground matches. What they set above the ground
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
    /** Deepest block under the ground a dug column is followed to (ADR-118): lakes are 8 blocks tall. */
    private static final int PIT_DEPTH = 8;
    /** Features that failed once, logged once per server. */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    /**
     * Blocks of the dimension's default block under a stand-in's surface for
     * its surface rules: they run on that block only, and read the depth
     * of stone above and below; deeper, a block of no rule stands for stone.
     */
    static final int CRUST = 6;
    private static final BlockState UNDER_CRUST = Blocks.BEDROCK.defaultBlockState();
    /**
     * Blocks under its lowest surface a stand-in for the surface rules goes
     * down to. The rules walk every block of every column down to the chunk's
     * bottom (a third of a tile's time when that was the world's), and read
     * the stone under a block only to tell a ceiling a few blocks thick.
     */
    static final int RULES_DEPTH = 64;
    /** Dimensions whose surface rules threw once: guessed tops from then on, logged once. */
    private static final Set<String> SURFACE_FAILED = ConcurrentHashMap.newKeySet();

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
    /** Cells ({@code cz * CELLS + cx}) whose guessed top stays: the icebergs the preview raises itself. */
    private final boolean[] keepCells;
    /** The generator's surface rules lay the ground; null: the tile's guessed tops (another generator, or a failure). */
    private NoiseBasedChunkGenerator surface;
    private net.minecraft.world.level.biome.BiomeManager surfaceBiomes;
    /** The biome source's biomes by quart, for the surface rules: each sampled once a tile. */
    private final Long2ObjectOpenHashMap<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> quarts =
            new Long2ObjectOpenHashMap<>();
    /** The noise chunk all stand-ins share for their surface rules ({@link #layGround}). */
    private NoiseChunk noiseChunk;
    /** Chunks of the tile whose cells were read from their surface. */
    private final boolean[] cellsRead = new boolean[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
    private int surfacedChunks;
    private long surfaceNanos;
    private final Int2ObjectOpenHashMap<BlockState> states = new Int2ObjectOpenHashMap<>();
    private final Map<PlacedFeature, Boolean> runs = new IdentityHashMap<>();
    private final FeatureSandbox sandbox;
    /** Per feature voxel ({@link #voxel}): blocks set in it, by material. */
    private final Long2ObjectOpenHashMap<Int2IntOpenHashMap> voxels = new Long2ObjectOpenHashMap<>();
    /** Per column of the tile ({@code BlockPos.asLong(x, 0, z)}): its plants under the sea, {lowest y, blocks, lowest block's material}. */
    private final Long2ObjectOpenHashMap<int[]> seaColumns = new Long2ObjectOpenHashMap<>();
    /** Per block ({@code BlockPos.asLong}): the light emission of the features' light sources (ADR-117). */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lights = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    /**
     * Per block column on land, the air and fluids the features wrote into
     * the top {@link #PIT_DEPTH} blocks of the ground, index depth - 1
     * (ADR-118): what a lake digs and fills.
     */
    private final Long2ObjectOpenHashMap<BlockState[]> dug = new Long2ObjectOpenHashMap<>();
    /** Per chunk of the tile: plant blocks set in it, by material. */
    private final Int2IntOpenHashMap[] plants = new Int2IntOpenHashMap[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
    private int failures;
    /** Time spent making stand-in chunks and, per decoration step, running features (the spike's report). */
    private long standInNanos;
    private final long[] stepNanos = new long[GenerationStep.Decoration.values().length];

    PreviewFeatures(ServerLevel level, BiomeSource biomes, Climate.Sampler sampler, BlockState fluid, int seaLevel,
                    LodWorld world, ServerConfig settings, PreviewTile tile, int tileX, int tileZ,
                    java.util.function.BiPredicate<Integer, Integer> bare, boolean[] keepCells) {
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
        this.keepCells = keepCells;
        if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise
                && !SURFACE_FAILED.contains(level.dimension().location().toString())) {
            this.surface = noise;
            // A subclass on purpose: ModernFix swaps a plain BiomeManager in the surface rules for a
            // lookup in the chunk's own biomes, and a stand-in holds one per quart column, at its
            // ground: the rules then saw another biome at every chunk's first cell row and column
            // (grass lines across a gravel beach, ADR-104).
            this.surfaceBiomes = new net.minecraft.world.level.biome.BiomeManager(this::quartBiome,
                    net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(level.getSeed())) {
            };
        }
        this.sandbox = new FeatureSandbox(level, this::standIn);
        this.sandbox.timeSteps(stepNanos);
    }

    /** Features that threw while decorating this tile. */
    int failures() {
        return failures;
    }

    /** Where the time went: stand-in chunks (their surface rules), then each decoration step, in milliseconds. */
    String timing() {
        StringBuilder out = new StringBuilder("stand-in chunks " + standInNanos / 1_000_000 + " ms (surface rules "
                + surfaceNanos / 1_000_000 + " ms, " + surfacedChunks + " chunks)");
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

    /**
     * Decorates the {@code n} x {@code n} chunks of the tile from its chunk
     * ({@code lx0}, {@code lz0}) and stores what grew there into the tile:
     * a few chunks of a biome sampled for its canopy (ADR-126). The tile's
     * heights must be known a chunk around them, where features spill.
     */
    void decorateWindow(int lx0, int lz0, int n) {
        int chunkX0 = (x0 >> 4) + lx0;
        int chunkZ0 = (z0 >> 4) + lz0;
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                for (FeatureSandbox.Failure f : sandbox.decorate(chunkX0 + cx, chunkZ0 + cz, this::runs)) {
                    failures++;
                    if (FAILED.add(f.feature())) {
                        Farfield.LOG.warn("Noise preview: feature {} failed in the preview's stand-in world; left out"
                                + " where it fails", f.feature(), f.error());
                    }
                }
            }
        }
        collect(Integer.MAX_VALUE);
        store();
    }

    /**
     * Lays the tile's ground from the generator's surface rules without
     * decorating it (ADR-126): the terrain pass of a level-0 tile, written
     * before its features grow, and all of a tile when the server's settings
     * turn features off. {@link #decorate} after it lays nothing again.
     */
    void groundOnly() {
        long start = System.nanoTime();
        int chunkX0 = x0 >> 4;
        int chunkZ0 = z0 >> 4;
        BlockState below = state(tile.below());
        for (int cz = 0; cz < PreviewTile.CHUNKS && surface != null; cz++) {
            for (int cx = 0; cx < PreviewTile.CHUNKS && surface != null; cx++) {
                if (cellsRead[cz * PreviewTile.CHUNKS + cx]) {
                    continue;
                }
                int bx0 = (chunkX0 + cx) << 4;
                int bz0 = (chunkZ0 + cz) << 4;
                int[] heights = new int[256];
                int lowest = columnHeights(bx0, bz0, heights)[0];
                groundBiomes(bx0, bz0, heights);
                surfaceChunk(chunkX0 + cx, chunkZ0 + cz, heights, lowest, below);
            }
        }
        standInNanos += System.nanoTime() - start;
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
        int[] range = columnHeights(bx0, bz0, heights);
        int lowest = range[0];
        int highest = range[1];
        BlockState below = state(tile.below());
        var groundBiomes = groundBiomes(bx0, bz0, heights);
        if (surface != null && inTile(chunkX, chunkZ) && !cellsRead[cellsIndex(chunkX, chunkZ)]) {
            // The rules lay the cells' tops first; the features then grow on a ground made of them.
            surfaceChunk(chunkX, chunkZ, heights, lowest, below);
        }
        return chunk(chunkX, chunkZ, heights, lowest, highest, groundBiomes, below);
    }

    /** A chunk's surface per column ({@code z * 16 + x}) into {@code heights}; returns {lowest, highest}. */
    private int[] columnHeights(int bx0, int bz0, int[] heights) {
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
        return new int[]{lowest, highest};
    }

    /**
     * One biome per quart column of a chunk, at its ground: features grow at
     * the ground and ask its biome there, and the surface rules then find the
     * same. Sampling every quart of the sections around cost 6 ms a chunk
     * (the climate noise).
     */
    @SuppressWarnings("unchecked")
    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] groundBiomes(int bx0, int bz0,
                                                                                         int[] heights) {
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] out = new net.minecraft.core.Holder[16];
        for (int q = 0; q < 16; q++) {
            int qx = q & 3;
            int qz = q >> 2;
            out[q] = quartBiome(QuartPos.fromBlock(bx0) + qx,
                    QuartPos.fromBlock(heights[(qz * 4 + 2) * 16 + qx * 4 + 2]), QuartPos.fromBlock(bz0) + qz);
        }
        return out;
    }

    /** Runs the surface rules on a chunk of the tile and reads its cells; on a throw, guessed tops from then on. */
    private void surfaceChunk(int chunkX, int chunkZ, int[] heights, int lowest, BlockState below) {
        ProtoChunk ground = rulesChunk(chunkX, chunkZ, heights, lowest, below);
        try {
            long start = System.nanoTime();
            layGround(ground);
            surfaceNanos += System.nanoTime() - start;
            surfacedChunks++;
            readCells(ground, heights);
        } catch (RuntimeException e) {
            if (SURFACE_FAILED.add(level.dimension().location().toString())) {
                Farfield.LOG.warn("Noise preview of {}: the surface rules failed in the preview's stand-in"
                        + " world; tops guessed from the biomes from now on", level.dimension().location(), e);
            }
            surface = null;
        }
    }

    private boolean inTile(int chunkX, int chunkZ) {
        int lx = chunkX - (x0 >> 4);
        int lz = chunkZ - (z0 >> 4);
        return lx >= 0 && lz >= 0 && lx < PreviewTile.CHUNKS && lz < PreviewTile.CHUNKS;
    }

    private int cellsIndex(int chunkX, int chunkZ) {
        return (chunkZ - (z0 >> 4)) * PreviewTile.CHUNKS + chunkX - (x0 >> 4);
    }

    /**
     * The column of each 4-block cell the surface rules run on: one per cell,
     * as the tile keeps one top per cell. The others hold a block no rule
     * matches, at their height, so the rules still read the slope.
     */
    static boolean ruled(int x, int z) {
        return (x & 3) == PreviewTile.GRID / 2 && (z & 3) == PreviewTile.GRID / 2;
    }

    /**
     * For the spike of ADR-101: a stand-in chunk on the given heights (the
     * first free block above the ground per column, {@code z * 16 + x})
     * with the generator's surface rules run on it; null when they cannot.
     */
    ProtoChunk surfaced(int chunkX, int chunkZ, int[] heights) {
        if (surface == null) {
            return null;
        }
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int h : heights) {
            lowest = Math.min(lowest, h);
            highest = Math.max(highest, h);
        }
        // The quart columns' biomes at their ground first, as a tile's stand-ins sample them.
        for (int q = 0; q < 16; q++) {
            int qx = q & 3;
            int qz = q >> 2;
            quartBiome(QuartPos.fromBlock(chunkX << 4) + qx,
                    QuartPos.fromBlock(heights[(qz * 4 + 2) * 16 + qx * 4 + 2]), QuartPos.fromBlock(chunkZ << 4) + qz);
        }
        BlockState below = surface.generatorSettings().value().defaultBlock();
        ProtoChunk chunk = rulesChunk(chunkX, chunkZ, heights, lowest, below);
        long start = System.nanoTime();
        layGround(chunk);
        surfaceNanos += System.nanoTime() - start;
        surfacedChunks++;
        return chunk;
    }

    /** Time the surface rules took so far, in nanoseconds. */
    long surfaceNanos() {
        return surfaceNanos;
    }

    /**
     * A stand-in chunk for the surface rules, at the noise status with its
     * heightmaps, as the game hands a chunk to them: at that status a block
     * set updates the two world generation heightmaps, which the rules read
     * (their start in a column, the slope) and {@link #readCells} after them.
     * At the carvers status those stayed as primed: the pillars the rules
     * raise in eroded badlands were never ruled and kept the ground's block
     * for top. Down to {@link #RULES_DEPTH} blocks under its lowest surface
     * only: the {@link #ruled} columns hold the dimension's default block for
     * {@link #CRUST} blocks under the surface and a block no rule matches
     * under it; the other columns hold that block at their top alone, for the
     * slope the rules read from the heightmap (they never match it, and walk
     * through air faster than through blocks). The sea over lower ground. No
     * biomes: the rules ask {@link #surfaceBiomes}.
     */
    private ProtoChunk rulesChunk(int chunkX, int chunkZ, int[] heights, int lowest, BlockState below) {
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int bottom = Math.max(level.getMinBuildHeight(), Math.floorDiv(lowest - RULES_DEPTH, 16) * 16);
        var span = net.minecraft.world.level.LevelHeightAccessor.create(bottom, level.getMaxBuildHeight() - bottom);
        LevelChunkSection[] sections = new LevelChunkSection[span.getSectionsCount()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new LevelChunkSection(biomeRegistry);
        }
        int bits = net.minecraft.util.Mth.ceillog2(span.getHeight() + 1);
        var surfaceTops = new net.minecraft.util.SimpleBitStorage(bits, 256);
        var floorTops = new net.minecraft.util.SimpleBitStorage(bits, 256);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                if (ruled(x, z)) {
                    for (int y = bottom; y < h; y++) {
                        sections[(y - bottom) >> 4].setBlockState(x, y & 15, z, y < h - CRUST ? UNDER_CRUST : below, false);
                    }
                } else if (h > bottom) {
                    sections[(h - 1 - bottom) >> 4].setBlockState(x, (h - 1) & 15, z, UNDER_CRUST, false);
                }
                int surfaceTop = h;
                for (int y = Math.max(h, bottom); fluid != null && y < seaLevel; y++) {
                    sections[(y - bottom) >> 4].setBlockState(x, y & 15, z, fluid, false);
                    surfaceTop = seaLevel;
                }
                floorTops.set(z * 16 + x, Math.max(h, bottom) - bottom);
                surfaceTops.set(z * 16 + x, Math.max(surfaceTop, bottom) - bottom);
            }
        }
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, sections,
                new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), span, biomeRegistry, null);
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        if (knownTops(below)) {
            // What priming would find, without its scan of every column: the columns were just laid.
            chunk.setHeightmap(Heightmap.Types.WORLD_SURFACE_WG, surfaceTops.getRaw());
            chunk.setHeightmap(Heightmap.Types.OCEAN_FLOOR_WG, floorTops.getRaw());
        } else {
            Heightmap.primeHeightmaps(chunk, WORLD_GEN_HEIGHTMAPS);
        }
        return chunk;
    }

    /**
     * Whether a rules stand-in's heightmaps follow from its heights: the
     * ground's blocks stop motion and the sea's fluid does not, as in every
     * dimension known; else they are primed from the blocks.
     */
    private boolean knownTops(BlockState below) {
        var floor = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        return floor.test(below) && floor.test(UNDER_CRUST) && (fluid == null || !floor.test(fluid));
    }

    /**
     * A stand-in chunk for the features, at the carvers status with its
     * heightmaps: the tile's top, filler and ground, the sea over lower
     * ground, the generator's biomes around the ground.
     */
    private ProtoChunk chunk(int chunkX, int chunkZ, int[] heights, int lowest, int highest,
                             net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] groundBiomes,
                             BlockState below) {
        int bx0 = chunkX << 4;
        int bz0 = chunkZ << 4;
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int minSection = level.getMinSection();
        LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        int top = Math.max(highest, fluid != null ? seaLevel : highest);
        int depth = PreviewTile.FILLER_DEPTH + 1;
        for (int i = 0; i < sections.length; i++) {
            int sectionY = minSection + i;
            int y0 = SectionPos.sectionToBlockCoord(sectionY);
            PalettedContainer<BlockState> blocks;
            if (y0 + 16 <= lowest - depth) {
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
        layHeightmaps(chunk, bx0, bz0, heights, below);
        return chunk;
    }

    /** Per block state, the heightmaps of {@link #HEIGHTMAPS} it counts for, as bits. */
    private final Map<BlockState, Integer> opaque = new IdentityHashMap<>();

    private int opaque(BlockState state) {
        Integer bits = opaque.get(state);
        if (bits == null) {
            int found = 0;
            for (int t = 0; t < HEIGHTMAPS.length; t++) {
                found |= HEIGHTMAPS[t].isOpaque().test(state) ? 1 << t : 0;
            }
            opaque.put(state, found);
            return found;
        }
        return bits;
    }

    /**
     * Sets the heightmaps of a features stand-in from the column {@link #fill}
     * just laid (the sea, the top, its filler, the ground), each state asked
     * the heightmap's own test: what priming finds by scanning every column
     * from the top.
     */
    private void layHeightmaps(ProtoChunk chunk, int bx0, int bz0, int[] heights, BlockState below) {
        int min = level.getMinBuildHeight();
        int bits = net.minecraft.util.Mth.ceillog2(level.getHeight() + 1);
        var maps = new net.minecraft.util.SimpleBitStorage[HEIGHTMAPS.length];
        for (int t = 0; t < maps.length; t++) {
            maps[t] = new net.minecraft.util.SimpleBitStorage(bits, 256);
        }
        int sea = fluid == null ? 0 : opaque(fluid);
        int ground = opaque(below);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                int cx = clamp(bx0 + x - x0) / PreviewTile.GRID;
                int cz = clamp(bz0 + z - z0) / PreviewTile.GRID;
                BlockState filler = state(tile.filler(cx, cz));
                int topBits = opaque(soil(state(tile.top(cx, cz)), filler));
                int fillerBits = opaque(filler);
                boolean flooded = fluid != null && Math.max(h, min) < seaLevel;
                for (int t = 0; t < maps.length; t++) {
                    int bit = 1 << t;
                    int first;
                    if (flooded && (sea & bit) != 0) {
                        first = seaLevel;
                    } else if ((topBits & bit) != 0) {
                        first = h;
                    } else if ((fillerBits & bit) != 0) {
                        first = h - 1;
                    } else if ((ground & bit) != 0) {
                        first = h - 1 - PreviewTile.FILLER_DEPTH;
                    } else {
                        first = min;
                    }
                    maps[t].set(z * 16 + x, Math.max(first, min) - min);
                }
            }
        }
        for (int t = 0; t < maps.length; t++) {
            chunk.setHeightmap(HEIGHTMAPS[t], maps[t].getRaw());
        }
    }

    /**
     * Runs the generator's surface rules on a stand-in, as
     * {@code NoiseBasedChunkGenerator.buildSurface} does in the real world,
     * with the biome source's biomes. Its noise chunk carries no structure:
     * the generator's own would ask the real world for the chunk's structure
     * references, generating real chunks from the preview's thread.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void layGround(ProtoChunk chunk) {
        var random = level.getChunkSource().randomState();
        if (noiseChunk == null) {
            // One for the tile, and it computes no density: the rules ask it only for the preliminary
            // surface level, under which `abovePreliminarySurface` fails; the preview reads the top
            // block, above it. Computed, it cost 30 to 50 ms a chunk in the user's pack (C2ME's
            // compiled density functions, and nothing cached across chunks), 1 to 3 ms in dev.
            var noiseSettings = surface.generatorSettings().value();
            var clamped = noiseSettings.noiseSettings().clampToHeightAccessor(level);
            Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel,
                    fluid != null ? fluid : Blocks.AIR.defaultBlockState());
            Beardifier none = new Beardifier((it.unimi.dsi.fastutil.objects.ObjectListIterator) it.unimi.dsi.fastutil
                    .objects.ObjectLists.emptyList().iterator(), (it.unimi.dsi.fastutil.objects.ObjectListIterator) it
                    .unimi.dsi.fastutil.objects.ObjectLists.emptyList().iterator());
            noiseChunk = new SurfaceNoise(16 / clamped.getCellWidth(), random, chunk.getPos().getMinBlockX(),
                    chunk.getPos().getMinBlockZ(), clamped, none, noiseSettings, (x, y, z) -> sea, level.getMinBuildHeight());
        }
        NoiseChunk shared = noiseChunk;
        chunk.getOrCreateNoiseChunk(c -> shared);
        // Rules may raise the ground (badlands pillars, icebergs) or open it (a swamp's water, powder
        // snow): each block they set updates the chunk's heightmaps (ProtoChunk.setBlockState at the
        // noise status), no priming again after them.
        surface.buildSurface(chunk, new WorldGenerationContext(surface, level), random, level.structureManager(),
                surfaceBiomes, level.registryAccess().registryOrThrow(Registries.BIOME), Blender.empty());
    }

    /** What the surface rules read; then all a feature may read. */
    private static final Set<Heightmap.Types> WORLD_GEN_HEIGHTMAPS =
            EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG);
    private static final Set<Heightmap.Types> ALL_HEIGHTMAPS = EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,
            Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);
    private static final Heightmap.Types[] HEIGHTMAPS = ALL_HEIGHTMAPS.toArray(new Heightmap.Types[0]);

    /** A noise chunk for the surface rules alone: every block is above its preliminary surface. */
    private static final class SurfaceNoise extends NoiseChunk {
        private final int bottom;

        SurfaceNoise(int cells, net.minecraft.world.level.levelgen.RandomState random, int x, int z,
                     net.minecraft.world.level.levelgen.NoiseSettings settings, Beardifier beardifier,
                     net.minecraft.world.level.levelgen.NoiseGeneratorSettings generator, Aquifer.FluidPicker fluid,
                     int bottom) {
            super(cells, random, x, z, settings, beardifier, generator, fluid, Blender.empty());
            this.bottom = bottom;
        }

        @Override
        public int preliminarySurfaceLevel(int x, int z) {
            return bottom;
        }
    }

    /**
     * The biome source's biome of a quart column, sampled once per tile at
     * the height first asked (the ground's: a stand-in's own biomes, then its
     * surface rules near the ground). Sampled per quart in three dimensions,
     * as the game's zoom asks, the surface rules took 30 ms a chunk in the
     * user's pack: each climate sample there costs about half a millisecond.
     * The surface has no underground biome to miss.
     */
    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> quartBiome(int qx, int qy, int qz) {
        long key = ChunkPos.asLong(qx, qz);
        var biome = quarts.get(key);
        if (biome == null) {
            biome = biomes.getNoiseBiome(qx, qy, qz, sampler);
            quarts.put(key, biome);
        }
        return biome;
    }

    /**
     * The tile's top and filler blocks of a surfaced chunk's cells, from each
     * cell's {@link #ruled} column (its top block and the one under it). The surface rules lay no snow: the game's snow layers come
     * with a feature (freeze_top_layer), so above the sea a cell cold enough
     * to snow keeps a snow top over the rules' block, as the guess had it.
     */
    private void readCells(ProtoChunk chunk, int[] heights) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int lx = chunkX - (x0 >> 4);
        int lz = chunkZ - (z0 >> 4);
        cellsRead[lz * PreviewTile.CHUNKS + lx] = true;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int per = 16 / PreviewTile.GRID;
        for (int gz = 0; gz < per; gz++) {
            for (int gx = 0; gx < per; gx++) {
                int cx = lx * per + gx;
                int cz = lz * per + gz;
                if (keepCells != null && keepCells[cz * PreviewTile.CELLS + cx]) {
                    continue;
                }
                int x = gx * PreviewTile.GRID + PreviewTile.GRID / 2;
                int z = gz * PreviewTile.GRID + PreviewTile.GRID / 2;
                int y = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z); // the top block itself
                int top = id(chunk.getBlockState(pos.set(x, y, z)));
                int filler = id(chunk.getBlockState(pos.set(x, y - 1, z)));
                int highest = Integer.MIN_VALUE;
                for (int hz = gz * PreviewTile.GRID; hz < (gz + 1) * PreviewTile.GRID; hz++) {
                    for (int hx = gx * PreviewTile.GRID; hx < (gx + 1) * PreviewTile.GRID; hx++) {
                        highest = Math.max(highest, heights[hz * 16 + hx]);
                    }
                }
                int bx = (chunkX << 4) + gx * PreviewTile.GRID + PreviewTile.GRID / 2;
                int bz = (chunkZ << 4) + gz * PreviewTile.GRID + PreviewTile.GRID / 2;
                if (highest > seaLevel && fluid != null
                        && quartBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(highest), QuartPos.fromBlock(bz))
                        .value().coldEnoughToSnow(pos.set(bx, highest, bz))) {
                    filler = top;
                    top = id(Blocks.SNOW_BLOCK.defaultBlockState());
                }
                tile.setCell(cx, cz, top, filler);
            }
        }
    }

    private int id(BlockState state) {
        return world.blockStates().id(BlockKeys.key(state));
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
            if (x < x0 - margin || z < z0 - margin || x >= x0 + PreviewTile.BLOCKS + margin
                    || z >= z0 + PreviewTile.BLOCKS + margin) {
                continue;
            }
            int g = ground(x, z);
            if (y < g) {
                // A lake digs the ground and fills it (ADR-118): kept down to PIT_DEPTH, on land
                // only (the tile's sea already covers the ground under it), resolved in store().
                if (g - y <= PIT_DEPTH && g >= seaLevel && (state.isAir() || state.getBlock() instanceof LiquidBlock)
                        && !bare.test(x, z)) {
                    dug.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> new BlockState[PIT_DEPTH])[g - y - 1] = state;
                }
                continue;
            }
            if (state.isAir() || x < x0 - margin || z < z0 - margin || x >= x0 + PreviewTile.BLOCKS + margin
                    || z >= z0 + PreviewTile.BLOCKS + margin || y < ground(x, z) || bare.test(x, z)) {
                continue;
            }
            int emission = state.getLightEmission();
            if (emission > 0) {
                // Kept whatever the block becomes below (water, nothing, a voxel too empty to
                // show): its light is what the LODs show of a sea pickle or a torch (ADR-117).
                lights.put(pos, Math.max(lights.get(pos), Math.min(15, emission)));
            }
            if (y < seaLevel && BlockKeys.isSubmergedPlant(state)) {
                // Kelp and seagrass: counted per column, drawn in the tile's water (ADR-105).
                if (x >= x0 && z >= z0 && x < x0 + PreviewTile.BLOCKS && z < z0 + PreviewTile.BLOCKS) {
                    int[] column = seaColumns.computeIfAbsent(BlockPos.asLong(x, 0, z),
                            k -> new int[]{Integer.MAX_VALUE, 0, -1});
                    column[1]++;
                    if (y < column[0]) {
                        column[0] = y;
                        column[2] = world.blockStates().id(BlockKeys.key(state));
                    }
                }
                continue;
            }
            if (y < seaLevel && (!state.getFluidState().isEmpty() || state.is(Blocks.ICE))) {
                continue; // the tile's sea, its ice, and what stands in it as water (coral fans, pickles)
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
        storeSeaPlants();
        storePits();
        int[] pairs = new int[lights.size() * 2];
        int n = 0;
        for (var e : lights.long2IntEntrySet()) {
            long pos = e.getLongKey();
            pairs[n++] = PreviewTile.lightWord(BlockPos.getX(pos) - x0, BlockPos.getZ(pos) - z0, e.getIntValue());
            pairs[n++] = BlockPos.getY(pos);
        }
        tile.setLights(pairs);
    }

    /**
     * Writes the dug columns into the tile (ADR-118): from the ground down,
     * the run of air and fluid the features wrote with no ground left above
     * it (a lake open to the sky, not a pocket under the ground), the fluid
     * filling it from the bottom up to its highest fluid block. The fluid's
     * top block is a light source when it glows (lava).
     */
    private void storePits() {
        it.unimi.dsi.fastutil.ints.IntArrayList entries = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (var e : dug.long2ObjectEntrySet()) {
            BlockState[] column = e.getValue();
            int depth = 0;
            BlockState fluid = null;
            int fluidDepth = -1;
            while (depth < PIT_DEPTH && column[depth] != null) {
                if (fluid == null && !column[depth].isAir()) {
                    fluid = column[depth];
                    fluidDepth = depth;
                }
                depth++;
            }
            if (depth == 0) {
                continue; // nothing written right under the surface: a pocket in the ground
            }
            int x = BlockPos.getX(e.getLongKey());
            int z = BlockPos.getZ(e.getLongKey());
            int g = ground(x, z);
            int bottom = g - depth;
            int fluidTop = fluid == null ? bottom : g - fluidDepth;
            entries.add(PreviewTile.pitWord(x - x0, z - z0));
            entries.add(bottom);
            entries.add(fluidTop);
            entries.add(fluid == null ? -1 : world.blockStates().id(BlockKeys.key(fluid)));
            int emission = fluid == null ? 0 : Math.min(15, fluid.getLightEmission());
            if (emission > 0) {
                long pos = BlockPos.asLong(x, fluidTop - 1, z);
                lights.put(pos, Math.max(lights.get(pos), emission));
            }
        }
        dug.clear();
        tile.setPits(entries.toIntArray());
    }

    /** Per chunk and lowest block's material: the columns of plants under the sea and their mean height. */
    private void storeSeaPlants() {
        @SuppressWarnings("unchecked")
        Int2ObjectOpenHashMap<long[]>[] perChunk = new Int2ObjectOpenHashMap[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
        for (var e : seaColumns.long2ObjectEntrySet()) {
            int x = BlockPos.getX(e.getLongKey()) - x0;
            int z = BlockPos.getZ(e.getLongKey()) - z0;
            int c = (z >> 4) * PreviewTile.CHUNKS + (x >> 4);
            int[] column = e.getValue();
            if (perChunk[c] == null) {
                perChunk[c] = new Int2ObjectOpenHashMap<>();
            }
            long[] sum = perChunk[c].computeIfAbsent(column[2], m -> new long[2]);
            sum[0]++;
            sum[1] += column[1];
        }
        for (int c = 0; c < perChunk.length; c++) {
            if (perChunk[c] == null) {
                continue;
            }
            int[] entries = new int[perChunk[c].size()];
            int n = 0;
            for (var m : perChunk[c].int2ObjectEntrySet()) {
                int kind = tile.plantKind(m.getIntKey());
                if (kind >= 0) {
                    long[] sum = m.getValue();
                    entries[n++] = PreviewTile.seaPlantEntry(kind, sum[0], (double) sum[1] / sum[0]);
                }
            }
            tile.setSeaPlants(c % PreviewTile.CHUNKS, c / PreviewTile.CHUNKS, java.util.Arrays.copyOf(entries, n));
        }
    }
}
