package io.lakio.farfield.server;

import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import java.util.List;
import com.mojang.brigadier.CommandDispatcher;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.lod.RenderCommands;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /farfield import [threads]}, {@code /farfield import stop},
 * {@code /farfield import pause|resume}, {@code /farfield import status}:
 * build the LODs of the current dimension from its region files (a
 * dedicated server also does it at start for the regions its LODs never
 * saw, ADR-124). The drawing settings ({@link RenderCommands}) act
 * in single player only; players elsewhere use {@code /farfieldclient}.
 * {@code /farfield pregen start|spawn|pause|resume|cancel|list}: the
 * preview built ahead within a radius, while the server is free (ADR-123).
 * {@code /farfield spike generation [side]}: measures the
 * cost of showing terrain not generated yet ({@link GenerationSpike}; test
 * worlds only). Operators, or the owner of a single-player world (cheats off
 * included).
 */
public final class FarfieldCommands {

    private FarfieldCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("farfield")
                .requires(FarfieldCommands::allowed)
                .then(Commands.literal("import")
                        .executes(c -> start(c, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)))
                        .then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "threads"))))
                        .then(Commands.literal("stop").executes(FarfieldCommands::stop))
                        .then(Commands.literal("pause").executes(c -> importHold(c, true)))
                        .then(Commands.literal("resume").executes(c -> importHold(c, false)))
                        .then(Commands.literal("status").executes(FarfieldCommands::status)))
                .then(Commands.literal("rebuild")
                        .executes(FarfieldCommands::rebuild)
                        .then(Commands.literal("status").executes(FarfieldCommands::rebuildStatus))
                        .then(Commands.literal("cancel").executes(FarfieldCommands::rebuildCancel)))
                .then(Commands.literal("pregen")
                        .then(Commands.literal("start")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(0,
                                                                io.lakio.farfield.core.preview.PregenTasks.MAX_RADIUS))
                                                        .executes(c -> pregenStart(c, null))
                                                        .then(Commands.argument("dimension",
                                                                        net.minecraft.commands.arguments.DimensionArgument.dimension())
                                                                .executes(c -> pregenStart(c,
                                                                        net.minecraft.commands.arguments.DimensionArgument
                                                                                .getDimension(c, "dimension"))))))))
                        .then(Commands.literal("spawn")
                                .executes(c -> pregenSpawn(c, -1))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0,
                                                io.lakio.farfield.core.preview.PregenTasks.MAX_RADIUS))
                                        .executes(c -> pregenSpawn(c, IntegerArgumentType.getInteger(c, "radius")))))
                        .then(pregenSet("pause", io.lakio.farfield.core.preview.PregenTasks.State.PAUSED))
                        .then(pregenSet("resume", io.lakio.farfield.core.preview.PregenTasks.State.RUNNING))
                        .then(pregenSet("cancel", io.lakio.farfield.core.preview.PregenTasks.State.CANCELLED))
                        .then(Commands.literal("list").executes(FarfieldCommands::pregenList)))
                .then(Commands.literal("spike")
                        .then(Commands.literal("generation")
                                .executes(c -> generationSpike(c, 16))
                                .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                        .executes(c -> generationSpike(c, IntegerArgumentType.getInteger(c, "side")))))
                        .then(Commands.literal("tile")
                                .then(Commands.literal("at")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(FarfieldCommands::tileSpikeAt))))
                                .then(Commands.argument("biome", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(FarfieldCommands::tileSpike)))
                        .then(Commands.literal("surface")
                                .then(Commands.literal("at")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(FarfieldCommands::surfaceSpikeAt))))
                                .then(Commands.argument("biome", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(c -> surfaceSpike(c, 8))
                                        .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                                .executes(c -> surfaceSpike(c, IntegerArgumentType.getInteger(c, "side"))))))
                        .then(Commands.literal("heights")
                                .then(Commands.literal("at")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(FarfieldCommands::heightsSpikeAt)))))
                        .then(Commands.literal("features")
                                .then(Commands.argument("biome", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(c -> featureSpike(c, 8))
                                        .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                                .executes(c -> featureSpike(c, IntegerArgumentType.getInteger(c, "side")))))));
        dispatcher.register(RenderCommands.addTo(root, "farfield", FarfieldCommands::clientSetting));
    }

    /** The pregeneration tasks, or a failure reply when there are none here. */
    private static PreviewPregen pregenOrFail(CommandSourceStack source) {
        PreviewPregen p = LodServer.pregen();
        if (p == null) {
            source.sendFailure(Component.literal(LodServer.get() == null ? "Farfield's server side is not running."
                    : "No preview here (preview=false in farfield-server.properties), or the server is still starting."));
        }
        return p;
    }

    private static int pregenStart(CommandContext<CommandSourceStack> c,
                                   net.minecraft.server.level.ServerLevel level) {
        CommandSourceStack source = c.getSource();
        PreviewPregen p = pregenOrFail(source);
        if (p == null) {
            return 0;
        }
        net.minecraft.server.level.ServerLevel in = level != null ? level : source.getLevel();
        String line = p.start(null, in.dimension().location().toString(), IntegerArgumentType.getInteger(c, "x"),
                IntegerArgumentType.getInteger(c, "z"), IntegerArgumentType.getInteger(c, "radius"));
        source.sendSuccess(() -> Component.literal(line), true);
        return 1;
    }

    /** The world's spawn, with the given radius or the setting's (4,096 when the setting is off). */
    private static int pregenSpawn(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack source = c.getSource();
        PreviewPregen p = pregenOrFail(source);
        if (p == null) {
            return 0;
        }
        int r = radius >= 0 ? radius : StreamServer.settings().previewSpawnRadius() > 0
                ? StreamServer.settings().previewSpawnRadius() : 4096;
        net.minecraft.core.BlockPos spawn = source.getServer().overworld().getSharedSpawnPos();
        String line = p.start(io.lakio.farfield.core.preview.PregenTasks.SPAWN, "minecraft:overworld", spawn.getX(),
                spawn.getZ(), r);
        source.sendSuccess(() -> Component.literal(line), true);
        return 1;
    }

    /** {@code pause|resume|cancel [id|all]}: one task, or every open one. */
    private static LiteralArgumentBuilder<CommandSourceStack> pregenSet(String verb,
                                                                       io.lakio.farfield.core.preview.PregenTasks.State state) {
        return Commands.literal(verb)
                .executes(c -> pregenSet(c, null, state))
                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(c -> {
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id");
                            return pregenSet(c, id.equalsIgnoreCase("all") ? null : id, state);
                        }));
    }

    private static int pregenSet(CommandContext<CommandSourceStack> c, String id,
                                 io.lakio.farfield.core.preview.PregenTasks.State state) {
        CommandSourceStack source = c.getSource();
        PreviewPregen p = pregenOrFail(source);
        if (p == null) {
            return 0;
        }
        String line = p.set(id, state);
        if (line == null) {
            source.sendFailure(Component.literal(id == null ? "No open preview pregeneration."
                    : "No open preview pregeneration " + id + " (/farfield pregen list)."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(line), true);
        return 1;
    }

    private static int pregenList(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        PreviewPregen p = pregenOrFail(source);
        if (p == null) {
            return 0;
        }
        List<String> lines = p.list(LodServer::previewBuilt);
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No preview pregeneration."), false);
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /**
     * The drawing settings act on the client of this process: in single
     * player, for its owner. Elsewhere they belong to each player's client.
     */
    private static int clientSetting(CommandSourceStack source, Supplier<String> change) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !source.getServer().isSingleplayerOwner(player.getGameProfile())) {
            source.sendFailure(Component.literal("LOD drawing settings belong to each player's game: use "
                    + "/farfieldclient instead of /farfield (same arguments)."));
            return 0;
        }
        String line = change.get();
        source.sendSuccess(() -> Component.literal(line), false);
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

    /** Spike for ADR-079: the world's features in a stand-in, against the same chunks for real. */
    private static int featureSpike(CommandContext<CommandSourceStack> c, int side) {
        CommandSourceStack source = c.getSource();
        var biome = net.minecraft.commands.arguments.ResourceLocationArgument.getId(c, "biome");
        boolean started = FeatureSpike.start(source.getLevel(), biome, side,
                line -> source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), true)));
        if (!started) {
            source.sendFailure(Component.literal("A feature spike is already running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Feature spike started near " + biome + ", " + side + "x" + side
                + " chunks. It generates far chunks into this world."), true);
        return 1;
    }

    /** Spike for ADR-101: the surface rules on a stand-in and the guessed tops, against the real ones. */
    private static int surfaceSpike(CommandContext<CommandSourceStack> c, int side) {
        CommandSourceStack source = c.getSource();
        var biome = net.minecraft.commands.arguments.ResourceLocationArgument.getId(c, "biome");
        boolean started = SurfaceSpike.start(source.getLevel(), biome, side,
                line -> source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), true)));
        if (!started) {
            source.sendFailure(Component.literal("A surface spike is already running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Surface spike started near " + biome + ", " + side + "x" + side
                + " chunks. It generates far chunks into this world."), true);
        return 1;
    }

    /** The preview tile over a position, built now (ADR-079's tile spike). */
    private static int tileSpikeAt(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        var at = new net.minecraft.core.BlockPos(IntegerArgumentType.getInteger(c, "x"), 0,
                IntegerArgumentType.getInteger(c, "z"));
        boolean started = FeatureSpike.startTile(source.getLevel(), null, at,
                line -> source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), true)));
        if (!started) {
            source.sendFailure(Component.literal("A feature spike is already running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Preview tile spike started at " + at.getX() + " " + at.getZ()), true);
        return 1;
    }

    /**
     * Spike for the coarse-to-fine study: the time of the heights of a tile's
     * grid around a position, at the spacings of several tile levels.
     */
    private static int heightsSpikeAt(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        int x = IntegerArgumentType.getInteger(c, "x");
        int z = IntegerArgumentType.getInteger(c, "z");
        var level = source.getLevel();
        Thread t = new Thread(() -> {
            java.util.function.Consumer<String> report = line -> {
                Farfield.LOG.info("Heights spike: {}", line);
                source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), true));
            };
            try {
                report.accept(LodServer.previewHeightsSpike(level, x, z, report) ? "done"
                        : "no preview in " + level.dimension().location());
            } catch (RuntimeException e) {
                Farfield.LOG.warn("Heights spike failed", e);
                report.accept("failed: " + e);
            }
        }, "farfield-heights-spike");
        t.setDaemon(true);
        t.start();
        source.sendSuccess(() -> Component.literal("Heights spike started at " + x + " " + z), true);
        return 1;
    }

    /** The surface spike over the 4 x 4 chunks around a position, with the real and ruled tops as maps. */
    private static int surfaceSpikeAt(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        var at = new net.minecraft.core.BlockPos(IntegerArgumentType.getInteger(c, "x"), 0,
                IntegerArgumentType.getInteger(c, "z"));
        boolean started = SurfaceSpike.start(source.getLevel(), null, at, 4,
                line -> source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), true)));
        if (!started) {
            source.sendFailure(Component.literal("A surface spike is already running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Surface spike started around " + at.getX() + " " + at.getZ()), true);
        return 1;
    }

    /** Spike for ADR-079: one preview tile with the world's features, built now over a biome. */
    private static int tileSpike(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        var biome = net.minecraft.commands.arguments.ResourceLocationArgument.getId(c, "biome");
        boolean started = FeatureSpike.startTile(source.getLevel(), biome,
                line -> source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), true)));
        if (!started) {
            source.sendFailure(Component.literal("A feature spike is already running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Preview tile spike started over " + biome), true);
        return 1;
    }

    /**
     * Rebuilds the coarse levels of the stored LODs from their finest level
     * (ADR-082), in the background: those of the player's dimension, of every
     * dimension from the console. For a change of the rules that build them;
     * clients get the new sections by themselves.
     */
    private static int rebuild(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        ResourceKey<Level> dimension = source.getEntity() instanceof ServerPlayer p ? p.level().dimension() : null;
        Thread t = new Thread(() -> {
            List<String> lines;
            try {
                lines = LodServer.rebuildCoarseLevels(dimension);
            } catch (java.io.IOException | RuntimeException e) {
                Farfield.LOG.warn("LOD rebuild failed", e);
                source.getServer().execute(() -> source.sendFailure(Component.literal("LOD rebuild failed: " + e)));
                return;
            }
            source.getServer().execute(() -> {
                if (lines.isEmpty()) {
                    source.sendFailure(Component.literal("No LOD store is open"
                            + (dimension == null ? "." : " in " + dimension.location() + ".")));
                    return;
                }
                for (String line : lines) {
                    Farfield.LOG.info("LOD rebuild of {}", line);
                    source.sendSuccess(() -> Component.literal("LOD rebuild of " + line), true);
                }
                source.sendSuccess(() -> Component.literal("The coarse levels are built again in the background;"
                        + " /farfield rebuild status shows what is left."), false);
            });
        }, "farfield-rebuild");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** Stops the rebuild of the player's dimension, of every dimension from the console. */
    private static int rebuildCancel(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        ResourceKey<Level> dimension = source.getEntity() instanceof ServerPlayer p ? p.level().dimension() : null;
        List<String> lines = LodServer.cancelRebuild(dimension);
        if (lines.isEmpty()) {
            source.sendFailure(Component.literal("No LOD store is open"
                    + (dimension == null ? "." : " in " + dimension.location() + ".")));
            return 0;
        }
        for (String line : lines) {
            Farfield.LOG.info("LOD rebuild of {}", line);
            source.sendSuccess(() -> Component.literal("LOD store of " + line), true);
        }
        return 1;
    }

    private static int rebuildStatus(CommandContext<CommandSourceStack> c) {
        List<String> lines = LodServer.coarseLevelsPending();
        if (lines.isEmpty()) {
            c.getSource().sendFailure(Component.literal("No LOD store is open."));
            return 0;
        }
        for (String line : lines) {
            c.getSource().sendSuccess(() -> Component.literal("LOD store of " + line), false);
        }
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

    /** {@code import pause}: holds the import; {@code import resume}: runs it, players online or not. */
    private static int importHold(CommandContext<CommandSourceStack> c, boolean pause) {
        LodServer server = LodServer.get();
        boolean done = server != null && (pause ? server.pauseImport() : server.resumeImport());
        if (!done) {
            c.getSource().sendFailure(Component.literal("No LOD import is running."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal(pause ? "LOD import paused until /farfield import resume."
                : "LOD import resumed, even with players online, until it ends."), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        LodServer server = LodServer.get();
        LodServer.ImportJob job = server == null ? null : server.importJob();
        List<String> queued = server == null ? List.of() : server.importQueue();
        if (!queued.isEmpty()) {
            String waiting = "LOD import at start, waiting: " + String.join(", ", queued);
            c.getSource().sendSuccess(() -> Component.literal(waiting), false);
        }
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
