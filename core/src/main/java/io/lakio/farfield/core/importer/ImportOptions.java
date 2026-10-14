package io.lakio.farfield.core.importer;

import java.nio.file.Path;

/**
 * Settings of a region import.
 *
 * @param threads            worker threads (each decodes whole regions)
 * @param maxChunksPerSecond throttle across all workers; 0 for no limit
 * @param fullChunksOnly     skip chunks whose generation status is not
 *                           {@code full} (partially generated terrain)
 * @param checkpoint         file recording completed regions so an
 *                           interrupted import resumes; null to disable
 * @param centerRegionX      regions are processed nearest to this region first
 * @param centerRegionZ      see {@code centerRegionX}
 */
public record ImportOptions(
        int threads,
        double maxChunksPerSecond,
        boolean fullChunksOnly,
        Path checkpoint,
        int centerRegionX,
        int centerRegionZ) {

    public ImportOptions {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
        if (maxChunksPerSecond < 0) {
            throw new IllegalArgumentException("maxChunksPerSecond must not be negative");
        }
    }

    public static ImportOptions defaults() {
        return new ImportOptions(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), 0, true, null, 0, 0);
    }

    public ImportOptions withThreads(int value) {
        return new ImportOptions(value, maxChunksPerSecond, fullChunksOnly, checkpoint, centerRegionX, centerRegionZ);
    }

    public ImportOptions withMaxChunksPerSecond(double value) {
        return new ImportOptions(threads, value, fullChunksOnly, checkpoint, centerRegionX, centerRegionZ);
    }

    public ImportOptions withFullChunksOnly(boolean value) {
        return new ImportOptions(threads, maxChunksPerSecond, value, checkpoint, centerRegionX, centerRegionZ);
    }

    public ImportOptions withCheckpoint(Path value) {
        return new ImportOptions(threads, maxChunksPerSecond, fullChunksOnly, value, centerRegionX, centerRegionZ);
    }

    public ImportOptions withCenterRegion(int x, int z) {
        return new ImportOptions(threads, maxChunksPerSecond, fullChunksOnly, checkpoint, x, z);
    }
}
