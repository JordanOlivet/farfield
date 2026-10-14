package io.lakio.farfield.core.importer;

import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.ChunkDecompressor;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.anvil.RegionFile;
import io.lakio.farfield.core.anvil.TestChunks;
import io.lakio.farfield.core.anvil.TestChunks.Section;
import io.lakio.farfield.core.anvil.TestChunks.State;
import io.lakio.farfield.core.anvil.TestRegionWriter;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionImporterTest {

    @TempDir
    Path dir;

    private static byte[] chunk(int x, int z, String status) {
        return TestChunks.chunk(x, z, status, List.of(
                new Section(0, new State[]{new State("minecraft:stone")}, null,
                        new String[]{"minecraft:plains"}, null, new byte[2048], null)));
    }

    private static byte[] lz4(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LZ4BlockOutputStream out = new LZ4BlockOutputStream(bytes)) {
            out.write(data);
        }
        return bytes.toByteArray();
    }

    /** Region (0, 0) with every compression type, an external chunk, a partial and a corrupt chunk. */
    private void writeMixedRegion() throws IOException {
        new TestRegionWriter(0, 0)
                .put(0, 0, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(0, 0, "minecraft:full")))
                .put(1, 0, ChunkDecompressor.GZIP, TestChunks.gzip(chunk(1, 0, "minecraft:full")))
                .put(2, 0, ChunkDecompressor.NONE, chunk(2, 0, "minecraft:full"))
                .put(3, 0, ChunkDecompressor.LZ4, lz4(chunk(3, 0, "minecraft:full")))
                .put(4, 0, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(4, 0, "minecraft:full")), true)
                .put(5, 0, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(5, 0, "minecraft:noise")))
                .put(6, 0, ChunkDecompressor.ZLIB, new byte[]{1, 2, 3, 4})
                .put(31, 31, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(31, 31, "minecraft:full")))
                .write(dir);
    }

    @Test
    void readsRegionHeaderAndPayloads() throws IOException {
        writeMixedRegion();
        try (RegionFile region = RegionFile.open(dir.resolve("r.0.0.mca"))) {
            assertEquals(8, region.chunkCount());
            assertTrue(region.hasChunk(31 + 31 * 32));
            assertEquals(31, region.chunkX(31 + 31 * 32));
            assertEquals(31, region.chunkZ(31 + 31 * 32));
            RegionFile.RawChunk raw = new RegionFile.RawChunk();
            assertTrue(region.read(4, raw));
            assertTrue(raw.external);
            assertEquals(ChunkDecompressor.ZLIB, raw.compression);
            assertEquals(1_700_000_004, raw.timestamp);
        }
    }

    @Test
    void importsEveryCompressionAndCountsProblems() throws Exception {
        writeMixedRegion();
        new TestRegionWriter(-1, 2)
                .put(0, 0, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(-32, 64, "minecraft:full")))
                .write(dir);
        // Not a region file: ignored.
        Files.writeString(dir.resolve("notes.txt"), "x");

        Set<Long> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger failures = new AtomicInteger();
        KeyRegistry blocks = new KeyRegistry();
        RegionImporter importer = new RegionImporter(ImportOptions.defaults().withThreads(2), blocks, new KeyRegistry(),
                c -> {
                    assertEquals(1, c.sectionCount());
                    seen.add(((long) c.chunkX() << 32) | (c.chunkZ() & 0xFFFFFFFFL));
                },
                new ImportListener() {
                    @Override
                    public void chunkFailed(Path region, int chunkX, int chunkZ, Throwable error) {
                        failures.incrementAndGet();
                    }
                });
        ImportStats stats = importer.run(dir);

        assertEquals(2, stats.regionsDone());
        assertEquals(9, stats.chunksRead());
        assertEquals(7, stats.chunksImported());
        assertEquals(1, stats.chunksSkippedStatus());
        assertEquals(1, stats.chunksFailed());
        assertEquals(1, failures.get());
        assertEquals(1, stats.chunksExternal());
        assertEquals(7, seen.size());
        assertTrue(seen.contains((-32L << 32) | 64));
        assertEquals(1, blocks.size());
        assertEquals(1, stats.compressions().get(ChunkDecompressor.LZ4));
        assertEquals(7, stats.statuses().get("minecraft:full"));
        assertEquals(1, stats.statuses().get("minecraft:noise"));
    }

    @Test
    void importsPartialChunksWhenAsked() throws Exception {
        writeMixedRegion();
        AtomicInteger count = new AtomicInteger();
        RegionImporter importer = new RegionImporter(ImportOptions.defaults().withFullChunksOnly(false),
                new KeyRegistry(), new KeyRegistry(), c -> count.incrementAndGet(), ImportListener.NONE);
        importer.run(dir);
        assertEquals(7, count.get());
    }

    @Test
    void resumesFromCheckpoint() throws Exception {
        for (int r = 0; r < 4; r++) {
            new TestRegionWriter(r, 0)
                    .put(0, 0, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(r * 32, 0, "minecraft:full")))
                    .put(1, 0, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(r * 32 + 1, 0, "minecraft:full")))
                    .write(dir);
        }
        Path checkpoint = dir.resolve("import.checkpoint");
        ImportOptions options = ImportOptions.defaults().withThreads(1).withCheckpoint(checkpoint);

        // First run: the sink cancels the import in the middle of the second region.
        AtomicInteger first = new AtomicInteger();
        RegionImporter[] holder = new RegionImporter[1];
        holder[0] = new RegionImporter(options, new KeyRegistry(), new KeyRegistry(), c -> {
            if (first.incrementAndGet() == 3) {
                holder[0].cancel();
            }
        }, ImportListener.NONE);
        holder[0].run(dir);
        assertEquals(3, first.get());
        assertEquals(1, holder[0].stats().regionsDone());

        // Second run: skips the recorded region, redoes the interrupted one.
        AtomicInteger second = new AtomicInteger();
        RegionImporter resumed = new RegionImporter(options, new KeyRegistry(), new KeyRegistry(),
                c -> second.incrementAndGet(), ImportListener.NONE);
        ImportStats stats = resumed.run(dir);
        assertEquals(1, stats.regionsSkipped());
        assertEquals(3, stats.regionsDone());
        assertEquals(6, second.get());

        // A rewritten region file is imported again.
        Path region = dir.resolve("r.0.0.mca");
        Files.setLastModifiedTime(region, FileTime.fromMillis(
                Files.getLastModifiedTime(region).toMillis() + 10_000));
        AtomicInteger third = new AtomicInteger();
        ImportStats again = new RegionImporter(options, new KeyRegistry(), new KeyRegistry(),
                c -> third.incrementAndGet(), ImportListener.NONE).run(dir);
        assertEquals(2, third.get());
        assertEquals(3, again.regionsSkipped());
    }

    @Test
    void checkpointToleratesTruncatedLastLine() throws Exception {
        new TestRegionWriter(0, 0)
                .put(0, 0, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(0, 0, "minecraft:full")))
                .write(dir);
        Path checkpoint = dir.resolve("import.checkpoint");
        Files.writeString(checkpoint, ImportCheckpoint.HEADER + "\nr.0.0.mca 12");
        AtomicInteger count = new AtomicInteger();
        new RegionImporter(ImportOptions.defaults().withCheckpoint(checkpoint), new KeyRegistry(), new KeyRegistry(),
                c -> count.incrementAndGet(), ImportListener.NONE).run(dir);
        assertEquals(1, count.get());
        List<String> lines = Files.readAllLines(checkpoint);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).startsWith("r.0.0.mca "));
    }

    @Test
    void sinkFailureAbortsTheImport() throws Exception {
        writeMixedRegion();
        RegionImporter importer = new RegionImporter(ImportOptions.defaults(), new KeyRegistry(), new KeyRegistry(),
                c -> {
                    throw new IllegalStateException("boom");
                }, ImportListener.NONE);
        RegionImporter.ImportFailedException e = assertThrows(RegionImporter.ImportFailedException.class,
                () -> importer.run(dir));
        assertEquals("boom", e.getCause().getMessage());
    }

    @Test
    void reportsUnreadableRegions() throws Exception {
        new TestRegionWriter(0, 0).rawLocation(0, 0, 999 << 8 | 1).write(dir);
        AtomicInteger failed = new AtomicInteger();
        ImportStats stats = new RegionImporter(ImportOptions.defaults(), new KeyRegistry(), new KeyRegistry(),
                c -> {
                }, new ImportListener() {
                    @Override
                    public void chunkFailed(Path region, int chunkX, int chunkZ, Throwable error) {
                        failed.incrementAndGet();
                    }
                }).run(dir);
        assertEquals(1, failed.get());
        assertEquals(1, stats.regionsDone());
    }

    @Test
    void throttles() throws Exception {
        TestRegionWriter writer = new TestRegionWriter(0, 0);
        for (int i = 0; i < 40; i++) {
            writer.put(i % 32, i / 32, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(i % 32, i / 32, "minecraft:full")));
        }
        writer.write(dir);
        long start = System.nanoTime();
        ImportStats stats = new RegionImporter(ImportOptions.defaults().withMaxChunksPerSecond(200),
                new KeyRegistry(), new KeyRegistry(), c -> {
        }, ImportListener.NONE).run(dir);
        double seconds = (System.nanoTime() - start) / 1e9;
        assertEquals(40, stats.chunksImported());
        // 40 chunks at 200/s take about 0.2 s, minus the 50 ms burst allowance.
        assertTrue(seconds > 0.12, "took " + seconds + " s");
    }

    @Test
    void pauseAndResume() throws Exception {
        TestRegionWriter writer = new TestRegionWriter(0, 0);
        for (int i = 0; i < 10; i++) {
            writer.put(i, 0, ChunkDecompressor.ZLIB, TestChunks.zlib(chunk(i, 0, "minecraft:full")));
        }
        writer.write(dir);
        AtomicInteger count = new AtomicInteger();
        RegionImporter[] holder = new RegionImporter[1];
        holder[0] = new RegionImporter(ImportOptions.defaults().withThreads(1), new KeyRegistry(), new KeyRegistry(),
                c -> {
                    if (count.incrementAndGet() == 3) {
                        holder[0].pause();
                    }
                }, ImportListener.NONE);
        Thread runner = new Thread(() -> {
            try {
                holder[0].run(dir);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        runner.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (count.get() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(100);
        assertEquals(3, count.get());
        holder[0].resume();
        runner.join(5000);
        assertEquals(10, count.get());
    }

    @Test
    void sinkSeesChunkData() throws Exception {
        writeMixedRegion();
        KeyRegistry blocks = new KeyRegistry();
        KeyRegistry biomes = new KeyRegistry();
        new RegionImporter(ImportOptions.defaults().withThreads(1), blocks, biomes, c -> {
            assertTrue(c.isFull());
            assertEquals("minecraft:stone", blocks.key(c.section(0).blockAt(0)));
            assertEquals("minecraft:plains", biomes.key(c.section(0).biomeAt(0, 0, 0)));
            assertEquals(0, c.warnings() & ~ChunkData.WARN_MISPLACED);
        }, ImportListener.NONE).run(dir);
    }
}
