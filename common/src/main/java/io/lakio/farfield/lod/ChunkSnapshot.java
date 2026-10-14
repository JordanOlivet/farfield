package io.lakio.farfield.lod;

import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.SectionData;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * A copy of what the LOD needs from a loaded chunk, taken on the owner
 * thread of its level, server or client ({@link #take}), in a few
 * microseconds per section (palette and packed-array copies), and converted
 * on a worker ({@link #fill}).
 */
final class ChunkSnapshot {

    private static final int BLOCKS = 4096;

    final int chunkX;
    final int chunkZ;
    private final int minSection;
    private final PalettedContainer<BlockState>[] states;
    private final PalettedContainerRO<Holder<Biome>>[] biomes;
    private final DataLayer[] sky;
    private final DataLayer[] block;
    private final boolean noSkyLight;

    @SuppressWarnings("unchecked")
    private ChunkSnapshot(int chunkX, int chunkZ, int minSection, int count, boolean noSkyLight) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSection = minSection;
        this.states = new PalettedContainer[count];
        this.biomes = new PalettedContainerRO[count];
        this.sky = new DataLayer[count];
        this.block = new DataLayer[count];
        this.noSkyLight = noSkyLight;
    }

    /** Copies a loaded chunk. Owner thread of the level only. */
    static ChunkSnapshot take(Level level, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        boolean noSky = !level.dimensionType().hasSkyLight();
        ChunkSnapshot s = new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, minSection, sections.length, noSky);
        var skyListener = level.getLightEngine().getLayerListener(LightLayer.SKY);
        var blockListener = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            s.states[i] = section.getStates().copy();
            PalettedContainerRO<Holder<Biome>> b = section.getBiomes();
            s.biomes[i] = b instanceof PalettedContainer<Holder<Biome>> container ? container.copy() : b;
            SectionPos pos = SectionPos.of(s.chunkX, minSection + i, s.chunkZ);
            DataLayer skyLayer = noSky ? null : skyListener.getDataLayerData(pos);
            DataLayer blockLayer = blockListener.getDataLayerData(pos);
            s.sky[i] = skyLayer == null ? null : skyLayer.copy();
            s.block[i] = blockLayer == null ? null : blockLayer.copy();
        }
        return s;
    }

    /** Per-worker scratch for {@link #fill}. */
    static final class Scratch {
        final ChunkData chunk = new ChunkData();
        final int[] palette = new int[BLOCKS];
        final BlockState[] paletteStates = new BlockState[BLOCKS];
        final int[] biomePalette = new int[64];
        @SuppressWarnings("unchecked")
        final Holder<Biome>[] biomeHolders = new Holder[64];
        final byte[] dark = new byte[SectionData.LIGHT_BYTES];
    }

    /** Converts to engine ids of {@code level}'s LOD world. Any thread. */
    ChunkData fill(LodLevel level, Scratch scratch) {
        ChunkData chunk = scratch.chunk;
        chunk.beginLive(chunkX, chunkZ);
        for (int i = 0; i < states.length; i++) {
            SectionData section = chunk.addLiveSection(minSection + i);
            fillBlocks(level, states[i], section, scratch);
            fillBiomes(level, biomes[i], section, scratch);
            if (noSkyLight) {
                section.setSkyLight(scratch.dark);
            } else if (sky[i] != null) {
                section.setSkyLight(sky[i].getData());
            }
            if (block[i] != null) {
                section.setBlockLight(block[i].getData());
            }
        }
        return chunk;
    }

    private static void fillBlocks(LodLevel level, PalettedContainer<BlockState> states, SectionData section,
                                   Scratch scratch) {
        int count = 0;
        short[] indices = section.blockIndicesForWrite();
        BlockState last = null;
        int lastIndex = 0;
        int i = 0;
        // Index order (y * 16 + z) * 16 + x, the game's and the engine's.
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = states.get(x, y, z);
                    if (state != last) {
                        int index = 0;
                        while (index < count && scratch.paletteStates[index] != state) {
                            index++;
                        }
                        if (index == count) {
                            scratch.paletteStates[count] = state;
                            scratch.palette[count] = level.blockId(state);
                            count++;
                        }
                        last = state;
                        lastIndex = index;
                    }
                    indices[i++] = (short) lastIndex;
                }
            }
        }
        section.setBlockPalette(scratch.palette, count);
    }

    private static void fillBiomes(LodLevel level, PalettedContainerRO<Holder<Biome>> biomes, SectionData section,
                                   Scratch scratch) {
        int count = 0;
        byte[] indices = section.biomeIndicesForWrite();
        int i = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    Holder<Biome> biome = biomes.get(x, y, z);
                    int index = 0;
                    while (index < count && scratch.biomeHolders[index] != biome) {
                        index++;
                    }
                    if (index == count) {
                        scratch.biomeHolders[count] = biome;
                        scratch.biomePalette[count] = level.biomeId(biome);
                        count++;
                    }
                    indices[i++] = (byte) index;
                }
            }
        }
        section.setBiomePalette(scratch.biomePalette, count);
    }
}
