package io.lakio.farfield.lod;

import io.lakio.farfield.core.world.LodWorld;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The LOD state of one level (server or client side) in a
 * {@link LodPipeline}: its {@link LodWorld}, block-state and biome id caches,
 * and the chunks waiting to be snapshotted.
 */
public final class LodLevel {

    final Level level;
    private final Path dir;
    private final LodWorld world;

    private final Map<BlockState, Integer> blockIds = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, Integer> biomeIds = new ConcurrentHashMap<>();

    /** Loaded chunks (packed {@link ChunkPos}): an import leaves them to the live path. */
    final Set<Long> loaded = ConcurrentHashMap.newKeySet();
    /**
     * A chunk to snapshot from a game tick on (after a load or its last block
     * change), with the chunk object itself: once the player moves away a
     * loaded chunk becomes a border chunk that {@code getChunkNow} no longer
     * returns, though it stays in memory until it unloads.
     */
    record Due(long tick, LevelChunk chunk) {
    }

    /** Chunk position -> when and what to snapshot. */
    final Map<Long, Due> due = new ConcurrentHashMap<>();
    /** Block changes seen in loaded chunks, and snapshots taken, for the logs. */
    final AtomicLong blockChanges = new AtomicLong();
    final AtomicLong snapshots = new AtomicLong();
    /** Snapshots taken when the chunk unloaded, and without complete light. */
    final AtomicLong snapshotsAtUnload = new AtomicLong();
    final AtomicLong snapshotsWithoutLight = new AtomicLong();
    /** Snapshots of chunks that {@code getChunkNow} did not return (border chunks), for the logs. */
    final AtomicLong snapshotsOfBorderChunks = new AtomicLong();
    /** Snapshots of chunks still waiting when the pipeline stopped. */
    final AtomicLong snapshotsAtStop = new AtomicLong();
    /** Counter at the last periodic log line. */
    long loggedSnapshots;

    private LodLevel(Level level, Path dir, LodWorld world) {
        this.level = level;
        this.dir = dir;
        this.world = world;
    }

    static LodLevel open(Level level, Path dir) throws IOException {
        return new LodLevel(level, dir, LodWorld.open(dir, BlockKeys::resolve));
    }

    public Level level() {
        return level;
    }

    public Path dir() {
        return dir;
    }

    public LodWorld world() {
        return world;
    }

    /** True while the chunk is loaded in this level. */
    public boolean isLoaded(int chunkX, int chunkZ) {
        return loaded.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public String name() {
        return level.dimension().location().toString();
    }

    int blockId(BlockState state) {
        Integer id = blockIds.get(state);
        if (id == null) {
            id = world.blockStates().id(BlockKeys.key(state));
            blockIds.put(state, id);
        }
        return id;
    }

    int biomeId(Holder<Biome> biome) {
        Integer id = biomeIds.get(biome);
        if (id == null) {
            id = world.biomes().id(BlockKeys.key(biome));
            biomeIds.put(biome, id);
        }
        return id;
    }
}
