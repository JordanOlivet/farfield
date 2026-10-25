package io.lakio.farfield.core.mesh;

/**
 * How the mesher treats a material: empty, an opaque cube, a translucent
 * cube (water, glass, ice), a partial block, a set of boxes or a plant. The Minecraft
 * side derives it from the block state; the engine only sees the class.
 */
@FunctionalInterface
public interface MaterialClassifier {

    /** No geometry; neighbors show their faces. */
    int AIR = 0;
    /** Hides the faces of every neighbor. */
    int OPAQUE = 1;
    /**
     * Drawn, but does not hide opaque neighbors. Faces between two
     * translucent voxels are culled when both are fluids or both are not
     * ({@link #isFluid}), as vanilla culls water against water and ice against
     * ice, but draws both faces between ice and water (ADR-055). Two different
     * non-fluid materials (ice against glass) still show no face: acceptable
     * for LODs.
     */
    int TRANSLUCENT = 2;
    /**
     * An opaque block lower than a full cube, standing on the bottom of its
     * voxel over the whole footprint (snow layers, bottom slabs, carpets):
     * it hides only the top face of the voxel below it, and only full opaque
     * voxels hide its faces. A renderer draws it at its own height.
     */
    int PARTIAL = 3;
    /**
     * A block drawn as the boxes of its shape ({@link #boxCount}, {@link #box})
     * on the finest level: stairs, top slabs, fences, walls, panes, doors. It
     * hides no neighbor face; full opaque neighbors hide the faces of its
     * boxes that reach their side. Renderers treat it as opaque on coarser levels.
     */
    int SHAPED = 4;
    /**
     * A plant without collision (grass, flowers, ferns, saplings, crops):
     * drawn on the finest level as two crossed quads with its texture, as
     * vanilla's cross model ({@link Quad#DIAGONAL_A}, {@link Quad#DIAGONAL_B}).
     * It hides no face and no voxel hides it. Renderers treat it as air on
     * coarser levels (ADR-059).
     */
    int PLANT = 5;

    int classOf(int material);

    /** Whether a {@link #TRANSLUCENT} material is a fluid (water, lava); see {@link #TRANSLUCENT}. */
    default boolean isFluid(int material) {
        return false;
    }

    /**
     * Whether a {@link #TRANSLUCENT} fluid holds a plant (kelp, seagrass):
     * meshed as the fluid {@link #fluidOf} names, plus, like a {@link #PLANT},
     * two crossed quads with its own material (ADR-065).
     */
    default boolean hasPlant(int material) {
        return false;
    }

    /** The material whose faces a {@link #TRANSLUCENT} voxel shows: its fluid for one that {@link #hasPlant}. */
    default int fluidOf(int material) {
        return material;
    }

    /** Number of boxes of a {@link #SHAPED} material (1..{@link ShapeBoxes#MAX}), 0 for the others. */
    default int boxCount(int material) {
        return 0;
    }

    /** Box {@code index} of a {@link #SHAPED} material, packed by {@link ShapeBoxes#pack}. */
    default int box(int material, int index) {
        return 0;
    }
}
