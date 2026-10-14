package io.lakio.farfield.core.anvil;

import io.lakio.farfield.core.util.GrowableBytes;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks our LZ4 and XXH32 code against the lz4-java library the game uses. */
class Lz4BlockStreamTest {

    @Test
    void xxHash32MatchesReference() {
        SplittableRandom random = new SplittableRandom(1);
        for (int length = 0; length < 200; length++) {
            byte[] data = new byte[length + 3];
            random.nextBytes(data);
            int seed = random.nextInt();
            int expected = XXHashFactory.safeInstance().hash32().hash(data, 3, length, seed);
            assertEquals(expected, XxHash32.hash(data, 3, length, seed), "length " + length);
        }
    }

    @Test
    void decodesStreamsWrittenByLz4Java() throws IOException {
        SplittableRandom random = new SplittableRandom(2);
        for (int size : new int[]{0, 1, 15, 1000, 65536, 65537, 300_000}) {
            for (int blockSize : new int[]{64, 1024, 65536}) {
                byte[] original = chunkLikeData(random, size);
                byte[] compressed = lz4(original, blockSize);
                GrowableBytes out = new GrowableBytes(16);
                Lz4BlockStream.decode(compressed, 0, compressed.length, out);
                assertArrayEquals(original, Arrays.copyOf(out.data(), out.length()),
                        "size " + size + ", block " + blockSize);
            }
        }
    }

    @Test
    void decodesIncompressibleData() throws IOException {
        byte[] original = new byte[100_000];
        new SplittableRandom(3).nextBytes(original);
        byte[] compressed = lz4(original, 65536);
        GrowableBytes out = new GrowableBytes(16);
        Lz4BlockStream.decode(compressed, 0, compressed.length, out);
        assertArrayEquals(original, Arrays.copyOf(out.data(), out.length()));
    }

    @Test
    void rejectsCorruptData() throws IOException {
        byte[] original = chunkLikeData(new SplittableRandom(4), 50_000);
        byte[] compressed = lz4(original, 65536);

        byte[] badChecksum = compressed.clone();
        badChecksum[17] ^= 1;
        assertThrows(AnvilFormatException.class,
                () -> Lz4BlockStream.decode(badChecksum, 0, badChecksum.length, new GrowableBytes(16)));

        byte[] badMagic = compressed.clone();
        badMagic[0] = 'X';
        assertThrows(AnvilFormatException.class,
                () -> Lz4BlockStream.decode(badMagic, 0, badMagic.length, new GrowableBytes(16)));

        assertThrows(AnvilFormatException.class,
                () -> Lz4BlockStream.decode(compressed, 0, compressed.length / 2, new GrowableBytes(16)));

        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 200; i++) {
            byte[] fuzzed = compressed.clone();
            // Flip bytes in the compressed body, keep headers: must throw, never loop or overrun.
            fuzzed[21 + random.nextInt(fuzzed.length - 21)] = (byte) random.nextInt();
            try {
                Lz4BlockStream.decode(fuzzed, 0, fuzzed.length, new GrowableBytes(16));
            } catch (AnvilFormatException expected) {
                // fine
            }
        }
    }

    /** Repetitive data with some noise, like chunk NBT. */
    private static byte[] chunkLikeData(SplittableRandom random, int size) {
        byte[] data = new byte[size];
        String text = "minecraft:stone minecraft:deepslate block_states palette data ";
        for (int i = 0; i < size; i++) {
            data[i] = random.nextInt(10) == 0 ? (byte) random.nextInt() : (byte) text.charAt(i % text.length());
        }
        return data;
    }

    private static byte[] lz4(byte[] data, int blockSize) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LZ4BlockOutputStream out = new LZ4BlockOutputStream(bytes, blockSize)) {
            out.write(data);
        }
        return bytes.toByteArray();
    }
}
