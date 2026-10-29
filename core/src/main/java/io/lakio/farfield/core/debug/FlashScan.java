package io.lakio.farfield.core.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Runs {@link FlashDetector} over a folder of frames (a recording cut into
 * images, ADR-099), to tune it on frames a person annotated before trusting
 * it in game:
 *
 * <pre>
 * gradlew :core:flashScan --args="&lt;frames dir&gt; [--width 512] [--change 14] [--settle 6]
 *     [--min 60] [--from 1] [--to 350] [--expect 286,294,336-338] [--ignore-color 0070D0:90]
 *     [--out &lt;dir&gt;]"
 * </pre>
 *
 * Frames are read in file name order and numbered from 1. {@code --expect}
 * lists the frames a person saw a glitch on: the scan reports which it
 * found, missed and found besides. {@code --out} writes each flagged frame
 * with its box, beside the frames before and after it. {@code --ignore-color}
 * takes out marks drawn on the frames (a person's annotation strokes): pixels
 * within the distance of that colour, and their neighbours, take the pixel
 * of the frame before.
 */
public final class FlashScan {

    private FlashScan() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: <frames dir> [--width N] [--change N] [--settle N] [--min N]"
                    + " [--from N] [--to N] [--expect 1,5-7] [--ignore-color RRGGBB:distance] [--out dir]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        int width = 512; // the most: frames are halved until they fit, as in game
        int change = 14;
        int settle = 6;
        int min = 60; // the frame probe's defaults (ADR-099)
        int from = 1;
        int to = Integer.MAX_VALUE;
        TreeSet<Long> expected = new TreeSet<>();
        Path out = null;
        int ignoreColor = -1;
        int ignoreDistance = 0;
        for (int i = 1; i + 1 < args.length; i += 2) {
            String v = args[i + 1];
            switch (args[i]) {
                case "--width" -> width = Integer.parseInt(v);
                case "--change" -> change = Integer.parseInt(v);
                case "--settle" -> settle = Integer.parseInt(v);
                case "--min" -> min = Integer.parseInt(v);
                case "--from" -> from = Integer.parseInt(v);
                case "--to" -> to = Integer.parseInt(v);
                case "--expect" -> expected.addAll(frames(v));
                case "--out" -> out = Path.of(v);
                case "--ignore-color" -> {
                    String[] c = v.split(":");
                    ignoreColor = Integer.parseInt(c[0], 16);
                    ignoreDistance = c.length > 1 ? Integer.parseInt(c[1]) : 90;
                }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().matches("(?i).*\\.(png|jpe?g)")).sorted().toList();
        }
        FlashDetector detector = null;
        FrameRing ring = new FrameRing(8, 1);
        TreeSet<Long> found = new TreeSet<>();
        List<FlashDetector.Flash> flashes = new ArrayList<>();
        int h = 0;
        int w = 0;
        int[] previous = null;
        for (int n = from; n <= Math.min(to, files.size()); n++) {
            FrameImages.Frame frame = FrameImages.read(files.get(n - 1), width);
            if (detector == null) {
                w = frame.width();
                h = frame.height();
                detector = new FlashDetector(w, h, change, settle, min);
            }
            if (ignoreColor >= 0 && previous != null) {
                erase(frame.rgb(), previous, w, h, ignoreColor, ignoreDistance);
            }
            previous = frame.rgb();
            ring.put(n, frame.rgb());
            for (FlashDetector.Flash f : detector.push(n, frame.rgb())) {
                flashes.add(f);
                for (int k = 0; k < f.frames(); k++) {
                    found.add(f.frame() + k);
                }
                System.out.printf(Locale.ROOT, "frame %d: %d-frame flash, area of %d pixels (%d flagged), box %d,%d to %d,%d%n",
                        f.frame(), f.frames(), f.pixels(), f.flagged(), f.minX(), f.minY(), f.maxX(), f.maxY());
                if (out != null) {
                    for (long k = f.frame() - 1; k <= f.frame() + f.frames(); k++) {
                        int[] rgb = ring.get(k, 0);
                        if (rgb != null) {
                            boolean flagged = k >= f.frame() && k < f.frame() + f.frames();
                            FrameImages.writePng(out.resolve(String.format(Locale.ROOT, "%04d-%dframe-%04d.png",
                                            f.frame(), f.frames(), k)), w, h, rgb,
                                    flagged ? f.minX() : 0, flagged ? f.minY() : 0,
                                    flagged ? f.maxX() : -1, flagged ? f.maxY() : -1);
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "%d flashes over frames %d to %d (%dx%d, change %d, settle %d, min %d)%n",
                flashes.size(), from, Math.min(to, files.size()), w, h, change, settle, min);
        if (!expected.isEmpty()) {
            TreeSet<Long> hit = new TreeSet<>(expected);
            hit.retainAll(found);
            TreeSet<Long> missed = new TreeSet<>(expected);
            missed.removeAll(found);
            TreeSet<Long> extra = new TreeSet<>(found);
            extra.removeAll(expected);
            System.out.println("expected and found: " + hit);
            System.out.println("expected, missed: " + missed);
            System.out.println("found, not expected: " + extra);
        }
    }

    /** Pixels near {@code color}, and their 8 neighbours, take the pixel of {@code previous}. */
    static void erase(int[] rgb, int[] previous, int width, int height, int color, int distance) {
        boolean[] mark = new boolean[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            mark[i] = FlashDetector.distance(rgb[i], color) <= distance;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean near = false;
                for (int dy = -1; dy <= 1 && !near; dy++) {
                    for (int dx = -1; dx <= 1 && !near; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        near = nx >= 0 && ny >= 0 && nx < width && ny < height && mark[ny * width + nx];
                    }
                }
                if (near) {
                    rgb[y * width + x] = previous[y * width + x];
                }
            }
        }
    }

    /** "1,5-7" as 1, 5, 6, 7. */
    static List<Long> frames(String list) {
        List<Long> frames = new ArrayList<>();
        for (String part : list.split(",")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            int dash = p.indexOf('-');
            if (dash < 0) {
                frames.add(Long.parseLong(p));
            } else {
                for (long f = Long.parseLong(p.substring(0, dash)); f <= Long.parseLong(p.substring(dash + 1)); f++) {
                    frames.add(f);
                }
            }
        }
        return frames;
    }
}
