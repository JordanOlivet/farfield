package io.lakio.farfield.client;

import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.core.config.ServerConfig;
import io.lakio.farfield.lod.RenderSettings;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Farfield's client settings, described once for every screen that shows
 * them: Farfield's own settings screen, and Sodium's video settings screen
 * when Sodium (0.8 on) is installed. A setting reads and writes {@link
 * RenderSettings}; the screens save them when they close or apply.
 *
 * <p>Four pages, as the tabs of the settings screen: Rendering (what every
 * player sets), Preview, Performance (thread counts) and Debug.
 */
public final class SettingsModel {

    /** One setting: its translation key (its name; its tooltip is the key + {@code .tooltip}). */
    public sealed interface Setting permits Toggle, Slider {
        String key();

        Component tooltip();
    }

    /**
     * On or off. {@code label}: the value's text in Farfield's screen
     * (null: vanilla's On and Off).
     */
    public record Toggle(String key, boolean defaultValue, BooleanSupplier get, Consumer<Boolean> set,
                         @Nullable Function<Boolean, Component> label, Supplier<Component> tooltipText)
            implements Setting {
        @Override
        public Component tooltip() {
            return tooltipText.get();
        }
    }

    /**
     * Steps from {@code min} to {@code max}, each with its text. {@code
     * applyOnRelease}: Farfield's screen applies the value when the slider
     * is released, not at each step (each change re-selects the sections).
     */
    public record Slider(String key, int min, int max, int defaultStep, IntSupplier get, IntConsumer set,
                         IntFunction<Component> label, boolean applyOnRelease, Supplier<Component> tooltipText)
            implements Setting {
        @Override
        public Component tooltip() {
            return tooltipText.get();
        }
    }

    /** A page of settings, its title a translation key. */
    public record Page(String key, List<Setting> settings) {
    }

    /** Texture distance slider: 0 off, 1 to {@link #TEXTURE_STEPS} times this many blocks, then all LODs. */
    static final int TEXTURE_STEP = 64;
    static final int TEXTURE_STEPS = RenderSettings.MAX_TEXTURE_DISTANCE / TEXTURE_STEP;
    static final int TEXTURE_ALL = TEXTURE_STEPS + 1;
    static final int FOG_STEP = 128;
    /** Past the default draw distance the fog slider goes by this many blocks (E.3b) to {@link #DOUBLING_FROM}. */
    static final int FAR_FOG_STEP = 1024;
    /** From here the fog slider doubles at each step, to the maximum (E.3d: to the world border). */
    static final int DOUBLING_FROM = 32768;
    /** Steps of {@link #FOG_STEP} from the minimum to the default draw distance included. */
    static final int NEAR_FOG_STEPS =
            (RenderConfig.DEFAULT_DRAW_DISTANCE - RenderConfig.MIN_FOG_DISTANCE) / FOG_STEP + 1;
    /** Steps up to {@link #DOUBLING_FROM} included. */
    static final int LINEAR_FOG_STEPS = NEAR_FOG_STEPS
            + (DOUBLING_FROM - RenderConfig.DEFAULT_DRAW_DISTANCE) / FAR_FOG_STEP;
    /** Last step of the normal fog slider (off); the thick fog slider has one more (vanilla first). */
    static final int FOG_STEPS = LINEAR_FOG_STEPS
            + Integer.numberOfTrailingZeros(RenderConfig.MAX_FOG_DISTANCE / DOUBLING_FROM);
    /** Blocks a step of the features' distance slider: 1,250 (the default) is 25 of them. */
    static final int FEATURES_STEP = 50;

    private SettingsModel() {
    }

    /** The pages, read fresh (a setting's current value is read through its getter). */
    public static List<Page> pages() {
        RenderConfig d = RenderConfig.DEFAULTS;
        return List.of(
                new Page("farfield.options.tab.rendering", List.of(
                        toggle("farfield.options.rendering", d.enabled(), RenderSettings::enabled, RenderSettings::set),
                        // Applied when the slider is released: each new value re-selects the LOD sections.
                        new Slider("farfield.options.detail", (int) RenderSettings.MIN_VOXEL_PIXELS,
                                (int) RenderSettings.MAX_VOXEL_PIXELS, Math.round(d.voxelPixels()),
                                () -> Math.round(RenderSettings.voxelPixels()), RenderSettings::setVoxelPixels,
                                pixels -> Component.translatable("farfield.options.detail.value", pixels), true,
                                tooltip("farfield.options.detail")),
                        slider("farfield.options.textures", 0, TEXTURE_ALL, textureStep(d.textureDistance()),
                                () -> textureStep(RenderSettings.textureDistance()),
                                step -> RenderSettings.setTextureDistance(textureDistance(step)),
                                SettingsModel::textureLabel),
                        // The draw distance, then the fog shown or not over it (ADR-140): "off" held the LODs
                        // to 4,096 blocks.
                        slider("farfield.options.fog", 0, FOG_STEPS - 1, fogStep(RenderConfig.drawDistance(d.fogDistance()), false),
                                () -> fogStep(RenderSettings.drawDistance(), false),
                                step -> RenderSettings.setFogDistance(fogDistance(step, false)),
                                step -> fogLabel(fogDistance(step, false))),
                        toggle("farfield.options.fog_shown", d.fog(), RenderSettings::fog, RenderSettings::setFog),
                        slider("farfield.options.thick_fog", 0, FOG_STEPS + 1, fogStep(d.thickFogDistance(), true),
                                () -> fogStep(RenderSettings.thickFogDistance(), true),
                                step -> RenderSettings.setThickFogDistance(fogDistance(step, true)),
                                step -> fogLabel(fogDistance(step, true))))),
                new Page("farfield.options.tab.preview", List.of(
                        toggle("farfield.options.noise_preview", d.noisePreview(), RenderSettings::noisePreview,
                                RenderSettings::setNoisePreview),
                        toggle("farfield.options.preview_light", d.previewLight(), RenderSettings::previewLight,
                                RenderSettings::setPreviewLight),
                        previewFeature("farfield.options.preview_trees", RenderConfig.FEATURE_TREES),
                        previewFeature("farfield.options.preview_plants", RenderConfig.FEATURE_PLANTS),
                        previewFeature("farfield.options.preview_others", RenderConfig.FEATURE_OTHERS),
                        // Single player: the integrated server's distance for the world's own features (a
                        // dedicated server has its own preview_features_distance). Steps of 50 blocks.
                        slider("farfield.options.preview_features_distance", 0,
                                ServerConfig.MAX_FEATURES_DISTANCE / FEATURES_STEP,
                                Math.round(d.previewFeaturesDistance() / (float) FEATURES_STEP),
                                () -> Math.round(RenderSettings.previewFeaturesDistance() / (float) FEATURES_STEP),
                                step -> RenderSettings.setPreviewFeaturesDistance(step * FEATURES_STEP),
                                step -> step == 0
                                        ? Component.translatable("farfield.options.preview_features_distance.off")
                                        : Component.translatable("farfield.options.fog.blocks", step * FEATURES_STEP)))),
                new Page("farfield.options.tab.performance", List.of(
                        threads("farfield.options.mesh_threads", RenderSettings::meshThreads,
                                RenderSettings::setMeshThreads),
                        threads("farfield.options.preview_threads", RenderSettings::previewThreads,
                                RenderSettings::setPreviewThreads))),
                new Page("farfield.options.tab.debug", List.of(
                        toggle("farfield.options.level_colors", d.levelColors(), RenderSettings::debugLevels,
                                RenderSettings::setDebugLevels),
                        toggle("farfield.options.performance_logs", d.performanceLogs(),
                                RenderSettings::performanceLogs, RenderSettings::setPerformanceLogs))));
    }

    private static Supplier<Component> tooltip(String key) {
        Component text = Component.translatable(key + ".tooltip");
        return () -> text;
    }

    private static Toggle toggle(String key, boolean defaultValue, BooleanSupplier get, Consumer<Boolean> set) {
        return new Toggle(key, defaultValue, get, set, null, tooltip(key));
    }

    private static Slider slider(String key, int min, int max, int defaultStep, IntSupplier get, IntConsumer set,
                                 IntFunction<Component> label) {
        return new Slider(key, min, max, defaultStep, get, set, label, false, tooltip(key));
    }

    /** Shown or hidden: one kind of the preview's features (plan item D.3). */
    private static Toggle previewFeature(String key, int feature) {
        return new Toggle(key, (RenderConfig.DEFAULTS.previewFeatures() & feature) != 0,
                () -> RenderSettings.previewShows(feature), shown -> RenderSettings.setPreviewShows(feature, shown),
                shown -> Component.translatable(
                        shown ? "farfield.options.preview_feature.shown" : "farfield.options.preview_feature.hidden"),
                tooltip(key));
    }

    /**
     * A thread count slider: Auto first (the count it stands for on this
     * machine shown), then 1 to this machine's logical processors.
     */
    private static Slider threads(String key, IntSupplier get, IntConsumer set) {
        int processors = Runtime.getRuntime().availableProcessors();
        int max = RenderConfig.maxThreads(processors);
        return new Slider(key, RenderConfig.AUTO, max, RenderConfig.AUTO, () -> Math.min(get.getAsInt(), max), set,
                threads -> threads == RenderConfig.AUTO
                        ? Component.translatable("farfield.options.threads.auto", RenderSettings.effectiveThreads(threads))
                        : Component.literal(Integer.toString(threads)),
                false,
                () -> Component.translatable(key + ".tooltip", RenderSettings.effectiveThreads(RenderConfig.AUTO), max));
    }

    static Component textureLabel(int step) {
        if (step == 0) {
            return Component.translatable("farfield.options.textures.off");
        }
        if (step == TEXTURE_ALL) {
            return Component.translatable("farfield.options.textures.all");
        }
        return Component.translatable("farfield.options.textures.blocks", step * TEXTURE_STEP);
    }

    /**
     * Fog distance sliders: {@link RenderConfig#MIN_FOG_DISTANCE} to the
     * default draw distance by {@link #FOG_STEP}, then to {@link
     * RenderConfig#MAX_FOG_DISTANCE} by {@link #FAR_FOG_STEP}, then off; the
     * thick fog slider starts with vanilla.
     */
    static int fogDistance(int step, boolean thick) {
        if (thick) {
            if (step == 0) {
                return RenderConfig.FOG_VANILLA;
            }
            step--;
        }
        if (step >= FOG_STEPS) {
            return RenderConfig.FOG_OFF;
        }
        if (step < NEAR_FOG_STEPS) {
            return RenderConfig.MIN_FOG_DISTANCE + step * FOG_STEP;
        }
        if (step < LINEAR_FOG_STEPS) {
            return RenderConfig.DEFAULT_DRAW_DISTANCE + (step - NEAR_FOG_STEPS + 1) * FAR_FOG_STEP;
        }
        return DOUBLING_FROM << (step - LINEAR_FOG_STEPS + 1);
    }

    static int fogStep(int blocks, boolean thick) {
        int offset = thick ? 1 : 0;
        if (blocks == RenderConfig.FOG_VANILLA) {
            return 0;
        }
        if (blocks == RenderConfig.FOG_OFF) {
            return FOG_STEPS + offset;
        }
        if (blocks <= RenderConfig.DEFAULT_DRAW_DISTANCE) {
            return Math.round((blocks - RenderConfig.MIN_FOG_DISTANCE) / (float) FOG_STEP) + offset;
        }
        if (blocks <= DOUBLING_FROM) {
            return NEAR_FOG_STEPS - 1 + Math.round((blocks - RenderConfig.DEFAULT_DRAW_DISTANCE) / (float) FAR_FOG_STEP)
                    + offset;
        }
        int doublings = 63 - Long.numberOfLeadingZeros(Math.max(1, Math.round(blocks / (double) DOUBLING_FROM)));
        return Math.min(FOG_STEPS - 1, LINEAR_FOG_STEPS - 1 + doublings) + offset;
    }

    static Component fogLabel(int blocks) {
        if (blocks == RenderConfig.FOG_OFF) {
            return Component.translatable("farfield.options.fog.off");
        }
        if (blocks == RenderConfig.FOG_VANILLA) {
            return Component.translatable("farfield.options.fog.vanilla");
        }
        return Component.translatable("farfield.options.fog.blocks", blocks);
    }

    static int textureDistance(int step) {
        return step == TEXTURE_ALL ? RenderSettings.ALL_DISTANCES : step * TEXTURE_STEP;
    }

    /** The nearest slider step; a distance set by command keeps its exact value until the slider moves. */
    static int textureStep(int blocks) {
        return blocks == RenderSettings.ALL_DISTANCES ? TEXTURE_ALL : Math.round(blocks / (float) TEXTURE_STEP);
    }
}
