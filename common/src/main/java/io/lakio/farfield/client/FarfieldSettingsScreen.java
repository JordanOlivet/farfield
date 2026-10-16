package io.lakio.farfield.client;

import io.lakio.farfield.lod.RenderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Farfield's settings screen, opened from the mod list (NeoForge's Mods
 * screen, Mod Menu on Fabric). Changes apply at once and are saved to
 * {@code config/farfield-client.properties} when the screen closes; the
 * {@code /farfield} commands change the same values.
 */
public final class FarfieldSettingsScreen extends OptionsSubScreen {

    /** Texture distance slider: 0 off, 1 to {@link #TEXTURE_STEPS} times this many blocks, then all LODs. */
    private static final int TEXTURE_STEP = 64;
    private static final int TEXTURE_STEPS = RenderSettings.MAX_TEXTURE_DISTANCE / TEXTURE_STEP;
    private static final int TEXTURE_ALL = TEXTURE_STEPS + 1;

    public FarfieldSettingsScreen(Screen lastScreen) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable("farfield.options.title"));
    }

    @Override
    protected void addOptions() {
        list.addBig(OptionInstance.createBoolean("farfield.options.rendering",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.rendering.tooltip")),
                RenderSettings.enabled(), RenderSettings::set));
        // Applied when the slider is released: each new value re-selects the LOD sections.
        list.addBig(new OptionInstance<>("farfield.options.detail",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.detail.tooltip")),
                (caption, pixels) -> Options.genericValueLabel(caption,
                        Component.translatable("farfield.options.detail.value", pixels)),
                new OptionInstance.IntRange((int) RenderSettings.MIN_VOXEL_PIXELS, (int) RenderSettings.MAX_VOXEL_PIXELS, false),
                Math.round(RenderSettings.voxelPixels()),
                pixels -> RenderSettings.setVoxelPixels(pixels)));
        list.addBig(new OptionInstance<>("farfield.options.textures",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.textures.tooltip")),
                (caption, step) -> Options.genericValueLabel(caption, textureLabel(step)),
                new OptionInstance.IntRange(0, TEXTURE_ALL),
                textureStep(RenderSettings.textureDistance()),
                step -> RenderSettings.setTextureDistance(textureDistance(step))));
        list.addBig(OptionInstance.createBoolean("farfield.options.dimension_fog",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.dimension_fog.tooltip")),
                // The cycle button adds the caption itself, as for vanilla's ON / OFF.
                (caption, extended) -> Component.translatable(extended
                        ? "farfield.options.dimension_fog.extended" : "farfield.options.dimension_fog.vanilla"),
                RenderSettings.extendDimensionFog(), RenderSettings::setExtendDimensionFog));
        list.addBig(OptionInstance.createBoolean("farfield.options.level_colors",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.level_colors.tooltip")),
                RenderSettings.debugLevels(), RenderSettings::setDebugLevels));
    }

    @Override
    public void removed() {
        super.removed();
        RenderSettings.save();
    }

    private static Component textureLabel(int step) {
        if (step == 0) {
            return Component.translatable("farfield.options.textures.off");
        }
        if (step == TEXTURE_ALL) {
            return Component.translatable("farfield.options.textures.all");
        }
        return Component.translatable("farfield.options.textures.blocks", step * TEXTURE_STEP);
    }

    private static int textureDistance(int step) {
        return step == TEXTURE_ALL ? RenderSettings.ALL_DISTANCES : step * TEXTURE_STEP;
    }

    /** The nearest slider step; a distance set by command keeps its exact value until the slider moves. */
    private static int textureStep(int blocks) {
        return blocks == RenderSettings.ALL_DISTANCES ? TEXTURE_ALL : Math.round(blocks / (float) TEXTURE_STEP);
    }
}
