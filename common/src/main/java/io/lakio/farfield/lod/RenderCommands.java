package io.lakio.farfield.lod;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.core.world.LodWorld;

import java.util.function.Supplier;

/**
 * The LOD drawing settings as commands ({@code render}, {@code detail},
 * {@code textures}, {@code fog}, {@code preview}, {@code debug}), generic
 * over the command source: under {@code /farfieldclient} they act on the
 * player's own client whatever the server, under {@code /farfield} only in
 * single player (a dedicated server has no client to change).
 */
public final class RenderCommands {

    private RenderCommands() {
    }

    /** Runs a setting change and replies with its line, or refuses where the source has no client. */
    @FunctionalInterface
    public interface Runner<S> {
        int run(S source, Supplier<String> change);
    }

    /** Adds the settings under {@code root}, whose name {@code command} the reply lines quote. */
    public static <S> LiteralArgumentBuilder<S> addTo(LiteralArgumentBuilder<S> root, String command, Runner<S> run) {
        return root
                .then(LiteralArgumentBuilder.<S>literal("render")
                        .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(), () -> render(true))))
                        .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(), () -> render(false)))))
                .then(LiteralArgumentBuilder.<S>literal("detail")
                        .executes(c -> run.run(c.getSource(), RenderCommands::showDetail))
                        .then(RequiredArgumentBuilder.<S, Float>argument("pixels", FloatArgumentType.floatArg(
                                        RenderSettings.MIN_VOXEL_PIXELS, RenderSettings.MAX_VOXEL_PIXELS))
                                .executes(c -> run.run(c.getSource(),
                                        () -> detail(FloatArgumentType.getFloat(c, "pixels"))))))
                .then(LiteralArgumentBuilder.<S>literal("textures")
                        .executes(c -> run.run(c.getSource(), () -> showTextures(command)))
                        .then(LiteralArgumentBuilder.<S>literal("all").executes(c -> run.run(c.getSource(),
                                () -> textures(command, RenderSettings.ALL_DISTANCES))))
                        .then(RequiredArgumentBuilder.<S, Integer>argument("blocks", IntegerArgumentType.integer(0,
                                        RenderSettings.MAX_TEXTURE_DISTANCE))
                                .executes(c -> run.run(c.getSource(),
                                        () -> textures(command, IntegerArgumentType.getInteger(c, "blocks"))))))
                .then(LiteralArgumentBuilder.<S>literal("fog")
                        .executes(c -> run.run(c.getSource(), () -> showFog(command)))
                        .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                () -> fog(command, false, RenderConfig.FOG_OFF))))
                        .then(RequiredArgumentBuilder.<S, Integer>argument("blocks", IntegerArgumentType.integer(
                                        RenderConfig.MIN_FOG_DISTANCE, RenderConfig.MAX_FOG_DISTANCE))
                                .executes(c -> run.run(c.getSource(),
                                        () -> fog(command, false, IntegerArgumentType.getInteger(c, "blocks")))))
                        .then(LiteralArgumentBuilder.<S>literal("thick")
                                .executes(c -> run.run(c.getSource(), () -> showFog(command)))
                                .then(LiteralArgumentBuilder.<S>literal("vanilla").executes(c -> run.run(c.getSource(),
                                        () -> fog(command, true, RenderConfig.FOG_VANILLA))))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> fog(command, true, RenderConfig.FOG_OFF))))
                                .then(RequiredArgumentBuilder.<S, Integer>argument("blocks", IntegerArgumentType.integer(
                                                RenderConfig.MIN_FOG_DISTANCE, RenderConfig.MAX_FOG_DISTANCE))
                                        .executes(c -> run.run(c.getSource(),
                                                () -> fog(command, true, IntegerArgumentType.getInteger(c, "blocks")))))))
                .then(LiteralArgumentBuilder.<S>literal("preview")
                        .executes(c -> run.run(c.getSource(), () -> preview(!RenderSettings.noisePreview())))
                        .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(), () -> preview(true))))
                        .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(), () -> preview(false))))
                        .then(LiteralArgumentBuilder.<S>literal("light")
                                .executes(c -> run.run(c.getSource(), () -> previewLight(!RenderSettings.previewLight())))
                                .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(),
                                        () -> previewLight(true))))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> previewLight(false)))))
                        .then(previewFeature(run, "trees", RenderConfig.FEATURE_TREES))
                        .then(previewFeature(run, "plants", RenderConfig.FEATURE_PLANTS))
                        .then(previewFeature(run, "others", RenderConfig.FEATURE_OTHERS)))
                .then(LiteralArgumentBuilder.<S>literal("debug")
                        .then(LiteralArgumentBuilder.<S>literal("levels")
                                .executes(c -> run.run(c.getSource(), () -> debugLevels(!RenderSettings.debugLevels())))
                                .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(),
                                        () -> debugLevels(true))))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> debugLevels(false)))))
                        .then(LiteralArgumentBuilder.<S>literal("prepass")
                                .executes(c -> run.run(c.getSource(), () -> debugPrepass(!RenderSettings.depthPrepass())))
                                .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(),
                                        () -> debugPrepass(true))))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> debugPrepass(false)))))
                        .then(LiteralArgumentBuilder.<S>literal("level")
                                .executes(c -> run.run(c.getSource(), RenderCommands::showForcedLevel))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> forcedLevel(-1))))
                                .then(RequiredArgumentBuilder.<S, Integer>argument("level", IntegerArgumentType.integer(
                                                0, LodWorld.LEVELS - 1))
                                        .executes(c -> run.run(c.getSource(),
                                                () -> forcedLevel(IntegerArgumentType.getInteger(c, "level"))))))
                        .then(LiteralArgumentBuilder.<S>literal("shaderpack")
                                .then(LiteralArgumentBuilder.<S>literal("auto").executes(c -> run.run(c.getSource(),
                                        () -> io.lakio.farfield.client.render.LodRenderer.forcePackContract(0))))
                                .then(LiteralArgumentBuilder.<S>literal("voxy").executes(c -> run.run(c.getSource(),
                                        () -> io.lakio.farfield.client.render.LodRenderer.forcePackContract(1))))
                                .then(LiteralArgumentBuilder.<S>literal("dh").executes(c -> run.run(c.getSource(),
                                        () -> io.lakio.farfield.client.render.LodRenderer.forcePackContract(2)))))
                        .then(LiteralArgumentBuilder.<S>literal("ao")
                                .executes(c -> run.run(c.getSource(), () -> debugAo(!RenderSettings.ambientOcclusion())))
                                .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(),
                                        () -> debugAo(true))))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> debugAo(false)))))
                        .then(LiteralArgumentBuilder.<S>literal("plants")
                                .executes(c -> run.run(c.getSource(), () -> debugPlants(!RenderSettings.drawPlants())))
                                .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(),
                                        () -> debugPlants(true))))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> debugPlants(false)))))
                        .then(LiteralArgumentBuilder.<S>literal("water")
                                .executes(c -> run.run(c.getSource(), () -> debugWater(!RenderSettings.drawTranslucent())))
                                .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(),
                                        () -> debugWater(true))))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> debugWater(false))))));
    }

    /** Switches the LOD translucent pass (water, ice, glass), to look at sea floors. Not saved. */
    private static String debugWater(boolean on) {
        RenderSettings.setDrawTranslucent(on);
        Farfield.LOG.info("LOD translucent pass switched {} by command", on ? "on" : "off");
        return "LOD water, ice and glass " + (on ? "on" : "off") + " (default on, not saved).";
    }

    /** Switches the plants' crossed quads, to compare GPU times on the stat line. Not saved. */
    private static String debugPlants(boolean on) {
        RenderSettings.setDrawPlants(on);
        Farfield.LOG.info("LOD plants switched {} by command", on ? "on" : "off");
        return "LOD plants " + (on ? "on" : "off") + " (default on, not saved; GPU times on the next stat lines).";
    }

    /** Switches the LOD ambient occlusion, to compare GPU times on the stat line. Not saved. */
    private static String debugAo(boolean on) {
        RenderSettings.setAmbientOcclusion(on);
        Farfield.LOG.info("LOD ambient occlusion switched {} by command", on ? "on" : "off");
        return "LOD ambient occlusion " + (on ? "on" : "off") + " (default on, not saved; GPU times on the next stat lines).";
    }

    /** Switches the LOD depth pre-pass, to compare GPU times on the stat line. Not saved. */
    private static String debugPrepass(boolean on) {
        RenderSettings.setDepthPrepass(on);
        Farfield.LOG.info("LOD depth pre-pass switched {} by command", on ? "on" : "off");
        return "LOD depth pre-pass " + (on ? "on" : "off") + " (default on, not saved; GPU times on the next stat lines).";
    }

    /** The distance up to which LOD faces show their textures (0: off). */
    private static String textures(String command, int blocks) {
        RenderSettings.setTextureDistance(blocks);
        RenderSettings.save();
        Farfield.LOG.info("LOD texture distance set to {} by command",
                RenderSettings.textureDistance() == RenderSettings.ALL_DISTANCES ? "all LODs"
                        : RenderSettings.textureDistance() + " blocks");
        return showTextures(command);
    }

    private static String showTextures(String command) {
        int blocks = RenderSettings.textureDistance();
        String what = blocks == 0 ? "LOD textures off"
                : blocks == RenderSettings.ALL_DISTANCES ? "LOD textures on every LOD"
                : "LOD textures up to " + blocks + " blocks, fading over the last quarter";
        return what + " (default all; /" + command + " textures all|<blocks>, 0 turns them off).";
    }

    /** Where the fog becomes opaque, in normal or thick-fog dimensions (ADR-034). */
    private static String fog(String command, boolean thick, int blocks) {
        if (thick) {
            RenderSettings.setThickFogDistance(blocks);
        } else {
            RenderSettings.setFogDistance(blocks);
        }
        RenderSettings.save();
        Farfield.LOG.info("LOD {}fog distance set to {} by command", thick ? "thick " : "",
                fogText(thick ? RenderSettings.thickFogDistance() : RenderSettings.fogDistance()));
        return showFog(command);
    }

    private static String showFog(String command) {
        return "LOD fog: " + fogText(RenderSettings.fogDistance()) + " (thick fog: "
                + fogText(RenderSettings.thickFogDistance()) + "); /" + command + " fog [thick] off|<"
                + RenderConfig.MIN_FOG_DISTANCE + ".." + RenderConfig.MAX_FOG_DISTANCE + ">, /" + command
                + " fog thick vanilla.";
    }

    private static String fogText(int blocks) {
        return blocks == RenderConfig.FOG_OFF ? "off" : blocks == RenderConfig.FOG_VANILLA ? "vanilla"
                : blocks + " blocks";
    }

    /** Shows terrain not generated yet from the world's noise (ADR-038). */
    private static String preview(boolean on) {
        RenderSettings.setNoisePreview(on);
        RenderSettings.save();
        Farfield.LOG.info("LOD noise preview switched {} by command", on ? "on" : "off");
        return on ? "Noise preview on: terrain not generated yet shows coarsely." : "Noise preview off.";
    }

    /** {@code preview <trees|plants|others> [on|off]}: one kind of the preview's features (plan item D.3). */
    private static <S> LiteralArgumentBuilder<S> previewFeature(Runner<S> run, String name, int feature) {
        return LiteralArgumentBuilder.<S>literal(name)
                .executes(c -> run.run(c.getSource(), () -> previewFeature(name, feature,
                        !RenderSettings.previewShows(feature))))
                .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(),
                        () -> previewFeature(name, feature, true))))
                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                        () -> previewFeature(name, feature, false))));
    }

    private static String previewFeature(String name, int feature, boolean on) {
        RenderSettings.setPreviewShows(feature, on);
        RenderSettings.save();
        Farfield.LOG.info("LOD preview {} switched {} by command", name, on ? "on" : "off");
        return "Preview " + name + " " + (on ? "shown" : "hidden") + ": the preview is meshed again.";
    }

    /** The light of the preview's light sources (ADR-117), saved like the settings screen's. */
    private static String previewLight(boolean on) {
        RenderSettings.setPreviewLight(on);
        RenderSettings.save();
        Farfield.LOG.info("LOD preview light switched {} by command", on ? "on" : "off");
        return "Preview light " + (on ? "on" : "off") + ": the preview is meshed again.";
    }

    /** Tints LOD faces by level, to see where each level hands over. */
    private static String debugLevels(boolean on) {
        RenderSettings.setDebugLevels(on);
        RenderSettings.save();
        Farfield.LOG.info("LOD level colors switched {} by command", on ? "on" : "off");
        return on ? "LOD level colors on: red 1 block, orange 2, yellow 4, green 8, blue 16."
                : "LOD level colors off.";
    }

    /** Locks every LOD section to one level, a diagnostic. Not saved. */
    private static String forcedLevel(int level) {
        RenderSettings.setForcedLevel(level);
        Farfield.LOG.info("LOD level lock {} by command", level < 0 ? "off" : "on level " + level);
        return level < 0 ? "LOD level lock off: levels by distance again."
                : "LOD level lock on level " + level + " (" + (1 << level) + " blocks per voxel): nothing finer,"
                + " coarser levels far away as usual; the game's terrain no longer hides the LODs (lower the render"
                + " distance to see them up close). Not saved; off: debug level off.";
    }

    private static String showForcedLevel() {
        int level = RenderSettings.forcedLevel();
        return level < 0 ? "LOD level lock off (debug level <0-" + (LodWorld.LEVELS - 1) + "> locks one)."
                : "LOD level lock on level " + level + " (debug level off unlocks).";
    }

    /** Switches LOD drawing, to compare frame rates. */
    private static String render(boolean on) {
        RenderSettings.set(on);
        RenderSettings.save();
        Farfield.LOG.info("LOD rendering switched {} by command", on ? "on" : "off");
        return "LOD rendering " + (on ? "on" : "off") + ".";
    }

    /** The largest on-screen size of an LOD voxel before the finer level takes over. */
    private static String detail(float pixels) {
        RenderSettings.setVoxelPixels(pixels);
        RenderSettings.save();
        Farfield.LOG.info("LOD detail set to {} px per voxel by command", RenderSettings.voxelPixels());
        return showDetail();
    }

    private static String showDetail() {
        return "LOD detail: " + RenderSettings.voxelPixels() + " px per voxel (lower is finer; default "
                + RenderSettings.DEFAULT_VOXEL_PIXELS + ").";
    }
}
