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
 * the performance log lines (off by default: no log flood in play), the
 * light of the preview's light sources (ADR-117), and the kinds of features
 * the preview shows (plan item D.3, {@link #FEATURE_TREES} and the others,
 * a bit each).
 * Missing or invalid keys take their default, out-of-range values are
 * clamped, so a hand-edited file never stops the game.
 */
public record RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                           int fogDistance, int thickFogDistance, boolean noisePreview, int meshThreads,
                           int previewThreads, boolean performanceLogs, boolean previewLight,
                           int previewFeatures, boolean fog) {

    /** Bits of {@link #previewFeatures}: the preview's trees, plants, and its other features and villages. */
    public static final int FEATURE_TREES = 1;
    public static final int FEATURE_PLANTS = 2;
    public static final int FEATURE_OTHERS = 4;
    public static final int ALL_FEATURES = FEATURE_TREES | FEATURE_PLANTS | FEATURE_OTHERS;

    /** Default target size of an LOD voxel on screen, in pixels, where a level hands over to the next. */
    public static final float DEFAULT_VOXEL_PIXELS = 6f;
    public static final float MIN_VOXEL_PIXELS = 1f;
    public static final float MAX_VOXEL_PIXELS = 32f;
    /** Texture distance meaning every LOD shows its textures, whatever its distance. */
    public static final int ALL_DISTANCES = Integer.MAX_VALUE;
    /** Default: textures on every LOD (ADR-026); the GPU cost was within noise. */
    public static final int DEFAULT_TEXTURE_DISTANCE = ALL_DISTANCES;
    public static final int MAX_TEXTURE_DISTANCE = 4096;

    /**
     * Fog distance meaning no fog at all, the edge of the LODs included: in
     * dimensions with thick fog only since ADR-140; in normal dimensions it
     * is read as {@link #fog} off at {@link #DEFAULT_DRAW_DISTANCE}.
     */
    public static final int FOG_OFF = Integer.MAX_VALUE;
    /** Thick fog distance meaning the game's own thick fog, LODs fading into it (ADR-033). */
    public static final int FOG_VANILLA = 0;
    /**
     * Fog distances in blocks, where the fog becomes opaque; nothing is drawn
     * beyond, so the fog distance is the LODs' distance too (E.3b, E.3d: up
     * to 2^25 blocks, past the world border).
     */
    public static final int MIN_FOG_DISTANCE = 256;
    public static final int MAX_FOG_DISTANCE = 1 << 25;
    /** The LODs' distance without fog ({@link #FOG_OFF}), and the fog's default: the 4,096 blocks of before E.3b. */
    public static final int DEFAULT_DRAW_DISTANCE = 4096;
    /** Default: the fog at the edge of the LODs in normal dimensions, the game's own in thick fog. */
    public static final int DEFAULT_FOG_DISTANCE = DEFAULT_DRAW_DISTANCE;
    public static final int DEFAULT_THICK_FOG_DISTANCE = FOG_VANILLA;

    /** Thread count meaning one per four logical processors ({@link #threads}). */
    public static final int AUTO = 0;
    public static final int MAX_THREADS = 8;

    public static final RenderConfig DEFAULTS = new RenderConfig(true, DEFAULT_VOXEL_PIXELS, DEFAULT_TEXTURE_DISTANCE,
            false, DEFAULT_FOG_DISTANCE, DEFAULT_THICK_FOG_DISTANCE, true, AUTO, AUTO, false, true, ALL_FEATURES,
            true);

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
    static final String PREVIEW_LIGHT = "preview_light";
    static final String PREVIEW_TREES = "preview_trees";
    static final String PREVIEW_PLANTS = "preview_plants";
    static final String PREVIEW_OTHERS = "preview_others";
    static final String FOG = "fog";
    /** Before ADR-034: true meant thick fog pushed back to the LOD horizon. Read only. */
    static final String EXTEND_DIMENSION_FOG = "extend_dimension_fog";
    private static final String ALL = "all";
    private static final String OFF = "off";
    private static final String VANILLA = "vanilla";
    private static final String AUTO_TEXT = "auto";

    public RenderConfig {
        voxelPixels = clampVoxelPixels(voxelPixels);
        textureDistance = clampTextureDistance(textureDistance);
        if (fogDistance == FOG_OFF) {
            // Before ADR-140 "off" was the only way to hide the fog, and it held the LODs to 4,096 blocks.
            fogDistance = DEFAULT_DRAW_DISTANCE;
            fog = false;
        }
        fogDistance = clampFogDistance(fogDistance);
        thickFogDistance = clampThickFogDistance(thickFogDistance);
        meshThreads = clampThreads(meshThreads);
        previewThreads = clampThreads(previewThreads);
        previewFeatures &= ALL_FEATURES;
    }

    /** The settings with the fog on (or off when {@code fogDistance} is {@link #FOG_OFF}). */
    public RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                        int fogDistance, int thickFogDistance, boolean noisePreview, int meshThreads,
                        int previewThreads, boolean performanceLogs, boolean previewLight, int previewFeatures) {
        this(enabled, voxelPixels, textureDistance, levelColors, fogDistance, thickFogDistance, noisePreview,
                meshThreads, previewThreads, performanceLogs, previewLight, previewFeatures, true);
    }

    /** The settings with every kind of feature in the preview. */
    public RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                        int fogDistance, int thickFogDistance, boolean noisePreview, int meshThreads,
                        int previewThreads, boolean performanceLogs, boolean previewLight) {
        this(enabled, voxelPixels, textureDistance, levelColors, fogDistance, thickFogDistance, noisePreview,
                meshThreads, previewThreads, performanceLogs, previewLight, ALL_FEATURES);
    }

    /** Whether the preview shows a kind of feature ({@link #FEATURE_TREES} and the others). */
    public boolean previewShows(int feature) {
        return (previewFeatures & feature) != 0;
    }

    /** The settings with the preview's light on. */
    public RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                        int fogDistance, int thickFogDistance, boolean noisePreview, int meshThreads,
                        int previewThreads, boolean performanceLogs) {
        this(enabled, voxelPixels, textureDistance, levelColors, fogDistance, thickFogDistance, noisePreview,
                meshThreads, previewThreads, performanceLogs, true);
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
     * three logical processors, 1 to 6 (8 logical processors: 2; 16: 5). A
     * streamed client meshes every section the server pushes (ADR-077): 4 of
     * 16 ran at their limit at the generation front. The game's own threads
     * keep the rest.
     */
    public static int threads(int setting, int processors) {
        return setting == AUTO ? Math.max(1, Math.min(6, processors / 3)) : setting;
    }

    /**
     * The noise preview's threads while the server takes in the chunks a
     * player generates: half its usual count, rounded up (5 threads: 3; 1
     * or 2: 1), so the new chunks keep the processor first (ADR-084) on a
     * small machine without leaving a large one idle (ADR-103).
     */
    public static int yieldingThreads(int threads) {
        return Math.max(1, (threads + 1) / 2);
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

    /** How far LODs are drawn under a fog distance: the distance itself, {@link #DEFAULT_DRAW_DISTANCE} without fog. */
    public static int drawDistance(int fogDistance) {
        return fogDistance == FOG_OFF ? DEFAULT_DRAW_DISTANCE : fogDistance;
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
        int thickFallback = bool(p, EXTEND_DIMENSION_FOG, false) ? DEFAULT_DRAW_DISTANCE : DEFAULT_THICK_FOG_DISTANCE;
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
                bool(p, PERFORMANCE_LOGS, DEFAULTS.performanceLogs),
                bool(p, PREVIEW_LIGHT, DEFAULTS.previewLight),
                (bool(p, PREVIEW_TREES, true) ? FEATURE_TREES : 0)
                        | (bool(p, PREVIEW_PLANTS, true) ? FEATURE_PLANTS : 0)
                        | (bool(p, PREVIEW_OTHERS, true) ? FEATURE_OTHERS : 0),
                bool(p, FOG, DEFAULTS.fog));
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
            out.write("# Tint LOD faces by level (red 1 block, orange 2, yellow 4, green 8, blue 16,\n");
            out.write("# purple 32, pink 64, white 128).\n");
            out.write(LEVEL_COLORS + "=" + levelColors + "\n");
            out.write("# How far LODs are drawn in normal dimensions, in blocks, " + MIN_FOG_DISTANCE + " to "
                    + MAX_FOG_DISTANCE + "; with the fog on, it\n");
            out.write("# becomes opaque there. Default " + DEFAULT_FOG_DISTANCE + ".\n");
            out.write(FOG_DISTANCE + "=" + fogText(fogDistance) + "\n");
            out.write("# Fog at the edge of the LODs in normal dimensions, true or false; the distance\n");
            out.write("# stays. Default true.\n");
            out.write(FOG + "=" + fog + "\n");
            out.write("# The same in dimensions with thick fog (the Nether), or vanilla: LODs fade into\n");
            out.write("# the game's own fog. Default vanilla.\n");
            out.write(THICK_FOG_DISTANCE + "=" + fogText(thickFogDistance) + "\n");
            out.write("# Show terrain not generated yet from the world's noise, coarsely (single player).\n");
            out.write(NOISE_PREVIEW + "=" + noisePreview + "\n");
            out.write("# Light around the preview's light sources (sea pickles, lava, glowing blocks)."
                    + " Default true.\n");
            out.write(PREVIEW_LIGHT + "=" + previewLight + "\n");
            out.write("# What the preview shows of the world's features: its trees, its plants, and the rest\n");
            out.write("# (rocks, ice spikes, mods' other features, villages). Default true each.\n");
            out.write(PREVIEW_TREES + "=" + previewShows(FEATURE_TREES) + "\n");
            out.write(PREVIEW_PLANTS + "=" + previewShows(FEATURE_PLANTS) + "\n");
            out.write(PREVIEW_OTHERS + "=" + previewShows(FEATURE_OTHERS) + "\n");
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
