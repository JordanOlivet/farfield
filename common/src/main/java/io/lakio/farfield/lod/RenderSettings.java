package io.lakio.farfield.lod;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.platform.Services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LOD drawing settings, set in the settings screen or by command:
 * {@code /farfield render on|off} to compare frame rates in the same session,
 * {@code /farfield detail <px>} for the level of detail,
 * {@code /farfield textures all|<blocks>} for the distance textures reach,
 * {@code /farfield debug levels on|off} to color each LOD level,
 * {@code /farfield preview on|off} for the noise preview. Saved in
 * {@code config/farfield-client.properties} on the physical client.
 * Side-neutral on purpose: the command lives on the (integrated) server, the
 * renderer on the client of the same process; a dedicated server only stores
 * the values and never writes the file.
 */
public final class RenderSettings {

    public static final float DEFAULT_VOXEL_PIXELS = RenderConfig.DEFAULT_VOXEL_PIXELS;
    public static final float MIN_VOXEL_PIXELS = RenderConfig.MIN_VOXEL_PIXELS;
    public static final float MAX_VOXEL_PIXELS = RenderConfig.MAX_VOXEL_PIXELS;
    public static final int ALL_DISTANCES = RenderConfig.ALL_DISTANCES;
    public static final int DEFAULT_TEXTURE_DISTANCE = RenderConfig.DEFAULT_TEXTURE_DISTANCE;
    public static final int MAX_TEXTURE_DISTANCE = RenderConfig.MAX_TEXTURE_DISTANCE;

    private static final String FILE_NAME = "farfield-client.properties";

    private static volatile boolean enabled = RenderConfig.DEFAULTS.enabled();
    private static volatile float voxelPixels = RenderConfig.DEFAULTS.voxelPixels();
    private static volatile boolean debugLevels = RenderConfig.DEFAULTS.levelColors();
    private static volatile int textureDistance = RenderConfig.DEFAULTS.textureDistance();
    private static volatile int fogDistance = RenderConfig.DEFAULTS.fogDistance();
    private static volatile int thickFogDistance = RenderConfig.DEFAULTS.thickFogDistance();
    private static volatile boolean noisePreview = RenderConfig.DEFAULTS.noisePreview();
    private static volatile boolean depthPrepass = true;
    private static volatile boolean drawPlants = true;
    private static volatile int meshThreads = RenderConfig.DEFAULTS.meshThreads();
    private static volatile int previewThreads = RenderConfig.DEFAULTS.previewThreads();
    private static volatile boolean performanceLogs = RenderConfig.DEFAULTS.performanceLogs();
    /** Dedicated servers and dev runs, which have no client settings: {@code -Dfarfield.perfLogs=true}. */
    private static final boolean PERFORMANCE_LOGS_PROPERTY = Boolean.getBoolean("farfield.perfLogs");

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
        voxelPixels = RenderConfig.clampVoxelPixels(pixels);
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
        textureDistance = RenderConfig.clampTextureDistance(blocks);
    }

    /** Whether LOD faces are tinted by their level (red 0, orange 1, yellow 2, green 3, blue 4). */
    public static boolean debugLevels() {
        return debugLevels;
    }

    public static void setDebugLevels(boolean value) {
        debugLevels = value;
    }

    /**
     * Whether opaque LOD faces get a depth pre-pass, so the full fragment
     * shader runs once per pixel. A diagnostic switch for comparing GPU
     * times ({@code /farfield debug prepass}), on at every start, not saved.
     */
    public static boolean depthPrepass() {
        return depthPrepass;
    }

    public static void setDepthPrepass(boolean value) {
        depthPrepass = value;
    }

    /**
     * Whether the plants' crossed quads are drawn (ADR-059, ADR-065). A
     * diagnostic switch for comparing GPU times ({@code /farfield debug
     * plants}), on at every start, not saved.
     */
    public static boolean drawPlants() {
        return drawPlants;
    }

    public static void setDrawPlants(boolean value) {
        drawPlants = value;
    }

    /** Threads that mesh LOD sections, as set: 1 to 8, or {@link RenderConfig#AUTO}. Applied live. */
    public static int meshThreads() {
        return meshThreads;
    }

    public static void setMeshThreads(int value) {
        meshThreads = RenderConfig.clampThreads(value);
    }

    /** Threads that build the noise preview, as set: 1 to 8, or {@link RenderConfig#AUTO}. Applied live. */
    public static int previewThreads() {
        return previewThreads;
    }

    public static void setPreviewThreads(int value) {
        previewThreads = RenderConfig.clampThreads(value);
    }

    /** The thread count a setting stands for on this machine ({@link RenderConfig#threads}). */
    public static int effectiveThreads(int setting) {
        return RenderConfig.threads(setting, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Resizes a pool to {@code threads} (growing raises the maximum first,
     * shrinking lowers the core size first, as the executor requires); a
     * thread past the new size ends after its current task.
     */
    public static void resize(java.util.concurrent.ThreadPoolExecutor pool, int threads, String name) {
        if (pool.getCorePoolSize() == threads) {
            return;
        }
        if (threads > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(threads);
            pool.setCorePoolSize(threads);
        } else {
            pool.setCorePoolSize(threads);
            pool.setMaximumPoolSize(threads);
        }
        Farfield.LOG.info("{} threads: {}", name, threads);
    }

    /**
     * Where the fog becomes opaque in normal dimensions, in blocks, or
     * {@link RenderConfig#FOG_OFF} (ADR-034).
     */
    public static int fogDistance() {
        return fogDistance;
    }

    public static void setFogDistance(int blocks) {
        fogDistance = RenderConfig.clampFogDistance(blocks);
    }

    /**
     * The same in dimensions with thick fog (the Nether), or
     * {@link RenderConfig#FOG_VANILLA}: LODs fade into the game's own fog
     * (ADR-033, ADR-034).
     */
    public static int thickFogDistance() {
        return thickFogDistance;
    }

    public static void setThickFogDistance(int blocks) {
        thickFogDistance = RenderConfig.clampThickFogDistance(blocks);
    }

    /**
     * Whether terrain not generated yet shows from the world's noise
     * (ADR-038): the integrated server builds it, the renderer draws it.
     */
    public static boolean noisePreview() {
        return noisePreview;
    }

    public static void setNoisePreview(boolean value) {
        noisePreview = value;
    }

    /**
     * Whether the performance lines go to the log (stat lines, fill and build
     * times): the client setting, or the {@code farfield.perfLogs} system
     * property. Lifecycle lines (stores opened and closed, settings) always do.
     */
    public static boolean performanceLogs() {
        return performanceLogs || PERFORMANCE_LOGS_PROPERTY;
    }

    public static void setPerformanceLogs(boolean value) {
        performanceLogs = value;
    }

    /** Physical client only: reads the saved settings, or keeps the defaults. */
    public static void load() {
        if (!Services.PLATFORM.isClient()) {
            return;
        }
        Path file = file();
        try {
            RenderConfig config = RenderConfig.read(file);
            enabled = config.enabled();
            voxelPixels = config.voxelPixels();
            textureDistance = config.textureDistance();
            debugLevels = config.levelColors();
            fogDistance = config.fogDistance();
            thickFogDistance = config.thickFogDistance();
            noisePreview = config.noisePreview();
            meshThreads = config.meshThreads();
            previewThreads = config.previewThreads();
            performanceLogs = config.performanceLogs();
            Farfield.LOG.info(Files.exists(file) ? "LOD settings loaded from {}: {}" : "No {} yet, LOD settings at their defaults: {}",
                    file.getFileName(), config);
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("Could not read {}, LOD settings keep their defaults", file, e);
        }
    }

    /** Physical client only: writes the current settings. */
    public static void save() {
        if (!Services.PLATFORM.isClient()) {
            return;
        }
        Path file = file();
        RenderConfig config = new RenderConfig(enabled, voxelPixels, textureDistance, debugLevels, fogDistance,
                thickFogDistance, noisePreview, meshThreads, previewThreads, performanceLogs);
        try {
            config.write(file);
            Farfield.LOG.info("LOD settings saved to {}: {}", file.getFileName(), config);
        } catch (IOException e) {
            Farfield.LOG.warn("Could not write {}", file, e);
        }
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(FILE_NAME);
    }
}
