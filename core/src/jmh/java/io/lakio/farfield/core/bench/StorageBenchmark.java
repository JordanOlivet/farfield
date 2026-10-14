package io.lakio.farfield.core.bench;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdDictTrainer;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.ChunkDecoder;
import io.lakio.farfield.core.anvil.ChunkDecompressor;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.anvil.RegionFile;
import io.lakio.farfield.core.anvil.SectionData;
import io.lakio.farfield.core.importer.RegionImporter;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.storage.LogStore;
import io.lakio.farfield.core.storage.SectionCodec;
import io.lakio.farfield.core.util.ByteWriter;
import io.lakio.farfield.core.util.GrowableBytes;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

/**
 * Spike S1 measurements on one world (plan items D.1 to D.3): builds the LOD
 * sections of every region (level 0 from full chunks, levels 1 to 4 with
 * {@link MipBuilder}), encodes them several ways, and optionally writes them
 * to the two candidate containers.
 *
 * <pre>
 *   gradlew :core:storageBench --args="&lt;world dir&gt; [--threads 8] [--limit-regions N]
 *                                      [--containers] [--no-dedup] [--keep] [--crash-test N]"
 * </pre>
 *
 * Encoders (sizes summed over all non-empty sections of all levels):
 * <ul>
 *   <li>dense+zstd: one 64-bit word per voxel (material, biome, sky, block
 *       light), the Voxy-style layout, zstd level 3;</li>
 *   <li>codec: {@link SectionCodec} (palette, bit packing, uniform bricks);
 *       then zstd 3, zstd 9, zstd 3 with a dictionary trained on the first
 *       regions;</li>
 *   <li>residual: levels 0 to 3 store only the voxels that differ from the
 *       upsampled parent (codec + zstd 3);</li>
 *   <li>dedup: share of non-uniform block bricks that already exist
 *       elsewhere in the world (exact content).</li>
 * </ul>
 */
public final class StorageBenchmark {

    static final int LEVELS = 5;
    /** Residual marker material: "same as the parent". */
    static final int SAME = 0x0FFFFFFF;
    static final int ZSTD_LEVEL = 3;
    static final int DICT_SIZE = 112 * 1024;
    static final int DICT_SAMPLE_BYTES = 32 << 20;

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    // Options
    private final Path regionDir;
    private int threads = 8;
    private int limitRegions = Integer.MAX_VALUE;
    private boolean containers;
    private boolean dedup = true;
    private boolean keep;
    private Path out;

    // Shared state
    private final KeyRegistry blocks = new KeyRegistry();
    private final KeyRegistry biomes = new KeyRegistry();
    private final IntPredicate isAir;
    private final int air;
    private volatile int[] classes = new int[0];
    private byte[] dictionary;
    private ZstdDictCompress dictCompress;
    private ZstdDictDecompress dictDecompress;

    // Results
    private final LongAdder fullChunks = new LongAdder();
    private final LongAdder[] built = adders();
    private final LongAdder[] kept = adders();
    private final LongAdder[] codecRaw = adders();
    private final LongAdder[] codecZstd = adders();
    private final LongAdder[] codecZstd9 = adders();
    private final LongAdder[] codecDict = adders();
    private final LongAdder[] dense = adders();
    private final LongAdder[] residual = adders();
    private final LongAdder[] uniformBricks = adders();
    private final LongAdder[] noLight = adders();
    private final LongAdder[] faceLight = adders();
    private final LongAdder[] faceLightDict = adders();
    private final LongAdder[] hiddenCodec = adders();
    private final LongAdder[] hiddenCodec9 = adders();
    private final LongAdder[] hiddenBytes = adders();
    private final LongAdder[] hiddenBytesUp = adders();
    private final LongAdder[] hiddenBytesUp9 = adders();
    private final LongAdder[] hiddenBytesUpDict = adders();
    private final LongAdder hiddenVoxels = new LongAdder();
    private final LongAdder verified = new LongAdder();
    private final LongAdder buildNanos = new LongAdder();
    private final LongAdder encodeNanos = new LongAdder();
    private final LongAdder bricksTotal = new LongAdder();
    private final Set<Long> brickHashes = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<long[]> blobKeys = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<byte[]> blobData = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LodSection> readSample = new ConcurrentLinkedQueue<>();
    private final AtomicInteger readSampleSize = new AtomicInteger();

    private static LongAdder[] adders() {
        LongAdder[] a = new LongAdder[LEVELS];
        for (int i = 0; i < LEVELS; i++) {
            a[i] = new LongAdder();
        }
        return a;
    }

    /**
     * Rough block shapes from block keys for the mip rule, for benchmarking
     * only (the game side derives them from block states).
     */
    static int shapeOf(String key) {
        int bracket = key.indexOf('[');
        String name = bracket < 0 ? key : key.substring(0, bracket);
        if (name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air")) {
            return MipBuilder.Shapes.EMPTY;
        }
        if (name.endsWith("_slab") || name.equals("minecraft:snow") || name.endsWith("_carpet")
                || name.equals("minecraft:dirt_path") || name.equals("minecraft:farmland")) {
            return MipBuilder.Shapes.COVER;
        }
        if (name.endsWith("grass") || name.endsWith("fern") || name.endsWith("flower") || name.endsWith("_sapling")
                || name.endsWith("torch") || name.endsWith("rail") || name.endsWith("_fence") || name.endsWith("_pane")
                || name.endsWith("_wall") || name.endsWith("_button") || name.endsWith("_bush") || name.contains("kelp")
                || name.endsWith("vine") || name.endsWith("vines") || name.equals("minecraft:dandelion")
                || name.equals("minecraft:poppy") || name.endsWith("_tulip") || name.endsWith("mushroom")) {
            return MipBuilder.Shapes.SPARSE;
        }
        return MipBuilder.Shapes.FULL;
    }

    private StorageBenchmark(Path regionDir) {
        this.regionDir = regionDir;
        this.air = blocks.id("minecraft:air");
        int caveAir = blocks.id("minecraft:cave_air");
        int voidAir = blocks.id("minecraft:void_air");
        this.isAir = m -> m == air || m == caveAir || m == voidAir;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: StorageBenchmark <world dir> [--threads 8] [--limit-regions N]"
                    + " [--containers] [--no-dedup] [--keep] [--crash-test N]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        if (Files.isDirectory(dir.resolve("region"))) {
            dir = dir.resolve("region");
        }
        StorageBenchmark bench = new StorageBenchmark(dir);
        int crashRuns = 0;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--threads" -> bench.threads = Integer.parseInt(args[++i]);
                case "--limit-regions" -> bench.limitRegions = Integer.parseInt(args[++i]);
                case "--containers" -> bench.containers = true;
                case "--no-dedup" -> bench.dedup = false;
                case "--keep" -> bench.keep = true;
                case "--crash-test" -> crashRuns = Integer.parseInt(args[++i]);
                case "--out" -> bench.out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (bench.out == null) {
            bench.out = Path.of("build", "storage-bench", dir.getParent().getFileName().toString());
        }
        if (crashRuns > 0) {
            StoreCrashTest.runAll(bench.out.resolveSibling("crash-test"), crashRuns);
            return;
        }
        bench.run();
    }

    private void run() throws Exception {
        List<Path> regions = RegionImporter.listRegions(regionDir, 0, 0);
        if (regions.size() > limitRegions) {
            regions = regions.subList(0, limitRegions);
        }
        long regionBytes = 0;
        for (Path r : regions) {
            regionBytes += Files.size(r);
        }
        System.out.printf(Locale.ROOT, "World: %s%nRegions: %d (%.1f MB), threads %d, JVM %s%n",
                regionDir, regions.size(), regionBytes / 1e6, threads, System.getProperty("java.vm.version"));

        trainDictionary(regions.subList(0, Math.min(6, regions.size())));

        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        ThreadLocal<Worker> workers = ThreadLocal.withInitial(Worker::new);
        for (Path region : regions) {
            futures.add(pool.submit(() -> {
                workers.get().process(region);
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        double seconds = (System.nanoTime() - start) / 1e9;

        report(regionBytes, seconds);
        readSpeed();
        if (containers) {
            containers();
        }
    }

    // ---------------------------------------------------------------- dictionary

    private void trainDictionary(List<Path> regions) throws IOException {
        ZstdDictTrainer trainer = new ZstdDictTrainer(DICT_SAMPLE_BYTES, DICT_SIZE);
        Worker worker = new Worker();
        int samples = 0;
        outer:
        for (Path region : regions) {
            for (Map<Long, LodSection> level : worker.buildLevels(region, false)) {
                for (LodSection s : level.values()) {
                    if (isEmpty(s)) {
                        continue;
                    }
                    worker.raw.reset();
                    worker.codec.encode(s, worker.raw);
                    if (!trainer.addSample(worker.raw.toByteArray())) {
                        break outer;
                    }
                    samples++;
                }
            }
        }
        dictionary = trainer.trainSamples();
        dictCompress = new ZstdDictCompress(dictionary, ZSTD_LEVEL);
        dictDecompress = new ZstdDictDecompress(dictionary);
        System.out.printf(Locale.ROOT, "Dictionary: %d bytes trained on %d sections of the %d nearest regions%n",
                dictionary.length, samples, regions.size());
    }

    // ---------------------------------------------------------------- per region

    private boolean isEmpty(LodSection s) {
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            if (!s.isUniform(b) || !isAir.test(s.paletteEntry(s.uniformIndex(b)))) {
                return false;
            }
        }
        return true;
    }

    private final class Worker {
        final SectionCodec codec = new SectionCodec();
        final ByteWriter raw = new ByteWriter(64 * 1024);
        final ByteWriter residualRaw = new ByteWriter(64 * 1024);
        final ZstdCompressCtx zstd = new ZstdCompressCtx().setLevel(ZSTD_LEVEL);
        final ZstdCompressCtx zstd9 = new ZstdCompressCtx().setLevel(9);
        final ZstdCompressCtx zstdDict = new ZstdCompressCtx().setLevel(ZSTD_LEVEL);
        final byte[] denseBytes = new byte[LodSection.SIZE * LodSection.SIZE * LodSection.SIZE * 8];
        byte[] compressed = new byte[(int) Zstd.compressBound(denseBytes.length)];
        final ChunkDecoder decoder = new ChunkDecoder(blocks, biomes);
        final ChunkData chunk = new ChunkData();
        final GrowableBytes nbt = new GrowableBytes(256 * 1024);
        final RegionFile.RawChunk rawChunk = new RegionFile.RawChunk();
        final MipBuilder mips = new MipBuilder(air, m -> shapeOf(blocks.key(m)));
        final int[] brickMaterials = new int[LodSection.BRICK_VOLUME];

        Worker() {
            if (dictCompress != null) {
                zstdDict.loadDict(dictCompress);
            }
        }

        List<Map<Long, LodSection>> buildLevels(Path regionPath, boolean count) throws IOException {
            long t0 = System.nanoTime();
            Map<Long, LodSection> level0 = new HashMap<>();
            try (RegionFile region = RegionFile.open(regionPath); ChunkDecompressor decompressor = new ChunkDecompressor()) {
                for (int i = 0; i < RegionFile.CHUNK_COUNT; i++) {
                    if (!region.read(i, rawChunk)) {
                        continue;
                    }
                    decompressor.decompress(rawChunk.compression, rawChunk.bytes.data(), rawChunk.offset, rawChunk.length, nbt);
                    int cx = region.chunkX(i);
                    int cz = region.chunkZ(i);
                    if (!decoder.decodeHeader(nbt.data(), 0, nbt.length(), cx, cz, chunk) || !chunk.isFull()) {
                        continue;
                    }
                    decoder.decodeSections(chunk);
                    if (count) {
                        fullChunks.increment();
                    }
                    for (int s = 0; s < chunk.sectionCount(); s++) {
                        SectionData section = chunk.section(s);
                        long key = LodKey.pack(0, Math.floorDiv(cx, 2), Math.floorDiv(section.y(), 2), Math.floorDiv(cz, 2));
                        level0.computeIfAbsent(key, k -> new LodSection(air))
                                .writeChunkSection(cx & 1, Math.floorMod(section.y(), 2), cz & 1, section);
                    }
                }
            }
            List<Map<Long, LodSection>> levels = new ArrayList<>();
            for (LodSection s : level0.values()) {
                s.compactBiomes();
            }
            levels.add(level0);
            for (int level = 1; level < LEVELS; level++) {
                Map<Long, LodSection[]> groups = new HashMap<>();
                for (Map.Entry<Long, LodSection> e : levels.get(level - 1).entrySet()) {
                    long k = e.getKey();
                    int x = LodKey.x(k);
                    int y = LodKey.y(k);
                    int z = LodKey.z(k);
                    long parent = LodKey.pack(level, Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2));
                    groups.computeIfAbsent(parent, p -> new LodSection[8])
                            [(Math.floorMod(y, 2) * 2 + Math.floorMod(z, 2)) * 2 + Math.floorMod(x, 2)] = e.getValue();
                }
                Map<Long, LodSection> parents = new HashMap<>();
                for (Map.Entry<Long, LodSection[]> e : groups.entrySet()) {
                    parents.put(e.getKey(), mips.build(e.getValue()));
                }
                levels.add(parents);
            }
            if (count) {
                buildNanos.add(System.nanoTime() - t0);
            }
            return levels;
        }

        void process(Path regionPath) throws IOException {
            List<Map<Long, LodSection>> levels = buildLevels(regionPath, true);
            long t0 = System.nanoTime();
            for (int level = 0; level < LEVELS; level++) {
                Map<Long, LodSection> parents = level + 1 < LEVELS ? levels.get(level + 1) : null;
                for (Map.Entry<Long, LodSection> e : levels.get(level).entrySet()) {
                    built[level].increment();
                    LodSection s = e.getValue();
                    if (isEmpty(s)) {
                        continue;
                    }
                    kept[level].increment();
                    uniformBricks[level].add(s.uniformBrickCount());
                    encode(level, e.getKey(), s, parents);
                }
            }
            encodeNanos.add(System.nanoTime() - t0);
        }

        private int compress(ZstdCompressCtx ctx, byte[] src, int length) {
            return ctx.compressByteArray(compressed, 0, compressed.length, src, 0, length);
        }

        private void encode(int level, long key, LodSection s, Map<Long, LodSection> parents) {
            raw.reset();
            codec.encode(s, raw);
            int rawLength = raw.length();
            codecRaw[level].add(rawLength);
            int z3 = compress(zstd, raw.array(), rawLength);
            codecZstd[level].add(z3);
            codecZstd9[level].add(compress(zstd9, raw.array(), rawLength));
            int zd = compress(zstdDict, raw.array(), rawLength);
            codecDict[level].add(zd);
            if (containers) {
                byte[] blob = new byte[zd + 4];
                ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).putInt(rawLength);
                System.arraycopy(compressed, 0, blob, 4, zd);
                blobKeys.add(new long[]{key});
                blobData.add(blob);
            }

            // Every 8th section: decode and re-encode, the bytes must match.
            if ((LodKey.x(key) + LodKey.z(key) + LodKey.y(key)) % 8 == 0) {
                LodSection back = codec.decode(raw.array(), 0, rawLength);
                ByteWriter again = new ByteWriter(rawLength);
                codec.encode(back, again);
                if (!Arrays.equals(raw.array(), 0, rawLength, again.array(), 0, again.length())) {
                    throw new IllegalStateException("codec round trip mismatch at " + Long.toHexString(key));
                }
                verified.increment();
            }

            dense[level].add(compress(zstd, denseOf(s), denseBytes.length));

            LodSection parent = parents == null ? null : parents.get(LodKey.pack(level + 1,
                    Math.floorDiv(LodKey.x(key), 2), Math.floorDiv(LodKey.y(key), 2), Math.floorDiv(LodKey.z(key), 2)));
            if (parent == null) {
                residual[level].add(z3);
            } else {
                LodSection r = residualOf(s, parent, Math.floorMod(LodKey.x(key), 2) * 16,
                        Math.floorMod(LodKey.y(key), 2) * 16, Math.floorMod(LodKey.z(key), 2) * 16);
                residualRaw.reset();
                codec.encode(r, residualRaw);
                residual[level].add(compress(zstd, residualRaw.array(), residualRaw.length()));
            }

            LodSection stripped = withLight(s, false);
            residualRaw.reset();
            codec.encode(stripped, residualRaw);
            noLight[level].add(compress(zstd, residualRaw.array(), residualRaw.length()));
            LodSection faces = withLight(s, true);
            residualRaw.reset();
            codec.encode(faces, residualRaw);
            faceLight[level].add(compress(zstd, residualRaw.array(), residualRaw.length()));
            faceLightDict[level].add(compress(zstdDict, residualRaw.array(), residualRaw.length()));
            LodSection hidden = withoutHiddenVoxels(faces, s);
            residualRaw.reset();
            codec.encode(hidden, residualRaw);
            hiddenCodec[level].add(compress(zstd, residualRaw.array(), residualRaw.length()));
            hiddenCodec9[level].add(compress(zstd9, residualRaw.array(), residualRaw.length()));
            residualRaw.reset();
            ByteCodec.encode(hidden, residualRaw, false);
            hiddenBytes[level].add(compress(zstd, residualRaw.array(), residualRaw.length()));
            residualRaw.reset();
            ByteCodec.encode(hidden, residualRaw, true);
            hiddenBytesUp[level].add(compress(zstd, residualRaw.array(), residualRaw.length()));
            hiddenBytesUp9[level].add(compress(zstd9, residualRaw.array(), residualRaw.length()));
            hiddenBytesUpDict[level].add(compress(zstdDict, residualRaw.array(), residualRaw.length()));

            if (dedup && level == 0) {
                for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
                    long[] data = s.brickData(b);
                    if (data == null) {
                        continue;
                    }
                    long h = 0x9E3779B97F4A7C15L;
                    for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
                        h = (h ^ s.paletteEntry(LodSection.unpack(data, s.bits(), v))) * 0x100000001B3L;
                        h ^= h >>> 29;
                    }
                    bricksTotal.increment();
                    brickHashes.add(h);
                }
            }

            if (level == 0 && readSampleSize.get() < 2000 && (key & 7) == 3 && readSampleSize.incrementAndGet() <= 2000) {
                readSample.add(s);
            }
        }

        /**
         * A copy of the section's blocks and biomes with either no light at
         * all, or light kept only where a face can be drawn: on non-opaque
         * voxels with at least one non-air neighbor (out-of-section
         * neighbors count as non-air). Other voxels take the brick's most
         * common kept value, so bricks collapse to uniform.
         */
        LodSection withLight(LodSection s, boolean faces) {
            LodSection copy = LodSection.withPalette(paletteOf(s), s.paletteSize());
            for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
                if (s.isUniform(b)) {
                    copy.setUniform(b, s.uniformIndex(b));
                } else {
                    copy.setBrickData(b, s.brickData(b).clone());
                }
            }
            if (s.biomeCells() == null) {
                copy.fillBiome(s.uniformBiome());
            } else {
                for (int c = 0; c < LodSection.BIOME_CELLS; c++) {
                    copy.setBiome(c & 7, c >> 6, (c >> 3) & 7, s.biomeCells()[c]);
                }
            }
            if (!faces) {
                return copy;
            }
            for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
                faceLightBrick(s, copy, b, true);
                faceLightBrick(s, copy, b, false);
            }
            return copy;
        }

        private final byte[] keptLight = new byte[LodSection.BRICK_VOLUME];
        private final boolean[] keptMask = new boolean[LodSection.BRICK_VOLUME];
        private final byte[] nibbles = new byte[256];

        private void faceLightBrick(LodSection s, LodSection copy, int b, boolean sky) {
            var light = sky ? s.skyLight() : s.blockLight();
            var target = sky ? copy.skyLight() : copy.blockLight();
            if (light.isUniform(b)) {
                target.setUniform(b, light.uniformValue(b));
                return;
            }
            int bx = (b & 3) * 8;
            int bz = ((b >> 2) & 3) * 8;
            int by = (b >> 4) * 8;
            int[] histogram = new int[16];
            int keptCount = 0;
            for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
                int x = bx + (v & 7);
                int z = bz + ((v >> 3) & 7);
                int y = by + (v >> 6);
                boolean keep = !opaque(s.material(x, y, z)) && touchesSolid(s, x, y, z);
                keptMask[v] = keep;
                if (keep) {
                    int value = light.get(x, y, z);
                    keptLight[v] = (byte) value;
                    histogram[value]++;
                    keptCount++;
                }
            }
            if (keptCount == 0) {
                target.setUniform(b, light.get(bx, by, bz));
                return;
            }
            int fill = 0;
            for (int i = 1; i < 16; i++) {
                if (histogram[i] > histogram[fill]) {
                    fill = i;
                }
            }
            Arrays.fill(nibbles, (byte) 0);
            for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
                int value = keptMask[v] ? keptLight[v] : fill;
                nibbles[v >> 1] |= (byte) (value << ((v & 1) << 2));
            }
            target.setBrick(b, nibbles);
        }

        /**
         * Replaces opaque voxels whose six neighbors are all opaque (never
         * visible, so useless to a renderer that rebuilds from the real chunk
         * on change) by the most common material of the visible voxels of
         * their brick. Border voxels are kept (their outside neighbor is
         * unknown here). Light comes from {@code lightFrom}.
         */
        LodSection withoutHiddenVoxels(LodSection lightFrom, LodSection s) {
            LodSection out = withLight(lightFrom, false);
            for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
                copyLight(lightFrom, out, b);
                if (s.isUniform(b)) {
                    continue;
                }
                int bx = (b & 3) * 8;
                int bz = ((b >> 2) & 3) * 8;
                int by = (b >> 4) * 8;
                int[] counts = new int[s.paletteSize()];
                int hiddenCount = 0;
                for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
                    int x = bx + (v & 7);
                    int z = bz + ((v >> 3) & 7);
                    int y = by + (v >> 6);
                    if (isHidden(s, x, y, z)) {
                        keptMask[v] = false;
                        hiddenCount++;
                    } else {
                        keptMask[v] = true;
                        counts[s.paletteIndex(x, y, z)]++;
                    }
                }
                if (hiddenCount == 0) {
                    continue;
                }
                hiddenVoxels.add(hiddenCount);
                int fill = 0;
                for (int i = 1; i < counts.length; i++) {
                    if (counts[i] > counts[fill]) {
                        fill = i;
                    }
                }
                int fillMaterial = s.paletteEntry(fill);
                for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
                    if (!keptMask[v]) {
                        out.set(bx + (v & 7), by + (v >> 6), bz + ((v >> 3) & 7), fillMaterial);
                    }
                }
            }
            out.compact();
            return out;
        }

        private boolean isHidden(LodSection s, int x, int y, int z) {
            if (x == 0 || y == 0 || z == 0 || x == 31 || y == 31 || z == 31) {
                return false;
            }
            return opaque(s.material(x, y, z))
                    && opaque(s.material(x - 1, y, z)) && opaque(s.material(x + 1, y, z))
                    && opaque(s.material(x, y - 1, z)) && opaque(s.material(x, y + 1, z))
                    && opaque(s.material(x, y, z - 1)) && opaque(s.material(x, y, z + 1));
        }

        private boolean opaque(int material) {
            int[] c = classes;
            if (material >= c.length) {
                synchronized (StorageBenchmark.this) {
                    if (material >= classes.length) {
                        classes = MeshBenchmark.classify(blocks);
                    }
                    c = classes;
                }
            }
            return c[material] == MaterialClassifier.OPAQUE;
        }

        private boolean touchesSolid(LodSection s, int x, int y, int z) {
            return x == 0 || y == 0 || z == 0 || x == 31 || y == 31 || z == 31
                    || !isAir.test(s.material(x - 1, y, z)) || !isAir.test(s.material(x + 1, y, z))
                    || !isAir.test(s.material(x, y - 1, z)) || !isAir.test(s.material(x, y + 1, z))
                    || !isAir.test(s.material(x, y, z - 1)) || !isAir.test(s.material(x, y, z + 1));
        }

        private int[] paletteOf(LodSection s) {
            int[] p = new int[s.paletteSize()];
            for (int i = 0; i < p.length; i++) {
                p[i] = s.paletteEntry(i);
            }
            return p;
        }

        /** The Voxy-style dense layout: one 64-bit word per voxel. */
        byte[] denseOf(LodSection s) {
            int i = 0;
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        long word = s.material(x, y, z)
                                | (long) (s.biome(x >> 2, y >> 2, z >> 2) + 1 & 0x3FF) << 20
                                | (long) s.skyLight().get(x, y, z) << 30
                                | (long) s.blockLight().get(x, y, z) << 34;
                        LONG_LE.set(denseBytes, i, word);
                        i += 8;
                    }
                }
            }
            return denseBytes;
        }

        LodSection residualOf(LodSection s, LodSection parent, int ox, int oy, int oz) {
            LodSection r = new LodSection(SAME);
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int m = s.material(x, y, z);
                        if (m != parent.material(ox + (x >> 1), oy + (y >> 1), oz + (z >> 1))) {
                            r.set(x, y, z, m);
                        }
                    }
                }
            }
            for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
                copyLight(s, r, b);
            }
            if (s.biomeCells() == null) {
                r.fillBiome(s.uniformBiome());
            } else {
                for (int c = 0; c < LodSection.BIOME_CELLS; c++) {
                    r.setBiome(c & 7, c >> 6, (c >> 3) & 7, s.biomeCells()[c]);
                }
            }
            return r;
        }

        private void copyLight(LodSection from, LodSection to, int b) {
            if (from.skyLight().isUniform(b)) {
                to.skyLight().setUniform(b, from.skyLight().uniformValue(b));
            } else {
                to.skyLight().setBrick(b, from.skyLight().brickData(b));
            }
            if (from.blockLight().isUniform(b)) {
                to.blockLight().setUniform(b, from.blockLight().uniformValue(b));
            } else {
                to.blockLight().setBrick(b, from.blockLight().brickData(b));
            }
        }
    }

    // ---------------------------------------------------------------- report

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%,.1f", bytes / 1e6);
    }

    private static long sum(LongAdder[] a) {
        long n = 0;
        for (LongAdder x : a) {
            n += x.sum();
        }
        return n;
    }

    private void report(long regionBytes, double seconds) {
        long chunks = fullChunks.sum();
        System.out.printf(Locale.ROOT, "%nFull chunks: %,d; processed in %.1f s (build %.1f s, encode %.1f s of worker time)%n",
                chunks, seconds, buildNanos.sum() / 1e9, encodeNanos.sum() / 1e9);
        System.out.println("level  built    kept  uniform bricks  dense+zstd  codec raw  codec+zstd3  codec+zstd9  codec+dict  residual  (MB)");
        for (int l = 0; l < LEVELS; l++) {
            long k = kept[l].sum();
            System.out.printf(Locale.ROOT, "%5d %7d %7d %14.1f%% %11s %10s %12s %12s %11s %9s%n",
                    l, built[l].sum(), k, 100.0 * uniformBricks[l].sum() / Math.max(1, k * LodSection.BRICK_COUNT),
                    mb(dense[l].sum()), mb(codecRaw[l].sum()), mb(codecZstd[l].sum()), mb(codecZstd9[l].sum()),
                    mb(codecDict[l].sum()), mb(residual[l].sum()));
        }
        long[] totals = {sum(dense), sum(codecRaw), sum(codecZstd), sum(codecZstd9), sum(codecDict), sum(residual),
                sum(noLight), sum(faceLight), sum(faceLightDict), sum(hiddenCodec), sum(hiddenCodec9), sum(hiddenBytes), sum(hiddenBytesUp),
                sum(hiddenBytesUp9), sum(hiddenBytesUpDict)};
        String[] names = {"dense+zstd3", "codec raw", "codec+zstd3", "codec+zstd9", "codec+zstd3+dict", "residual+zstd3",
                "no light+zstd3", "face light+zstd3", "face light+dict", "hidden+face, codec", "  same, zstd9", "hidden+face, bytes",
                "hidden+face, bytes up", "  same, zstd9", "  same, dict"};
        System.out.printf(Locale.ROOT, "%ntotal (all levels)        MB   bytes/full chunk   vs region files%n");
        for (int i = 0; i < totals.length; i++) {
            System.out.printf(Locale.ROOT, "%-20s %9s %15.0f %14.1f%%%n", names[i], mb(totals[i]),
                    (double) totals[i] / Math.max(1, chunks), 100.0 * totals[i] / regionBytes);
        }
        System.out.printf(Locale.ROOT, "level 0 only: codec+zstd3+dict %s MB, levels 1-4 add %.1f%%%n",
                mb(codecDict[0].sum()), 100.0 * (sum(codecDict) - codecDict[0].sum()) / Math.max(1, codecDict[0].sum()));
        System.out.printf(Locale.ROOT, "Round trips verified: %,d sections; hidden voxels replaced: %,d%n",
                verified.sum(), hiddenVoxels.sum());
        if (dedup) {
            long total = bricksTotal.sum();
            long unique = brickHashes.size();
            System.out.printf(Locale.ROOT, "Brick dedup (level 0, non-uniform block bricks): %,d bricks, %,d unique, %.1f%% duplicates%n",
                    total, unique, 100.0 * (total - unique) / Math.max(1, total));
        }
    }

    // ---------------------------------------------------------------- read speed

    private void readSpeed() {
        List<LodSection> sample = new ArrayList<>(readSample);
        if (sample.isEmpty()) {
            return;
        }
        Worker w = new Worker();
        List<byte[]> denseBlobs = new ArrayList<>();
        List<byte[]> codecBlobs = new ArrayList<>();
        List<Integer> codecLengths = new ArrayList<>();
        for (LodSection s : sample) {
            int n = w.compress(w.zstd, w.denseOf(s), w.denseBytes.length);
            denseBlobs.add(Arrays.copyOf(w.compressed, n));
            w.raw.reset();
            w.codec.encode(s, w.raw);
            codecLengths.add(w.raw.length());
            n = w.compress(w.zstdDict, w.raw.array(), w.raw.length());
            codecBlobs.add(Arrays.copyOf(w.compressed, n));
        }
        ZstdDecompressCtx plain = new ZstdDecompressCtx();
        ZstdDecompressCtx withDict = new ZstdDecompressCtx();
        withDict.loadDict(dictDecompress);
        byte[] denseOut = new byte[w.denseBytes.length];
        long[] voxels = new long[LodSection.SIZE * LodSection.SIZE * LodSection.SIZE];
        byte[] codecOut = new byte[1 << 20];
        long sink = 0;
        double denseUs = 0;
        double codecUs = 0;
        for (int pass = 0; pass < 8; pass++) {
            long t0 = System.nanoTime();
            for (byte[] blob : denseBlobs) {
                plain.decompressByteArray(denseOut, 0, denseOut.length, blob, 0, blob.length);
                for (int i = 0; i < voxels.length; i++) {
                    voxels[i] = (long) LONG_LE.get(denseOut, i * 8);
                }
                sink += voxels[sink == 0 ? 0 : 1];
            }
            long t1 = System.nanoTime();
            for (int i = 0; i < codecBlobs.size(); i++) {
                byte[] blob = codecBlobs.get(i);
                int n = codecLengths.get(i);
                withDict.decompressByteArray(codecOut, 0, n, blob, 0, blob.length);
                LodSection s = w.codec.decode(codecOut, 0, n);
                sink += s.paletteSize();
            }
            long t2 = System.nanoTime();
            if (pass >= 3) {
                denseUs += (t1 - t0) / 1e3 / denseBlobs.size() / 5;
                codecUs += (t2 - t1) / 1e3 / codecBlobs.size() / 5;
            }
        }
        System.out.printf(Locale.ROOT, "%nRead, one thread, %d level-0 sections: dense+zstd %.1f us (decompress + unpack),"
                        + " codec+zstd+dict %.1f us (decompress + decode) (sink %d)%n",
                sample.size(), denseUs, codecUs, sink & 1);
        zstdImplementations(w, sample);
    }

    /** zstd-jni (native) against aircompressor (pure Java) on the codec bytes of the read sample. */
    private static void zstdImplementations(Worker w, List<LodSection> sample) {
        List<byte[]> raws = new ArrayList<>();
        for (LodSection s : sample) {
            w.raw.reset();
            w.codec.encode(s, w.raw);
            raws.add(Arrays.copyOf(w.raw.array(), w.raw.length()));
        }
        io.airlift.compress.zstd.ZstdCompressor air = new io.airlift.compress.zstd.ZstdCompressor();
        io.airlift.compress.zstd.ZstdDecompressor airDecompressor = new io.airlift.compress.zstd.ZstdDecompressor();
        ZstdDecompressCtx jniDecompressor = new ZstdDecompressCtx();
        byte[] out = new byte[(int) Zstd.compressBound(1 << 20)];
        byte[] back = new byte[1 << 20];
        String[] names = {"zstd-jni level 3", "zstd-jni level 9", "aircompressor"};
        for (int impl = 0; impl < 3; impl++) {
            List<byte[]> blobs = new ArrayList<>();
            double compressUs = 0;
            double jniReadUs = 0;
            double airReadUs = 0;
            long bytes = 0;
            for (int pass = 0; pass < 6; pass++) {
                blobs.clear();
                bytes = 0;
                long t0 = System.nanoTime();
                for (byte[] raw : raws) {
                    int n = switch (impl) {
                        case 0 -> w.compress(w.zstd, raw, raw.length);
                        case 1 -> w.compress(w.zstd9, raw, raw.length);
                        default -> air.compress(raw, 0, raw.length, out, 0, out.length);
                    };
                    byte[] blob = impl == 2 ? Arrays.copyOf(out, n) : Arrays.copyOf(w.compressed, n);
                    blobs.add(blob);
                    bytes += n;
                }
                long t1 = System.nanoTime();
                for (int i = 0; i < blobs.size(); i++) {
                    byte[] blob = blobs.get(i);
                    jniDecompressor.decompressByteArray(back, 0, raws.get(i).length, blob, 0, blob.length);
                }
                long t2 = System.nanoTime();
                for (int i = 0; i < blobs.size(); i++) {
                    byte[] blob = blobs.get(i);
                    int n = airDecompressor.decompress(blob, 0, blob.length, back, 0, back.length);
                    if (n != raws.get(i).length || !Arrays.equals(back, 0, n, raws.get(i), 0, n)) {
                        throw new IllegalStateException("aircompressor round trip failed");
                    }
                }
                long t3 = System.nanoTime();
                if (pass >= 2) {
                    compressUs += (t1 - t0) / 1e3 / raws.size() / 4;
                    jniReadUs += (t2 - t1) / 1e3 / raws.size() / 4;
                    airReadUs += (t3 - t2) / 1e3 / raws.size() / 4;
                }
            }
            System.out.printf(Locale.ROOT, "%-17s %,10d bytes  compress %6.1f us  decompress jni %5.1f us, aircompressor %5.1f us%n",
                    names[impl], bytes, compressUs, jniReadUs, airReadUs);
        }
    }

    // ---------------------------------------------------------------- containers

    private void containers() throws Exception {
        List<long[]> keys = new ArrayList<>(blobKeys);
        List<byte[]> data = new ArrayList<>(blobData);
        long payload = 0;
        for (byte[] d : data) {
            payload += d.length;
        }
        System.out.printf(Locale.ROOT, "%nContainers: %,d records, %s MB of payload (codec+zstd3+dict, 4-byte length prefix)%n",
                keys.size(), mb(payload));
        deleteTree(out);
        Files.createDirectories(out);
        logStore(keys, data, payload);
        lmdb(keys, data, payload);
        if (!keep) {
            deleteTree(out);
        }
    }

    private static String fileName(long key) {
        return "L" + LodKey.level(key) + "." + (LodKey.x(key) >> 5) + "." + (LodKey.z(key) >> 5) + ".lod";
    }

    private void logStore(List<long[]> keys, List<byte[]> data, long payload) throws Exception {
        Path dir = out.resolve("logstore");
        Files.createDirectories(dir);
        long t0 = System.nanoTime();
        Map<String, LogStore> stores = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            long key = keys.get(i)[0];
            String name = fileName(key);
            LogStore store = stores.get(name);
            if (store == null) {
                store = LogStore.open(dir.resolve(name));
                stores.put(name, store);
            }
            byte[] d = data.get(i);
            store.put(key, d, 0, d.length);
            if (i % 10_000 == 9_999) {
                for (LogStore s : stores.values()) {
                    s.sync();
                }
            }
        }
        for (LogStore s : stores.values()) {
            s.close();
        }
        double write = (System.nanoTime() - t0) / 1e9;
        long bytes = folderBytes(dir);
        System.out.printf(Locale.ROOT, "LogStore: %d files, write %.2f s (%.0f MB/s, synced every 10,000 records), %s MB on disk (%.1f%% overhead)%n",
                stores.size(), write, payload / write / 1e6, mb(bytes), 100.0 * (bytes - payload) / payload);

        long t1 = System.nanoTime();
        Map<String, LogStore> open = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".lod"))::iterator) {
                open.put(p.getFileName().toString(), LogStore.open(p));
            }
        }
        double reopen = (System.nanoTime() - t1) / 1e6;
        List<long[]> shuffled = new ArrayList<>(keys);
        Collections.shuffle(shuffled, new Random(1));
        readAll("LogStore", shuffled, payload, key -> open.get(fileName(key)).get(key));
        System.out.printf(Locale.ROOT, "LogStore: reopen with snapshots %.0f ms%n", reopen);
        for (LogStore s : open.values()) {
            s.close();
        }
    }

    private void lmdb(List<long[]> keys, List<byte[]> data, long payload) throws Exception {
        Path dir = out.resolve("lmdb");
        Files.createDirectories(dir);
        long mapSize = Math.max(64L << 20, payload * 4);
        long t0 = System.nanoTime();
        try (Env<ByteBuffer> env = Env.create().setMapSize(mapSize).setMaxDbs(1).open(dir.toFile())) {
            Dbi<ByteBuffer> db = env.openDbi("lod", DbiFlags.MDB_CREATE);
            ByteBuffer k = ByteBuffer.allocateDirect(8).order(ByteOrder.BIG_ENDIAN);
            ByteBuffer v = ByteBuffer.allocateDirect(1 << 20);
            Txn<ByteBuffer> txn = env.txnWrite();
            for (int i = 0; i < keys.size(); i++) {
                k.clear();
                k.putLong(keys.get(i)[0]).flip();
                byte[] d = data.get(i);
                v.clear();
                v.put(d).flip();
                db.put(txn, k, v);
                if (i % 10_000 == 9_999) {
                    txn.commit();
                    txn.close();
                    txn = env.txnWrite();
                }
            }
            txn.commit();
            txn.close();
            env.sync(true);
            double write = (System.nanoTime() - t0) / 1e9;
            long used = env.info().lastPageNumber * env.stat().pageSize;
            System.out.printf(Locale.ROOT, "LMDB: write %.2f s (%.0f MB/s, one transaction per 10,000 records), %s MB used (%.1f%% overhead), file %s MB%n",
                    write, payload / write / 1e6, mb(used), 100.0 * (used - payload) / payload, mb(folderBytes(dir)));

            List<long[]> shuffled = new ArrayList<>(keys);
            Collections.shuffle(shuffled, new Random(1));
            ThreadLocal<ByteBuffer> keyBuf = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(8).order(ByteOrder.BIG_ENDIAN));
            readAll("LMDB", shuffled, payload, key -> {
                ByteBuffer kb = keyBuf.get();
                kb.clear();
                kb.putLong(key).flip();
                try (Txn<ByteBuffer> read = env.txnRead()) {
                    ByteBuffer value = db.get(read, kb);
                    byte[] copy = new byte[value.remaining()];
                    value.get(copy);
                    return copy;
                }
            });
        }
    }

    interface Reader {
        byte[] read(long key) throws Exception;
    }

    private void readAll(String name, List<long[]> keys, long payload, Reader reader) throws Exception {
        for (int t : new int[]{1, threads}) {
            long t0 = System.nanoTime();
            ExecutorService pool = Executors.newFixedThreadPool(t);
            List<Future<Long>> parts = new ArrayList<>();
            int per = (keys.size() + t - 1) / t;
            for (int p = 0; p < t; p++) {
                List<long[]> slice = keys.subList(Math.min(keys.size(), p * per), Math.min(keys.size(), (p + 1) * per));
                parts.add(pool.submit(() -> {
                    long bytes = 0;
                    for (long[] key : slice) {
                        bytes += reader.read(key[0]).length;
                    }
                    return bytes;
                }));
            }
            long bytes = 0;
            for (Future<Long> f : parts) {
                bytes += f.get();
            }
            pool.shutdown();
            double s = (System.nanoTime() - t0) / 1e9;
            if (bytes != payload) {
                throw new IllegalStateException(name + " read " + bytes + " bytes, wrote " + payload);
            }
            System.out.printf(Locale.ROOT, "%s: random reads, %d thread(s): %,.0f records/s, %.1f us per record%n",
                    name, t, keys.size() / s, s * 1e6 / keys.size() * t);
        }
    }

    static long folderBytes(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
