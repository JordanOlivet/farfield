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
 * The client's LOD drawing settings as saved in {@code config/farfield-client.properties}:
 * LOD drawing on or off, level of detail in pixels per voxel (ADR-027),
 * texture distance (ADR-026), level colors, the fog distances of normal
 * dimensions and of dimensions with thick fog such as the Nether (ADR-034),
 * the noise preview of terrain not generated yet (ADR-038), the
 * threads that mesh LOD sections and build the preview, or {@link #AUTO},
 * and the performance log lines (off by default: no log flood in play).
 * Missing or invalid keys take their default, out-of-range values are
 * clamped, so a hand-edited file never stops the game.
 */
public record RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                           int fogDistance, int thickFogDistance, boolean noisePreview, int meshThreads,
                           int previewThreads, boolean performanceLogs) {

    /** Default target size of an LOD voxel on screen, in pixels, where a level hands over to the next. */
    public static final float DEFAULT_VOXEL_PIXELS = 6f;
    public static final float MIN_VOXEL_PIXELS = 1f;
    public static final float MAX_VOXEL_PIXELS = 32f;
    /** Texture distance meaning every LOD shows its textures, whatever its distance. */
    public static final int ALL_DISTANCES = Integer.MAX_VALUE;
    /** Default: textures on every LOD (ADR-026); the GPU cost was within noise. */
    public static final int DEFAULT_TEXTURE_DISTANCE = ALL_DISTANCES;
    public static final int MAX_TEXTURE_DISTANCE = 4096;

    /** Fog distance meaning no fog at all, the edge of the LODs included. */
    public static final int FOG_OFF = Integer.MAX_VALUE;
    /** Thick fog distance meaning the game's own thick fog, LODs fading into it (ADR-033). */
    public static final int FOG_VANILLA = 0;
    /** Fog distances in blocks, where the fog becomes opaque. */
    public static final int MIN_FOG_DISTANCE = 256;
    public static final int MAX_FOG_DISTANCE = 4096;
    /** Default: the fog at the edge of the LODs in normal dimensions, the game's own in thick fog. */
    public static final int DEFAULT_FOG_DISTANCE = MAX_FOG_DISTANCE;
    public static final int DEFAULT_THICK_FOG_DISTANCE = FOG_VANILLA;

    /** Thread count meaning one per four logical processors ({@link #threads}). */
    public static final int AUTO = 0;
    public static final int MAX_THREADS = 8;

    public static final RenderConfig DEFAULTS = new RenderConfig(true, DEFAULT_VOXEL_PIXELS, DEFAULT_TEXTURE_DISTANCE,
            false, DEFAULT_FOG_DISTANCE, DEFAULT_THICK_FOG_DISTANCE, true, AUTO, AUTO, false);

    static final String ENABLED = "lod_rendering";
    static final String VOXEL_PIXELS = "detail_pixels_per_voxel";
    static final String TEXTURE_DISTANCE = "texture_distance";
    static final String LEVEL_COLORS = "level_colors";
    static final String FOG_DISTANCE = "fog_distance";
    static final String THICK_FOG_DISTANCE = "thick_fog_distance";
    static final String NOISE_PREVIEW = "noise_preview";
    static final String MESH_THREADS = "mesh_threads";
    static final String PREVIEW_THREADS = "preview_threads";
    static final String PERFORMANCE_LOGS = "performance_logs";
    /** Before ADR-034: true meant thick fog pushed back to the LOD horizon. Read only. */
    static final String EXTEND_DIMENSION_FOG = "extend_dimension_fog";
    private static final String ALL = "all";
    private static final String OFF = "off";
    private static final String VANILLA = "vanilla";
    private static final String AUTO_TEXT = "auto";

    public RenderConfig {
        voxelPixels = clampVoxelPixels(voxelPixels);
        textureDistance = clampTextureDistance(textureDistance);
        fogDistance = clampFogDistance(fogDistance);
        thickFogDistance = clampThickFogDistance(thickFogDistance);
        meshThreads = clampThreads(meshThreads);
        previewThreads = clampThreads(previewThreads);
    }

    /** The settings with the performance logs off. */
    public RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                        int fogDistance, int thickFogDistance, boolean noisePreview, int meshThreads,
                        int previewThreads) {
        this(enabled, voxelPixels, textureDistance, levelColors, fogDistance, thickFogDistance, noisePreview,
                meshThreads, previewThreads, false);
    }

    /** The settings with automatic thread counts. */
    public RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                        int fogDistance, int thickFogDistance, boolean noisePreview) {
        this(enabled, voxelPixels, textureDistance, levelColors, fogDistance, thickFogDistance, noisePreview, AUTO,
                AUTO);
    }

    /** The settings with the noise preview on. */
    public RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                        int fogDistance, int thickFogDistance) {
        this(enabled, voxelPixels, textureDistance, levelColors, fogDistance, thickFogDistance, true);
    }

    /** {@link #AUTO} (0 and below), or 1 to {@link #MAX_THREADS}. */
    public static int clampThreads(int threads) {
        return threads <= AUTO ? AUTO : Math.min(MAX_THREADS, threads);
    }

    /**
     * The threads a setting stands for: itself, or for {@link #AUTO} one per
     * four logical processors, 1 to 4 (8 logical processors: 2, the count
     * before this setting; 16: 4). The game's own threads keep the rest.
     */
    public static int threads(int setting, int processors) {
        return setting == AUTO ? Math.max(1, Math.min(4, processors / 4)) : setting;
    }

    public static float clampVoxelPixels(float pixels) {
        if (Float.isNaN(pixels)) {
            return DEFAULT_VOXEL_PIXELS;
        }
        return Math.max(MIN_VOXEL_PIXELS, Math.min(MAX_VOXEL_PIXELS, pixels));
    }

    public static int clampTextureDistance(int blocks) {
        return blocks == ALL_DISTANCES ? ALL_DISTANCES : Math.max(0, Math.min(MAX_TEXTURE_DISTANCE, blocks));
    }

    /** {@link #FOG_OFF}, or {@link #MIN_FOG_DISTANCE} to {@link #MAX_FOG_DISTANCE} blocks. */
    public static int clampFogDistance(int blocks) {
        return blocks == FOG_OFF ? FOG_OFF : Math.max(MIN_FOG_DISTANCE, Math.min(MAX_FOG_DISTANCE, blocks));
    }

    /** As {@link #clampFogDistance}, plus {@link #FOG_VANILLA} (0 and below). */
    public static int clampThickFogDistance(int blocks) {
        return blocks <= FOG_VANILLA ? FOG_VANILLA : clampFogDistance(blocks);
    }

    /** Reads the file, or returns the defaults when it does not exist. */
    public static RenderConfig read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return DEFAULTS;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        int thickFallback = bool(p, EXTEND_DIMENSION_FOG, false) ? MAX_FOG_DISTANCE : DEFAULT_THICK_FOG_DISTANCE;
        return new RenderConfig(
                bool(p, ENABLED, DEFAULTS.enabled),
                floatValue(p, VOXEL_PIXELS, DEFAULTS.voxelPixels),
                parseTextureDistance(p, DEFAULTS.textureDistance),
                bool(p, LEVEL_COLORS, DEFAULTS.levelColors),
                parseFogDistance(p, FOG_DISTANCE, DEFAULTS.fogDistance),
                parseFogDistance(p, THICK_FOG_DISTANCE, thickFallback),
                bool(p, NOISE_PREVIEW, DEFAULTS.noisePreview),
                parseThreads(p, MESH_THREADS),
                parseThreads(p, PREVIEW_THREADS),
                bool(p, PERFORMANCE_LOGS, DEFAULTS.performanceLogs));
    }

    /** Writes the file through a temporary file, so a crash mid-write keeps the previous one. */
    public void write(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            out.write("# Farfield client settings, also set in game: Mods > Farfield > Config.\n");
            out.write("# LOD drawing on or off.\n");
            out.write(ENABLED + "=" + enabled + "\n");
            out.write("# Largest size of an LOD voxel on screen before the finer level takes over,\n");
            out.write("# " + fmt(MIN_VOXEL_PIXELS) + " to " + fmt(MAX_VOXEL_PIXELS)
                    + ": lower is finer and costs more memory and GPU time. Default " + fmt(DEFAULT_VOXEL_PIXELS) + ".\n");
            out.write(VOXEL_PIXELS + "=" + fmt(voxelPixels) + "\n");
            out.write("# Distance in blocks up to which LOD faces show their textures, 0 to "
                    + MAX_TEXTURE_DISTANCE + " (0: off), or all. Default all.\n");
            out.write(TEXTURE_DISTANCE + "=" + (textureDistance == ALL_DISTANCES ? ALL : Integer.toString(textureDistance)) + "\n");
            out.write("# Tint LOD faces by level (red 1 block, orange 2, yellow 4, green 8, blue 16).\n");
            out.write(LEVEL_COLORS + "=" + levelColors + "\n");
            out.write("# Distance in blocks where the fog becomes opaque in normal dimensions, "
                    + MIN_FOG_DISTANCE + " to " + MAX_FOG_DISTANCE + ", or off. Default " + DEFAULT_FOG_DISTANCE + ".\n");
            out.write(FOG_DISTANCE + "=" + fogText(fogDistance) + "\n");
            out.write("# The same in dimensions with thick fog (the Nether), or vanilla: LODs fade into\n");
            out.write("# the game's own fog. Default vanilla.\n");
            out.write(THICK_FOG_DISTANCE + "=" + fogText(thickFogDistance) + "\n");
            out.write("# Show terrain not generated yet from the world's noise, coarsely (single player).\n");
            out.write(NOISE_PREVIEW + "=" + noisePreview + "\n");
            out.write("# Threads that build LOD meshes, 1 to " + MAX_THREADS + ", or auto (one per four logical\n");
            out.write("# processors, 1 to 4). Default auto.\n");
            out.write(MESH_THREADS + "=" + threadsText(meshThreads) + "\n");
            out.write("# Threads that build the noise preview, the same way. Default auto.\n");
            out.write(PREVIEW_THREADS + "=" + threadsText(previewThreads) + "\n");
            out.write("# Performance lines in the game log (draw and build times, fill times), for\n");
            out.write("# reports and tests. Default false.\n");
            out.write(PERFORMANCE_LOGS + "=" + performanceLogs + "\n");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String threadsText(int threads) {
        return threads == AUTO ? AUTO_TEXT : Integer.toString(threads);
    }

    /** A thread count, or auto (clamped by the constructor); auto when missing or invalid. */
    private static int parseThreads(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.trim().equalsIgnoreCase(AUTO_TEXT)) {
            return AUTO;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return AUTO;
        }
    }

    private static String fogText(int blocks) {
        return blocks == FOG_OFF ? OFF : blocks == FOG_VANILLA ? VANILLA : Integer.toString(blocks);
    }

    private static String fmt(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
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

    private static float floatValue(Properties p, String key, float fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseTextureDistance(Properties p, int fallback) {
        String v = p.getProperty(TEXTURE_DISTANCE);
        if (v == null) {
            return fallback;
        }
        v = v.trim();
        if (v.equalsIgnoreCase(ALL)) {
            return ALL_DISTANCES;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A fog distance: blocks, off or vanilla (clamped per key by the constructor). */
    private static int parseFogDistance(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        v = v.trim();
        if (v.equalsIgnoreCase(OFF)) {
            return FOG_OFF;
        }
        if (v.equalsIgnoreCase(VANILLA)) {
            return FOG_VANILLA;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
