package io.lakio.farfield.lod;

import io.lakio.farfield.core.world.LodWorld;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

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
    /** Chunk -> game tick from which it may be snapshotted (after a load or the last block change). */
    final Map<Long, Long> due = new ConcurrentHashMap<>();
    /** Block changes seen in loaded chunks, and snapshots taken, for the logs. */
    final AtomicLong blockChanges = new AtomicLong();
    final AtomicLong snapshots = new AtomicLong();
    /** Snapshots taken when the chunk unloaded, and without complete light. */
    final AtomicLong snapshotsAtUnload = new AtomicLong();
    final AtomicLong snapshotsWithoutLight = new AtomicLong();
    /** Due chunks never snapshotted (not available for 60 s and no unload seen). */
    final AtomicLong dropped = new AtomicLong();
    /** Counters at the last periodic log line. */
    long loggedSnapshots;
    long loggedDropped;

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
