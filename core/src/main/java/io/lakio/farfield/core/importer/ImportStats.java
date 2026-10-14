package io.lakio.farfield.core.importer;

import io.lakio.farfield.core.anvil.ChunkData;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Live counters of an import, updated by the workers and readable at any
 * time (each counter is individually consistent, not the set as a whole).
 */
public final class ImportStats {

    final LongAdder regionsDone = new LongAdder();
    final LongAdder regionsSkipped = new LongAdder();
    final LongAdder regionsFailed = new LongAdder();
    final LongAdder chunksRead = new LongAdder();
    final LongAdder chunksImported = new LongAdder();
    final LongAdder chunksSkippedStatus = new LongAdder();
    final LongAdder chunksLegacy = new LongAdder();
    final LongAdder chunksFailed = new LongAdder();
    final LongAdder chunksExternal = new LongAdder();
    final LongAdder sections = new LongAdder();
    final LongAdder compressedBytes = new LongAdder();
    final LongAdder nbtBytes = new LongAdder();
    final LongAdder readNanos = new LongAdder();
    final LongAdder decompressNanos = new LongAdder();
    final LongAdder decodeNanos = new LongAdder();
    final LongAdder sinkNanos = new LongAdder();
    final LongAdder throttleNanos = new LongAdder();
    final ConcurrentHashMap<String, LongAdder> statuses = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, LongAdder> dataVersions = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, LongAdder> compressions = new ConcurrentHashMap<>();
    final LongAdder[] warnings = new LongAdder[5];

    ImportStats() {
        for (int i = 0; i < warnings.length; i++) {
            warnings[i] = new LongAdder();
        }
    }

    void countWarnings(int flags) {
        for (int i = 0; i < warnings.length; i++) {
            if ((flags & (1 << i)) != 0) {
                warnings[i].increment();
            }
        }
    }

    static <K> void increment(ConcurrentHashMap<K, LongAdder> map, K key) {
        map.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public long regionsDone() {
        return regionsDone.sum();
    }

    /** Regions skipped because the checkpoint marks them complete. */
    public long regionsSkipped() {
        return regionsSkipped.sum();
    }

    public long regionsFailed() {
        return regionsFailed.sum();
    }

    /** Chunk payloads read from region files (imported, skipped or failed). */
    public long chunksRead() {
        return chunksRead.sum();
    }

    /** Chunks decoded and handed to the sink. */
    public long chunksImported() {
        return chunksImported.sum();
    }

    public long chunksSkippedStatus() {
        return chunksSkippedStatus.sum();
    }

    /** Chunks in a pre-1.18 format, not supported. */
    public long chunksLegacy() {
        return chunksLegacy.sum();
    }

    public long chunksFailed() {
        return chunksFailed.sum();
    }

    public long chunksExternal() {
        return chunksExternal.sum();
    }

    public long sections() {
        return sections.sum();
    }

    public long compressedBytes() {
        return compressedBytes.sum();
    }

    public long nbtBytes() {
        return nbtBytes.sum();
    }

    /** Worker time per stage, summed over all workers, in nanoseconds. */
    public long readNanos() {
        return readNanos.sum();
    }

    public long decompressNanos() {
        return decompressNanos.sum();
    }

    public long decodeNanos() {
        return decodeNanos.sum();
    }

    public long sinkNanos() {
        return sinkNanos.sum();
    }

    public long throttleNanos() {
        return throttleNanos.sum();
    }

    /** Chunks per stored generation status (every chunk read and decoded far enough). */
    public Map<String, Long> statuses() {
        return snapshot(statuses);
    }

    public Map<Integer, Long> dataVersions() {
        return snapshot(dataVersions);
    }

    /** Chunks per compression type (see {@code ChunkDecompressor}). */
    public Map<Integer, Long> compressions() {
        return snapshot(compressions);
    }

    /** Chunks that raised the given {@code ChunkData.WARN_*} flag. */
    public long warnings(int flag) {
        int bit = Integer.numberOfTrailingZeros(flag);
        return bit < warnings.length ? warnings[bit].sum() : 0;
    }

    private static <K extends Comparable<K>> Map<K, Long> snapshot(ConcurrentHashMap<K, LongAdder> map) {
        TreeMap<K, Long> copy = new TreeMap<>();
        map.forEach((k, v) -> copy.put(k, v.sum()));
        return copy;
    }

    @Override
    public String toString() {
        return "regions done=" + regionsDone() + " skipped=" + regionsSkipped() + " failed=" + regionsFailed()
                + ", chunks read=" + chunksRead() + " imported=" + chunksImported()
                + " skippedStatus=" + chunksSkippedStatus() + " legacy=" + chunksLegacy()
                + " failed=" + chunksFailed() + " external=" + chunksExternal()
                + ", warnings misplaced=" + warnings(ChunkData.WARN_MISPLACED)
                + " badIndex=" + warnings(ChunkData.WARN_BAD_INDEX)
                + " badDataLength=" + warnings(ChunkData.WARN_BAD_DATA_LENGTH)
                + " badLight=" + warnings(ChunkData.WARN_BAD_LIGHT)
                + " missingPalette=" + warnings(ChunkData.WARN_MISSING_PALETTE);
    }
}
