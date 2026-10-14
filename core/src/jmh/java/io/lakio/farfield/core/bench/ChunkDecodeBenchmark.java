package io.lakio.farfield.core.bench;

import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.ChunkDecoder;
import io.lakio.farfield.core.anvil.ChunkDecompressor;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.anvil.RegionFile;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Single-thread cost per chunk of the import stages, on the chunks of the
 * largest region file of a corpus world (spike S5, B.4). The corpus root is
 * the {@code farfield.corpus} system property (default
 * {@code C:\Users\Lakio\farfield-corpus}, see {@code docs/corpus.md}).
 *
 * <p>Each invocation processes the next chunk of the region, cycling, so the
 * score is the average over the region's real chunk mix.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"-Xmx2g"})
public class ChunkDecodeBenchmark {

    @Param({"server-spawn", "server-pregen", "amplified"})
    public String world;

    private int[] compression;
    private byte[][] compressed;
    private byte[][] nbt;
    private int[] chunkX;
    private int[] chunkZ;
    private int next;

    private final GrowableBytes buffer = new GrowableBytes(256 * 1024);
    private final ChunkData chunk = new ChunkData();
    private ChunkDecompressor decompressor;
    private ChunkDecoder decoder;

    @Setup(Level.Trial)
    public void load() throws IOException {
        Path corpus = Path.of(System.getProperty("farfield.corpus", "C:\\Users\\Lakio\\farfield-corpus"));
        Path regionDir = corpus.resolve(world).resolve("region");
        Path largest;
        try (Stream<Path> files = Files.list(regionDir)) {
            largest = files.filter(RegionFile::isRegionFile)
                    .max(Comparator.comparingLong(ChunkDecodeBenchmark::size))
                    .orElseThrow(() -> new IllegalStateException("no region files in " + regionDir));
        }
        decompressor = new ChunkDecompressor();
        decoder = new ChunkDecoder(new KeyRegistry(), new KeyRegistry());
        List<byte[]> payloads = new ArrayList<>();
        List<byte[]> decoded = new ArrayList<>();
        List<int[]> meta = new ArrayList<>();
        RegionFile.RawChunk raw = new RegionFile.RawChunk();
        try (RegionFile region = RegionFile.open(largest)) {
            for (int i = 0; i < RegionFile.CHUNK_COUNT; i++) {
                if (!region.hasChunk(i) || !region.read(i, raw)) {
                    continue;
                }
                byte[] payload = Arrays.copyOfRange(raw.bytes.data(), raw.offset, raw.offset + raw.length);
                decompressor.decompress(raw.compression, payload, 0, payload.length, buffer);
                byte[] data = Arrays.copyOf(buffer.data(), buffer.length());
                // Keep full chunks only, like a default import.
                if (!decoder.decodeHeader(data, 0, data.length, region.chunkX(i), region.chunkZ(i), chunk) || !chunk.isFull()) {
                    continue;
                }
                payloads.add(payload);
                decoded.add(data);
                meta.add(new int[]{raw.compression, region.chunkX(i), region.chunkZ(i)});
            }
        }
        int n = payloads.size();
        compressed = payloads.toArray(new byte[0][]);
        nbt = decoded.toArray(new byte[0][]);
        compression = new int[n];
        chunkX = new int[n];
        chunkZ = new int[n];
        for (int i = 0; i < n; i++) {
            compression[i] = meta.get(i)[0];
            chunkX[i] = meta.get(i)[1];
            chunkZ[i] = meta.get(i)[2];
        }
        System.out.printf("%n%s: %s, %d full chunks%n", world, largest.getFileName(), n);
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    @TearDown(Level.Trial)
    public void close() {
        decompressor.close();
    }

    private int nextIndex() {
        int i = next;
        next = i + 1 == compressed.length ? 0 : i + 1;
        return i;
    }

    @Benchmark
    public int decompress() {
        int i = nextIndex();
        decompressor.decompress(compression[i], compressed[i], 0, compressed[i].length, buffer);
        return buffer.length();
    }

    @Benchmark
    public int decode() {
        int i = nextIndex();
        decoder.decodeHeader(nbt[i], 0, nbt[i].length, chunkX[i], chunkZ[i], chunk);
        decoder.decodeSections(chunk);
        return chunk.sectionCount();
    }

    @Benchmark
    public int decompressAndDecode() {
        int i = nextIndex();
        decompressor.decompress(compression[i], compressed[i], 0, compressed[i].length, buffer);
        decoder.decodeHeader(buffer.data(), 0, buffer.length(), chunkX[i], chunkZ[i], chunk);
        decoder.decodeSections(chunk);
        return chunk.sectionCount();
    }
}
