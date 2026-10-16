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
 * texture distance (ADR-026), level colors, and whether thick dimension fog
 * (the Nether) is pushed back for the LODs (ADR-033). Missing or invalid keys take
 * their default, out-of-range values are clamped, so a hand-edited file never
 * stops the game.
 */
public record RenderConfig(boolean enabled, float voxelPixels, int textureDistance, boolean levelColors,
                           boolean extendDimensionFog) {

    /** Default target size of an LOD voxel on screen, in pixels, where a level hands over to the next. */
    public static final float DEFAULT_VOXEL_PIXELS = 6f;
    public static final float MIN_VOXEL_PIXELS = 1f;
    public static final float MAX_VOXEL_PIXELS = 32f;
    /** Texture distance meaning every LOD shows its textures, whatever its distance. */
    public static final int ALL_DISTANCES = Integer.MAX_VALUE;
    /** Default: textures on every LOD (ADR-026); the GPU cost was within noise. */
    public static final int DEFAULT_TEXTURE_DISTANCE = ALL_DISTANCES;
    public static final int MAX_TEXTURE_DISTANCE = 4096;

    public static final RenderConfig DEFAULTS =
            new RenderConfig(true, DEFAULT_VOXEL_PIXELS, DEFAULT_TEXTURE_DISTANCE, false, false);

    static final String ENABLED = "lod_rendering";
    static final String VOXEL_PIXELS = "detail_pixels_per_voxel";
    static final String TEXTURE_DISTANCE = "texture_distance";
    static final String LEVEL_COLORS = "level_colors";
    static final String EXTEND_DIMENSION_FOG = "extend_dimension_fog";
    private static final String ALL = "all";

    public RenderConfig {
        voxelPixels = clampVoxelPixels(voxelPixels);
        textureDistance = clampTextureDistance(textureDistance);
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

    /** Reads the file, or returns the defaults when it does not exist. */
    public static RenderConfig read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return DEFAULTS;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new RenderConfig(
                bool(p, ENABLED, DEFAULTS.enabled),
                floatValue(p, VOXEL_PIXELS, DEFAULTS.voxelPixels),
                parseTextureDistance(p, DEFAULTS.textureDistance),
                bool(p, LEVEL_COLORS, DEFAULTS.levelColors),
                bool(p, EXTEND_DIMENSION_FOG, DEFAULTS.extendDimensionFog));
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
            out.write("# Thick dimension fog (the Nether): false, LODs fade into it like the game's terrain;\n");
            out.write("# true, it is pushed back to the LOD horizon. Default false.\n");
            out.write(EXTEND_DIMENSION_FOG + "=" + extendDimensionFog + "\n");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
}
