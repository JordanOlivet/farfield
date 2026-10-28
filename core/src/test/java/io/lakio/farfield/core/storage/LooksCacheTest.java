package io.lakio.farfield.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LooksCacheTest {

    private static byte[] tile(int seed) {
        byte[] t = new byte[LooksCache.TILE_BYTES];
        for (int i = 0; i < t.length; i++) {
            t[i] = (byte) (seed * 31 + i);
        }
        return t;
    }

    @Test
    void looksComeBackWithTheirTilesForTheSameFingerprintOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("farfield").resolve("looks.bin");
        long fingerprint = LooksCache.fingerprint(1, List.of("farfield@0.1.0", "minecraft@1.21.1"), List.of("vanilla"));
        LooksCache cache = LooksCache.open(file, fingerprint);
        assertEquals(LooksCache.Status.MISSING, cache.status());
        int[] words = new int[LooksCache.WORDS];
        for (int i = 0; i < words.length; i++) {
            words[i] = i * 7 - 3;
        }
        byte[] grass = tile(1);
        byte[][] tiles = new byte[12][];
        tiles[6] = grass; // the top face's untinted tile
        tiles[7] = tile(2);
        cache.put("minecraft:grass_block[snowy=false]", new LooksCache.Look(true, words, tiles));
        byte[][] stoneTiles = new byte[12][];
        stoneTiles[0] = grass.clone(); // the same pixels: one tile in the file
        cache.put("minecraft:stone", new LooksCache.Look(false, new int[LooksCache.WORDS], stoneTiles));
        cache.save();

        LooksCache read = LooksCache.open(file, fingerprint);
        assertEquals(LooksCache.Status.READ, read.status());
        assertEquals(2, read.size());
        assertEquals(2, read.tilesRead());
        LooksCache.Look grassLook = read.get("minecraft:grass_block[snowy=false]");
        assertTrue(grassLook.tinted());
        assertArrayEquals(words, grassLook.words());
        assertArrayEquals(grass, grassLook.tiles()[6]);
        assertArrayEquals(tile(2), grassLook.tiles()[7]);
        assertNull(grassLook.tiles()[0]);
        assertSame(grassLook.tiles()[6], read.get("minecraft:stone").tiles()[0]);

        // Another mod list or resource pack: baked again from nothing.
        long other = LooksCache.fingerprint(1, List.of("farfield@0.1.1", "minecraft@1.21.1"), List.of("vanilla"));
        LooksCache stale = LooksCache.open(file, other);
        assertEquals(LooksCache.Status.OTHER_FINGERPRINT, stale.status());
        assertEquals(0, stale.size());
        assertTrue(LooksCache.fingerprint(2, List.of("a"), List.of()) != LooksCache.fingerprint(1, List.of("a"), List.of()));

        // A file cut short (a crash while it was moved): baked again.
        byte[] whole = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(whole, 30));
        assertEquals(LooksCache.Status.UNREADABLE, LooksCache.open(file, fingerprint).status());
    }
}
