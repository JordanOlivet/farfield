package io.lakio.farfield.lod;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.core.select.LodSelector;
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
                        .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(), () -> preview(false)))))
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
                        .then(LiteralArgumentBuilder.<S>literal("plants")
                                .executes(c -> run.run(c.getSource(), () -> debugPlants(!RenderSettings.drawPlants())))
                                .then(LiteralArgumentBuilder.<S>literal("on").executes(c -> run.run(c.getSource(),
                                        () -> debugPlants(true))))
                                .then(LiteralArgumentBuilder.<S>literal("off").executes(c -> run.run(c.getSource(),
                                        () -> debugPlants(false))))));
    }

    /** Switches the plants' crossed quads, to compare GPU times on the stat line. Not saved. */
    private static String debugPlants(boolean on) {
        RenderSettings.setDrawPlants(on);
        Farfield.LOG.info("LOD plants switched {} by command", on ? "on" : "off");
        return "LOD plants " + (on ? "on" : "off") + " (default on, not saved; GPU times on the next stat lines).";
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
                : "LOD level lock on level " + level + " (" + (1 << level) + " blocks per voxel), within "
                + LodSelector.FORCED_RADIUS_SECTIONS * (32 << level) + " blocks; the game's terrain no longer hides"
                + " the LODs (lower the render distance to see them up close). Not saved; off: debug level off.";
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
