package io.lakio.farfield.core.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the short glitches of a frame stream (ADR-099): an area that
 * changes for one or two frames and comes back, as the bands and level
 * flashes of 2026-10-28 did. A pixel is flagged when it differs from the
 * frames on both sides of the flash while those two agree, so steady
 * camera motion, where the frames on both sides differ too, flags little.
 * What is left of motion and compression noise is scattered along edges,
 * while a glitch is an area: a flash is judged by its largest connected
 * area of flagged pixels, not by their total.
 *
 * <p>Frames are packed {@code 0xRRGGBB} pixels of one size, pushed in
 * order with their frame numbers; a gap in the numbers (a frame not read
 * back) starts the history again. A flash is reported once the frame after
 * it is in, so one or two frames late.
 */
public final class FlashDetector {

    /**
     * One or two consecutive frames: {@code pixels} in their largest connected
     * flagged area, which the box bounds, of {@code flagged} in the frame.
     */
    public record Flash(long frame, int frames, int pixels, int flagged, int minX, int minY, int maxX, int maxY) {
    }

    private final int width;
    private final int height;
    private final int change;
    private final int settle;
    private final int minPixels;
    private final int[][] history = new int[4][];
    private final long[] numbers = new long[4];
    private int count;
    private final boolean[] mask;
    private final int[] queue;

    /**
     * @param change    smallest RGB distance (sum of the channel differences, 0 to 765) that counts as a change
     * @param settle    largest distance between the frames around a flash for them to count as the same view
     * @param minPixels size of the largest connected flagged area below which a frame is not reported
     */
    public FlashDetector(int width, int height, int change, int settle, int minPixels) {
        this.width = width;
        this.height = height;
        this.change = change;
        this.settle = settle;
        this.minPixels = minPixels;
        this.mask = new boolean[width * height];
        this.queue = new int[width * height];
    }

    /** Adds a frame; returns the flashes it completes (often none). */
    public List<Flash> push(long frame, int[] rgb) {
        if (rgb.length != width * height) {
            throw new IllegalArgumentException("frame of " + rgb.length + " pixels, expected " + width * height);
        }
        if (count > 0 && frame != numbers[3] + 1) {
            count = 0;
        }
        System.arraycopy(history, 1, history, 0, 3);
        System.arraycopy(numbers, 1, numbers, 0, 3);
        history[3] = rgb;
        numbers[3] = frame;
        count = Math.min(4, count + 1);
        List<Flash> flashes = new ArrayList<>(2);
        if (count >= 3) {
            Flash one = scan(history[1], history[2], history[2], history[3], numbers[2], 1);
            if (one != null) {
                flashes.add(one);
            }
        }
        if (count >= 4) {
            Flash two = scan(history[0], history[1], history[2], history[3], numbers[1], 2);
            if (two != null) {
                flashes.add(two);
            }
        }
        return flashes;
    }

    /** Pixels where {@code first..last} differ from {@code before} and {@code after}, which agree. */
    private Flash scan(int[] before, int[] first, int[] last, int[] after, long frame, int frames) {
        int flagged = 0;
        for (int i = 0; i < before.length; i++) {
            int b = before[i];
            int a = after[i];
            int f = first[i];
            int l = last[i];
            boolean flash = distance(b, a) <= settle && distance(f, b) > change && distance(l, a) > change
                    && (frames == 1 || distance(f, l) <= settle);
            mask[i] = flash;
            flagged += flash ? 1 : 0;
        }
        if (flagged < minPixels) {
            return null;
        }
        // The largest 8-connected area of flagged pixels.
        int best = 0;
        int bMinX = 0;
        int bMinY = 0;
        int bMaxX = -1;
        int bMaxY = -1;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start]) {
                continue;
            }
            mask[start] = false;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            int minX = width;
            int minY = height;
            int maxX = -1;
            int maxY = -1;
            while (head < tail) {
                int i = queue[head++];
                int x = i % width;
                int y = i / width;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int n = ny * width + nx;
                        if (nx >= 0 && nx < width && mask[n]) {
                            mask[n] = false;
                            queue[tail++] = n;
                        }
                    }
                }
            }
            if (tail > best) {
                best = tail;
                bMinX = minX;
                bMinY = minY;
                bMaxX = maxX;
                bMaxY = maxY;
            }
        }
        return best < minPixels ? null : new Flash(frame, frames, best, flagged, bMinX, bMinY, bMaxX, bMaxY);
    }

    /** Sum of the channel differences of two {@code 0xRRGGBB} pixels, 0 to 765. */
    public static int distance(int p, int q) {
        return Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF))
                + Math.abs((p & 0xFF) - (q & 0xFF));
    }
}
