package io.lakio.farfield.compat;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.ticks.ProtoChunkTicks;
//? if >=1.21.11 {
/*import net.minecraft.world.level.chunk.PalettedContainerFactory;
*///?} else {
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
//?}

/**
 * The block and biome palettes of a level's chunk sections, and the stand-in
 * chunks built from them, across Minecraft versions (ADR-165): from the biome
 * registry up to 1.21.8, from the level's palette factory since.
 */
public final class Palettes {
    //? if >=1.21.11 {
    /*private final PalettedContainerFactory factory;
    *///?} else {
    private final Registry<Biome> biomes;
    private final Holder<Biome> plains;
    //?}

    public Palettes(Level level) {
        //? if >=1.21.11 {
        /*factory = level.palettedContainerFactory();
        *///?} else {
        biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        //?}
    }

    /** An empty section: air, plains. */
    public LevelChunkSection emptySection() {
        //? if >=1.21.11 {
        /*return new LevelChunkSection(factory);
        *///?} else {
        return new LevelChunkSection(biomes);
        //?}
    }

    /** A section's block container, every block {@code fill}. */
    public PalettedContainer<BlockState> blockStates(BlockState fill) {
        //? if >=1.21.11 {
        /*return new PalettedContainer<>(fill, factory.blockStatesStrategy());
        *///?} else {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, fill, PalettedContainer.Strategy.SECTION_STATES);
        //?}
    }

    /** A section's biome container, every cell {@code fill}. */
    public PalettedContainer<Holder<Biome>> biomes(Holder<Biome> fill) {
        //? if >=1.21.11 {
        /*return new PalettedContainer<>(fill, factory.biomeStrategy());
        *///?} else {
        return new PalettedContainer<>(biomes.asHolderIdMap(), fill, PalettedContainer.Strategy.SECTION_BIOMES);
        //?}
    }

    /** The plains biome. */
    public Holder<Biome> plains() {
        //? if >=1.21.11 {
        /*return factory.defaultBiome();
        *///?} else {
        return plains;
        //?}
    }

    /** A proto chunk over {@code sections}, {@code height} tall, no ticks, no blending. */
    public ProtoChunk protoChunk(ChunkPos pos, LevelChunkSection[] sections, LevelHeightAccessor height) {
        //? if >=1.21.11 {
        /*return new ProtoChunk(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                height, factory, null);
        *///?} else {
        return new ProtoChunk(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                height, biomes, null);
        //?}
    }
}
