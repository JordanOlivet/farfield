package io.lakio.farfield.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LooksCacheTest {

    private static final long FINGERPRINT =
            LooksCache.fingerprint(1, List.of("farfield@0.1.0", "minecraft@1.21.1"), List.of("vanilla"));

    private static byte[] tile(int seed) {
        byte[] t = new byte[LooksCache.TILE_BYTES];
        for (int i = 0; i < t.length; i++) {
            t[i] = (byte) (seed * 31 + i);
        }
        return t;
    }

    private static LooksCache.Look look(int seed, byte[] top) {
        int[] words = new int[LooksCache.WORDS];
        for (int i = 0; i < words.length; i++) {
            words[i] = seed * 100 + i;
        }
        byte[][] tiles = new byte[12][];
        tiles[6] = top;
        return new LooksCache.Look(seed % 2 == 0, words, tiles);
    }

    @Test
    void looksAreAppendedAndComeBackForTheSameFingerprintOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("farfield").resolve("looks.bin");
        LooksCache cache = LooksCache.open(file, FINGERPRINT);
        assertEquals(LooksCache.Status.MISSING, cache.status());
        byte[] grass = tile(1);
        cache.put("minecraft:grass_block[snowy=false]", look(2, grass));
        assertTrue(cache.hasPending());
        cache.save();
        assertFalse(cache.hasPending());
        long first = Files.size(file);
        // A second look sharing the tile: appended, the tile not written again.
        cache.put("minecraft:stone", look(3, grass.clone()));
        cache.save();
        long second = Files.size(file);
        assertTrue(second - first < LooksCache.TILE_BYTES, (second - first) + " bytes appended");

        LooksCache read = LooksCache.open(file, FINGERPRINT);
        assertEquals(LooksCache.Status.READ, read.status());
        assertEquals(2, read.size());
        assertEquals(1, read.tilesRead());
        LooksCache.Look grassLook = read.get("minecraft:grass_block[snowy=false]");
        assertTrue(grassLook.tinted());
        assertArrayEquals(look(2, grass).words(), grassLook.words());
        assertArrayEquals(grass, grassLook.tiles()[6]);
        assertNull(grassLook.tiles()[0]);
        assertSame(grassLook.tiles()[6], read.get("minecraft:stone").tiles()[6]);

        // Appending to the file read back keeps what was there.
        read.put("minecraft:dirt", look(4, tile(5)));
        read.save();
        LooksCache again = LooksCache.open(file, FINGERPRINT);
        assertEquals(3, again.size());
        assertEquals(2, again.tilesRead());

        // Another mod list or resource pack: empty, and the file starts over at the next append.
        long other = LooksCache.fingerprint(1, List.of("farfield@0.1.1", "minecraft@1.21.1"), List.of("vanilla"));
        LooksCache stale = LooksCache.open(file, other);
        assertEquals(LooksCache.Status.OTHER_FINGERPRINT, stale.status());
        assertEquals(0, stale.size());
        stale.put("minecraft:sand", look(6, tile(7)));
        stale.save();
        assertEquals(1, LooksCache.open(file, other).size());
        assertEquals(LooksCache.Status.OTHER_FINGERPRINT, LooksCache.open(file, FINGERPRINT).status());
        assertTrue(LooksCache.fingerprint(2, List.of("a"), List.of()) != LooksCache.fingerprint(1, List.of("a"), List.of()));
    }

    @Test
    void aTailCutShortIsDroppedAndAppendedOver(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("looks.bin");
        LooksCache cache = LooksCache.open(file, FINGERPRINT);
        cache.put("a", look(1, tile(1)));
        cache.put("b", look(2, tile(2)));
        cache.save();
        // A crash in the middle of the last look's record.
        byte[] whole = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(whole, whole.length - 10));
        LooksCache cut = LooksCache.open(file, FINGERPRINT);
        assertEquals(LooksCache.Status.READ, cut.status());
        assertEquals(1, cut.size());
        assertEquals(1, cut.recordsDropped());
        cut.put("c", look(3, tile(3)));
        cut.save();
        LooksCache healed = LooksCache.open(file, FINGERPRINT);
        assertEquals(0, healed.recordsDropped());
        assertEquals(2, healed.size());
        assertTrue(healed.get("c") != null && healed.get("a") != null);

        Files.writeString(file, "not a cache, longer than its header");
        assertEquals(LooksCache.Status.UNREADABLE, LooksCache.open(file, FINGERPRINT).status());
    }
}
