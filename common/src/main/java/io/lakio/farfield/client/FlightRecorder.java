package io.lakio.farfield.client;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.debug.FlightPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the camera of a flight, one sample per client tick, to
 * {@code <game dir>/farfield/flights/<name>.flight} (ADR-099): the user
 * records the flight on which they saw a glitch, a test agent replays it
 * before and after a fix. {@code /farfieldclient record start|stop}, or the
 * agent inbox. A change of dimension or leaving the world ends the
 * recording and saves it. Client thread only.
 */
public final class FlightRecorder {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static String name;
    private static String dimension;
    private static List<FlightPath.Sample> samples;

    private FlightRecorder() {
    }

    public static Path folder(Minecraft mc) {
        return mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve("farfield").resolve("flights");
    }

    public static boolean recording() {
        return samples != null;
    }

    /**
     * @param flightName the file name without its suffix, or null for the date and time
     * @return a line for the player
     */
    public static String start(Minecraft mc, String flightName) {
        if (mc.player == null) {
            return "Open a world first.";
        }
        String n = flightName == null ? LocalDateTime.now().format(STAMP) : flightName;
        if (!FlightPath.validName(n)) {
            return "Use letters, digits, '.', '-' or '_' for the flight's name.";
        }
        if (recording()) {
            return "Already recording " + name + ": /farfieldclient record stop first.";
        }
        name = n;
        dimension = mc.player.level().dimension().location().toString();
        samples = new ArrayList<>();
        Farfield.LOG.info("Flight recording {} started in {}", name, dimension);
        return "Recording the flight " + name + "; /farfieldclient record stop saves it.";
    }

    /** @return a line for the player */
    public static String stop(Minecraft mc) {
        if (!recording()) {
            return "No flight is being recorded.";
        }
        List<FlightPath.Sample> recorded = samples;
        // A stop in the middle of a tick (an agent's command) comes before this tick's sample.
        LocalPlayer p = mc.player;
        if (p != null && p.level().dimension().location().toString().equals(dimension)) {
            FlightPath.Sample now = new FlightPath.Sample(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
            if (recorded.isEmpty() || !recorded.get(recorded.size() - 1).equals(now)) {
                recorded.add(now);
            }
        }
        String n = name;
        samples = null;
        name = null;
        if (recorded.isEmpty()) {
            return "The flight " + n + " has no tick: nothing saved.";
        }
        Path file = folder(mc).resolve(n + FlightPath.SUFFIX);
        try {
            new FlightPath(dimension, recorded).write(file);
        } catch (IOException e) {
            Farfield.LOG.error("Saving the flight {} failed", file, e);
            return "Could not save the flight: " + e.getMessage();
        }
        Farfield.LOG.info("Flight recording {} saved: {} ({} ticks, {} s)", n, file, recorded.size(),
                recorded.size() / 20);
        return "Flight " + n + " saved: " + recorded.size() + " ticks in " + file;
    }

    /** End of every client tick. */
    public static void tick(Minecraft mc) {
        if (!recording()) {
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || !p.level().dimension().location().toString().equals(dimension)) {
            stop(mc);
            return;
        }
        samples.add(new FlightPath.Sample(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
    }
}
