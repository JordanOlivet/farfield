package io.lakio.farfield.core.preview;

/**
 * The levels of preview tiles a server builds (ADR-126, step 3 of
 * {@code docs/analysis/preview-coarse-to-fine.md}): level 0 with the world's
 * own features within the features' distance (a server setting), level L
 * within {@code 1024 << L} blocks, all within a player's drawn distance
 * (E.3b), the coarsest over all of it. A
 * level-L tile's samples are {@code 4 << L} blocks apart. The coarsest level
 * is built first over the whole range, then each finer one; from {@link
 * #GROUND_ONLY} up a tile holds the ground only.
 */
public final class PreviewPyramid {

    /** Levels built: 0 to {@code LEVELS - 1}. */
    public static final int LEVELS = 6;
    /**
     * The first level whose tiles hold the ground only (E.3a of milestone 2):
     * heights, the surface rules' blocks, the sea and ice; no plants, no
     * canopy, no biome sample for the profiles. 32-block cells, drawn at
     * 32-block voxels and coarser.
     */
    public static final int GROUND_ONLY = 3;
    /**
     * The farthest drawn distance, in blocks (E.3b): the coarsest level's
     * tiles reach it; a player's own distance is at most this.
     */
    public static final int MAX_RANGE = 32768;
    /**
     * Blocks a finer level waits behind the coarser one when quarters are
     * ordered by distance (ADR-127): a coarse disc grows from the player, the
     * detail follows it.
     */
    public static final int LEVEL_LAG = 1024;

    /** The order of a tile or quarter of {@code level} at {@code distance} blocks: lower first. */
    public static double order(int level, double distance) {
        return distance + (double) LEVEL_LAG * (LEVELS - 1 - level);
    }

    private PreviewPyramid() {
    }

    /**
     * Blocks from a player drawing to {@code distance} within which tiles of
     * {@code level} are wanted: the features' distance for level 0, {@code
     * 1024 << level} for the others, the whole distance for the coarsest;
     * never past the distance.
     */
    public static int range(int level, int featuresDistance, int distance) {
        int d = Math.max(0, Math.min(MAX_RANGE, distance));
        if (level == 0) {
            return Math.min(d, featuresDistance);
        }
        return level == LEVELS - 1 ? d : Math.min(d, 1024 << level);
    }
}
