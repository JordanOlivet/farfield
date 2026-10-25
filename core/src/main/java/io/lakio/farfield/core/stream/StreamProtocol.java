package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.storage.SectionStore;
import io.lakio.farfield.core.util.ByteReader;
import io.lakio.farfield.core.util.ByteWriter;

import java.nio.charset.StandardCharsets;

/**
 * Messages of the LOD streaming protocol (ADR-068): a server with Farfield
 * answers the requests of its players' clients. Each message is one payload,
 * its type in the first byte. The client pulls: it asks for the index of a
 * region (which sections exist, at which version), then for the sections it
 * lacks or holds at another version; the server answers each, within its
 * bandwidth budgets. A session is one dimension of one connection.
 */
public final class StreamProtocol {

    /**
     * Raised when the messages change shape; peers with another version do
     * not stream (2: preview tiles; 3: the welcome tells the table sizes).
     */
    public static final int VERSION = 3;
    /** Largest body of one message, under the game's custom payload limits. */
    public static final int MAX_PAYLOAD = 30_000;

    // Client to server.
    public static final int HELLO = 1;
    public static final int INDEX_REQUEST = 2;
    public static final int FETCH = 3;
    public static final int TILE_FETCH = 4;
    // Server to client.
    public static final int WELCOME = 10;
    public static final int KEYS = 11;
    public static final int INDEX = 12;
    public static final int SECTION = 13;
    public static final int ABSENT = 14;
    public static final int UNCHANGED = 15;
    public static final int TILE = 16;
    public static final int TILE_ABSENT = 17;
    public static final int TILE_UNCHANGED = 18;
    /** A tile near the player was built or rebuilt: ask for it again. */
    public static final int TILE_CHANGED = 19;

    /** {@link Keys#kind}: block-state keys or biome keys. */
    public static final int BLOCK_STATES = 0;
    public static final int BIOMES = 1;

    private StreamProtocol() {
    }

    /** The message type of a payload. */
    public static int type(byte[] payload) {
        return payload.length == 0 ? -1 : payload[0] & 0xFF;
    }

    /** A reader positioned after the type byte. */
    public static ByteReader body(byte[] payload) {
        return new ByteReader().reset(payload, 1, payload.length - 1);
    }

    private static ByteWriter start(int type) {
        ByteWriter out = new ByteWriter(64);
        out.writeByte(type);
        return out;
    }

    static void writeString(ByteWriter out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeVarInt(bytes.length);
        out.writeBytes(bytes, 0, bytes.length);
    }

    static String readString(ByteReader in) {
        int n = in.readVarInt();
        if (n < 0 || n > in.remaining()) {
            throw new IllegalArgumentException("bad string length " + n);
        }
        byte[] bytes = new byte[n];
        in.readBytes(bytes, 0, n);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int zigzag(int v) {
        return v << 1 ^ v >> 31;
    }

    private static int unzigzag(int v) {
        return v >>> 1 ^ -(v & 1);
    }

    /**
     * Client: starts streaming a dimension. {@code epoch} is the server store's
     * epoch the client's mirror was filled from (0 for none); with it, the key
     * counts it already holds, so the server sends only the new keys.
     */
    public record Hello(int version, String dimension, long epoch, int blockStates, int biomes) {
        public byte[] encode() {
            ByteWriter out = start(HELLO);
            out.writeVarInt(version);
            writeString(out, dimension);
            out.writeLong(epoch);
            out.writeVarInt(blockStates);
            out.writeVarInt(biomes);
            return out.toByteArray();
        }

        public static Hello decode(byte[] payload) {
            ByteReader in = body(payload);
            return new Hello(in.readVarInt(), readString(in), in.readLong(), in.readVarInt(), in.readVarInt());
        }
    }

    /**
     * Server: the dimension streams under {@code session}, from a store of
     * that epoch, within a radius; its key tables hold {@code blockStates}
     * and {@code biomes} keys, all sent right after.
     */
    public record Welcome(int session, String dimension, long epoch, int radiusBlocks, int blockStates, int biomes) {
        public byte[] encode() {
            ByteWriter out = start(WELCOME);
            out.writeVarInt(session);
            writeString(out, dimension);
            out.writeLong(epoch);
            out.writeVarInt(radiusBlocks);
            out.writeVarInt(blockStates);
            out.writeVarInt(biomes);
            return out.toByteArray();
        }

        public static Welcome decode(byte[] payload) {
            ByteReader in = body(payload);
            return new Welcome(in.readVarInt(), readString(in), in.readLong(), in.readVarInt(), in.readVarInt(),
                    in.readVarInt());
        }
    }

    /** Server: the keys of a table from id {@code from} on, in id order; sent before any section using them. */
    public record Keys(int session, int kind, int from, String[] keys) {
        public byte[] encode() {
            ByteWriter out = start(KEYS);
            out.writeVarInt(session);
            out.writeByte(kind);
            out.writeVarInt(from);
            out.writeVarInt(keys.length);
            for (String k : keys) {
                writeString(out, k);
            }
            return out.toByteArray();
        }

        public static Keys decode(byte[] payload) {
            ByteReader in = body(payload);
            int session = in.readVarInt();
            int kind = in.readByte();
            int from = in.readVarInt();
            int n = in.readVarInt();
            if (n < 0 || n > in.remaining()) {
                throw new IllegalArgumentException("bad key count " + n);
            }
            String[] keys = new String[n];
            for (int i = 0; i < n; i++) {
                keys[i] = readString(in);
            }
            return new Keys(session, kind, from, keys);
        }
    }

    /** Client: which sections exist in one region file of a level ({@link SectionStore#REGION_SHIFT}). */
    public record IndexRequest(int session, int level, int rx, int rz) {
        public byte[] encode() {
            ByteWriter out = start(INDEX_REQUEST);
            out.writeVarInt(session);
            out.writeVarInt(level);
            out.writeVarInt(zigzag(rx));
            out.writeVarInt(zigzag(rz));
            return out.toByteArray();
        }

        public static IndexRequest decode(byte[] payload) {
            ByteReader in = body(payload);
            return new IndexRequest(in.readVarInt(), in.readVarInt(), unzigzag(in.readVarInt()),
                    unzigzag(in.readVarInt()));
        }
    }

    /**
     * Server: part of a region's index, {@code keys} and their {@code versions};
     * {@code last} on the final part (a region without sections sends one
     * empty, last part).
     */
    public record Index(int session, int level, int rx, int rz, boolean last, long[] keys, long[] versions) {
        /** Entries per message: a column (2), a y (1 to 2), a version (8). */
        public static final int MAX_ENTRIES = MAX_PAYLOAD / 12;

        public byte[] encode() {
            ByteWriter out = start(INDEX);
            out.writeVarInt(session);
            out.writeVarInt(level);
            out.writeVarInt(zigzag(rx));
            out.writeVarInt(zigzag(rz));
            out.writeByte(last ? 1 : 0);
            out.writeVarInt(keys.length);
            int mask = (1 << SectionStore.REGION_SHIFT) - 1;
            for (int i = 0; i < keys.length; i++) {
                long k = keys[i];
                int column = (LodKey.z(k) & mask) << SectionStore.REGION_SHIFT | LodKey.x(k) & mask;
                out.writeByte(column & 0xFF);
                out.writeByte(column >>> 8);
                out.writeVarInt(zigzag(LodKey.y(k)));
                out.writeLong(versions[i]);
            }
            return out.toByteArray();
        }

        public static Index decode(byte[] payload) {
            ByteReader in = body(payload);
            int session = in.readVarInt();
            int level = in.readVarInt();
            int rx = unzigzag(in.readVarInt());
            int rz = unzigzag(in.readVarInt());
            boolean last = in.readByte() != 0;
            int n = in.readVarInt();
            if (n < 0 || n > in.remaining()) {
                throw new IllegalArgumentException("bad index size " + n);
            }
            long[] keys = new long[n];
            long[] versions = new long[n];
            int mask = (1 << SectionStore.REGION_SHIFT) - 1;
            for (int i = 0; i < n; i++) {
                int column = in.readByte() | in.readByte() << 8;
                int y = unzigzag(in.readVarInt());
                keys[i] = LodKey.pack(level, (rx << SectionStore.REGION_SHIFT) + (column & mask), y,
                        (rz << SectionStore.REGION_SHIFT) + (column >>> SectionStore.REGION_SHIFT & mask));
                versions[i] = in.readLong();
            }
            return new Index(session, level, rx, rz, last, keys, versions);
        }
    }

    /** Client: sections it wants, each with the version it holds (-1 for none). */
    public record Fetch(int session, long[] keys, long[] haves) {
        /** Keys per message, as many as the client keeps in flight. */
        public static final int MAX_KEYS = 64;

        public byte[] encode() {
            ByteWriter out = start(FETCH);
            out.writeVarInt(session);
            out.writeVarInt(keys.length);
            for (int i = 0; i < keys.length; i++) {
                out.writeLong(keys[i]);
                out.writeLong(haves[i]);
            }
            return out.toByteArray();
        }

        public static Fetch decode(byte[] payload) {
            ByteReader in = body(payload);
            int session = in.readVarInt();
            int n = in.readVarInt();
            if (n < 0 || n > MAX_KEYS) {
                throw new IllegalArgumentException("bad fetch size " + n);
            }
            long[] keys = new long[n];
            long[] haves = new long[n];
            for (int i = 0; i < n; i++) {
                keys[i] = in.readLong();
                haves[i] = in.readLong();
            }
            return new Fetch(session, keys, haves);
        }
    }

    /**
     * Server: part {@code part} of {@code parts} of a section's stored record
     * ({@link SectionStore#readRecord}), at {@code version}.
     */
    public record Section(int session, long key, long version, int part, int parts, byte[] data) {
        /** Record bytes per message. */
        public static final int MAX_DATA = MAX_PAYLOAD - 40;

        public byte[] encode() {
            ByteWriter out = start(SECTION);
            out.writeVarInt(session);
            out.writeLong(key);
            out.writeLong(version);
            out.writeVarInt(part);
            out.writeVarInt(parts);
            out.writeVarInt(data.length);
            out.writeBytes(data, 0, data.length);
            return out.toByteArray();
        }

        public static Section decode(byte[] payload) {
            ByteReader in = body(payload);
            int session = in.readVarInt();
            long key = in.readLong();
            long version = in.readLong();
            int part = in.readVarInt();
            int parts = in.readVarInt();
            int n = in.readVarInt();
            if (n < 0 || n > in.remaining() || part < 0 || part >= parts) {
                throw new IllegalArgumentException("bad section part");
            }
            byte[] data = new byte[n];
            in.readBytes(data, 0, n);
            return new Section(session, key, version, part, parts, data);
        }

        /** The messages of one record, in order. */
        public static Section[] split(int session, long key, long version, byte[] record) {
            int parts = Math.max(1, (record.length + MAX_DATA - 1) / MAX_DATA);
            Section[] out = new Section[parts];
            for (int p = 0; p < parts; p++) {
                int from = p * MAX_DATA;
                int to = Math.min(record.length, from + MAX_DATA);
                out[p] = new Section(session, key, version, p, parts, java.util.Arrays.copyOfRange(record, from, to));
            }
            return out;
        }
    }

    /**
     * Client: noise preview tiles it wants ({@code PreviewStore} keys), each
     * with the version it holds (-1 for none).
     */
    public record TileFetch(int session, long[] tiles, long[] haves) {
        public static final int MAX_TILES = 16;

        public byte[] encode() {
            ByteWriter out = start(TILE_FETCH);
            out.writeVarInt(session);
            out.writeVarInt(tiles.length);
            for (int i = 0; i < tiles.length; i++) {
                out.writeLong(tiles[i]);
                out.writeLong(haves[i]);
            }
            return out.toByteArray();
        }

        public static TileFetch decode(byte[] payload) {
            ByteReader in = body(payload);
            int session = in.readVarInt();
            int n = in.readVarInt();
            if (n < 0 || n > MAX_TILES) {
                throw new IllegalArgumentException("bad tile fetch size " + n);
            }
            long[] tiles = new long[n];
            long[] haves = new long[n];
            for (int i = 0; i < n; i++) {
                tiles[i] = in.readLong();
                haves[i] = in.readLong();
            }
            return new TileFetch(session, tiles, haves);
        }
    }

    /** Server: part {@code part} of {@code parts} of a preview tile's file, at {@code version}. */
    public record Tile(int session, long tile, long version, int part, int parts, byte[] data) {
        public byte[] encode() {
            ByteWriter out = start(TILE);
            out.writeVarInt(session);
            out.writeLong(tile);
            out.writeLong(version);
            out.writeVarInt(part);
            out.writeVarInt(parts);
            out.writeVarInt(data.length);
            out.writeBytes(data, 0, data.length);
            return out.toByteArray();
        }

        public static Tile decode(byte[] payload) {
            ByteReader in = body(payload);
            int session = in.readVarInt();
            long tile = in.readLong();
            long version = in.readLong();
            int part = in.readVarInt();
            int parts = in.readVarInt();
            int n = in.readVarInt();
            if (n < 0 || n > in.remaining() || part < 0 || part >= parts) {
                throw new IllegalArgumentException("bad tile part");
            }
            byte[] data = new byte[n];
            in.readBytes(data, 0, n);
            return new Tile(session, tile, version, part, parts, data);
        }

        /** The messages of one tile file, in order. */
        public static Tile[] split(int session, long tile, long version, byte[] file) {
            int max = Section.MAX_DATA;
            int parts = Math.max(1, (file.length + max - 1) / max);
            Tile[] out = new Tile[parts];
            for (int p = 0; p < parts; p++) {
                int from = p * max;
                int to = Math.min(file.length, from + max);
                out[p] = new Tile(session, tile, version, p, parts, java.util.Arrays.copyOfRange(file, from, to));
            }
            return out;
        }
    }

    /**
     * Server: a requested section the store does not hold ({@link #ABSENT}),
     * or holds at the client's version ({@link #UNCHANGED}); the same for a
     * tile ({@link #TILE_ABSENT}, {@link #TILE_UNCHANGED}), or a tile written
     * since ({@link #TILE_CHANGED}). {@code key} is a section or tile key.
     */
    public record Answer(int type, int session, long key) {
        public byte[] encode() {
            ByteWriter out = start(type);
            out.writeVarInt(session);
            out.writeLong(key);
            return out.toByteArray();
        }

        public static Answer decode(byte[] payload) {
            ByteReader in = body(payload);
            return new Answer(StreamProtocol.type(payload), in.readVarInt(), in.readLong());
        }
    }
}
