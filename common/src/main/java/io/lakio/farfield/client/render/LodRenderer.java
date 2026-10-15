package io.lakio.farfield.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.LodWorldProvider;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.BinaryGreedyMesher;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.MeshBuffer;
import io.lakio.farfield.core.mesh.Quad;
import io.lakio.farfield.core.select.LodSelector;
import io.lakio.farfield.core.util.RangeAllocator;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.RenderSettings;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Draws the LOD sections of the current dimension beyond the vanilla render
 * distance (plan item E.4), on the minimal OpenGL 4.6 RHI (E.3).
 *
 * <ul>
 *   <li>Data: the {@link LodWorld} that {@link LodWorldProvider} gives for
 *       the current dimension (ADR-022).</li>
 *   <li>Selection: {@link LodSelector} with a screen-space criterion: a
 *       section splits into the finer level when its voxels would exceed
 *       {@link RenderSettings#voxelPixels()} pixels on screen (from the
 *       window height and the field of view). Run again only when the camera
 *       moved 8 blocks, meshes arrived or left, the store changed, the
 *       detail changed, or once a second; between runs each frame only culls the cached list against
 *       the frustum. Section existence is cached (the store's listener keeps
 *       it exact). Missing meshes are built by {@link BinaryGreedyMesher} on
 *       two low-priority threads and uploaded on the render thread under a
 *       per-frame budget; a section the server rewrites is meshed again.</li>
 *   <li>Steady frames allocate nothing (primitive collections, reused
 *       matrices and buffers), so they add no garbage-collection pauses.</li>
 *   <li>GPU: quads in one storage buffer (first-fit ranges, grown on
 *       demand), one 32-byte record per resident section, baked face
 *       textures and their mean colors per block state and face
 *       ({@link MaterialColors}, {@link FaceTextures}), tints per biome
 *       ({@link BiomeTints}). Textures show up to
 *       {@link RenderSettings#textureDistance()} and fade into the mean
 *       colors over its last quarter. Two {@code glMultiDrawArraysIndirect}
 *       per frame: opaque quads, then translucent ones (water, glass, ice)
 *       blended with their texture coverage, without depth writes; each
 *       section stores its opaque quads first. Vertex pulling in
 *       {@code lod.vert}, back faces culled. GPU time is measured with
 *       timestamp queries and logged with the render-thread time.</li>
 *   <li>Sections with translucent voxels are meshed with their six
 *       same-level neighbors' border layers, so water spanning sections
 *       shows no inner walls; a rewritten section meshes its translucent
 *       neighbors again.</li>
 *   <li>Hand-off: drawn after the sky and before the terrain, with its own
 *       depth range; the depth buffer is cleared afterwards so vanilla
 *       terrain covers it, and fragments within the vanilla distance are
 *       discarded. Vanilla's terrain fog moves out to the LOD fog
 *       ({@code FogRendererMixin}), so near terrain does not fade into the
 *       sky just before the LODs start.</li>
 * </ul>
 */
public final class LodRenderer {

    private static final LodRenderer INSTANCE = new LodRenderer();

    private static final double MAX_DISTANCE = 4096;
    private static final float FOG_START = (float) MAX_DISTANCE * 0.6f;
    private static final float FOG_END = (float) MAX_DISTANCE;
    private static final int MAX_IN_FLIGHT = 64;
    private static final long UPLOAD_BUDGET_QUADS = 1 << 20;
    private static final long INITIAL_QUADS = 8L << 20;
    private static final long MAX_QUADS = 128L << 20;
    private static final int SECTION_BYTES = 32;
    /** A resident mesh the selection has not used for this long is freed. */
    private static final long EVICT_AFTER_NANOS = 10_000_000_000L;
    /** The selection runs again when the camera moved this far (squared blocks)... */
    private static final double RESELECT_DISTANCE_SQ = 8 * 8;
    /** ...or after this long anyway. */
    private static final long RESELECT_NANOS = 1_000_000_000L;
    private static final byte EXISTS = 1;
    private static final byte ABSENT = 2;
    private static final int TIMER_SLOTS = 4;
    private static final int[][] STEP = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private boolean initialized;
    private boolean disabled;
    private GlProgram program;
    private GlBuffer quads;
    private GlBuffer sectionInfo;
    private GlBuffer colorBuffer;
    private GlBuffer biomeBuffer;
    private GlBuffer indirect;
    private int vao;
    private RangeAllocator arena;
    /** Texture unit of the face atlas; the game tracks units 0 to 11 and uses the low ones. */
    private static final int ATLAS_UNIT = 10;
    /** Texture unit of vanilla's lightmap for the LOD shader. */
    private static final int LIGHTMAP_UNIT = 11;

    private final FaceTextures faceTextures = new FaceTextures();
    private final MaterialColors colors = new MaterialColors(faceTextures);
    private int atlas;
    private final BiomeTints tints = new BiomeTints();
    private final IntPredicate tintedMaterials = colors::tinted;
    /** Changes on every resource reload: the colors are built again. */
    private BakedModel missingModel;
    private ClientLevel colorLevel;
    private int uViewProj;
    private int uCameraBlock;
    private int uCameraFrac;
    private int uDepthRange;
    private int uVanillaRadius;
    private int uLightmap;
    private int uFogColor;
    private int uFog;
    private int uTranslucent;
    private int uDebugLevels;
    private int uAtlas;
    private int uAtlasSize;
    private int uTextureEnd;
    private final int[] timerQueries = new int[TIMER_SLOTS * 2];
    private final boolean[] timerPending = new boolean[TIMER_SLOTS];
    private int timerSlot;

    private LodWorld world;
    private final AtomicInteger generation = new AtomicInteger();
    private final LodSelector selector = new LodSelector(LodWorld.LEVELS, 4.0);
    private final Long2ObjectOpenHashMap<Resident> resident = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet requested = new LongOpenHashSet();
    private final LongOpenHashSet stale = new LongOpenHashSet();
    private final Long2ByteOpenHashMap existence = new Long2ByteOpenHashMap();
    private final ConcurrentLinkedQueue<MeshResult> done = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> rewritten = new ConcurrentLinkedQueue<>();
    private final LongConsumer listener = rewritten::add;
    private final ExecutorService meshers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "farfield-mesher");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final ThreadLocal<BinaryGreedyMesher> mesher = ThreadLocal.withInitial(BinaryGreedyMesher::new);
    private final ThreadLocal<MeshBuffer> meshBuffer = ThreadLocal.withInitial(MeshBuffer::new);
    private final ThreadLocal<int[][]> borderPlanes = ThreadLocal.withInitial(() -> new int[6][LodSection.SIZE]);
    private int[] freeSlots = new int[0];
    private int freeSlotCount;
    private int slotCount;

    // Cached selection and per-frame scratch, reused to keep steady frames allocation-free.
    private final LongArrayList drawList = new LongArrayList();
    private final LongConsumer drawSink = drawList::add;
    private final LongConsumer wantSink = this::request;
    private final LodSelector.Sections sections = new LodSelector.Sections() {
        @Override
        public boolean exists(long key) {
            return sectionExists(key);
        }

        @Override
        public boolean ready(long key) {
            // A section the selection relies on to descend is in use even when
            // only its children are drawn: freeing it would make its parent fall
            // back to the coarse level until it is meshed again (flicker).
            Resident r = resident.get(key);
            if (r != null) {
                r.lastUsed = selectionTime;
            }
            return r != null;
        }
    };
    private boolean selectionDirty = true;
    private double loggedDetail = 1e-9;
    private double pendingDetail = 1e-9;
    private long selectionTime;
    private double selectedX;
    private double selectedY;
    private double selectedZ;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f cullMatrix = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();
    private final float[] matrix = new float[16];
    private IntBuffer commands = MemoryUtil.memAllocInt(4 * 1024);
    private IntBuffer translucentCommands = MemoryUtil.memAllocInt(4 * 1024);

    // Statistics for the periodic log line.
    private long frame;
    private long frameNanos;
    private long timedFrames;
    private long gpuNanos;
    private long gpuFrames;
    private int selections;
    private int meshesUploaded;
    private int meshesEvicted;
    private long nextStats;

    private static final class Resident {
        final int slot;
        long start;
        int quadCount;
        /** The first quads of the range are opaque, the rest translucent. */
        int opaqueCount;
        /** Last time the selection used it: drawn, or relied on to descend to its children. */
        long lastUsed;

        Resident(int slot) {
            this.slot = slot;
        }
    }

    private record MeshResult(int generation, long key, long[] quads, int opaqueCount) {
    }

    private LodRenderer() {
    }

    /** Whether LODs are drawn beyond the vanilla terrain (a LOD world is attached and rendering is on). */
    public static boolean drawsBeyondVanilla() {
        LodRenderer r = INSTANCE;
        return r.world != null && r.initialized && !r.disabled && RenderSettings.enabled();
    }

    /** Distance where the LOD fog starts, in blocks. */
    public static float fogStart() {
        return FOG_START;
    }

    /** Distance where the LOD fog is opaque, in blocks. */
    public static float fogEnd() {
        return FOG_END;
    }

    /** Called from the level renderer mixin, after the sky, before the terrain. */
    public static void render(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        INSTANCE.renderFrame(camera, viewRotation, projection);
    }

    private boolean init() {
        if (initialized) {
            return !disabled;
        }
        initialized = true;
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.OpenGL46) {
            disabled = true;
            Farfield.LOG.warn("LOD rendering disabled: OpenGL 4.6 is not available ({})",
                    glGetString(GL_VERSION));
            return false;
        }
        try {
            program = new GlProgram("lod.vert", "lod.frag");
        } catch (RuntimeException e) {
            disabled = true;
            Farfield.LOG.error("LOD rendering disabled: shader setup failed", e);
            return false;
        }
        uViewProj = program.uniform("u_viewProj");
        uCameraBlock = program.uniform("u_cameraBlock");
        uCameraFrac = program.uniform("u_cameraFrac");
        uDepthRange = program.uniform("u_depthRange");
        uVanillaRadius = program.uniform("u_vanillaRadius");
        uLightmap = program.uniform("u_lightmap");
        uFogColor = program.uniform("u_fogColor");
        uFog = program.uniform("u_fog");
        uTranslucent = program.uniform("u_translucent");
        uDebugLevels = program.uniform("u_debugLevels");
        uAtlas = program.uniform("u_atlas");
        uAtlasSize = program.uniform("u_atlasSize");
        uTextureEnd = program.uniform("u_textureEnd");
        glGenQueries(timerQueries);
        quads = new GlBuffer(INITIAL_QUADS * 8);
        arena = new RangeAllocator(INITIAL_QUADS);
        sectionInfo = new GlBuffer(4096L * SECTION_BYTES);
        colorBuffer = new GlBuffer(4096L * MaterialColors.STRIDE * 4);
        biomeBuffer = new GlBuffer(256L * BiomeTints.KINDS * 4);
        indirect = new GlBuffer(1024L * 16);
        vao = glCreateVertexArrays();
        Farfield.LOG.info("LOD renderer ready on {}, OpenGL {} ({} MB of quads to start)", glGetString(GL_RENDERER),
                glGetString(GL_VERSION), INITIAL_QUADS * 8 >> 20);
        return true;
    }

    private void renderFrame(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !RenderSettings.enabled() || !init()) {
            return;
        }
        LodWorld current = LodWorldProvider.current(mc, level);
        if (current != world) {
            switchWorld(current);
        }
        if (world == null) {
            return;
        }
        long frameStart = System.nanoTime();
        frame++;
        readGpuTimers();
        applyRewrites();
        uploadMeshes();
        uploadColors(mc, level);

        Vec3 cam = camera.getPosition();
        double dx = cam.x - selectedX;
        double dy = cam.y - selectedY;
        double dz = cam.z - selectedZ;
        updateDetail(mc, projection);
        if (selectionDirty || dx * dx + dy * dy + dz * dz > RESELECT_DISTANCE_SQ
                || frameStart - selectionTime > RESELECT_NANOS) {
            select(level, cam, frameStart);
        }

        updateFrustum(viewRotation, projection);
        commands.clear();
        translucentCommands.clear();
        int count = 0;
        int opaqueDraws = 0;
        int translucentDraws = 0;
        for (int i = 0, n = drawList.size(); i < n; i++) {
            long key = drawList.getLong(i);
            Resident r = resident.get(key);
            if (r == null || r.quadCount == 0 || !visible(key, cam)) {
                continue;
            }
            count++;
            if (r.opaqueCount > 0) {
                if (commands.remaining() < 4) {
                    commands = MemoryUtil.memRealloc(commands, commands.capacity() * 2);
                }
                commands.put(r.opaqueCount * 6).put(1).put(0).put(r.slot);
                opaqueDraws++;
            }
            int translucent = r.quadCount - r.opaqueCount;
            if (translucent > 0) {
                if (translucentCommands.remaining() < 4) {
                    translucentCommands = MemoryUtil.memRealloc(translucentCommands,
                            translucentCommands.capacity() * 2);
                }
                // gl_VertexID starts at "first": the shader reads the quads after the opaque ones.
                translucentCommands.put(translucent * 6).put(1).put(r.opaqueCount * 6).put(r.slot);
                translucentDraws++;
            }
        }
        if (frame % 60 == 0) {
            evictUnused(frameStart);
        }
        if (count > 0) {
            draw(opaqueDraws, translucentDraws, cam, viewRotation, projection, mc);
        }
        frameNanos += System.nanoTime() - frameStart;
        timedFrames++;
        if (frameStart > nextStats) {
            logStats(count);
        }
    }

    /**
     * Screen-space level of detail: a section of S blocks at distance D
     * covers about {@code S * focal / D} pixels, so its 32 voxels stay under
     * {@code voxelPixels} each while {@code D > S * focal / (32 * voxelPixels)}.
     */
    private void updateDetail(Minecraft mc, Matrix4f projection) {
        float focal = mc.getWindow().getHeight() * Math.abs(projection.m11()) / 2;
        if (!Float.isFinite(focal) || focal <= 0) {
            return; // degenerate projection (seen on the first frame): keep the last detail
        }
        double detail = Math.max(1, focal / (LodSection.SIZE * RenderSettings.voxelPixels()));
        // A value must hold for two frames: the first frame's projection can be off
        // (seen at 20 times the right detail), which would request level-0 meshes for nothing.
        if (Math.abs(detail - pendingDetail) > pendingDetail * 0.02) {
            pendingDetail = detail;
            return;
        }
        double current = selector.detail();
        if (Math.abs(detail - current) > current * 0.02) {
            selector.setDetail(detail);
            selectionDirty = true;
            if (Math.abs(detail - loggedDetail) > loggedDetail * 0.1) {
                loggedDetail = detail;
                Farfield.LOG.info("LOD detail: {} px per voxel, focal {} px (window height {}, projection m11 {}),"
                                + " sections split within {} section sizes", RenderSettings.voxelPixels(), focal,
                        mc.getWindow().getHeight(), projection.m11(), String.format("%.1f", detail));
            }
        }
    }

    private void select(ClientLevel level, Vec3 cam, long now) {
        selectionTime = now;
        selectedX = cam.x;
        selectedY = cam.y;
        selectedZ = cam.z;
        selectionDirty = false;
        selections++;
        drawList.clear();
        selector.select(cam.x, cam.y, cam.z, level.getMinBuildHeight(), level.getMaxBuildHeight(), MAX_DISTANCE,
                sections, drawSink, wantSink);
        for (int i = 0, n = drawList.size(); i < n; i++) {
            Resident r = resident.get(drawList.getLong(i));
            if (r != null) {
                r.lastUsed = now;
            }
        }
    }

    private boolean sectionExists(long key) {
        byte known = existence.get(key);
        if (known != 0) {
            return known == EXISTS;
        }
        boolean exists;
        try {
            exists = world.exists(key);
        } catch (IOException e) {
            exists = false;
        }
        existence.put(key, exists ? EXISTS : ABSENT);
        return exists;
    }

    private void logStats(int count) {
        nextStats = System.nanoTime() + 30_000_000_000L;
        Farfield.LOG.info("LOD renderer: {} sections drawn, {} resident, {} MB of {} MB quads used, {} meshing,"
                        + " {} meshes uploaded and {} freed, {} selections since last line; per frame {} us on the"
                        + " render thread and {} us on the GPU, over {} frames; detail {}", count, resident.size(),
                arena.used() * 8 >> 20, arena.capacity() * 8 >> 20, requested.size(), meshesUploaded, meshesEvicted,
                selections, frameNanos / 1000 / Math.max(1, timedFrames), gpuNanos / 1000 / Math.max(1, gpuFrames),
                timedFrames, String.format("%.1f", selector.detail()));
        frameNanos = 0;
        timedFrames = 0;
        gpuNanos = 0;
        gpuFrames = 0;
        selections = 0;
        meshesUploaded = 0;
        meshesEvicted = 0;
    }

    private void switchWorld(LodWorld next) {
        if (world != null) {
            world.removeListener(listener);
        }
        generation.incrementAndGet();
        for (Resident r : resident.values()) {
            if (r.quadCount > 0) {
                arena.release(r.start, r.quadCount);
            }
        }
        resident.clear();
        requested.clear();
        stale.clear();
        existence.clear();
        drawList.clear();
        done.clear();
        rewritten.clear();
        colors.reset();
        tints.reset();
        freeSlotCount = 0;
        slotCount = 0;
        selectionDirty = true;
        world = next;
        if (world != null) {
            world.addListener(listener);
            Farfield.LOG.info("LOD renderer attached to a LOD store");
        }
    }

    private void request(long key) {
        if (requested.size() >= MAX_IN_FLIGHT || !requested.add(key)) {
            return;
        }
        LodWorld w = world;
        int gen = generation.get();
        meshers.execute(() -> {
            MeshResult result;
            try {
                LodSection section = w.read(key);
                result = section == null ? new MeshResult(gen, key, new long[0], 0) : mesh(w, gen, key, section);
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.debug("LOD mesh of {} failed", LodKey.toString(key), e);
                result = new MeshResult(gen, key, new long[0], 0);
            }
            done.add(result);
        });
    }

    /** Meshes a section: opaque quads first, then translucent ones. */
    private MeshResult mesh(LodWorld w, int gen, long key, LodSection section) throws IOException {
        MaterialClassifier classes = w.materials();
        int[][] solid = hasTranslucent(section, classes) ? neighborSolid(w, key, classes) : null;
        MeshBuffer out = meshBuffer.get();
        mesher.get().mesh(section, classes, tintedMaterials, null, solid, out);
        long[] all = new long[out.total()];
        int opaque = 0;
        int back = all.length;
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            long[] quads = out.quads(d);
            for (int i = 0, c = out.count(d); i < c; i++) {
                long q = quads[i];
                if (classes.classOf(Quad.material(q)) == MaterialClassifier.TRANSLUCENT) {
                    all[--back] = q;
                } else {
                    all[opaque++] = q;
                }
            }
        }
        return new MeshResult(gen, key, all, opaque);
    }

    private static boolean hasTranslucent(LodSection section, MaterialClassifier classes) {
        for (int i = 0, n = section.paletteSize(); i < n; i++) {
            if (classes.classOf(section.paletteEntry(i)) == MaterialClassifier.TRANSLUCENT) {
                return true;
            }
        }
        return false;
    }

    /** Solid border layers of the six same-level neighbors (null entries where none is stored). */
    private int[][] neighborSolid(LodWorld w, long key, MaterialClassifier classes) throws IOException {
        int[][] planes = borderPlanes.get();
        int[][] result = new int[6][];
        int level = LodKey.level(key);
        for (int d = 0; d < 6; d++) {
            int y = LodKey.y(key) + STEP[d][1];
            if (y < LodKey.Y_MIN || y > LodKey.Y_MAX) {
                continue;
            }
            long neighbor = LodKey.pack(level, LodKey.x(key) + STEP[d][0], y, LodKey.z(key) + STEP[d][2]);
            LodSection section = w.exists(neighbor) ? w.read(neighbor) : null;
            if (section != null) {
                result[d] = BinaryGreedyMesher.borderPlane(section, d, classes, true, planes[d]);
            }
        }
        return result;
    }

    /**
     * Sections the store wrote: they exist now (the selection must run again,
     * it may descend further), and resident ones are meshed again; the old
     * mesh is drawn until the new one arrives. A section whose mesh is being
     * built waits for that job, which may have read the old data.
     */
    private void applyRewrites() {
        Long key;
        while ((key = rewritten.poll()) != null) {
            if (existence.put(key.longValue(), EXISTS) != EXISTS) {
                selectionDirty = true;
            }
            if (resident.containsKey(key.longValue())) {
                stale.add(key.longValue());
            }
            // Translucent faces of the neighbors were culled against the old data.
            long k = key;
            for (int d = 0; d < 6; d++) {
                int y = LodKey.y(k) + STEP[d][1];
                if (y < LodKey.Y_MIN || y > LodKey.Y_MAX) {
                    continue;
                }
                long neighbor = LodKey.pack(LodKey.level(k), LodKey.x(k) + STEP[d][0], y, LodKey.z(k) + STEP[d][2]);
                Resident r = resident.get(neighbor);
                if (r != null && r.quadCount > r.opaqueCount) {
                    stale.add(neighbor);
                }
            }
        }
        LongIterator it = stale.iterator();
        while (it.hasNext() && requested.size() < MAX_IN_FLIGHT) {
            long k = it.nextLong();
            if (!resident.containsKey(k)) {
                it.remove();
            } else if (!requested.contains(k)) {
                it.remove();
                request(k);
            }
        }
    }

    private void uploadMeshes() {
        long budget = UPLOAD_BUDGET_QUADS;
        MeshResult m;
        while (budget > 0 && (m = done.poll()) != null) {
            requested.remove(m.key);
            if (m.generation != generation.get()) {
                continue;
            }
            Resident r = resident.get(m.key);
            if (r == null) {
                r = new Resident(allocateSlot());
                r.lastUsed = System.nanoTime();
                resident.put(m.key, r);
                selectionDirty = true; // a parent may now descend
            } else if (r.quadCount > 0) {
                arena.release(r.start, r.quadCount);
            }
            r.quadCount = m.quads.length;
            r.opaqueCount = m.opaqueCount;
            meshesUploaded++;
            if (r.quadCount > 0) {
                long start = arena.allocate(r.quadCount);
                if (start < 0 && growArena(r.quadCount)) {
                    start = arena.allocate(r.quadCount);
                }
                if (start < 0) {
                    r.quadCount = 0; // out of GPU budget: drawn as empty until evictions free space
                    r.opaqueCount = 0;
                } else {
                    r.start = start;
                    quads.upload(start * 8, m.quads, m.quads.length);
                    budget -= m.quads.length;
                }
            }
            writeSection(m.key, r);
        }
    }

    private boolean growArena(long needed) {
        long capacity = arena.capacity();
        long target = Math.max(capacity * 2, capacity + needed);
        if (target > MAX_QUADS) {
            return false;
        }
        quads.grow(target * 8, capacity * 8);
        arena.grow(target);
        Farfield.LOG.info("LOD quad buffer grown to {} MB", target * 8 >> 20);
        return true;
    }

    private int allocateSlot() {
        if (freeSlotCount > 0) {
            return freeSlots[--freeSlotCount];
        }
        int slot = slotCount++;
        long needed = (long) slotCount * SECTION_BYTES;
        if (needed > sectionInfo.size()) {
            sectionInfo.grow(sectionInfo.size() * 2, sectionInfo.size());
        }
        return slot;
    }

    private void releaseSlot(int slot) {
        if (freeSlots.length == freeSlotCount) {
            freeSlots = java.util.Arrays.copyOf(freeSlots, Math.max(64, freeSlotCount * 2));
        }
        freeSlots[freeSlotCount++] = slot;
    }

    private void writeSection(long key, Resident r) {
        int level = LodKey.level(key);
        int size = LodSection.SIZE << level;
        sectionInfo.upload((long) r.slot * SECTION_BYTES, new int[]{
                LodKey.x(key) * size, LodKey.y(key) * size, LodKey.z(key) * size, level,
                (int) r.start, r.quadCount, 0, 0});
    }

    private void evictUnused(long now) {
        ObjectIterator<Long2ObjectOpenHashMap.Entry<Resident>> it = resident.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Resident r = it.next().getValue();
            if (now - r.lastUsed > EVICT_AFTER_NANOS) {
                if (r.quadCount > 0) {
                    arena.release(r.start, r.quadCount);
                }
                releaseSlot(r.slot);
                meshesEvicted++;
                it.remove();
                selectionDirty = true;
            }
        }
    }

    /**
     * Builds and uploads the colors of new block states and the biome tints.
     * A resource reload or another level builds everything again, and meshes
     * again what is resident (which materials depend on the biome may change).
     */
    private void uploadColors(Minecraft mc, ClientLevel level) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        if (missing != missingModel || level != colorLevel) {
            if (missingModel != null) {
                stale.addAll(resident.keySet());
            }
            missingModel = missing;
            colorLevel = level;
            colors.reset();
            tints.reset();
        }
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Biome plains = biomes.get(Biomes.PLAINS);
        if (plains == null) {
            plains = biomes.stream().findFirst().orElse(null);
            if (plains == null) {
                return;
            }
        }
        int first = colors.update(world.blockStates(), world.materials(), tints, plains);
        if (first >= 0) {
            int stride = MaterialColors.STRIDE;
            long needed = (long) colors.count() * stride * 4;
            if (needed > colorBuffer.size()) {
                colorBuffer.grow(Math.max(needed, colorBuffer.size() * 2), (long) first * stride * 4);
            }
            colorBuffer.upload((long) first * stride * 4,
                    java.util.Arrays.copyOfRange(colors.colors(), first * stride, colors.count() * stride));
        }
        atlas = faceTextures.upload();
        if (tints.update(world.biomes(), biomes, plains)) {
            long needed = (long) tints.length() * 4;
            if (needed > biomeBuffer.size()) {
                biomeBuffer.grow(Math.max(needed, biomeBuffer.size() * 2), 0);
            }
            biomeBuffer.upload(0, java.util.Arrays.copyOf(tints.table(), tints.length()));
        }
    }

    private void updateFrustum(Matrix4f viewRotation, Matrix4f projection) {
        float m11 = Math.abs(projection.m11());
        float m00 = Math.abs(projection.m00());
        float fovY = (float) (2 * Math.atan(1 / m11)) * 1.1f;
        cullMatrix.setPerspective(fovY, m11 / m00, 1f, (float) MAX_DISTANCE * 2).mul(viewRotation);
        frustum.set(cullMatrix);
    }

    private boolean visible(long key, Vec3 cam) {
        int size = LodSection.SIZE << LodKey.level(key);
        float x = (float) (LodKey.x(key) * (double) size - cam.x);
        float y = (float) (LodKey.y(key) * (double) size - cam.y);
        float z = (float) (LodKey.z(key) * (double) size - cam.z);
        return frustum.testAab(x, y, z, x + size, y + size, z + size);
    }

    /**
     * Vanilla's lightmap texture (updated by the game renderer before the
     * level renders): binding it as the light layer stores its id in shader
     * texture slot 2, as the terrain shaders expect.
     */
    private static int lightmap(Minecraft mc) {
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        return RenderSystem.getShaderTexture(2);
    }

    /** Reads the GPU timestamps of earlier frames that are ready, without waiting. */
    private void readGpuTimers() {
        for (int slot = 0; slot < TIMER_SLOTS; slot++) {
            if (!timerPending[slot]
                    || glGetQueryObjecti(timerQueries[slot * 2 + 1], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) {
                continue;
            }
            long begin = glGetQueryObjectui64(timerQueries[slot * 2], GL_QUERY_RESULT);
            long end = glGetQueryObjectui64(timerQueries[slot * 2 + 1], GL_QUERY_RESULT);
            gpuNanos += end - begin;
            gpuFrames++;
            timerPending[slot] = false;
        }
    }

    private void draw(int opaqueDraws, int translucentDraws, Vec3 cam, Matrix4f viewRotation, Matrix4f projection,
                      Minecraft mc) {
        long opaqueBytes = (long) opaqueDraws * 16;
        long bytes = opaqueBytes + (long) translucentDraws * 16;
        if (bytes > indirect.size()) {
            indirect.grow(Math.max(bytes, indirect.size() * 2), 0);
        }
        commands.flip();
        indirect.upload(0, commands);
        translucentCommands.flip();
        indirect.upload(opaqueBytes, translucentCommands);

        int blockX = (int) Math.floor(cam.x);
        int blockY = (int) Math.floor(cam.y);
        int blockZ = (int) Math.floor(cam.z);
        viewProj.set(projection).mul(viewRotation);
        float[] fogColor = RenderSystem.getShaderFogColor();
        float vanilla = Math.max(0, (mc.options.getEffectiveRenderDistance() - 1) * 16f);

        // Timestamps rather than a time-elapsed query: the game may have one open.
        int slot = timerSlot;
        boolean timed = !timerPending[slot];
        if (timed) {
            glQueryCounter(timerQueries[slot * 2], GL_TIMESTAMP);
        }

        GlStateManager._glUseProgram(program.id());
        glUniformMatrix4fv(uViewProj, false, viewProj.get(matrix));
        glUniform3i(uCameraBlock, blockX, blockY, blockZ);
        glUniform3f(uCameraFrac, (float) (cam.x - blockX), (float) (cam.y - blockY), (float) (cam.z - blockZ));
        glUniform2f(uDepthRange, 8f, (float) MAX_DISTANCE * 2);
        glUniform1f(uVanillaRadius, vanilla);
        glUniform1i(uLightmap, LIGHTMAP_UNIT);
        glUniform4f(uFogColor, fogColor[0], fogColor[1], fogColor[2], 1f);
        glUniform2f(uFog, FOG_START, FOG_END);
        glUniform1i(uTranslucent, 0);
        glUniform1i(uDebugLevels, RenderSettings.debugLevels() ? 1 : 0);
        glUniform1i(uAtlas, ATLAS_UNIT);
        glUniform2f(uAtlasSize, faceTextures.width(), Math.max(1, faceTextures.height()));
        int textureDistance = RenderSettings.textureDistance();
        glUniform1f(uTextureEnd, atlas == 0 ? 0
                : textureDistance == RenderSettings.ALL_DISTANCES ? Float.MAX_VALUE : textureDistance);
        int previousUnit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL_TEXTURE0 + ATLAS_UNIT);
        GlStateManager._bindTexture(atlas);
        GlStateManager._activeTexture(GL_TEXTURE0 + LIGHTMAP_UNIT);
        GlStateManager._bindTexture(lightmap(mc));
        GlStateManager._activeTexture(previousUnit);

        GlStateManager._glBindVertexArray(vao);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, quads.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, sectionInfo.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, colorBuffer.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, biomeBuffer.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect.id());
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull(); // lod.vert winds every face counter-clockwise from outside

        if (opaqueDraws > 0) {
            glMultiDrawArraysIndirect(GL_TRIANGLES, 0, opaqueDraws, 0);
        }
        if (translucentDraws > 0) {
            glUniform1i(uTranslucent, 1);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(false);
            glMultiDrawArraysIndirect(GL_TRIANGLES, opaqueBytes, translucentDraws, 0);
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }

        if (timed) {
            glQueryCounter(timerQueries[slot * 2 + 1], GL_TIMESTAMP);
            timerPending[slot] = true;
            timerSlot = (slot + 1) % TIMER_SLOTS;
        }
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        GlStateManager._glUseProgram(0);
        // Vanilla terrain draws next with its own depth range: start it clean.
        RenderSystem.clear(GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }
}
