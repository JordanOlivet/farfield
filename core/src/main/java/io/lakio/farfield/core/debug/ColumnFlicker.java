package io.lakio.farfield.core.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The renderer's state per chunk column, frame by frame (ADR-099): what
 * draws each column (a real level, a preview level, or nothing). A state
 * that lasts one or two frames is a glitch the user sees as a flash when it
 * goes back to the state before it, or when it is worse than the states on
 * both sides (nothing drawn, or a coarser level), as a hole between the
 * preview and the real terrain is. Lasting changes, such as the preview
 * giving way to the real terrain, are not reported.
 *
 * <p>States are ranks, lower is better: {@link #real}, {@link #preview},
 * {@link #NONE}. Per frame, {@link #begin}, then {@link #set} for every
 * column watched, then {@link #end}.
 */
public final class ColumnFlicker {

    public static final int NONE = 1000;

    /** A real level's rank: 0 (1 block) is the best. */
    public static int real(int level) {
        return level;
    }

    /** A preview level's rank: any preview is worse than any real level. */
    public static int preview(int level) {
        return 100 + level;
    }

    /** A brief state: column {@code chunkX, chunkZ} went {@code before} to {@code brief} for {@code frames} frames, then {@code after}. */
    public record Flicker(long frame, int frames, int chunkX, int chunkZ, int before, int brief, int after) {
    }

    /** A column's last states, newest last, with the frame each began. */
    private static final class History {
        final int[] states = new int[4];
        final long[] since = new long[4];
        int count;
        long seen;
    }

    private final Map<Long, History> columns = new HashMap<>();
    private long frame = -1;
    private final List<Flicker> found = new ArrayList<>();

    public void begin(long frameNumber) {
        frame = frameNumber;
        found.clear();
    }

    public void set(int chunkX, int chunkZ, int state) {
        long key = (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
        History h = columns.computeIfAbsent(key, k -> new History());
        if (h.count > 0 && h.seen != frame - 1) {
            h.count = 0; // not watched last frame: no verdict across the gap
        }
        h.seen = frame;
        if (h.count > 0 && h.states[h.count - 1] == state) {
            return;
        }
        if (h.count == 4) {
            System.arraycopy(h.states, 1, h.states, 0, 3);
            System.arraycopy(h.since, 1, h.since, 0, 3);
            h.count = 3;
        }
        h.states[h.count] = state;
        h.since[h.count] = frame;
        h.count++;
        if (h.count >= 3) {
            int before = h.states[h.count - 3];
            int brief = h.states[h.count - 2];
            int after = h.states[h.count - 1];
            long frames = frame - h.since[h.count - 2];
            if (frames <= 2 && (before == after || brief > Math.max(before, after))) {
                found.add(new Flicker(h.since[h.count - 2], (int) frames, chunkX, chunkZ, before, brief, after));
            }
        }
    }

    /** The brief states this frame ended; columns not watched for a few frames are forgotten. */
    public List<Flicker> end() {
        Iterator<History> it = columns.values().iterator();
        while (it.hasNext()) {
            if (frame - it.next().seen > 4) {
                it.remove();
            }
        }
        return List.copyOf(found);
    }

    /** A rank for a person: "level 2", "preview 3", "nothing". */
    public static String describe(int state) {
        return state == NONE ? "nothing" : state >= 100 ? "preview " + (state - 100) : "level " + state;
    }
}
