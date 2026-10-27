package io.lakio.farfield.core.select;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/**
 * Chooses which LOD sections to draw around a camera: an octree descent
 * from the coarsest level, splitting a section into its eight children when
 * the camera is closer than {@code detail} times its size. A renderer gets
 * a screen-space criterion from {@code detail = focal length in pixels /
 * (32 * voxel pixels)}: a section splits when its voxels would exceed that
 * many pixels at its nearest point.
 *
 * <p>Coverage while meshes stream in: a section descends only when all its
 * existing children are ready to draw; otherwise it draws itself (if ready)
 * and asks for the missing children. A section that already descended in the
 * previous selection keeps descending when a child is not ready: that child
 * is new (it did not exist, so nothing was drawn there) or being remeshed
 * (its old mesh stays ready), and falling back would flash the whole area to
 * the coarse level, up to the top when the parents are new too (terrain
 * explored for the first time). Sections absent from the store are air and
 * skipped.
 */
public final class LodSelector {

    private final int levels;
    private double detail;
    /** Sections outside the view split at {@link #outsideDetail} at most; null: every section at {@link #detail}. */
    private LongPredicate inView;
    private double outsideDetail = Double.MAX_VALUE;
    private int fallbacks;
    /** A diagnostic lock: every section drawn at this level, or -1 for the usual split. */
    private int forcedLevel = -1;
    /** Sections of the locked level drawn around the camera, in its section sizes. */
    public static final int FORCED_RADIUS_SECTIONS = 16;
    /** Sections that descended in the previous selection, and in the current one. */
    private Set<Long> descended = new HashSet<>();
    private Set<Long> descending = new HashSet<>();

    /**
     * @param levels number of levels (0 to {@code levels - 1})
     * @param detail split distance in section sizes: a level-L section
     *               ({@code 32 << L} blocks) splits when closer than
     *               {@code detail * (32 << L)} blocks
     */
    public LodSelector(int levels, double detail) {
        this.levels = levels;
        this.detail = detail;
    }

    public double detail() {
        return detail;
    }

    /** Sets the split distance in section sizes (see the constructor). */
    public void setDetail(double detail) {
        this.detail = detail;
    }

    /**
     * Sections {@code inView} rejects split at {@code detail} at most: a
     * zoomed view (a spyglass) raises the detail of what it shows, not of
     * everything around (ADR-063). Null for every section at the same detail.
     */
    public void setOutsideView(LongPredicate inView, double detail) {
        this.inView = inView;
        this.outsideDetail = detail;
    }

    /**
     * Locks the selection to one level (a diagnostic, {@code /farfield debug
     * level}): every section descends to it whatever the distance, none
     * goes finer, and only {@link #FORCED_RADIUS_SECTIONS} sections of that
     * level around the camera are drawn, so a lock on the finest level does
     * not ask for the whole radius. -1 unlocks.
     */
    public void setForcedLevel(int level) {
        forcedLevel = level < 0 ? -1 : Math.min(level, levels - 1);
    }

    public int forcedLevel() {
        return forcedLevel;
    }

    /** Sections the last selection drew coarser than wanted, waiting for a child mesh. */
    public int fallbacks() {
        return fallbacks;
    }

    /** What the store and the mesh cache know about sections. */
    public interface Sections {
        /** True if the store has the section (it holds non-air voxels). */
        boolean exists(long key);

        /** True if the section's mesh is on the GPU. */
        boolean ready(long key);
    }

    /**
     * @param minY      lowest block Y of the dimension
     * @param maxY      highest block Y (exclusive)
     * @param maxDistance horizontal draw distance in blocks
     * @param draw      receives the sections to draw
     * @param want      receives the sections whose mesh is needed
     */
    public void select(double camX, double camY, double camZ, int minY, int maxY, double maxDistance,
                       Sections sections, LongConsumer draw, LongConsumer want) {
        fallbacks = 0;
        descending.clear();
        if (forcedLevel >= 0) {
            maxDistance = Math.min(maxDistance, (double) (LodSection.SIZE << forcedLevel) * FORCED_RADIUS_SECTIONS);
        }
        int top = levels - 1;
        int size = LodSection.SIZE << top;
        int x0 = Math.floorDiv((int) Math.floor(camX - maxDistance), size);
        int x1 = Math.floorDiv((int) Math.floor(camX + maxDistance), size);
        int z0 = Math.floorDiv((int) Math.floor(camZ - maxDistance), size);
        int z1 = Math.floorDiv((int) Math.floor(camZ + maxDistance), size);
        int y0 = Math.floorDiv(minY, size);
        int y1 = Math.floorDiv(maxY - 1, size);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    visit(LodKey.pack(top, x, y, z), camX, camY, camZ, maxDistance, sections, draw, want);
                }
            }
        }
        Set<Long> previous = descended;
        descended = descending;
        descending = previous;
    }

    private void visit(long key, double camX, double camY, double camZ, double maxDistance, Sections sections,
                       LongConsumer draw, LongConsumer want) {
        if (!sections.exists(key)) {
            return;
        }
        int level = LodKey.level(key);
        int size = LodSection.SIZE << level;
        double minX = (double) LodKey.x(key) * size;
        double minY = (double) LodKey.y(key) * size;
        double minZ = (double) LodKey.z(key) * size;
        double dx = Math.max(0, Math.max(minX - camX, camX - (minX + size)));
        double dz = Math.max(0, Math.max(minZ - camZ, camZ - (minZ + size)));
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > maxDistance) {
            return;
        }
        double dy = Math.max(0, Math.max(minY - camY, camY - (minY + size)));
        double distance = Math.sqrt(horizontal * horizontal + dy * dy);
        double split = inView != null && outsideDetail < detail && !inView.test(key) ? outsideDetail : detail;
        if (forcedLevel >= 0 && level > forcedLevel) {
            // Locked: down to that level, missing children asked for, nothing coarser drawn.
            int x = LodKey.x(key) * 2;
            int y = LodKey.y(key) * 2;
            int z = LodKey.z(key) * 2;
            for (int i = 0; i < 8; i++) {
                visit(LodKey.pack(level - 1, x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1)),
                        camX, camY, camZ, maxDistance, sections, draw, want);
            }
            return;
        }
        if (forcedLevel < 0 && level > 0 && distance < split * size) {
            boolean childrenReady = true;
            int x = LodKey.x(key) * 2;
            int y = LodKey.y(key) * 2;
            int z = LodKey.z(key) * 2;
            for (int i = 0; i < 8; i++) {
                long child = LodKey.pack(level - 1, x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1));
                if (sections.exists(child) && !sections.ready(child)) {
                    childrenReady = false;
                    want.accept(child);
                }
            }
            if (childrenReady || descended.contains(key)) {
                descending.add(key);
                for (int i = 0; i < 8; i++) {
                    visit(LodKey.pack(level - 1, x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1)),
                            camX, camY, camZ, maxDistance, sections, draw, want);
                }
                return;
            }
        }
        if (sections.ready(key)) {
            if (level > 0 && distance < split * size) {
                fallbacks++;
            }
            draw.accept(key);
        } else {
            want.accept(key);
        }
    }
}
