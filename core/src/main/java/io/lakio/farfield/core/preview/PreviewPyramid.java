package io.lakio.farfield.core.preview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
    public static final int LEVELS = 16;
    /**
     * The first level whose tiles hold the ground only (E.3a of milestone 2):
     * heights, the surface rules' blocks, the sea and ice; no plants, no
     * canopy, no biome sample for the profiles. 32-block cells, drawn at
     * 32-block voxels and coarser.
     */
    public static final int GROUND_ONLY = 3;
    /**
     * The farthest drawn distance, in blocks (E.3d: past the world border,
     * 2^25): the coarsest level's tiles reach it; a player's own distance is
     * at most this.
     */
    public static final int MAX_RANGE = 1 << 25;
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

    /**
     * The far front's order (ADR-139): the coarsest level first, every one of
     * its quarters by distance, then the next finer level; only the levels
     * {@link #inFarFront} take part. Lower first.
     */
    public static double farOrder(int level, double distance) {
        return (double) (LEVELS - 1 - level) * 2 * MAX_RANGE + Math.min(distance, 2.0 * MAX_RANGE - 1);
    }

    /**
     * The finest level of the far front (ADR-139): the coarsest level alone
     * covers the whole distance, so it is the first image to the border; a
     * front down to level 3 held the near rings at half speed for a minute
     * (the detail within 1,024 blocks whole at 56 s instead of 31 s).
     */
    public static final int FAR_FRONT_FROM = LEVELS - 1;

    /** Whether quarters of {@code level} also go to the far front (ADR-139). */
    public static boolean inFarFront(int level) {
        return level >= FAR_FRONT_FROM;
    }

    /**
     * The two fronts merged (ADR-139): one from {@code rings} (by {@link
     * #order}), one from {@code far} (by {@link #farOrder}), taken in turn,
     * so the rings grow from the player while the coarsest levels reach the
     * border on the other half; an element in both lists is taken once, at
     * its first turn (by identity). Either list may run out first: the other
     * goes on alone.
     */
    public static <T> List<T> twoFronts(List<T> rings, List<T> far) {
        List<T> out = new ArrayList<>(rings.size());
        Set<T> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        int r = 0;
        int f = 0;
        boolean farTurn = false;
        while (r < rings.size() || f < far.size()) {
            T next = null;
            if (farTurn) {
                while (f < far.size() && next == null) {
                    T t = far.get(f++);
                    next = taken.add(t) ? t : null;
                }
            } else {
                while (r < rings.size() && next == null) {
                    T t = rings.get(r++);
                    next = taken.add(t) ? t : null;
                }
            }
            if (next != null) {
                out.add(next);
            }
            farTurn = !farTurn;
        }
        return out;
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
