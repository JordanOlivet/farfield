package io.lakio.farfield.client.render;

import static org.lwjgl.opengl.GL45C.*;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import org.lwjgl.system.MemoryUtil;

/**
 * A persistently mapped upload ring (ADR-149): data is written into mapped
 * memory, then copied on the GPU into its buffer, so the render thread never
 * waits on the driver. {@code glNamedBufferSubData} into the quad buffer
 * made the frame wait 14 to 33 ms a few times a minute in flight, one mesh
 * each time. The writes of a frame are fenced at its end; a span is written
 * again only once the GPU passed its fence. A copy that finds no free span
 * never waits: it returns false, and the caller sends the data directly.
 * Render thread only.
 */
final class StagingRing implements AutoCloseable {

    private int id;
    private final long size;
    private final long address;
    /** Next write offset. */
    private long head;
    /** The start of this frame's writes, -1 before the first. */
    private long frameStart = -1;
    /** Earlier frames' writes still read by the GPU: start offset, fence. */
    private final ArrayDeque<long[]> spans = new ArrayDeque<>();
    /** Bytes copied through the ring, and copies left to the direct path, since the last {@link #takeCounts}. */
    private long copiedBytes;
    private long missed;

    StagingRing(long size) {
        this.size = size;
        id = glCreateBuffers();
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        glNamedBufferStorage(id, size, flags);
        ByteBuffer mapped = glMapNamedBufferRange(id, 0, size, flags);
        if (mapped == null) {
            glDeleteBuffers(id);
            id = 0;
            throw new IllegalStateException("cannot map the LOD upload ring");
        }
        address = MemoryUtil.memAddress(mapped);
    }

    /**
     * Copies the first {@code count} longs of {@code data} into {@code target}
     * at byte {@code targetOffset}; false when the ring has no free span for
     * them now (nothing written, the caller sends them directly).
     */
    boolean copy(long[] data, int count, GlBuffer target, long targetOffset) {
        long bytes = (long) count * 8;
        if (bytes == 0) {
            return true;
        }
        long at = bytes > size / 4 ? -1 : reserve(bytes);
        if (at < 0) {
            missed++;
            return false;
        }
        MemoryUtil.memLongBuffer(address + at, count).put(data, 0, count);
        glCopyNamedBufferSubData(id, target.id(), at, targetOffset, bytes);
        copiedBytes += bytes;
        return true;
    }

    /** Fences this frame's writes: they are written again once the GPU passed this point. */
    void endFrame() {
        if (frameStart >= 0) {
            spans.addLast(new long[]{frameStart, glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)});
            frameStart = -1;
        }
    }

    /** Bytes copied through the ring and copies sent directly since the last call. */
    long[] takeCounts() {
        long[] counts = {copiedBytes, missed};
        copiedBytes = 0;
        missed = 0;
        return counts;
    }

    /** A free span of {@code bytes} in ring order after the spans in use, or -1. */
    private long reserve(long bytes) {
        reclaim();
        long tail = !spans.isEmpty() ? spans.peekFirst()[0] : frameStart;
        long at;
        if (tail < 0) {
            at = 0; // nothing in use: start over at the beginning
        } else if (head > tail) {
            if (head + bytes <= size) {
                at = head;
            } else if (bytes < tail) {
                at = 0;
            } else {
                return -1;
            }
        } else if (head + bytes < tail) {
            at = head; // wrapped: up to the oldest span in use, never onto it
        } else {
            return -1;
        }
        if (frameStart < 0) {
            frameStart = at;
        }
        head = at + bytes;
        return at;
    }

    /** Frees the spans the GPU has passed, without waiting for any. */
    private void reclaim() {
        while (!spans.isEmpty()) {
            long fence = spans.peekFirst()[1];
            int state = glClientWaitSync(fence, 0, 0);
            if (state != GL_ALREADY_SIGNALED && state != GL_CONDITION_SATISFIED) {
                return;
            }
            glDeleteSync(fence);
            spans.pollFirst();
        }
    }

    @Override
    public void close() {
        for (long[] span : spans) {
            glDeleteSync(span[1]);
        }
        spans.clear();
        if (id != 0) {
            glUnmapNamedBuffer(id);
            glDeleteBuffers(id);
            id = 0;
        }
    }
}
