package io.lakio.farfield.core.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The block looks a client baked, kept on disk across sessions (ADR-078):
 * baking the 32,000 block states of a large pack took 22 s at every join,
 * and nothing was drawn meanwhile. One file for every world and server,
 * valid for one {@link #fingerprint} (the bake's version, the mods and
 * their versions, the resource packs); another fingerprint starts it over.
 *
 * <p>Append only (ADR-081): exploring brings a few new block states at a
 * time, and rewriting the whole 39 MB file for each wrote gigabytes in a
 * session. The file is a header, then records in the order they were
 * baked: a face tile, or a look naming its tiles by their place among the
 * tiles before it.
 *
 * <pre>
 *   header: magic "FFLK" | format | fingerprint (8)
 *   record: 1 | tile ({@link #TILE_BYTES})
 *         | 2 | key (UTF) | tinted (1) | {@link #WORDS} ints | 12 tile indices + 1 (0: none)
 * </pre>
 *
 * A record cut short (a crash while appending) is dropped at open, and the
 * next append starts where the last whole record ends. Looks are read and
 * put by one thread (the render thread); appends run on a thread of their own.
 */
public final class LooksCache {

    /** The face words of a look: per face an untinted color, a tinted color, its tiles. */
    public static final int WORDS = 18;
    /** A face tile: 16 x 16 RGBA8. */
    public static final int TILE_BYTES = 16 * 16 * 4;
    private static final int MAGIC = 0x4B4C4646; // "FFLK"
    private static final int FORMAT = 2;
    private static final int HEADER = 16;
    private static final int TILE = 1;
    private static final int LOOK = 2;

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
    /** Looks put since the last append, in order. */
    private final ConcurrentLinkedQueue<Map.Entry<String, Look>> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean writing = new AtomicBoolean();
    /** The file's tiles and their place, for the appending thread; replaced once an append is written. */
    private Map<TileKey, Integer> tileIndex = new HashMap<>();
    /** Where the last whole record ends; -1: the file is written again from its header. */
    private long end = -1;
    private Status status = Status.MISSING;
    private int tilesRead;
    private int recordsDropped;

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

    /**
     * The cache in {@code file} if it was written for {@code fingerprint},
     * else an empty one there ({@link #status}), which starts the file over
     * at its first append.
     */
    public static LooksCache open(Path file, long fingerprint) {
        LooksCache cache = new LooksCache(file, fingerprint);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return cache;
        } catch (IOException | RuntimeException e) {
            cache.status = Status.UNREADABLE;
            return cache;
        }
        ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
        DataInputStream in = new DataInputStream(raw);
        try {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) {
                cache.status = Status.UNREADABLE;
                return cache;
            }
            if (in.readLong() != fingerprint) {
                cache.status = Status.OTHER_FINGERPRINT;
                return cache;
            }
        } catch (IOException e) {
            cache.status = Status.UNREADABLE;
            return cache;
        }
        List<byte[]> tiles = new ArrayList<>();
        long end = HEADER;
        while (raw.available() > 0) {
            try {
                int tag = in.readUnsignedByte();
                if (tag == TILE) {
                    byte[] tile = new byte[TILE_BYTES];
                    in.readFully(tile);
                    cache.tileIndex.put(new TileKey(tile), tiles.size());
                    tiles.add(tile);
                } else if (tag == LOOK) {
                    String key = in.readUTF();
                    boolean tinted = in.readBoolean();
                    int[] words = new int[WORDS];
                    for (int w = 0; w < WORDS; w++) {
                        words[w] = in.readInt();
                    }
                    byte[][] faces = new byte[12][];
                    for (int t = 0; t < 12; t++) {
                        int index = in.readInt() - 1;
                        if (index >= tiles.size()) {
                            throw new EOFException("tile " + index + " not written before its look");
                        }
                        faces[t] = index < 0 ? null : tiles.get(index);
                    }
                    cache.looks.put(key, new Look(tinted, words, faces));
                } else {
                    throw new EOFException("record type " + tag);
                }
                end = bytes.length - raw.available();
            } catch (IOException e) {
                // A record cut short or damaged: the file is whole up to the last good one.
                cache.recordsDropped++;
                break;
            }
        }
        cache.end = end;
        cache.status = Status.READ;
        cache.tilesRead = tiles.size();
        return cache;
    }

    public Status status() {
        return status;
    }

    /** Tiles the file held when read. */
    public int tilesRead() {
        return tilesRead;
    }

    /** Records dropped at open, a tail cut short (0 or 1). */
    public int recordsDropped() {
        return recordsDropped;
    }

    public Look get(String key) {
        return looks.get(key);
    }

    /** A look baked this session, appended at the next {@link #appendPending}. */
    public void put(String key, Look look) {
        looks.put(key, look);
        pending.add(Map.entry(key, look));
    }

    public int size() {
        return looks.size();
    }

    /** Whether looks wait to be appended. */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * Appends the looks put since the last append, on a thread of its own;
     * {@code done} hears of the outcome there: the looks appended, or what
     * failed. Nothing when none wait or an append runs.
     */
    public void appendPending(Consumer<Object> done) {
        if (pending.isEmpty() || !writing.compareAndSet(false, true)) {
            return;
        }
        List<Map.Entry<String, Look>> batch = drain();
        Thread t = new Thread(() -> {
            Object outcome;
            try {
                append(batch);
                outcome = batch.size();
            } catch (IOException | RuntimeException e) {
                pending.addAll(batch); // tried again at the next append
                outcome = e;
            } finally {
                writing.set(false);
            }
            done.accept(outcome);
        }, "farfield-looks-cache");
        t.setDaemon(true);
        t.start();
    }

    /** Appends the looks put since the last append now, on the calling thread. */
    public void save() throws IOException {
        append(drain());
    }

    private List<Map.Entry<String, Look>> drain() {
        List<Map.Entry<String, Look>> batch = new ArrayList<>();
        Map.Entry<String, Look> e;
        while ((e = pending.poll()) != null) {
            batch.add(e);
        }
        return batch;
    }

    private synchronized void append(List<Map.Entry<String, Look>> batch) throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        DataOutputStream out = new DataOutputStream(bytes);
        boolean over = end < 0;
        // A copy: a failed append must not leave tiles counted that the file never got.
        Map<TileKey, Integer> index = over ? new HashMap<>() : new HashMap<>(tileIndex);
        if (over) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(fingerprint);
        }
        int tiles = index.size();
        for (Map.Entry<String, Look> e : batch) {
            Look look = e.getValue();
            int[] refs = new int[12];
            for (int t = 0; t < 12; t++) {
                byte[] tile = look.tiles()[t];
                if (tile == null) {
                    continue;
                }
                Integer place = index.get(new TileKey(tile));
                if (place == null) {
                    place = tiles++;
                    index.put(new TileKey(tile), place);
                    out.writeByte(TILE);
                    out.write(tile);
                }
                refs[t] = place + 1;
            }
            out.writeByte(LOOK);
            out.writeUTF(e.getKey());
            out.writeBoolean(look.tinted());
            for (int w : look.words()) {
                out.writeInt(w);
            }
            for (int r : refs) {
                out.writeInt(r);
            }
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long at = over ? 0 : end;
            // Cuts a tail left by a crash, or the old file when starting over.
            channel.truncate(at);
            ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
            while (buffer.hasRemaining()) {
                at += channel.write(buffer, at);
            }
            end = at;
        }
        tileIndex = index;
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
