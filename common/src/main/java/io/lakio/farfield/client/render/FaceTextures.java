package io.lakio.farfield.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import io.lakio.farfield.Farfield;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Baked 16x16 face textures of LOD materials (ADR-010, near levels), in one
 * sRGB atlas with five mip levels built per tile, so sampling with nearest
 * filtering never bleeds into a neighbor tile. Identical tiles are stored
 * once. A CPU copy of every level is kept: the atlas grows by doubling its
 * height and uploading everything again, and new tiles upload the rows they
 * touch. Render thread only.
 */
final class FaceTextures {

    static final int TILE = 16;
    static final int LEVELS = 5;
    /** Tile indices are stored + 1 in 16 bits by {@link MaterialColors}. */
    static final int MAX_TILES = 0xFFFF - 1;
    private static final int WIDTH = 2048;
    private static final int TILES_PER_ROW = WIDTH / TILE;
    private static final int INITIAL_ROWS = 16;
    private static final int MAX_ROWS = MAX_TILES / TILES_PER_ROW + 1;
    private static final float[] LINEAR = new float[256];
    private static final byte[] SRGB = new byte[4096];

    static {
        for (int i = 0; i < 256; i++) {
            LINEAR[i] = (float) Math.pow(i / 255.0, 2.2);
        }
        for (int i = 0; i < SRGB.length; i++) {
            SRGB[i] = (byte) Math.round(Math.pow(i / (SRGB.length - 1.0), 1 / 2.2) * 255);
        }
    }

    private final Map<Key, Integer> tiles = new HashMap<>();
    private int count;
    private int rows;
    /** Per mip level, RGBA8 of the whole atlas (width >> level by rows * TILE >> level). */
    private byte[][] levels = new byte[0][];
    private int texture;
    private int textureRows;
    private int dirtyFirstRow = Integer.MAX_VALUE;
    private int dirtyLastRow = -1;
    private boolean full;

    private record Key(byte[] rgba) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Arrays.equals(rgba, k.rgba);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(rgba);
        }
    }

    /** Forgets every tile (resource reload, other world); the texture is kept and overwritten. */
    void reset() {
        tiles.clear();
        count = 0;
        dirtyFirstRow = Integer.MAX_VALUE;
        dirtyLastRow = -1;
    }

    int count() {
        return count;
    }

    /**
     * Adds a tile, or finds the same one.
     *
     * @param rgb   linear RGB per pixel ({@code TILE * TILE * 3}, row-major,
     *              row = V), not premultiplied
     * @param alpha coverage per pixel ({@code TILE * TILE})
     * @return the tile index, or -1 when the atlas is full
     */
    int add(float[] rgb, float[] alpha) {
        byte[] base = encode(rgb, alpha, TILE);
        Key key = new Key(base);
        Integer known = tiles.get(key);
        if (known != null) {
            return known;
        }
        if (count >= MAX_TILES) {
            return -1;
        }
        int index = count;
        int row = index / TILES_PER_ROW;
        if (row >= rows) {
            grow(Math.max(row + 1, rows == 0 ? INITIAL_ROWS : rows * 2));
        }
        int column = index % TILES_PER_ROW;
        write(0, column, row, base, TILE);
        // Mips: alpha-weighted box filter in linear space, per tile.
        float[] r = rgb;
        float[] a = alpha;
        for (int level = 1, size = TILE / 2; level < LEVELS; level++, size /= 2) {
            float[] nr = new float[size * size * 3];
            float[] na = new float[size * size];
            int src = size * 2;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float sa = 0;
                    float sr = 0;
                    float sg = 0;
                    float sb = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int i = (y * 2 + dy) * src + x * 2 + dx;
                            float w = a[i];
                            sa += w;
                            sr += r[i * 3] * w;
                            sg += r[i * 3 + 1] * w;
                            sb += r[i * 3 + 2] * w;
                        }
                    }
                    int o = y * size + x;
                    na[o] = sa / 4;
                    if (sa > 0) {
                        nr[o * 3] = sr / sa;
                        nr[o * 3 + 1] = sg / sa;
                        nr[o * 3 + 2] = sb / sa;
                    }
                }
            }
            write(level, column, row, encode(nr, na, size), size);
            r = nr;
            a = na;
        }
        tiles.put(key, index);
        count++;
        dirtyFirstRow = Math.min(dirtyFirstRow, row);
        dirtyLastRow = Math.max(dirtyLastRow, row);
        return index;
    }

    private void grow(int newRows) {
        newRows = Math.min(newRows, MAX_ROWS);
        byte[][] next = new byte[LEVELS][];
        for (int level = 0; level < LEVELS; level++) {
            int w = WIDTH >> level;
            int h = (newRows * TILE) >> level;
            next[level] = levels.length == 0 ? new byte[w * h * 4] : Arrays.copyOf(levels[level], w * h * 4);
        }
        levels = next;
        rows = newRows;
        full = true;
    }

    private void write(int level, int column, int row, byte[] tile, int size) {
        byte[] dst = levels[level];
        int stride = (WIDTH >> level) * 4;
        int x = column * size;
        int y = row * size;
        for (int ty = 0; ty < size; ty++) {
            System.arraycopy(tile, ty * size * 4, dst, (y + ty) * stride + x * 4, size * 4);
        }
    }

    private static byte[] encode(float[] rgb, float[] alpha, int size) {
        byte[] out = new byte[size * size * 4];
        for (int i = 0; i < size * size; i++) {
            out[i * 4] = srgb(rgb[i * 3]);
            out[i * 4 + 1] = srgb(rgb[i * 3 + 1]);
            out[i * 4 + 2] = srgb(rgb[i * 3 + 2]);
            out[i * 4 + 3] = (byte) Math.round(Math.max(0, Math.min(1, alpha[i])) * 255);
        }
        return out;
    }

    private static byte srgb(float linear) {
        return SRGB[Math.round(Math.max(0, Math.min(1, linear)) * (SRGB.length - 1))];
    }

    static float linear(int srgbByte) {
        return LINEAR[srgbByte & 0xFF];
    }

    /** Uploads new tiles (or the whole atlas after it grew); returns the GL texture, 0 if none yet. */
    int upload() {
        if (rows == 0) {
            return texture;
        }
        if (texture == 0 || textureRows != rows) {
            if (texture != 0) {
                GlStateManager._deleteTexture(texture);
            }
            texture = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(texture, LEVELS, GL_SRGB8_ALPHA8, WIDTH, rows * TILE);
            glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
            glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_MAX_LEVEL, LEVELS - 1);
            textureRows = rows;
            full = true;
            Farfield.LOG.info("LOD face texture atlas: {}x{} ({} tiles)", WIDTH, rows * TILE, count);
        }
        int first = full ? 0 : dirtyFirstRow;
        int last = full ? rows - 1 : dirtyLastRow;
        if (last < first) {
            return texture;
        }
        GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);
        for (int level = 0; level < LEVELS; level++) {
            int w = WIDTH >> level;
            int size = TILE >> level;
            int y = first * size;
            int h = (last - first + 1) * size;
            ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
            try {
                buffer.put(levels[level], y * w * 4, w * h * 4).flip();
                glTextureSubImage2D(texture, level, 0, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            } finally {
                MemoryUtil.memFree(buffer);
            }
        }
        full = false;
        dirtyFirstRow = Integer.MAX_VALUE;
        dirtyLastRow = -1;
        return texture;
    }

    int width() {
        return WIDTH;
    }

    int height() {
        return textureRows * TILE;
    }
}
