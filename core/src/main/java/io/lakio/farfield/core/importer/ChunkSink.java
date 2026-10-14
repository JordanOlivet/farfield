package io.lakio.farfield.core.importer;

import io.lakio.farfield.core.anvil.ChunkData;

/**
 * Receives decoded chunks from the import workers.
 *
 * <p>Called concurrently from every worker thread, so implementations must be
 * thread-safe. The {@link ChunkData} is the worker's reusable holder: it is
 * only valid during the call; copy what must outlive it. Blocking in
 * {@link #accept} is the backpressure mechanism: the worker reads nothing
 * more until the sink returns.
 *
 * <p>An interrupted import resumes at the start of the region it stopped in,
 * so a sink may see a chunk twice and must treat a chunk as a replacement,
 * not an addition.
 */
@FunctionalInterface
public interface ChunkSink {

    void accept(ChunkData chunk) throws InterruptedException;
}
