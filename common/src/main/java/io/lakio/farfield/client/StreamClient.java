package io.lakio.farfield.client;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.preview.PreviewStore;
import io.lakio.farfield.core.preview.PreviewTile;
import io.lakio.farfield.core.storage.SectionStore;
import io.lakio.farfield.core.stream.FetchWindow;
import io.lakio.farfield.core.stream.KeyTranslation;
import io.lakio.farfield.core.stream.StreamProtocol;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.core.world.RemoteWorldId;
import io.lakio.farfield.lod.BlockKeys;
import io.lakio.farfield.lod.RenderSettings;
import io.lakio.farfield.platform.Services;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The client side of LOD streaming (ADR-068): on a server with Farfield, the
 * LODs come from the server's store into a mirror of it, per server and
 * dimension ({@code farfield/servers/<server>/<seed>/stream/<dimension>}),
 * instead of being built from the chunks the client sees (client mode,
 * {@link LodClient}). The renderer asks it which sections exist (region
 * indexes from the server), and hands it the sections it wants, nearest
 * and in view first; at most {@link StreamProtocol.Fetch#MAX_KEYS} are in
 * flight, fewer when the answers come back late ({@link FetchWindow}).
 * Received records name blocks and biomes by the server's ids: a writer
 * thread translates them to the mirror's ({@link KeyTranslation}) and
 * stores them, so the mirror keeps key tables of its own. The versions it
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
    /** Written once the mirror's coarse levels are known to be the server's (ADR-083). */
    private static final String COARSE_MARKER_FILE = "coarse.v1";
    private static final String TILES_FILE = "tiles.bin";
    private static final String BLOCK_KEYS_FILE = "server-block-states.txt";
    private static final String BIOME_KEYS_FILE = "server-biomes.txt";
    /** Hellos without an answer before the client builds its own LODs (a server of another version). */
    private static final int HELLO_ATTEMPTS = 6;
    private static final long TILE_SCAN_NANOS = 250_000_000L;
    /** Answers this much later than the quickest one halve the requests in flight. */
    private static final long WINDOW_SLACK_NANOS = 1_000_000_000L;
    private static final int WINDOW_MIN = 4;

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
     * The server's keys of each table and their ids in the mirror (kept with
     * the epoch): their counts are announced at hello, so the server never
     * skips a key the mirror lacks.
     */
    private KeyTranslation blockKeys;
    private KeyTranslation biomeKeys;
    private long helloSent;
    private int hellos;
    /** Translates and stores received sections and tiles, in order, off the client thread. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "farfield-stream-mirror");
        t.setDaemon(true);
        return t;
    });
    /** Sections and tiles the writer could not store: fetched again. */
    private final ConcurrentLinkedQueue<Long> lostSections = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> lostTiles = new ConcurrentLinkedQueue<>();
    /** Sections whose ids differed from the server's (counted by the writer). */
    private final java.util.concurrent.atomic.AtomicInteger translated = new java.util.concurrent.atomic.AtomicInteger();
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
     * blended twice, its grass over real sand). Also every section the
     * mirror holds at an older version than an index says: the renderer
     * asks only for sections it lacks, so a drawn copy fetched while its
     * chunks were still generating stayed drawn (black holes over the sea,
     * where a surface section lacked a chunk the section under it had).
     */
    private final LongLinkedOpenHashSet background = new LongLinkedOpenHashSet();
    private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
    private final FetchWindow window = new FetchWindow(WINDOW_MIN, StreamProtocol.Fetch.MAX_KEYS,
            StreamProtocol.Fetch.MAX_KEYS, WINDOW_SLACK_NANOS);
    /** The same for preview tiles, apart: a tile weighs about six sections (14 KB against 2.5). */
    private final FetchWindow tileWindow = new FetchWindow(1, StreamProtocol.TileFetch.MAX_TILES,
            StreamProtocol.TileFetch.MAX_TILES, WINDOW_SLACK_NANOS);
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
    private int regionsChanged;
    /** Sections held at an older version than an index said: fetched again. */
    private int outdated;
    /** Outdated sections and when an index said so, until their new version lands. */
    private final Long2LongOpenHashMap outdatedSince = new Long2LongOpenHashMap();
    /** Outdated sections refreshed since the stat line, their summed and longest wait since their index. */
    private int refreshed;
    private long refreshSum;
    private long refreshMax;
    /** Sections the server pushed (ADR-075), unasked, since the stat line. */
    private int pushedReceived;
    /** Finer region indexes asked again because a coarser index moved, since the stat line. */
    private int cascaded;
    /** The renderer's split detail, and the one last told the server (-1: not yet this session). */
    private double detail = 4.0;
    private double hintSent = -1;

    private StreamClient(ResourceKey<Level> dimension, Path dir) {
        this.dimension = dimension;
        this.dir = dir;
        remote.defaultReturnValue(-1);
        local.defaultReturnValue(-1);
        localTiles.defaultReturnValue(-1);
        outdatedSince.defaultReturnValue(-1);
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
     * Welcomed, and the mirror holds every key the server had then: the
     * sections drawn can all be translated.
     */
    private boolean ready() {
        return !declined && session >= 0 && blockKeys.size() >= blockStatesExpected
                && biomeKeys.size() >= biomesExpected;
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
        mirror = LodWorld.openMirror(dir, BlockKeys.RESOLVER);
        blockKeys = new KeyTranslation(mirror.blockStates());
        biomeKeys = new KeyTranslation(mirror.biomes());
        Path epochFile = dir.resolve(EPOCH_FILE);
        cachedEpoch = 0;
        local.clear();
        localTiles.clear();
        if (Files.exists(epochFile)) {
            try {
                // "<epoch> <block-state keys> <biome keys>" as received, the server's keys beside.
                String[] parts = Files.readString(epochFile).trim().split("\\s+");
                int blockStates = Integer.parseInt(parts[1]);
                int biomes = Integer.parseInt(parts[2]);
                boolean held = blockKeys.read(dir.resolve(BLOCK_KEYS_FILE))
                        && biomeKeys.read(dir.resolve(BIOME_KEYS_FILE)) || adoptOwnTables(blockStates, biomes);
                if (held && blockKeys.size() == blockStates && biomeKeys.size() == biomes) {
                    cachedEpoch = Long.parseUnsignedLong(parts[0], 16);
                    readVersions(VERSIONS_FILE, local);
                    readVersions(TILES_FILE, localTiles);
                    forgetLocalCoarseLevels();
                }
            } catch (RuntimeException | IOException e) {
                cachedEpoch = 0;
                local.clear();
                localTiles.clear();
            }
            if (cachedEpoch == 0) {
                blockKeys.clear();
                biomeKeys.clear();
            }
        }
    }

    /**
     * A mirror written before the translation kept the server's tables as its
     * own, id for id, and no file of the server's keys: those are its tables.
     */
    private boolean adoptOwnTables(int blockStates, int biomes) {
        if (mirror.blockStates().size() != blockStates || mirror.biomes().size() != biomes) {
            return false;
        }
        String[] b = new String[blockStates];
        for (int i = 0; i < blockStates; i++) {
            b[i] = mirror.blockStates().key(i);
        }
        String[] m = new String[biomes];
        for (int i = 0; i < biomes; i++) {
            m[i] = mirror.biomes().key(i);
        }
        blockKeys.clear();
        biomeKeys.clear();
        return blockKeys.add(0, b) && biomeKeys.add(0, m);
    }

    /** Waits until the writer stored everything received so far. */
    private void drainWriter() {
        try {
            writer.submit(() -> {
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            Farfield.LOG.warn("LOD streaming: the mirror's writer did not finish", e);
        }
    }

    /**
     * Once per mirror: forgets the versions held of its coarse levels, so
     * they are fetched again. A client of ADR-082 rebuilt them from its own
     * partial level 0 when it closed, and kept the server's versions for
     * them (ADR-083).
     */
    private void forgetLocalCoarseLevels() throws IOException {
        Path marker = dir.resolve(COARSE_MARKER_FILE);
        if (Files.exists(marker)) {
            return;
        }
        local.long2LongEntrySet().removeIf(e -> LodKey.level(e.getLongKey()) > 0);
        writeVersions(VERSIONS_FILE, local);
        Files.writeString(marker, "coarse levels are the server's" + System.lineSeparator());
        Farfield.LOG.info("LOD stream mirror: its coarse levels are fetched again from the server, once");
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
        drainWriter();
        mirror.close();
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
        mirror = LodWorld.open(dir, BlockKeys.RESOLVER);
        blockKeys = new KeyTranslation(mirror.blockStates());
        biomeKeys = new KeyTranslation(mirror.biomes());
        local.clear();
        localTiles.clear();
        cachedEpoch = epoch;
    }

    private void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                Farfield.LOG.warn("LOD streaming: the mirror's writer did not finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dropLost();
        try {
            Path epochFile = dir.resolve(EPOCH_FILE);
            if (session >= 0) {
                writeVersions(VERSIONS_FILE, local);
                writeVersions(TILES_FILE, localTiles);
                blockKeys.write(dir.resolve(BLOCK_KEYS_FILE));
                biomeKeys.write(dir.resolve(BIOME_KEYS_FILE));
                Files.writeString(epochFile, Long.toUnsignedString(cachedEpoch, 16) + " " + blockKeys.size() + " "
                        + biomeKeys.size());
            } else {
                Files.deleteIfExists(epochFile); // not welcomed, or the keys disagreed: emptied at the next session
            }
            mirror.close();
            Farfield.LOG.info("LOD stream mirror of {} closed: {} sections and {} preview tiles held, {} keys of the"
                            + " server's and {} of its own", dimension.location(), local.size(), localTiles.size(),
                    blockKeys.size() + biomeKeys.size(), mirror.blockStates().size() + mirror.biomes().size()
                            - blockKeys.size() - biomeKeys.size());
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("LOD streaming: closing the mirror of {} failed", dimension.location(), e);
        }
    }

    private void update(Minecraft mc) {
        long now = System.nanoTime();
        if (session < 0 && !declined && now - helloSent > HELLO_RETRY_NANOS) {
            if (hellos >= HELLO_ATTEMPTS) {
                // A server whose Farfield speaks another protocol and does not say so (before 0.2.0).
                declined = true;
                Farfield.LOG.info("LOD streaming: no answer from the server's Farfield after {} tries (another"
                        + " version?); building the LODs from its chunks", hellos);
                return;
            }
            hellos++;
            helloSent = now;
            send(new StreamProtocol.Hello(StreamProtocol.VERSION, dimension.location().toString(), cachedEpoch,
                    blockKeys.size(), biomeKeys.size()).encode());
        }
        if (session < 0) {
            return;
        }
        if (hintSent < 0 || Math.abs(detail - hintSent) > hintSent * 0.05) {
            hintSent = detail;
            send(new StreamProtocol.Hint(session, detail).encode());
        }
        dropLost();
        // Requests the server never answered (it dropped them): asked again later.
        if (inFlight.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_NANOS)) {
            window.timedOut(now);
        }
        if (tilesInFlight.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_NANOS)) {
            tileWindow.timedOut(now);
        }
        if (RenderSettings.noisePreview() && now > nextTileScan && tilesInFlight.size() < tileWindow.size()) {
            nextTileScan = now + TILE_SCAN_NANOS;
            askTiles(mc, now);
        }
        askedRegions.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_NANOS);
        while (inFlight.size() < window.size() && (!wishes.isEmpty() || !background.isEmpty())) {
            int n = Math.min(wishes.size() + background.size(), window.size() - inFlight.size());
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
                Farfield.LOG.info("LOD streaming: {} KB received in 10 s ({} sections, {} of them pushed, {} unchanged,"
                                + " {} preview tiles, {} regions changed, {} finer indexes asked again, {} outdated,"
                                + " {} translated), {} in flight of {}, {} tiles of {},"
                                + " {} wanted, {} known regions, {} sections and {} tiles held; {} outdated sections"
                                + " refreshed {} ms after their index on average, {} ms at most, {} waiting",
                        received >> 10, sectionsReceived, pushedReceived, unchangedReceived, tilesReceived,
                        regionsChanged, cascaded, outdated,
                        translated.getAndSet(0), inFlight.size(), window.size(), tilesInFlight.size(),
                        tileWindow.size(), wishes.size(), knownRegions.size(), local.size(), localTiles.size(),
                        refreshed, refreshed == 0 ? 0 : refreshSum / refreshed / 1_000_000, refreshMax / 1_000_000,
                        outdatedSince.size());
            }
            refreshed = 0;
            refreshSum = 0;
            refreshMax = 0;
            pushedReceived = 0;
            cascaded = 0;
            received = 0;
            sectionsReceived = 0;
            unchangedReceived = 0;
            tilesReceived = 0;
            regionsChanged = 0;
            outdated = 0;
        }
    }

    /** Forgets what the writer could not store, so it is fetched again. */
    private void dropLost() {
        Long key;
        while ((key = lostSections.poll()) != null) {
            local.remove(key.longValue());
        }
        while ((key = lostTiles.poll()) != null) {
            localTiles.remove(key.longValue());
            tilesAnswered.remove(key.longValue());
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
        int n = Math.min(candidates.size(), tileWindow.size() - tilesInFlight.size());
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

    /** What the stream knows of {@code key}, for {@code debug here}. Client thread. */
    public String describe(long key) {
        long region = regionOf(key);
        String index = !knownRegions.contains(region)
                ? (askedRegions.containsKey(region) ? "region index asked, not received" : "region index unknown")
                : remote.containsKey(key) ? "in the server's index" : "not in the server's index";
        long held = local.get(key);
        String mirror = held < 0 ? "not held" : held == remote.get(key) ? "held, current" : "held, outdated";
        return index + ", " + mirror + (inFlight.containsKey(key) ? ", in flight" : "")
                + (wishes.contains(key) ? ", wished" : "") + (background.contains(key) ? ", queued in background" : "");
    }

    /**
     * The renderer's split detail ({@link StreamProtocol.Hint}): told the
     * server when it moves, so it pushes each level where it is drawn.
     */
    public void setDetail(double detail) {
        this.detail = detail;
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
                case StreamProtocol.CHANGED -> c.changed(StreamProtocol.Changed.decode(message));
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
            if (welcome.radiusBlocks() > 0) {
                Farfield.LOG.info("LOD streaming: the server's Farfield speaks protocol {}, this one {}; building the"
                        + " LODs from its chunks", welcome.radiusBlocks(), StreamProtocol.VERSION);
            } else {
                Farfield.LOG.info("LOD streaming: the server does not stream; building the LODs from its chunks");
            }
            return;
        }
        if (!welcome.dimension().equals(dimension.location().toString())) {
            return;
        }
        if (welcome.epoch() != cachedEpoch) {
            wipe(welcome.epoch());
        }
        session = welcome.session();
        hellos = 0;
        hintSent = -1;
        radius = welcome.radiusBlocks();
        blockStatesExpected = welcome.blockStates();
        biomesExpected = welcome.biomes();
        remote.clear();
        knownRegions.clear();
        askedRegions.clear();
        inFlight.clear();
        window.reset(StreamProtocol.Fetch.MAX_KEYS);
        wishes.clear();
        background.clear();
        outdatedSince.clear();
        parts.clear();
        tilesAnswered.clear();
        tilesInFlight.clear();
        tileWindow.reset(StreamProtocol.TileFetch.MAX_TILES);
        tileParts.clear();
        generation++;
        Farfield.LOG.info("LOD streaming from the server: {} (session {}, radius {} blocks, {} sections held)",
                dimension.location(), session, radius, local.size());
    }

    private void keys(StreamProtocol.Keys keys) throws IOException {
        if (keys.session() != session) {
            return;
        }
        KeyTranslation table = keys.kind() == StreamProtocol.BLOCK_STATES ? blockKeys : biomeKeys;
        if (!table.add(keys.from(), keys.keys())) {
            // The server's keys disagree with those the mirror holds: start it again, at most twice.
            mismatches++;
            Farfield.LOG.warn("LOD streaming: keys from {} disagree with the server's held here ({}); {}",
                    keys.from(), table.size(), mismatches > MAX_MISMATCHES
                            ? "building the LODs from the chunks instead" : "emptying the mirror");
            wipe(0);
            session = -1;
            helloSent = 0;
            if (mismatches > MAX_MISMATCHES) {
                declined = true;
            }
        }
    }

    private void index(StreamProtocol.Index index) {
        if (index.session() != session) {
            return;
        }
        long region = LodKey.pack(index.level(), index.rx(), 0, index.rz());
        LongOpenHashSet finer = null;
        // A region read for the first time moves nothing the renderer relied on.
        boolean refresh = knownRegions.contains(region);
        for (int i = 0; i < index.keys().length; i++) {
            long key = index.keys()[i];
            long before = remote.put(key, index.versions()[i]);
            if (refresh && before != index.versions()[i] && index.level() > 0) {
                // A coarse section moved: its children did first. Their region's index is asked
                // again now, or the renderer would split the new parent over children it does not
                // know yet and draw nothing there (regions are announced each on its own clock).
                if (finer == null) {
                    finer = new LongOpenHashSet();
                }
                finer.add(regionOf(LodKey.pack(index.level() - 1, LodKey.x(key) * 2, 0, LodKey.z(key) * 2)));
            }
            long held = local.get(key);
            if (held != index.versions()[i] && (held >= 0 || index.level() == LodWorld.LEVELS - 1)) {
                background.add(key);
                if (held >= 0) {
                    outdated++;
                    outdatedSince.putIfAbsent(key, System.nanoTime());
                }
            }
        }
        if (index.last()) {
            knownRegions.add(region);
            askedRegions.remove(region);
            generation++;
        }
        if (finer != null) {
            LongIterator it = finer.iterator();
            while (it.hasNext()) {
                long r = it.nextLong();
                if (knownRegions.contains(r) && !askedRegions.containsKey(r)) {
                    askedRegions.put(r, System.nanoTime());
                    send(new StreamProtocol.IndexRequest(session, LodKey.level(r), LodKey.x(r), LodKey.z(r)).encode());
                    cascaded++;
                }
            }
        }
    }

    /**
     * Regions written on the server since their indexes came: asked again, so
     * the sections whose version moved are fetched (the stale copies stay
     * drawn meanwhile). Regions never asked for are left to the renderer.
     */
    private void changed(StreamProtocol.Changed changed) {
        if (changed.session() != session) {
            return;
        }
        for (long region : changed.regions()) {
            if (knownRegions.contains(region) || askedRegions.containsKey(region)) {
                askedRegions.put(region, System.nanoTime());
                send(new StreamProtocol.IndexRequest(session, LodKey.level(region), LodKey.x(region),
                        LodKey.z(region)).encode());
                regionsChanged++;
            }
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
        if (!inFlight.containsKey(part.key())) {
            pushedReceived++; // unasked: the server pushed it (ADR-075)
        }
        local.put(part.key(), part.version());
        remote.put(part.key(), part.version());
        landed(part.key());
        refreshedNow(part.key());
        sectionsReceived++;
        long key = part.key();
        LodWorld m = mirror;
        int[] blocks = blockKeys.map();
        int[] biomes = biomeKeys.map();
        byte[] received = record;
        writer.execute(() -> {
            try {
                LodSection section = m.decodeRecord(key, received);
                if (section.remapIds(blocks, biomes)) {
                    translated.incrementAndGet();
                    m.putSection(key, section);
                } else {
                    m.putRecord(key, received, 0, received.length); // the same ids: kept as it came
                }
            } catch (IOException | RuntimeException e) {
                lostSections.add(key);
                Farfield.LOG.warn("LOD streaming: could not store {}", LodKey.toString(key), e);
            }
        });
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
        tileLanded(part.tile());
        tilesAnswered.add(part.tile());
        localTiles.put(part.tile(), part.version());
        tilesReceived++;
        long key = part.tile();
        LodWorld m = mirror;
        int[] blocks = blockKeys.map();
        int[] biomes = biomeKeys.map();
        byte[] received = file;
        writer.execute(() -> {
            try {
                PreviewTile tile = PreviewStore.decodeFile(received);
                tile.remapIds(blocks, biomes);
                m.preview().write(PreviewStore.tileX(key), PreviewStore.tileZ(key), tile);
            } catch (IOException | RuntimeException e) {
                lostTiles.add(key);
                Farfield.LOG.warn("LOD streaming: could not store preview tile {} {}", PreviewStore.tileX(key),
                        PreviewStore.tileZ(key), e);
            }
        });
    }

    private void tileAnswer(StreamProtocol.Answer answer) {
        if (answer.session() != session) {
            return;
        }
        if (answer.type() == StreamProtocol.TILE_CHANGED) {
            tilesAnswered.remove(answer.key()); // asked again at the next scan
            return;
        }
        tileLanded(answer.key());
        tilesAnswered.add(answer.key());
    }

    /** A tile request was answered: no longer in flight, its round trip sizes the tile window. */
    private void tileLanded(long tile) {
        if (tilesInFlight.containsKey(tile)) {
            long now = System.nanoTime();
            tileWindow.answered(now - tilesInFlight.remove(tile), now);
        }
    }

    /** A section landed: if an index had said it was outdated, how long that took, for the stat line. */
    private void refreshedNow(long key) {
        long since = outdatedSince.remove(key);
        if (since >= 0) {
            long wait = System.nanoTime() - since;
            refreshed++;
            refreshSum += wait;
            refreshMax = Math.max(refreshMax, wait);
        }
    }

    /** A request was answered: no longer in flight, its round trip sizes the window. */
    private void landed(long key) {
        if (inFlight.containsKey(key)) {
            long now = System.nanoTime();
            window.answered(now - inFlight.remove(key), now);
        }
    }

    private void answer(StreamProtocol.Answer answer) {
        if (answer.session() != session) {
            return;
        }
        landed(answer.key());
        outdatedSince.remove(answer.key());
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
