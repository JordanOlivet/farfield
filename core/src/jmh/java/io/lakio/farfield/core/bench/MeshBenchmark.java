package io.lakio.farfield.core.bench;

import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.ChunkDecoder;
import io.lakio.farfield.core.anvil.ChunkDecompressor;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.anvil.RegionFile;
import io.lakio.farfield.core.anvil.SectionData;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.BinaryGreedyMesher;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.MeshBuffer;
import io.lakio.farfield.core.mesh.Quad;
import io.lakio.farfield.core.util.GrowableBytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Single-thread cost of meshing one 32x32x32 LOD section (spike S2, C.3), on
 * every non-empty section of the largest region file of a corpus world,
 * with its real neighbors. Corpus root: {@code farfield.corpus} system
 * property, set from {@code farfield.corpus.dir} in {@code local.properties}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"-Xmx3g"})
public class MeshBenchmark {

    @Param({"server-spawn", "server-pregen", "amplified"})
    public String world;

    private LodSection[] sections;
    private int[][][] borders;
    private MaterialClassifier classifier;
    private int next;

    private final BinaryGreedyMesher mesher = new BinaryGreedyMesher();
    private final MeshBuffer out = new MeshBuffer();

    @Setup(Level.Trial)
    public void load() throws IOException {
        String root = System.getProperty("farfield.corpus");
        if (root == null) {
            throw new IllegalStateException("set farfield.corpus.dir in local.properties (docs/corpus.md)");
        }
        Path corpus = Path.of(root);
        Path regionDir = corpus.resolve(world).resolve("region");
        Path largest;
        try (Stream<Path> files = Files.list(regionDir)) {
            largest = files.filter(RegionFile::isRegionFile)
                    .max(Comparator.comparingLong(p -> p.toFile().length()))
                    .orElseThrow();
        }

        KeyRegistry blocks = new KeyRegistry();
        int air = blocks.id("minecraft:air");
        ChunkDecoder decoder = new ChunkDecoder(blocks, new KeyRegistry());
        ChunkData chunk = new ChunkData();
        GrowableBytes nbt = new GrowableBytes(256 * 1024);
        RegionFile.RawChunk raw = new RegionFile.RawChunk();
        Map<Long, LodSection> grid = new HashMap<>();
        long buildNanos = 0;
        long chunkSections = 0;
        try (RegionFile region = RegionFile.open(largest); ChunkDecompressor decompressor = new ChunkDecompressor()) {
            for (int i = 0; i < RegionFile.CHUNK_COUNT; i++) {
                if (!region.read(i, raw)) {
                    continue;
                }
                decompressor.decompress(raw.compression, raw.bytes.data(), raw.offset, raw.length, nbt);
                int cx = region.chunkX(i);
                int cz = region.chunkZ(i);
                if (!decoder.decodeHeader(nbt.data(), 0, nbt.length(), cx, cz, chunk) || !chunk.isFull()) {
                    continue;
                }
                decoder.decodeSections(chunk);
                long t0 = System.nanoTime();
                for (int s = 0; s < chunk.sectionCount(); s++) {
                    SectionData section = chunk.section(s);
                    if (!section.hasBlocks()) {
                        continue;
                    }
                    long key = key(Math.floorDiv(cx, 2), Math.floorDiv(section.y(), 2), Math.floorDiv(cz, 2));
                    grid.computeIfAbsent(key, k -> new LodSection(air))
                            .writeChunkSection(cx & 1, Math.floorMod(section.y(), 2), cz & 1, section);
                    chunkSections++;
                }
                buildNanos += System.nanoTime() - t0;
            }
        }

        int[] classes = classify(blocks);
        classifier = m -> classes[m];

        List<LodSection> meshed = new ArrayList<>();
        List<int[][]> meshedBorders = new ArrayList<>();
        long quads = 0;
        long faces = 0;
        long payload = 0;
        long[] perDirection = new long[Quad.DIRECTIONS];
        for (Map.Entry<Long, LodSection> e : grid.entrySet()) {
            long k = e.getKey();
            int x = (int) (k >> 42);
            int y = (int) (k << 22 >> 43);
            int z = (int) (k << 43 >> 43);
            int[][] planes = new int[Quad.DIRECTIONS][];
            for (int d = 0; d < Quad.DIRECTIONS; d++) {
                int[] step = {0, 0, 0};
                step[d >> 1] = (d & 1) == 0 ? -1 : 1;
                LodSection neighbor = grid.get(key(x + step[0], y + step[1], z + step[2]));
                if (neighbor != null) {
                    planes[d] = BinaryGreedyMesher.borderPlane(neighbor, d, classifier, null);
                }
            }
            mesher.mesh(e.getValue(), classifier, planes, out);
            if (out.total() == 0) {
                continue;
            }
            meshed.add(e.getValue());
            meshedBorders.add(planes);
            quads += out.total();
            faces += mesher.facesEmitted();
            payload += e.getValue().payloadBytes();
            for (int d = 0; d < Quad.DIRECTIONS; d++) {
                perDirection[d] += out.count(d);
            }
        }
        sections = meshed.toArray(new LodSection[0]);
        borders = meshedBorders.toArray(new int[0][][]);
        int n = sections.length;
        System.out.printf(Locale.ROOT,
                "%n%s: %s, %d sections (%d with quads), build %.1f us per chunk section,"
                        + " %.0f quads and %.0f faces per meshed section (%.1fx merge), %.0f bytes of quads,"
                        + " %.0f bytes of bricks; quads per direction %s%n",
                world, largest.getFileName(), grid.size(), n, buildNanos / 1e3 / Math.max(1, chunkSections),
                (double) quads / n, (double) faces / n, (double) faces / Math.max(1, quads), 8.0 * quads / n,
                (double) payload / n, Arrays.toString(perDirection));
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /**
     * Rough material classes from block keys, for benchmarking only (the game
     * side will derive them from block states): air and small plants are
     * air, water, glass and ice are translucent, everything else is an
     * opaque cube.
     */
    static int[] classify(KeyRegistry blocks) {
        Set<String> airLike = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
                "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
                "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:kelp", "minecraft:kelp_plant",
                "minecraft:vine", "minecraft:dead_bush");
        int[] classes = new int[blocks.size()];
        for (int id = 0; id < classes.length; id++) {
            String key = blocks.key(id);
            int bracket = key.indexOf('[');
            String name = bracket < 0 ? key : key.substring(0, bracket);
            if (airLike.contains(name)) {
                classes[id] = MaterialClassifier.AIR;
            } else if (name.equals("minecraft:water") || name.endsWith("glass") || name.endsWith("glass_pane")
                    || name.equals("minecraft:ice")) {
                classes[id] = MaterialClassifier.TRANSLUCENT;
            } else {
                classes[id] = MaterialClassifier.OPAQUE;
            }
        }
        return classes;
    }

    @Benchmark
    public int mesh() {
        int i = next;
        next = i + 1 == sections.length ? 0 : i + 1;
        mesher.mesh(sections[i], classifier, borders[i], out);
        return out.total();
    }
}
