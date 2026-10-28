package io.lakio.farfield.core.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The block looks a client baked, kept on disk across sessions (ADR-078):
 * baking the 32,000 block states of a large pack took 22 s at every join,
 * and nothing was drawn meanwhile. One file for every world and server,
 * valid for one {@link #fingerprint} (the bake's version, the mods and
 * their versions, the resource packs); another fingerprint starts empty.
 *
 * <pre>
 *   file: magic "FFLK" | format | fingerprint (8) | tile count | tiles ({@link #TILE_BYTES} each)
 *         | look count | per look: key (UTF) | tinted (1) | {@link #WORDS} ints | 12 tile indices + 1 (0: none)
 * </pre>
 *
 * Read and filled by one thread (the render thread); the file is written on
 * a thread of its own from a copy.
 */
public final class LooksCache {

    /** The face words of a look: per face an untinted color, a tinted color, its tiles. */
    public static final int WORDS = 18;
    /** A face tile: 16 x 16 RGBA8. */
    public static final int TILE_BYTES = 16 * 16 * 4;
    private static final int MAGIC = 0x4B4C4646; // "FFLK"
    private static final int FORMAT = 1;

    /** How {@link #open} found the file. */
    public enum Status { READ, MISSING, OTHER_FINGERPRINT, UNREADABLE }

    /**
     * A baked look: its face words (tile indices are the atlas's, the cache
     * keeps the tiles themselves) and per face its untinted and tinted tile,
     * or null.
     */
    public record Look(boolean tinted, int[] words, byte[][] tiles) {
    }

    private final Path file;
    private final long fingerprint;
    private final Map<String, Look> looks = new ConcurrentHashMap<>();
    private final AtomicBoolean writing = new AtomicBoolean();
    private volatile boolean dirty;
    private Status status = Status.MISSING;
    private int tilesRead;

    private LooksCache(Path file, long fingerprint) {
        this.file = file;
        this.fingerprint = fingerprint;
    }

    /**
     * What a cache is valid for: {@code bakeVersion} (raised whenever looks
     * are baked differently), the loaded mods and the resource packs, in order.
     */
    public static long fingerprint(int bakeVersion, List<String> mods, List<String> resourcePacks) {
        long h = 0xCBF29CE484222325L ^ bakeVersion;
        for (List<String> list : List.of(mods, List.of("|"), resourcePacks)) {
            for (String s : list) {
                for (int i = 0; i < s.length(); i++) {
                    h = (h ^ s.charAt(i)) * 0x100000001B3L;
                }
                h = (h ^ 0x1F) * 0x100000001B3L;
            }
        }
        return h;
    }

    /** The cache in {@code file} if it was written for {@code fingerprint}, else an empty one there ({@link #status}). */
    public static LooksCache open(Path file, long fingerprint) {
        LooksCache cache = new LooksCache(file, fingerprint);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT || in.readLong() != fingerprint) {
                cache.status = Status.OTHER_FINGERPRINT;
                return cache;
            }
            int tileCount = in.readInt();
            byte[][] tiles = new byte[tileCount][];
            for (int i = 0; i < tileCount; i++) {
                tiles[i] = new byte[TILE_BYTES];
                in.readFully(tiles[i]);
            }
            int lookCount = in.readInt();
            for (int i = 0; i < lookCount; i++) {
                String key = in.readUTF();
                boolean tinted = in.readBoolean();
                int[] words = new int[WORDS];
                for (int w = 0; w < WORDS; w++) {
                    words[w] = in.readInt();
                }
                byte[][] faces = new byte[12][];
                for (int t = 0; t < 12; t++) {
                    int index = in.readInt() - 1;
                    faces[t] = index < 0 || index >= tileCount ? null : tiles[index];
                }
                cache.looks.put(key, new Look(tinted, words, faces));
            }
            cache.status = Status.READ;
            cache.tilesRead = tileCount;
        } catch (NoSuchFileException e) {
            cache.status = Status.MISSING;
        } catch (IOException | RuntimeException e) {
            cache.status = Status.UNREADABLE;
            cache.looks.clear();
        }
        return cache;
    }

    public Status status() {
        return status;
    }

    /** Tiles the file held when read. */
    public int tilesRead() {
        return tilesRead;
    }

    public Look get(String key) {
        return looks.get(key);
    }

    /** A look baked this session. */
    public void put(String key, Look look) {
        looks.put(key, look);
        dirty = true;
    }

    public int size() {
        return looks.size();
    }

    /**
     * Writes the cache when looks were baked since it was read or last
     * written, on a thread of its own from a copy; {@code done} hears of
     * the outcome there ({@code null} on success, else what failed).
     */
    public void saveIfChanged(Consumer<Exception> done) {
        if (!dirty || !writing.compareAndSet(false, true)) {
            return;
        }
        dirty = false;
        Map<String, Look> copy = new HashMap<>(looks);
        Thread t = new Thread(() -> {
            Exception failure = null;
            try {
                write(copy);
            } catch (IOException | RuntimeException e) {
                failure = e;
                dirty = true;
            } finally {
                writing.set(false);
            }
            done.accept(failure);
        }, "farfield-looks-cache");
        t.setDaemon(true);
        t.start();
    }

    /** Writes the cache now, on the calling thread. */
    public void save() throws IOException {
        write(new HashMap<>(looks));
        dirty = false;
    }

    private void write(Map<String, Look> copy) throws IOException {
        Map<TileKey, Integer> tileIndex = new HashMap<>();
        List<byte[]> tiles = new ArrayList<>();
        for (Look look : copy.values()) {
            for (byte[] tile : look.tiles()) {
                if (tile != null && tileIndex.putIfAbsent(new TileKey(tile), tiles.size()) == null) {
                    tiles.add(tile);
                }
            }
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(fingerprint);
            out.writeInt(tiles.size());
            for (byte[] tile : tiles) {
                out.write(tile);
            }
            out.writeInt(copy.size());
            for (Map.Entry<String, Look> e : copy.entrySet()) {
                Look look = e.getValue();
                out.writeUTF(e.getKey());
                out.writeBoolean(look.tinted());
                for (int w : look.words()) {
                    out.writeInt(w);
                }
                for (byte[] tile : look.tiles()) {
                    out.writeInt(tile == null ? 0 : tileIndex.get(new TileKey(tile)) + 1);
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private record TileKey(byte[] rgba) {
        @Override
        public boolean equals(Object o) {
            return o instanceof TileKey k && Arrays.equals(rgba, k.rgba);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(rgba);
        }
    }
}
