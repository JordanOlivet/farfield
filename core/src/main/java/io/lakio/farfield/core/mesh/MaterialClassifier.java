package io.lakio.farfield.core.mesh;

/**
 * How the mesher treats a material: empty, an opaque cube, or a translucent
 * cube (water, glass, ice). The Minecraft side derives it from the block
 * state; the engine only sees the class.
 */
@FunctionalInterface
public interface MaterialClassifier {

    /** No geometry; neighbors show their faces. */
    int AIR = 0;
    /** Hides the faces of every neighbor. */
    int OPAQUE = 1;
    /**
     * Drawn, but does not hide opaque neighbors. Faces between two
     * translucent voxels are culled whatever their materials (water against
     * glass shows no face: acceptable for LODs).
     */
    int TRANSLUCENT = 2;
    /**
     * An opaque block lower than a full cube, standing on the bottom of its
     * voxel over the whole footprint (snow layers, bottom slabs, carpets):
     * it hides only the top face of the voxel below it, and only full opaque
     * voxels hide its faces. A renderer draws it at its own height.
     */
    int PARTIAL = 3;

    int classOf(int material);
}
