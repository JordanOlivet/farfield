package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.LodKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamProtocolTest {

    @Test
    void messagesRoundTrip() {
        StreamProtocol.Hello hello = new StreamProtocol.Hello(1, "minecraft:overworld", 0x1234_5678_9ABCL, 812, 64);
        assertEquals(hello, StreamProtocol.Hello.decode(hello.encode()));
        assertEquals(StreamProtocol.HELLO, StreamProtocol.type(hello.encode()));

        StreamProtocol.Welcome welcome = new StreamProtocol.Welcome(7, "minecraft:the_nether", -5L, 4096, 6460, 127);
        assertEquals(welcome, StreamProtocol.Welcome.decode(welcome.encode()));
        assertEquals(1, StreamProtocol.Hello.version(hello.encode()));
        // A refusal reads the same in every version: session -1, the server's protocol as the radius.
        StreamProtocol.Welcome declined = StreamProtocol.Welcome.decode(
                StreamProtocol.Welcome.declined(StreamProtocol.VERSION).encode());
        assertEquals(-1, declined.session());
        assertEquals(StreamProtocol.VERSION, declined.radiusBlocks());

        StreamProtocol.Keys keys = new StreamProtocol.Keys(7, StreamProtocol.BIOMES, 3,
                new String[]{"minecraft:plains", "cobblemon:é"});
        StreamProtocol.Keys back = StreamProtocol.Keys.decode(keys.encode());
        assertEquals(keys.session(), back.session());
        assertEquals(keys.kind(), back.kind());
        assertEquals(keys.from(), back.from());
        assertArrayEquals(keys.keys(), back.keys());

        StreamProtocol.IndexRequest request = new StreamProtocol.IndexRequest(7, 2, -3, 5);
        assertEquals(request, StreamProtocol.IndexRequest.decode(request.encode()));

        StreamProtocol.Answer absent = new StreamProtocol.Answer(StreamProtocol.ABSENT, 7, LodKey.pack(1, -40, -2, 9));
        assertEquals(absent, StreamProtocol.Answer.decode(absent.encode()));
    }

    @Test
    void indexEntriesKeepTheirKeysInsideTheRegion() {
        // Region (-1, 2) of level 1: columns x -32..-1, z 64..95.
        long[] keys = {LodKey.pack(1, -32, -4, 64), LodKey.pack(1, -1, 7, 95), LodKey.pack(1, -17, 0, 80)};
        long[] versions = {1L << 40 | 77, 5, Long.MAX_VALUE};
        StreamProtocol.Index index = new StreamProtocol.Index(3, 1, -1, 2, true, keys, versions);
        StreamProtocol.Index back = StreamProtocol.Index.decode(index.encode());
        assertArrayEquals(keys, back.keys());
        assertArrayEquals(versions, back.versions());
        assertTrue(back.last());
        assertTrue(index.encode().length < 12 * keys.length + 16);
    }

    @Test
    void changedCarriesRegionsOfEveryLevel() {
        long[] regions = {RegionChanges.regionOf(LodKey.pack(0, -33, 5, 64)), LodKey.pack(4, 7, 0, -1),
                LodKey.pack(2, -262_144, 0, 262_143)};
        assertEquals(LodKey.pack(0, -2, 0, 2), regions[0]);
        StreamProtocol.Changed back = StreamProtocol.Changed.decode(new StreamProtocol.Changed(5, regions).encode());
        assertEquals(5, back.session());
        assertArrayEquals(regions, back.regions());
        long[] many = new long[StreamProtocol.Changed.MAX_REGIONS];
        Arrays.fill(many, LodKey.pack(4, -262_144, 0, -262_144));
        assertTrue(new StreamProtocol.Changed(5, many).encode().length <= StreamProtocol.MAX_PAYLOAD + 8);
    }

    @Test
    void regionChangesWaitForQuietOrTheLongestWait() {
        long s = 1_000_000_000L;
        RegionChanges changes = new RegionChanges(3 * s, 20 * s);
        long a = LodKey.pack(0, 1, 0, 1);
        long b = LodKey.pack(0, 40, 0, 1);
        changes.written(a, 0);
        changes.written(LodKey.pack(0, 2, 3, 2), s); // same region as a
        changes.written(b, 0);
        java.util.List<Long> due = new java.util.ArrayList<>();
        changes.drainDue(2 * s, due::add);
        assertTrue(due.isEmpty());
        assertEquals(2, changes.pendingRegions());
        changes.drainDue(3 * s, due::add);
        assertEquals(java.util.List.of(RegionChanges.regionOf(b)), due);
        // a keeps being written: announced at its longest wait, then waits again.
        for (long t = 2 * s; t <= 20 * s; t += s) {
            changes.written(a, t);
        }
        due.clear();
        changes.drainDue(19 * s + s / 2, due::add);
        assertTrue(due.isEmpty());
        changes.drainDue(20 * s, due::add);
        assertEquals(java.util.List.of(RegionChanges.regionOf(a)), due);
        assertEquals(0, changes.pendingRegions());
    }

    @Test
    void fetchWindowHalvesOnLateAnswersOncePerRoundTrip() {
        long ms = 1_000_000L;
        FetchWindow w = new FetchWindow(4, 64, 64, 1000 * ms);
        w.answered(50 * ms, 0);
        assertEquals(64, w.size());
        w.answered(1200 * ms, 100 * ms); // late: halved
        assertEquals(32, w.size());
        w.answered(1300 * ms, 200 * ms); // queued behind the first: not cut again
        assertEquals(32, w.size());
        w.answered(1300 * ms, 1600 * ms); // a round trip later: cut again
        assertEquals(16, w.size());
        for (int i = 0; i < 17; i++) {
            w.answered(60 * ms, 2000 * ms);
        }
        assertEquals(17, w.size()); // one more per window of timely answers
        w.timedOut(3000 * ms);
        assertEquals(4, w.size());
        w.reset(64);
        assertEquals(64, w.size());
    }

    @Test
    void fetchCarriesTheVersionsHeld() {
        long[] keys = {LodKey.pack(0, 1, 2, 3), LodKey.pack(4, -1, -2, -3)};
        long[] haves = {-1, 99};
        StreamProtocol.Fetch back = StreamProtocol.Fetch.decode(new StreamProtocol.Fetch(9, keys, haves).encode());
        assertArrayEquals(keys, back.keys());
        assertArrayEquals(haves, back.haves());
    }

    @Test
    void tileMessagesRoundTrip() {
        long[] tiles = {io.lakio.farfield.core.preview.PreviewStore.key(-3, 7), 5L};
        long[] haves = {-1, 123456789L};
        StreamProtocol.TileFetch fetch = StreamProtocol.TileFetch.decode(
                new StreamProtocol.TileFetch(2, tiles, haves).encode());
        assertArrayEquals(tiles, fetch.tiles());
        assertArrayEquals(haves, fetch.haves());
        byte[] file = new byte[StreamProtocol.Section.MAX_DATA + 10];
        file[file.length - 1] = 42;
        StreamProtocol.Tile[] parts = StreamProtocol.Tile.split(2, tiles[0], 99L, file);
        assertEquals(2, parts.length);
        StreamProtocol.Tile back = StreamProtocol.Tile.decode(parts[1].encode());
        assertEquals(tiles[0], back.tile());
        assertEquals(99L, back.version());
        assertEquals(42, back.data()[back.data().length - 1]);
        StreamProtocol.Answer changed = new StreamProtocol.Answer(StreamProtocol.TILE_CHANGED, 2, tiles[0]);
        assertEquals(changed, StreamProtocol.Answer.decode(changed.encode()));
    }

    @Test
    void largeRecordsSplitUnderThePayloadLimit() {
        byte[] record = new byte[StreamProtocol.Section.MAX_DATA * 2 + 5];
        Arrays.fill(record, (byte) 7);
        record[record.length - 1] = 9;
        StreamProtocol.Section[] parts = StreamProtocol.Section.split(4, 11L, 22L, record);
        assertEquals(3, parts.length);
        byte[] joined = new byte[0];
        for (StreamProtocol.Section p : parts) {
            byte[] encoded = p.encode();
            assertTrue(encoded.length <= StreamProtocol.MAX_PAYLOAD + 64);
            StreamProtocol.Section back = StreamProtocol.Section.decode(encoded);
            assertEquals(3, back.parts());
            byte[] grown = Arrays.copyOf(joined, joined.length + back.data().length);
            System.arraycopy(back.data(), 0, grown, joined.length, back.data().length);
            joined = grown;
        }
        assertArrayEquals(record, joined);
        assertEquals(1, StreamProtocol.Section.split(4, 11L, 22L, new byte[0]).length);
    }

    @Test
    void bandwidthHoldsBackBeyondItsRateAndShared() {
        long t = 1_000_000_000L;
        Bandwidth shared = new Bandwidth(1_000, t);
        Bandwidth a = new Bandwidth(800, t);
        Bandwidth b = new Bandwidth(800, t);
        assertTrue(a.tryTake(800, shared, t));
        assertFalse(a.tryTake(1, shared, t)); // a's second is spent
        assertTrue(b.tryTake(200, shared, t));
        assertFalse(b.tryTake(1, shared, t)); // the shared second is spent
        // Half a second later: 500 shared bytes, 400 for a.
        assertFalse(a.tryTake(401, shared, t + 500_000_000L));
        assertTrue(a.tryTake(400, shared, t + 500_000_000L));
        // A message bigger than the rate passes once the budget is full.
        Bandwidth c = new Bandwidth(100, t);
        assertTrue(c.tryTake(5_000, null, t));
        assertFalse(c.tryTake(1, null, t + 1_000_000_000L));
        // No limit.
        assertTrue(new Bandwidth(0, t).tryTake(1 << 30, null, t));
    }
}
