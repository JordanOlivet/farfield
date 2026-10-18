package io.lakio.farfield.server;

import com.mojang.brigadier.CommandDispatcher;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.lod.RenderSettings;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * {@code /farfield import [threads]}, {@code /farfield import stop},
 * {@code /farfield import status}: build the LODs of the current dimension
 * from its region files. {@code /farfield render on|off}: switch LOD drawing
 * in single player. {@code /farfield spike generation [side]}: measures the
 * cost of showing terrain not generated yet ({@link GenerationSpike}; test
 * worlds only). Operators, or the owner of a single-player world (cheats off
 * included).
 */
public final class FarfieldCommands {

    private FarfieldCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("farfield")
                .requires(FarfieldCommands::allowed)
                .then(Commands.literal("import")
                        .executes(c -> start(c, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)))
                        .then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "threads"))))
                        .then(Commands.literal("stop").executes(FarfieldCommands::stop))
                        .then(Commands.literal("status").executes(FarfieldCommands::status)))
                .then(Commands.literal("render")
                        .then(Commands.literal("on").executes(c -> render(c, true)))
                        .then(Commands.literal("off").executes(c -> render(c, false))))
                .then(Commands.literal("detail")
                        .executes(FarfieldCommands::showDetail)
                        .then(Commands.argument("pixels", FloatArgumentType.floatArg(
                                        RenderSettings.MIN_VOXEL_PIXELS, RenderSettings.MAX_VOXEL_PIXELS))
                                .executes(c -> detail(c, FloatArgumentType.getFloat(c, "pixels")))))
                .then(Commands.literal("textures")
                        .executes(FarfieldCommands::showTextures)
                        .then(Commands.literal("all").executes(c -> textures(c, RenderSettings.ALL_DISTANCES)))
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0,
                                        RenderSettings.MAX_TEXTURE_DISTANCE))
                                .executes(c -> textures(c, IntegerArgumentType.getInteger(c, "blocks")))))
                .then(Commands.literal("fog")
                        .executes(FarfieldCommands::showFog)
                        .then(Commands.literal("off").executes(c -> fog(c, false, RenderConfig.FOG_OFF)))
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(RenderConfig.MIN_FOG_DISTANCE,
                                        RenderConfig.MAX_FOG_DISTANCE))
                                .executes(c -> fog(c, false, IntegerArgumentType.getInteger(c, "blocks"))))
                        .then(Commands.literal("thick")
                                .executes(FarfieldCommands::showFog)
                                .then(Commands.literal("vanilla").executes(c -> fog(c, true, RenderConfig.FOG_VANILLA)))
                                .then(Commands.literal("off").executes(c -> fog(c, true, RenderConfig.FOG_OFF)))
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(
                                                RenderConfig.MIN_FOG_DISTANCE, RenderConfig.MAX_FOG_DISTANCE))
                                        .executes(c -> fog(c, true, IntegerArgumentType.getInteger(c, "blocks"))))))
                .then(Commands.literal("spike")
                        .then(Commands.literal("generation")
                                .executes(c -> generationSpike(c, 16))
                                .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                        .executes(c -> generationSpike(c, IntegerArgumentType.getInteger(c, "side"))))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("levels")
                                .executes(c -> debugLevels(c, !RenderSettings.debugLevels()))
                                .then(Commands.literal("on").executes(c -> debugLevels(c, true)))
                                .then(Commands.literal("off").executes(c -> debugLevels(c, false))))));
    }

    /** Single player: the distance up to which LOD faces show their textures (0: off). */
    private static int textures(CommandContext<CommandSourceStack> c, int blocks) {
        RenderSettings.setTextureDistance(blocks);
        RenderSettings.save();
        Farfield.LOG.info("LOD texture distance set to {} by command",
                RenderSettings.textureDistance() == RenderSettings.ALL_DISTANCES ? "all LODs"
                        : RenderSettings.textureDistance() + " blocks");
        return showTextures(c);
    }

    private static int showTextures(CommandContext<CommandSourceStack> c) {
        int blocks = RenderSettings.textureDistance();
        String what = blocks == 0 ? "LOD textures off"
                : blocks == RenderSettings.ALL_DISTANCES ? "LOD textures on every LOD"
                : "LOD textures up to " + blocks + " blocks, fading over the last quarter";
        c.getSource().sendSuccess(() -> Component.literal(what
                + " (default all; /farfield textures all|<blocks>, 0 turns them off; single player only)."), false);
        return 1;
    }

    /** Single player: where the fog becomes opaque, in normal or thick-fog dimensions (ADR-034). */
    private static int fog(CommandContext<CommandSourceStack> c, boolean thick, int blocks) {
        if (thick) {
            RenderSettings.setThickFogDistance(blocks);
        } else {
            RenderSettings.setFogDistance(blocks);
        }
        RenderSettings.save();
        Farfield.LOG.info("LOD {}fog distance set to {} by command", thick ? "thick " : "",
                fogText(thick ? RenderSettings.thickFogDistance() : RenderSettings.fogDistance()));
        return showFog(c);
    }

    private static int showFog(CommandContext<CommandSourceStack> c) {
        String line = "LOD fog: " + fogText(RenderSettings.fogDistance()) + " (thick fog: "
                + fogText(RenderSettings.thickFogDistance()) + "); /farfield fog [thick] off|<"
                + RenderConfig.MIN_FOG_DISTANCE + ".." + RenderConfig.MAX_FOG_DISTANCE
                + ">, /farfield fog thick vanilla; single player only.";
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static String fogText(int blocks) {
        return blocks == RenderConfig.FOG_OFF ? "off" : blocks == RenderConfig.FOG_VANILLA ? "vanilla"
                : blocks + " blocks";
    }

    /** Single player: tints LOD faces by level, to see where each level hands over. */
    private static int debugLevels(CommandContext<CommandSourceStack> c, boolean on) {
        RenderSettings.setDebugLevels(on);
        RenderSettings.save();
        Farfield.LOG.info("LOD level colors switched {} by command", on ? "on" : "off");
        c.getSource().sendSuccess(() -> Component.literal(on
                ? "LOD level colors on: red 1 block, orange 2, yellow 4, green 8, blue 16 (single player only)."
                : "LOD level colors off."), false);
        return 1;
    }

    /** Single player: switches LOD drawing on the client of the same process, to compare frame rates. */
    private static int render(CommandContext<CommandSourceStack> c, boolean on) {
        RenderSettings.set(on);
        RenderSettings.save();
        Farfield.LOG.info("LOD rendering switched {} by command", on ? "on" : "off");
        c.getSource().sendSuccess(() -> Component.literal("LOD rendering " + (on ? "on" : "off")
                + " (single player only: it acts on this game's own client)."), false);
        return 1;
    }

    /** Single player: the largest on-screen size of an LOD voxel before the finer level takes over. */
    private static int detail(CommandContext<CommandSourceStack> c, float pixels) {
        RenderSettings.setVoxelPixels(pixels);
        RenderSettings.save();
        Farfield.LOG.info("LOD detail set to {} px per voxel by command", RenderSettings.voxelPixels());
        c.getSource().sendSuccess(() -> Component.literal("LOD detail: " + RenderSettings.voxelPixels()
                + " px per voxel (lower is finer; default " + RenderSettings.DEFAULT_VOXEL_PIXELS
                + "; single player only)."), false);
        return 1;
    }

    private static int showDetail(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal("LOD detail: " + RenderSettings.voxelPixels()
                + " px per voxel (lower is finer; default " + RenderSettings.DEFAULT_VOXEL_PIXELS + ")."), false);
        return 1;
    }

    /** Spike S6: noise preview and generation costs in this dimension, reported in chat and in the log. */
    private static int generationSpike(CommandContext<CommandSourceStack> c, int side) {
        CommandSourceStack source = c.getSource();
        boolean started = GenerationSpike.start(source.getLevel(), side,
                line -> source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), true)));
        if (!started) {
            source.sendFailure(Component.literal("A generation spike is already running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Generation spike started in "
                + source.getLevel().dimension().location() + ": noise preview, then " + side + "x" + side
                + " chunks up to noise, surface and full. It generates far chunks into this world."), true);
        return 1;
    }

    private static boolean allowed(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        MinecraftServer server = source.getServer();
        return source.getEntity() instanceof ServerPlayer player && server.isSingleplayerOwner(player.getGameProfile());
    }

    private static int start(CommandContext<CommandSourceStack> c, int threads) {
        CommandSourceStack source = c.getSource();
        LodServer server = LodServer.get();
        if (server == null) {
            source.sendFailure(Component.literal("Farfield is not running on this server."));
            return 0;
        }
        boolean started = server.startImport(source.getLevel(), threads,
                line -> source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), true)));
        if (!started) {
            source.sendFailure(Component.literal("An LOD import is already running, or this dimension has no region files."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("LOD import of " + source.getLevel().dimension().location()
                + " started with " + threads + " threads."), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        LodServer server = LodServer.get();
        LodServer.ImportJob job = server == null ? null : server.importJob();
        if (job == null) {
            c.getSource().sendFailure(Component.literal("No LOD import is running."));
            return 0;
        }
        job.importer().cancel();
        c.getSource().sendSuccess(() -> Component.literal("Stopping the LOD import; it resumes from its checkpoint next time."), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        LodServer server = LodServer.get();
        LodServer.ImportJob job = server == null ? null : server.importJob();
        if (job == null) {
            c.getSource().sendSuccess(() -> Component.literal("No LOD import is running."), false);
            return 1;
        }
        var stats = job.importer().stats();
        double seconds = (System.nanoTime() - job.startNanos()) / 1e9;
        String line = String.format(Locale.ROOT, "LOD import of %s: %,d chunks, %,d regions done in %.0f s (%,.0f chunks/s)",
                job.level().name(), stats.chunksImported(), stats.regionsDone(), seconds,
                stats.chunksImported() / Math.max(seconds, 1e-3));
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
