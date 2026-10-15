package io.lakio.farfield.core.select;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;

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
 * and asks for the missing children. Sections absent from the store are air
 * and skipped.
 */
public final class LodSelector {

    private final int levels;
    private double detail;

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
        if (level > 0 && distance < detail * size) {
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
            if (childrenReady) {
                for (int i = 0; i < 8; i++) {
                    visit(LodKey.pack(level - 1, x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1)),
                            camX, camY, camZ, maxDistance, sections, draw, want);
                }
                return;
            }
        }
        if (sections.ready(key)) {
            draw.accept(key);
        } else {
            want.accept(key);
        }
    }
}
