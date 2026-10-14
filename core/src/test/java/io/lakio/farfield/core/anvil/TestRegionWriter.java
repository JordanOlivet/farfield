package io.lakio.farfield.core.anvil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Writes region files for tests, in the game's layout. */
public final class TestRegionWriter {

    private final int regionX;
    private final int regionZ;
    private final int[] locations = new int[RegionFile.CHUNK_COUNT];
    private final int[] timestamps = new int[RegionFile.CHUNK_COUNT];
    private final ByteArrayOutputStream sectors = new ByteArrayOutputStream();
    private final Map<String, byte[]> externals = new HashMap<>();
    private int nextSector = 2;

    public TestRegionWriter(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    /** Adds a chunk at local coordinates with an already compressed payload. */
    public TestRegionWriter put(int localX, int localZ, int compression, byte[] payload) {
        return put(localX, localZ, compression, payload, false);
    }

    public TestRegionWriter put(int localX, int localZ, int compression, byte[] payload, boolean external) {
        int index = localX + localZ * 32;
        byte[] body = external ? new byte[0] : payload;
        ByteBuffer chunk = ByteBuffer.allocate(5 + body.length);
        chunk.putInt(body.length + 1);
        chunk.put((byte) (external ? compression | RegionFile.EXTERNAL_FLAG : compression));
        chunk.put(body);
        int count = (chunk.capacity() + RegionFile.SECTOR_BYTES - 1) / RegionFile.SECTOR_BYTES;
        byte[] padded = new byte[count * RegionFile.SECTOR_BYTES];
        System.arraycopy(chunk.array(), 0, padded, 0, chunk.capacity());
        sectors.writeBytes(padded);
        locations[index] = nextSector << 8 | count;
        timestamps[index] = 1_700_000_000 + index;
        nextSector += count;
        if (external) {
            int chunkX = regionX * 32 + localX;
            int chunkZ = regionZ * 32 + localZ;
            externals.put("c." + chunkX + "." + chunkZ + ".mcc", payload);
        }
        return this;
    }

    /** Puts a raw location entry (for corrupt-header tests). */
    public TestRegionWriter rawLocation(int localX, int localZ, int location) {
        locations[localX + localZ * 32] = location;
        return this;
    }

    public Path write(Path dir) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(2 * RegionFile.SECTOR_BYTES);
        for (int l : locations) {
            header.putInt(l);
        }
        for (int t : timestamps) {
            header.putInt(t);
        }
        Path file = dir.resolve("r." + regionX + "." + regionZ + ".mca");
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(header.array());
        all.writeBytes(sectors.toByteArray());
        Files.write(file, all.toByteArray());
        for (var e : externals.entrySet()) {
            Files.write(dir.resolve(e.getKey()), e.getValue());
        }
        return file;
    }
}
