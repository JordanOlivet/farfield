package io.lakio.farfield.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.lakio.farfield.core.world.RemoteWorldNames;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;

/**
 * Client commands (ADR-035), run by the client whatever the server:
 * {@code /farfieldclient world} shows the identity of the current remote
 * world, {@code /farfieldclient world name <name>} names it (its LODs move
 * along when the name is new), {@code /farfieldclient world reset} goes back
 * to the automatic identity. A root of its own, so it never clashes with a
 * server's {@code /farfield}. Each loader registers it with its client
 * command source type.
 */
public final class FarfieldClientCommands {

    private FarfieldClientCommands() {
    }

    /**
     * @param reply sends a line to the player through the loader's source
     */
    public static <S> void register(CommandDispatcher<S> dispatcher, BiConsumer<S, Component> reply) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal("farfieldclient")
                .then(LiteralArgumentBuilder.<S>literal("world")
                        .executes(c -> {
                            reply.accept(c.getSource(), Component.literal(LodClient.describe(Minecraft.getInstance())));
                            return 1;
                        })
                        .then(LiteralArgumentBuilder.<S>literal("reset").executes(c -> {
                            reply.accept(c.getSource(), Component.literal(LodClient.rename(Minecraft.getInstance(), null)));
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<S>literal("name")
                                .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            String name = RemoteWorldNames.sanitize(StringArgumentType.getString(c, "name"));
                                            String line = name == null
                                                    ? "Use letters, digits, '.', '-' or '_' for the name."
                                                    : LodClient.rename(Minecraft.getInstance(), name);
                                            reply.accept(c.getSource(), Component.literal(line));
                                            return name == null ? 0 : 1;
                                        })))));
    }
}
