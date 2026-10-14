package io.lakio.farfield.server;

import io.lakio.farfield.core.world.LodWorld;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The LOD state of one server dimension: its {@link LodWorld} (stored in
 * {@code <dimension folder>/farfield/}), block-state and biome id caches, and
 * the chunks waiting to be snapshotted.
 */
final class LodLevel {

    final ServerLevel level;
    final Path dir;
    final Path regionDir;
    final LodWorld world;

    private final Map<BlockState, Integer> blockIds = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, Integer> biomeIds = new ConcurrentHashMap<>();

    /** Loaded chunks (packed {@link ChunkPos}): the importer leaves them to the live path. */
    final Set<Long> loaded = ConcurrentHashMap.newKeySet();
    /** Chunk -> server tick from which it may be snapshotted (after a load or the last block change). */
    final Map<Long, Long> due = new ConcurrentHashMap<>();
    /** Block changes seen in loaded chunks, and snapshots taken, for the logs. */
    final AtomicLong blockChanges = new AtomicLong();
    final AtomicLong snapshots = new AtomicLong();

    private LodLevel(ServerLevel level, Path dir, Path regionDir, LodWorld world) {
        this.level = level;
        this.dir = dir;
        this.regionDir = regionDir;
        this.world = world;
    }

    static LodLevel open(ServerLevel level) throws IOException {
        Path dimension = DimensionType.getStorageFolder(level.dimension(),
                level.getServer().getWorldPath(LevelResource.ROOT));
        Path dir = dimension.resolve("farfield");
        return new LodLevel(level, dir, dimension.resolve("region"), LodWorld.open(dir, BlockKeys::resolve));
    }

    String name() {
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
