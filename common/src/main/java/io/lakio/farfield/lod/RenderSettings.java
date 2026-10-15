package io.lakio.farfield.lod;

/**
 * LOD drawing settings changed by command: {@code /farfield render on|off}
 * to compare frame rates in the same session, {@code /farfield detail <px>}
 * for the level of detail, {@code /farfield textures all|<blocks>} for the
 * distance textures reach, {@code /farfield debug levels on|off} to color
 * each LOD level. Side-neutral on purpose: the command lives on
 * the (integrated) server, the renderer on the client of the same process;
 * a dedicated server only stores the values.
 */
public final class RenderSettings {

    /** Default target size of an LOD voxel on screen, in pixels, where a level hands over to the next. */
    public static final float DEFAULT_VOXEL_PIXELS = 8f;
    public static final float MIN_VOXEL_PIXELS = 1f;
    public static final float MAX_VOXEL_PIXELS = 32f;
    /** Texture distance meaning every LOD shows its textures, whatever its distance. */
    public static final int ALL_DISTANCES = Integer.MAX_VALUE;
    /** Default: textures on every LOD (ADR-026); the GPU cost was within noise. */
    public static final int DEFAULT_TEXTURE_DISTANCE = ALL_DISTANCES;
    public static final int MAX_TEXTURE_DISTANCE = 4096;

    private static volatile boolean enabled = true;
    private static volatile float voxelPixels = DEFAULT_VOXEL_PIXELS;
    private static volatile boolean debugLevels;
    private static volatile int textureDistance = DEFAULT_TEXTURE_DISTANCE;

    private RenderSettings() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void set(boolean value) {
        enabled = value;
    }

    /**
     * The largest size, in pixels, an LOD voxel reaches on screen before its
     * section splits into the finer level: lower is finer and costs more
     * memory and GPU time.
     */
    public static float voxelPixels() {
        return voxelPixels;
    }

    public static void setVoxelPixels(float pixels) {
        voxelPixels = Math.max(MIN_VOXEL_PIXELS, Math.min(MAX_VOXEL_PIXELS, pixels));
    }

    /**
     * Distance in blocks up to which LOD faces show their textures; they fade
     * into their mean colors over the last quarter. 0 turns textures off,
     * {@link #ALL_DISTANCES} puts them on every LOD.
     */
    public static int textureDistance() {
        return textureDistance;
    }

    public static void setTextureDistance(int blocks) {
        textureDistance = blocks == ALL_DISTANCES ? ALL_DISTANCES : Math.max(0, Math.min(MAX_TEXTURE_DISTANCE, blocks));
    }

    /** Whether LOD faces are tinted by their level (red 0, orange 1, yellow 2, green 3, blue 4). */
    public static boolean debugLevels() {
        return debugLevels;
    }

    public static void setDebugLevels(boolean value) {
        debugLevels = value;
    }
}
