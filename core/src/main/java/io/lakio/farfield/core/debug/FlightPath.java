package io.lakio.farfield.core.debug;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A recorded flight (ADR-099): the camera position and rotation at every
 * client tick, so a test agent replays the user's exact flight before and
 * after a fix. Text, one tick per line:
 *
 * <pre>
 * # Farfield flight 1
 * dimension minecraft:overworld
 * 48.000 170.000 32.000 -90.00 25.00
 * ...
 * </pre>
 *
 * Yaw is kept as the game holds it, unwrapped, so a replay turns the same
 * way through 180 degrees.
 */
public final class FlightPath {

    public static final String HEADER = "# Farfield flight 1";
    public static final String SUFFIX = ".flight";
    /** Longest flight name: letters, digits, '.', '-' and '_'. */
    public static final int MAX_NAME_LENGTH = 64;

    public record Sample(double x, double y, double z, float yaw, float pitch) {
    }

    private final String dimension;
    private final List<Sample> samples;

    public FlightPath(String dimension, List<Sample> samples) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("a flight needs at least one tick");
        }
        this.dimension = dimension;
        this.samples = List.copyOf(samples);
    }

    /** Whether {@code name} is a valid flight file name, without the suffix. */
    public static boolean validName(String name) {
        return name.length() <= MAX_NAME_LENGTH && name.matches("[A-Za-z0-9._-]+") && !name.startsWith(".");
    }

    public String dimension() {
        return dimension;
    }

    public int ticks() {
        return samples.size();
    }

    public Sample sample(int tick) {
        return samples.get(tick);
    }

    /** The camera at a fractional tick, linear between the two recorded ticks, held at both ends. */
    public Sample at(double tick) {
        if (tick <= 0) {
            return samples.get(0);
        }
        int last = samples.size() - 1;
        if (tick >= last) {
            return samples.get(last);
        }
        int i = (int) Math.floor(tick);
        double f = tick - i;
        Sample a = samples.get(i);
        Sample b = samples.get(i + 1);
        return new Sample(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f,
                (float) (a.yaw + (b.yaw - a.yaw) * f), (float) (a.pitch + (b.pitch - a.pitch) * f));
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.newLine();
            out.write("dimension " + dimension);
            out.newLine();
            for (Sample s : samples) {
                out.write(String.format(Locale.ROOT, "%.3f %.3f %.3f %.2f %.2f", s.x, s.y, s.z, s.yaw, s.pitch));
                out.newLine();
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** @throws IOException also for a file that is not a flight, with the line at fault */
    public static FlightPath read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).strip().equals(HEADER)) {
            throw new IOException(file + " is not a Farfield flight (first line: " + HEADER + ")");
        }
        String dimension = null;
        List<Sample> samples = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("dimension ")) {
                dimension = line.substring("dimension ".length()).strip();
                continue;
            }
            String[] w = line.split("\\s+");
            try {
                if (w.length != 5) {
                    throw new NumberFormatException();
                }
                samples.add(new Sample(Double.parseDouble(w[0]), Double.parseDouble(w[1]), Double.parseDouble(w[2]),
                        Float.parseFloat(w[3]), Float.parseFloat(w[4])));
            } catch (NumberFormatException e) {
                throw new IOException(file + " line " + (i + 1) + ": expected x y z yaw pitch, got '" + line + "'");
            }
        }
        if (dimension == null || samples.isEmpty()) {
            throw new IOException(file + " has no dimension line or no tick");
        }
        return new FlightPath(dimension, samples);
    }
}
