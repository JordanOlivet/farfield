package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.config.ServerConfig;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.preview.PreviewStore;
import io.lakio.farfield.core.preview.PreviewTile;
import io.lakio.farfield.core.storage.SectionStore;
import io.lakio.farfield.core.stream.Bandwidth;
import io.lakio.farfield.core.stream.RegionChanges;
import io.lakio.farfield.core.stream.StreamProtocol;
import io.lakio.farfield.core.stream.SurfaceIndex;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.BlockKeys;
import io.lakio.farfield.lod.RenderSettings;
import io.lakio.farfield.platform.Services;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves the LODs of this server's stores to the players whose client has
 * Farfield (ADR-068). The client pulls what it has never seen: it opens a
 * session per dimension ({@link StreamProtocol.Hello}), asks for region
 * indexes and for sections; a small pool reads them from the store, and the
 * server tick sends the answers within each player's budget and the budget
 * shared by all, so the game's own packets never wait behind a burst.
 * Requests beyond the radius around the player are refused. The server
 * pushes what changes (ADR-075): every section written where a player's
 * client draws its level is queued for it at once, in write order, and read
 * when the player's queue runs low, so a section written again meanwhile
 * goes once. Thread-safe; one instance per server.
 */
public final class StreamServer {

    private static final String CONFIG_FILE = "farfield-server.properties";
    private static final long STATS_NANOS = 10_000_000_000L;
    /** How often written regions are checked for notices, and their quiet time and longest wait. */
    private static final long NOTICE_NANOS = 1_000_000_000L;
    private static final long NOTICE_QUIET_NANOS = 3_000_000_000L;
    private static final long NOTICE_MAX_WAIT_NANOS = 20_000_000_000L;
    /** Pushed sections read per task. */
    private static final int PUSH_BATCH = 32;
    /**
     * Pushes are read while fewer messages wait than this: a section written
     * again while it waits in the push queue goes once, at its latest version.
     */
    private static final int PUSH_QUEUE_LOW = 64;
    /** Beyond the client's draw distance of a level: the player moves on before its client asks again. */
    private static final double PUSH_MARGIN = 1.25;
    /** A client's detail before its first hint (the renderer's usual 3 to 4). */
    private static final double DEFAULT_DETAIL = 4.0;
    private static final int RECORD_CACHE = 512;

    private static volatile StreamServer instance;
    /** The settings read at the last server start, streaming on or off: the preview reads its own there. */
    private static volatile ServerConfig settings = ServerConfig.DEFAULTS;
    /** The settings turned streaming off: hellos get a refusal, so clients build their own LODs. */
    private static volatile boolean off;

    private final MinecraftServer server;
    private final ServerConfig config;
    private final ExecutorService readers;
    private final Bandwidth shared;
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final AtomicInteger sessions = new AtomicInteger();
    private long nextStats;
    private final AtomicLong sections = new AtomicLong();
    private final AtomicLong unchanged = new AtomicLong();
    private final AtomicLong indexes = new AtomicLong();
    private final AtomicLong hiddenSent = new AtomicLong();
    private final AtomicLong tilesSent = new AtomicLong();
    /** Worlds whose preview store tells this server about new tiles. */
    private final Map<LodWorld, Boolean> tileListeners = new ConcurrentHashMap<>();
    /** Regions written per world since their last notice, and the listener feeding them. */
    private final Map<LodWorld, RegionChanges> changes = new ConcurrentHashMap<>();
    private final Map<LodWorld, java.util.function.LongConsumer> sectionListeners = new ConcurrentHashMap<>();
    private long nextNotice;
    private final AtomicLong noticesSent = new AtomicLong();
    /**
     * Sections under the surface per world and region (ADR-013), with the
     * region's fingerprint (its versions) they were computed for.
     */
    private final Map<LodWorld, Map<Long, Hidden>> hidden = new ConcurrentHashMap<>();

    private record Hidden(long fingerprint, java.util.Set<Long> keys) {
    }
    /**
     * The floor of each level-0 section column a push checked (ADR-013's
     * surface), per world; a write in the column drops it.
     */
    private final Map<LodWorld, Map<Long, Integer>> floors = new ConcurrentHashMap<>();
    /** Records read lately, per world: a section pushed to several players is read once per version. */
    private final Map<LodWorld, Map<Long, Record>> records = new ConcurrentHashMap<>();

    private record Record(long version, byte[] data) {
    }

    private final AtomicLong pushed = new AtomicLong();
    private final AtomicLong pushesHidden = new AtomicLong();
    private final AtomicLong pushWaitSum = new AtomicLong();
    private final AtomicLong pushWaitMax = new AtomicLong();
    private long bytes;

    /** One player's streaming: its current session (one dimension) and its queue of messages. */
    private final class Player {
        final ServerPlayer player;
        final Bandwidth budget;
        final ConcurrentLinkedQueue<byte[]> out = new ConcurrentLinkedQueue<>();
        volatile int session = -1;
        volatile ResourceKey<Level> dimension;
        volatile LodWorld world;
        /** Keys of each table the client holds; guarded by this. */
        int blockStatesSent;
        int biomesSent;
        /**
         * Sections written within the player's push distance since they were
         * last pushed, in write order (children before their parents), with
         * their first write's time; guarded by itself.
         */
        final java.util.LinkedHashMap<Long, Long> pushes = new java.util.LinkedHashMap<>();
        /** A task reads the next pushes. */
        volatile boolean pushing;
        /** The client's split detail ({@link StreamProtocol.Hint}). */
        volatile double detail = DEFAULT_DETAIL;

        Player(ServerPlayer player, long now) {
            this.player = player;
            this.budget = new Bandwidth(config.playerKBps() * 1024L, now);
        }
    }

    private StreamServer(MinecraftServer server, ServerConfig config) {
        this.server = server;
        this.config = config;
        int threads = config.effectiveThreads(Runtime.getRuntime().availableProcessors());
        AtomicInteger n = new AtomicInteger();
        this.readers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "farfield-stream-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.shared = new Bandwidth(config.totalKBps() * 1024L, System.nanoTime());
        this.nextStats = System.nanoTime() + STATS_NANOS;
        this.nextNotice = System.nanoTime() + NOTICE_NANOS;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Server starting: reads (or writes) the settings and starts streaming when they allow it. */
    public static void onServerStarting(MinecraftServer server) {
        Path file = Services.PLATFORM.getConfigDir().resolve(CONFIG_FILE);
        ServerConfig config;
        try {
            config = ServerConfig.read(file);
            config.write(file); // adds the keys of newer versions
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("Could not read {}, streaming settings at their defaults", file, e);
            config = ServerConfig.DEFAULTS;
        }
        settings = config;
        off = !config.streaming();
        if (off) {
            Farfield.LOG.info("LOD streaming off ({}): clients build their own LODs", CONFIG_FILE);
            instance = null;
            return;
        }
        instance = new StreamServer(server, config);
        Farfield.LOG.info("LOD streaming ready: radius {} blocks, {} KB/s per player, {} KB/s in all, {} threads, {},"
                        + " preview {}", config.radiusBlocks(), config.playerKBps(), config.totalKBps(),
                config.effectiveThreads(Runtime.getRuntime().availableProcessors()),
                config.fullData() ? "full data" : "surface only", config.preview() ? "on" : "off");
    }

    /** The settings read at the server's start ({@link ServerConfig#DEFAULTS} before). */
    public static ServerConfig settings() {
        return settings;
    }

    /** Whether this server streams the noise preview (a dedicated server then builds it). */
    public static boolean streamsPreview() {
        StreamServer s = instance;
        return s != null && s.config.preview();
    }

    public static void onServerStopping() {
        StreamServer s = instance;
        instance = null;
        if (s != null) {
            s.readers.shutdownNow();
            s.sectionListeners.forEach(LodWorld::removeListener);
        }
    }

    // ---------------------------------------------------------------- receiving (game thread)

    /** A message from a player's client. */
    public static void receive(ServerPlayer player, byte[] message) {
        StreamServer s = instance;
        if (s == null) {
            if (off && StreamProtocol.type(message) == StreamProtocol.HELLO) {
                // Session -1: not streamed here.
                Services.NETWORK.sendToPlayer(player, StreamProtocol.Welcome.declined(0).encode());
            }
            return;
        }
        try {
            switch (StreamProtocol.type(message)) {
                case StreamProtocol.HELLO -> s.hello(player, message);
                case StreamProtocol.INDEX_REQUEST -> s.index(player, StreamProtocol.IndexRequest.decode(message));
                case StreamProtocol.FETCH -> s.fetch(player, StreamProtocol.Fetch.decode(message));
                case StreamProtocol.TILE_FETCH -> s.tileFetch(player, StreamProtocol.TileFetch.decode(message));
                case StreamProtocol.HINT -> s.hint(player, StreamProtocol.Hint.decode(message));
                default -> {
                }
            }
        } catch (RuntimeException e) {
            Farfield.LOG.warn("LOD streaming: bad message from {}", player.getGameProfile().getName(), e);
        }
    }

    private void hello(ServerPlayer sp, byte[] message) {
        int version = StreamProtocol.Hello.version(message);
        if (version != StreamProtocol.VERSION) {
            // Declined, so its client builds its own LODs instead of waiting for a welcome; the rest
            // of the hello is not read, its shape may be another.
            Farfield.LOG.info("LOD streaming: {} speaks protocol {}, this server {}: not streamed",
                    sp.getGameProfile().getName(), version, StreamProtocol.VERSION);
            Services.NETWORK.sendToPlayer(sp, StreamProtocol.Welcome.declined(StreamProtocol.VERSION).encode());
            return;
        }
        StreamProtocol.Hello hello = StreamProtocol.Hello.decode(message);
        ResourceKey<Level> dimension = sp.level().dimension();
        if (!dimension.location().toString().equals(hello.dimension())) {
            return; // the player moved on since; its client sends another hello
        }
        LodWorld world = LodServer.world(dimension);
        if (world == null) {
            return;
        }
        long epoch;
        try {
            epoch = world.epoch();
        } catch (IOException e) {
            Farfield.LOG.warn("LOD streaming: no epoch for {}", dimension.location(), e);
            return;
        }
        Player p = players.compute(sp.getUUID(),
                (id, old) -> old != null && old.player == sp ? old : new Player(sp, System.nanoTime()));
        int session = sessions.incrementAndGet();
        synchronized (p.pushes) {
            p.pushes.clear();
        }
        synchronized (p) {
            p.out.clear();
            p.dimension = dimension;
            p.world = world;
            boolean sameStore = hello.epoch() == epoch;
            p.blockStatesSent = sameStore ? clampKeys(hello.blockStates(), world.blockStates()) : 0;
            p.biomesSent = sameStore ? clampKeys(hello.biomes(), world.biomes()) : 0;
            p.session = session;
            int blockStates = world.blockStates().size();
            int biomes = world.biomes().size();
            p.out.add(new StreamProtocol.Welcome(session, hello.dimension(), epoch, config.radiusBlocks(), blockStates,
                    biomes).encode());
            sendKeys(p, session);
        }
        changes.computeIfAbsent(world, w -> {
            RegionChanges c = new RegionChanges(NOTICE_QUIET_NANOS, NOTICE_MAX_WAIT_NANOS);
            java.util.function.LongConsumer listener = key -> {
                long now = System.nanoTime();
                c.written(key, now);
                written(w, key, now);
            };
            sectionListeners.put(w, listener);
            w.addListener(listener);
            return c;
        });
        if (config.preview()) {
            tileListeners.computeIfAbsent(world, w -> {
                w.preview().addListener(tile -> tileWritten(w, tile));
                return Boolean.TRUE;
            });
        }
        Farfield.LOG.info("LOD streaming to {}: {} (session {}, {} store)", sp.getGameProfile().getName(),
                dimension.location(), session, hello.epoch() == epoch ? "same" : "new");
    }

    private static int clampKeys(int held, KeyRegistry registry) {
        return Math.max(0, Math.min(held, registry.size()));
    }

    /** Queues the keys the client lacks; under the player's lock. */
    private void sendKeys(Player p, int session) {
        p.blockStatesSent = queueKeys(p, session, StreamProtocol.BLOCK_STATES, p.world.blockStates(), p.blockStatesSent);
        p.biomesSent = queueKeys(p, session, StreamProtocol.BIOMES, p.world.biomes(), p.biomesSent);
    }

    private static int queueKeys(Player p, int session, int kind, KeyRegistry registry, int from) {
        int size = registry.size();
        while (from < size) {
            List<String> keys = new ArrayList<>();
            int bytes = 0;
            while (from + keys.size() < size && bytes < StreamProtocol.MAX_PAYLOAD - 512) {
                String k = registry.key(from + keys.size());
                keys.add(k);
                bytes += k.length() * 3 + 2;
            }
            p.out.add(new StreamProtocol.Keys(session, kind, from, keys.toArray(new String[0])).encode());
            from += keys.size();
        }
        return from;
    }

    private Player current(ServerPlayer sp, int session) {
        Player p = players.get(sp.getUUID());
        if (p == null || p.session != session || p.dimension != sp.level().dimension()) {
            return null;
        }
        return p;
    }

    private void index(ServerPlayer sp, StreamProtocol.IndexRequest request) {
        Player p = current(sp, request.session());
        if (p == null || request.level() < 0 || request.level() >= LodWorld.LEVELS
                || !regionInRange(sp, request.level(), request.rx(), request.rz())) {
            return;
        }
        readers.execute(() -> {
            List<Long> keys = new ArrayList<>();
            List<Long> versions = new ArrayList<>();
            try {
                p.world.forEachVersion(request.level(), request.rx(), request.rz(), (k, v) -> {
                    keys.add(k);
                    versions.add(v);
                });
            } catch (IOException e) {
                Farfield.LOG.warn("LOD streaming: index of {} failed", request, e);
                return;
            }
            indexes.incrementAndGet();
            if (!config.fullData()) {
                java.util.Set<Long> under = hiddenKeys(p.world, request.level(), request.rx(), request.rz(), keys,
                        versions);
                if (!under.isEmpty()) {
                    hiddenSent.addAndGet(under.size());
                    for (int i = keys.size() - 1; i >= 0; i--) {
                        if (under.contains(keys.get(i))) {
                            keys.remove(i);
                            versions.remove(i);
                        }
                    }
                }
            }
            int n = keys.size();
            int from = 0;
            do {
                int to = Math.min(n, from + StreamProtocol.Index.MAX_ENTRIES);
                long[] k = new long[to - from];
                long[] v = new long[to - from];
                for (int i = from; i < to; i++) {
                    k[i - from] = keys.get(i);
                    v[i - from] = versions.get(i);
                }
                p.out.add(new StreamProtocol.Index(request.session(), request.level(), request.rx(), request.rz(),
                        to == n, k, v).encode());
                from = to;
            } while (from < n);
        });
    }

    private void fetch(ServerPlayer sp, StreamProtocol.Fetch fetch) {
        Player p = current(sp, fetch.session());
        if (p == null) {
            return;
        }
        double px = sp.getX();
        double pz = sp.getZ();
        readers.execute(() -> {
            for (int i = 0; i < fetch.keys().length; i++) {
                long key = fetch.keys()[i];
                if (p.session != fetch.session()) {
                    return;
                }
                if (!inRange(key, px, pz) || !config.fullData() && underSurface(p.world, key)) {
                    p.out.add(new StreamProtocol.Answer(StreamProtocol.ABSENT, fetch.session(), key).encode());
                    continue;
                }
                try {
                    long version = p.world.version(key);
                    if (version < 0) {
                        p.out.add(new StreamProtocol.Answer(StreamProtocol.ABSENT, fetch.session(), key).encode());
                    } else if (version == fetch.haves()[i]) {
                        unchanged.incrementAndGet();
                        p.out.add(new StreamProtocol.Answer(StreamProtocol.UNCHANGED, fetch.session(), key).encode());
                    } else {
                        byte[] record = p.world.readRecord(key);
                        long read = p.world.version(key);
                        if (record == null) {
                            p.out.add(new StreamProtocol.Answer(StreamProtocol.ABSENT, fetch.session(), key).encode());
                            continue;
                        }
                        synchronized (p) {
                            if (p.session != fetch.session()) {
                                return;
                            }
                            // The keys the record uses go first: the client maps its ids with them.
                            sendKeys(p, fetch.session());
                            for (StreamProtocol.Section part : StreamProtocol.Section.split(fetch.session(), key, read,
                                    record)) {
                                p.out.add(part.encode());
                            }
                        }
                        sections.incrementAndGet();
                    }
                } catch (IOException | RuntimeException e) {
                    Farfield.LOG.warn("LOD streaming: reading {} failed", LodKey.toString(key), e);
                    p.out.add(new StreamProtocol.Answer(StreamProtocol.ABSENT, fetch.session(), key).encode());
                }
            }
        });
    }

    private void tileFetch(ServerPlayer sp, StreamProtocol.TileFetch fetch) {
        Player p = current(sp, fetch.session());
        if (p == null || !config.preview()) {
            return;
        }
        double px = sp.getX();
        double pz = sp.getZ();
        readers.execute(() -> {
            for (int i = 0; i < fetch.tiles().length; i++) {
                long tile = fetch.tiles()[i];
                if (p.session != fetch.session()) {
                    return;
                }
                int tx = PreviewStore.tileX(tile);
                int tz = PreviewStore.tileZ(tile);
                byte[] file = null;
                if (tileInRange(tx, tz, px, pz)) {
                    try {
                        file = p.world.preview().readFile(tx, tz);
                    } catch (IOException e) {
                        Farfield.LOG.warn("LOD streaming: reading preview tile {} {} failed", tx, tz, e);
                    }
                }
                if (file == null) {
                    p.out.add(new StreamProtocol.Answer(StreamProtocol.TILE_ABSENT, fetch.session(), tile).encode());
                    continue;
                }
                long version = PreviewStore.version(file);
                if (version == fetch.haves()[i]) {
                    p.out.add(new StreamProtocol.Answer(StreamProtocol.TILE_UNCHANGED, fetch.session(), tile).encode());
                    continue;
                }
                synchronized (p) {
                    if (p.session != fetch.session()) {
                        return;
                    }
                    // A tile names biomes and plants by the store's ids: their keys go first.
                    sendKeys(p, fetch.session());
                    for (StreamProtocol.Tile part : StreamProtocol.Tile.split(fetch.session(), tile, version, file)) {
                        p.out.add(part.encode());
                    }
                }
                tilesSent.incrementAndGet();
            }
        });
    }

    /** A tile was built: the players near it ask for it again. Any thread. */
    private void tileWritten(LodWorld world, long tile) {
        int tx = PreviewStore.tileX(tile);
        int tz = PreviewStore.tileZ(tile);
        for (Player p : players.values()) {
            int session = p.session;
            if (session >= 0 && p.world == world && tileInRange(tx, tz, p.player.getX(), p.player.getZ())) {
                p.out.add(new StreamProtocol.Answer(StreamProtocol.TILE_CHANGED, session, tile).encode());
            }
        }
    }

    private boolean tileInRange(int tx, int tz, double px, double pz) {
        double x0 = (double) tx * PreviewTile.BLOCKS;
        double z0 = (double) tz * PreviewTile.BLOCKS;
        double dx = Math.max(0, Math.max(x0 - px, px - (x0 + PreviewTile.BLOCKS)));
        double dz = Math.max(0, Math.max(z0 - pz, pz - (z0 + PreviewTile.BLOCKS)));
        double r = config.radiusBlocks();
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * The sections of a region under the surface ({@link SurfaceIndex}),
     * computed once per state of the region (its keys and versions).
     */
    private java.util.Set<Long> hiddenKeys(LodWorld world, int level, int rx, int rz, List<Long> keys,
                                           List<Long> versions) {
        // Order-independent: the index lists keys in no fixed order.
        long sum = 0;
        for (int i = 0; i < keys.size(); i++) {
            sum += keys.get(i) * 0x9E3779B97F4A7C15L ^ versions.get(i);
        }
        long fingerprint = sum ^ keys.size();
        long region = LodKey.pack(level, rx, 0, rz);
        Map<Long, Hidden> regions = hidden.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
        Hidden cached = regions.get(region);
        if (cached != null && cached.fingerprint() == fingerprint) {
            return cached.keys();
        }
        long[] array = keys.stream().mapToLong(Long::longValue).toArray();
        java.util.Set<Long> under;
        try {
            under = java.util.Set.copyOf(SurfaceIndex.hidden(array, world::read, world.materials(), m -> leaves(world, m)));
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("LOD streaming: surface of region {} {} {} failed, sending all", level, rx, rz, e);
            under = java.util.Set.of();
        }
        regions.put(region, new Hidden(fingerprint, under));
        return under;
    }

    /** Whether a material of {@code world} is leaves: the LODs draw them with holes, the view passes. */
    private static boolean leaves(LodWorld world, int material) {
        String key = world.blockStates().key(material);
        net.minecraft.world.level.block.state.BlockState state = key == null ? null : BlockKeys.state(key);
        return state != null && state.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /**
     * Whether a section lies under the surface, so a direct request for a
     * section the index left out is refused too; when the region cannot be
     * read, refused.
     */
    private boolean underSurface(LodWorld world, long key) {
        int level = LodKey.level(key);
        int rx = LodKey.x(key) >> SectionStore.REGION_SHIFT;
        int rz = LodKey.z(key) >> SectionStore.REGION_SHIFT;
        List<Long> keys = new ArrayList<>();
        List<Long> versions = new ArrayList<>();
        try {
            world.forEachVersion(level, rx, rz, (k, v) -> {
                keys.add(k);
                versions.add(v);
            });
        } catch (IOException e) {
            return true;
        }
        return hiddenKeys(world, level, rx, rz, keys, versions).contains(key);
    }

    /** Whether a section lies within the radius around a player (horizontally, its nearest edge). */
    private boolean inRange(long key, double px, double pz) {
        int size = LodSection.SIZE << LodKey.level(key);
        double x0 = (double) LodKey.x(key) * size;
        double z0 = (double) LodKey.z(key) * size;
        double dx = Math.max(0, Math.max(x0 - px, px - (x0 + size)));
        double dz = Math.max(0, Math.max(z0 - pz, pz - (z0 + size)));
        double r = config.radiusBlocks();
        return dx * dx + dz * dz <= r * r;
    }

    private boolean regionInRange(ServerPlayer sp, int level, int rx, int rz) {
        return regionInRange(level, rx, rz, sp.getX(), sp.getZ());
    }

    private boolean regionInRange(int level, int rx, int rz, double px, double pz) {
        long size = (long) LodSection.SIZE << level << SectionStore.REGION_SHIFT;
        double x0 = (double) rx * size;
        double z0 = (double) rz * size;
        double dx = Math.max(0, Math.max(x0 - px, px - (x0 + size)));
        double dz = Math.max(0, Math.max(z0 - pz, pz - (z0 + size)));
        double r = config.radiusBlocks();
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * Tells each player streaming {@code world} which regions near it were
     * written since; its client asks for their indexes again, then for the
     * sections whose version moved. Server thread.
     */
    private void notice(LodWorld world, RegionChanges c, long now) {
        List<Long> due = new ArrayList<>();
        c.drainDue(now, due::add);
        if (due.isEmpty()) {
            return;
        }
        for (Player p : players.values()) {
            int session = p.session;
            if (session < 0 || p.world != world) {
                continue;
            }
            double px = p.player.getX();
            double pz = p.player.getZ();
            List<Long> near = new ArrayList<>();
            for (long r : due) {
                if (regionInRange(LodKey.level(r), LodKey.x(r), LodKey.z(r), px, pz)) {
                    near.add(r);
                }
            }
            for (int from = 0; from < near.size(); from += StreamProtocol.Changed.MAX_REGIONS) {
                long[] regions = near.subList(from, Math.min(near.size(), from + StreamProtocol.Changed.MAX_REGIONS))
                        .stream().mapToLong(Long::longValue).toArray();
                p.out.add(new StreamProtocol.Changed(session, regions).encode());
            }
            noticesSent.addAndGet(near.size());
        }
    }

    // ---------------------------------------------------------------- pushing (ADR-075)

    private void hint(ServerPlayer sp, StreamProtocol.Hint hint) {
        Player p = current(sp, hint.session());
        if (p != null) {
            p.detail = Math.max(1, hint.detail());
        }
    }

    /**
     * A section of {@code world} was written: queued for the players whose
     * client draws its level there. Any thread (the store's writers).
     */
    private void written(LodWorld world, long key, long now) {
        if (LodKey.level(key) == 0) {
            Map<Long, Integer> f = floors.get(world);
            if (f != null) {
                f.remove(LodKey.pack(0, LodKey.x(key), 0, LodKey.z(key)));
            }
        }
        for (Player p : players.values()) {
            if (p.session >= 0 && p.world == world && pushRange(p, key)) {
                synchronized (p.pushes) {
                    p.pushes.putIfAbsent(key, now);
                }
            }
        }
    }

    /**
     * Whether a section lies where the player's client draws its level
     * ({@link StreamProtocol.Hint#drawDistance}, with a margin), within the
     * radius; the top level everywhere in the radius (the preview hides
     * itself from it).
     */
    private boolean pushRange(Player p, long key) {
        int level = LodKey.level(key);
        int size = LodSection.SIZE << level;
        double x0 = (double) LodKey.x(key) * size;
        double z0 = (double) LodKey.z(key) * size;
        double px = p.player.getX();
        double pz = p.player.getZ();
        double dx = Math.max(0, Math.max(x0 - px, px - (x0 + size)));
        double dz = Math.max(0, Math.max(z0 - pz, pz - (z0 + size)));
        double r = Math.min(config.radiusBlocks(),
                StreamProtocol.Hint.drawDistance(level, LodWorld.LEVELS, p.detail) * PUSH_MARGIN + size);
        return dx * dx + dz * dz <= r * r;
    }

    /** Starts reading a player's next pushes when its queue runs low. Server thread. */
    private void startPush(Player p) {
        if (p.pushing || p.out.size() >= PUSH_QUEUE_LOW || p.session < 0) {
            return;
        }
        synchronized (p.pushes) {
            if (p.pushes.isEmpty()) {
                return;
            }
        }
        p.pushing = true;
        int session = p.session;
        try {
            readers.execute(() -> push(p, session));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            p.pushing = false; // stopping
        }
    }

    /** Reads up to {@link #PUSH_BATCH} pushes of a player and queues them as sections. Reader thread. */
    private void push(Player p, int session) {
        try {
            long[] keys = new long[PUSH_BATCH];
            long[] since = new long[PUSH_BATCH];
            int n = 0;
            synchronized (p.pushes) {
                Iterator<Map.Entry<Long, Long>> it = p.pushes.entrySet().iterator();
                while (n < PUSH_BATCH && it.hasNext()) {
                    Map.Entry<Long, Long> e = it.next();
                    keys[n] = e.getKey();
                    since[n++] = e.getValue();
                    it.remove();
                }
            }
            LodWorld world = p.world;
            for (int i = 0; i < n; i++) {
                long key = keys[i];
                if (p.session != session || p.world != world) {
                    return;
                }
                if (!pushRange(p, key)) {
                    continue; // the player moved away meanwhile
                }
                if (!config.fullData() && hiddenForPush(p, world, key)) {
                    pushesHidden.incrementAndGet();
                    continue;
                }
                Record record = record(world, key);
                if (record == null) {
                    continue;
                }
                synchronized (p) {
                    if (p.session != session) {
                        return;
                    }
                    // The keys the record uses go first: the client maps its ids with them.
                    sendKeys(p, session);
                    for (StreamProtocol.Section part : StreamProtocol.Section.split(session, key, record.version(),
                            record.data())) {
                        p.out.add(part.encode());
                    }
                }
                long wait = System.nanoTime() - since[i];
                pushed.incrementAndGet();
                pushWaitSum.addAndGet(wait);
                pushWaitMax.accumulateAndGet(wait, Math::max);
            }
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.warn("LOD streaming: pushing to {} failed", p.player.getGameProfile().getName(), e);
        } finally {
            p.pushing = false;
        }
    }

    /**
     * A section's record and the version it was read at, through a small
     * cache shared by the players; null if absent. A write between reading
     * the version and the record reads again.
     */
    private Record record(LodWorld world, long key) throws IOException {
        Map<Long, Record> cache = records.computeIfAbsent(world, w -> java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<>(RECORD_CACHE, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, Record> eldest) {
                        return size() > RECORD_CACHE;
                    }
                }));
        for (int attempt = 0; attempt < 3; attempt++) {
            long version = world.version(key);
            if (version < 0) {
                return null;
            }
            Record cached = cache.get(key);
            if (cached != null && cached.version() == version) {
                return cached;
            }
            byte[] data = world.readRecord(key);
            if (data == null) {
                return null;
            }
            if (world.version(key) == version) {
                Record read = new Record(version, data);
                cache.put(key, read);
                return read;
            }
        }
        return null; // written over and over: its next write pushes it again
    }

    /**
     * Whether a level-0 section lies under the surface (the index's rule,
     * {@link SurfaceIndex#hidden(long, java.util.function.LongToIntFunction)}),
     * from the floors of its column and of the eight around, without reading
     * the whole region.
     */
    private boolean hiddenForPush(Player p, LodWorld world, long key) throws IOException {
        if (LodKey.level(key) != 0) {
            return false;
        }
        Map<Long, Integer> f = floors.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
        if (f.size() > 1 << 18) {
            f.clear(); // a long flight: read again on demand
        }
        Level level = p.player.level();
        int y0 = Math.floorDiv(level.getMinBuildHeight(), LodSection.SIZE);
        int y1 = Math.floorDiv(level.getMaxBuildHeight() - 1, LodSection.SIZE);
        IOException[] failed = new IOException[1];
        boolean hidden = SurfaceIndex.hidden(key, column -> {
            Integer known = f.get(column);
            if (known != null) {
                return known;
            }
            try {
                int floor = columnFloor(world, column, y0, y1);
                f.put(column, floor);
                return floor;
            } catch (IOException e) {
                failed[0] = e;
                return Integer.MIN_VALUE;
            }
        });
        if (failed[0] != null) {
            throw failed[0];
        }
        return hidden;
    }

    private static int columnFloor(LodWorld world, long column, int y0, int y1) throws IOException {
        List<Long> topDown = new ArrayList<>();
        for (int y = y1; y >= y0; y--) {
            long key = LodKey.pack(0, LodKey.x(column), y, LodKey.z(column));
            if (world.exists(key)) {
                topDown.add(key);
            }
        }
        if (topDown.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        return SurfaceIndex.columnFloor(topDown, world::read, world.materials(), m -> leaves(world, m));
    }

    // ---------------------------------------------------------------- sending (server tick)

    /** End of every server tick: sends what each player's budget allows, drops players who left. */
    public static void tick(MinecraftServer server) {
        StreamServer s = instance;
        if (s != null) {
            s.send();
        }
    }

    private void send() {
        long now = System.nanoTime();
        Iterator<Player> it = players.values().iterator();
        while (it.hasNext()) {
            Player p = it.next();
            ServerPlayer live = server.getPlayerList().getPlayer(p.player.getUUID());
            if (live == null || live != p.player) {
                it.remove();
                p.session = -1;
                continue;
            }
            if (p.dimension != null && live.level().dimension() != p.dimension) {
                p.session = -1; // the client says hello for the new dimension
                p.dimension = null;
                p.out.clear();
                synchronized (p.pushes) {
                    p.pushes.clear();
                }
                continue;
            }
            byte[] next;
            while ((next = p.out.peek()) != null && p.budget.tryTake(next.length, shared, now)) {
                p.out.poll();
                Services.NETWORK.sendToPlayer(live, next);
                bytes += next.length;
            }
            startPush(p);
        }
        if (now > nextNotice) {
            nextNotice = now + NOTICE_NANOS;
            changes.forEach((world, c) -> notice(world, c, now));
        }
        if (now > nextStats) {
            nextStats = now + STATS_NANOS;
            long s = sections.getAndSet(0);
            long u = unchanged.getAndSet(0);
            long i = indexes.getAndSet(0);
            long h = hiddenSent.getAndSet(0);
            long t = tilesSent.getAndSet(0);
            long c = noticesSent.getAndSet(0);
            // How long the written regions waited for their notice: the server's share of the delay
            // between a chunk generated and its LODs drawn by the clients.
            long announced = 0;
            long waitSum = 0;
            long waitMax = 0;
            for (RegionChanges rc : changes.values()) {
                long[] w = rc.takeWaits();
                announced += w[0];
                waitSum += w[1];
                waitMax = Math.max(waitMax, w[2]);
            }
            long pushes = pushed.getAndSet(0);
            long pushHidden = pushesHidden.getAndSet(0);
            long pushSum = pushWaitSum.getAndSet(0);
            long pushMax = pushWaitMax.getAndSet(0);
            if (RenderSettings.performanceLogs() && (bytes > 0 || i > 0)) {
                // The game's ping of the streamed players: the streaming must not make it wait.
                int ping = 0;
                int queued = 0;
                for (Player p : players.values()) {
                    ping = Math.max(ping, p.player.connection.latency());
                    queued = Math.max(queued, p.out.size());
                }
                Farfield.LOG.info("LOD streaming: {} players, {} KB sent in 10 s ({} sections, {} unchanged, {} indexes,"
                        + " {} sections under the surface left out, {} preview tiles, {} regions changed),"
                        + " highest ping {} ms, longest queue {} messages; {} written regions announced {} ms after"
                        + " their first write on average, {} ms at most; {} sections pushed {} ms after their write on"
                        + " average, {} ms at most ({} under the surface left out)", players.size(), bytes >> 10, s, u,
                        i, h, t, c, ping, queued, announced, announced == 0 ? 0 : waitSum / announced / 1_000_000,
                        waitMax / 1_000_000, pushes, pushes == 0 ? 0 : pushSum / pushes / 1_000_000,
                        pushMax / 1_000_000, pushHidden);
            }
            bytes = 0;
        }
    }
}
