package io.lakio.farfield.lod;

/**
 * LOD drawing settings changed by command: {@code /farfield render on|off}
 * to compare frame rates in the same session, {@code /farfield detail <px>}
 * for the level of detail. Side-neutral on purpose: the command lives on
 * the (integrated) server, the renderer on the client of the same process;
 * a dedicated server only stores the values.
 */
public final class RenderSettings {

    /** Default target size of an LOD voxel on screen, in pixels, where a level hands over to the next. */
    public static final float DEFAULT_VOXEL_PIXELS = 4f;
    public static final float MIN_VOXEL_PIXELS = 1f;
    public static final float MAX_VOXEL_PIXELS = 32f;

    private static volatile boolean enabled = true;
    private static volatile float voxelPixels = DEFAULT_VOXEL_PIXELS;

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
}
