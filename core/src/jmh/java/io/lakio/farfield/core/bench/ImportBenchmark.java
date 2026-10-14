package io.lakio.farfield.core.bench;

import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.anvil.SectionData;
import io.lakio.farfield.core.importer.ImportListener;
import io.lakio.farfield.core.importer.ImportOptions;
import io.lakio.farfield.core.importer.ImportStats;
import io.lakio.farfield.core.importer.RegionImporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * End-to-end region import throughput on a real world (spike S5, B.4): reads
 * every region file of a dimension with the production importer and a sink
 * that only touches the decoded data.
 *
 * <pre>
 *   gradlew :core:importBench --args="&lt;world or region dir&gt; [--threads 1,8,16] [--repeat N]
 *                                     [--all-status] [--limit-regions N] [--center X,Z]"
 * </pre>
 *
 * The first repeat may read from disk; later repeats mostly hit the OS file
 * cache. Both are reported.
 */
public final class ImportBenchmark {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ImportBenchmark <world or region dir> [--threads 1,8,16] [--repeat N]"
                    + " [--all-status] [--limit-regions N] [--center X,Z]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        if (Files.isDirectory(dir.resolve("region"))) {
            dir = dir.resolve("region");
        }
        List<Integer> threadCounts = List.of(8);
        int repeat = 1;
        boolean allStatus = false;
        int limitRegions = Integer.MAX_VALUE;
        int centerX = 0;
        int centerZ = 0;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--threads" -> {
                    List<Integer> list = new ArrayList<>();
                    for (String t : args[++i].split(",")) {
                        list.add(Integer.parseInt(t.trim()));
                    }
                    threadCounts = list;
                }
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                case "--all-status" -> allStatus = true;
                case "--limit-regions" -> limitRegions = Integer.parseInt(args[++i]);
                case "--center" -> {
                    String[] c = args[++i].split(",");
                    centerX = Integer.parseInt(c[0].trim());
                    centerZ = Integer.parseInt(c[1].trim());
                }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        List<Path> regions = RegionImporter.listRegions(dir, centerX, centerZ);
        if (regions.size() > limitRegions) {
            regions = regions.subList(0, limitRegions);
        }
        long regionBytes = 0;
        for (Path r : regions) {
            regionBytes += Files.size(r);
        }
        System.out.printf(Locale.ROOT, "World: %s%nRegions: %d (%.1f MB), cores: %d, JVM: %s%n",
                dir, regions.size(), regionBytes / 1e6, Runtime.getRuntime().availableProcessors(),
                System.getProperty("java.vm.version"));

        for (int threads : threadCounts) {
            for (int run = 1; run <= repeat; run++) {
                runOnce(regions, threads, allStatus, run, centerX, centerZ);
            }
        }
    }

    private static void runOnce(List<Path> regions, int threads, boolean allStatus, int run,
                                int centerX, int centerZ) throws Exception {
        KeyRegistry blocks = new KeyRegistry();
        KeyRegistry biomes = new KeyRegistry();
        LongAdder nonUniformSections = new LongAdder();
        LongAdder blockSections = new LongAdder();
        AtomicLong touch = new AtomicLong();
        AtomicLong firstError = new AtomicLong();
        ImportOptions options = ImportOptions.defaults()
                .withThreads(threads)
                .withFullChunksOnly(!allStatus)
                .withCenterRegion(centerX, centerZ);
        RegionImporter importer = new RegionImporter(options, blocks, biomes, chunk -> {
            long h = 0;
            for (int i = 0; i < chunk.sectionCount(); i++) {
                SectionData s = chunk.section(i);
                if (!s.hasBlocks()) {
                    continue;
                }
                blockSections.increment();
                if (!s.isUniform()) {
                    nonUniformSections.increment();
                    h += s.blockIndices()[i * 97 & 4095];
                }
                h += s.blockPaletteEntry(0);
            }
            touch.addAndGet(h);
        }, new ImportListener() {
            @Override
            public void chunkFailed(Path region, int chunkX, int chunkZ, Throwable error) {
                if (firstError.getAndIncrement() < 5) {
                    System.out.printf(Locale.ROOT, "  chunk error %s (%d, %d): %s%n",
                            region.getFileName(), chunkX, chunkZ, error);
                }
            }

            @Override
            public void regionFailed(Path region, Throwable error) {
                System.out.printf(Locale.ROOT, "  region error %s: %s%n", region.getFileName(), error);
            }
        });

        long start = System.nanoTime();
        ImportStats stats = importer.run(regions);
        double seconds = (System.nanoTime() - start) / 1e9;

        long read = stats.chunksRead();
        long imported = stats.chunksImported();
        System.out.printf(Locale.ROOT, "%n== threads=%d run=%d: %.2f s, %,.0f chunks/s imported, %,.0f chunks/s read, %.0f MB/s compressed%n",
                threads, run, seconds, imported / seconds, read / seconds, stats.compressedBytes() / seconds / 1e6);
        System.out.println("  " + stats);
        System.out.printf(Locale.ROOT, "  sections: %,d (%,d with blocks, %,d non-uniform)%n",
                stats.sections(), blockSections.sum(), nonUniformSections.sum());
        System.out.printf(Locale.ROOT, "  bytes per chunk: %,.0f compressed, %,.0f NBT%n",
                (double) stats.compressedBytes() / Math.max(1, read), (double) stats.nbtBytes() / Math.max(1, read));
        System.out.printf(Locale.ROOT, "  worker time per chunk read (us): read %.1f, decompress %.1f, decode %.1f (per imported), sink %.1f%n",
                stats.readNanos() / 1e3 / Math.max(1, read), stats.decompressNanos() / 1e3 / Math.max(1, read),
                stats.decodeNanos() / 1e3 / Math.max(1, imported), stats.sinkNanos() / 1e3 / Math.max(1, imported));
        System.out.println("  statuses: " + stats.statuses());
        System.out.println("  data versions: " + stats.dataVersions());
        System.out.println("  compressions: " + stats.compressions());
        System.out.printf(Locale.ROOT, "  distinct block states: %,d, biomes: %,d (touch %d)%n",
                blocks.size(), biomes.size(), touch.get() & 1);
        if (stats.warnings(ChunkData.WARN_MISPLACED) > 0) {
            System.out.println("  note: misplaced chunks are imported at their region slot");
        }
    }
}
