package io.lakio.farfield.core.bench;

import io.lakio.farfield.core.importer.ImportListener;
import io.lakio.farfield.core.importer.ImportOptions;
import io.lakio.farfield.core.importer.ImportStats;
import io.lakio.farfield.core.importer.RegionImporter;
import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.core.world.MaterialTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The production LOD build path end to end on a corpus world: region import
 * into a {@link LodWorld} (level-0 read-modify-write, zstd store), then the
 * mip pyramid. Measures what the "build LOD for an existing world" command
 * costs.
 *
 * <pre>
 *   gradlew :core:worldBench --args="&lt;world dir&gt; &lt;output dir&gt; [--threads N] [--limit-regions N]"
 * </pre>
 *
 * The output directory is deleted first.
 */
public final class WorldBuildBenchmark {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: WorldBuildBenchmark <world dir> <output dir> [--threads N] [--limit-regions N]");
            System.exit(2);
        }
        Path world = Path.of(args[0]);
        Path regionDir = Files.isDirectory(world.resolve("region")) ? world.resolve("region") : world;
        Path out = Path.of(args[1]);
        int threads = 8;
        int limit = Integer.MAX_VALUE;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--limit-regions" -> limit = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        deleteTree(out);
        List<Path> regions = RegionImporter.listRegions(regionDir, 0, 0);
        if (regions.size() > limit) {
            regions = regions.subList(0, limit);
        }
        long regionBytes = 0;
        for (Path r : regions) {
            regionBytes += Files.size(r);
        }
        System.out.printf(Locale.ROOT, "World: %s%nRegions: %d (%.1f MB), threads %d, JVM %s%n",
                regionDir, regions.size(), regionBytes / 1e6, threads, Runtime.version());

        MaterialTable.Resolver resolver = key -> {
            int shape = StorageBenchmark.shapeOf(key);
            int meshClass = shape == MipBuilder.Shapes.EMPTY ? MaterialClassifier.AIR : MaterialClassifier.OPAQUE;
            return MaterialTable.pack(meshClass, shape);
        };
        try (LodWorld lod = LodWorld.open(out, resolver)) {
            long t0 = System.nanoTime();
            RegionImporter importer = new RegionImporter(ImportOptions.defaults().withThreads(threads),
                    lod.blockStates(), lod.biomes(), chunk -> {
                try {
                    lod.ingest(chunk);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, ImportListener.NONE);
            ImportStats stats = importer.run(regions);
            long t1 = System.nanoTime();
            lod.flush(0);
            long tf = System.nanoTime();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            int parents = lod.buildMips(pool);
            pool.shutdown();
            long t2 = System.nanoTime();
            lod.sync();
            long[] store = lod.storeStats();
            double importS = (t1 - t0) / 1e9;
            System.out.printf(Locale.ROOT, "Import + level 0: %,d chunks in %.1f s (%,.0f chunks/s), %,d sections written, %,d unchanged%n",
                    stats.chunksImported(), importS, stats.chunksImported() / importS, lod.sectionsWritten() - parents,
                    lod.sectionsUnchanged());
            System.out.printf(Locale.ROOT, "Flush %.1f s; mips (%d threads): %,d parents in %.1f s%n", (tf - t1) / 1e9, threads,
                    parents, (t2 - tf) / 1e9);
            System.out.printf(Locale.ROOT, "Store: %,d region files, %,d records, %.1f MB (%.1f%% of the region files), %,d block states%n",
                    store[0], store[1], store[2] / 1e6, 100.0 * store[2] / regionBytes, lod.blockStates().size());
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
