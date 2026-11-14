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
     * Blocks past the game's own view distance the near ring reaches
     * (ADR-152): the LODs drawn in detail first around the player.
     */
    public static final int NEAR_RING_MARGIN = 512;
    /** How many tile levels coarser than wanted a first image may be: the selection's own (ADR-150). */
    public static final int ACCEPTABLE_LEVELS = io.lakio.farfield.core.select.LodSelector.MAX_FALLBACK_LEVELS;

    /**
     * The tile level whose samples match the voxels drawn {@code distance}
     * blocks away at a split {@code detail} (ADR-152): the selection draws
     * level {@code floor(log2(distance / (detail * 32)))} there, and a level-L
     * section reads tiles of level L - 2 (4-block samples under 1-block voxels).
     */
    public static int wantedTile(double distance, double detail) {
        double sections = distance / (Math.max(1e-3, detail) * io.lakio.farfield.core.lod.LodSection.SIZE);
        int level = sections < 2 ? 0 : 63 - Long.numberOfLeadingZeros((long) sections);
        return Math.max(0, Math.min(LEVELS - 1, level) - 2);
    }

    /**
     * The step of a quarter of {@code level} spanning {@code near} to {@code far}
     * blocks from a player (ADR-152), lower first; -1 when no view there needs
     * that level:
     * <ol start="0">
     * <li>the coarsest level, whatever its distance: the selection descends
     *     from it, nothing shows under a section of it before its tile;</li>
     * <li>within the near ring ({@code ring} blocks), the level a first image
     *     may take ({@link #ACCEPTABLE_LEVELS} coarser than wanted);</li>
     * <li>within the near ring, the wanted level;</li>
     * <li>everywhere else, the acceptable level;</li>
     * <li>everywhere else, the wanted level.</li>
     * </ol>
     * The levels in between are not built for players: each finer tile meshed
     * the same sections again, about three meshes for each section a new
     * world drew, and the middle distances waited behind the near ones.
     */
    public static int step(int level, double near, double far, double ring, double detail) {
        if (level == LEVELS - 1) {
            return 0;
        }
        int nearest = wantedTile(near, detail);
        int farthest = wantedTile(far, detail);
        boolean acceptable = level >= nearest + ACCEPTABLE_LEVELS && level <= farthest + ACCEPTABLE_LEVELS;
        boolean wanted = level >= nearest && level <= farthest;
        if (!acceptable && !wanted) {
            return -1;
        }
        boolean inRing = near <= ring;
        return (inRing ? 1 : 3) + (acceptable ? 0 : 1);
    }

    /** The order of a quarter in its step (ADR-152): the step first, then the distance. */
    public static double stepOrder(int step, double distance) {
        return step * 4.0 * MAX_RANGE + Math.min(distance, 4.0 * MAX_RANGE - 1);
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

    /**
     * The near disc built before the far front starts (ADR-145): the levels up
     * to this one within {@link #NEAR_FIRST_DISTANCE} blocks; at a new world
     * the far front halved the threads the near detail had (3 in the pack).
     */
    public static final int NEAR_FIRST_LEVEL = 6;
    public static final int NEAR_FIRST_DISTANCE = 8192;

    /** Whether a quarter belongs to the near disc built before the far front (ADR-145). */
    public static boolean nearFirst(int level, double distance) {
        return level <= NEAR_FIRST_LEVEL && distance <= NEAR_FIRST_DISTANCE;
    }

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
