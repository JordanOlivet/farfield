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

    private static int create(long size) {
        int id = glCreateBuffers();
        glNamedBufferStorage(id, size, GL_DYNAMIC_STORAGE_BIT);
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

    /** Sets every byte to zero (new storage holds undefined data). */
    void clear() {
        glClearNamedBufferData(id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
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
