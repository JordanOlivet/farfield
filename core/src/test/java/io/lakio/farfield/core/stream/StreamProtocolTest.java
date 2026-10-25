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

        StreamProtocol.Welcome welcome = new StreamProtocol.Welcome(7, "minecraft:the_nether", -5L, 4096);
        assertEquals(welcome, StreamProtocol.Welcome.decode(welcome.encode()));

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
    void fetchCarriesTheVersionsHeld() {
        long[] keys = {LodKey.pack(0, 1, 2, 3), LodKey.pack(4, -1, -2, -3)};
        long[] haves = {-1, 99};
        StreamProtocol.Fetch back = StreamProtocol.Fetch.decode(new StreamProtocol.Fetch(9, keys, haves).encode());
        assertArrayEquals(keys, back.keys());
        assertArrayEquals(haves, back.haves());
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
