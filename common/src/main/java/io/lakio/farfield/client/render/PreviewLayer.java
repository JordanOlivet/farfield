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
 * known. The mask also holds each real chunk's ground and fluid top (from
 * the real level-4 voxels and their fill): preview columns beside a real
 * sea go down to its floor, and the real column's mask materials hide only
 * what the real terrain covers. A real write in a tile refreshes its mask in the background while
 * the old one stays in use (dropping it made the tile vanish for a frame on
 * every write: flicker, and holes next to explored land); only a mask that
 * changed meshes the tile again.
 */
final class PreviewLayer {

    static final int KEY_LEVEL_OFFSET = 8;
    private static final byte EXISTS = 1;
    private static final byte ABSENT = 2;
    private static final int TOP_LEVEL = LodWorld.LEVELS - 1;
    /** Mask layout: 32 rows of real-chunk bits (z, bit x), then per chunk (z * 32 + x) the ground, then the fluid top. */
    private static final int GROUND = PreviewTile.CHUNKS;
    private static final int FLUID = GROUND + PreviewTile.CHUNKS * PreviewTile.CHUNKS;
    private static final int MASK_INTS = FLUID + PreviewTile.CHUNKS * PreviewTile.CHUNKS;
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
    /**
     * Masks that hide the preview over more chunks, waiting until the real
     * terrain drawn there is meshed from its current data (ADR-076); with the
     * time they were read.
     */
    private final Map<Long, int[]> heldMasks = new ConcurrentHashMap<>();
    private final Map<Long, Long> heldSince = new ConcurrentHashMap<>();
    /** A held mask goes in after this long anyway: real terrain the selection does not draw there. */
    private static final long HOLD_NANOS = 2_000_000_000L;
    /** Tiles whose sections must be meshed again: written again, or their real data changed. */
    private final ConcurrentLinkedQueue<Long> changedTiles = new ConcurrentLinkedQueue<>();
    /**
     * Per changed tile, its chunks whose preview sections must be meshed
     * again (32 rows of bits), or null for all of them: a mask change only
     * reaches the chunks it changed and those beside them (ADR-077).
     */
    private final Map<Long, int[]> changedChunks = new ConcurrentHashMap<>();
    private static final int[] ALL_CHUNKS = filledRows();
    private final LongConsumer tileListener = tile -> changed(tile, ALL_CHUNKS);
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
        heldMasks.clear();
        heldSince.clear();
        changedTiles.clear();
        changedChunks.clear();
        if (next != null) {
            next.preview().addListener(tileListener);
        }
    }

    void setDetail(double detail) {
        selector.setDetail(detail);
    }

    /** See {@link LodSelector#setForcedLevel}: the level lock applies to the preview too. */
    void setForcedLevel(int level) {
        selector.setForcedLevel(level);
    }

    /** See {@link LodSelector#setOutsideView}; {@code inView} takes plain keys (their level is the draw level). */
    void setOutsideView(LongPredicate inView, double detail) {
        selector.setOutsideView(inView, detail);
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
    boolean drainChanges(Map<Long, int[]> tiles) {
        boolean changed = masksChanged;
        masksChanged = false;
        Long tile;
        while ((tile = changedTiles.poll()) != null) {
            int[] rows = changedChunks.remove(tile);
            if (rows != null) {
                tiles.merge(tile, rows, (a, b) -> {
                    for (int z = 0; z < a.length; z++) {
                        a[z] |= b[z];
                    }
                    return a;
                });
            }
            changed = true;
        }
        if (changed) {
            existence.clear();
        }
        return changed;
    }

    /** Whether the preview hides a chunk for real terrain, for {@code debug here}. Render thread. */
    String describeChunk(int chunkX, int chunkZ) {
        long tile = PreviewStore.key(Math.floorDiv(chunkX, PreviewTile.CHUNKS), Math.floorDiv(chunkZ, PreviewTile.CHUNKS));
        int[] mask = masks.get(tile);
        if (mask == null) {
            return "mask of its tile not built";
        }
        int c = chunk(chunkX, chunkZ);
        int[] held = heldMasks.get(tile);
        String hold = held == null ? ""
                : (held[Math.floorMod(chunkZ, PreviewTile.CHUNKS)] >>> Math.floorMod(chunkX, PreviewTile.CHUNKS) & 1) != 0
                ? "; a held mask hides it once the real terrain is drawn" : "; a held mask for its tile";
        if ((mask[Math.floorMod(chunkZ, PreviewTile.CHUNKS)] >>> Math.floorMod(chunkX, PreviewTile.CHUNKS) & 1) == 0) {
            return "shown (no real data at level " + TOP_LEVEL + ")" + hold;
        }
        return "hidden for real terrain (level " + TOP_LEVEL + " ground at " + mask[GROUND + c] + ", fluid top "
                + mask[FLUID + c] + ")" + hold;
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
        long tile = PreviewStore.key(tileX, tileZ);
        int[] mask = masks.get(tile);
        if (mask == null) {
            requestMask(w, tile);
            return false;
        }
        int bits = chunks >= 32 ? -1 : ((1 << chunks) - 1) << cx0;
        boolean preview = false;
        for (int cz = cz0; cz < cz0 + chunks; cz++) {
            preview |= (mask[cz] & bits) != bits;
        }
        if (!preview) {
            return false; // every chunk has real data
        }
        // Real chunks around go down to their ground: so do the preview columns beside them.
        int lowest = Integer.MAX_VALUE;
        for (int cz = Math.max(0, cz0 - 1); cz <= Math.min(PreviewTile.CHUNKS - 1, cz0 + chunks); cz++) {
            for (int cx = Math.max(0, cx0 - 1); cx <= Math.min(PreviewTile.CHUNKS - 1, cx0 + chunks); cx++) {
                if ((mask[cz] >>> cx & 1) != 0) {
                    lowest = Math.min(lowest, mask[GROUND + cz * PreviewTile.CHUNKS + cx]);
                }
            }
        }
        return summary.spans(cx0, cz0, cx0 + chunks - 1, cz0 + chunks - 1, y0, y0 + size, s, lowest);
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
                    int[] old = masks.get(tile);
                    if (old != null && hidesMore(old, fresh)) {
                        // Real chunks came in: the preview stays over them until the real
                        // terrain drawn there is meshed (applyHeld), or it showed a hole meanwhile.
                        heldMasks.put(tile, fresh);
                        heldSince.putIfAbsent(tile, System.nanoTime());
                        continue;
                    }
                    heldMasks.remove(tile);
                    heldSince.remove(tile);
                    masks.put(tile, fresh);
                    if (old == null || !Arrays.equals(old, fresh)) {
                        masksChanged = true;
                        if (old != null) {
                            changed(tile, changedChunks(old, fresh));
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

    private static int[] filledRows() {
        int[] rows = new int[PreviewTile.CHUNKS];
        Arrays.fill(rows, -1);
        return rows;
    }

    /** Records chunks of a tile to mesh again; any thread. */
    private void changed(long tile, int[] rows) {
        changedChunks.merge(tile, rows.clone(), (a, b) -> {
            for (int z = 0; z < a.length; z++) {
                a[z] |= b[z];
            }
            return a;
        });
        changedTiles.add(tile);
    }

    /**
     * The chunks two masks of a tile differ on (real or not, ground, fluid
     * top), and the chunks beside them: preview columns go down to the
     * ground of the real chunks around.
     */
    private static int[] changedChunks(int[] old, int[] fresh) {
        if (old == null) {
            return ALL_CHUNKS;
        }
        int n = PreviewTile.CHUNKS;
        int[] rows = new int[n];
        for (int z = 0; z < n; z++) {
            int differ = old[z] ^ fresh[z];
            for (int x = 0; x < n; x++) {
                int c = z * n + x;
                if (old[GROUND + c] != fresh[GROUND + c] || old[FLUID + c] != fresh[FLUID + c]) {
                    differ |= 1 << x;
                }
            }
            rows[z] = differ;
        }
        int[] grown = new int[n];
        for (int z = 0; z < n; z++) {
            int row = rows[z] | rows[z] << 1 | rows[z] >>> 1;
            grown[z] |= row;
            if (z > 0) {
                grown[z - 1] |= row;
            }
            if (z + 1 < n) {
                grown[z + 1] |= row;
            }
        }
        return grown;
    }

    /**
     * Whether a preview section's footprint meets the chunks of its tile
     * marked in {@code rows}.
     */
    static boolean meets(long key, int[] rows) {
        int level = drawLevel(key);
        int chunks = 2 << level;
        int x0 = Math.floorMod(LodKey.x(key) * chunks, PreviewTile.CHUNKS);
        int z0 = Math.floorMod(LodKey.z(key) * chunks, PreviewTile.CHUNKS);
        int bits = chunks >= 32 ? -1 : ((1 << chunks) - 1) << x0;
        for (int z = z0; z < z0 + chunks; z++) {
            if ((rows[z] & bits) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code fresh} marks a chunk real that {@code old} did not. */
    private static boolean hidesMore(int[] old, int[] fresh) {
        for (int z = 0; z < PreviewTile.CHUNKS; z++) {
            if ((fresh[z] & ~old[z]) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether the real terrain drawn over a chunk is meshed from its current data. */
    interface RealReady {
        boolean test(int chunkX, int chunkZ);
    }

    /**
     * Puts in the chunks of the held masks whose real terrain is drawn and
     * meshed from its current data; a whole mask after {@link #HOLD_NANOS}.
     * Render thread, before {@link #drainChanges}.
     */
    void applyHeld(RealReady ready, long now) {
        if (heldMasks.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, int[]> e : heldMasks.entrySet()) {
            long tile = e.getKey();
            int[] fresh = e.getValue();
            int[] old = masks.get(tile);
            Long since = heldSince.get(tile);
            boolean go = old == null || since == null || now - since > HOLD_NANOS;
            // Chunk by chunk: the preview leaves each new real chunk once that one is drawn. Held for
            // the whole tile until every new chunk was, it stayed over real terrain for seconds at a
            // generation front, where new chunks keep coming (ADR-087).
            int[] partial = null;
            if (!go) {
                int baseX = PreviewStore.tileX(tile) * PreviewTile.CHUNKS;
                int baseZ = PreviewStore.tileZ(tile) * PreviewTile.CHUNKS;
                for (int z = 0; z < PreviewTile.CHUNKS; z++) {
                    int added = fresh[z] & ~old[z];
                    while (added != 0) {
                        int x = Integer.numberOfTrailingZeros(added);
                        added &= added - 1;
                        if (!ready.test(baseX + x, baseZ + z)) {
                            if (partial == null) {
                                partial = fresh.clone();
                            }
                            int c = z * PreviewTile.CHUNKS + x;
                            partial[z] &= ~(1 << x);
                            partial[GROUND + c] = old[GROUND + c];
                            partial[FLUID + c] = old[FLUID + c];
                        }
                    }
                }
                go = partial == null;
            }
            if (go) {
                if (heldMasks.remove(tile, fresh)) {
                    heldSince.remove(tile);
                    if (old != null && since != null) {
                        countHeld(old, fresh, now - since, now - since > HOLD_NANOS);
                    }
                    masks.put(tile, fresh);
                    masksChanged = true;
                    changed(tile, changedChunks(old, fresh));
                }
            } else if (!Arrays.equals(partial, old) && heldMasks.get(tile) == fresh) {
                countHeld(old, partial, now - since, false);
                masks.put(tile, partial);
                masksChanged = true;
                changed(tile, changedChunks(old, partial));
            }
        }
    }

    /** Chunks the preview left after a hold since the last report, their summed and longest hold, and those timed out. */
    private long heldChunks;
    private long heldNanos;
    private long heldMaxNanos;
    private long heldTimedOut;

    /**
     * Counts the chunks {@code to} hides that {@code from} did not, held for
     * at most {@code nanos} (from the tile's first hold). Render thread.
     */
    private void countHeld(int[] from, int[] to, long nanos, boolean timedOut) {
        int n = 0;
        for (int z = 0; z < PreviewTile.CHUNKS; z++) {
            n += Integer.bitCount(to[z] & ~from[z]);
        }
        heldChunks += n;
        heldNanos += nanos * n;
        heldMaxNanos = Math.max(heldMaxNanos, nanos);
        heldTimedOut += timedOut ? n : 0;
    }

    /**
     * How long the preview stayed over new real chunks since the last call,
     * for the renderer's holes line (ADR-087); resets. Render thread.
     */
    String heldReport() {
        String out = heldChunks + " chunks left by the preview after " + (heldChunks == 0 ? 0 : heldNanos / heldChunks / 1_000_000)
                + " ms on average, " + heldMaxNanos / 1_000_000 + " ms at most, " + heldTimedOut + " at the 2 s limit";
        heldChunks = 0;
        heldNanos = 0;
        heldMaxNanos = 0;
        heldTimedOut = 0;
        return out;
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

    /**
     * The chunks of a tile whose real level-4 column holds a voxel, with
     * their ground (the first block above the highest solid voxel's fill)
     * and fluid top (the same for the highest translucent voxel above it).
     * Any thread.
     */
    private int[] readMask(LodWorld w, long tile) throws IOException {
        int[] mask = new int[MASK_INTS];
        java.util.Arrays.fill(mask, GROUND, MASK_INTS, Integer.MIN_VALUE);
        int x = PreviewStore.tileX(tile);
        int z = PreviewStore.tileZ(tile);
        int size = LodSection.SIZE << TOP_LEVEL;
        int voxel = 1 << TOP_LEVEL;
        int air = w.air();
        MaterialClassifier classes = w.materials();
        // From the top down: the first fluid and the first ground seen in each column.
        for (int y = Math.floorDiv(maxY - 1, size); y >= Math.floorDiv(minY, size); y--) {
            long key = LodKey.pack(TOP_LEVEL, x, y, z);
            LodSection real = w.exists(key) ? w.read(key) : null;
            if (real == null) {
                continue;
            }
            for (int cz = 0; cz < LodSection.SIZE; cz++) {
                for (int cx = 0; cx < LodSection.SIZE; cx++) {
                    int c = cz * PreviewTile.CHUNKS + cx;
                    if (mask[GROUND + c] != Integer.MIN_VALUE) {
                        continue;
                    }
                    for (int cy = LodSection.SIZE - 1; cy >= 0; cy--) {
                        int material = real.material(cx, cy, cz);
                        if (material == air) {
                            continue;
                        }
                        mask[cz] |= 1 << cx;
                        int top = (y * LodSection.SIZE + cy) * voxel + real.fill().get(cx, cy, cz) + 1;
                        if (classes.classOf(material) == MaterialClassifier.TRANSLUCENT) {
                            if (mask[FLUID + c] == Integer.MIN_VALUE) {
                                mask[FLUID + c] = top;
                            }
                        } else {
                            mask[GROUND + c] = top;
                            break;
                        }
                    }
                }
            }
        }
        // Fluid without ground under it in the store: its bottom stands for the ground.
        for (int c = 0; c < PreviewTile.CHUNKS * PreviewTile.CHUNKS; c++) {
            if (mask[GROUND + c] == Integer.MIN_VALUE && mask[FLUID + c] != Integer.MIN_VALUE) {
                mask[GROUND + c] = minY;
            }
            if (mask[FLUID + c] == Integer.MIN_VALUE) {
                mask[FLUID + c] = mask[GROUND + c];
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
                w.air(), minY, maskGround(w), maskFluid(w), layerAxis, layerIndex, w.materials());
    }

    /** The material ids of real columns, whose quads {@code LodRenderer} drops. */
    static int maskGround(LodWorld w) {
        return w.blockStates().id(PreviewVoxels.MASK_GROUND_KEY);
    }

    static int maskFluid(LodWorld w) {
        return w.blockStates().id(PreviewVoxels.MASK_FLUID_KEY);
    }

    private PreviewVoxels.Mask maskOf(LodWorld w) {
        return new PreviewVoxels.Mask() {
            @Override
            public boolean real(int chunkX, int chunkZ) {
                int[] mask = maskAt(chunkX, chunkZ);
                return mask != null
                        && (mask[Math.floorMod(chunkZ, PreviewTile.CHUNKS)] >>> Math.floorMod(chunkX, PreviewTile.CHUNKS) & 1) != 0;
            }

            @Override
            public int ground(int chunkX, int chunkZ) {
                int[] mask = maskAt(chunkX, chunkZ);
                return mask == null ? Integer.MIN_VALUE : mask[GROUND + chunk(chunkX, chunkZ)];
            }

            @Override
            public int fluidTop(int chunkX, int chunkZ) {
                int[] mask = maskAt(chunkX, chunkZ);
                return mask == null ? Integer.MIN_VALUE : mask[FLUID + chunk(chunkX, chunkZ)];
            }

            private int[] maskAt(int chunkX, int chunkZ) {
                long tile = PreviewStore.key(Math.floorDiv(chunkX, PreviewTile.CHUNKS),
                        Math.floorDiv(chunkZ, PreviewTile.CHUNKS));
                try {
                    return mask(w, tile);
                } catch (IOException e) {
                    return null;
                }
            }
        };
    }

    /** A chunk's index in its tile's mask, {@code z * 32 + x}. */
    private static int chunk(int chunkX, int chunkZ) {
        return Math.floorMod(chunkZ, PreviewTile.CHUNKS) * PreviewTile.CHUNKS + Math.floorMod(chunkX, PreviewTile.CHUNKS);
    }

    /**
     * The solid border layers of a preview section's six neighbors, built
     * one layer each, for translucent faces (water spanning sections shows
     * no inner walls). Mesher threads.
     */
    int[][] solidNeighbors(LodWorld w, long key, MaterialClassifier classes, int[][] planes, byte[][] light)
            throws IOException {
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
                // The preview's water keeps its highest block's light, as the mips do (ADR-071).
                light[d] = BinaryGreedyMesher.borderLight(layer, d, null, classes, 1 << drawLevel(key));
            }
        }
        return solid;
    }
}
