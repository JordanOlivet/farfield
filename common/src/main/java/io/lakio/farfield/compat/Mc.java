package io.lakio.farfield.compat;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
//? if >=1.21.11 {
/*import net.minecraft.server.permissions.Permissions;
*///?}

/**
 * Small game calls whose names or parameters differ between Minecraft
 * versions (ADR-165). Plain renames are Stonecutter replacements
 * (stonecutter.gradle); what changes shape comes here, so the callers stay
 * free of version comments.
 */
public final class Mc {
    private Mc() {
    }

    /** A chunk position's X, in chunks: a field up to 1.21.11, a record component since 26.1. */
    public static int chunkX(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.x();
        *///?} else {
        return pos.x;
        //?}
    }

    /** A chunk position's Z, in chunks. */
    public static int chunkZ(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.z();
        *///?} else {
        return pos.z;
        //?}
    }

    /** The level's world spawn point. */
    public static BlockPos spawnPos(ServerLevel level) {
        //? if >=1.21.11 {
        /*return level.getRespawnData().pos();
        *///?} else {
        return level.getSharedSpawnPos();
        //?}
    }

    /** The player's account name. */
    public static String playerName(ServerPlayer player) {
        //? if >=1.21.11 {
        /*return player.getGameProfile().name();
        *///?} else {
        return player.getGameProfile().getName();
        //?}
    }

    /** True when the player is the owner of a single player (or LAN) game. */
    public static boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
        //? if >=1.21.11 {
        /*return server.isSingleplayerOwner(player.nameAndId());
        *///?} else {
        return server.isSingleplayerOwner(player.getGameProfile());
        //?}
    }

    /** The server the player is on. */
    public static MinecraftServer server(ServerPlayer player) {
        //? if >=1.21.11 {
        /*return player.level().getServer();
        *///?} else {
        return player.server;
        //?}
    }

    /** True when the command source may run the game master commands (level 2: /tp, /gamemode). */
    public static boolean isGameMaster(CommandSourceStack source) {
        //? if >=1.21.11 {
        /*return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        *///?} else {
        return source.hasPermission(2);
        //?}
    }

    /** True when it snows at {@code pos} in the biome, given the level's sea level (used since 1.21.2). */
    public static boolean coldEnoughToSnow(Biome biome, BlockPos pos, int seaLevel) {
        //? if >=1.21.2 {
        /*return biome.coldEnoughToSnow(pos, seaLevel);
        *///?} else {
        return biome.coldEnoughToSnow(pos);
        //?}
    }

    /** True when it rains at {@code pos} in the biome, given the level's sea level (used since 1.21.2). */
    public static boolean warmEnoughToRain(Biome biome, BlockPos pos, int seaLevel) {
        //? if >=1.21.2 {
        /*return biome.warmEnoughToRain(pos, seaLevel);
        *///?} else {
        return biome.warmEnoughToRain(pos);
        //?}
    }

    /** True when the frozen ocean's icebergs melt slightly at {@code pos}, given the sea level (since 1.21.2). */
    public static boolean meltsIcebergsSlightly(Biome biome, BlockPos pos, int seaLevel) {
        //? if >=1.21.2 {
        /*return biome.shouldMeltFrozenOceanIcebergSlightly(pos, seaLevel);
        *///?} else {
        return biome.shouldMeltFrozenOceanIcebergSlightly(pos);
        //?}
    }

    /** The registry's entry of that id, or null. */
    public static <T> T value(Registry<T> registry, ResourceLocation id) {
        //? if >=1.21.2 {
        /*return registry.getValue(id);
        *///?} else {
        return registry.get(id);
        //?}
    }

    /** A beardifier with no structure pieces: the terrain's density unchanged. */
    public static net.minecraft.world.level.levelgen.Beardifier emptyBeardifier() {
        //? if >=1.21.11 {
        /*return new net.minecraft.world.level.levelgen.Beardifier(java.util.List.of(), java.util.List.of(), null);
        *///?} else {
        return new net.minecraft.world.level.levelgen.Beardifier(
                it.unimi.dsi.fastutil.objects.ObjectLists.<net.minecraft.world.level.levelgen.Beardifier.Rigid>emptyList()
                        .iterator(),
                it.unimi.dsi.fastutil.objects.ObjectLists
                        .<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>emptyList().iterator());
        //?}
    }

    /** The registry's entry of that key, or null. */
    public static <T> T value(Registry<T> registry, net.minecraft.resources.ResourceKey<T> key) {
        //? if >=1.21.2 {
        /*return registry.getValue(key);
        *///?} else {
        return registry.get(key);
        //?}
    }

    /** The block registry as a holder lookup, for the block state parser. */
    public static HolderLookup<Block> blockLookup() {
        //? if >=1.21.2 {
        /*return BuiltInRegistries.BLOCK;
        *///?} else {
        return BuiltInRegistries.BLOCK.asLookup();
        //?}
    }
}
