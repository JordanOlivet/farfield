package io.lakio.farfield.client.render;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.BinaryGreedyMesher;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.preview.PreviewStore;
import io.lakio.farfield.core.preview.PreviewTile;
import io.lakio.farfield.core.preview.PreviewVoxels;
import io.lakio.farfield.core.select.LodSelector;
import io.lakio.farfield.core.world.LodWorld;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/**
 * The noise preview's side of the renderer (ADR-039): its own octree
 * selection over the preview tiles, with sections of every level built on
 * demand from the tiles ({@link PreviewVoxels}). Preview sections use the
 * renderer's keys with {@link #KEY_LEVEL_OFFSET} added to their level, so
 * they live beside the real ones in the mesh cache. Chunks with real LOD
 * data are masked out: per tile, the chunks whose real level-4 column holds
 * a voxel; a tile's sections exist for the selection only once its mask is
 * known. A real write in a tile refreshes its mask in the background while
 * the old one stays in use (dropping it made the tile vanish for a frame on
 * every write: flicker, and holes next to explored land); only a mask that
 * changed meshes the tile again.
 */
final class PreviewLayer {

    static final int KEY_LEVEL_OFFSET = 8;
    private static final byte EXISTS = 1;
    private static final byte ABSENT = 2;
    private static final int TOP_LEVEL = LodWorld.LEVELS - 1;
    /** Block-state key of the mask material: unknown to the game, so opaque, never drawn. */
    static final String MASK_KEY = "farfield:preview_mask";
    private static final int[][] STEP = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private final LodSelector selector = new LodSelector(LodWorld.LEVELS, 4.0);
    private final Executor executor;
    /** Selection existence of plain keys, render thread; cleared whenever tiles or masks change. */
    private final Long2ByteOpenHashMap existence = new Long2ByteOpenHashMap();
    /** Per tile, its chunks with real data: 32 rows (z) of bits (x). */
    private final Map<Long, int[]> masks = new ConcurrentHashMap<>();
    private final Set<Long> maskPending = ConcurrentHashMap.newKeySet();
    /** Tiles whose real data changed since their mask was last read. */
    private final Set<Long> maskDirty = ConcurrentHashMap.newKeySet();
    /** Tiles whose sections must be meshed again: written again, or their real data changed. */
    private final ConcurrentLinkedQueue<Long> changedTiles = new ConcurrentLinkedQueue<>();
    private final LongConsumer tileListener = changedTiles::add;
    private volatile boolean masksChanged;
    private volatile LodWorld world;
    private volatile int minY;
    private volatile int maxY;

    PreviewLayer(Executor executor) {
        this.executor = executor;
    }

    static boolean isPreview(long key) {
        return LodKey.level(key) >= KEY_LEVEL_OFFSET;
    }

    static long toPreview(long plain) {
        return LodKey.pack(LodKey.level(plain) + KEY_LEVEL_OFFSET, LodKey.x(plain), LodKey.y(plain), LodKey.z(plain));
    }

    static long toPlain(long key) {
        return LodKey.pack(LodKey.level(key) - KEY_LEVEL_OFFSET, LodKey.x(key), LodKey.y(key), LodKey.z(key));
    }

    /** The level a section is drawn at. */
    static int drawLevel(long key) {
        int level = LodKey.level(key);
        return level >= KEY_LEVEL_OFFSET ? level - KEY_LEVEL_OFFSET : level;
    }

    /** Follows another world's tiles; everything known is dropped. Render thread. */
    void attach(LodWorld next) {
        LodWorld previous = world;
        if (previous != null) {
            previous.preview().removeListener(tileListener);
        }
        world = next;
        existence.clear();
        masks.clear();
        maskPending.clear();
        maskDirty.clear();
        changedTiles.clear();
        if (next != null) {
            next.preview().addListener(tileListener);
        }
    }

    void setDetail(double detail) {
        selector.setDetail(detail);
    }

    /**
     * Selects preview sections like the real ones; {@code draw} and
     * {@code want} receive preview keys. Render thread.
     */
    void select(double camX, double camY, double camZ, int levelMinY, int levelMaxY, double distance,
                LongPredicate ready, LongConsumer draw, LongConsumer want) {
        if (world == null) {
            return;
        }
        minY = levelMinY;
        maxY = levelMaxY;
        selector.select(camX, camY, camZ, levelMinY, levelMaxY, distance, new LodSelector.Sections() {
            @Override
            public boolean exists(long plain) {
                return sectionExists(plain);
            }

            @Override
            public boolean ready(long plain) {
                return ready.test(toPreview(plain));
            }
        }, plain -> draw.accept(toPreview(plain)), plain -> want.accept(toPreview(plain)));
    }

    /** A real section was written: a level-4 one may change its tile's mask. Render thread. */
    void realWritten(long key) {
        LodWorld w = world;
        if (w != null && LodKey.level(key) == TOP_LEVEL) {
            long tile = PreviewStore.key(LodKey.x(key), LodKey.z(key));
            if (masks.containsKey(tile)) {
                maskDirty.add(tile);
                requestMask(w, tile);
            }
        }
    }

    /**
     * The tiles whose resident preview sections must be meshed again, since
     * the last call; also tells whether the selection must run again. Render thread.
     */
    boolean drainChanges(LongOpenHashSet tiles) {
        boolean changed = masksChanged;
        masksChanged = false;
        Long tile;
        while ((tile = changedTiles.poll()) != null) {
            tiles.add(tile.longValue());
            changed = true;
        }
        if (changed) {
            existence.clear();
        }
        return changed;
    }

    /** The tile a preview key's section lies in. */
    static long tileOf(long key) {
        int shift = drawLevel(key);
        // A level-L section spans 32 << L blocks; a tile 512 = 32 << 4.
        return PreviewStore.key(LodKey.x(key) >> (TOP_LEVEL - shift), LodKey.z(key) >> (TOP_LEVEL - shift));
    }

    private boolean sectionExists(long plain) {
        byte known = existence.get(plain);
        if (known != 0) {
            return known == EXISTS;
        }
        boolean exists = computeExists(plain);
        // A tile whose mask is not known yet is asked again at the next selection.
        if (exists || masks.containsKey(tileOf(plain))) {
            existence.put(plain, exists ? EXISTS : ABSENT);
        }
        return exists;
    }

    private boolean computeExists(long plain) {
        LodWorld w = world;
        int level = LodKey.level(plain);
        int s = 1 << level;
        int size = LodSection.SIZE * s;
        int bx = LodKey.x(plain) * size;
        int bz = LodKey.z(plain) * size;
        int tileX = Math.floorDiv(bx, PreviewTile.BLOCKS);
        int tileZ = Math.floorDiv(bz, PreviewTile.BLOCKS);
        PreviewTile.Summary summary = w.preview().summary(tileX, tileZ);
        if (summary == null) {
            return false;
        }
        int cx0 = Math.floorMod(bx, PreviewTile.BLOCKS) >> 4;
        int cz0 = Math.floorMod(bz, PreviewTile.BLOCKS) >> 4;
        int chunks = size >> 4;
        int y0 = LodKey.y(plain) * size;
        if (!summary.spans(cx0, cz0, cx0 + chunks - 1, cz0 + chunks - 1, y0, y0 + size, s)) {
            return false;
        }
        long tile = PreviewStore.key(tileX, tileZ);
        int[] mask = masks.get(tile);
        if (mask == null) {
            requestMask(w, tile);
            return false;
        }
        int bits = chunks >= 32 ? -1 : ((1 << chunks) - 1) << cx0;
        for (int cz = cz0; cz < cz0 + chunks; cz++) {
            if ((mask[cz] & bits) != bits) {
                return true;
            }
        }
        return false; // every chunk has real data
    }

    /**
     * Reads a tile's mask in the background, again while real data keeps
     * changing in it; the mask in use stays until the new one replaces it.
     */
    private void requestMask(LodWorld w, long tile) {
        if (!maskPending.add(tile)) {
            return;
        }
        executor.execute(() -> {
            try {
                do {
                    maskDirty.remove(tile);
                    int[] fresh = readMask(w, tile);
                    int[] old = masks.put(tile, fresh);
                    if (old == null || !Arrays.equals(old, fresh)) {
                        masksChanged = true;
                        if (old != null) {
                            changedTiles.add(tile);
                        }
                    }
                } while (maskDirty.contains(tile) && world == w);
            } catch (IOException | RuntimeException e) {
                // Asked again at the next selection or write.
            } finally {
                maskPending.remove(tile);
            }
            // A write between the last read and the release above.
            if (maskDirty.contains(tile) && world == w) {
                requestMask(w, tile);
            }
        });
    }

    /** The tile's mask, read from the real level-4 sections when not known. Any thread. */
    private int[] mask(LodWorld w, long tile) throws IOException {
        int[] mask = masks.get(tile);
        if (mask != null) {
            return mask;
        }
        mask = readMask(w, tile);
        int[] raced = masks.putIfAbsent(tile, mask);
        return raced != null ? raced : mask;
    }

    /** The chunks of a tile whose real level-4 column holds a voxel. Any thread. */
    private int[] readMask(LodWorld w, long tile) throws IOException {
        int[] mask = new int[PreviewTile.CHUNKS];
        int x = PreviewStore.tileX(tile);
        int z = PreviewStore.tileZ(tile);
        int size = LodSection.SIZE << TOP_LEVEL;
        int air = w.air();
        for (int y = Math.floorDiv(minY, size); y <= Math.floorDiv(maxY - 1, size); y++) {
            long key = LodKey.pack(TOP_LEVEL, x, y, z);
            LodSection real = w.exists(key) ? w.read(key) : null;
            if (real == null) {
                continue;
            }
            for (int cz = 0; cz < LodSection.SIZE; cz++) {
                for (int cx = 0; cx < LodSection.SIZE; cx++) {
                    if ((mask[cz] >>> cx & 1) != 0) {
                        continue;
                    }
                    for (int cy = 0; cy < LodSection.SIZE; cy++) {
                        if (real.material(cx, cy, cz) != air) {
                            mask[cz] |= 1 << cx;
                            break;
                        }
                    }
                }
            }
        }
        return mask;
    }

    /** The section of a preview key, real chunks left empty; null when empty. Mesher threads. */
    LodSection build(LodWorld w, long key) throws IOException {
        return build(w, key, -1, 0);
    }

    private LodSection build(LodWorld w, long key, int layerAxis, int layerIndex) throws IOException {
        PreviewStore store = w.preview();
        PreviewVoxels.Tiles tiles = (x, z) -> {
            try {
                return store.read(x, z);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        return PreviewVoxels.build(drawLevel(key), LodKey.x(key), LodKey.y(key), LodKey.z(key), tiles, maskOf(w),
                w.air(), minY, maskMaterial(w), layerAxis, layerIndex);
    }

    /** The material id of masked columns, whose quads {@code LodRenderer} drops. */
    static int maskMaterial(LodWorld w) {
        return w.blockStates().id(MASK_KEY);
    }

    private PreviewVoxels.Mask maskOf(LodWorld w) {
        return (chunkX, chunkZ) -> {
            long tile = PreviewStore.key(Math.floorDiv(chunkX, PreviewTile.CHUNKS), Math.floorDiv(chunkZ, PreviewTile.CHUNKS));
            int[] mask;
            try {
                mask = mask(w, tile);
            } catch (IOException e) {
                return false;
            }
            return (mask[Math.floorMod(chunkZ, PreviewTile.CHUNKS)] >>> Math.floorMod(chunkX, PreviewTile.CHUNKS) & 1) != 0;
        };
    }

    /**
     * The solid border layers of a preview section's six neighbors, built
     * one layer each, for translucent faces (water spanning sections shows
     * no inner walls). Mesher threads.
     */
    int[][] solidNeighbors(LodWorld w, long key, MaterialClassifier classes, int[][] planes) throws IOException {
        int[][] solid = new int[6][];
        for (int d = 0; d < 6; d++) {
            int y = LodKey.y(key) + STEP[d][1];
            if (y < LodKey.Y_MIN || y > LodKey.Y_MAX) {
                continue;
            }
            long neighbor = LodKey.pack(LodKey.level(key), LodKey.x(key) + STEP[d][0], y, LodKey.z(key) + STEP[d][2]);
            // The neighbor in the + direction touches with its layer 0, the other with layer 31.
            LodSection layer = build(w, neighbor, d >> 1, (d & 1) != 0 ? 0 : LodSection.SIZE - 1);
            if (layer != null) {
                solid[d] = BinaryGreedyMesher.borderPlane(layer, d, classes, true, planes[d]);
            }
        }
        return solid;
    }
}
