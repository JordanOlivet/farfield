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

    /** A resource id: a constructor before 1.21, a factory since. */
    public static ResourceLocation id(String namespace, String path) {
        //? if >=1.21 {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
        //?} else {
        /*return new ResourceLocation(namespace, path);
        *///?}
    }

    /**
     * Whether a chunk future's result holds a chunk: a {@code ChunkResult} since 1.20.5, an
     * {@code Either} with the chunk on its left before.
     */
    public static boolean chunkLoaded(Object result) {
        //? if >=1.20.5 {
        return ((net.minecraft.server.level.ChunkResult<?>) result).isSuccess();
        //?} else {
        /*return ((com.mojang.datafixers.util.Either<?, ?>) result).left().isPresent();
        *///?}
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

    /** The biome a biome source places at quart (x, y, z): since 26.3 through a resolver over the sampler. */
    public static net.minecraft.core.Holder<Biome> noiseBiome(net.minecraft.world.level.biome.BiomeSource source,
                                                              int x, int y, int z,
                                                              net.minecraft.world.level.biome.Climate.Sampler sampler) {
        //? if >=26.3 {
        /*return source.createResolver(sampler).getNoiseBiome(x, y, z);
        *///?} else {
        return source.getNoiseBiome(x, y, z, sampler);
        //?}
    }

    /** The height of the noise's cells: a setting up to 26.2, 8 blocks in 26.3's interpolation. */
    public static int cellHeight(net.minecraft.world.level.levelgen.NoiseSettings noise) {
        //? if >=26.3 {
        /*return 8;
        *///?} else {
        return noise.getCellHeight();
        //?}
    }

    /** The status that builds a chunk's surface: {@code SURFACE}, since 26.3 part of {@code TERRAIN}. */
    public static final net.minecraft.world.level.chunk.status.ChunkStatus SURFACE_STATUS =
            //? if >=26.3 {
            /*net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN;
            *///?} else {
            net.minecraft.world.level.chunk.status.ChunkStatus.SURFACE;
            //?}

    /** The world's climate sampler: since 26.3 one is made per use, over a sampler context (ADR-171). */
    public static net.minecraft.world.level.biome.Climate.Sampler climateSampler(
            net.minecraft.world.level.levelgen.RandomState state) {
        //? if >=26.3 {
        /*return state.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
        *///?} else {
        return state.sampler();
        //?}
    }

    /**
     * The noise router the world's densities come from: the world's wired one
     * up to 26.2; since 26.3 the world keeps none and its sampler compiles the
     * settings' own (ADR-171).
     */
    public static net.minecraft.world.level.levelgen.NoiseRouter noiseRouter(
            net.minecraft.world.level.levelgen.RandomState state,
            net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator) {
        //? if >=26.3 {
        /*return generator.generatorSettings().value().noiseRouter();
        *///?} else {
        return state.router();
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
