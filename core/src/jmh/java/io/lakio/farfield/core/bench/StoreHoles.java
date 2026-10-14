package io.lakio.farfield.core.bench;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.ChunkDecoder;
import io.lakio.farfield.core.anvil.ChunkDecompressor;
import io.lakio.farfield.core.anvil.RegionFile;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.util.GrowableBytes;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.core.world.MaterialTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Diagnostic: lists the full chunks of a world's region files whose
 * level-0 LOD octant holds no voxel at all (a hole in the LODs).
 *
 * <pre>
 *   gradlew :core:storeHoles --args="&lt;dir with region/ and farfield/&gt;"
 * </pre>
 *
 * Opens the store read-mostly: run it on a copy (opening drops the index
 * snapshot of the files it touches).
 */
public final class StoreHoles {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        List<Path> regions;
        try (Stream<Path> s = Files.list(dir.resolve("region"))) {
            regions = s.filter(p -> p.toString().endsWith(".mca")).sorted().toList();
        }
        int full = 0;
        List<String> holes = new ArrayList<>();
        try (LodWorld world = LodWorld.open(dir.resolve("farfield"), MaterialTable.SIMPLE, 1)) {
            int air = world.air();
            ChunkDecoder decoder = new ChunkDecoder(world.blockStates(), world.biomes());
            ChunkData chunk = new ChunkData();
            GrowableBytes nbt = new GrowableBytes(1 << 20);
            RegionFile.RawChunk raw = new RegionFile.RawChunk();
            try (ChunkDecompressor decompressor = new ChunkDecompressor()) {
                for (Path r : regions) {
                    try (RegionFile file = RegionFile.open(r)) {
                        for (int i = 0; i < RegionFile.CHUNK_COUNT; i++) {
                            if (!file.read(i, raw)) {
                                continue;
                            }
                            decompressor.decompress(raw.compression, raw.bytes.data(), raw.offset, raw.length, nbt);
                            int cx = file.chunkX(i);
                            int cz = file.chunkZ(i);
                            if (!decoder.decodeHeader(nbt.data(), 0, nbt.length(), cx, cz, chunk) || !chunk.isFull()) {
                                continue;
                            }
                            full++;
                            if (!hasVoxels(world, air, cx, cz)) {
                                holes.add(cx + "," + cz + " (blocks " + cx * 16 + ".." + (cx * 16 + 15) + ", "
                                        + cz * 16 + ".." + (cz * 16 + 15) + ") in " + r.getFileName());
                            }
                        }
                    }
                }
            }
        }
        System.out.println(full + " full chunks, " + holes.size() + " without any LOD voxel");
        holes.stream().limit(60).forEach(h -> System.out.println("  " + h));
    }

    private static boolean hasVoxels(LodWorld world, int air, int cx, int cz) throws Exception {
        int ox = (cx & 1) * 16;
        int oz = (cz & 1) * 16;
        for (int ly = -3; ly <= 10; ly++) {
            LodSection s = world.read(LodKey.pack(0, cx >> 1, ly, cz >> 1));
            if (s == null) {
                continue;
            }
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (s.material(ox + x, y, oz + z) != air) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
