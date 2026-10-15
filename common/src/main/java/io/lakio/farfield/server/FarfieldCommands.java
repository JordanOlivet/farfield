package io.lakio.farfield.server;

import com.mojang.brigadier.CommandDispatcher;
import io.lakio.farfield.Farfield;
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
 * in single player. Operators, or the owner of a single-player world
 * (cheats off included).
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
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0,
                                        RenderSettings.MAX_TEXTURE_DISTANCE))
                                .executes(c -> textures(c, IntegerArgumentType.getInteger(c, "blocks")))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("levels")
                                .executes(c -> debugLevels(c, !RenderSettings.debugLevels()))
                                .then(Commands.literal("on").executes(c -> debugLevels(c, true)))
                                .then(Commands.literal("off").executes(c -> debugLevels(c, false))))));
    }

    /** Single player: the distance up to which LOD faces show their textures (0: off). */
    private static int textures(CommandContext<CommandSourceStack> c, int blocks) {
        RenderSettings.setTextureDistance(blocks);
        Farfield.LOG.info("LOD texture distance set to {} blocks by command", RenderSettings.textureDistance());
        return showTextures(c);
    }

    private static int showTextures(CommandContext<CommandSourceStack> c) {
        int blocks = RenderSettings.textureDistance();
        c.getSource().sendSuccess(() -> Component.literal(blocks == 0 ? "LOD textures off."
                : "LOD textures up to " + blocks + " blocks, fading over the last quarter (default "
                + RenderSettings.DEFAULT_TEXTURE_DISTANCE + "; 0 turns them off; single player only)."), false);
        return 1;
    }

    /** Single player: tints LOD faces by level, to see where each level hands over. */
    private static int debugLevels(CommandContext<CommandSourceStack> c, boolean on) {
        RenderSettings.setDebugLevels(on);
        Farfield.LOG.info("LOD level colors switched {} by command", on ? "on" : "off");
        c.getSource().sendSuccess(() -> Component.literal(on
                ? "LOD level colors on: red 1 block, orange 2, yellow 4, green 8, blue 16 (single player only)."
                : "LOD level colors off."), false);
        return 1;
    }

    /** Single player: switches LOD drawing on the client of the same process, to compare frame rates. */
    private static int render(CommandContext<CommandSourceStack> c, boolean on) {
        RenderSettings.set(on);
        Farfield.LOG.info("LOD rendering switched {} by command", on ? "on" : "off");
        c.getSource().sendSuccess(() -> Component.literal("LOD rendering " + (on ? "on" : "off")
                + " (single player only: it acts on this game's own client)."), false);
        return 1;
    }

    /** Single player: the largest on-screen size of an LOD voxel before the finer level takes over. */
    private static int detail(CommandContext<CommandSourceStack> c, float pixels) {
        RenderSettings.setVoxelPixels(pixels);
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
