package io.lakio.farfield.core.preview;

/**
 * The levels of preview tiles a server builds (ADR-126, step 3 of
 * {@code docs/analysis/preview-coarse-to-fine.md}): level 0 with the world's
 * own features within the features' distance (a server setting), level 1
 * within 2,048 blocks, level 2 over the whole drawn range. A level-L tile's
 * samples are {@code 4 << L} blocks apart, the far grid of the tiles before
 * (4 blocks to 1,024, 8 to 2,048, 16 beyond). The coarsest level is built
 * first over the whole range, then each finer one.
 */
public final class PreviewPyramid {

    /** Levels built: 0 to {@code LEVELS - 1}. */
    public static final int LEVELS = 3;
    /** The drawn range, in blocks: the renderer draws nothing farther. */
    public static final int RANGE = 4096;

    private PreviewPyramid() {
    }

    /**
     * Blocks from a player within which tiles of {@code level} are wanted:
     * the features' distance for level 0, {@code 1024 << level} for the
     * others, the drawn range for the coarsest.
     */
    public static int range(int level, int featuresDistance) {
        if (level == 0) {
            return Math.min(RANGE, featuresDistance);
        }
        return level == LEVELS - 1 ? RANGE : Math.min(RANGE, 1024 << level);
    }
}
