package io.lakio.farfield.client;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.preview.PreviewStore;
import io.lakio.farfield.core.preview.PreviewTile;
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
 * holds are kept beside it, so a rejoin only checks them. The noise preview
 * comes the same way, as tiles within the radius, nearest first, asked
 * again when the server says one was built. Client thread only.
 */
public final class StreamClient {

    private static final long HELLO_RETRY_NANOS = 10_000_000_000L;
    private static final long REQUEST_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long STATS_NANOS = 10_000_000_000L;
    private static final String EPOCH_FILE = "server.epoch";
    private static final String VERSIONS_FILE = "versions.bin";
    private static final String TILES_FILE = "tiles.bin";
    private static final int TILES_IN_FLIGHT = StreamProtocol.TileFetch.MAX_TILES;
    private static final long TILE_SCAN_NANOS = 250_000_000L;

    private static StreamClient instance;

    private final ResourceKey<Level> dimension;
    private final Path dir;
    private LodWorld mirror;
    private long cachedEpoch;
    /** -1 until the server welcomes the session; the server declined when {@link #declined}. */
    private int session = -1;
    private boolean declined;
    private int radius;
    /** Key counts the server announced at welcome: the mirror is drawn once its tables hold them. */
    private int blockStatesExpected = Integer.MAX_VALUE;
    private int biomesExpected = Integer.MAX_VALUE;
    /** Times the tables disagreed this connection; past {@link #MAX_MISMATCHES}, client mode instead. */
    private int mismatches;
    private static final int MAX_MISMATCHES = 2;
    /**
     * Keys of each table the server sent (kept with the epoch): announced at
     * hello, so the server never skips a key the mirror lacks.
     */
    private int serverBlockStates;
    private int serverBiomes;
    private long helloSent;
    private final Long2LongOpenHashMap remote = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap local = new Long2LongOpenHashMap();
    private final LongOpenHashSet knownRegions = new LongOpenHashSet();
    private final Long2LongOpenHashMap askedRegions = new Long2LongOpenHashMap();
    private final LongLinkedOpenHashSet wishes = new LongLinkedOpenHashSet();
    /**
     * The coarsest sections of the radius, fetched in the background after
     * the renderer's wishes: the preview reads them to hide itself where real
     * terrain is, and the renderer, drawing finer levels near the player,
     * never asks for them (the preview then drew over real terrain: water
     * blended twice, its grass over real sand).
     */
    private final LongLinkedOpenHashSet background = new LongLinkedOpenHashSet();
    private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<byte[][]> parts = new Long2ObjectOpenHashMap<>();
    /** Preview tiles: versions held (kept beside the mirror), answered this session, in flight, parts. */
    private final Long2LongOpenHashMap localTiles = new Long2LongOpenHashMap();
    private final LongOpenHashSet tilesAnswered = new LongOpenHashSet();
    private final Long2LongOpenHashMap tilesInFlight = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<byte[][]> tileParts = new Long2ObjectOpenHashMap<>();
    private long nextTileScan;
    private int tilesReceived;
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
        localTiles.defaultReturnValue(-1);
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
        return c != null && c.ready() && c.dimension == dimension ? c.mirror : null;
    }

    /**
     * Welcomed, and the mirror's key tables hold every key the server had
     * then: nothing drawn before may add a key of its own in between.
     */
    private boolean ready() {
        return !declined && session >= 0 && mirror.blockStates().size() >= blockStatesExpected
                && mirror.biomes().size() >= biomesExpected;
    }

    /** The client streaming {@code world} (its mirror), or null. */
    public static StreamClient of(LodWorld world) {
        StreamClient c = instance;
        return c != null && world != null && c.mirror == world && c.ready() ? c : null;
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
        freezeKeys();
        Path epochFile = dir.resolve(EPOCH_FILE);
        cachedEpoch = 0;
        serverBlockStates = 0;
        serverBiomes = 0;
        local.clear();
        localTiles.clear();
        if (Files.exists(epochFile)) {
            try {
                // "<epoch> <block-state keys> <biome keys>" as received; a table holding
                // other keys (added here) is not the server's: the mirror starts again.
                String[] parts = Files.readString(epochFile).trim().split("\\s+");
                int blockStates = Integer.parseInt(parts[1]);
                int biomes = Integer.parseInt(parts[2]);
                if (mirror.blockStates().size() == blockStates && mirror.biomes().size() == biomes) {
                    cachedEpoch = Long.parseUnsignedLong(parts[0], 16);
                    serverBlockStates = blockStates;
                    serverBiomes = biomes;
                    readVersions(VERSIONS_FILE, local);
                    readVersions(TILES_FILE, localTiles);
                }
            } catch (RuntimeException | IOException e) {
                cachedEpoch = 0;
                serverBlockStates = 0;
                serverBiomes = 0;
                local.clear();
                localTiles.clear();
            }
        }
    }

    /** The mirror's tables are the server's, id for id: nothing here adds a key to them. */
    private void freezeKeys() {
        mirror.blockStates().freeze();
        mirror.biomes().freeze();
    }

    private void readVersions(String name, Long2LongOpenHashMap into) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                into.put(in.readLong(), in.readLong());
            }
        }
    }

    private void writeVersions(String name, Long2LongOpenHashMap from) throws IOException {
        Path file = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(from.size());
            for (Long2LongOpenHashMap.Entry e : from.long2LongEntrySet()) {
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
        freezeKeys();
        local.clear();
        localTiles.clear();
        cachedEpoch = epoch;
        serverBlockStates = 0;
        serverBiomes = 0;
    }

    private void close() {
        try {
            Path epochFile = dir.resolve(EPOCH_FILE);
            if (session >= 0 && mirror.blockStates().size() == serverBlockStates
                    && mirror.biomes().size() == serverBiomes) {
                writeVersions(VERSIONS_FILE, local);
                writeVersions(TILES_FILE, localTiles);
                Files.writeString(epochFile, Long.toUnsignedString(cachedEpoch, 16) + " " + serverBlockStates + " "
                        + serverBiomes);
            } else {
                Files.deleteIfExists(epochFile); // not the server's tables: emptied at the next session
            }
            mirror.close();
            Farfield.LOG.info("LOD stream mirror of {} closed: {} sections and {} preview tiles held",
                    dimension.location(), local.size(), localTiles.size());
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("LOD streaming: closing the mirror of {} failed", dimension.location(), e);
        }
    }

    private void update(Minecraft mc) {
        long now = System.nanoTime();
        if (session < 0 && !declined && now - helloSent > HELLO_RETRY_NANOS) {
            helloSent = now;
            send(new StreamProtocol.Hello(StreamProtocol.VERSION, dimension.location().toString(), cachedEpoch,
                    serverBlockStates, serverBiomes).encode());
        }
        if (session < 0) {
            return;
        }
        // Requests the server never answered (it dropped them): asked again later.
        inFlight.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_NANOS);
        tilesInFlight.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_NANOS);
        if (RenderSettings.noisePreview() && now > nextTileScan && tilesInFlight.size() < TILES_IN_FLIGHT) {
            nextTileScan = now + TILE_SCAN_NANOS;
            askTiles(mc, now);
        }
        askedRegions.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_NANOS);
        while (inFlight.size() < StreamProtocol.Fetch.MAX_KEYS && (!wishes.isEmpty() || !background.isEmpty())) {
            int n = Math.min(wishes.size() + background.size(), StreamProtocol.Fetch.MAX_KEYS - inFlight.size());
            long[] keys = new long[n];
            long[] haves = new long[n];
            int count = 0;
            while (count < n && (!wishes.isEmpty() || !background.isEmpty())) {
                long key = !wishes.isEmpty() ? wishes.removeFirstLong() : background.removeFirstLong();
                if (inFlight.containsKey(key) || !needsFetch(key)) {
                    continue;
                }
                keys[count] = key;
                haves[count] = local.get(key);
                inFlight.put(key, now);
                count++;
            }
            if (count == 0) {
                break;
            }
            send(new StreamProtocol.Fetch(session, java.util.Arrays.copyOf(keys, count),
                    java.util.Arrays.copyOf(haves, count)).encode());
        }
        if (now > nextStats) {
            nextStats = now + STATS_NANOS;
            if (RenderSettings.performanceLogs() && (received > 0 || !inFlight.isEmpty())) {
                Farfield.LOG.info("LOD streaming: {} KB received in 10 s ({} sections, {} unchanged, {} preview tiles),"
                                + " {} in flight, {} wanted, {} known regions, {} sections and {} tiles held",
                        received >> 10, sectionsReceived, unchangedReceived, tilesReceived, inFlight.size(),
                        wishes.size(), knownRegions.size(), local.size(), localTiles.size());
            }
            received = 0;
            sectionsReceived = 0;
            unchangedReceived = 0;
            tilesReceived = 0;
        }
    }

    /** Asks for the preview tiles within the radius not checked yet this session, nearest first. */
    private void askTiles(Minecraft mc, long now) {
        var player = mc.player;
        if (player == null) {
            return;
        }
        double px = player.getX();
        double pz = player.getZ();
        int tile = PreviewTile.BLOCKS;
        int x0 = Math.floorDiv((int) Math.floor(px - radius), tile);
        int x1 = Math.floorDiv((int) Math.floor(px + radius), tile);
        int z0 = Math.floorDiv((int) Math.floor(pz - radius), tile);
        int z1 = Math.floorDiv((int) Math.floor(pz + radius), tile);
        java.util.List<long[]> candidates = new java.util.ArrayList<>();
        for (int tz = z0; tz <= z1; tz++) {
            for (int tx = x0; tx <= x1; tx++) {
                long key = PreviewStore.key(tx, tz);
                if (tilesAnswered.contains(key) || tilesInFlight.containsKey(key)) {
                    continue;
                }
                double dx = Math.max(0, Math.max((double) tx * tile - px, px - ((double) tx + 1) * tile));
                double dz = Math.max(0, Math.max((double) tz * tile - pz, pz - ((double) tz + 1) * tile));
                double d = dx * dx + dz * dz;
                if (d <= (double) radius * radius) {
                    candidates.add(new long[]{key, (long) d});
                }
            }
        }
        candidates.sort((a, b) -> Long.compare(a[1], b[1]));
        int n = Math.min(candidates.size(), TILES_IN_FLIGHT - tilesInFlight.size());
        if (n <= 0) {
            return;
        }
        long[] tiles = new long[n];
        long[] haves = new long[n];
        for (int i = 0; i < n; i++) {
            tiles[i] = candidates.get(i)[0];
            haves[i] = localTiles.get(tiles[i]);
            tilesInFlight.put(tiles[i], now);
        }
        send(new StreamProtocol.TileFetch(session, tiles, haves).encode());
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
                case StreamProtocol.TILE -> c.tile(StreamProtocol.Tile.decode(message));
                case StreamProtocol.TILE_ABSENT, StreamProtocol.TILE_UNCHANGED, StreamProtocol.TILE_CHANGED ->
                        c.tileAnswer(StreamProtocol.Answer.decode(message));
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
        blockStatesExpected = welcome.blockStates();
        biomesExpected = welcome.biomes();
        remote.clear();
        knownRegions.clear();
        askedRegions.clear();
        inFlight.clear();
        wishes.clear();
        background.clear();
        parts.clear();
        tilesAnswered.clear();
        tilesInFlight.clear();
        tileParts.clear();
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
            int id = registry.idForced(keys.keys()[i]);
            if (id != keys.from() + i) {
                // The mirror's tables disagree with the server's: start it again, at most twice.
                mismatches++;
                Farfield.LOG.warn("LOD streaming: key {} is {} here and {} on the server; {}", keys.keys()[i], id,
                        keys.from() + i, mismatches > MAX_MISMATCHES ? "building the LODs from the chunks instead"
                                : "emptying the mirror");
                wipe(0);
                session = -1;
                helloSent = 0;
                if (mismatches > MAX_MISMATCHES) {
                    declined = true;
                }
                return;
            }
        }
        int end = keys.from() + keys.keys().length;
        if (keys.kind() == StreamProtocol.BLOCK_STATES) {
            serverBlockStates = Math.max(serverBlockStates, end);
        } else {
            serverBiomes = Math.max(serverBiomes, end);
        }
    }

    private void index(StreamProtocol.Index index) {
        if (index.session() != session) {
            return;
        }
        long region = LodKey.pack(index.level(), index.rx(), 0, index.rz());
        for (int i = 0; i < index.keys().length; i++) {
            remote.put(index.keys()[i], index.versions()[i]);
            if (index.level() == LodWorld.LEVELS - 1 && local.get(index.keys()[i]) != index.versions()[i]) {
                background.add(index.keys()[i]);
            }
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

    private void tile(StreamProtocol.Tile part) throws IOException {
        if (part.session() != session) {
            return;
        }
        byte[] file;
        if (part.parts() == 1) {
            file = part.data();
        } else {
            byte[][] got = tileParts.computeIfAbsent(part.tile(), k -> new byte[part.parts()][]);
            if (got.length != part.parts()) {
                tileParts.remove(part.tile());
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
            tileParts.remove(part.tile());
            file = new byte[length];
            int at = 0;
            for (byte[] b : got) {
                System.arraycopy(b, 0, file, at, b.length);
                at += b.length;
            }
        }
        tilesInFlight.remove(part.tile());
        tilesAnswered.add(part.tile());
        mirror.preview().writeFile(PreviewStore.tileX(part.tile()), PreviewStore.tileZ(part.tile()), file);
        localTiles.put(part.tile(), part.version());
        tilesReceived++;
    }

    private void tileAnswer(StreamProtocol.Answer answer) {
        if (answer.session() != session) {
            return;
        }
        if (answer.type() == StreamProtocol.TILE_CHANGED) {
            tilesAnswered.remove(answer.key()); // asked again at the next scan
            return;
        }
        tilesInFlight.remove(answer.key());
        tilesAnswered.add(answer.key());
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
