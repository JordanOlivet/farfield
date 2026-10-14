package io.lakio.farfield.core.importer;

import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.ChunkDecoder;
import io.lakio.farfield.core.anvil.ChunkDecompressor;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.anvil.RegionFile;
import io.lakio.farfield.core.util.GrowableBytes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Imports every chunk of a dimension's region folder: reads the region files
 * directly (no server, no chunk loading), decodes the chunks on worker
 * threads and hands them to a {@link ChunkSink}.
 *
 * <ul>
 *   <li><b>Parallel</b>: each worker takes the next region file, nearest to
 *       the configured center first, and processes its chunks in 2x2
 *       groups (one 32-block LOD column after another).</li>
 *   <li><b>Backpressure</b>: the sink is called on the worker thread; a slow
 *       sink stalls the workers, so memory stays bounded by one region's
 *       worth of buffers per worker.</li>
 *   <li><b>Throttled</b>: optional chunks-per-second limit shared by all
 *       workers; {@link #pause()} / {@link #resume()} at chunk granularity.</li>
 *   <li><b>Resumable</b>: completed regions go to an {@link ImportCheckpoint};
 *       a cancelled or crashed import restarts at the regions not recorded
 *       there.</li>
 * </ul>
 *
 * <p>One instance runs one import. Corrupt chunks and unreadable regions are
 * counted and reported to the {@link ImportListener}; the import goes on. An
 * exception thrown by the sink aborts the import and is rethrown by
 * {@link #run}.
 */
public final class RegionImporter {

    private final ImportOptions options;
    private final ChunkSink sink;
    private final ImportListener listener;
    private final KeyRegistry blockStates;
    private final KeyRegistry biomes;
    private final ImportStats stats = new ImportStats();
    private final RateLimiter limiter;
    private final Object pauseLock = new Object();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private volatile boolean paused;
    private volatile boolean cancelled;

    /**
     * Chunk slots in 2x2 groups: the four chunks of one 32-block LOD column
     * reach the sink one after another, so it can merge them before writing.
     */
    private static final int[] CHUNK_ORDER = new int[RegionFile.CHUNK_COUNT];

    static {
        int slot = 0;
        for (int gz = 0; gz < 16; gz++) {
            for (int gx = 0; gx < 16; gx++) {
                for (int i = 0; i < 4; i++) {
                    CHUNK_ORDER[slot++] = (gz * 2 + (i >> 1)) * 32 + gx * 2 + (i & 1);
                }
            }
        }
    }

    public RegionImporter(ImportOptions options, KeyRegistry blockStates, KeyRegistry biomes,
                          ChunkSink sink, ImportListener listener) {
        this.options = options;
        this.blockStates = blockStates;
        this.biomes = biomes;
        this.sink = sink;
        this.listener = listener;
        this.limiter = options.maxChunksPerSecond() > 0 ? new RateLimiter(options.maxChunksPerSecond()) : null;
    }

    public ImportStats stats() {
        return stats;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    /** Stops the workers at their next chunk; the current regions are not marked complete. */
    public void cancel() {
        cancelled = true;
        resume();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Region files of a folder, nearest to {@code (centerX, centerZ)} (in regions) first. */
    public static List<Path> listRegions(Path regionDir, int centerX, int centerZ) throws IOException {
        List<Path> regions = new ArrayList<>();
        try (Stream<Path> files = Files.list(regionDir)) {
            files.filter(RegionFile::isRegionFile).forEach(regions::add);
        }
        regions.sort(Comparator.<Path>comparingLong(p -> {
            int[] c = RegionFile.parseName(p.getFileName().toString());
            long dx = c[0] - (long) centerX;
            long dz = c[1] - (long) centerZ;
            return dx * dx + dz * dz;
        }).thenComparing(p -> p.getFileName().toString()));
        return regions;
    }

    /**
     * Imports the region folder and blocks until done, cancelled or failed.
     *
     * @return the final counters
     * @throws ImportFailedException if the sink threw
     */
    public ImportStats run(Path regionDir) throws IOException, InterruptedException {
        return run(listRegions(regionDir, options.centerRegionX(), options.centerRegionZ()));
    }

    /** Imports the given region files (processed in list order). */
    public ImportStats run(List<Path> regions) throws IOException, InterruptedException {
        ConcurrentLinkedQueue<Path> queue = new ConcurrentLinkedQueue<>(regions);
        try (ImportCheckpoint checkpoint = options.checkpoint() != null ? ImportCheckpoint.open(options.checkpoint()) : null) {
            int threads = Math.min(options.threads(), Math.max(1, regions.size()));
            List<Thread> workers = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> work(queue, checkpoint), "farfield-import-" + i);
                t.setDaemon(true);
                workers.add(t);
                t.start();
            }
            try {
                for (Thread t : workers) {
                    t.join();
                }
            } catch (InterruptedException e) {
                cancel();
                for (Thread t : workers) {
                    t.join();
                }
                throw e;
            }
        }
        Throwable error = failure.get();
        if (error != null) {
            throw new ImportFailedException("chunk sink failed", error);
        }
        return stats;
    }

    private void work(ConcurrentLinkedQueue<Path> queue, ImportCheckpoint checkpoint) {
        RegionFile.RawChunk raw = new RegionFile.RawChunk();
        GrowableBytes nbt = new GrowableBytes(256 * 1024);
        ChunkData chunk = new ChunkData();
        ChunkDecoder decoder = new ChunkDecoder(blockStates, biomes);
        try (ChunkDecompressor decompressor = new ChunkDecompressor()) {
            Path region;
            while (!cancelled && (region = queue.poll()) != null) {
                if (checkpoint != null && isDone(checkpoint, region)) {
                    stats.regionsSkipped.increment();
                    continue;
                }
                int imported;
                try (RegionFile file = RegionFile.open(region)) {
                    imported = importRegion(file, raw, nbt, chunk, decoder, decompressor);
                } catch (ImportFailedException e) {
                    throw e;
                } catch (IOException | RuntimeException e) {
                    stats.regionsFailed.increment();
                    listener.regionFailed(region, e);
                    continue;
                }
                if (imported < 0) {
                    return;
                }
                if (checkpoint != null) {
                    checkpoint.markDone(region);
                }
                stats.regionsDone.increment();
                listener.regionDone(region, imported);
            }
        } catch (InterruptedException e) {
            cancelled = true;
        } catch (ImportFailedException e) {
            failure.compareAndSet(null, e.getCause());
            cancelled = true;
        } catch (IOException e) {
            // The checkpoint could not be written: stop rather than lose track.
            failure.compareAndSet(null, new UncheckedIOException(e));
            cancelled = true;
        }
    }

    /** Returns the number of chunks handed to the sink, or -1 if cancelled midway. */
    private int importRegion(RegionFile file, RegionFile.RawChunk raw, GrowableBytes nbt, ChunkData chunk,
                             ChunkDecoder decoder, ChunkDecompressor decompressor) throws InterruptedException {
        int imported = 0;
        for (int slot = 0; slot < RegionFile.CHUNK_COUNT; slot++) {
            int index = CHUNK_ORDER[slot];
            if (!file.hasChunk(index)) {
                continue;
            }
            if (paused) {
                awaitResume();
            }
            if (cancelled) {
                return -1;
            }
            if (limiter != null) {
                stats.throttleNanos.add(limiter.acquire());
            }
            int chunkX = file.chunkX(index);
            int chunkZ = file.chunkZ(index);
            try {
                long t0 = System.nanoTime();
                if (!file.read(index, raw)) {
                    continue;
                }
                long t1 = System.nanoTime();
                stats.chunksRead.increment();
                stats.compressedBytes.add(raw.length);
                ImportStats.increment(stats.compressions, raw.compression);
                if (raw.external) {
                    stats.chunksExternal.increment();
                }
                decompressor.decompress(raw.compression, raw.bytes.data(), raw.offset, raw.length, nbt);
                long t2 = System.nanoTime();
                stats.nbtBytes.add(nbt.length());
                boolean supported = decoder.decodeHeader(nbt.data(), 0, nbt.length(), chunkX, chunkZ, chunk);
                ImportStats.increment(stats.dataVersions, chunk.dataVersion());
                if (!supported) {
                    stats.chunksLegacy.increment();
                    stats.readNanos.add(t1 - t0);
                    stats.decompressNanos.add(t2 - t1);
                    continue;
                }
                ImportStats.increment(stats.statuses, chunk.status());
                if (options.fullChunksOnly() && !chunk.isFull()) {
                    stats.chunksSkippedStatus.increment();
                    stats.readNanos.add(t1 - t0);
                    stats.decompressNanos.add(t2 - t1);
                    continue;
                }
                decoder.decodeSections(chunk);
                long t3 = System.nanoTime();
                stats.readNanos.add(t1 - t0);
                stats.decompressNanos.add(t2 - t1);
                stats.decodeNanos.add(t3 - t2);
                stats.sections.add(chunk.sectionCount());
                stats.countWarnings(chunk.warnings());
            } catch (IOException | RuntimeException e) {
                // RuntimeException too: odd modded data must never stop an import.
                stats.chunksFailed.increment();
                listener.chunkFailed(file.path(), chunkX, chunkZ, e);
                continue;
            }
            long t4 = System.nanoTime();
            try {
                sink.accept(chunk);
            } catch (InterruptedException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ImportFailedException("chunk sink failed", e);
            }
            stats.sinkNanos.add(System.nanoTime() - t4);
            stats.chunksImported.increment();
            imported++;
        }
        return imported;
    }

    private static boolean isDone(ImportCheckpoint checkpoint, Path region) {
        try {
            return checkpoint.isDone(region);
        } catch (IOException e) {
            // The file vanished or cannot be read: opening it reports the error.
            return false;
        }
    }

    private void awaitResume() throws InterruptedException {
        synchronized (pauseLock) {
            while (paused && !cancelled) {
                pauseLock.wait();
            }
        }
    }

    /** The sink threw: the import stopped. The cause is the sink's exception. */
    public static final class ImportFailedException extends RuntimeException {
        public ImportFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
