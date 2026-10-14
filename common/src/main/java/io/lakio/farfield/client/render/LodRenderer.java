package io.lakio.farfield.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.LodWorldProvider;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.BinaryGreedyMesher;
import io.lakio.farfield.core.mesh.MeshBuffer;
import io.lakio.farfield.core.mesh.Quad;
import io.lakio.farfield.core.select.LodSelector;
import io.lakio.farfield.core.util.RangeAllocator;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.RenderToggle;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import java.util.function.LongConsumer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Draws the LOD sections of the current dimension beyond the vanilla render
 * distance (plan item E.4), on the minimal OpenGL 4.6 RHI (E.3).
 *
 * <ul>
 *   <li>Data: the {@link LodWorld} that {@link LodWorldProvider} gives for
 *       the current dimension (ADR-022).</li>
 *   <li>Selection: {@link LodSelector}, run again only when the camera moved
 *       8 blocks, meshes arrived or left, the store changed, or once a
 *       second; between runs each frame only culls the cached list against
 *       the frustum. Section existence is cached (the store's listener keeps
 *       it exact). Missing meshes are built by {@link BinaryGreedyMesher} on
 *       two low-priority threads and uploaded on the render thread under a
 *       per-frame budget; a section the server rewrites is meshed again.</li>
 *   <li>Steady frames allocate nothing (primitive collections, reused
 *       matrices and buffers), so they add no garbage-collection pauses.</li>
 *   <li>GPU: quads in one storage buffer (first-fit ranges, grown on
 *       demand), one 32-byte record per resident section, colors per block
 *       state; one {@code glMultiDrawArraysIndirect} per frame, vertex
 *       pulling in {@code lod.vert}, back faces culled. GPU time is measured
 *       with timestamp queries and logged with the render-thread time.</li>
 *   <li>Hand-off: drawn after the sky and before the terrain, with its own
 *       depth range; the depth buffer is cleared afterwards so vanilla
 *       terrain covers it, and fragments within the vanilla distance are
 *       discarded.</li>
 * </ul>
 */
public final class LodRenderer {

    private static final LodRenderer INSTANCE = new LodRenderer();

    private static final double DETAIL = 4.0;
    private static final double MAX_DISTANCE = 4096;
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

    private boolean initialized;
    private boolean disabled;
    private GlProgram program;
    private GlBuffer quads;
    private GlBuffer sectionInfo;
    private GlBuffer colorBuffer;
    private GlBuffer indirect;
    private int vao;
    private RangeAllocator arena;
    private final MaterialColors colors = new MaterialColors();
    private int uViewProj;
    private int uCameraBlock;
    private int uCameraFrac;
    private int uDepthRange;
    private int uVanillaRadius;
    private int uBrightness;
    private int uFogColor;
    private int uFog;
    private final int[] timerQueries = new int[TIMER_SLOTS * 2];
    private final boolean[] timerPending = new boolean[TIMER_SLOTS];
    private int timerSlot;

    private LodWorld world;
    private final AtomicInteger generation = new AtomicInteger();
    private final LodSelector selector = new LodSelector(LodWorld.LEVELS, DETAIL);
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
    private long selectionTime;
    private double selectedX;
    private double selectedY;
    private double selectedZ;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f cullMatrix = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();
    private final float[] matrix = new float[16];
    private IntBuffer commands = MemoryUtil.memAllocInt(4 * 1024);

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
        /** Last time the selection used it: drawn, or relied on to descend to its children. */
        long lastUsed;

        Resident(int slot) {
            this.slot = slot;
        }
    }

    private record MeshResult(int generation, long key, long[] quads) {
    }

    private LodRenderer() {
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
        uBrightness = program.uniform("u_brightness");
        uFogColor = program.uniform("u_fogColor");
        uFog = program.uniform("u_fog");
        glGenQueries(timerQueries);
        quads = new GlBuffer(INITIAL_QUADS * 8);
        arena = new RangeAllocator(INITIAL_QUADS);
        sectionInfo = new GlBuffer(4096L * SECTION_BYTES);
        colorBuffer = new GlBuffer(4096L * 4);
        indirect = new GlBuffer(1024L * 16);
        vao = glCreateVertexArrays();
        Farfield.LOG.info("LOD renderer ready on {}, OpenGL {} ({} MB of quads to start)", glGetString(GL_RENDERER),
                glGetString(GL_VERSION), INITIAL_QUADS * 8 >> 20);
        return true;
    }

    private void renderFrame(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !RenderToggle.enabled() || !init()) {
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
        uploadColors();

        Vec3 cam = camera.getPosition();
        double dx = cam.x - selectedX;
        double dy = cam.y - selectedY;
        double dz = cam.z - selectedZ;
        if (selectionDirty || dx * dx + dy * dy + dz * dz > RESELECT_DISTANCE_SQ
                || frameStart - selectionTime > RESELECT_NANOS) {
            select(level, cam, frameStart);
        }

        updateFrustum(viewRotation, projection);
        commands.clear();
        int count = 0;
        for (int i = 0, n = drawList.size(); i < n; i++) {
            long key = drawList.getLong(i);
            Resident r = resident.get(key);
            if (r == null || r.quadCount == 0 || !visible(key, cam)) {
                continue;
            }
            if (commands.remaining() < 4) {
                commands = MemoryUtil.memRealloc(commands, commands.capacity() * 2);
            }
            commands.put(r.quadCount * 6).put(1).put(0).put(r.slot);
            count++;
        }
        if (frame % 60 == 0) {
            evictUnused(frameStart);
        }
        if (count > 0) {
            draw(count, cam, viewRotation, projection, mc, skyBrightness(level, camera));
        }
        frameNanos += System.nanoTime() - frameStart;
        timedFrames++;
        if (frameStart > nextStats) {
            logStats(count);
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
                        + " render thread and {} us on the GPU, over {} frames", count, resident.size(),
                arena.used() * 8 >> 20, arena.capacity() * 8 >> 20, requested.size(), meshesUploaded, meshesEvicted,
                selections, frameNanos / 1000 / Math.max(1, timedFrames), gpuNanos / 1000 / Math.max(1, gpuFrames),
                timedFrames);
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
            long[] result;
            try {
                LodSection section = w.read(key);
                result = section == null ? new long[0] : mesh(w, section);
            } catch (IOException | RuntimeException e) {
                Farfield.LOG.debug("LOD mesh of {} failed", LodKey.toString(key), e);
                result = new long[0];
            }
            done.add(new MeshResult(gen, key, result));
        });
    }

    private long[] mesh(LodWorld w, LodSection section) {
        MeshBuffer out = meshBuffer.get();
        mesher.get().mesh(section, w.materials(), null, out);
        long[] all = new long[out.total()];
        int n = 0;
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            int c = out.count(d);
            System.arraycopy(out.quads(d), 0, all, n, c);
            n += c;
        }
        return all;
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
            meshesUploaded++;
            if (r.quadCount > 0) {
                long start = arena.allocate(r.quadCount);
                if (start < 0 && growArena(r.quadCount)) {
                    start = arena.allocate(r.quadCount);
                }
                if (start < 0) {
                    r.quadCount = 0; // out of GPU budget: drawn as empty until evictions free space
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

    private void uploadColors() {
        int first = colors.update(world.blockStates());
        if (first < 0) {
            return;
        }
        long needed = (long) colors.count() * 4;
        if (needed > colorBuffer.size()) {
            colorBuffer.grow(Math.max(needed, colorBuffer.size() * 2), (long) first * 4);
        }
        int[] all = colors.colors();
        colorBuffer.upload((long) first * 4, java.util.Arrays.copyOfRange(all, first, colors.count()));
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

    /** Vanilla's sky brightness (time of day, rain, thunder); 1 in dimensions without sky light. */
    private static float skyBrightness(ClientLevel level, Camera camera) {
        return level.dimensionType().hasSkyLight() ? level.getSkyDarken(camera.getPartialTickTime()) : 1f;
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

    private void draw(int count, Vec3 cam, Matrix4f viewRotation, Matrix4f projection, Minecraft mc,
                      float brightness) {
        long bytes = (long) count * 16;
        if (bytes > indirect.size()) {
            indirect.grow(Math.max(bytes, indirect.size() * 2), 0);
        }
        commands.flip();
        indirect.upload(0, commands);

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
        glUniform1f(uBrightness, brightness);
        glUniform4f(uFogColor, fogColor[0], fogColor[1], fogColor[2], 1f);
        glUniform2f(uFog, (float) MAX_DISTANCE * 0.6f, (float) MAX_DISTANCE);

        GlStateManager._glBindVertexArray(vao);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, quads.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, sectionInfo.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, colorBuffer.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect.id());
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull(); // lod.vert winds every face counter-clockwise from outside

        glMultiDrawArraysIndirect(GL_TRIANGLES, 0, count, 0);

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
