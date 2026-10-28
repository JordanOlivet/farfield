package io.lakio.farfield.server;

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
 * {@code /farfield import status}: build the LODs of the current dimension
 * from its region files. The drawing settings ({@link RenderCommands}) act
 * in single player only; players elsewhere use {@code /farfieldclient}.
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
                        .then(Commands.literal("status").executes(FarfieldCommands::status)))
                .then(Commands.literal("spike")
                        .then(Commands.literal("generation")
                                .executes(c -> generationSpike(c, 16))
                                .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                        .executes(c -> generationSpike(c, IntegerArgumentType.getInteger(c, "side")))))
                        .then(Commands.literal("tile")
                                .then(Commands.argument("biome", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(FarfieldCommands::tileSpike)))
                        .then(Commands.literal("features")
                                .then(Commands.argument("biome", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(c -> featureSpike(c, 8))
                                        .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                                .executes(c -> featureSpike(c, IntegerArgumentType.getInteger(c, "side")))))));
        dispatcher.register(RenderCommands.addTo(root, "farfield", FarfieldCommands::clientSetting));
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
