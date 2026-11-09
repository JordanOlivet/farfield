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
 * too (full data; off by default, ADR-013), whether a dedicated server
 * builds the noise preview of terrain not generated yet for its players,
 * whether that preview runs the world's own features (trees, plants,
 * rocks, ADR-079) and which of them it leaves out ({@link #excludesFeature}),
 * the radius around the world's spawn its preview is built in ahead of the
 * players (0: none, ADR-123), whether that pregeneration (and the import
 * at start) pauses while players are online, and whether the server
 * imports its region files into its LODs at start (ADR-124).
 * Defaults from Neo-Voxy, whose streaming worked at these values. Missing
 * or invalid keys take their default.
 */
public record ServerConfig(boolean streaming, int radiusBlocks, int playerKBps, int totalKBps, int threads,
                           boolean fullData, boolean preview, boolean previewFeatures, String previewFeatureExclude,
                           int previewSpawnRadius, boolean pregenPausesForPlayers, boolean importOnStart) {

    public static final int AUTO = 0;
    public static final int MAX_RADIUS = 30_000_000;

    /** Pregeneration radii, in blocks: up to four times the preview's drawn range (ADR-123). */
    public static final int MAX_PREGEN_RADIUS = 16_384;

    public static final ServerConfig DEFAULTS = new ServerConfig(true, 4096, 1024, 10240, AUTO, false, true, true, "",
            4096, true, true);

    static final String STREAMING = "streaming";
    static final String RADIUS = "streaming_radius";
    static final String PLAYER_KBPS = "player_kbps";
    static final String TOTAL_KBPS = "total_kbps";
    static final String THREADS = "streaming_threads";
    static final String FULL_DATA = "full_data";
    static final String PREVIEW = "preview";
    static final String PREVIEW_FEATURES = "preview_features";
    static final String PREVIEW_FEATURE_EXCLUDE = "preview_feature_exclude";
    static final String PREVIEW_SPAWN_RADIUS = "preview_spawn_radius";
    static final String PREGEN_WHEN_PLAYERS = "pregen_when_players";
    static final String IMPORT_ON_START = "import_on_start";

    public ServerConfig {
        radiusBlocks = Math.max(0, Math.min(MAX_RADIUS, radiusBlocks));
        playerKBps = Math.max(0, playerKBps);
        totalKBps = Math.max(0, totalKBps);
        threads = Math.max(AUTO, Math.min(16, threads));
        previewFeatureExclude = previewFeatureExclude == null ? "" : previewFeatureExclude.trim();
        previewSpawnRadius = Math.max(0, Math.min(MAX_PREGEN_RADIUS, previewSpawnRadius));
    }

    /** The settings before ADR-123: the spawn's preview built ahead in 4,096 blocks, paused for players. */
    public ServerConfig(boolean streaming, int radiusBlocks, int playerKBps, int totalKBps, int threads,
                        boolean fullData, boolean preview, boolean previewFeatures, String previewFeatureExclude) {
        this(streaming, radiusBlocks, playerKBps, totalKBps, threads, fullData, preview, previewFeatures,
                previewFeatureExclude, 4096, true);
    }

    /** The settings before ADR-124: the import at start on. */
    public ServerConfig(boolean streaming, int radiusBlocks, int playerKBps, int totalKBps, int threads,
                        boolean fullData, boolean preview, boolean previewFeatures, String previewFeatureExclude,
                        int previewSpawnRadius, boolean pregenPausesForPlayers) {
        this(streaming, radiusBlocks, playerKBps, totalKBps, threads, fullData, preview, previewFeatures,
                previewFeatureExclude, previewSpawnRadius, pregenPausesForPlayers, true);
    }

    /**
     * Whether the preview leaves out the placed feature {@code id}
     * ({@code namespace:path}): {@link #previewFeatureExclude} lists patterns
     * separated by commas or spaces, {@code *} matching any run of
     * characters ({@code biomesoplenty:*}, {@code *:patch_*}, {@code *rock*}).
     */
    public boolean excludesFeature(String id) {
        if (previewFeatureExclude.isEmpty()) {
            return false;
        }
        for (String pattern : previewFeatureExclude.split("[,\\s]+")) {
            if (!pattern.isEmpty() && matches(pattern, id)) {
                return true;
            }
        }
        return false;
    }

    /** A glob with {@code *} only, matched whole. */
    static boolean matches(String pattern, String text) {
        String[] parts = pattern.split("\\*", -1);
        int at = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                if (!text.startsWith(part)) {
                    return false;
                }
                at = part.length();
            } else if (i == parts.length - 1) {
                return text.length() - part.length() >= at && text.endsWith(part);
            } else {
                int found = text.indexOf(part, at);
                if (found < 0) {
                    return false;
                }
                at = found + part.length();
            }
        }
        return at == text.length();
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
                threads(p), bool(p, FULL_DATA, DEFAULTS.fullData), bool(p, PREVIEW, DEFAULTS.preview),
                bool(p, PREVIEW_FEATURES, DEFAULTS.previewFeatures),
                p.getProperty(PREVIEW_FEATURE_EXCLUDE, DEFAULTS.previewFeatureExclude),
                integer(p, PREVIEW_SPAWN_RADIUS, DEFAULTS.previewSpawnRadius),
                !"run".equalsIgnoreCase(p.getProperty(PREGEN_WHEN_PLAYERS, "pause").trim()),
                bool(p, IMPORT_ON_START, DEFAULTS.importOnStart));
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
            out.write("# Grow the world's own features in the preview: trees, plants, rocks of every mod,\n");
            out.write("# where the game will place them (more server CPU per tile). Default true.\n");
            out.write(PREVIEW_FEATURES + "=" + previewFeatures + "\n");
            out.write("# Placed features the preview leaves out, by id, separated by commas; * matches\n");
            out.write("# anything. Examples: biomesoplenty:*  *:patch_*  minecraft:trees_jungle\n");
            out.write(PREVIEW_FEATURE_EXCLUDE + "=" + previewFeatureExclude + "\n");
            out.write("# Build the preview this many blocks around the world's spawn ahead of the players,\n");
            out.write("# from the server's start (0 to " + MAX_PREGEN_RADIUS + "; 0: off). Players see it up to 4096\n");
            out.write("# blocks; farther tiles wait for them. /farfield pregen starts other areas. Default "
                    + DEFAULTS.previewSpawnRadius + ".\n");
            out.write(PREVIEW_SPAWN_RADIUS + "=" + previewSpawnRadius + "\n");
            out.write("# While players are online, preview pregeneration pauses (pause), or goes on at low\n");
            out.write("# priority (run). The preview around the players themselves is always built. Default pause.\n");
            out.write(PREGEN_WHEN_PLAYERS + "=" + (pregenPausesForPlayers ? "pause" : "run") + "\n");
            out.write("# At start, read into the LODs the region files they have never seen: a world that\n");
            out.write("# lived without Farfield, regions changed while it was away. Every dimension, nearest\n");
            out.write("# the spawn first; paused while players are online as above. Default true.\n");
            out.write(IMPORT_ON_START + "=" + importOnStart + "\n");
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
