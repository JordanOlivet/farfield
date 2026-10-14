package io.lakio.farfield.core.importer;

import java.nio.file.Path;

/**
 * Progress and error callbacks of an import, called from the worker threads
 * (implementations must be thread-safe). The engine core does not log; the
 * Minecraft side forwards these to its logger.
 */
public interface ImportListener {

    ImportListener NONE = new ImportListener() {
    };

    /** A region was fully processed and recorded in the checkpoint. */
    default void regionDone(Path region, int chunks) {
    }

    /** A region file could not be opened or read at all. */
    default void regionFailed(Path region, Throwable error) {
    }

    /** One chunk could not be read or decoded; the import goes on. */
    default void chunkFailed(Path region, int chunkX, int chunkZ, Throwable error) {
    }
}
