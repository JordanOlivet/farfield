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
 * previous selection keeps descending when a child is not ready (a child
 * being remeshed keeps its old mesh, so it is ready), unless its own mesh
 * draws something in that child's octant: then the child is new terrain the
 * parent already shows (a server's mirror fetches the coarse levels first,
 * ADR-074), and descending would leave a hole until the child is fetched and
 * meshed. When the parent shows nothing there, descending keeps the area fine
 * instead of flashing it to the coarse level. The other way, a section far
 * enough to draw itself but not meshed yet draws its children meanwhile when
 * they all are (ADR-076). Sections absent from the store are air and
 * skipped.
 */
public final class LodSelector {

    private final int levels;
    private double detail;
    /** Sections outside the view split at {@link #outsideDetail} at most; null: every section at {@link #detail}. */
    private LongPredicate inView;
    private double outsideDetail = Double.MAX_VALUE;
    private int fallbacks;
    private int finerFallbacks;
    /** A diagnostic lock: no section drawn finer than this level, or -1 for the usual split. */
    private int forcedLevel = -1;
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
     * Locks the selection at one level (a diagnostic, {@code /farfield debug
     * level}): no section splits finer than it, so what the usual split draws
     * at that level far away stays at it up close; farther, coarser levels
     * as usual, over the whole distance. -1 unlocks.
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

    /** Sections the last selection drew as their children, waiting for their own mesh. */
    public int finerFallbacks() {
        return finerFallbacks;
    }

    /** What the store and the mesh cache know about sections. */
    public interface Sections {
        /** True if the store has the section (it holds non-air voxels). */
        boolean exists(long key);

        /** True if the section's mesh is on the GPU. */
        boolean ready(long key);

        /**
         * The octants of a ready section its mesh draws something in, one bit
         * per child ({@code x | z << 1 | y << 2}); 0 when not known.
         */
        default int octants(long key) {
            return 0;
        }
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
        finerFallbacks = 0;
        descending.clear();
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
        if (level > Math.max(0, forcedLevel) && distance < split * size) {
            int notReady = 0;
            int x = LodKey.x(key) * 2;
            int y = LodKey.y(key) * 2;
            int z = LodKey.z(key) * 2;
            for (int i = 0; i < 8; i++) {
                long child = LodKey.pack(level - 1, x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1));
                if (sections.exists(child) && !sections.ready(child)) {
                    notReady |= 1 << i;
                    want.accept(child);
                }
            }
            // A parent not ready draws nothing anywhere: its ready children are better than a hole.
            if (notReady == 0 || descended.contains(key)
                    && (!sections.ready(key) || (notReady & sections.octants(key)) == 0)) {
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
            return;
        }
        want.accept(key);
        // Not meshed yet, while its children are: they draw meanwhile, finer than wanted. On a
        // server's mirror the children arrive first (ADR-075), so moving away from terrain seen
        // up close found its parent never meshed, and the area vanished until it was.
        if (level > Math.max(0, forcedLevel)) {
            int x = LodKey.x(key) * 2;
            int y = LodKey.y(key) * 2;
            int z = LodKey.z(key) * 2;
            boolean any = false;
            for (int i = 0; i < 8; i++) {
                long child = LodKey.pack(level - 1, x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1));
                if (sections.exists(child)) {
                    if (!sections.ready(child)) {
                        return;
                    }
                    any = true;
                }
            }
            if (any) {
                finerFallbacks++;
                for (int i = 0; i < 8; i++) {
                    long child = LodKey.pack(level - 1, x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1));
                    if (sections.exists(child)) {
                        draw.accept(child);
                    }
                }
            }
        }
    }
}
