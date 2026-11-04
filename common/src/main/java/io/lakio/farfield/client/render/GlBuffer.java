package io.lakio.farfield.client.render;

import static org.lwjgl.opengl.GL45C.*;

/**
 * An immutable-storage OpenGL buffer (direct state access, GL 4.5), updated
 * with {@code glNamedBufferSubData}. Part of the minimal OpenGL 4.6 RHI
 * (plan item E.3). Render thread only.
 */
final class GlBuffer implements AutoCloseable {

    private int id;
    private long size;

    GlBuffer(long size) {
        this.size = size;
        this.id = create(size);
    }

    /**
     * A new store, filled with zeros: storage made without data holds
     * whatever the driver had there. A buffer the shaders read before its
     * first upload (the preview's hidden columns, uploaded at the first
     * hand-over) then hid the preview's water over random chunk columns:
     * dark squares on the sea, in some runs only (2026-11-04).
     */
    private static int create(long size) {
        int id = glCreateBuffers();
        glNamedBufferStorage(id, size, GL_DYNAMIC_STORAGE_BIT);
        glClearNamedBufferData(id, GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        return id;
    }

    int id() {
        return id;
    }

    long size() {
        return size;
    }

    void upload(long offset, long[] data, int count) {
        if (count == data.length) {
            glNamedBufferSubData(id, offset, data);
        } else {
            long[] slice = java.util.Arrays.copyOf(data, count);
            glNamedBufferSubData(id, offset, slice);
        }
    }

    void upload(long offset, int[] data) {
        glNamedBufferSubData(id, offset, data);
    }

    /** Uploads the buffer's remaining ints (position to limit). */
    void upload(long offset, java.nio.IntBuffer data) {
        glNamedBufferSubData(id, offset, data);
    }

    /** Reallocates to {@code newSize} bytes, keeping the first {@code keep} bytes. */
    void grow(long newSize, long keep) {
        if (newSize <= size) {
            return;
        }
        int bigger = create(newSize);
        if (keep > 0) {
            glCopyNamedBufferSubData(id, bigger, 0, 0, keep);
        }
        glDeleteBuffers(id);
        id = bigger;
        size = newSize;
    }

    @Override
    public void close() {
        if (id != 0) {
            glDeleteBuffers(id);
            id = 0;
        }
    }
}
