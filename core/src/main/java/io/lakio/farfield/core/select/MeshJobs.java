package io.lakio.farfield.core.select;

import java.util.Arrays;

/**
 * Counts the mesh jobs a renderer asks for by what asked for each, for the
 * performance logs: at a join half of the jobs meshed a section again, and
 * one mesh in five was replaced before it was ever drawn
 * (docs/analysis/join-as-a-wave.md). One thread.
 */
public final class MeshJobs {

    /** The section had no mesh. */
    public static final int FIRST = 0;
    /** A real section the store wrote again. */
    public static final int REAL_WRITTEN = 1;
    /** A neighbor of a real section written for the first time. */
    public static final int WRITE_NEIGHBOR = 2;
    /** A neighbor whose view of a section changed with that section's new mesh. */
    public static final int BORDER_CHANGED = 3;
    /** A preview section whose tiles changed, meshed whole before: a refinement. */
    public static final int TILE_REFINED = 4;
    /** A preview section whose tiles changed, meshed with places missing before: coverage. */
    public static final int TILE_COVERAGE = 5;
    /** Written while its mesh was being built. */
    public static final int WRITTEN_WHILE_MESHING = 6;
    /** Its looks came after its mesh. */
    public static final int LOOKS = 7;
    /** The server holds a newer copy. */
    public static final int SERVER_NEWER = 8;
    /** Anything else (no room for its quads, a setting, a resource reload). */
    public static final int OTHER = 9;

    private static final String[] NAMES = {"first", "real written", "neighbor of a write", "neighbor's border changed",
        "preview refined", "preview coverage", "written while meshing", "looks", "server copy newer", "other"};
    /** Draw levels 0, 1 to 2, 3 to 5, 6 and up. */
    private static final int BUCKETS = 4;

    /** Per cause: real sections by level bucket, then preview sections by level bucket. */
    private final long[][] counts = new long[NAMES.length][2 * BUCKETS];
    private long replacedUndrawn;
    private long outdatedOnArrival;

    /** A mesh job asked for a section of draw level {@code level}. */
    public void asked(int cause, boolean preview, int level) {
        int bucket = level == 0 ? 0 : level <= 2 ? 1 : level <= 5 ? 2 : 3;
        counts[cause < 0 || cause >= NAMES.length ? OTHER : cause][(preview ? BUCKETS : 0) + bucket]++;
    }

    /** A mesh was replaced by a newer one without having been drawn once. */
    public void replacedUndrawn() {
        replacedUndrawn++;
    }

    /** A mesh arrived for a section that had changed again meanwhile. */
    public void outdatedOnArrival() {
        outdatedOnArrival++;
    }

    public long total() {
        long total = 0;
        for (long[] row : counts) {
            for (long n : row) {
                total += n;
            }
        }
        return total;
    }

    /** The jobs asked for one cause, real and preview. */
    public long count(int cause) {
        return Arrays.stream(counts[cause]).sum();
    }

    public void reset() {
        for (long[] row : counts) {
            Arrays.fill(row, 0);
        }
        replacedUndrawn = 0;
        outdatedOnArrival = 0;
    }

    /**
     * "N asked: first a (real r, preview p), ...; by level [0, 1-2, 3-5, 6+]
     * real [...], preview [...]; n replaced before being drawn, m outdated on
     * arrival".
     */
    public String text() {
        StringBuilder out = new StringBuilder();
        long[] real = new long[BUCKETS];
        long[] preview = new long[BUCKETS];
        out.append(total()).append(" asked");
        String separator = ": ";
        for (int cause = 0; cause < NAMES.length; cause++) {
            long r = 0;
            long p = 0;
            for (int b = 0; b < BUCKETS; b++) {
                r += counts[cause][b];
                p += counts[cause][BUCKETS + b];
                real[b] += counts[cause][b];
                preview[b] += counts[cause][BUCKETS + b];
            }
            if (r + p == 0) {
                continue;
            }
            out.append(separator).append(NAMES[cause]).append(' ').append(r + p);
            if (cause == FIRST) {
                out.append(" (real ").append(r).append(", preview ").append(p).append(')');
            }
            separator = ", ";
        }
        out.append("; by level [0, 1-2, 3-5, 6+] real ").append(Arrays.toString(real)).append(", preview ")
                .append(Arrays.toString(preview)).append("; ").append(replacedUndrawn)
                .append(" replaced before being drawn, ").append(outdatedOnArrival).append(" outdated on arrival");
        return out.toString();
    }
}
