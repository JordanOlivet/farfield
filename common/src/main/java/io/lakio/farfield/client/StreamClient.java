package io.lakio.farfield.client;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.storage.SectionStore;
import io.lakio.farfield.core.stream.StreamProtocol;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.core.world.RemoteWorldId;
import io.lakio.farfield.lod.BlockKeys;
import io.lakio.farfield.lod.RenderSettings;
import io.lakio.farfield.platform.Services;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The client side of LOD streaming (ADR-068): on a server with Farfield, the
 * LODs come from the server's store into a mirror of it, per server and
 * dimension ({@code farfield/servers/<server>/<seed>/stream/<dimension>}),
 * instead of being built from the chunks the client sees (client mode,
 * {@link LodClient}). The renderer asks it which sections exist (region
 * indexes from the server), and hands it the sections it wants, nearest
 * and in view first; at most {@link StreamProtocol.Fetch#MAX_KEYS} are in
 * flight. Received records go into the mirror as they are; the versions it
 * holds are kept beside it, so a rejoin only checks them. Client thread only.
 */
public final class StreamClient {

    private static final long HELLO_RETRY_NANOS = 10_000_000_000L;
    private static final long REQUEST_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long STATS_NANOS = 10_000_000_000L;
    private static final String EPOCH_FILE = "server.epoch";
    private static final String VERSIONS_FILE = "versions.bin";

    private static StreamClient instance;

    private final ResourceKey<Level> dimension;
    private final Path dir;
    private LodWorld mirror;
    private long cachedEpoch;
    /** -1 until the server welcomes the session; the server declined when {@link #declined}. */
    private int session = -1;
    private boolean declined;
    private int radius;
    private long helloSent;
    private final Long2LongOpenHashMap remote = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap local = new Long2LongOpenHashMap();
    private final LongOpenHashSet knownRegions = new LongOpenHashSet();
    private final Long2LongOpenHashMap askedRegions = new Long2LongOpenHashMap();
    private final LongLinkedOpenHashSet wishes = new LongLinkedOpenHashSet();
    private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<byte[][]> parts = new Long2ObjectOpenHashMap<>();
    private int generation;
    private long nextStats = System.nanoTime() + STATS_NANOS;
    private long received;
    private int sectionsReceived;
    private int unchangedReceived;

    private StreamClient(ResourceKey<Level> dimension, Path dir) {
        this.dimension = dimension;
        this.dir = dir;
        remote.defaultReturnValue(-1);
        local.defaultReturnValue(-1);
    }

    // ---------------------------------------------------------------- lifecycle (client tick)

    /**
     * End of every client tick: opens the session of the current dimension on
     * a server that streams, closes it when the player leaves or changes
     * dimension, and sends the wanted requests.
     */
    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        boolean remote = level != null && !mc.hasSingleplayerServer() && mc.getConnection() != null
                && Services.NETWORK.serverStreams();
        StreamClient c = instance;
        if (c != null && (!remote || c.dimension != level.dimension())) {
            instance = null;
            c.close();
            c = null;
        }
        if (!remote) {
            return;
        }
        if (c == null) {
            c = open(mc, level);
            instance = c;
            if (c == null) {
                return;
            }
        }
        c.update(mc);
    }

    /**
     * Whether this client's LODs come from the server (no client-mode
     * building): the server has the channel and did not decline. True from
     * the join on, before the session opens, so client mode never starts.
     */
    public static boolean streaming(Minecraft mc) {
        StreamClient c = instance;
        return !mc.hasSingleplayerServer() && mc.getConnection() != null && Services.NETWORK.serverStreams()
                && (c == null || !c.declined);
    }

    /**
     * The mirror the renderer draws for {@code dimension}, or null when it
     * does not stream; only once the server welcomed the session, which may
     * empty the mirror first (another store).
     */
    public static LodWorld world(ResourceKey<Level> dimension) {
        StreamClient c = instance;
        return c != null && !c.declined && c.session >= 0 && c.dimension == dimension ? c.mirror : null;
    }

    /** The client streaming {@code world} (its mirror), or null. */
    public static StreamClient of(LodWorld world) {
        StreamClient c = instance;
        return c != null && world != null && c.mirror == world && !c.declined && c.session >= 0 ? c : null;
    }

    /** The game is closing. */
    public static void onClientStopping() {
        StreamClient c = instance;
        instance = null;
        if (c != null) {
            c.close();
        }
    }

    private static StreamClient open(Minecraft mc, ClientLevel level) {
        Path dir = LodWorldProvider.remoteStore(mc, level).getParent().resolve("stream")
                .resolve(RemoteWorldId.dimensionFolder(level.dimension().location().toString()));
        StreamClient c = new StreamClient(level.dimension(), dir);
        try {
            c.openMirror();
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.error("LOD streaming: cannot open the mirror in {}", dir, e);
            return null;
        }
        return c;
    }

    private void openMirror() throws IOException {
        mirror = LodWorld.open(dir, BlockKeys.RESOLVER);
        Path epochFile = dir.resolve(EPOCH_FILE);
        cachedEpoch = 0;
        local.clear();
        if (Files.exists(epochFile)) {
            try {
                cachedEpoch = Long.parseUnsignedLong(Files.readString(epochFile).trim(), 16);
                readVersions();
            } catch (NumberFormatException | IOException e) {
                cachedEpoch = 0;
                local.clear();
            }
        }
    }

    private void readVersions() throws IOException {
        Path file = dir.resolve(VERSIONS_FILE);
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                local.put(in.readLong(), in.readLong());
            }
        }
    }

    private void writeVersions() throws IOException {
        Path file = dir.resolve(VERSIONS_FILE);
        Path tmp = dir.resolve(VERSIONS_FILE + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(local.size());
            for (Long2LongOpenHashMap.Entry e : local.long2LongEntrySet()) {
                out.writeLong(e.getLongKey());
                out.writeLong(e.getLongValue());
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Empties the mirror: the server's store is another one (new epoch) or its keys disagree. */
    private void wipe(long epoch) throws IOException {
        mirror.close();
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
        mirror = LodWorld.open(dir, BlockKeys.RESOLVER);
        local.clear();
        cachedEpoch = epoch;
        if (epoch != 0) {
            Files.writeString(dir.resolve(EPOCH_FILE), Long.toUnsignedString(epoch, 16));
        }
    }

    private void close() {
        try {
            if (session >= 0) {
                writeVersions();
            }
            mirror.close();
            Farfield.LOG.info("LOD stream mirror of {} closed: {} sections held", dimension.location(), local.size());
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("LOD streaming: closing the mirror of {} failed", dimension.location(), e);
        }
    }

    private void update(Minecraft mc) {
        long now = System.nanoTime();
        if (session < 0 && !declined && now - helloSent > HELLO_RETRY_NANOS) {
            helloSent = now;
            send(new StreamProtocol.Hello(StreamProtocol.VERSION, dimension.location().toString(), cachedEpoch,
                    mirror.blockStates().size(), mirror.biomes().size()).encode());
        }
        if (session < 0) {
            return;
        }
        // Requests the server never answered (it dropped them): asked again later.
        inFlight.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_NANOS);
        askedRegions.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_NANOS);
        while (inFlight.size() < StreamProtocol.Fetch.MAX_KEYS && !wishes.isEmpty()) {
            int n = Math.min(wishes.size(), StreamProtocol.Fetch.MAX_KEYS - inFlight.size());
            long[] keys = new long[n];
            long[] haves = new long[n];
            for (int i = 0; i < n; i++) {
                long key = wishes.removeFirstLong();
                keys[i] = key;
                haves[i] = local.get(key);
                inFlight.put(key, now);
            }
            send(new StreamProtocol.Fetch(session, keys, haves).encode());
        }
        if (now > nextStats) {
            nextStats = now + STATS_NANOS;
            if (RenderSettings.performanceLogs() && (received > 0 || !inFlight.isEmpty())) {
                Farfield.LOG.info("LOD streaming: {} KB received in 10 s ({} sections, {} unchanged), {} in flight,"
                                + " {} wanted, {} known regions, {} sections held", received >> 10, sectionsReceived,
                        unchangedReceived, inFlight.size(), wishes.size(), knownRegions.size(), local.size());
            }
            received = 0;
            sectionsReceived = 0;
            unchangedReceived = 0;
        }
    }

    private static void send(byte[] message) {
        Services.NETWORK.sendToServer(message);
    }

    // ---------------------------------------------------------------- the renderer's side

    /**
     * Whether the server holds {@code key}: from its region's index once
     * known (asked for here otherwise, within the server's radius), else
     * from the mirror.
     */
    public boolean exists(long key) {
        long region = regionOf(key);
        if (knownRegions.contains(region)) {
            return remote.containsKey(key);
        }
        askRegion(region);
        try {
            return mirror.exists(key);
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the mirror lacks {@code key} or holds another version than the server's. */
    public boolean needsFetch(long key) {
        long version = remote.get(key);
        return version >= 0 && local.get(key) != version;
    }

    /** Whether the mirror holds some version of {@code key}, current or not. */
    public boolean hasLocal(long key) {
        return local.containsKey(key);
    }

    /** The renderer wants {@code key}; the latest selection's order comes first. */
    public void want(long key) {
        if (!inFlight.containsKey(key)) {
            wishes.add(key);
        }
    }

    /** A new selection: its wishes replace the previous ones (those in flight stay). */
    public void newSelection() {
        wishes.clear();
    }

    /** Changes when region indexes arrive: the renderer runs its selection again. */
    public int generation() {
        return generation;
    }

    private static long regionOf(long key) {
        return LodKey.pack(LodKey.level(key), LodKey.x(key) >> SectionStore.REGION_SHIFT, 0,
                LodKey.z(key) >> SectionStore.REGION_SHIFT);
    }

    private void askRegion(long region) {
        if (session < 0 || askedRegions.containsKey(region) || !regionInRange(region)) {
            return;
        }
        askedRegions.put(region, System.nanoTime());
        send(new StreamProtocol.IndexRequest(session, LodKey.level(region), LodKey.x(region), LodKey.z(region))
                .encode());
    }

    private boolean regionInRange(long region) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        long size = (long) LodSection.SIZE << LodKey.level(region) << SectionStore.REGION_SHIFT;
        double x0 = (double) LodKey.x(region) * size;
        double z0 = (double) LodKey.z(region) * size;
        double dx = Math.max(0, Math.max(x0 - player.getX(), player.getX() - (x0 + size)));
        double dz = Math.max(0, Math.max(z0 - player.getZ(), player.getZ() - (z0 + size)));
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    // ---------------------------------------------------------------- receiving (client thread)

    /** A message from the server. */
    public static void receive(byte[] message) {
        StreamClient c = instance;
        if (c == null) {
            return;
        }
        try {
            c.received += message.length;
            switch (StreamProtocol.type(message)) {
                case StreamProtocol.WELCOME -> c.welcome(StreamProtocol.Welcome.decode(message));
                case StreamProtocol.KEYS -> c.keys(StreamProtocol.Keys.decode(message));
                case StreamProtocol.INDEX -> c.index(StreamProtocol.Index.decode(message));
                case StreamProtocol.SECTION -> c.section(StreamProtocol.Section.decode(message));
                case StreamProtocol.UNCHANGED, StreamProtocol.ABSENT -> c.answer(StreamProtocol.Answer.decode(message));
                default -> {
                }
            }
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("LOD streaming: bad message from the server", e);
        }
    }

    private void welcome(StreamProtocol.Welcome welcome) throws IOException {
        if (welcome.session() < 0) {
            declined = true;
            Farfield.LOG.info("LOD streaming: the server does not stream; building the LODs from its chunks");
            return;
        }
        if (!welcome.dimension().equals(dimension.location().toString())) {
            return;
        }
        if (welcome.epoch() != cachedEpoch) {
            wipe(welcome.epoch());
        }
        session = welcome.session();
        radius = welcome.radiusBlocks();
        remote.clear();
        knownRegions.clear();
        askedRegions.clear();
        inFlight.clear();
        wishes.clear();
        parts.clear();
        generation++;
        Farfield.LOG.info("LOD streaming from the server: {} (session {}, radius {} blocks, {} sections held)",
                dimension.location(), session, radius, local.size());
    }

    private void keys(StreamProtocol.Keys keys) throws IOException {
        if (keys.session() != session) {
            return;
        }
        KeyRegistry registry = keys.kind() == StreamProtocol.BLOCK_STATES ? mirror.blockStates() : mirror.biomes();
        for (int i = 0; i < keys.keys().length; i++) {
            if (registry.id(keys.keys()[i]) != keys.from() + i) {
                // The mirror's tables disagree with the server's: start it again.
                Farfield.LOG.warn("LOD streaming: key tables differ from the server's, emptying the mirror");
                wipe(0);
                session = -1;
                helloSent = 0;
                return;
            }
        }
    }

    private void index(StreamProtocol.Index index) {
        if (index.session() != session) {
            return;
        }
        long region = LodKey.pack(index.level(), index.rx(), 0, index.rz());
        for (int i = 0; i < index.keys().length; i++) {
            remote.put(index.keys()[i], index.versions()[i]);
        }
        if (index.last()) {
            knownRegions.add(region);
            askedRegions.remove(region);
            generation++;
        }
    }

    private void section(StreamProtocol.Section part) throws IOException {
        if (part.session() != session) {
            return;
        }
        byte[] record;
        if (part.parts() == 1) {
            record = part.data();
        } else {
            byte[][] got = parts.computeIfAbsent(part.key(), k -> new byte[part.parts()][]);
            if (got.length != part.parts()) {
                parts.remove(part.key());
                return;
            }
            got[part.part()] = part.data();
            int length = 0;
            for (byte[] b : got) {
                if (b == null) {
                    return;
                }
                length += b.length;
            }
            parts.remove(part.key());
            record = new byte[length];
            int at = 0;
            for (byte[] b : got) {
                System.arraycopy(b, 0, record, at, b.length);
                at += b.length;
            }
        }
        local.put(part.key(), part.version());
        remote.put(part.key(), part.version());
        inFlight.remove(part.key());
        sectionsReceived++;
        mirror.putRecord(part.key(), record, 0, record.length);
    }

    private void answer(StreamProtocol.Answer answer) {
        if (answer.session() != session) {
            return;
        }
        inFlight.remove(answer.key());
        if (answer.type() == StreamProtocol.UNCHANGED) {
            long version = remote.get(answer.key());
            if (version >= 0) {
                local.put(answer.key(), version);
            }
            unchangedReceived++;
            mirror.touch(answer.key());
        } else {
            remote.remove(answer.key());
            generation++;
        }
    }
}
