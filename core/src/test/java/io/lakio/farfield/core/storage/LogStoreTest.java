package io.lakio.farfield.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogStoreTest {

    @TempDir
    Path dir;

    private static byte[] blob(SplittableRandom random) {
        byte[] b = new byte[random.nextInt(1, 5000)];
        random.nextBytes(b);
        return b;
    }

    private Map<Long, byte[]> fill(LogStore store, int count, long seed) throws IOException {
        SplittableRandom random = new SplittableRandom(seed);
        Map<Long, byte[]> expected = new HashMap<>();
        for (int i = 0; i < count; i++) {
            long key = random.nextInt(count / 2);
            byte[] b = blob(random);
            store.put(key, b, 0, b.length);
            expected.put(key, b);
        }
        return expected;
    }

    private static void check(LogStore store, Map<Long, byte[]> expected) throws IOException {
        assertEquals(expected.size(), store.size());
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            assertArrayEquals(e.getValue(), store.get(e.getKey()));
        }
    }

    @Test
    void putGetAndReopenWithSnapshot() throws IOException {
        Path file = dir.resolve("a.lod");
        Map<Long, byte[]> expected;
        try (LogStore store = LogStore.open(file)) {
            expected = fill(store, 400, 1);
            check(store, expected);
            assertNull(store.get(-1));
        }
        assertTrue(Files.exists(LogStore.indexFile(file)));
        try (LogStore store = LogStore.open(file)) {
            check(store, expected);
            // Opening invalidates the snapshot until the next clean close.
            assertFalse(Files.exists(LogStore.indexFile(file)));
        }
    }

    @Test
    void reopensByScanningWithoutSnapshot() throws IOException {
        Path file = dir.resolve("b.lod");
        Map<Long, byte[]> expected;
        try (LogStore store = LogStore.open(file)) {
            expected = fill(store, 300, 2);
        }
        Files.delete(LogStore.indexFile(file));
        try (LogStore store = LogStore.open(file)) {
            check(store, expected);
        }
    }

    @Test
    void cutsATornTail() throws IOException {
        Path file = dir.resolve("c.lod");
        Map<Long, byte[]> expected;
        long size;
        try (LogStore store = LogStore.open(file)) {
            expected = fill(store, 100, 3);
            store.sync();
            size = store.fileBytes();
        }
        Files.delete(LogStore.indexFile(file));
        // A crash in the middle of the next write: header and part of the payload.
        byte[] partial = new byte[LogStore.HEADER + 10];
        java.nio.ByteBuffer.wrap(partial).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(LogStore.MAGIC).putLong(7).putInt(1000).putInt(0);
        Files.write(file, partial, StandardOpenOption.APPEND);
        try (LogStore store = LogStore.open(file)) {
            check(store, expected);
            assertEquals(partial.length, store.truncatedBytes());
            assertEquals(size, store.fileBytes());
            byte[] b = {1, 2, 3};
            store.put(99, b, 0, 3);
            expected.put(99L, b);
        }
        try (LogStore store = LogStore.open(file)) {
            check(store, expected);
        }
    }

    @Test
    void skipsACorruptRecordAndReportsIt() throws IOException {
        Path file = dir.resolve("d.lod");
        byte[] a = {1, 2, 3, 4};
        byte[] b = {5, 6, 7, 8};
        try (LogStore store = LogStore.open(file)) {
            store.put(1, a, 0, a.length);
            store.put(2, b, 0, b.length);
        }
        // Flip a payload byte of the first record (bit rot).
        byte[] bytes = Files.readAllBytes(file);
        bytes[LogStore.HEADER] ^= 1;
        Files.write(file, bytes);
        // With the snapshot, the index still points at the record: get detects it.
        try (LogStore store = LogStore.open(file)) {
            assertThrows(IOException.class, () -> store.get(1));
            assertArrayEquals(b, store.get(2));
        }
        Files.delete(LogStore.indexFile(file));
        // Without it, the scan skips the record and keeps the next one.
        try (LogStore store = LogStore.open(file)) {
            assertEquals(1, store.corruptRecords());
            assertFalse(store.contains(1));
            assertArrayEquals(b, store.get(2));
        }
    }

    @Test
    void compactionDropsGarbage() throws IOException {
        Path file = dir.resolve("e.lod");
        Map<Long, byte[]> expected;
        long before;
        try (LogStore store = LogStore.open(file)) {
            expected = fill(store, 1000, 5);
            before = store.fileBytes();
            assertTrue(store.liveBytes() < before);
        }
        LogStore.compact(file);
        try (LogStore store = LogStore.open(file)) {
            check(store, expected);
            assertEquals(store.liveBytes(), store.fileBytes());
            assertTrue(store.fileBytes() < before);
        }
    }
}
