package io.lakio.farfield.core.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/**
 * The server's streaming settings (ADR-068), {@code config/farfield-server.properties}:
 * streaming on or off, the radius in blocks within which players may ask
 * for sections, the bandwidth per player and for all players in KB/s (0:
 * no limit), the threads that read sections for players ({@link #AUTO}:
 * min(4, processors / 2)), whether sections under the surface are sent
 * too (full data; off by default, ADR-013), and whether a dedicated server
 * builds the noise preview of terrain not generated yet for its players.
 * Defaults from Neo-Voxy, whose streaming worked at these values. Missing
 * or invalid keys take their default.
 */
public record ServerConfig(boolean streaming, int radiusBlocks, int playerKBps, int totalKBps, int threads,
                           boolean fullData, boolean preview) {

    public static final int AUTO = 0;
    public static final int MAX_RADIUS = 30_000_000;

    public static final ServerConfig DEFAULTS = new ServerConfig(true, 4096, 1024, 10240, AUTO, false, true);

    static final String STREAMING = "streaming";
    static final String RADIUS = "streaming_radius";
    static final String PLAYER_KBPS = "player_kbps";
    static final String TOTAL_KBPS = "total_kbps";
    static final String THREADS = "streaming_threads";
    static final String FULL_DATA = "full_data";
    static final String PREVIEW = "preview";

    public ServerConfig {
        radiusBlocks = Math.max(0, Math.min(MAX_RADIUS, radiusBlocks));
        playerKBps = Math.max(0, playerKBps);
        totalKBps = Math.max(0, totalKBps);
        threads = Math.max(AUTO, Math.min(16, threads));
    }

    /** The threads {@link #threads} stands for on a machine with {@code processors}. */
    public int effectiveThreads(int processors) {
        return threads == AUTO ? Math.max(1, Math.min(4, processors / 2)) : threads;
    }

    /** Reads the file, or returns the defaults when it does not exist. */
    public static ServerConfig read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return DEFAULTS;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new ServerConfig(bool(p, STREAMING, DEFAULTS.streaming), integer(p, RADIUS, DEFAULTS.radiusBlocks),
                integer(p, PLAYER_KBPS, DEFAULTS.playerKBps), integer(p, TOTAL_KBPS, DEFAULTS.totalKBps),
                threads(p), bool(p, FULL_DATA, DEFAULTS.fullData), bool(p, PREVIEW, DEFAULTS.preview));
    }

    /** Writes the file through a temporary file. */
    public void write(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            out.write("# Farfield server settings: streaming LODs to players who have Farfield.\n");
            out.write("# Send LODs to players' clients (true), or let each client build its own (false).\n");
            out.write(STREAMING + "=" + streaming + "\n");
            out.write("# Distance in blocks around a player within which it may ask for LODs. Default "
                    + DEFAULTS.radiusBlocks + ".\n");
            out.write(RADIUS + "=" + radiusBlocks + "\n");
            out.write("# Bandwidth per player and for all players together, in KB/s (0: no limit).\n");
            out.write("# Defaults " + DEFAULTS.playerKBps + " and " + DEFAULTS.totalKBps
                    + ": the game's own packets always pass first.\n");
            out.write(PLAYER_KBPS + "=" + playerKBps + "\n");
            out.write(TOTAL_KBPS + "=" + totalKBps + "\n");
            out.write("# Threads that read LODs for players, 1 to 16, or auto (half the processors, 1 to 4).\n");
            out.write(THREADS + "=" + (threads == AUTO ? "auto" : Integer.toString(threads)) + "\n");
            out.write("# Send the sections under the surface too (caves, ores): true lets players see\n");
            out.write("# them through the LODs. Default false: only what is seen from the sky down.\n");
            out.write(FULL_DATA + "=" + fullData + "\n");
            out.write("# Build the preview of terrain not generated yet around players and send it to\n");
            out.write("# them (costs server CPU on low-priority threads). Default true.\n");
            out.write(PREVIEW + "=" + preview + "\n");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> true;
            case "false", "off", "no" -> false;
            default -> fallback;
        };
    }

    private static int integer(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int threads(Properties p) {
        String v = p.getProperty(THREADS);
        if (v == null || v.trim().equalsIgnoreCase("auto")) {
            return AUTO;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return AUTO;
        }
    }
}
